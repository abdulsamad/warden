package app.warden

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.warden.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches the foreground app (via UsageStatsManager, not an accessibility service) and genuinely
 * disables the managed settings while a target app is in front, restoring them the moment it is no
 * longer the active, visible app (or Warden is paused).
 *
 * Memory/battery: one reused event object, change-detection so transitions run only on an actual
 * foreground change, the active snapshot kept in memory (DataStore is touched only at transitions as
 * a crash-durable copy), and polling that stops while the screen is off.
 *
 * Fail-safe: the snapshot is persisted BEFORE anything is disabled, every settings call is guarded,
 * any unexpected error during disable immediately restores, and onDestroy restores on the way out.
 * The hard-kill case (no onDestroy) is covered by [Recovery] at the next service or app start, and
 * by this service adopting a leftover persisted snapshot on start.
 *
 * Every state transition runs under [SettingsController.lock]; the in-memory snapshot is the source
 * of truth and the persisted copy is only the crash-durable duplicate.
 */
class ForegroundMonitorService : Service() {

    private class Session(
        val packageName: String,
        val appLabel: String,
        val disabled: Set<Protection>,
        val failed: Set<Protection>,
    )

    /**
     * A target opened from a Warden widget, see [ACTION_PROTECT_LAUNCH]. [since] is wall-clock
     * (compared with usage event timestamps); [untilElapsed] is monotonic, so a clock change can
     * never stretch the hold and leave settings off.
     */
    private class LaunchHold(val packageName: String, val since: Long, val untilElapsed: Long)

