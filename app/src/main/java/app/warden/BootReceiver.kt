package app.warden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the monitor after a reboot or an app update if the user had it enabled. Any snapshot
 * left over from before the reboot/update is restored first, even when the monitor is disabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ConfigStore(appContext)
                runCatching { Recovery.restorePending(appContext, store) }
                if (store.config.first().monitorEnabled) {
                    runCatching { ForegroundMonitorService.start(appContext) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
