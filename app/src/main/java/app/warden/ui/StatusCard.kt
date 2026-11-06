package app.warden.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.ColumnScope
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import app.warden.PAUSE_UNTIL_SCREEN_OFF
import app.warden.PauseLength
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.warden.AppConfig
import app.warden.MonitorState
import app.warden.Protection
import app.warden.R
import app.warden.SessionRecord
import java.util.Date

/** Something the user still has to do before the monitor can start. */
internal enum class MissingStep(@StringRes val labelRes: Int) {
    USAGE_ACCESS(R.string.main_missing_usage),
    ADB_PERMISSION(R.string.main_missing_adb),
    PICK_APP(R.string.main_missing_targets),
    CHOOSE_A11Y(R.string.main_missing_a11y),
}

/** Everything still missing, in the order the user should do it. Empty means the monitor can run. */
internal fun missingSteps(setup: SetupState, config: AppConfig): List<MissingStep> = buildList {
    if (!setup.usageAccess) add(MissingStep.USAGE_ACCESS)
    if (!setup.writeSecureSettings) add(MissingStep.ADB_PERMISSION)
    if (config.targetPackages.isEmpty()) add(MissingStep.PICK_APP)
    if (config.manageAccessibility && !config.managesAccessibility) {
        add(MissingStep.CHOOSE_A11Y)
    }
}

/** Display names of [set], in a stable order. */
internal fun Context.protectionNames(set: Set<Protection>): String =
    set.sortedBy { it.ordinal }.joinToString(", ") { getString(it.labelRes) }

private enum class StatusKind { SetupIncomplete, Off, Paused, Starting, Watching, Protecting }

private data class StatusModel(
    val kind: StatusKind,
    val title: String,
    val description: String,
    val missing: List<MissingStep> = emptyList(),
)

/**
 * Live status of the monitor. Whenever Warden's own UI is visible no target is in front, so the
 * Protecting state is rarely seen here; problems from the last session therefore come from
 * [lastRecord] (persisted) and not only from live state.
 */
