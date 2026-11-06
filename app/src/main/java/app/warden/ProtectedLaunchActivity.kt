package app.warden

import kotlinx.coroutines.CancellationException
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.warden.ui.LaunchWidgetConfigActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible trampoline behind the Protected launch widget: switches a watched app's settings off,
 * THEN opens it. Some apps check Developer options within their first second; opened from their
 * own icon they beat the monitor's next poll and refuse to run. Here the monitor confirms the
 * settings are off before the app's process even starts.
 *
 * It never writes settings itself. It asks [ForegroundMonitorService], which owns sessions,
 * snapshots and restore, so leaving the app restores exactly as usual. When Warden can't protect
 * (not set up, monitor off, paused, app not watched) it says why and opens the app as its icon
 * would.
 */
// Not a splash screen: lint matches on "Launch" in the name. This shows nothing at all.
@SuppressLint("CustomSplashScreen")
class ProtectedLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (pkg == null || launch == null) {
            val installedLabel = pkg?.let { packageManager.labelOrNull(it) }
            if (installedLabel != null) {
                // Still installed, just nothing to open (turned off, or no launcher screen). Not a
                // reason to pick another app for the widget.
                toast(getString(R.string.launch_cant_open, installedLabel))
                finish()
                return
            }
            // Uninstalled since the widget was set up: let the user pick another app for it.
            toast(getString(R.string.launch_not_installed))
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                finish()
                return
            }
            lifecycleScope.launch {
                // Redraw it as "Choose app" now; until the next widget update it kept the old app.
                try {
                    LaunchWidgets.refresh(applicationContext, AppWidgetManager.getInstance(applicationContext), intArrayOf(widgetId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only the picture is stale; the picker still opens.
                }
                runCatching { startActivity(LaunchWidgetConfigActivity.intent(this@ProtectedLaunchActivity, widgetId)) }
                finish()
            }
            return
        }
        lifecycleScope.launch {
            val label = packageManager.labelOrNull(pkg) ?: pkg
            val blocker = blocker(pkg)
            when {
                blocker != null -> toast(getString(blocker, label))
                !protect(pkg) -> toast(getString(R.string.launch_timed_out, label))
                // Settings are off, but the adb daemon stops a moment later: an app that looks for
                // it as it opens would still find it.
                else -> SettingsController.awaitAdbDaemonStopped(applicationContext)
            }
            // The same flags a launcher uses, so this behaves like tapping the app's icon. The app
            // can be removed or disabled while we wait; an uncaught throw here would take the whole
            // process down, the monitor included, mid-session.
            try {
                startActivity(
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            } catch (e: RuntimeException) {
                // ActivityNotFoundException or SecurityException. The launch hold ends on its own.
                toast(getString(R.string.launch_not_installed))
            }
            finish()
        }
    }

    /**
     * Why Warden can't protect this launch (a string resource), or null when it can. Mirrors what
     * the service checks.
     */
    private suspend fun blocker(pkg: String): Int? {
        if (!SettingsController.hasWriteSecureSettings(this)) return R.string.launch_not_set_up
        val cfg = try {
            ConfigStore(applicationContext).config.first()
        } catch (e: CancellationException) {
            throw e // finished meanwhile (the user left): stop, don't open the app behind them
        } catch (e: Exception) {
            return R.string.launch_timed_out
        }
        return when {
            !cfg.monitorEnabled -> R.string.launch_monitor_off
            pkg !in cfg.targetPackages -> R.string.launch_not_watched
            System.currentTimeMillis() < cfg.pausedUntil -> R.string.launch_paused
            else -> null
        }
    }

    /** True once the monitor reports [pkg] protected; false if it couldn't be asked or took too long. */
    private suspend fun protect(pkg: String): Boolean {
        try {
            ForegroundMonitorService.protectLaunch(applicationContext, pkg)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (12+) or IllegalStateException.
            return false
        }
        return withTimeoutOrNull(PROTECT_TIMEOUT_MS) {
            MonitorStatus.state.first { it is MonitorState.Protecting && it.packageName == pkg }
        } != null
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_PACKAGE = "app.warden.extra.LAUNCH_PACKAGE"

        /** Ample for a cold service start on a slow phone; on timeout the app opens anyway. */
        private const val PROTECT_TIMEOUT_MS = 2_000L

        /** What a launch widget runs when tapped. One PendingIntent per widget, keyed by its id. */
        fun pendingIntent(context: Context, appWidgetId: Int, packageName: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                appWidgetId,
                Intent(context, ProtectedLaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
