package app.warden.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.ShizukuStatus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.warden.R
import kotlinx.coroutines.delay

/* Bodies of the four setup steps. Shown only while the step is incomplete. */

private const val COPIED_LABEL_MILLIS = 2000L
private const val GRANT_POLL_MILLIS = 1000L

@Composable
private fun SetupWhy(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SetupAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Spacer(Modifier.size(12.dp))
    TonalButton(label, SetupButtonModifier.then(modifier), onClick)
}

@Composable
internal fun SetupUsageBody() {
    val context = LocalContext.current
    SetupWhy(R.string.setup_usage_why)
    SetupAction(stringResource(R.string.setup_usage_action)) { context.openUsageAccessSettings() }
    // Android 15+ guards Usage access as a "restricted setting" for apps from installers it doesn't
    // trust (a downloaded APK, likely Obtainium too), whichever screen it's opened from. Only the
    // app's own page lifts it. The trusted-installer list isn't readable, so show this on every 15+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        Spacer(Modifier.size(12.dp))
        SetupWhy(R.string.setup_usage_restricted)
        SetupAction(stringResource(R.string.setup_usage_open_app_settings)) {
            context.openAppDetailsSettings()
        }
    }
}

/** The adb grant command in a selectable box, with a Copy button. Also used by the guide. */
@Composable
internal fun AdbGrantCommand() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val command = stringResource(R.string.setup_grant_command, context.packageName)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MILLIS)
            copied = false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            CommandText(command, Modifier.padding(12.dp))
        }
    }
    SetupAction(
        label = stringResource(
            if (copied) R.string.setup_grant_copied else R.string.setup_grant_copy,
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        clipboard.setText(AnnotatedString(command))
        copied = true
    }
}

/** Smallest the command text may shrink to, as a share of the theme size (and the user's font scale). */
private const val MIN_COMMAND_SCALE = 0.75f

/**
 * Monospace command that wraps only at spaces. A word wider than the box (the 40-character
 * permission name on a narrow phone) would otherwise split mid-word, and "SETTIN / GS" reads as two
 * words to anyone typing it. So the font shrinks just enough for the longest word to fit, but only
 * if that needs no less than [MIN_COMMAND_SCALE]; otherwise it stays full size and the platform
 * wraps, rather than clipping. No invisible break characters: they would end up in a manual copy
 * and break the command.
 */
@Composable
private fun CommandText(command: String, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val scale = remember(command, base, available, measurer) {
            val longest = command.split(' ').maxBy { it.length }
            val wordWidth = measurer.measure(longest, base, softWrap = false, maxLines = 1).size.width
            // A pixel of slack for rounding at the smaller size.
            val fit = (available - 1f) / wordWidth
            // If even the smallest size won't fit (large system text), keep the reader's size:
            // shrinking would cost legibility and still wrap.
            if (fit >= 1f || fit < MIN_COMMAND_SCALE) 1f else fit
        }
        Text(
            text = command,
            style = if (scale == 1f) base else base.copy(
                fontSize = base.fontSize * scale,
                lineHeight = if (base.lineHeight.isSp) base.lineHeight * scale else base.lineHeight,
                letterSpacing = if (base.letterSpacing.isSp) base.letterSpacing * scale else base.letterSpacing,
            ),
        )
    }
}

@Composable
internal fun SetupGrantBody(state: SetupState, vm: SetupViewModel) {
    val context = LocalContext.current
    // The command usually runs while this screen stays open, so there is no resume to trigger a
    // re-check. Poll while this step is showing and the app is in front; it stops once granted,
    // because a done step's body leaves composition.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(GRANT_POLL_MILLIS)
                vm.refreshIfGrantChanged()
            }
        }
    }

    SetupWhy(R.string.setup_grant_why)
    Spacer(Modifier.size(12.dp))
    SetupInstruction(1, stringResource(R.string.setup_grant_instruction_1))
    SetupInstruction(2, stringResource(R.string.setup_grant_instruction_2))
    Spacer(Modifier.size(8.dp))
    AdbGrantCommand()
    Spacer(Modifier.size(12.dp))

    // Some makers block `pm grant` until an extra Developer options toggle is on.
    PhoneMaker.current.grantHint?.let { hint ->
        SetupWhy(hint)
        Spacer(Modifier.size(12.dp))
    }

    if (state.developerOptionsEnabled) {
        TonalButton(
            stringResource(R.string.setup_grant_open_dev_options),
            SetupButtonModifier,
        ) { context.openDeveloperOptions() }
    } else {
        SetupWhy(R.string.setup_grant_dev_options_off)
        SetupAction(stringResource(R.string.setup_grant_open_about_phone)) {
            context.openAboutPhone()
        }
    }

    // On-device route. Below Android 11 Shizuku itself needs a computer to start, so it only
    // shows there if it's already running (for example Sui on a rooted phone).
    val shizukuUseful = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
        state.shizuku == ShizukuStatus.NEEDS_PERMISSION || state.shizuku == ShizukuStatus.READY
    if (shizukuUseful) ShizukuGrantBlock(state.shizuku, vm)
}

/** The on-phone grant route, step by step. Shared by Setup and the guide. */
internal val ShizukuSteps = listOf(
    R.string.setup_shizuku_step_1,
    R.string.setup_shizuku_step_2,
    R.string.setup_shizuku_step_3,
    R.string.setup_shizuku_step_4,
)

/**
 * "Rather skip the computer? Use Shizuku" - a disclosure row; the steps and the one status-driven
 * button sit under it. Closed by default so adb stays the obvious route, and it opens by itself
 * when Shizuku is plainly the route in use (already running, or an attempt just failed), so the
 * button the block exists for is never hidden at the moment it's needed.
 */
