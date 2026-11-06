package app.warden.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.compose.BackHandler
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox

import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.MonitorStatus
import app.warden.R

/** The screen's snackbar host, so sections can offer Undo without threading it through every call. */
internal val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

class MainActivity : ComponentActivity() {

    // The same instances the screen uses: viewModels() and Compose's viewModel() share this
    // activity's ViewModelStore.
    private val vm: MainViewModel by viewModels()
    private val setupVm: SetupViewModel by viewModels()
    private val targetsVm: TargetsViewModel by viewModels()

    /** System "remove animations" setting. Re-read on every resume, so changing it applies live. */
    private var reducedMotion by mutableStateOf(false)

    /** False until the state the screen opens on has loaded; see [holdFirstFrameUntilLoaded]. */
    private var showContent by mutableStateOf(false)

    private fun readReducedMotion(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the splash launch-theme for the real app theme before drawing content.
        setTheme(app.warden.R.style.Theme_Warden)
        super.onCreate(savedInstanceState)
        // Transparent system bars on every version, so the splash hands off to the themed UI
        // without a grey platform status bar. Scaffold/TopAppBar consume the insets.
        enableEdgeToEdge()
        reducedMotion = readReducedMotion()
        lifecycleScope.launch {
            withTimeoutOrNull(FIRST_FRAME_MAX_WAIT_MILLIS) {
                vm.firstLoadDone.first { it }
                targetsVm.targetsLoaded.first { it }
            }
            showContent = true
        }
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (showContent) MainScreen(vm = vm, setupVm = setupVm, targetsVm = targetsVm)
                    }
                }
            }
        }
        holdFirstFrameUntilLoaded()
    }

    /**
     * Keeps the launch splash up until the saved config, watched apps, activity log and live device
     * values have loaded (the coroutine in onCreate sets [showContent]). Drawn before that, a set-up
     * user saw "Finish setup", no apps and an empty log, then watched it all animate into the real
     * state, and TalkBack read the wrong status first. On recreation everything is already loaded,
     * [showContent] is set before the first frame and this does nothing.
     */
    private fun holdFirstFrameUntilLoaded() {
        if (showContent) return
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!showContent) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    /**
     * Re-checks everything on every resume: returning from a Settings screen, after the adb grant,
     * and - most importantly - opening the app after the monitor was killed mid-session, when
     * reconcileOnResume restores stuck settings. This lives here, not in a Compose lifecycle
     * observer: the screen composes after the activity has resumed, and an observer added from
     * that first composition was observed to receive only ON_CREATE, silently skipping the
     * cold-start restore.
     */
    override fun onResume() {
        super.onResume()
        reducedMotion = readReducedMotion()
        refreshSetupAfterEnterAnimation()
        vm.reconcileOnResume()
        vm.refreshDeviceState()
        targetsVm.refreshIfAppsChanged()
    }

    /** Set in onStop: the window was fully hidden, so this resume plays a system enter animation. */
    private var wasHidden = false
    private var setupRefreshPending = false
    private val applySetupRefresh = Runnable {
        if (setupRefreshPending) {
            setupRefreshPending = false
            setupVm.refresh()
        }
    }

    /**
     * A setup step that changed (say, Usage access granted in Settings) collapses with an animation.
     * Run during the system's own window animation back into the app, that collapse fights it for
     * the GPU and drops to ~26 fps on a low-end phone (measured on a Moto E13). So after a full
     * hide, apply the refresh once the enter animation completes, with a fallback for resumes the
     * system sends no callback for. Returning from a dialog-style screen, which leaves this window
     * visible, has no enter animation and refreshes at once. Monitor recovery is not deferred.
     */
    private fun refreshSetupAfterEnterAnimation() {
        val view = window.decorView
        view.removeCallbacks(applySetupRefresh)
        if (!wasHidden || reducedMotion) {
            setupRefreshPending = false
            setupVm.refresh()
            return
        }
        wasHidden = false
        setupRefreshPending = true
        view.postDelayed(applySetupRefresh, SETUP_REFRESH_FALLBACK_MILLIS)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        window.decorView.removeCallbacks(applySetupRefresh)
        applySetupRefresh.run()
    }

    override fun onStop() {
        super.onStop()
        wasHidden = true
    }

    companion object {
        /**
         * How the rest of the app opens this screen: the same intent a launcher sends for the
         * app's icon. Android brings an existing task forward only when the intent matches the one
         * that started it, so a plain `Intent(context, MainActivity::class.java)` from the
         * notification or tile stacked a second copy of this screen over one opened from the
         * launcher, and the other way round.
         */
        fun openIntent(context: Context): Intent =
            Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        /** Longer than the system's activity transition, so the fallback never lands mid-animation. */
        private const val SETUP_REFRESH_FALLBACK_MILLIS = 600L

        /** Upper bound on [holdFirstFrameUntilLoaded]: a slow read never keeps the splash up. */
        private const val FIRST_FRAME_MAX_WAIT_MILLIS = 1_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    vm: MainViewModel,
    setupVm: SetupViewModel,
    targetsVm: TargetsViewModel,
) {
    val context = LocalContext.current
    val config by vm.config.collectAsStateWithLifecycle()
    val setup by setupVm.state.collectAsStateWithLifecycle()
    val monitorState by vm.monitorState.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val recoveryNotice by vm.recoveryNotice.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val selfTestSuggested by vm.selfTestSuggested.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Each "Finish setup" item goes straight to its fix: a settings screen, a picker, or (for the
    // multi-step grant) the setup card scrolled into view.
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showServicePicker by rememberSaveable { mutableStateOf(false) }
    val setupRequester = remember { BringIntoViewRequester() }
    val onMissingStep: (MissingStep) -> Unit = { step ->
        when (step) {
            MissingStep.USAGE_ACCESS -> context.openUsageAccessSettings()
            MissingStep.ADB_PERMISSION -> scope.launch { setupRequester.bringIntoView() }
            MissingStep.PICK_APP -> showAppPicker = true
            MissingStep.CHOOSE_A11Y -> showServicePicker = true
        }
    }

    val haptics = rememberHaptics()

    // Asked in context, when the user taps Start - and never allowed to block starting.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        haptics.confirm()
        vm.setMonitor(true)
        setupVm.refresh()
    }

    // Shown once per notice; acknowledged after the snackbar goes away. Resolved here (not in the
    // effect) because stringResource is only available while composing.
    val recoveryMessage = recoveryNotice?.let { notice ->
        if (notice.failed.isEmpty()) {
            context.getString(R.string.main_recovery_ok)
        } else {
            context.getString(R.string.main_recovery_failed, context.protectionNames(notice.failed))
        }
    }
    LaunchedEffect(recoveryNotice) {
        if (recoveryMessage != null) {
            snackbarHost.showSnackbar(recoveryMessage)
            MonitorStatus.acknowledgeRecovery()
        }
    }

    // Help opens only on request: the Setup card is the onboarding, so nothing pops up by itself.
    var showGuide by rememberSaveable { mutableStateOf(false) }
    val closeGuide: () -> Unit = { showGuide = false }
    // Back closes the guide rather than leaving the app: never trap the user, never surprise them.
    BackHandler(enabled = showGuide, onBack = closeGuide)

    // The bar tints once content scrolls under it, instead of cards being cut off at a hard edge.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = ScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = scrollBehavior,
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = {
                    // An icon, like every other app bar action on Android: "?" needs no translation
                    // and leaves the title the only text up here. The label is still there for
                    // anyone who needs it: TalkBack reads it, and a long press shows it.
                    HelpAction(onClick = { showGuide = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { inner ->
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHost) {
        // Scrolls full width, but content is capped and centered so tablets and foldables don't
        // stretch cards edge to edge.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(
                state = monitorState,
                config = config,
                setup = setup,
                lastRecord = records.firstOrNull(),
                onStart = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !setup.notificationsAllowed
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        haptics.confirm()
                        vm.setMonitor(true)
                    }
                },
                onStop = {
                    haptics.confirm()
                    vm.setMonitor(false)
                },
                onPause = { haptics.confirm(); vm.pause(it) },
                onResume = vm::resumeNow,
                onMissingStep = onMissingStep,
                showTestHint = selfTestSuggested == false,
                onTest = vm::requestSelfTest,
                onDismissTestHint = vm::markSelfTestSuggested,
            )

            SetupSection(setupVm, Modifier.bringIntoViewRequester(setupRequester))

            // Common path first: which apps to watch. What gets switched off has good defaults.
            TargetsSection(
                targetsVm,
                showPicker = showAppPicker,
                onShowPickerChange = { showAppPicker = it },
            )

            ProtectionsSection(
                vm,
                showServicePicker = showServicePicker,
                onShowServicePickerChange = { showServicePicker = it },
            )

            ActivitySection(vm)
        }
        }
    }
    }

    // Overlay rather than navigation: keeps the main screen's scroll position, and Help slides in
    // with the same fast ease-out as everything else (snaps with animations off).
    val overlayIn = modalTween<Float>()
    val overlayOut = exitTween<Float>()
    AnimatedVisibility(
        visible = showGuide,
        enter = slideInVertically(modalTween()) { it / 16 },
        exit = slideOutVertically(exitTween()) { it / 16 },
    ) {
        // Faded here rather than with fadeIn/fadeOut, which render the whole screen through an
        // offscreen buffer that a low-end GPU must allocate and clear on every open and close
        // (7.8 ms just to clear it on a Moto E13: a dropped frame). ModulateAlpha applies the alpha
        // to each draw instead; on an opaque full-screen surface the difference isn't visible.
        val alpha by transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) overlayIn else overlayOut },
            label = "helpAlpha",
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
        // Help is drawn over the main screen in the same window, so nothing tells a screen reader
        // the view changed. A pane title makes TalkBack announce "Help" as it appears.
        val helpTitle = stringResource(R.string.guide_title)
        Surface(
            Modifier
                .fillMaxSize()
                .semantics { paneTitle = helpTitle }
                .graphicsLayer {
                    this.alpha = alpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            GuideScreen(onClose = closeGuide)
        }
    }
    }
}

/**
 * The top bar's Help button: a "?" icon with its label as the content description, plus the
 * platform's long-press tooltip for sighted users who want the word. Native ripple and tooltip
 * motion, like every other app bar action; nothing decorative on something seen every visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpAction(onClick: () -> Unit) {
    val label = stringResource(R.string.guide_open)
    val spacing = with(LocalDensity.current) { TOOLTIP_ANCHOR_SPACING.roundToPx() }
    TooltipBox(
        positionProvider = remember(spacing) { BelowAnchorPositionProvider(spacing) },
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(painterResource(R.drawable.ic_help), contentDescription = label)
        }
    }
}

/** Material's gap between a tooltip and its anchor. */
private val TOOLTIP_ANCHOR_SPACING = 4.dp

/**
 * Places a tooltip under its anchor, centered and kept on screen. Material 3's default goes above,
 * which for a top bar action lands over the status bar's clock and battery once the app draws edge
 * to edge (the status bar area counts as free space).
 */
private class BelowAnchorPositionProvider(private val spacingPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(0, maxX)
        return IntOffset(x, anchorBounds.bottom + spacingPx)
    }
}
