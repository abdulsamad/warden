package app.warden

import kotlin.system.exitProcess

/**
 * Started by Shizuku in a separate process running as the shell user (or root), the same identity
 * `adb shell` has. It does exactly one thing: the one-time `pm grant` from the setup step. Nothing
 * here runs in Warden's own process, and the service exits as soon as Warden unbinds.
 */
class GrantUserService : IGrantService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun grantWriteSecureSettings(packageName: String): String {
        // argv, not a shell string, so nothing can be injected; still refuse anything odd.
        if (!PACKAGE_NAME.matches(packageName)) return "invalid package name"
        val process = ProcessBuilder(
            "/system/bin/pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return if (process.waitFor() == 0) "" else output.ifEmpty { "pm exited with ${process.exitValue()}" }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
