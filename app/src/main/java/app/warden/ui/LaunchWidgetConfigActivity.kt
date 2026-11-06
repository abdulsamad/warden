package app.warden.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.warden.ConfigStore
import app.warden.LaunchWidgets
import app.warden.R
import app.warden.labelOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the app a Protected launch widget opens. The launcher starts this when the widget is
 * placed from its widget list (and on reconfigure, Android 12+); so does tapping a widget whose
 * app is gone. Widgets placed from inside Warden come with their app already set. Only
 * watched apps are offered: the monitor protects nothing else, so any other pick would just open
 * the app unprotected.
 */
class LaunchWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** Null while loading. */
    private var apps by mutableStateOf<List<AppInfo>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Leaving without a pick tells the launcher not to place the widget.
        setResult(RESULT_CANCELED, result())
        // Exported for launchers, so only accept a widget that really is one of ours.
        if (!LaunchWidgets.isOurs(this, appWidgetId)) {
            finish()
            return
        }
        // Placed from inside Warden moments ago, so the app is already set. Some launchers may open
        // the picker anyway; answer for the user instead of asking again.
        if (LaunchWidgets.consumeFreshPin(appWidgetId)) {
            setResult(RESULT_OK, result())
            finish()
            return
        }
        enableEdgeToEdge()
        val icons = TargetsIconCache(packageManager)
        lifecycleScope.launch { apps = watchedApps() }
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LaunchWidgetPicker(
                        apps = apps,
                        icons = icons,
                        onPick = ::pick,
                        onClose = ::finish,
                        onOpenWarden = ::openWarden,
                    )
                }
            }
        }
    }

    /** Installed, launchable watched apps, sorted by label. */
    private suspend fun watchedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val targets = runCatching { ConfigStore(applicationContext).config.first().targetPackages }
            .getOrDefault(emptySet())
        targets
            .filter { packageManager.getLaunchIntentForPackage(it) != null }
            .mapNotNull { pkg -> packageManager.labelOrNull(pkg)?.let { AppInfo(pkg, it) } }
            .sortedBy { it.label.lowercase() }
    }

    private fun pick(packageName: String) {
        lifecycleScope.launch {
            // Saved before reporting success: the result is what makes the launcher place it.
            runCatching { LaunchWidgets.bind(applicationContext, appWidgetId, packageName) }
                .onSuccess { setResult(RESULT_OK, result()) }
            finish()
        }
    }

    private fun openWarden() {
        startActivity(MainActivity.openIntent(this))
        finish()
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    companion object {
        /** Opens the picker for an existing widget (one not set up yet, or whose app is gone). */
        fun intent(context: Context, appWidgetId: Int): Intent =
            Intent(context, LaunchWidgetConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

        fun pendingIntent(context: Context, appWidgetId: Int): PendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            intent(context, appWidgetId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchWidgetPicker(
    apps: List<AppInfo>?,
    icons: TargetsIconCache,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    onOpenWarden: () -> Unit,
) {
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
                        stringResource(R.string.widget_config_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.widget_config_close_cd))
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "lead") {
                Text(
                    stringResource(R.string.widget_config_lead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
            when {
                // Loading takes a few milliseconds; a spinner would only flash.
                apps == null -> Unit
                apps.isEmpty() -> item(key = "empty") {
                    EmptyState(onOpenWarden, Modifier.widthIn(max = ContentMaxWidth))
                }
                else -> items(apps, key = { it.packageName }) { app ->
                    PickRow(app, icons, onPick, Modifier.widthIn(max = ContentMaxWidth))
                }
            }
        }
    }
}

@Composable
private fun PickRow(app: AppInfo, icons: TargetsIconCache, onPick: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) { onPick(app.packageName) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
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

@Composable
private fun EmptyState(onOpenWarden: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.widget_config_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenWarden, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.widget_config_open_warden))
        }
    }
}
