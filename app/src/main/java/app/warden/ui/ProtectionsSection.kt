package app.warden.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.warden.AppConfig
import app.warden.Protection
import app.warden.R

/**
 * Which settings Warden manages, what the device says they are right now, and the self-test.
 * The main screen owns [showServicePicker] so a "Finish setup" item can open it directly.
 */
@Composable
fun ProtectionsSection(
    vm: MainViewModel,
    showServicePicker: Boolean,
    onShowServicePickerChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config by vm.config.collectAsStateWithLifecycle()
    val values by vm.currentValues.collectAsStateWithLifecycle()
    val services by vm.a11yServices.collectAsStateWithLifecycle()
    val selfTest by vm.selfTest.collectAsStateWithLifecycle()

    SectionCard(stringResource(R.string.main_protections_title), modifier) {
        ToggleRow(
            label = stringResource(Protection.USB_DEBUGGING.labelRes),
            checked = config.manageUsbDebugging,
            deviceOn = values?.usbOn,
            onChange = vm::setManageUsb,
        )
        ToggleRow(
            label = stringResource(Protection.DEVELOPER_OPTIONS.labelRes),
            checked = config.manageDevOptions,
            deviceOn = values?.devOn,
            onChange = vm::setManageDev,
        )
        ToggleRow(
            label = stringResource(Protection.ACCESSIBILITY.labelRes),
            checked = config.manageAccessibility,
            deviceOn = if (config.managesAccessibility) values?.isA11yOn(config) else null,
            onChange = vm::setManageA11y,
        )
        AnimatedVisibility(
            visible = config.manageAccessibility,
            enter = fadeIn(motionTween()) + expandVertically(motionTween()),
            exit = fadeOut(exitTween()) + shrinkVertically(exitTween()),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                // State indication: the line under the toggle swaps between "pick something" and
                // what will be paused, so it crossfades and the card resizes smoothly.
                val enter = motionTween<Float>()
                val exit = exitTween<Float>()
                val sizeSpec = morphTween<IntSize>()
                AnimatedContent(
                    targetState = config.managesAccessibility,
                    transitionSpec = {
                        (fadeIn(enter) togetherWith fadeOut(exit))
                            .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
                    },
                    label = "a11ySummary",
                ) { manages ->
                    if (!manages) {
                        Row(
                            Modifier.hiddenWhileExiting(this),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Decorative: the text beside it carries the message.
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.main_a11y_choose_warning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        val names = if (config.accessibilityAll) {
                            stringResource(R.string.main_a11y_all)
                        } else {
                            config.accessibilityComponents
                                .map { serviceName(it, services) }
                                .sorted()
                                .joinToString(", ")
                        }
                        Text(
                            stringResource(R.string.main_a11y_pauses, names),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.hiddenWhileExiting(this),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TonalButton(stringResource(R.string.main_a11y_choose_button)) { onShowServicePickerChange(true) }
            }
        }

        Spacer(Modifier.height(8.dp))
        TonalButton(stringResource(R.string.main_selftest_button)) { vm.requestSelfTest() }
    }

    SelfTestDialog(
        state = selfTest,
        onConfirm = vm::runSelfTest,
        onDismiss = vm::dismissSelfTest,
    )

    if (showServicePicker) {
        ServicePickerDialog(
            services = services,
            config = config,
            onAllChange = vm::setAccessibilityAll,
            onServiceChange = vm::setAccessibilityComponent,
            onDismiss = { onShowServicePickerChange(false) },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    deviceOn: Boolean?,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label)
            // Says what Warden will do, not just what the phone says: a subtitle reading "on" under
            // a switch that is off would contradict the control it explains.
            val subtitle = when {
                !checked -> R.string.main_protection_unmanaged
                deviceOn == true -> R.string.main_protection_on
                deviceOn == false -> R.string.main_protection_off
                else -> null
            }
            val enter = motionTween<Float>()
            val exit = exitTween<Float>()
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                label = "protectionSubtitle",
            ) { res ->
                if (res != null) {
                    // The row reads as one item; this keeps the fading-out subtitle out of it.
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.hiddenWhileExiting(this),
                    )
                }
            }
        }
        // High-frequency control: keep the native switch motion, no extra flourish.
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---- accessibility service picker ----

/** Label of an installed service; until the list has loaded we cannot tell, so show the raw id. */
@Composable
private fun serviceName(id: String, services: List<A11yService>?): String {
    val installed = services?.firstOrNull { it.id == id }
    return when {
        installed != null -> installed.label
        services == null -> id
        else -> stringResource(R.string.main_a11y_not_installed, id)
    }
}

/**
 * Multi-select picker. Every tap writes config straight away (no local draft), so closing the
 * dialog any way keeps what is shown. While "All services" is checked the individual rows are
 * shown checked and disabled; the saved selection comes back when "All" is unchecked.
 */
@Composable
private fun ServicePickerDialog(
    services: List<A11yService>?,
    config: AppConfig,
    onAllChange: (Boolean) -> Unit,
    onServiceChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val all = config.accessibilityAll
    val installedIds = services.orEmpty().map { it.id }.toSet()
    // Chosen services that are no longer installed stay listed so they can be removed.
    val missing = config.accessibilityComponents.filter { it !in installedIds }.sorted()

    AnimatedDialog(onDismissRequest = onDismiss) { dismiss ->
            val title = stringResource(R.string.main_a11y_picker_title)
            Column(Modifier.padding(16.dp).semantics { paneTitle = title }) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                // Shrinks to fit (landscape, large text) so the title and Done always stay on screen.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ServiceOption(
                        label = stringResource(R.string.main_a11y_all),
                        description = stringResource(R.string.main_a11y_all_desc),
                        checked = all,
                        onChange = onAllChange,
                    )
                    HorizontalDivider()
                    services.orEmpty().forEach { svc ->
                        ServiceOption(
                            label = svc.label,
                            checked = all || svc.id in config.accessibilityComponents,
                            enabled = !all,
                        ) { onServiceChange(svc.id, it) }
                        HorizontalDivider()
                    }
                    missing.forEach { id ->
                        ServiceOption(
                            label = serviceName(id, services),
                            checked = all || id in config.accessibilityComponents,
                            enabled = !all,
                        ) { onServiceChange(id, it) }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.main_a11y_done))
                }
            }
    }
}

@Composable
private fun ServiceOption(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            // Rows dim together with their checkbox when "All" takes over, instead of snapping.
            val labelColor by animateColorAsState(
                targetValue = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                animationSpec = colorSpec(),
                label = "serviceLabel",
            )
            Text(label, color = labelColor)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