@Composable
internal fun StatusCard(
    state: MonitorState,
    config: AppConfig,
    setup: SetupState,
    lastRecord: SessionRecord?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: (PauseLength) -> Unit,
    onResume: () -> Unit,
    /** A tapped "Finish setup" item: take the user to where it's fixed. */
    onMissingStep: (MissingStep) -> Unit,
    /** Show the one-time "test it on this phone" hint (once setup is done). */
    showTestHint: Boolean,
    onTest: () -> Unit,
    onDismissTestHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val missing = missingSteps(setup, config)

    val kind = when {
        missing.isNotEmpty() -> StatusKind.SetupIncomplete
        !config.monitorEnabled -> StatusKind.Off
        else -> when (state) {
            MonitorState.Stopped -> StatusKind.Starting
            is MonitorState.Watching -> StatusKind.Watching
            is MonitorState.Paused -> StatusKind.Paused
            is MonitorState.Protecting -> StatusKind.Protecting
        }
    }

    val appCount = config.targetPackages.size
    val model = when (kind) {
        StatusKind.SetupIncomplete -> StatusModel(
            kind,
            stringResource(R.string.main_status_finish_setup),
            stringResource(R.string.main_status_setup_hint),
            missing,
        )
        StatusKind.Off -> StatusModel(
            kind,
            stringResource(R.string.main_status_off_title),
            pluralStringResource(R.plurals.main_status_ready, appCount, appCount),
        )
        StatusKind.Paused -> {
            val until = (state as? MonitorState.Paused)?.untilMillis ?: 0L
            StatusModel(
                kind,
                if (until == PAUSE_UNTIL_SCREEN_OFF) {
                    stringResource(R.string.main_status_paused_screen_off_title)
                } else {
                    stringResource(
                        R.string.main_status_paused_title,
                        DateFormat.getTimeFormat(context).format(Date(until)),
                    )
                },
                stringResource(R.string.main_status_paused_desc),
            )
        }
        StatusKind.Starting -> StatusModel(
            kind,
            stringResource(R.string.main_status_starting_title),
            stringResource(R.string.main_status_starting_desc),
        )
        StatusKind.Watching -> StatusModel(
            kind,
            pluralStringResource(R.plurals.main_status_watching_title, appCount, appCount),
            stringResource(R.string.main_status_watching_desc),
        )
        StatusKind.Protecting -> StatusModel(
            kind,
            stringResource(
                R.string.main_status_protecting_title,
                (state as? MonitorState.Protecting)?.appLabel.orEmpty(),
            ),
            stringResource(R.string.main_status_protecting_desc),
        )
    }

    val scheme = MaterialTheme.colorScheme
    val active = kind == StatusKind.Watching || kind == StatusKind.Protecting
    val containerTarget = when (kind) {
        StatusKind.Watching, StatusKind.Protecting -> scheme.primaryContainer
        StatusKind.Paused -> scheme.secondaryContainer
        else -> scheme.surfaceVariant
    }
    val contentTarget = when (kind) {
        StatusKind.Watching, StatusKind.Protecting -> scheme.onPrimaryContainer
        StatusKind.Paused -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }

    // Problems, drawn on top of whatever state is showing.
    val restoreFailed = (state as? MonitorState.Watching)?.restoreFailed.orEmpty()
    val failedToDisable = lastRecord?.failedToDisable.orEmpty()

    val enter = motionTween<Float>()
    val exit = exitTween<Float>()
    val sizeSpec = morphTween<IntSize>()

    StatusSurface(containerTarget, contentTarget, modifier) {
        Column(Modifier.padding(20.dp)) {
            // The title beside it already says the state, so the dot needs no description.
            StatusDot(active = active, description = null)
            Spacer(Modifier.height(12.dp))

            AnimatedContent(
                targetState = model,
                transitionSpec = {
                    (fadeIn(enter) togetherWith fadeOut(exit))
                        .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
                },
                label = "statusText",
                // The live region sits on this stable container, not on the title: each state is
                // a new title node, and a screen reader only announces changes to a live region
                // that already exists. The outgoing state is hidden so only the new one is read.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) { m ->
                Column(Modifier.hiddenWhileExiting(this)) {
                    Text(
                        m.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(m.description, style = MaterialTheme.typography.bodyMedium)
                    if (m.missing.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        m.missing.forEach { step ->
                            MissingRow(stringResource(step.labelRes)) { onMissingStep(step) }
                        }
                    }
                }
            }

            Column(Modifier.animateContentSize(sizeSpec)) {
                if (restoreFailed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    val openAction = if (restoreFailed.any { it != Protection.ACCESSIBILITY }) {
                        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                    } else {
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    }
                    ProblemCallout(
                        text = stringResource(
                            R.string.main_problem_restore,
                            context.protectionNames(restoreFailed),
                        ),
                        actionLabel = stringResource(R.string.main_problem_open_settings),
                        onAction = { openSettings(context, openAction) },
                    )
                }
                if (failedToDisable.isNotEmpty() && lastRecord != null) {
                    Spacer(Modifier.height(12.dp))
                    ProblemCallout(
                        text = stringResource(
                            R.string.main_problem_last_session,
                            context.protectionNames(failedToDisable),
                            lastRecord.appLabel,
                        ),
                    )
                }
                if (showTestHint && (kind == StatusKind.Off || kind == StatusKind.Watching)) {
                    Spacer(Modifier.height(12.dp))
                    TestHint(onTest = onTest, onDismiss = onDismissTestHint)
                }
            }

            Spacer(Modifier.height(16.dp))
            StatusActions(
                kind = kind,
                monitorEnabled = config.monitorEnabled,
                onStart = onStart,
                onStop = onStop,
                onPause = onPause,
                onResume = onResume,
            )
        }
    }
}

@Composable
private fun StatusActions(
    kind: StatusKind,
    monitorEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: (PauseLength) -> Unit,
    onResume: () -> Unit,
) {
    val full = Modifier.fillMaxWidth()
    when (kind) {
        StatusKind.SetupIncomplete ->
            // Normally Start is just disabled. If the monitor is already on (a permission was
            // revoked, or the last target removed) the user still needs a way to stop it.
            if (monitorEnabled) {
                StatusOutlinedButton(stringResource(R.string.main_action_stop), full, onClick = onStop)
            } else {
                StatusButton(
                    stringResource(R.string.main_action_start),
                    full,
                    enabled = false,
                    onClick = onStart,
                )
            }
        StatusKind.Off ->
            StatusButton(stringResource(R.string.main_action_start), full, onClick = onStart)
        StatusKind.Paused ->
            StatusButton(stringResource(R.string.main_action_resume), full, onClick = onResume)
        StatusKind.Starting, StatusKind.Protecting ->
            StatusOutlinedButton(stringResource(R.string.main_action_stop), full, onClick = onStop)
        StatusKind.Watching ->
            // Side by side normally; stacked with large text so labels never wrap or clip.
            if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
                Column(full, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PauseButton(full, onPause)
                    StatusOutlinedButton(stringResource(R.string.main_action_stop), full, onClick = onStop)
                }
            } else {
                Row(full, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusOutlinedButton(
                        stringResource(R.string.main_action_stop),
                        Modifier.weight(1f),
                        onClick = onStop,
                    )
                    PauseButton(Modifier.weight(1f), onPause)
                }
            }
    }
}

