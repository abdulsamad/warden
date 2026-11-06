package app.warden.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.R
import kotlinx.coroutines.launch

private val IconSize = 40.dp
private val SheetHorizontalPadding = 24.dp

private const val TYPE_HEADER = "header"
private const val TYPE_APP = "app"

/**
 * Bottom sheet for choosing target apps.
 *
 * Ordering rule: apps selected when the sheet OPENED are grouped first; the grouping is captured
 * once per open and never recomputed while visible, so a row cannot move under the user's finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(vm: TargetsViewModel, onDismiss: () -> Unit) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    val loading by vm.isLoading.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    // Saveable, so rotating or unfolding with the sheet open keeps the same grouping (rows stay
    // put), and after process death it isn't the empty set the store hasn't refilled yet.
    val initialList = rememberSaveable { ArrayList(vm.targets.value) }
    val initialSelected = remember(initialList) { initialList.toSet() }

    // Saveable: unfolding or rotating recreates the activity, and the search shouldn't reset.
    var query by rememberSaveable { mutableStateOf("") }
    val groups by remember(apps, initialSelected) {
        derivedStateOf {
            val q = query.trim()
            val filtered = if (q.isEmpty()) apps else apps.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
            filtered.partition { it.packageName in initialSelected }
        }
    }
    val selectedGroup = groups.first
    val otherGroup = groups.second

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val onToggle = remember(vm) { { pkg: String, on: Boolean -> vm.setTarget(pkg, on) } }
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .imePadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SheetHorizontalPadding),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.targets_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    pluralStringResource(R.plurals.targets_selected_count, targets.size, targets.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SheetHorizontalPadding, vertical = 12.dp),
                placeholder = { Text(stringResource(R.string.targets_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.targets_search_clear_cd),
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = MaterialTheme.shapes.large,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> {
                        val loadingCd = stringResource(R.string.targets_loading)
                        CircularProgressIndicator(
                            Modifier
                                .align(Alignment.Center)
                                .size(32.dp)
                                .semantics { contentDescription = loadingCd },
                        )
                    }

                    selectedGroup.isEmpty() && otherGroup.isEmpty() -> {
                        Text(
                            stringResource(R.string.targets_no_match, query.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = SheetHorizontalPadding, vertical = 24.dp),
                        )
                    }

                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (selectedGroup.isNotEmpty()) {
                                item(key = "header_selected", contentType = TYPE_HEADER) {
                                    GroupHeader(stringResource(R.string.targets_section_selected))
                                }
                                items(selectedGroup, key = { it.packageName }, contentType = { TYPE_APP }) { app ->
                                    AppRow(app, app.packageName in targets, vm.icons, onToggle)
                                }
                            }
                            if (otherGroup.isNotEmpty()) {
                                item(key = "header_all", contentType = TYPE_HEADER) {
                                    GroupHeader(stringResource(R.string.targets_section_all))
                                }
                                items(otherGroup, key = { it.packageName }, contentType = { TYPE_APP }) { app ->
                                    AppRow(app, app.packageName in targets, vm.icons, onToggle)
                                }
                            }
                        }
                    }
                }
            }

            // No navigationBarsPadding: the sheet already clears the bottom bar, and in landscape
            // the extra side inset pushed the button off-center inside the centered sheet.
            Button(
                onClick = close,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SheetHorizontalPadding, vertical = 12.dp),
            ) { Text(stringResource(R.string.targets_done)) }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SheetHorizontalPadding)
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    icons: TargetsIconCache,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle(app.packageName, it) },
            )
            .padding(horizontal = SheetHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icons, app.packageName, app.label, IconSize)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/**
 * Decorative app icon. Decodes lazily (per composed item) through [icons]; until it is ready a
 * same-sized tonal placeholder with the label's first letter is shown, so nothing shifts. The
 * bitmap is always decoded at the picker size and drawn scaled for smaller uses. No fade-in:
 * this appears hundreds of times in a list.
 */
@Composable
fun AppIcon(
    icons: TargetsIconCache,
    packageName: String,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { IconSize.roundToPx() }
    // Equivalent to produceState keyed by packageName; written out because the Compose lint
    // check ProduceStateDoesNotAssignValue false-positives on this suspend-call shape.
    var icon by remember(packageName) { mutableStateOf(icons.peek(packageName)) }
    LaunchedEffect(packageName) {
        if (icon == null) icon = icons.load(packageName, px)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                // Decorative like the icon it stands in for: the row already says the app's name.
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "",
                style = if (size >= 32.dp) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
