package app.warden.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Emil Kowalski's motion principles, applied to Compose:
 *  - every animation has a purpose (feedback, state, spatial continuity); none is decoration;
 *  - entrances and exits use a strong ease-out, on-screen size changes a strong ease-in-out,
 *    colors plain ease; UI stays under 300 ms and exits are quicker than entrances;
 *  - surfaces leave the way they came in (dialogs scale back toward where they grew from);
 *  - no overshoot on ordinary UI - bounce is reserved for momentum, which this app has none of;
 *  - high-frequency controls (switches, checkboxes, the bottom sheet) keep their native motion;
 *  - when the user has asked the OS to remove animations, everything snaps instantly.
 *
 * Every curve and duration lives here; screens use the specs in Components.kt, never raw values.
 */

/** True when the system "remove animations" setting is on. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Strong ease-out: anything entering or leaving. Never ease-in on UI - it delays the moment being watched. */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** Strong ease-in-out: something already on screen changing size or position. */
val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/** The standard "ease" curve, for plain color changes. */
val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Press feedback: inside the 100-160 ms window, so it reads as instant. */
const val DURATION_PRESS = 160

/** In-place reveals and crossfades (accordion-sized changes). */
const val DURATION_ENTER = 200

/** Dialogs and the full-screen guide: the one surface type allowed a little longer. */
const val DURATION_MODAL = 250

/** Exits are quicker than entrances: the user has already decided. */
const val DURATION_EXIT = 150

/** Color-only state changes. */
const val DURATION_COLOR = 200

/** Scale a modal surface starts from and returns to. Never from 0: nothing appears from nothing. */
const val MODAL_INITIAL_SCALE = 0.96f

/**
 * Press feedback. A tween (not a bouncy spring) so it lands in the press window without overshoot;
 * animateFloatAsState retargets from the current value, so a quick tap-release stays continuous.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val target = if (pressed && !reduced) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) snap() else tween(DURATION_PRESS, easing = EaseOut),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * For the content inside AnimatedContent: hides the outgoing content from accessibility services
 * while it fades out. During a crossfade both versions are on screen, and a screen reader would
 * otherwise read both, or announce the old text inside a live region.
 */
fun Modifier.hiddenWhileExiting(scope: AnimatedVisibilityScope): Modifier =
    if (scope.transition.targetState == EnterExitState.PostExit) clearAndSetSemantics {} else this

// ---- haptics ----

/**
 * Haptic feedback for the few moments that earn it: a commit (the monitor starting or stopping)
 * and a verdict (the self-test passing or failing). Fired on the same frame as the visual change,
 * and never on routine taps, so it keeps meaning something. The system haptics setting still wins.
 */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

class Haptics(private val view: View) {
    fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    fun reject() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }
}
