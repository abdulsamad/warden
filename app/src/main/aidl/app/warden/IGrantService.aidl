package app.warden;

/** Runs inside Shizuku's shell-privileged process. See GrantUserService. */
interface IGrantService {
    /** Reserved transaction code Shizuku uses to stop a user service. */
    void destroy() = 16777114;

    /** Grants WRITE_SECURE_SETTINGS to packageName. Returns "" on success, otherwise pm's output. */
    String grantWriteSecureSettings(String packageName) = 1;
}
