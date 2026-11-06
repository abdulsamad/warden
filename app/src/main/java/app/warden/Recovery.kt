package app.warden

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Restores any settings a previous session left disabled. Idempotent and safe to call repeatedly:
 * if nothing is pending it does nothing. This is the fallback for the case where the OS killed the
 * service process hard (no onDestroy), so the in-memory restore never ran - the persisted snapshot
 * is the durable record that lets us put settings back.
 *
 * Runs under [SettingsController.lock] so it can never interleave with a live session or a self-test.
 * When it restores something it reports a [RecoveryNotice], closes the open activity record as
 * recovered, and raises an alert if a setting did not come back.
 */
object Recovery {
    /**
     * Alerts once if the config file was found corrupt and replaced. The saved restore state went
     * with it, so if a watched app was open at the time, settings may still be off and only the
     * user can put them back.
     */
    suspend fun reportConfigReset(context: Context, store: ConfigStore) {
        // Keep the marker until the alert can be seen: with notifications or the alerts channel
        // off, posting does nothing, and this is the only sign that a restore was lost.
        if (!EngineNotifications.canAlert(context)) return
        if (runCatching { store.consumeResetMarker() }.getOrDefault(false)) {
            EngineNotifications.postConfigResetAlert(context.applicationContext)
        }
    }

    suspend fun restorePending(context: Context, store: ConfigStore): Boolean {
        reportConfigReset(context, store)
        return restoreSnapshot(context, store)
    }

    // Off the main thread: the service's onDestroy blocks main while waiting for this lock, so a
    // holder that needed main to resume (a viewModelScope caller) would deadlock it.
    private suspend fun restoreSnapshot(context: Context, store: ConfigStore): Boolean =
        withContext(Dispatchers.Default) {
            SettingsController.lock.withLock {
                // A live session owns this snapshot and restores it when its app is left. Restoring
                // here would switch settings back on with that app still in front: after a reboot,
                // BOOT_COMPLETED can arrive well after unlock, once the user is already in one.
                if (MonitorStatus.state.value is MonitorState.Protecting) return@withLock false
                val snapshot = store.snapshot.first()
                if (!snapshot.active) return@withLock false
                val app = context.applicationContext
                withContext(NonCancellable + Dispatchers.IO) {
                    val failed = runCatching { SettingsController.restore(app, snapshot) }
                        .getOrElse { SettingsController.managedIn(snapshot) }
                    store.clearSnapshot()
                    runCatching { ActivityLog(app).recordLeave(failed, recovered = true) }
                    // Inside NonCancellable too: a caller cancelled mid-restore (the user left the
                    // screen) must still get the notice, and the alert if something didn't come back.
                    MonitorStatus.reportRecovery(failed)
                    if (failed.isNotEmpty()) EngineNotifications.postRestoreAlert(app, failed)
                }
                true
            }
        }
}
