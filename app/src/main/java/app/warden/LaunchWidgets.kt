package app.warden

import android.util.SizeF
import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.warden.ui.LaunchWidgetConfigActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// Only which app each widget opens, kept apart from `config` so it can never disturb the monitor
// or the saved restore state. A corrupt file just leaves the widgets asking for an app again.
private val Context.launchWidgetStore: DataStore<Preferences> by preferencesDataStore(
    name = "launch_widgets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Protected launch widgets: each opens one watched app through [ProtectedLaunchActivity], so that
 * app's settings are off before it starts. Looks like the app's own icon with a small Warden badge.
 *
 * Placed either from the launcher's widget list (the user picks the app in
 * [LaunchWidgetConfigActivity]) or from inside Warden with [requestPin], where the app is already
 * known and [LaunchWidgetPinReceiver] binds it.
 */
object LaunchWidgets {

    private val _added = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Label of an app whose widget was just placed from inside Warden, for a confirmation. In
     * process only, like [MonitorStatus]: dropped when no screen is listening, which is fine
     * because the widget itself is the lasting proof.
     */
    val added: SharedFlow<String> = _added.asSharedFlow()

    /** Widgets bound by an in-app pin, by id, with when (elapsed). See [consumeFreshPin]. */
    private val freshPins = ConcurrentHashMap<Int, Long>()

    /**
     * One store read or write plus its render at a time. A pin sends APPWIDGET_UPDATE and then the
     * pin callback, and both run in the background: unserialized, the update could read the store
     * before the bind wrote it and draw "Choose app" over the bound widget.
     */
    private val lock = Mutex()

    /** Remembers [packageName] for the widget and draws it. */
    suspend fun bind(context: Context, appWidgetId: Int, packageName: String) = lock.withLock {
        context.launchWidgetStore.edit { it[key(appWidgetId)] = packageName }
        render(context, AppWidgetManager.getInstance(context), appWidgetId, packageName)
    }

    /** True when the launcher can place a widget at an app's request (most can; some can't). */
    fun canRequestPin(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /**
     * Asks the launcher to place a widget for [packageName]. The launcher shows its own dialog,
     * previewing the finished widget; on Add, [LaunchWidgetPinReceiver] binds it. Launchers skip
     * the widget's picker for these (Launcher3: `PinWidgetFlowHandler.needsConfigure` is false),
     * so the callback is the only place the app gets set. False when the launcher refused, so the
     * caller can show the manual steps instead.
     */
    fun requestPin(context: Context, packageName: String): Boolean = runCatching {
        val label = context.packageManager.labelOrNull(packageName) ?: return false
        val extras = Bundle().apply {
            putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, appViews(context, packageName, label))
        }
        // Mutable so the system can add EXTRA_APPWIDGET_ID (required from 12). Explicit, as a
        // mutable PendingIntent must be. One per app, so two quick pins never overwrite each other.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            Intent(context, LaunchWidgetPinReceiver::class.java).putExtra(EXTRA_PACKAGE, packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        AppWidgetManager.getInstance(context).requestPinAppWidget(provider(context), extras, callback)
    }.getOrDefault(false)

    /** True when [appWidgetId] is one of Warden's widgets. */
    internal fun isOurs(context: Context, appWidgetId: Int): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider == provider(context)

    /**
     * True, once, for a widget an in-app pin bound in the last few seconds. A launcher that opens
     * the picker right after an in-app pin anyway can then be answered at once, while reconfiguring
     * the widget later still shows the picker.
     */
    internal fun consumeFreshPin(appWidgetId: Int): Boolean {
        val at = freshPins.remove(appWidgetId) ?: return false
        return SystemClock.elapsedRealtime() - at < FRESH_PIN_MS
    }

    internal suspend fun bindPinned(context: Context, appWidgetId: Int, packageName: String) {
        bind(context, appWidgetId, packageName)
        // Only once bound: a picker answered for a widget with no app would leave it unusable.
        freshPins[appWidgetId] = SystemClock.elapsedRealtime()
        context.packageManager.labelOrNull(packageName)?.let { _added.tryEmit(it) }
    }

    internal suspend fun refresh(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) =
        lock.withLock {
            val saved = context.launchWidgetStore.data.first()
            for (id in appWidgetIds) runCatching { render(context, manager, id, saved[key(id)]) }
        }

    internal suspend fun forget(context: Context, appWidgetIds: IntArray) {
        lock.withLock { context.launchWidgetStore.edit { p -> appWidgetIds.forEach { p.remove(key(it)) } } }
    }

    private fun key(appWidgetId: Int) = stringPreferencesKey("widget_$appWidgetId")

    private fun provider(context: Context) = ComponentName(context, LaunchWidgetProvider::class.java)

