package app.warden

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Where the optional Shizuku route stands, in the order the user moves through it. */
enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

/**
 * The optional no-computer grant: Shizuku runs `pm grant` with adb's identity, on the phone.
 * Only the one-time grant goes through here; the engine never depends on Shizuku being installed
 * or running afterward.
 */
object ShizukuGrant {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 7301
    private const val BIND_TIMEOUT_MS = 15_000L

    fun status(context: Context): ShizukuStatus {
        // pingBinder is true for the Shizuku app and for Sui (root), whichever is running.
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            // Throws if the binder died between the ping and this call; then it is simply not running.
            val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrNull()
            if (granted != null) return if (granted) ShizukuStatus.READY else ShizukuStatus.NEEDS_PERMISSION
        }
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return if (installed) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
    }

    /** True when the user refused Warden in Shizuku; asking again shows nothing. */
    fun permissionDenied(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.shouldShowRequestPermissionRationale() }
            .getOrDefault(false)

    /** False when Shizuku went away before the request could be made. */
    fun requestPermission(): Boolean =
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }.isSuccess

    /**
     * Runs the grant in a short-lived Shizuku user service. Returns null on success, otherwise a
     * reason for logs. Must be called from the main thread (Shizuku's callbacks arrive there).
     */
    suspend fun grant(context: Context): String? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, GrantUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("grant")
            .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .version(versionCode(context))

        val connected = CompletableDeferred<IGrantService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(
                    binder?.takeIf { it.pingBinder() }?.let(IGrantService.Stub::asInterface),
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.complete(null)
            }
        }
        return try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
                ?: return "Shizuku service did not start"
            withContext(Dispatchers.IO) {
                service.grantWriteSecureSettings(context.packageName).takeIf { it.isNotEmpty() }
            }
        } catch (e: RemoteException) {
            e.message ?: "Shizuku call failed"
        } catch (e: RuntimeException) {
            // Shizuku throws IllegalStateException / SecurityException when its binder went away
            // or the permission was revoked between the status check and this call.
            e.message ?: "Shizuku call failed"
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private fun versionCode(context: Context): Int =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0),
        ).toInt()
}
