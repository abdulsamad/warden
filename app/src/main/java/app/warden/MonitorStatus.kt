package app.warden

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The three settings Warden can manage. */
enum class Protection(@StringRes val labelRes: Int) {
    USB_DEBUGGING(R.string.protection_usb_debugging),
    DEVELOPER_OPTIONS(R.string.protection_developer_options),
    ACCESSIBILITY(R.string.protection_accessibility),
}

/** What the monitor is doing right now. Published by the service, observed by UI and the QS tile. */
sealed interface MonitorState {
    /** The monitor service is not running. */
    data object Stopped : MonitorState

    /**
     * Running, no target app in front, nothing disabled.
     * [restoreFailed] is non-empty when the most recent restore could not put a setting back.
     */
    data class Watching(
        val targetCount: Int,
        val restoreFailed: Set<Protection> = emptySet(),
    ) : MonitorState

    /** Running but paused by the user until [untilMillis] (wall clock). Nothing disabled. */
    data class Paused(val untilMillis: Long) : MonitorState

    /** A target app is in front. [disabled] were switched off; [failed] could not be. */
    data class Protecting(
        val packageName: String,
        val appLabel: String,
        val disabled: Set<Protection>,
        val failed: Set<Protection>,
    ) : MonitorState
}

/** Settings were restored by the fallback path (the service had died mid-session). */
data class RecoveryNotice(
    val at: Long,
    val failed: Set<Protection>,
)

/**
 * Process-wide live state. The service, UI and tile all run in the same process, so a plain
 * StateFlow is enough - no IPC, no persistence. If the process dies the state resets to [Stopped],
 * which is correct because the service died with it.
 */
object MonitorStatus {
    private val _state = MutableStateFlow<MonitorState>(MonitorState.Stopped)
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _recoveryNotice = MutableStateFlow<RecoveryNotice?>(null)

    /** Non-null until the UI has shown it and called [acknowledgeRecovery]. */
    val recoveryNotice: StateFlow<RecoveryNotice?> = _recoveryNotice.asStateFlow()

    fun update(state: MonitorState) {
        _state.value = state
    }

    fun reportRecovery(failed: Set<Protection>) {
        _recoveryNotice.value = RecoveryNotice(System.currentTimeMillis(), failed)
    }

    fun acknowledgeRecovery() {
        _recoveryNotice.value = null
    }
}
