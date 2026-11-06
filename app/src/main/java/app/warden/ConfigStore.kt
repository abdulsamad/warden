package app.warden

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Present only in a file that replaced a corrupt one; see [ConfigStore.consumeResetMarker]. */
private val RESET_MARKER = booleanPreferencesKey("reset_after_corruption")

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "config",
    // Without a handler, one corrupt file makes every read throw for good, taking the monitor and
    // Recovery down with it. Start over from defaults instead, and leave a marker: the saved
    // restore state was in that file too, so the user has to be told (see Recovery).
    corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(RESET_MARKER to true) },
)

/** User-controlled configuration. */
data class AppConfig(
    val targetPackages: Set<String> = emptySet(),
    /** True: switch off every enabled accessibility service. False: only [accessibilityComponents]. */
    val accessibilityAll: Boolean = true,
    /** Flattened ComponentNames to switch off when [accessibilityAll] is false. */
    val accessibilityComponents: Set<String> = emptySet(),
    val manageUsbDebugging: Boolean = true,
    val manageDevOptions: Boolean = true,
    val manageAccessibility: Boolean = true,
    val monitorEnabled: Boolean = false,
    /**
     * Wall-clock millis until which the monitor is paused. 0 (or any past time) = not paused.
     * [PAUSE_UNTIL_SCREEN_OFF] = paused until the screen next turns off.
     */
    val pausedUntil: Long = 0L,
) {
    val pausedUntilScreenOff: Boolean get() = pausedUntil == PAUSE_UNTIL_SCREEN_OFF

    /** Accessibility is managed only when its toggle is on and there is something to switch off. */
    val managesAccessibility: Boolean
        get() = manageAccessibility && (accessibilityAll || accessibilityComponents.isNotEmpty())
}

/** Sentinel for [AppConfig.pausedUntil]: compares as "far future", so it reads as paused everywhere. */
const val PAUSE_UNTIL_SCREEN_OFF = Long.MAX_VALUE

/** How long a pause lasts, as offered in the app. The notification uses [HOUR]. */
enum class PauseLength {
    MINUTES_15, HOUR, UNTIL_SCREEN_OFF;

    fun pausedUntil(now: Long): Long = when (this) {
        MINUTES_15 -> now + 15 * 60_000L
        HOUR -> now + 60 * 60_000L
        UNTIL_SCREEN_OFF -> PAUSE_UNTIL_SCREEN_OFF
    }
}

/**
 * The pre-session state captured before disabling settings, used to restore them exactly.
 * A null field means that setting is not being managed and must not be touched on restore.
 */
data class Snapshot(
    val active: Boolean = false,
    val adbEnabled: Int? = null,
    /** Wireless debugging (Android 11+), managed with USB debugging. Null in older snapshots. */
    val adbWifiEnabled: Int? = null,
    val devEnabled: Int? = null,
    val a11yServices: String? = null,
    val a11yEnabled: Int? = null,
)

class ConfigStore(private val context: Context) {

    private object Keys {
        val TARGETS = stringSetPreferencesKey("target_packages")
        /** Legacy single-service pick; read only to seed [A11Y_COMPONENTS] for existing installs. */
        val A11Y_COMPONENT = stringPreferencesKey("a11y_component")
        val A11Y_ALL = booleanPreferencesKey("a11y_all")
        val A11Y_COMPONENTS = stringSetPreferencesKey("a11y_components")
        val MANAGE_USB = booleanPreferencesKey("manage_usb")
        val MANAGE_DEV = booleanPreferencesKey("manage_dev")
        val MANAGE_A11Y = booleanPreferencesKey("manage_a11y")
        val MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val SELF_TEST_SUGGESTED = booleanPreferencesKey("self_test_suggested")

        val SNAP_ACTIVE = booleanPreferencesKey("snap_active")
        val SNAP_ADB = intPreferencesKey("snap_adb")
        val SNAP_ADB_WIFI = intPreferencesKey("snap_adb_wifi")
        val SNAP_DEV = intPreferencesKey("snap_dev")
        val SNAP_A11Y_SERVICES = stringPreferencesKey("snap_a11y_services")
        val SNAP_A11Y_ENABLED = intPreferencesKey("snap_a11y_enabled")
        val SNAP_HAS_ADB = booleanPreferencesKey("snap_has_adb")
        val SNAP_HAS_ADB_WIFI = booleanPreferencesKey("snap_has_adb_wifi")
        val SNAP_HAS_DEV = booleanPreferencesKey("snap_has_dev")
        val SNAP_HAS_A11Y = booleanPreferencesKey("snap_has_a11y")
    }

