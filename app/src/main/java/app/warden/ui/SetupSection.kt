package app.warden.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.R

/**
 * CONTRACT: `SetupSection(vm, modifier)` is called by the main screen.
 *
 * A guided, numbered checklist. Done steps shrink to one line; once everything is done the whole
 * card collapses to a single "Setup complete" row.
 */
@Composable
fun SetupSection(vm: SetupViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()

    // The "unused apps" step only exists on Android 11+, where apps can be paused when unused.
    val showUnusedStep = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val recommended = buildList {
        add(state.batteryUnrestricted)
        if (showUnusedStep) add(state.keepsPermissionsWhenUnused)
        add(state.notificationsAllowed)
    }
    val totalSteps = 2 + recommended.size
    val recommendedLeft = recommended.count { !it }
    val doneCount = listOf(state.usageAccess, state.writeSecureSettings).count { it } +
        recommended.count { it }
    val allDone = doneCount == totalSteps

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SetupHeader(
                allDone = allDone,
                subtitle = when {
                    allDone -> null
                    state.requiredComplete -> pluralStringResource(
                        R.plurals.setup_ready_recommended_left,
                        recommendedLeft,
                        recommendedLeft,
                    )
                    else -> stringResource(R.string.setup_progress, doneCount, totalSteps)
                },
            )
            SetupReveal(visible = !allDone) {
                Column(Modifier.padding(top = 8.dp)) {
                    // Help's must-read, here because Help no longer opens by itself: people should
                    // know this before granting anything.
                    Text(
                        text = stringResource(R.string.setup_restore_promise),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    var number = 0
                    SetupStep(
                        number = ++number,
                        title = stringResource(R.string.setup_usage_title),
                        done = state.usageAccess,
                        required = true,
                    ) { SetupUsageBody() }
                    SetupStep(
                        number = ++number,
                        title = stringResource(R.string.setup_grant_title),
                        done = state.writeSecureSettings,
                        required = true,
                    ) { SetupGrantBody(state, vm) }
                    SetupStep(
                        number = ++number,
                        title = stringResource(R.string.setup_battery_title),
                        done = state.batteryUnrestricted,
                        required = false,
                    ) { SetupBatteryBody(backgroundRestricted = state.backgroundRestricted) }
                    if (showUnusedStep) {
                        SetupStep(
                            number = ++number,
                            title = stringResource(R.string.setup_unused_title),
                            done = state.keepsPermissionsWhenUnused,
                            required = false,
                        ) { SetupUnusedBody() }
                    }
                    SetupStep(
                        number = ++number,
                        title = stringResource(R.string.setup_notifications_title),
                        done = state.notificationsAllowed,
                        required = false,
                    ) { SetupNotificationsBody(vm) }
                }
            }
            SetupReliabilityTips()
        }
    }
}

@Composable
private fun SetupHeader(allDone: Boolean, subtitle: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupReveal(visible = allDone, horizontal = true) {
            Row {
                SetupCheckCircle()
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            // Live regions: progress is read out as steps complete, usually on returning from a
            // settings screen, where nothing else would tell a screen reader user it worked.
            Text(
                text = stringResource(if (allDone) R.string.setup_complete else R.string.setup_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Decorative check circle; the adjacent text already states the status. */
@Composable
private fun SetupCheckCircle() {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** One checklist row: badge, title, and (only while incomplete) the explanation and action. */
@Composable
private fun SetupStep(
    number: Int,
    title: String,
    done: Boolean,
    required: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        SetupStepBadge(number, done)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // The badge's meaning travels with the title, so jumping by heading hears it too:
            // "Usage access, step 1, done, heading".
            val status = stringResource(
                if (done) R.string.setup_step_done else R.string.setup_step_not_done,
                number,
            )
            Box(Modifier.heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics {
                        heading()
                        stateDescription = status
                    },
                )
            }
            SetupReveal(visible = !done) {
                Column(Modifier.padding(top = 2.dp)) {
                    Text(
                        text = stringResource(
                            if (required) R.string.setup_tag_required else R.string.setup_tag_recommended,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (required) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SetupStepBadge(number: Int, done: Boolean) {
    val container by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = colorSpec(),
        label = "badgeContainer",
    )
    val content by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSecondaryContainer,
        animationSpec = colorSpec(),
        label = "badgeContent",
    )
    // Decorative: the step title next to it carries the number and status.
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(container)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

/** Shared reveal: enter/exit specs already snap when the system removes animations. */
@Composable
internal fun SetupReveal(
    visible: Boolean,
    horizontal: Boolean = false,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motionTween()) +
            if (horizontal) expandHorizontally(motionTween())
            else expandVertically(motionTween()),
        exit = fadeOut(exitTween()) +
            if (horizontal) shrinkHorizontally(exitTween())
            else shrinkVertically(exitTween()),
    ) { content() }
}

internal val SetupButtonModifier: Modifier = Modifier.heightIn(min = 48.dp)