@Composable
private fun ShizukuGrantBlock(status: ShizukuStatus, vm: SetupViewModel) {
    val context = LocalContext.current
    val grant by vm.shizukuGrant.collectAsStateWithLifecycle()
    val inUse = status == ShizukuStatus.NEEDS_PERMISSION || status == ShizukuStatus.READY ||
        grant.error != null
    // Keyed on inUse: a change of relevance re-opens it, a tap closes it again, and the one-second
    // grant poll in between leaves it alone.
    var expanded by rememberSaveable(inUse) { mutableStateOf(inUse) }
    // The chevron turns on the same clock as the content, so the two read as one motion; the
    // float retargets mid-flight, so a quick open-close never jumps.
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (expanded) motionTween() else exitTween(),
        label = "shizukuChevron",
    )
    val stateText = stringResource(
        if (expanded) R.string.setup_disclosure_expanded else R.string.setup_disclosure_collapsed,
    )

    Spacer(Modifier.size(12.dp))
    HorizontalDivider()
    Spacer(Modifier.size(4.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = stateText }
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.setup_shizuku_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // Decorative: the row is a button and carries the open/closed state.
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .rotate(chevron),
        )
    }
    SetupReveal(visible = expanded) {
        Column(Modifier.padding(bottom = 4.dp)) {
            SetupWhy(R.string.setup_shizuku_why)
            Spacer(Modifier.size(12.dp))
            ShizukuSteps.forEachIndexed { i, step -> SetupInstruction(i + 1, stringResource(step)) }
            when (status) {
                ShizukuStatus.NOT_INSTALLED ->
                    SetupAction(stringResource(R.string.setup_shizuku_get)) { context.getShizuku() }
                ShizukuStatus.NOT_RUNNING ->
                    SetupAction(stringResource(R.string.setup_shizuku_open)) { context.openShizuku() }
                ShizukuStatus.NEEDS_PERMISSION, ShizukuStatus.READY -> {
                    Spacer(Modifier.size(12.dp))
                    val source = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = vm::grantWithShizuku,
                        enabled = !grant.busy,
                        interactionSource = source,
                        modifier = SetupButtonModifier.pressScale(source),
                    ) {
                        Text(
                            stringResource(
                                if (grant.busy) R.string.setup_shizuku_granting
                                else R.string.setup_shizuku_grant,
                            ),
                        )
                    }
                }
            }
            grant.error?.let {
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun SetupInstruction(number: Int, text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SetupBatteryBody(backgroundRestricted: Boolean) {
    val context = LocalContext.current
    SetupWhy(R.string.setup_battery_why)
    if (backgroundRestricted) {
        // "Restricted" overrides the exemption, and only the app's own battery page can change it.
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.setup_battery_restricted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        SetupAction(stringResource(R.string.setup_battery_open_app_settings)) {
            context.openAppDetailsSettings()
        }
        return
    }
    SetupAction(stringResource(R.string.setup_battery_action)) { context.requestBatteryExemption() }
    // The exemption alone isn't enough on these builds; their own switches live on the app's page.
    PhoneMaker.current.batteryHint?.let { hint ->
        Spacer(Modifier.size(12.dp))
        SetupWhy(hint)
        SetupAction(stringResource(R.string.setup_battery_open_app_settings)) {
            context.openAppDetailsSettings()
        }
    }
}

@Composable
internal fun SetupUnusedBody() {
    val context = LocalContext.current
    SetupWhy(R.string.setup_unused_why)
    SetupAction(stringResource(R.string.setup_unused_action)) { context.openUnusedAppSettings() }
}

/**
 * Things that keep Warden alive that Android can't report back, so they can't be auto-checked.
 * Collapsed by default and available even after setup is complete.
 */
@Composable
internal fun SetupReliabilityTips() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val maker = PhoneMaker.current
    val tips = buildList {
        addAll(maker.tips)
        add(R.string.setup_tip_no_force_stop)
        add(R.string.setup_tip_notifications)
        add(R.string.setup_tip_reinstall)
    }
    Spacer(Modifier.size(4.dp))
    TextButton(onClick = { open = !open }, modifier = SetupButtonModifier) {
        Text(stringResource(if (open) R.string.setup_tips_hide else R.string.setup_tips_show))
    }
    SetupReveal(visible = open) {
        Column {
            tips.forEachIndexed { i, tip -> SetupInstruction(i + 1, stringResource(tip)) }
            when (maker) {
                PhoneMaker.SAMSUNG -> SetupAction(stringResource(R.string.setup_tips_open_battery)) {
                    context.openBatterySettings()
                }
                PhoneMaker.OTHER -> Unit
                // Autostart and background switches live on the app's own page on these builds.
                else -> SetupAction(stringResource(R.string.setup_battery_open_app_settings)) {
                    context.openAppDetailsSettings()
                }
            }
        }
    }
}

@Composable
internal fun SetupNotificationsBody(vm: SetupViewModel) {
    val context = LocalContext.current
    // Once the system prompt has been refused it will not show again, so send the user to settings.
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) denied = true
        vm.refresh()
    }
    val canPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !denied

    SetupWhy(R.string.setup_notifications_why)
    SetupAction(
        stringResource(
            if (canPrompt) R.string.setup_notifications_allow
            else R.string.setup_notifications_open_settings,
        ),
    ) {
        if (canPrompt) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.openNotificationSettings()
        }
    }
}
