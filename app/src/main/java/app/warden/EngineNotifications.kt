package app.warden

import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.warden.ui.MainActivity
import java.util.Date

/**
 * Channels, ids and text for the engine's notifications. The ongoing notification (owned by the
 * service) is IMPORTANCE_LOW; the separate Alerts channel is used only when something fails.
 * Every notify call is guarded because notifications may be denied.
 */
object EngineNotifications {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_ONGOING = 1
    const val ID_ALERT_DISABLE = 2
    const val ID_ALERT_RESTORE = 3
    const val ID_ALERT_RESET = 4

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            // Re-creating an existing channel updates its name and description (not importance),
            // so installs that already have it pick up the new wording too.
            NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.engine_channel_monitor),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.engine_channel_monitor_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.engine_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.engine_channel_alerts_desc) },
        )
    }

    /** True when an alert would actually show: notifications allowed and the alerts channel not off. */
    fun canAlert(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ALERTS)
        // Not created yet: postAlert creates it, at default importance.
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    // --- text ---

    fun joinProtections(context: Context, set: Set<Protection>): String =
        set.joinToString(", ") { context.getString(it.labelRes) }

    fun watchingText(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.engine_notif_watching, count, count)

    fun pausedText(context: Context, untilMillis: Long): String =
        if (untilMillis == PAUSE_UNTIL_SCREEN_OFF) {
            context.getString(R.string.engine_notif_paused_screen_off)
        } else {
            context.getString(
                R.string.engine_notif_paused,
                DateFormat.getTimeFormat(context).format(Date(untilMillis)),
            )
        }

    /** One-line description of [state] for the ongoing notification. */
    fun describe(context: Context, state: MonitorState): String = when (state) {
        MonitorState.Stopped -> context.getString(R.string.engine_notif_starting)
        is MonitorState.Watching ->
            if (state.restoreFailed.isNotEmpty()) {
                context.getString(R.string.engine_notif_couldnt_restore, joinProtections(context, state.restoreFailed))
            } else {
                watchingText(context, state.targetCount)
            }
        is MonitorState.Paused -> pausedText(context, state.untilMillis)
        is MonitorState.Protecting -> when {
            state.failed.isNotEmpty() ->
                context.getString(R.string.engine_notif_couldnt_turn_off, joinProtections(context, state.failed))
            state.disabled.isEmpty() -> context.getString(R.string.engine_notif_protecting, state.appLabel)
            else -> context.resources.getQuantityString(
                R.plurals.engine_notif_protecting_count,
                state.disabled.size,
                state.appLabel,
                state.disabled.size,
            )
        }
    }

    // --- alerts ---

    fun postDisableAlert(context: Context, appLabel: String, failed: Set<Protection>) = postAlert(
        context,
        ID_ALERT_DISABLE,
        context.getString(R.string.engine_alert_disable_title),
        context.getString(R.string.engine_alert_disable_text, joinProtections(context, failed), appLabel),
    )

    fun postRestoreAlert(context: Context, failed: Set<Protection>) = postAlert(
        context,
        ID_ALERT_RESTORE,
        context.getString(R.string.engine_alert_restore_title),
        context.getString(R.string.engine_alert_restore_text, joinProtections(context, failed)),
    )

    fun postConfigResetAlert(context: Context) = postAlert(
        context,
        ID_ALERT_RESET,
        context.getString(R.string.engine_alert_reset_title),
        context.getString(R.string.engine_alert_reset_text),
    )

    fun cancelDisableAlert(context: Context) = cancel(context, ID_ALERT_DISABLE)

    fun cancelRestoreAlert(context: Context) = cancel(context, ID_ALERT_RESTORE)

    private fun postAlert(context: Context, id: Int, title: String, text: String) {
        runCatching {
            ensureChannels(context)
            val pi = PendingIntent.getActivity(
                context,
                0,
                MainActivity.openIntent(context),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_warden)
                .setColor(ContextCompat.getColor(context, R.color.warden_accent))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    private fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id) }
    }
}
