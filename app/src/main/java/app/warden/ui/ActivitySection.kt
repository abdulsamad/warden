package app.warden.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.R
import app.warden.SessionRecord

private const val COLLAPSED_COUNT = 3

/** Recent protected sessions, newest first, from the persisted [app.warden.ActivityLog]. */
@Composable
fun ActivitySection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val records by vm.records.collectAsStateWithLifecycle()
    // "5 minutes ago" goes stale: refresh it every minute while the screen is in front, and at
    // once on every return (rows don't recompose on their own, and the log may not change).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(DateUtils.MINUTE_IN_MILLIS)
            }
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val clearedMessage = stringResource(R.string.main_activity_cleared)
    val undoLabel = stringResource(R.string.main_undo)
    val head = records.take(COLLAPSED_COUNT)
    val tail = records.drop(COLLAPSED_COUNT)

    SectionCard(stringResource(R.string.main_activity_title), modifier) {
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.main_activity_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column {
                head.forEachIndexed { i, record ->
                    if (i > 0) HorizontalDivider()
                    SessionRow(record, now)
                }
                if (tail.isNotEmpty()) {
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(motionTween()) + expandVertically(motionTween()),
                        exit = fadeOut(exitTween()) + shrinkVertically(exitTween()),
                    ) {
                        Column {
                            tail.forEach { record ->
                                HorizontalDivider()
                                SessionRow(record, now)
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tail.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.main_activity_show_less
                            else R.string.main_activity_show_all,
                        ),
                    )
                }
            }
            TextButton(
                onClick = {
                    val cleared = vm.clearActivity()
                    offerUndo(snackbar, scope, clearedMessage, undoLabel) { vm.restoreActivity(cleared) }
                },
                enabled = records.isNotEmpty(),
            ) {
                Text(stringResource(R.string.main_activity_clear))
            }
        }
    }
}

@Composable
private fun SessionRow(record: SessionRecord, now: Long) {
    val context = LocalContext.current
    val relative = DateUtils.getRelativeTimeSpanString(
        record.startedAt,
        now,
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    val tail = record.endedAt?.let { formatDuration(context, it - record.startedAt) }
        ?: stringResource(R.string.main_activity_in_progress)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                record.appLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (record.recovered) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        stringResource(R.string.main_activity_recovered),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.main_activity_when_duration, relative, tail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (record.disabled.isEmpty()) stringResource(R.string.main_activity_nothing_off)
            else stringResource(R.string.main_activity_turned_off, context.protectionNames(record.disabled)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.failedToDisable.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.main_activity_failed_disable,
                    context.protectionNames(record.failedToDisable),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (record.failedToRestore.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.main_activity_failed_restore,
                    context.protectionNames(record.failedToRestore),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatDuration(context: Context, millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    return when {
        minutes < 1 -> context.getString(R.string.main_duration_seconds, totalSeconds)
        minutes < 60 -> context.getString(R.string.main_duration_minutes, minutes)
        else -> context.getString(R.string.main_duration_hours_minutes, minutes / 60, minutes % 60)
    }
}
