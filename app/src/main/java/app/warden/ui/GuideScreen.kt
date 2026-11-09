package app.warden.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.warden.R

/** One card of the guide: a title, an optional lead paragraph, and numbered or bulleted lines. */
private class GuideSection(
    @StringRes val title: Int,
    @StringRes val lead: Int? = null,
    val items: List<Int> = emptyList(),
    val numbered: Boolean = false,
    /** The one card that must be read: tinted, bordered, check-marked, shown first. */
    val highlight: Boolean = false,
    /** Caveat shown under the items with an info icon, so it doesn't read like a promise. */
    @StringRes val note: Int? = null,
    /** Index of the item followed by the copyable adb command and the Shizuku alternative. */
    val commandAfter: Int? = null,
)

private val sections = listOf(
    GuideSection(
        R.string.guide_restore_title,
        lead = R.string.guide_restore_lead,
        items = listOf(R.string.guide_restore_crash),
        highlight = true,
        note = R.string.guide_restore_changes,
    ),
    GuideSection(
        R.string.guide_setup_title,
        items = listOf(
            R.string.guide_setup_grant,
            R.string.guide_setup_usage,
            R.string.guide_setup_running,
            R.string.guide_setup_apps,
            R.string.guide_setup_test,
        ),
        numbered = true,
        commandAfter = 0,
    ),
    GuideSection(R.string.guide_targets_title, lead = R.string.guide_targets_lead),
    GuideSection(
        R.string.guide_daily_title,
        items = listOf(
            R.string.guide_daily_open,
            R.string.guide_daily_pause,
            R.string.guide_daily_alert,
        ),
    ),
    // Why the widget exists and how to use it, right after everyday use: the one case where
    // opening an app as usual is not enough.
    GuideSection(
        R.string.guide_widget_title,
        lead = R.string.guide_widget_lead,
        items = listOf(
            R.string.guide_widget_add,
            R.string.guide_widget_close,
            R.string.guide_widget_open,
        ),
        numbered = true,
        note = R.string.guide_widget_note,
    ),
    GuideSection(
        R.string.guide_tips_title,
        items = listOf(
            R.string.guide_tips_screen_off,
            R.string.guide_tips_tile,
            R.string.guide_tips_hide,
            R.string.guide_tips_recents,
        ),
    ),
    GuideSection(
        R.string.guide_trouble_title,
        items = listOf(
            R.string.guide_trouble_started,
            R.string.guide_trouble_restricted,
            R.string.guide_trouble_listed,
            R.string.guide_trouble_widget,
            R.string.guide_trouble_a11y,
            R.string.guide_trouble_test,
            R.string.guide_trouble_root,
            R.string.guide_trouble_uninstall,
        ),
    ),
)

/**
 * Full-screen Help, opened only from the top bar (the Setup card is the onboarding).
 * Static text only, so it costs nothing until it's opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    // Same scroll-edge cue as the main bar: it tints only once cards scroll under it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = ScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = scrollBehavior,
                title = {
                    Text(
                        stringResource(R.string.guide_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                // Close, not Back: Help is a sheet over the main screen that rises and drops back
                // down, not a step sideways in a hierarchy. The icon should say what the motion does.
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.guide_close_cd),
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(sections, key = { it.title }) { section ->
                GuideCard(section, Modifier.widthIn(max = ContentMaxWidth))
            }
            item(key = "done") {
                val source = remember { MutableInteractionSource() }
                Button(
                    onClick = onClose,
                    interactionSource = source,
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .pressScale(source),
                ) { Text(stringResource(R.string.guide_done)) }
            }
        }
    }
}

@Composable
private fun GuideCard(section: GuideSection, modifier: Modifier) {
    if (section.highlight) {
        HighlightCard(stringResource(section.title), modifier) { GuideBody(section) }
    } else {
        SectionCard(stringResource(section.title), modifier) { GuideBody(section) }
    }
}

/** Tinted, bordered card with a check badge: the guide's one must-read. */
@Composable
private fun HighlightCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, scheme.primary),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Decorative: the title says it.
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun GuideBody(section: GuideSection) {
    val scheme = MaterialTheme.colorScheme
    Column {
        section.lead?.let {
            Text(
                stringResource(it),
                style = if (section.highlight) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.bodyMedium,
                fontWeight = if (section.highlight) FontWeight.SemiBold else null,
                color = if (section.highlight) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            if (section.items.isNotEmpty()) Spacer(Modifier.height(8.dp))
        }
        section.items.forEachIndexed { i, item ->
            val text = stringResource(item)
            when {
                section.highlight -> GuideIconLine(Icons.Filled.CheckCircle, scheme.primary, text)
                else -> GuideLine(
                    marker = if (section.numbered) "${i + 1}." else "\u2022",
                    text = text,
                )
            }
            if (i == section.commandAfter) {
                Column(Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp)) {
                    AdbGrantCommand()
                    GuideShizukuSteps()
                }
            }
        }
        section.note?.let {
            Spacer(Modifier.height(8.dp))
            GuideIconLine(
                Icons.Filled.Info,
                if (section.highlight) scheme.onPrimaryContainer else scheme.primary,
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The no-computer route, under the Copy button so it reads as an alternative, not a step. */
@Composable
private fun GuideShizukuSteps() {
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.guide_setup_shizuku),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    ShizukuSteps.forEachIndexed { i, step ->
        GuideLine(marker = "${'a' + i}.", text = stringResource(step))
    }
}

@Composable
private fun GuideIconLine(
    icon: ImageVector,
    tint: Color,
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        // Decorative: the text carries the meaning.
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = style)
    }
}

@Composable
private fun GuideLine(marker: String, text: String) {
    // Merged so TalkBack reads "1. Grant from a computer..." as one item.
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(22.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
