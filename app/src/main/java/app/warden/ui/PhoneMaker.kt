package app.warden.ui

import android.os.Build
import androidx.annotation.StringRes
import app.warden.R

/**
 * Phone-maker builds that need setup steps stock Android doesn't. Android can't report any of
 * these back to us, so Setup can only show the advice, not check it.
 *
 * [grantHint]: an extra Developer options toggle some builds require before `pm grant` works.
 * [batteryHint]: where that build hides "let it run in the background".
 * [tips]: extra items for the collapsed "How to keep Warden running" list.
 */
internal enum class PhoneMaker(
    @StringRes val grantHint: Int?,
    @StringRes val batteryHint: Int?,
    val tips: List<Int>,
) {
    SAMSUNG(
        grantHint = null,
        batteryHint = R.string.setup_battery_samsung,
        tips = listOf(R.string.setup_tip_samsung_never_sleeping, R.string.setup_tip_samsung_keep_open),
    ),

    /** Xiaomi, Redmi and Poco (MIUI / HyperOS) all report the manufacturer as Xiaomi. */
    XIAOMI(
        grantHint = R.string.setup_grant_xiaomi,
        batteryHint = R.string.setup_battery_xiaomi,
        tips = listOf(R.string.setup_tip_lock_recents),
    ),

    /** Oppo, Realme and OnePlus: ColorOS, Realme UI and OxygenOS share one base. */
    COLOROS(
        grantHint = R.string.setup_grant_coloros,
        batteryHint = R.string.setup_battery_coloros,
        tips = listOf(R.string.setup_tip_lock_recents),
    ),

    /** Vivo and iQOO (Funtouch OS / OriginOS). */
    VIVO(
        grantHint = null,
        batteryHint = R.string.setup_battery_vivo,
        tips = listOf(R.string.setup_tip_lock_recents),
    ),

    OTHER(grantHint = null, batteryHint = null, tips = emptyList()),
    ;

    companion object {
        /** The maker of this phone. Fixed for the life of the process. */
        val current: PhoneMaker by lazy {
            when (Build.MANUFACTURER.lowercase()) {
                "samsung" -> SAMSUNG
                "xiaomi", "redmi", "poco" -> XIAOMI
                "oppo", "realme", "oneplus" -> COLOROS
                "vivo", "iqoo" -> VIVO
                else -> OTHER
            }
        }
    }
}
