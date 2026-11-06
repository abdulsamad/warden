package app.warden.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.OneShotPreDrawListener
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Shared building blocks used by every section. FROZEN for feature work: if a section needs a
 * variant, define it privately in that section's file instead of changing these. The motion specs
 * at the bottom are the app-wide motion system; change them here, never per screen.
 */

/** Content width cap: phones use the full width, tablets and foldables get a centered column. */
internal val ContentMaxWidth = 600.dp

/*
 * Insets for full-screen Scaffolds and their top bars. The window draws edge to edge including
 * the camera cutout (enableEdgeToEdge), but Material's defaults only clear the system bars. On a
 * phone in landscape the cutout sits on a side edge and could cover the title, the action or the
 * content. safeDrawing clears the bars and the cutout.
 */
internal val ScreenInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing
internal val TopBarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Colored status indicator. Color alone is not accessible, so pass [description] whenever the dot
 * is the only thing conveying state; pass null when adjacent text already says it.
 */
@Composable
fun StatusDot(
    active: Boolean,
    description: String?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outline,
) {
    val color by animateColorAsState(
        targetValue = if (active) activeColor else inactiveColor,
        animationSpec = colorSpec(),
        label = "statusDot",
    )
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description }
                else Modifier,
            ),
    )
}

@Composable
fun TonalButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.pressScale(source),
    ) { Text(label) }
}

/**
 * Dialog that animates out as well as in. The surface fades and scales up from
 * [MODAL_INITIAL_SCALE] on open and returns the same way on close, quicker; only once the exit has
 * finished is [onDismissRequest] called, so the caller still owns whether the dialog is shown.
 * Modals stay centered: they are not anchored to a trigger. [content] gets a `dismiss` callback
 * that plays the exit - buttons inside should call it rather than [onDismissRequest].
 *
 * The backdrop is the window's own dim (it covers the whole screen, system bars included), but its
 * strength is driven from the same transition as the surface. Left alone, the stock dim only fades
 * once the window is removed, so it would linger after the surface had gone.
 */
@Composable
fun AnimatedDialog(
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    // Starts hidden; shown once the dialog window draws its first frame (see doOnPreDraw below).
    val visibility = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        closing = true
        visibility.targetState = false
    }
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(closing, visibility.isIdle) {
        // The exit has finished: hand control back to the caller.
        if (closing && visibility.isIdle) latestOnDismiss()
    }

    val enter = modalTween<Float>()
    val exit = exitTween<Float>()

    Dialog(
        onDismissRequest = { if (dismissible) dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            // Outside taps land on the tap layer below, which plays the exit first.
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        // Creating a dialog window can take longer than the entrance itself on slower phones, so
        // starting at composition would play most of it off-screen. Start on the first real frame.
        DisposableEffect(view) {
            // Start undimmed; from here on only the transition below sets the dim.
            window?.setDimAmount(0f)
            val listener = OneShotPreDrawListener.add(view) {
                if (!closing) visibility.targetState = true
            }
            onDispose { listener.removeListener() }
        }

        val transition = rememberTransition(visibility, label = "dialog")
        val dimAmount by transition.animateFloat(
            transitionSpec = { if (targetState) enter else exit },
            label = "dialogDim",
        ) { shown -> if (shown) DIM_AMOUNT else 0f }
        LaunchedEffect(window) {
            window ?: return@LaunchedEffect
            snapshotFlow { dimAmount }.collect { window.setDimAmount(it) }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(dismissible) { detectTapGestures { if (dismissible) dismiss() } },
            )
            transition.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(enter) + scaleIn(enter, initialScale = MODAL_INITIAL_SCALE),
                exit = fadeOut(exit) + scaleOut(exit, targetScale = MODAL_INITIAL_SCALE),
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 40.dp, vertical = 24.dp)
                    .widthIn(max = 560.dp),
            ) {
                // Card is a Surface, which swallows taps, so touching it never reaches the backdrop.
                // Hidden from accessibility while leaving, like the touch shield below.
                Card(Modifier.hiddenWhileExiting(this)) { content(dismiss) }
            }
            // Once closing, nothing inside takes input. A tap during the exit (Run right after
            // Cancel) could change the caller's state so it refuses the dismissal, leaving this
            // window up: invisible, undimmed, and swallowing every touch and Back press.
            if (closing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                )
            }
        }
    }
}

/** Backdrop strength once fully shown, matching the platform dialog default. */
private const val DIM_AMOUNT = 0.6f

// ---- animation specs honoring reduced motion ----

/** In-place reveals (expand/collapse) and crossfades. */
@Composable
fun <T> motionTween(): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(DURATION_ENTER, easing = EaseOut)

/** Dialogs and full-screen overlays coming in. */
@Composable
fun <T> modalTween(): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(DURATION_MODAL, easing = EaseOut)

/** Anything leaving. */
@Composable
fun <T> exitTween(): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(DURATION_EXIT, easing = EaseOut)

/** Content already on screen changing size (cards growing, chips reflowing). */
@Composable
fun <T> morphTween(): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(DURATION_MODAL, easing = EaseInOut)

@Composable
fun colorSpec(): AnimationSpec<Color> =
    if (LocalReducedMotion.current) snap() else tween(DURATION_COLOR, easing = Ease)

/**
 * Shows "[message] - Undo" and runs [undo] if it is tapped. Replaces any snackbar already showing,
 * so quick repeated actions don't queue a backlog of stale offers.
 */
fun offerUndo(
    host: SnackbarHostState,
    scope: CoroutineScope,
    message: String,
    undoLabel: String,
    undo: () -> Unit,
) {
    host.currentSnackbarData?.dismiss()
    scope.launch {
        val result = host.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) undo()
    }
}
