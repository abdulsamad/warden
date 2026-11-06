package app.warden.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.warden.ShizukuGrant

/*
 * Settings deep-links used by the setup steps. Each one tries its candidates in order and gives
 * up silently if the device exposes none of them (OEM builds drop some screens).
 */

private fun Context.launchFirst(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // try the next candidate
        } catch (_: SecurityException) {
            // some OEM screens are not exported; treat like not found
        }
    }
}

private fun Context.packageUri(): Uri = Uri.parse("package:$packageName")

internal fun Context.openUsageAccessSettings() {
    val plain = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        launchFirst(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri()), plain)
    } else {
        launchFirst(plain)
    }
}

internal fun Context.openDeveloperOptions() = launchFirst(
    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
)

internal fun Context.openAboutPhone() = launchFirst(
    Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
)

// Direct exemption prompt is intended for apps like this one whose core function needs it.
@SuppressLint("BatteryLife")
internal fun Context.requestBatteryExemption() = launchFirst(
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
)

internal fun Context.openAppDetailsSettings() = launchFirst(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()),
)

/** Android 11+ "Pause app activity if unused" toggle for Warden. */
internal fun Context.openUnusedAppSettings() {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        launchFirst(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri()), details)
    } else {
        launchFirst(details)
    }
}

/**
 * Phone-wide battery screen. One UI's own Battery page (with Background usage limits) first,
 * since the AOSP action opens a usage graph there; other phones get the standard screen.
 */
internal fun Context.openBatterySettings() = launchFirst(
    Intent("com.samsung.android.sm.ACTION_BATTERY"),
    Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
    Intent(Settings.ACTION_SETTINGS),
)

internal fun Context.openNotificationSettings() = launchFirst(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()),
)

/** Shizuku's own app, where the user starts it with Wireless debugging. */
internal fun Context.openShizuku() {
    packageManager.getLaunchIntentForPackage(ShizukuGrant.SHIZUKU_PACKAGE)?.let { launchFirst(it) }
}

/** Store listing first, then the official download page (no store, or a de-Googled phone). */
internal fun Context.getShizuku() = launchFirst(
    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuGrant.SHIZUKU_PACKAGE}")),
    Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")),
)