    val config: Flow<AppConfig> = context.dataStore.data.map { p ->
        AppConfig(
            targetPackages = p[Keys.TARGETS] ?: emptySet(),
            accessibilityAll = p[Keys.A11Y_ALL] ?: true,
            accessibilityComponents = p[Keys.A11Y_COMPONENTS] ?: setOfNotNull(p[Keys.A11Y_COMPONENT]),
            manageUsbDebugging = p[Keys.MANAGE_USB] ?: true,
            manageDevOptions = p[Keys.MANAGE_DEV] ?: true,
            manageAccessibility = p[Keys.MANAGE_A11Y] ?: true,
            monitorEnabled = p[Keys.MONITOR_ENABLED] ?: false,
            pausedUntil = p[Keys.PAUSED_UNTIL] ?: 0L,
        )
    }

    val snapshot: Flow<Snapshot> = context.dataStore.data.map { p ->
        val hasA11y = p[Keys.SNAP_HAS_A11Y] == true
        Snapshot(
            active = p[Keys.SNAP_ACTIVE] ?: false,
            adbEnabled = if (p[Keys.SNAP_HAS_ADB] == true) p[Keys.SNAP_ADB] else null,
            adbWifiEnabled = if (p[Keys.SNAP_HAS_ADB_WIFI] == true) p[Keys.SNAP_ADB_WIFI] else null,
            devEnabled = if (p[Keys.SNAP_HAS_DEV] == true) p[Keys.SNAP_DEV] else null,
            a11yServices = if (hasA11y) p[Keys.SNAP_A11Y_SERVICES] else null,
            a11yEnabled = if (hasA11y) p[Keys.SNAP_A11Y_ENABLED] else null,
        )
    }

    /**
     * UI-only flag, kept out of [AppConfig] so changing it never wakes the monitor service: the
     * one-time "test it on this phone" hint was run or dismissed.
     */
    val selfTestSuggested: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SELF_TEST_SUGGESTED] ?: false }

    suspend fun setSelfTestSuggested() = edit { it[Keys.SELF_TEST_SUGGESTED] = true }

    /** True exactly once after the file was found corrupt and replaced with defaults. */
    suspend fun consumeResetMarker(): Boolean {
        var wasReset = false
        edit { p ->
            if (p[RESET_MARKER] == true) {
                wasReset = true
                p.remove(RESET_MARKER)
            }
        }
        return wasReset
    }

    suspend fun setTargets(v: Set<String>) = edit { it[Keys.TARGETS] = v }

    suspend fun setAccessibilityAll(v: Boolean) = edit { it[Keys.A11Y_ALL] = v }

    /** Read-modify-write inside one DataStore transaction, so quick taps never overwrite each other. */
    suspend fun setAccessibilityComponent(component: String, selected: Boolean) = edit { p ->
        val current = p[Keys.A11Y_COMPONENTS] ?: setOfNotNull(p[Keys.A11Y_COMPONENT])
        p[Keys.A11Y_COMPONENTS] = if (selected) current + component else current - component
        p.remove(Keys.A11Y_COMPONENT)
    }

    suspend fun setManageUsb(v: Boolean) = edit { it[Keys.MANAGE_USB] = v }
    suspend fun setManageDev(v: Boolean) = edit { it[Keys.MANAGE_DEV] = v }
    suspend fun setManageA11y(v: Boolean) = edit { it[Keys.MANAGE_A11Y] = v }
    suspend fun setMonitorEnabled(v: Boolean) = edit { it[Keys.MONITOR_ENABLED] = v }
    suspend fun setPausedUntil(v: Long) = edit { it[Keys.PAUSED_UNTIL] = v }

    /** Ends a "until screen off" pause; leaves timed pauses alone. One transaction, so never racy. */
    suspend fun endScreenOffPause() = edit { p ->
        if (p[Keys.PAUSED_UNTIL] == PAUSE_UNTIL_SCREEN_OFF) p[Keys.PAUSED_UNTIL] = 0L
    }

    suspend fun saveSnapshot(s: Snapshot) = edit { p ->
        p[Keys.SNAP_ACTIVE] = s.active
        p[Keys.SNAP_HAS_ADB] = s.adbEnabled != null
        s.adbEnabled?.let { p[Keys.SNAP_ADB] = it }
        p[Keys.SNAP_HAS_ADB_WIFI] = s.adbWifiEnabled != null
        s.adbWifiEnabled?.let { p[Keys.SNAP_ADB_WIFI] = it }
        p[Keys.SNAP_HAS_DEV] = s.devEnabled != null
        s.devEnabled?.let { p[Keys.SNAP_DEV] = it }
        p[Keys.SNAP_HAS_A11Y] = s.a11yServices != null
        if (s.a11yServices != null) {
            p[Keys.SNAP_A11Y_SERVICES] = s.a11yServices
            p[Keys.SNAP_A11Y_ENABLED] = s.a11yEnabled ?: 0
        }
    }

    suspend fun clearSnapshot() = edit { p -> p[Keys.SNAP_ACTIVE] = false }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