/** Text scale at which side-by-side buttons stop fitting on a narrow phone. */
private const val LARGE_FONT_SCALE = 1.3f

/** "Pause" opens a short menu of lengths, anchored to the button. */
@Composable
private fun PauseButton(modifier: Modifier, onPause: (PauseLength) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        StatusButton(stringResource(R.string.main_action_pause), Modifier.fillMaxWidth()) {
            open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                PauseLength.MINUTES_15 to R.string.main_pause_15_min,
                PauseLength.HOUR to R.string.main_pause_1_hour,
                PauseLength.UNTIL_SCREEN_OFF to R.string.main_pause_screen_off,
            ).forEach { (length, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onPause(length)
                    },
                )
            }
        }
    }
}

/** One-time nudge to run the self-test, so the user sees it work before they need it. */
@Composable
private fun TestHint(onTest: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LocalContentColor.current.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.main_test_hint), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_test_hint_dismiss)) }
                TextButton(onClick = onTest) { Text(stringResource(R.string.main_test_hint_run)) }
            }
        }
    }
}

@Composable
private fun StatusButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.pressScale(source),
    ) { Text(label) }
}

@Composable
private fun StatusOutlinedButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.pressScale(source),
    ) { Text(label) }
}

/** A tappable "still to do" line that takes the user to the fix. */
@Composable
private fun MissingRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(LocalContentColor.current),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        // Decorative: the row is a button, and its text says where it goes.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProblemCallout(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val onError = MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = onError,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Decorative: the text beside it says everything.
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.textButtonColors(contentColor = onError),
                ) { Text(actionLabel) }
            }
        }
    }
}

private fun openSettings(context: Context, action: String) {
    try {
        context.startActivity(Intent(action))
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds hide this screen; nothing useful to do.
    } catch (_: SecurityException) {
    }
}

/**
 * The card and its color fade. Kept out of [StatusCard]: animated colors read there re-ran all of
 * it (state, strings, the model) on every frame of the fade, about a dozen times per change.
 */
@Composable
private fun StatusSurface(
    containerTarget: Color,
    contentTarget: Color,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container by animateColorAsState(containerTarget, colorSpec(), label = "statusContainer")
    val contentColor by animateColorAsState(contentTarget, colorSpec(), label = "statusContent")
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = contentColor),
        content = content,
    )
}
