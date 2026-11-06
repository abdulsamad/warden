package app.warden

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.EnumMap

enum class TestOutcome {
    /** Disabled, read back as disabled, then restored and read back as restored. */
    OK,

    /** Not managed by the current config (or no accessibility service chosen). */
    SKIPPED,

    /** Already off, so there was nothing to switch off. Restore is a no-op. */
    ALREADY_OFF,

    /** The write failed, or the value read back unchanged (OEM blocked or reverted it). */
    DISABLE_FAILED,

    /** Disabling worked but putting the original value back did not. */
    RESTORE_FAILED,
}

sealed interface SelfTestResult {
    data class Completed(val outcomes: Map<Protection, TestOutcome>) : SelfTestResult

    /** Refused because a protected session is in progress or a snapshot is pending restore. */
    data object Busy : SelfTestResult

    /** WRITE_SECURE_SETTINGS is not granted. */
    data object NoPermission : SelfTestResult
}

/**
 * Briefly disables each managed setting, verifies by reading it back, then restores it.
 *
 * Runs under [SettingsController.lock] and persists a snapshot before touching anything, so a crash
 * mid-test is undone by [Recovery] exactly like a crash mid-session. The settle delay is shared by
 * all protections (one wait, not one per setting) to catch OEMs that revert a write a moment later.
 * Turning off developer options or USB debugging drops an active ADB connection; that is expected.
 */
object SelfTest {
    private const val SETTLE_MS = 800L

    suspend fun run(context: Context, store: ConfigStore): SelfTestResult {
        val app = context.applicationContext
        if (!SettingsController.hasWriteSecureSettings(app)) return SelfTestResult.NoPermission
        if (MonitorStatus.state.value is MonitorState.Protecting) return SelfTestResult.Busy
        if (!SettingsController.lock.tryLock()) return SelfTestResult.Busy
        try {
            // Re-check under the lock: a session may have persisted its snapshot just before we got it.
            if (MonitorStatus.state.value is MonitorState.Protecting || store.snapshot.first().active) {
                return SelfTestResult.Busy
            }
            return withContext(Dispatchers.IO) { runLocked(app, store) }
        } finally {
            SettingsController.lock.unlock()
        }
    }

    private suspend fun runLocked(context: Context, store: ConfigStore): SelfTestResult {
        val cfg = store.config.first()
        val managed = SettingsController.managed(cfg)
        val snapshot = SettingsController.capture(context, cfg)
        store.saveSnapshot(snapshot) // persist BEFORE changing anything

        val outcomes = EnumMap<Protection, TestOutcome>(Protection::class.java)
        try {
            val toVerify = mutableListOf<Protection>()
            for (p in Protection.entries) {
                when {
                    p !in managed -> outcomes[p] = TestOutcome.SKIPPED
                    SettingsController.isDisabled(context, p, cfg) -> outcomes[p] = TestOutcome.ALREADY_OFF
                    runCatching { SettingsController.disable(context, p, cfg) }.getOrDefault(false) ->
                        toVerify += p
                    else -> outcomes[p] = TestOutcome.DISABLE_FAILED
                }
            }
            if (toVerify.isNotEmpty()) delay(SETTLE_MS)
            for (p in toVerify) {
                outcomes[p] =
                    if (SettingsController.isDisabled(context, p, cfg)) TestOutcome.OK
                    else TestOutcome.DISABLE_FAILED
            }
        } finally {
            withContext(NonCancellable) {
                val failed = runCatching { SettingsController.restore(context, snapshot) }
                    .getOrElse { SettingsController.managedIn(snapshot) }
                for (p in failed) outcomes[p] = TestOutcome.RESTORE_FAILED
                store.clearSnapshot()
            }
        }
        return SelfTestResult.Completed(outcomes)
    }
}