    /**
     * If the config can't be read (an I/O error; a corrupt file is handled in ConfigStore) or the
     * monitor loop dies, stop rather than run on with the notification claiming protection while
     * nothing is watched. The status card then shows the monitor as not running.
     */
    private val failureHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Monitor stopped after an unexpected error", e)
        stopSelf()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failureHandler)
    private lateinit var store: ConfigStore
    private lateinit var log: ActivityLog
    private lateinit var usm: UsageStatsManager

    /** Serializes polling (the loop and the screen-on receiver) around the shared event object. */
    private val pollMutex = Mutex()

    @Volatile private var config: AppConfig = AppConfig()
    @Volatile private var activeSnapshot: Snapshot? = null

    /** True when [activeSnapshot] was persisted by a previous process rather than made by this one. */
    @Volatile private var adopted = false
    @Volatile private var session: Session? = null
    @Volatile private var lastRestoreFailed: Set<Protection> = emptySet()
    @Volatile private var currentForeground: String? = null

    /** Set from the real display state in onCreate; a restart while locked must not poll. */
    @Volatile private var screenOn = true

    /**
     * Set by a widget launch before the target is on screen. While set, [evaluate] treats that app
     * as already in front, so its settings are off before it starts: some apps check Developer
     * options in their first second, sooner than the next poll could notice them. Ends when a
     * foreground event for that app arrives, after [LAUNCH_HOLD_MS], or on screen off. In memory
     * only: a new process starts without one.
     */
    private val launchHold = AtomicReference<LaunchHold?>(null)

    /** Completed once config is loaded and any leftover snapshot adopted. */
    private val ready = CompletableDeferred<Unit>()

    /** Forces the next evaluation even when no foreground change was observed. */
    @Volatile private var needsEvaluation = true
    @Volatile private var stopping = false

    /** The last restore could not clear the saved snapshot; the next evaluate retries. Under the lock. */
    private var clearPending = false

    /** Guarded by [pollMutex]. */
    private var lastQueryTime = 0L

    /** Timestamp of the newest foreground event taken so far; guarded by [pollMutex]. */
    private var lastEventTime = 0L
    private var lastNotified: MonitorState? = null

    /**
     * The user swiped the status notification away (possible on Android 13+). Respect that for the
     * rest of this service run: state changes stop re-posting it. Failures still alert on their own
     * channel, and the app and tile keep showing live status.
     */
    @Volatile private var notificationDismissed = false
    private val reusableEvent = UsageEvents.Event()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    // Screen off = the target is no longer active: restore, and force a fresh
                    // foreground evaluation when the screen comes back. A pending widget launch
                    // is over too: nothing opens behind a locked screen.
                    currentForeground = null
                    launchHold.set(null)
                    scope.launch {
                        // Not before start-up has loaded config and any saved snapshot: an
                        // evaluate on defaults would clear needsEvaluation, and the snapshot it
                        // was about to adopt would then wait for the next app switch.
                        ready.await()
                        runCatching { store.endScreenOffPause() }
                        runCatching { evaluate() }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // A poll racing SCREEN_OFF can write the old foreground app back over the null
                    // above. Change detection alone would then skip the target still in front, so
                    // force the evaluation; it is idempotent.
                    needsEvaluation = true
                    scope.launch {
                        ready.await() // as for SCREEN_OFF
                        runCatching { pollForeground() }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(applicationContext)
        log = ActivityLog(applicationContext)
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        // Set before the receiver is registered: a SCREEN_ON polls at once, and a zero start
        // would ask the system for its entire usage history.
        lastQueryTime = initialQueryStart(System.currentTimeMillis())
        EngineNotifications.ensureChannels(this)
        startForeground(EngineNotifications.ID_ONGOING, buildNotification(null))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            Recovery.reportConfigReset(applicationContext, store)
            // A fresh process can't know whether the screen went off since that pause began
            // (reboot, kill), so end it: protecting again too early is the safe side.
            runCatching { store.endScreenOffPause() }
            config = store.config.first()
            // Adopt any snapshot a previous (possibly killed) session persisted, so we can restore
            // it even if we never re-enter a target this session. Under the lock so a running
            // self-test finishes (and clears its snapshot) before we look.
            SettingsController.lock.withLock {
                store.snapshot.first().takeIf { it.active }?.let {
                    activeSnapshot = it
                    adopted = true
                }
            }
            ready.complete(Unit)
            launch { store.config.collect { onConfigChanged(it) } }
            monitorLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Config is the single source of truth for pausing: the notification just writes it.
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch {
                runCatching { store.setPausedUntil(PauseLength.HOUR.pausedUntil(System.currentTimeMillis())) }
            }
            ACTION_RESUME -> scope.launch { runCatching { store.setPausedUntil(0L) } }
            ACTION_NOTIFICATION_DISMISSED -> notificationDismissed = true
            // Never while the screen is off: SCREEN_OFF has already ended any hold, and nothing
            // would end this one before the screen came back on, maybe on the lock screen.
            ACTION_PROTECT_LAUNCH -> intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { screenOn }?.let { pkg ->
                launchHold.set(
                    LaunchHold(pkg, System.currentTimeMillis(), SystemClock.elapsedRealtime() + LAUNCH_HOLD_MS),
                )
                scope.launch {
                    ready.await()
                    runCatching { evaluate() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun onConfigChanged(new: AppConfig) {
        val old = config
        config = new
        if (old.pausedUntil != new.pausedUntil || old.targetPackages != new.targetPackages) {
            // Poll first: while paused or with nothing to watch the loop idles, so the app it
            // last saw in front may be out of date. With the screen off there is nothing to poll.
            needsEvaluation = true
            runCatching { if (screenOn) pollForeground() else evaluate() }
        }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            if (screenOn && watchingNow(now)) {
                runCatching { pollForeground() }
                delay(POLL_INTERVAL_MS)
            } else {
                // Idle heartbeat: screen off, paused, or nothing to watch. SCREEN_ON and config
                // changes wake us at once. It still notices a pause ending (on time, see
                // idleDelay) or a pending first evaluation; with the screen on it polls first,
                // because the app in front may have changed while nothing was polling.
                if (needsEvaluation || pauseExpired(now)) {
                    runCatching { if (screenOn) pollForeground() else evaluate() }
                }
                delay(idleDelay(now))
            }
        }
    }

    /**
     * Whether the screen-on loop has anything to track: apps chosen and no pause, or a widget
     * launch in progress. Otherwise every 750 ms poll would be wasted (an hour's pause is ~4,800).
     */
    private fun watchingNow(now: Long): Boolean =
        launchHold.get() != null || (config.targetPackages.isNotEmpty() && now >= config.pausedUntil)

    /** The idle interval, shortened so a timed pause ends on time rather than up to 5 s late. */
    private fun idleDelay(now: Long): Long {
        val untilPauseEnds = config.pausedUntil - now
        return if (untilPauseEnds in 1 until IDLE_INTERVAL_MS) untilPauseEnds else IDLE_INTERVAL_MS
    }

    /**
     * Where the first poll starts. After a restart (sticky, update, boot) the app in front is the
     * one with the newest foreground event, however long ago that was, so look back to boot, capped
     * so a phone that has been up for weeks is not asked for its whole history.
     */
    private fun initialQueryStart(now: Long): Long =
        maxOf(now - SystemClock.elapsedRealtime(), now - INITIAL_WINDOW_MS)

    private suspend fun pollForeground() = pollMutex.withLock {
        if (!screenOn) return@withLock
        val now = System.currentTimeMillis()
        // The wall clock went back (set by hand, or corrected). New events are stamped below the
        // newest one already taken, so the timestamp filter would drop every app switch until the
        // clock caught up. Start over from here; the app in front stays as last seen.
        if (now < lastQueryTime) {
            lastEventTime = now
            lastQueryTime = now
        }
        // Windows overlap: the system stamps an event before committing it on another thread, so
        // one stamped just before the previous query but committed after it would otherwise never
        // be returned. Events already taken are dropped by timestamp.
        val events = usm.queryEvents(lastQueryTime - QUERY_OVERLAP_MS, now)
        val hold = launchHold.get()
        var holdArrived = false
        var latest = currentForeground
        while (events.hasNextEvent()) {
            events.getNextEvent(reusableEvent)
            val type = reusableEvent.eventType
            if ((type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    type == UsageEvents.Event.ACTIVITY_RESUMED) &&
                reusableEvent.timeStamp > lastEventTime
            ) {
                lastEventTime = reusableEvent.timeStamp
                latest = reusableEvent.packageName
                // The widget-launched app has really arrived (an event newer than the tap, not a
                // stale one from before it), even if something else came in front in this same poll.
                if (hold != null && latest == hold.packageName && lastEventTime >= hold.since) holdArrived = true
            }
        }
        lastQueryTime = now
        val changed = latest != currentForeground
        if (changed) currentForeground = latest
        // Hand over to normal tracking only once currentForeground is current, so an evaluate on
        // another thread sees either the hold or the app, never a gap between them. Evaluate even
        // when the app in front looks unchanged: the target may have come and gone within one poll.
        if (holdArrived) launchHold.compareAndSet(hold, null)
        if (changed || holdArrived || needsEvaluation || pauseExpired(now) || holdExpired()) evaluate()
    }

    /**
     * The app to treat as in front: a widget-launched target that hasn't shown up in usage events
     * yet, otherwise the polled one. Ends an expired hold.
     */
    private fun resolveForeground(): String? {
        val hold = launchHold.get() ?: return currentForeground
        if (SystemClock.elapsedRealtime() < hold.untilElapsed) return hold.packageName
        launchHold.compareAndSet(hold, null)
        return currentForeground
    }

    /**
     * System screens a watched app sends you to and expects you back from: the runtime permission
     * prompt, the app chooser, and the Settings app (for location, permissions, date and time).
     * While one is in front, a session carries on. Restoring there let the app catch the settings
     * on as you came back: one banking app opens Location settings as it starts and re-checks on
     * return, before the next poll could switch them off again. Resolved rather than
     * hard-coded, because the packages differ by Android version and maker; never Warden or the
     * home screen, since leaving to home must always restore.
     */
    private val overlayPackages: Set<String> by lazy {
        val home = resolvePackage(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        listOf(
            Intent(ACTION_REQUEST_PERMISSIONS),
            Intent(Intent.ACTION_CHOOSER),
            Intent(Settings.ACTION_SETTINGS),
            // Some makers keep these screens outside the main Settings app.
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            Intent(Settings.ACTION_DATE_SETTINGS),
        )
            .mapNotNull(::resolvePackage)
            .filter { it != packageName && it != home }
            .toSet()
    }

    private fun resolvePackage(intent: Intent): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        info?.activityInfo?.packageName
    }.getOrNull()

    private fun holdExpired(): Boolean =
        launchHold.get()?.let { SystemClock.elapsedRealtime() >= it.untilElapsed } == true

    private fun pauseExpired(now: Long): Boolean {
        val state = MonitorStatus.state.value
        return state is MonitorState.Paused && now >= state.untilMillis
    }

    /**
     * Reconciles the device with the current foreground app, config and pause state. Idempotent;
     * writes happen only on an actual transition.
     */
    private suspend fun evaluate() = SettingsController.lock.withLock {
        if (stopping) return@withLock
        needsEvaluation = false
        // Left active on disk, a stale snapshot would be adopted after a restart and put back
        // values from before that session, undoing anything the user changed since.
        if (clearPending && activeSnapshot == null) clearPending = runCatching { store.clearSnapshot() }.isFailure
        val cfg = config
        val now = System.currentTimeMillis()
        val paused = now < cfg.pausedUntil
        val foreground = if (screenOn) resolveForeground() else null
        val target = foreground?.takeIf { !paused }?.let { fg ->
            when {
                fg in cfg.targetPackages -> fg
                // A permission prompt or app chooser the watched app opened sits on top of it: the
                // user hasn't left. Restoring here and disabling again on the way back would hand
                // the app a fresh race when it re-checks on resume.
                fg in overlayPackages -> session?.packageName?.takeIf { it in cfg.targetPackages }
                else -> null
            }
        }

        if (target == null) {
            if (activeSnapshot != null) leave()
        } else {
            val current = session
            when {
                activeSnapshot == null -> enter(target, cfg)
                // A snapshot from a previous process while a target is in front: put things back
                // and start a clean session, so the snapshot we hold is the real "before" state.
                adopted || current == null -> {
                    leave()
                    enter(target, cfg)
                }
                current.packageName != target -> switchSession(current, target)
            }
        }
        if (activeSnapshot == null) {
            publish(
                if (paused) MonitorState.Paused(cfg.pausedUntil)
                else MonitorState.Watching(cfg.targetPackages.size, lastRestoreFailed),
            )
        }
    }

    private suspend fun enter(pkg: String, cfg: AppConfig) {
        try {
            val snapshot = SettingsController.capture(applicationContext, cfg)
            activeSnapshot = snapshot
            adopted = false
            store.saveSnapshot(snapshot) // persist BEFORE disabling, so a crash can still restore
            clearPending = false // the saved snapshot is this session's now
            val failed = SettingsController.applyDisabled(applicationContext, cfg)
            val disabled = SettingsController.managed(cfg) - failed
            val label = resolveLabel(pkg)
            session = Session(pkg, label, disabled, failed)
            publish(MonitorState.Protecting(pkg, label, disabled, failed))
            if (failed.isNotEmpty()) EngineNotifications.postDisableAlert(this, label, failed)
            runCatching { log.recordEnter(pkg, label, System.currentTimeMillis(), disabled, failed) }
        } catch (e: Exception) {
            // Any unexpected failure must not leave settings half-disabled.
            leave()
            if (e is CancellationException) throw e
            EngineNotifications.postDisableAlert(this, resolveLabel(pkg), SettingsController.managed(cfg))
        }
    }

    /** Moves straight from one target to another without a restore/disable round trip. */
    private suspend fun switchSession(previous: Session, pkg: String) {
        val label = resolveLabel(pkg)
        session = Session(pkg, label, previous.disabled, previous.failed)
        publish(MonitorState.Protecting(pkg, label, previous.disabled, previous.failed))
        runCatching {
            log.recordLeave(emptySet(), recovered = false)
            log.recordEnter(pkg, label, System.currentTimeMillis(), previous.disabled, previous.failed)
        }
    }

    /** Restores the active snapshot. Must be called with [SettingsController.lock] held. */
    private suspend fun leave() {
        val snapshot = activeSnapshot ?: return
        val recovered = adopted
        // Restore must finish even if the service scope is being cancelled.
        withContext(NonCancellable) {
            val alreadyRecovered = recovered && !runCatching { store.snapshot.first().active }.getOrDefault(true)
            val failed = if (alreadyRecovered) {
                emptySet() // Recovery.restorePending got here first and already reported.
            } else {
                runCatching { SettingsController.restore(applicationContext, snapshot) }
                    .getOrElse { SettingsController.managedIn(snapshot) }
            }
            if (!alreadyRecovered) clearPending = runCatching { store.clearSnapshot() }.isFailure
            activeSnapshot = null
            adopted = false
            session = null
            if (!alreadyRecovered) {
                val hadRestoreFailure = lastRestoreFailed.isNotEmpty()
                lastRestoreFailed = failed
                if (failed.isNotEmpty()) {
                    EngineNotifications.postRestoreAlert(this@ForegroundMonitorService, failed)
                } else if (hadRestoreFailure) {
                    EngineNotifications.cancelRestoreAlert(this@ForegroundMonitorService)
                }
                EngineNotifications.cancelDisableAlert(this@ForegroundMonitorService)
                runCatching { log.recordLeave(failed, recovered) }
                if (recovered) MonitorStatus.reportRecovery(failed)
            }
        }
    }

    override fun onDestroy() {
        // Graceful teardown: restore synchronously so we never leave settings disabled. `stopping`
        // keeps any evaluation queued behind the lock from re-entering a target afterwards.
        stopping = true
        runCatching { runBlocking { SettingsController.lock.withLock { leave() } } }
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        MonitorStatus.update(MonitorState.Stopped)
        super.onDestroy()
    }

    private fun resolveLabel(pkg: String): String = runCatching {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString().ifBlank { pkg }
    }.getOrDefault(pkg)

    // --- notification ---

    private fun publish(state: MonitorState) {
        MonitorStatus.update(state)
        if (state == lastNotified) return
        lastNotified = state
        if (notificationDismissed) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(EngineNotifications.ID_ONGOING, buildNotification(state))
        }
    }

    /** [state] is null only for the very first notification, before config has loaded. */
    private fun buildNotification(state: MonitorState?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.openIntent(this),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, EngineNotifications.CHANNEL_MONITOR)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (state == null) getString(R.string.engine_notif_starting)
                else EngineNotifications.describe(this, state),
            )
            .setSmallIcon(R.drawable.ic_stat_warden)
            .setColor(ContextCompat.getColor(this, R.color.warden_accent))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Not ongoing: on Android 13+ the user can swipe it away while the service keeps running.
            .setDeleteIntent(serviceIntent(ACTION_NOTIFICATION_DISMISSED, REQUEST_DISMISSED))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
        when (state) {
            null, MonitorState.Stopped -> Unit
            is MonitorState.Paused ->
                builder.addAction(actionFor(ACTION_RESUME, R.string.engine_action_resume, REQUEST_RESUME))
            is MonitorState.Watching, is MonitorState.Protecting ->
                builder.addAction(actionFor(ACTION_PAUSE, R.string.engine_action_pause, REQUEST_PAUSE))
        }
        hideAction()?.let(builder::addAction)
        return builder.build()
    }

    /**
     * "Hide": a foreground service must keep its notification, but the user can turn this channel
     * off. Opens that channel's settings; the monitor keeps running and Alerts stay on. Left out
     * if the phone has no such screen.
     */
    private fun hideAction(): NotificationCompat.Action? {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, EngineNotifications.CHANNEL_MONITOR)
        if (intent.resolveActivity(packageManager) == null) return null
        val pi = PendingIntent.getActivity(this, REQUEST_HIDE, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_warden, getString(R.string.engine_action_hide), pi)
            .build()
    }

    private fun actionFor(action: String, titleRes: Int, requestCode: Int): NotificationCompat.Action {
        val pi = serviceIntent(action, requestCode)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_warden, getString(titleRes), pi).build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, ForegroundMonitorService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** Service intent actions behind the notification buttons. They just write [AppConfig.pausedUntil]. */
        const val ACTION_PAUSE = "app.warden.action.PAUSE"
        const val ACTION_RESUME = "app.warden.action.RESUME"
        private const val ACTION_NOTIFICATION_DISMISSED = "app.warden.action.NOTIFICATION_DISMISSED"

        /** Protect [EXTRA_PACKAGE] now, before it opens. Sent only by [ProtectedLaunchActivity]. */
        private const val ACTION_PROTECT_LAUNCH = "app.warden.action.PROTECT_LAUNCH"
        private const val EXTRA_PACKAGE = "app.warden.extra.PACKAGE"

        /** Upper bound for a cold start to show up in usage events after a widget tap. */
        private const val LAUNCH_HOLD_MS = 10_000L

        private const val REQUEST_PAUSE = 1
        private const val REQUEST_RESUME = 2
        private const val REQUEST_HIDE = 3
        private const val REQUEST_DISMISSED = 4
        private const val TAG = "WardenMonitor"
        private const val POLL_INTERVAL_MS = 750L
        private const val IDLE_INTERVAL_MS = 5_000L
        private const val QUERY_OVERLAP_MS = 2_000L

        /** What Activity.requestPermissions sends; PackageManager's constant for it is hidden. */
        private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
        private const val INITIAL_WINDOW_MS = 6 * 60 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ForegroundMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundMonitorService::class.java))
        }

        /**
         * Asks the monitor to switch [packageName]'s settings off before it opens. The service
         * applies it only if that app is watched and the monitor isn't paused; watch
         * [MonitorStatus] for the resulting [MonitorState.Protecting].
         */
        fun protectLaunch(context: Context, packageName: String) {
            context.startForegroundService(
                Intent(context, ForegroundMonitorService::class.java)
                    .setAction(ACTION_PROTECT_LAUNCH)
                    .putExtra(EXTRA_PACKAGE, packageName),
            )
        }
    }
}