    private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int, packageName: String?) {
        val label = packageName?.let { context.packageManager.labelOrNull(it) }
        val views = if (packageName != null && label != null) {
            val icon = appIcon(context, packageName) // once: both sizes share it
            sized(context) {
                appViews(context, packageName, label, icon).apply {
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        ProtectedLaunchActivity.pendingIntent(context, appWidgetId, packageName),
                    )
                }
            }
        } else {
            // Not set up yet, or its app was uninstalled: tapping picks an app.
            sized(context) {
                RemoteViews(context.packageName, R.layout.widget_launch).apply {
                    setImageViewResource(R.id.widget_icon, R.mipmap.ic_launcher)
                    setTextViewText(R.id.widget_label, context.getString(R.string.widget_choose))
                    setViewVisibility(R.id.widget_badge, View.GONE)
                    setContentDescription(R.id.widget_root, context.getString(R.string.widget_choose_cd))
                    setOnClickPendingIntent(R.id.widget_root, LaunchWidgetConfigActivity.pendingIntent(context, appWidgetId))
                }
            }
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    /**
     * The widget in two sizes on Android 12+: with its name, and the icon alone for a cell too short
     * for both (a phone's landscape home screen, the largest text sizes), so nothing is clipped.
     * The launcher shows the biggest that fits, and picks again on every resize, rotation or fold.
     * [build] runs once per size: a combined RemoteViews can't be changed after it is made.
     */
    private fun sized(context: Context, build: () -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return build()
        val iconOnly = build().apply { setViewVisibility(R.id.widget_label, View.GONE) }
        return RemoteViews(
            mapOf(
                SizeF(MIN_SIZE_DP, MIN_SIZE_DP) to iconOnly,
                SizeF(MIN_SIZE_DP, labelledHeightDp(context)) to build(),
            ),
        )
    }

    /** Height the icon and its name need together, in dp, at the current text size. */
    private fun labelledHeightDp(context: Context): Float {
        val res = context.resources
        val density = res.displayMetrics.density
        // getDimension already applies the text size setting to an sp value.
        val label = res.getDimension(R.dimen.widget_label_text) / density * LABEL_LINE_HEIGHT
        return res.getDimension(R.dimen.widget_icon_frame) / density + label + LAYOUT_EXTRA_DP
    }

    /** Decoded at the largest icon size the layout uses, so it stays sharp when a foldable unfolds. */
    private fun appIcon(context: Context, packageName: String): Bitmap {
        val px = (ICON_DECODE_DP * context.resources.displayMetrics.density).toInt()
        return context.packageManager.getApplicationIcon(packageName).toBitmap(px, px)
    }

    /** The widget for an installed app: its icon with the Warden badge, and its name. No click. */
    private fun appViews(
        context: Context,
        packageName: String,
        label: String,
        icon: Bitmap = appIcon(context, packageName),
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_launch).apply {
            setImageViewBitmap(R.id.widget_icon, icon)
            setTextViewText(R.id.widget_label, label)
            setViewVisibility(R.id.widget_badge, View.VISIBLE)
            setContentDescription(R.id.widget_root, context.getString(R.string.widget_open_cd, label))
        }
    }

    internal const val EXTRA_PACKAGE = "app.warden.extra.WIDGET_PACKAGE"

    /** The biggest widget_icon (values-sw600dp); smaller layouts scale it down. */
    private const val ICON_DECODE_DP = 56

    /** The provider's minWidth and minHeight. */
    private const val MIN_SIZE_DP = 40f

    /** A TextView line, font padding included, as a multiple of its text size. */
    private const val LABEL_LINE_HEIGHT = 1.4f

    /** The layout's padding (2 dp each side) and the label's top margin (2 dp). */
    private const val LAYOUT_EXTRA_DP = 6f

    /**
     * How long after an in-app pin a picker launch is taken as the launcher's, not a reconfigure.
     * A launcher that does this opens the picker straight after its own dialog; keep the window
     * short so a user reconfiguring a new widget still gets the picker.
     */
    private const val FRESH_PIN_MS = 10_000L
}

/** Draws each widget from its saved app, and forgets widgets that were removed. */
class LaunchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) =
        inBackground { LaunchWidgets.refresh(context.applicationContext, manager, appWidgetIds) }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) =
        inBackground { LaunchWidgets.forget(context.applicationContext, appWidgetIds) }

    /**
     * Launchers report a widget's size when it is placed, resized, rotated or folded, and after
     * Warden is updated. After an update One UI drops the drawn widget and sends only this, not an
     * update, so redraw here too: otherwise it is left as the bare "Your app" placeholder, which
     * does nothing when tapped. Serialized with binds, so it can't draw over a widget being set up.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = inBackground { LaunchWidgets.refresh(context.applicationContext, manager, intArrayOf(appWidgetId)) }
}

/**
 * Success callback of [LaunchWidgets.requestPin]: the launcher placed a new widget, so bind it to
 * the app it was asked for. Not exported: only the system, through Warden's own PendingIntent,
 * reaches it. The id is still checked, since it arrives as an extra.
 */
class LaunchWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val pkg = intent.getStringExtra(LaunchWidgets.EXTRA_PACKAGE) ?: return
        if (!LaunchWidgets.isOurs(app, id)) return
        inBackground { LaunchWidgets.bindPinned(app, id, pkg) }
    }
}

/** DataStore is suspend-only; goAsync keeps the receiver alive until the work is done. */
private fun BroadcastReceiver.inBackground(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            runCatching { block() }
        } finally {
            pending.finish()
        }
    }
}

/** Label of an installed app, or null when it isn't installed. */
internal fun PackageManager.labelOrNull(packageName: String): String? = runCatching {
    val info: ApplicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }
    getApplicationLabel(info).toString().ifBlank { packageName }
}.getOrNull()
