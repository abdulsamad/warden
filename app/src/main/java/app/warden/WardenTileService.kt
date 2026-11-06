package app.warden

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.warden.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows the monitor state and toggles [AppConfig.monitorEnabled].
 *
 * It only observes [MonitorStatus] and the config while the tile is visible. The toggle writes the
 * config and starts/stops the service; if Android refuses to start a foreground service from here,
 * it opens the app instead (whose onResume restarts the service when the monitor is enabled).
 */
class WardenTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var store: ConfigStore? = null

    @Volatile private var latestConfig: AppConfig? = null
    @Volatile private var available = false

    override fun onStartListening() {
        super.onStartListening()
        val s = ConfigStore(applicationContext)
        store = s
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { cs ->
            cs.launch {
                combine(MonitorStatus.state, s.config) { state, config -> state to config }
                    .collect { (state, config) ->
                        latestConfig = config
                        render(state, config)
                    }
            }
        }
    }

    override fun onStopListening() {
        stopObserving()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopObserving()
        super.onDestroy()
    }

    private fun stopObserving() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        super.onClick()
        val config = latestConfig
        val s = store
        if (config == null || s == null || !available) {
            openApp()
            return
        }
        val enable = !config.monitorEnabled
        // Write config on a scope that outlives this call; it is tiny and the service reads config itself.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { s.setMonitorEnabled(enable) }
        }
        if (enable) {
            try {
                ForegroundMonitorService.start(applicationContext)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (12+) or IllegalStateException.
                openApp()
            }
        } else {
            ForegroundMonitorService.stop(applicationContext)
        }
    }

    private fun render(state: MonitorState, config: AppConfig) {
        val tile = qsTile ?: return
        val ready = SettingsController.hasWriteSecureSettings(this) && config.targetPackages.isNotEmpty()
        available = ready
        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            config.monitorEnabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !ready -> getString(R.string.engine_tile_setup)
                !config.monitorEnabled -> getString(R.string.engine_tile_off)
                else -> when (state) {
                    MonitorState.Stopped -> getString(R.string.engine_tile_not_running)
                    is MonitorState.Watching -> EngineNotifications.watchingText(this, state.targetCount)
                    is MonitorState.Paused -> EngineNotifications.pausedText(this, state.untilMillis)
                    is MonitorState.Protecting -> getString(R.string.engine_notif_protecting, state.appLabel)
                }
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = MainActivity.openIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
