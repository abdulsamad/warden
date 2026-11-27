package app.warden

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Protected launch shortcuts: a home screen icon that opens one watched app through
 * [ProtectedLaunchActivity], exactly as a Protected launch widget does. Launchers keep widgets on
 * the grid but treat a shortcut like an app icon, so it can go in a folder.
 *
 * Placed from Apps to watch with [requestPin]. Nothing is stored: the shortcut's own intent
 * carries the package. Launchers built on Launcher3 badge a pinned shortcut with Warden's icon,
 * which tells it apart from the app's own icon.
 */
object LaunchShortcuts {

    private val _added = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Label of an app whose shortcut was just placed, for a confirmation. In process only, like
     * [LaunchWidgets.added]: the shortcut itself is the lasting proof.
     */
    val added: SharedFlow<String> = _added.asSharedFlow()

    /** True when the launcher can place a shortcut at an app's request (most can; some can't). */
    fun canRequestPin(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /**
     * Asks the launcher to place a shortcut for [packageName]. The launcher shows its own dialog;
     * on Add, [LaunchShortcutPinReceiver] reports it. False when the launcher refused, so the
     * caller can try a widget instead.
     */
    fun requestPin(context: Context, packageName: String): Boolean = runCatching {
        val label = context.packageManager.labelOrNull(packageName) ?: return false
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID_PREFIX + packageName)
            .setShortLabel(label)
            .setIcon(icon(context, packageName))
            .setIntent(ProtectedLaunchActivity.shortcutIntent(context, packageName))
            .build()
        // Nothing is added to it, so immutable. One per app, so two quick pins never overwrite each other.
        val callback = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            Intent(context, LaunchShortcutPinReceiver::class.java).putExtra(EXTRA_PACKAGE, packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, callback.intentSender)
    }.getOrDefault(false)

    internal fun pinned(context: Context, packageName: String) {
        context.packageManager.labelOrNull(packageName)?.let { _added.tryEmit(it) }
    }

    /**
     * The app's own icon. An adaptive one goes in unmasked at full layer size, so the launcher
     * shapes it like every other icon instead of shrinking it onto a plain background.
     */
    private fun icon(context: Context, packageName: String): IconCompat {
        val density = context.resources.displayMetrics.density
        val drawable = context.packageManager.getApplicationIcon(packageName)
        if (drawable is AdaptiveIconDrawable) {
            val inset = AdaptiveIconDrawable.getExtraInsetFraction()
            val px = (ICON_DP * density * (1 + 2 * inset)).toInt()
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            listOfNotNull(drawable.background, drawable.foreground).forEach {
                it.setBounds(0, 0, px, px)
                it.draw(canvas)
            }
            return IconCompat.createWithAdaptiveBitmap(bitmap)
        }
        val px = (ICON_DP * density).toInt()
        return IconCompat.createWithBitmap(drawable.toBitmap(px, px))
    }

    internal const val EXTRA_PACKAGE = "app.warden.extra.SHORTCUT_PACKAGE"

    private const val SHORTCUT_ID_PREFIX = "launch_"

    /** The visible part of the icon; an adaptive icon's layers reach a quarter further each side. */
    private const val ICON_DP = 72
}

/**
 * Success callback of [LaunchShortcuts.requestPin]: the launcher placed the shortcut. Not
 * exported: only the system, through Warden's own PendingIntent, reaches it.
 */
class LaunchShortcutPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(LaunchShortcuts.EXTRA_PACKAGE) ?: return
        LaunchShortcuts.pinned(context.applicationContext, pkg)
    }
}
