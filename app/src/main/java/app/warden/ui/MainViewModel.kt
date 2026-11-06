package app.warden.ui

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.warden.ActivityLog
import app.warden.AppConfig
import app.warden.ConfigStore
import app.warden.ForegroundMonitorService
import app.warden.MonitorStatus
import app.warden.Recovery
import app.warden.PauseLength
import app.warden.SelfTest
import app.warden.SelfTestResult
import app.warden.SessionRecord
import app.warden.SettingsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class A11yService(val id: String, val label: String)

/** What the device reports right now for each managed setting. Read off the main thread. */
data class DeviceValues(
    val usbOn: Boolean,
    val devOn: Boolean,
    val a11yMasterOn: Boolean,
    val a11yEnabledServices: Set<ComponentName>,
) {
    /**
     * True when accessibility is on and any service [config] would switch off is enabled: any
     * service at all in "all" mode, otherwise any of the chosen components.
     */
    fun isA11yOn(config: AppConfig): Boolean {
        if (!a11yMasterOn) return false
        if (config.accessibilityAll) return a11yEnabledServices.isNotEmpty()
        return config.accessibilityComponents
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it in a11yEnabledServices }
    }
}

/** Self-test dialog state. Lives in the ViewModel so rotation cannot lose a running test. */
sealed interface SelfTestUiState {
    data object Idle : SelfTestUiState
    data object Confirm : SelfTestUiState
    data object Running : SelfTestUiState
    data class Result(val result: SelfTestResult) : SelfTestUiState

    /** The test threw. Settings may have been left in an unexpected state. */
    data object Failed : SelfTestUiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private val activityLog = ActivityLog(app)

    // Set as each first value arrives; see [firstLoadDone]. Declared before the flows that set them.
    private val configRead = MutableStateFlow(false)
    private val recordsRead = MutableStateFlow(false)

    val config = store.config
        .onEach { configRead.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig())

    /** Null until loaded, so the one-time self-test hint never flashes for users who've seen it. */
    val selfTestSuggested: StateFlow<Boolean?> =
        store.selfTestSuggested.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markSelfTestSuggested() = viewModelScope.launch { store.setSelfTestSuggested() }

    val monitorState = MonitorStatus.state
    val recoveryNotice = MonitorStatus.recoveryNotice

