package app.warden.ui

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.warden.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppInfo(val packageName: String, val label: String)

/**
 * Target-app selection. CONTRACT: the main screen only constructs this and passes it to
 * [TargetsSection].
 */
class TargetsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private val ownPackage = app.packageName

    // Before the flows below: their first values can arrive while this is still being built.
    private val pm = app.packageManager

    val targets: StateFlow<Set<String>> = store.config
        .map { it.targetPackages }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Bumped when installed apps change, so the watched apps' labels are resolved again. */
    private val appsChanged = MutableStateFlow(0)

    /**
     * Each watched app with its launcher label, or null when it isn't installed (or can't be
     * opened). Resolved app by app, so the chips never wait for the full list the picker needs:
     * hundreds of labels, seconds on a low-end phone. Null until the watch list has been read.
     */
    val targetLabels: StateFlow<Map<String, String?>?> =
        combine(store.config.map { it.targetPackages }.distinctUntilChanged(), appsChanged) { pkgs, _ -> pkgs }
            .map { pkgs -> withContext(Dispatchers.IO) { pkgs.associateWith(::launcherLabel) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True once the watched apps and their labels are ready; the main screen's first frame waits for it. */
    val targetsLoaded: StateFlow<Boolean> = targetLabels
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Launchable apps (excluding Warden), distinct by package, sorted by lowercase label. */
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val icons = TargetsIconCache(app.packageManager)

    private val writeLock = Mutex()

    /** Package-change counter as of the last load; null until the first load has read it. */
    @Volatile
    private var packagesSeq: Int? = null
    private var loadJob: Job? = null

    init {
        loadApps(readSeq = true)
    }

    /**
     * Reloads the app list if anything was installed, removed or updated since it was loaded. The
     * monitor keeps this process, and so this ViewModel, alive for days: without this an app
     * installed while Warden sat in the background never showed up in the picker, and a removed
     * target kept looking installed. One cheap binder call when nothing changed. Main thread.
     */
    fun refreshIfAppsChanged() {
        val seq = packagesSeq ?: return // the first load is still running
        val changed = runCatching { pm.getChangedPackages(seq) }.getOrNull() ?: return
        packagesSeq = changed.sequenceNumber
        if (changed.packageNames.any { it != ownPackage }) {
            appsChanged.value++
            loadApps(readSeq = false)
        }
    }

    private fun loadApps(readSeq: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Read before the list, so a change landing mid-load is still caught next time.
            if (readSeq) packagesSeq = runCatching { pm.getChangedPackages(0)?.sequenceNumber }.getOrNull() ?: 0
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val all = try {
                pm.queryIntentActivities(main, 0)
                    .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            // A newer load started meanwhile; it publishes instead.
            ensureActive()
            _installedApps.value = all.filter { it.packageName != ownPackage }
            _isLoading.value = false
        }
    }

    /** The app's launcher label, as the full list has it; null if it isn't installed or can't be opened. */
    private fun launcherLabel(pkg: String): String? = runCatching {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        pm.queryIntentActivities(main, 0).firstOrNull()?.loadLabel(pm)?.toString()
    }.getOrNull()

    /**
     * Serialized read-modify-write against the latest stored value, so quick consecutive taps
     * can never overwrite each other with a stale snapshot.
     */
    fun setTarget(pkg: String, on: Boolean) {
        viewModelScope.launch {
            writeLock.withLock {
                val current = store.config.first().targetPackages
                val next = if (on) current + pkg else current - pkg
                if (next != current) store.setTargets(next)
            }
        }
    }

    override fun onCleared() {
        icons.clear()
    }
}
