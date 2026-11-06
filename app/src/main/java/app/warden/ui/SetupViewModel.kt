package app.warden.ui

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import app.warden.R
import app.warden.SettingsController
import app.warden.ShizukuGrant
import app.warden.ShizukuStatus
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Setup / permission state. CONTRACT: [SetupState] fields, [requiredComplete], [state] and
 * [refresh] are consumed by the main screen and must keep these names and meanings.
 */
data class SetupState(
    val usageAccess: Boolean = false,
    val writeSecureSettings: Boolean = false,
    /**
     * Exempt from battery optimization AND not background-restricted (recommended, not required).
     * One UI shows this combination as "Unrestricted".
     */
    val batteryUnrestricted: Boolean = false,
    /** The user set Warden's battery usage to "Restricted"; the exemption prompt can't undo that. */
    val backgroundRestricted: Boolean = false,
    /**
     * Android 11+ won't remove Warden's permissions or pause it after months without being opened.
     * Always true below Android 11, where that feature doesn't exist.
     */
    val keepsPermissionsWhenUnused: Boolean = true,
    /** Notifications can be posted (recommended, not required). */
    val notificationsAllowed: Boolean = false,
    /** Developer options currently on (only used to tailor the adb grant instructions). */
    val developerOptionsEnabled: Boolean = false,
    /** The optional no-computer grant route. */
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
) {
    /** The monitor cannot work without these. */
    val requiredComplete: Boolean get() = usageAccess && writeSecureSettings
}

/** Progress of a grant through Shizuku. [error] is a string resource to show under the button. */
data class ShizukuGrantState(val busy: Boolean = false, @StringRes val error: Int? = null)

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val _shizukuGrant = MutableStateFlow(ShizukuGrantState())
    val shizukuGrant: StateFlow<ShizukuGrantState> = _shizukuGrant.asStateFlow()

    // Shizuku delivers its binder asynchronously and can stop at any time; re-read on both.
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != ShizukuGrant.PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        refresh()
        if (result == PackageManager.PERMISSION_GRANTED) {
            grantWithShizuku()
        } else {
            _shizukuGrant.value = ShizukuGrantState(error = R.string.setup_shizuku_denied)
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    /**
     * One button for the whole route: asks Shizuku for permission first if needed (the result
     * listener continues from there), then runs the grant. The setup step updates via [refresh].
     */
    fun grantWithShizuku() {
        if (_shizukuGrant.value.busy) return
        val ctx = getApplication<Application>()
        when (ShizukuGrant.status(ctx)) {
            ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING -> refresh()
            ShizukuStatus.NEEDS_PERMISSION ->
                if (ShizukuGrant.permissionDenied()) {
                    _shizukuGrant.value = ShizukuGrantState(error = R.string.setup_shizuku_denied)
                } else {
                    _shizukuGrant.value = ShizukuGrantState()
                    if (!ShizukuGrant.requestPermission()) refresh()
                }
            ShizukuStatus.READY -> viewModelScope.launch {
                _shizukuGrant.value = ShizukuGrantState(busy = true)
                val failure = ShizukuGrant.grant(ctx)
                if (failure != null) Log.w(TAG, "Shizuku grant failed: $failure")
                refresh()
                _shizukuGrant.value = ShizukuGrantState(
                    error = if (_state.value.writeSecureSettings) null else R.string.setup_shizuku_failed,
                )
            }
        }
    }

    /** Re-read every permission. Called on every resume. */
    fun refresh() {
        val ctx = getApplication<Application>()
        val restricted = isBackgroundRestricted(ctx)
        _state.value = SetupState(
            usageAccess = hasUsageAccess(ctx),
            writeSecureSettings = SettingsController.hasWriteSecureSettings(ctx),
            batteryUnrestricted = isIgnoringBatteryOptimizations(ctx) && !restricted,
            backgroundRestricted = restricted,
            keepsPermissionsWhenUnused = keepsPermissionsWhenUnused(ctx),
            notificationsAllowed = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            developerOptionsEnabled = Settings.Global.getInt(
                ctx.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1,
            shizuku = ShizukuGrant.status(ctx),
        )
    }

    /**
     * The grant step's once-a-second check: one permission read, and the full [refresh] (six to
     * eight system calls on the main thread) only once the grant has landed.
     */
    fun refreshIfGrantChanged() {
        if (SettingsController.hasWriteSecureSettings(getApplication()) != _state.value.writeSecureSettings) refresh()
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isBackgroundRestricted
    }

    private fun keepsPermissionsWhenUnused(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return context.packageManager.isAutoRevokeWhitelisted
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private companion object {
        const val TAG = "WardenSetup"
    }
}
