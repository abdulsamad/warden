package app.warden

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.sync.Mutex
import java.util.EnumSet

/**
 * Owns the only writes to secure settings. Every write genuinely changes device state - nothing
 * here spoofs or hides a value from another app. Writes require WRITE_SECURE_SETTINGS (granted via
 * ADB); when the permission is missing, an OEM blocks a write, or an OEM silently reverts/ignores
 * it (Samsung does this for adb_enabled), the call reports a failure instead of pretending to have
 * succeeded: every write is verified by reading the value back.
 */
object SettingsController {

    /**
     * Process-wide lock. Every state transition (service enter/leave, Recovery, SelfTest) runs
     * while holding it, so a self-test can never interleave with a real session.
     */
    val lock = Mutex()

    private const val MISSING = Int.MIN_VALUE

    /** Wireless debugging's switch (Android 11+). Its Settings.Global constant is hidden. */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** How long a widget launch waits for the adb daemon to stop, and how often it looks. */
    private const val ADBD_STOP_TIMEOUT_MS = 1_000L
    private const val ADBD_POLL_MS = 50L

    private val hasWirelessDebugging: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    // --- reads ---

    fun readAdbEnabled(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)

    /** Wireless debugging; always 0 before Android 11, which doesn't have it. */
    fun readAdbWifiEnabled(context: Context): Int =
        if (hasWirelessDebugging) Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) else 0

    fun readDevEnabled(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)

    fun readA11yServices(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""

    fun readA11yEnabled(context: Context): Int =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)

    // --- config / snapshot helpers ---

    /** The protections the config currently asks us to manage. */
    fun managed(cfg: AppConfig): Set<Protection> {
        val set = EnumSet.noneOf(Protection::class.java)
        if (cfg.manageUsbDebugging) set += Protection.USB_DEBUGGING
        if (cfg.manageDevOptions) set += Protection.DEVELOPER_OPTIONS
        if (cfg.managesAccessibility) set += Protection.ACCESSIBILITY
        return set
    }

    /** The protections a snapshot holds a value for (null fields are not managed). */
    fun managedIn(snap: Snapshot): Set<Protection> {
        val set = EnumSet.noneOf(Protection::class.java)
        if (snap.adbEnabled != null || snap.adbWifiEnabled != null) set += Protection.USB_DEBUGGING
        if (snap.devEnabled != null) set += Protection.DEVELOPER_OPTIONS
        if (snap.a11yServices != null) set += Protection.ACCESSIBILITY
        return set
    }

    /** Captures the current state of exactly the settings the config says to manage. */
    fun capture(context: Context, cfg: AppConfig): Snapshot {
        val manageA11y = cfg.managesAccessibility
        return Snapshot(
            active = true,
            adbEnabled = if (cfg.manageUsbDebugging) readAdbEnabled(context) else null,
            adbWifiEnabled = if (cfg.manageUsbDebugging && hasWirelessDebugging) readAdbWifiEnabled(context) else null,
            devEnabled = if (cfg.manageDevOptions) readDevEnabled(context) else null,
            a11yServices = if (manageA11y) readA11yServices(context) else null,
            a11yEnabled = if (manageA11y) readA11yEnabled(context) else null,
        )
    }

    // --- per-protection operations (used by the service, Recovery and SelfTest) ---

    /** True when [protection] currently reads as switched off, as [cfg] defines "off" for accessibility. */
    fun isDisabled(context: Context, protection: Protection, cfg: AppConfig): Boolean = try {
        when (protection) {
            Protection.USB_DEBUGGING -> readAdbEnabled(context) == 0 && readAdbWifiEnabled(context) == 0
            Protection.DEVELOPER_OPTIONS -> readDevEnabled(context) == 0
            Protection.ACCESSIBILITY -> isA11yDisabled(readA11yServices(context), cfg)
        }
    } catch (e: Exception) {
        false
    }

    /** Switches [protection] off and verifies by read-back. */
    fun disable(context: Context, protection: Protection, cfg: AppConfig): Boolean = when (protection) {
        Protection.USB_DEBUGGING -> {
            // Both, even if the first fails: either one keeps the adb daemon running.
            val usb = putGlobalInt(context, Settings.Global.ADB_ENABLED, 0)
            val wireless = disableWirelessDebugging(context)
            usb && wireless
        }
        Protection.DEVELOPER_OPTIONS -> putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        Protection.ACCESSIBILITY -> disableA11y(context, cfg)
    }

    /** Disables the managed settings. Returns the protections that could not be switched off. */
    fun applyDisabled(context: Context, cfg: AppConfig): Set<Protection> {
        val failures = EnumSet.noneOf(Protection::class.java)
        for (protection in managed(cfg)) {
            if (!disable(context, protection, cfg)) failures += protection
        }
        return failures
    }

    /**
     * Restores exactly what was captured, verifying each by read-back. Null fields are left
     * untouched. Returns the protections that did not come back.
     */
    fun restore(context: Context, snap: Snapshot): Set<Protection> {
        val failures = EnumSet.noneOf(Protection::class.java)
        snap.adbEnabled?.let {
            if (!putGlobalInt(context, Settings.Global.ADB_ENABLED, it)) failures += Protection.USB_DEBUGGING
        }
        snap.devEnabled?.let {
            if (!putGlobalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, it)) {
                failures += Protection.DEVELOPER_OPTIONS
            }
        }
        snap.adbWifiEnabled?.let {
            // After Developer options, which some phones want on first. Only when it changed:
            // switching it on restarts the adb daemon.
            if (readAdbWifiEnabled(context) != it && !putGlobalInt(context, ADB_WIFI_ENABLED, it)) {
                failures += Protection.USB_DEBUGGING
            }
        }
        if (snap.a11yServices != null && !restoreA11y(context, snap.a11yServices, snap.a11yEnabled ?: 0)) {
            failures += Protection.ACCESSIBILITY
        }
        return failures
    }

    // --- debugging ---

    /**
     * Part of the USB debugging protection: Wireless debugging runs the same adb daemon, so an app
     * that looks for the daemon still finds it while this is on. Written only when it is on, so a
     * phone that never uses it sees no extra write (some phones' own security features manage it
     * too).
     */
    private fun disableWirelessDebugging(context: Context): Boolean =
        readAdbWifiEnabled(context) == 0 || putGlobalInt(context, ADB_WIFI_ENABLED, 0)

    /**
     * Android stops the adb daemon a moment after debugging is switched off, so an app opened at
     * once can still find it running. Waits until it has stopped, at most [timeoutMs]. Returns at
     * once while debugging is still on (the daemon won't stop) or when its state can't be read.
     * Only reads; used by a widget launch after the service has switched debugging off.
     */
    suspend fun awaitAdbDaemonStopped(context: Context, timeoutMs: Long = ADBD_STOP_TIMEOUT_MS) {
        if (readAdbEnabled(context) != 0 || readAdbWifiEnabled(context) != 0) return
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                while (adbDaemonRunning()) delay(ADBD_POLL_MS)
            }
        }
    }

    /** The adb daemon's init state, as apps that look for it read it. False when it can't be read. */
    private fun adbDaemonRunning(): Boolean = try {
        val process = ProcessBuilder("getprop", "init.svc.adbd").redirectErrorStream(true).start()
        val state = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor()
        state == "running"
    } catch (e: Exception) {
        false
    }

    // --- accessibility ---

    /** The components [cfg] asks to switch off, or null for "every enabled service". */
    private fun a11yTargets(cfg: AppConfig): Set<ComponentName>? =
        if (cfg.accessibilityAll) null
        else cfg.accessibilityComponents.mapNotNull(ComponentName::unflattenFromString).toSet()

    private fun isA11yDisabled(list: String, cfg: AppConfig): Boolean {
        val targets = a11yTargets(cfg) ?: return list.split(':').none { it.isNotBlank() }
        return targets.none { containsComponent(list, it) }
    }

    private fun disableA11y(context: Context, cfg: AppConfig): Boolean {
        val targets = a11yTargets(cfg)
        if (targets != null && targets.isEmpty()) return false
        val remaining = if (targets == null) {
            emptyList()
        } else {
            readA11yServices(context)
                .split(':')
                .filter { it.isNotBlank() }
                .filter { entry ->
                    val cn = ComponentName.unflattenFromString(entry)
                    cn == null || cn !in targets
                }
        }
        var ok = putSecureString(
            context,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            remaining.joinToString(":"),
        ) && isA11yDisabled(readA11yServices(context), cfg)
        if (remaining.isEmpty()) {
            ok = ok && putSecureInt(context, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }
        return ok
    }

    private fun restoreA11y(context: Context, services: String, enabled: Int): Boolean {
        val wrote = putSecureString(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services) &&
            putSecureInt(context, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
        // The accessibility manager may rewrite the list (reorder, drop stale entries) after a write,
        // so compare as a set of normalized components rather than as a raw string.
        return wrote && entries(readA11yServices(context)) == entries(services)
    }

    private fun entries(list: String): Set<String> = list.split(':')
        .filter { it.isNotBlank() }
        .map { ComponentName.unflattenFromString(it)?.flattenToString() ?: it }
        .toSet()

    private fun containsComponent(list: String, target: ComponentName): Boolean =
        list.split(':').any { it.isNotBlank() && ComponentName.unflattenFromString(it) == target }

    // --- verified writes ---

    private fun putGlobalInt(context: Context, key: String, value: Int): Boolean = try {
        val cr = context.contentResolver
        Settings.Global.putInt(cr, key, value) && Settings.Global.getInt(cr, key, MISSING) == value
    } catch (e: Exception) {
        false
    }

    private fun putSecureInt(context: Context, key: String, value: Int): Boolean = try {
        val cr = context.contentResolver
        Settings.Secure.putInt(cr, key, value) && Settings.Secure.getInt(cr, key, MISSING) == value
    } catch (e: Exception) {
        false
    }

    private fun putSecureString(context: Context, key: String, value: String): Boolean = try {
        Settings.Secure.putString(context.contentResolver, key, value)
    } catch (e: Exception) {
        false
    }
}
