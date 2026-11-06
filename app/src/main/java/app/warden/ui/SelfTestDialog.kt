package app.warden.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.warden.Protection
import app.warden.R
import app.warden.SelfTestResult
import app.warden.TestOutcome

/**
 * One dialog for the whole confirm -> running -> result flow, so it enters once and the content
 * cross-fades. It cannot be dismissed while the test runs: the settings are mid-flip.
 */
@Composable
fun SelfTestDialog(
    state: SelfTestUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == SelfTestUiState.Idle) return
    val dismissible = state != SelfTestUiState.Running
    val enter = motionTween<Float>()
    val exit = exitTween<Float>()
    val sizeSpec = morphTween<IntSize>()

    // The verdict is felt as well as read: a confirm for a clean pass, a reject for anything else.
    val haptics = rememberHaptics()
    // Once per verdict: the result lives in the ViewModel, so rotating, folding or a theme change
    // would otherwise run this again and buzz again.
    var verdictFelt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is SelfTestUiState.Result -> if (!verdictFelt) {
                val result = state.result
                val passed = result is SelfTestResult.Completed &&
                    result.outcomes.values.none {
                        it == TestOutcome.DISABLE_FAILED || it == TestOutcome.RESTORE_FAILED
                    }
                if (passed) haptics.confirm() else haptics.reject()
                verdictFelt = true
            }
            SelfTestUiState.Failed -> if (!verdictFelt) {
                haptics.reject()
                verdictFelt = true
            }
            else -> verdictFelt = false
        }
    }

    AnimatedDialog(onDismissRequest = onDismiss, dismissible = dismissible) { dismiss ->
        val title = stringResource(R.string.main_selftest_title)
        // Scrolls when the result is taller than the screen (landscape, a half-folded phone, large
        // text), so Done is always reachable.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .semantics { paneTitle = title },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn(enter) togetherWith fadeOut(exit))
                        .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
                },
                label = "selfTestContent",
                // Each step is read out as it arrives, the verdict most of all: without this a
                // screen reader user hears nothing when the test finishes.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) { s ->
                Column(Modifier.hiddenWhileExiting(this)) {
                    when (s) {
                        SelfTestUiState.Idle -> Unit
                        SelfTestUiState.Confirm -> ConfirmBody(onConfirm, dismiss)
                        SelfTestUiState.Running -> RunningBody()
                        is SelfTestUiState.Result -> ResultBody(s.result, dismiss)
                        SelfTestUiState.Failed -> MessageBody(R.string.main_selftest_error, dismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmBody(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Text(
        stringResource(R.string.main_selftest_confirm_body),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.main_selftest_cancel)) }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onConfirm) { Text(stringResource(R.string.main_selftest_run)) }
    }
}

@Composable
private fun RunningBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.main_selftest_running),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MessageBody(@StringRes message: Int, onDone: () -> Unit) {
    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
    DoneRow(onDone)
}

@Composable
private fun ResultBody(result: SelfTestResult, onDone: () -> Unit) {
    when (result) {
        SelfTestResult.Busy -> MessageBody(R.string.main_selftest_busy, onDone)
        SelfTestResult.NoPermission -> MessageBody(R.string.main_selftest_no_permission, onDone)
        is SelfTestResult.Completed -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Protection.entries.forEach { p ->
                    OutcomeRow(
                        label = stringResource(p.labelRes),
                        outcome = result.outcomes[p] ?: TestOutcome.SKIPPED,
                    )
                }
            }
            DoneRow(onDone)
        }
    }
}

@Composable
private fun DoneRow(onDone: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDone) { Text(stringResource(R.string.main_selftest_done)) }
    }
}

@Composable
private fun OutcomeRow(label: String, outcome: TestOutcome) {
    val scheme = MaterialTheme.colorScheme
    val icon: ImageVector
    val tint: Color
    val text: Int
    when (outcome) {
        TestOutcome.OK -> {
            icon = Icons.Filled.CheckCircle; tint = scheme.primary
            text = R.string.main_selftest_ok
        }
        TestOutcome.SKIPPED -> {
            icon = Icons.Filled.Info; tint = scheme.onSurfaceVariant
            text = R.string.main_selftest_skipped
        }
        TestOutcome.ALREADY_OFF -> {
            icon = Icons.Filled.Info; tint = scheme.onSurfaceVariant
            text = R.string.main_selftest_already_off
        }
        TestOutcome.DISABLE_FAILED -> {
            icon = Icons.Filled.Warning; tint = scheme.error
            text = R.string.main_selftest_disable_failed
        }
        TestOutcome.RESTORE_FAILED -> {
            icon = Icons.Filled.Warning; tint = scheme.error
            text = R.string.main_selftest_restore_failed
        }
    }
    // One item for a screen reader: "USB debugging, switched off and back on".
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        // Decorative: the outcome text beside it says the same thing.
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = if (tint == scheme.error) scheme.error else scheme.onSurfaceVariant,
            )
        }
    }
}