    /** Recent sessions, newest first. */
    val records: StateFlow<List<SessionRecord>> = activityLog.records
        .onEach { recordsRead.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Null until the installed accessibility services have been loaded. */
    private val _a11yServices = MutableStateFlow<List<A11yService>?>(null)
    val a11yServices = _a11yServices.asStateFlow()

    /** Null until the first read finishes. */
    private val _currentValues = MutableStateFlow<DeviceValues?>(null)
    val currentValues = _currentValues.asStateFlow()

    /**
     * True once what the main screen opens on has been read: saved config, the activity log and the
     * live device values. Until then [config] and [records] hold defaults (monitor off, no log).
     */
    val firstLoadDone: StateFlow<Boolean> =
        combine(configRead, recordsRead, _currentValues) { c, r, v -> c && r && v != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selfTest = MutableStateFlow<SelfTestUiState>(SelfTestUiState.Idle)
    val selfTest = _selfTest.asStateFlow()

    /**
     * Called when this app comes to the foreground. Our app being foreground means no target is
     * active, so nothing should be disabled right now. If the monitor service died while a target
     * was open, its in-memory restore never ran - so when the service is NOT running we restore any
     * stuck settings and relaunch it (which then re-reconciles from a clean slate). When the service
     * IS running we leave it alone: it owns the state and restores on its own next poll.
     */
    fun reconcileOnResume() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        if (isMonitorRunning(ctx)) return@launch
        Recovery.restorePending(ctx, store)
        // From the store, not the shared state: on a cold start that may still hold the default.
        if (store.config.first().monitorEnabled) startMonitor(ctx)
    }

    /**
     * Android 12+ throws if the app left the foreground while the caller was suspended; nothing to
     * do then, the next resume tries again. An uncaught throw here would take the process down.
     */
    private fun startMonitor(ctx: Context) {
        runCatching { ForegroundMonitorService.start(ctx) }
    }

    private fun isMonitorRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION") // Deprecated for third-party services; still returns our own.
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ForegroundMonitorService::class.java.name }
    }

    /** Re-reads the installed accessibility services and the live device values. */
    fun refreshDeviceState() = viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        _a11yServices.value = loadAccessibilityServices(ctx)
        _currentValues.value = readDeviceValues(ctx)
    }

    private fun loadAccessibilityServices(ctx: Context): List<A11yService> {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val pm = ctx.packageManager
        return am.installedAccessibilityServiceList
            .map {
                val info = it.resolveInfo.serviceInfo
                A11yService(
                    id = ComponentName(info.packageName, info.name).flattenToString(),
                    label = it.resolveInfo.loadLabel(pm).toString(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun readDeviceValues(ctx: Context): DeviceValues = DeviceValues(
        usbOn = SettingsController.readAdbEnabled(ctx) != 0 || SettingsController.readAdbWifiEnabled(ctx) != 0,
        devOn = SettingsController.readDevEnabled(ctx) != 0,
        a11yMasterOn = SettingsController.readA11yEnabled(ctx) != 0,
        a11yEnabledServices = SettingsController.readA11yServices(ctx)
            .split(':')
            .filter { it.isNotBlank() }
            .mapNotNull(ComponentName::unflattenFromString)
            .toSet(),
    )

    fun setAccessibilityAll(all: Boolean) = viewModelScope.launch { store.setAccessibilityAll(all) }

    fun setAccessibilityComponent(id: String, selected: Boolean) = viewModelScope.launch {
        store.setAccessibilityComponent(id, selected)
    }

    fun setManageUsb(v: Boolean) = viewModelScope.launch { store.setManageUsb(v) }
    fun setManageDev(v: Boolean) = viewModelScope.launch { store.setManageDev(v) }
    fun setManageA11y(v: Boolean) = viewModelScope.launch { store.setManageA11y(v) }

    fun setMonitor(enabled: Boolean) = viewModelScope.launch {
        store.setMonitorEnabled(enabled)
        val ctx = getApplication<Application>()
        if (enabled) startMonitor(ctx) else ForegroundMonitorService.stop(ctx)
    }

    /** Config is the single source of truth: the service reacts to this value. */
    fun pause(length: PauseLength) = viewModelScope.launch {
        store.setPausedUntil(length.pausedUntil(System.currentTimeMillis()))
    }

    fun resumeNow() = viewModelScope.launch { store.setPausedUntil(0L) }

    /** Clears the log and returns what was there, so the caller can offer Undo. */
    fun clearActivity(): List<SessionRecord> {
        val cleared = records.value
        viewModelScope.launch { activityLog.clear() }
        return cleared
    }

    fun restoreActivity(previous: List<SessionRecord>) = viewModelScope.launch {
        activityLog.restore(previous)
    }

    // ---- self-test ----

    fun requestSelfTest() {
        if (_selfTest.value == SelfTestUiState.Idle) _selfTest.value = SelfTestUiState.Confirm
    }

    fun runSelfTest() {
        if (_selfTest.value != SelfTestUiState.Confirm) return
        _selfTest.value = SelfTestUiState.Running
        markSelfTestSuggested()
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            _selfTest.value = try {
                SelfTestUiState.Result(SelfTest.run(ctx, store))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SelfTestUiState.Failed
            }
            _currentValues.value = readDeviceValues(ctx)
        }
    }

    /** Closes the dialog. Ignored while the test is running. */
    fun dismissSelfTest() {
        if (_selfTest.value != SelfTestUiState.Running) _selfTest.value = SelfTestUiState.Idle
    }

}
