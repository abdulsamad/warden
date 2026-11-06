package app.warden.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.warden.LaunchWidgets
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.R

/**
 * CONTRACT: `TargetsSection(vm, showPicker, onShowPickerChange, modifier)` is called by the main
 * screen, which owns [showPicker] so a "Finish setup" item can open the picker directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetsSection(
    vm: TargetsViewModel,
    showPicker: Boolean,
    onShowPickerChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets by vm.targets.collectAsStateWithLifecycle()
    val targetLabels by vm.targetLabels.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val removedMessage = stringResource(R.string.targets_removed)
    val undoLabel = stringResource(R.string.main_undo)
    val addedMessage = stringResource(R.string.widget_add_done)
    // Watched apps that are installed, for the widget action.
    val widgetApps = remember(targetLabels) {
        targetLabels.orEmpty()
            .mapNotNull { (pkg, label) -> label?.let { AppInfo(pkg, it) } }
            .sortedBy { it.label.lowercase() }
    }

    // The launcher placed a widget asked for below: confirm it here, where the request started.
    LaunchedEffect(snackbar) {
        LaunchWidgets.added.collect { label ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(addedMessage.format(label)) }
        }
    }

    SectionCard(stringResource(R.string.targets_title), modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(morphTween<IntSize>()),
        ) {
            val labels = targetLabels
            when {
                targets.isEmpty() -> Text(
                    stringResource(R.string.targets_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Labels resolve just after the watch list is read; avoid flashing raw package names.
                labels == null -> CircularProgressIndicator(Modifier.size(24.dp))

                else -> {
                    val chips = labels.toList()
                        .sortedBy { (pkg, label) -> (label ?: pkg).lowercase() }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        chips.forEach { (pkg, label) ->
                            key(pkg) {
                                TargetChip(
                                    vm = vm,
                                    packageName = pkg,
                                    label = label,
                                    onRemove = {
                                        // One tap removes, so a slip must be one tap to reverse.
                                        vm.setTarget(pkg, false)
                                        offerUndo(
                                            snackbar,
                                            scope,
                                            removedMessage.format(label ?: pkg),
                                            undoLabel,
                                        ) { vm.setTarget(pkg, true) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TonalButton(
                stringResource(
                    if (targets.isEmpty()) R.string.targets_choose_apps else R.string.targets_add_or_remove,
                ),
            ) { onShowPickerChange(true) }
            // Fade only: the column's animateContentSize already animates the height. An expanding
            // reveal inside it nested two size animations, so the card grew late and clipped this.
            AnimatedVisibility(
                visible = widgetApps.isNotEmpty(),
                enter = fadeIn(motionTween()),
                exit = ExitTransition.None,
            ) {
                WidgetAction(widgetApps, vm.icons)
            }
        }
    }

    if (showPicker) {
        AppPickerSheet(vm, onDismiss = { onShowPickerChange(false) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetChip(
    vm: TargetsViewModel,
    packageName: String,
    label: String?,
    onRemove: () -> Unit,
) {
    val installed = label != null
    val shownLabel = if (installed) label!! else stringResource(R.string.targets_chip_not_installed, packageName)
    val removeCd = if (installed) {
        stringResource(R.string.targets_chip_remove_cd, label!!)
    } else {
        stringResource(R.string.targets_chip_remove_not_installed_cd, packageName)
    }
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(shownLabel, maxLines = 1) },
        avatar = {
            AppIcon(
                icons = vm.icons,
                packageName = packageName,
                label = label ?: packageName,
                size = InputChipDefaults.AvatarSize,
            )
        },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = removeCd
            role = Role.Button
            onClick(label = removeCd) {
                onRemove()
                true
            }
        },
    )
}

/** Which widget dialog is open. An enum, so it survives rotation and fold in rememberSaveable. */
private enum class WidgetDialog { None, Choose, Manual }

/**
 * "Add home screen widget": places a Protected launch widget already set to the app, through the
 * launcher's own dialog. One watched app pins at once; several ask which first. Launchers that
 * can't place widgets for an app get the manual steps. Secondary to "Add or remove apps", so a
 * text button with a one-line reason above it.
 */
@Composable
private fun WidgetAction(apps: List<AppInfo>, icons: TargetsIconCache) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf(WidgetDialog.None) }
    val pin: (String) -> Unit = { pkg ->
        if (!LaunchWidgets.requestPin(context, pkg)) dialog = WidgetDialog.Manual
    }

    Column {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.widget_add_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val source = remember { MutableInteractionSource() }
        TextButton(
            onClick = {
                when {
                    !LaunchWidgets.canRequestPin(context) -> dialog = WidgetDialog.Manual
                    apps.size == 1 -> pin(apps.single().packageName)
                    else -> dialog = WidgetDialog.Choose
                }
            },
            interactionSource = source,
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            modifier = SetupButtonModifier
                // Optical alignment: the icon lines up with the line above; the press area keeps
                // its padding around it.
                .offset(x = -WidgetButtonInset)
                .pressScale(source),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.widget_add))
        }
    }

    when (dialog) {
        WidgetDialog.Choose -> ChooseWidgetAppDialog(apps, icons) { picked ->
            dialog = WidgetDialog.None
            picked?.let(pin)
        }
        WidgetDialog.Manual -> ManualWidgetStepsDialog { dialog = WidgetDialog.None }
        WidgetDialog.None -> Unit
    }
}

/** Start padding of TextButtonWithIconContentPadding, undone so the icon aligns with text above. */
private val WidgetButtonInset = 12.dp

/**
 * Which app the widget is for. The pick is acted on only after this dialog has finished leaving,
 * so the launcher's dialog never stacks on top of one still on its way out.
 */
@Composable
private fun ChooseWidgetAppDialog(
    apps: List<AppInfo>,
    icons: TargetsIconCache,
    onClosed: (picked: String?) -> Unit,
) {
    var picked by remember { mutableStateOf<String?>(null) }
    AnimatedDialog(onDismissRequest = { onClosed(picked) }) { dismiss ->
        val title = stringResource(R.string.widget_add_choose_title)
        Column(
            Modifier
                .padding(vertical = 16.dp)
                .semantics { paneTitle = title },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            // Shrinks to fit (landscape, large text) so the title and Cancel stay on screen.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                apps.forEach { app ->
                    val clickLabel = stringResource(R.string.widget_add_for_cd, app.label)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button, onClickLabel = clickLabel) {
                                picked = app.packageName
                                dismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(icons, app.packageName, app.label, 40.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = dismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.widget_add_cancel)) }
        }
    }
}

/** For launchers that can't place a widget at an app's request: the same result, by hand. */
@Composable
private fun ManualWidgetStepsDialog(onClosed: () -> Unit) {
    AnimatedDialog(onDismissRequest = onClosed) { dismiss ->
        val title = stringResource(R.string.widget_manual_title)
        // Scrolls when taller than the window (landscape, split screen, a folded or half-folded
        // phone, large text), like the self-test dialog, so Got it is always reachable.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .semantics { paneTitle = title },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                R.string.widget_manual_step1,
                R.string.widget_manual_step2,
                R.string.widget_manual_step3,
            ).forEachIndexed { i, step ->
                // Merged so TalkBack reads "1. Long-press..." as one item.
                Row(
                    Modifier
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 24.dp),
                    )
                    Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.widget_manual_done))
            }
        }
    }
}
