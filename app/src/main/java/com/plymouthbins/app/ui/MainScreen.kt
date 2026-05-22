package com.plymouthbins.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plymouthbins.app.BuildConfig
import com.plymouthbins.app.R
import com.plymouthbins.app.data.BinCollection
import com.plymouthbins.app.data.NotifyPrefs
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.Updater
import com.plymouthbins.app.data.WasteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenCapture: () -> Unit = {},
    vm: BinViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs by Prefs.flow(ctx).collectAsState(initial = null)

    androidx.compose.runtime.LaunchedEffect(prefs?.uprn) {
        vm.reloadFromCache()
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.loading,
        onRefresh = { vm.refresh() },
    )

    var sheetRow by remember { mutableStateOf<BinCollection?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var updateInfo by remember { mutableStateOf<Updater.Update?>(null) }
    androidx.compose.runtime.LaunchedEffect(prefs?.dismissedUpdateTag) {
        val u = withContext(Dispatchers.IO) {
            Updater.checkLatest(BuildConfig.VERSION_NAME)
        }
        updateInfo = if (u != null && u.tag != prefs?.dismissedUpdateTag) u else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_upcoming)) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Log")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad)) {
            updateInfo?.let { upd ->
                UpdateBanner(upd, onDismiss = {
                    updateInfo = null
                    scope.launch { Prefs.setDismissedUpdateTag(ctx, upd.tag) }
                })
            }
            if (prefs?.needsRecapture == true) {
                RecaptureBanner(
                    onRefresh = { vm.refresh(forceBootstrap = true) },
                    onReenter = onOpenCapture,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pullRefresh(pullRefreshState),
            ) {
                Body(
                    state = state,
                    onRowClick = { sheetRow = it },
                )
                PullRefreshIndicator(
                    refreshing = state.loading,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            AddressFooter(prefs)
        }
    }

    sheetRow?.let { row ->
        ModalBottomSheet(
            onDismissRequest = { sheetRow = null },
            sheetState = sheetState,
        ) {
            CollectionDetail(row)
        }
    }
}

@Composable
private fun AddressFooter(prefs: NotifyPrefs?) {
    if (prefs == null || prefs.addressLabel.isBlank()) return
    val updated = relativeTimeAgo(prefs.lastRefreshAtMs)
    val addressText = if (prefs.postcode.isNotBlank())
        "${prefs.addressLabel}  ·  ${prefs.postcode}"
    else prefs.addressLabel
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = addressText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (updated != null) {
            Text(
                text = "Updated $updated",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun relativeTimeAgo(ms: Long): String? {
    if (ms <= 0) return null
    val delta = (System.currentTimeMillis() - ms) / 1000
    return when {
        delta < 60 -> "just now"
        delta < 3600 -> "${delta / 60} min ago"
        delta < 86_400 -> "${delta / 3600} h ago"
        delta < 7 * 86_400 -> "${delta / 86_400} day${if (delta / 86_400 == 1L) "" else "s"} ago"
        else -> null
    }
}

@Composable
private fun UpdateBanner(upd: Updater.Update, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Update available: v${upd.tag}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (upd.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    upd.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val target = if (upd.apkUrl.isNotBlank()) upd.apkUrl else upd.htmlUrl
                val label = if (upd.apkUrl.isNotBlank()) "Download APK" else "Open release"
                OutlinedButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(target))
                    ctx.startActivity(intent)
                }) { Text(label) }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun RecaptureBanner(onRefresh: () -> Unit, onReenter: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Bin schedule incomplete",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Council returned no collections. Try a force-refresh first; only re-enter your address if that fails.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                TextButton(onClick = onReenter) { Text("Re-enter address") }
            }
        }
    }
}

@Composable
private fun Body(state: BinUiState, onRowClick: (BinCollection) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.collections.isEmpty() && !state.loading -> EmptyState(state.error)
            state.collections.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.collections) { c -> CollectionRow(c, onClick = { onRowClick(c) }) }
                if (state.error != null) item {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        if (!state.progressMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                state.progressMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LogTail()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(error: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(
                stringResource(R.string.empty_collections),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Pull down to refresh. If you've just moved, change your address in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val rowFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
private fun CollectionRow(c: BinCollection, onClick: () -> Unit) {
    val today = LocalDate.now()
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, c.date).toInt()
    val sub = when {
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        days < 0 -> "${-days} day${if (days == -1) "" else "s"} ago"
        else -> "in $days days"
    }
    val pretty = WasteType.pretty(c.wasteType)
    val dark = isSystemInDarkTheme()
    val containerColor = if (c.isInProgress)
        MaterialTheme.colorScheme.tertiaryContainer
    else
        WasteType.containerColor(c.wasteType, dark)
    val titleColor = if (c.isInProgress)
        MaterialTheme.colorScheme.onTertiaryContainer
    else
        WasteType.accentColor(c.wasteType, dark)
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = WasteType.iconRes(c.wasteType)),
                contentDescription = null,
                tint = WasteType.accentColor(c.wasteType, dark),
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = pretty,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${c.date.format(rowFmt)}  ·  $sub",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (c.status.isNotBlank()) {
                    val statusColor = when {
                        c.isInProgress -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = if (c.isInProgress) "● ${c.status}" else c.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = if (c.isInProgress) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private val detailFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

@Composable
private fun CollectionDetail(c: BinCollection) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(WasteType.pretty(c.wasteType), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(c.date.format(detailFmt), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        DetailRow("Status", c.status.ifBlank { "Not Started" })
        c.round.takeIf { it.isNotBlank() }?.let { DetailRow("Round", it) }
        DetailRow("Raw type", c.wasteType)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (label == "Round") FontFamily.Monospace else null)
    }
    HorizontalDivider()
}
