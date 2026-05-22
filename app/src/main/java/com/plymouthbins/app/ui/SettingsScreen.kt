package com.plymouthbins.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plymouthbins.app.BuildConfig
import com.plymouthbins.app.R
import com.plymouthbins.app.data.NotifyPrefs
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.Updater
import com.plymouthbins.app.work.NotificationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeAddress: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenDebug: () -> Unit,
    vm: BinViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by Prefs.flow(ctx).collectAsState(
        initial = NotifyPrefs(true, false, 19, 0, "", "", 14, emptyList(), "", false, "", "")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ToggleRow(
                label = stringResource(R.string.notify_day_before),
                checked = prefs.dayBefore,
            ) {
                scope.launch {
                    Prefs.setDayBefore(ctx, it)
                    vm.rescheduleOnPrefChange()
                }
            }
            ToggleRow(
                label = stringResource(R.string.notify_same_day),
                checked = prefs.sameDay,
            ) {
                scope.launch {
                    Prefs.setSameDay(ctx, it)
                    vm.rescheduleOnPrefChange()
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            WindowRow(
                days = prefs.daysAhead,
                onChange = { d ->
                    scope.launch {
                        Prefs.setDaysAhead(ctx, d)
                    }
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            TimeRow(
                hour = prefs.hour,
                minute = prefs.minute,
                onPick = { h, m ->
                    scope.launch {
                        Prefs.setTime(ctx, h, m)
                        vm.rescheduleOnPrefChange()
                    }
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                "Notify for",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            CategoryToggles(
                disabled = prefs.disabledCategories,
                onChange = { cats ->
                    scope.launch {
                        Prefs.setDisabledCategories(ctx, cats)
                        vm.rescheduleOnPrefChange()
                    }
                },
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                if (prefs.uprn.isBlank()) "UPRN: not set" else "UPRN: ${prefs.uprn}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onChangeAddress, modifier = Modifier.fillMaxWidth()) {
                Text("Change address")
            }
            Spacer(modifier = Modifier.height(8.dp))
            var updateMsg by remember { mutableStateOf<String?>(null) }
            var updateChecking by remember { mutableStateOf(false) }
            val updateCtx = LocalContext.current
            OutlinedButton(
                onClick = {
                    updateChecking = true; updateMsg = null
                    scope.launch {
                        val u = withContext(Dispatchers.IO) { Updater.checkLatest(BuildConfig.VERSION_NAME) }
                        updateChecking = false
                        if (u == null) {
                            updateMsg = "Up to date (v${BuildConfig.VERSION_NAME})"
                        } else {
                            updateMsg = "Update v${u.tag} available"
                            val target = if (u.apkUrl.isNotBlank()) u.apkUrl else u.htmlUrl
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(target))
                            updateCtx.startActivity(intent)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !updateChecking,
            ) {
                Text(if (updateChecking) "Checking…" else "Check for updates")
            }
            updateMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text("View log")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                Text("View scheduled notifications")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                "Test notifications",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    NotificationScheduler.fireImmediate(
                        ctx,
                        title = ctx.getString(R.string.notify_title_day_before),
                        body = "General Waste — tomorrow (test)",
                        wasteType = "Empty Residual 240L",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test standard reminder")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    NotificationScheduler.fireImmediate(
                        ctx,
                        title = "Bin truck active",
                        body = "Empty Residual 240L — In Progress",
                        wasteType = "Empty Residual 240L",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test in-progress alert")
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun WindowRow(days: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text("Lookup window", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((days - 1).coerceAtLeast(1)) },
                enabled = days > 1,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(
                "$days days",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(
                onClick = { onChange((days + 1).coerceAtMost(30)) },
                enabled = days < 30,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
private fun CategoryToggles(disabled: Set<String>, onChange: (Set<String>) -> Unit) {
    val cats = listOf(
        com.plymouthbins.app.data.WasteCategory.GENERAL to "General Waste",
        com.plymouthbins.app.data.WasteCategory.RECYCLING to "Recycling",
        com.plymouthbins.app.data.WasteCategory.GARDEN to "Garden",
        com.plymouthbins.app.data.WasteCategory.FOOD to "Food",
        com.plymouthbins.app.data.WasteCategory.GLASS to "Glass",
    )
    cats.forEach { (cat, label) ->
        val name = cat.name
        val enabled = name !in disabled
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onChange(if (enabled) disabled + name else disabled - name)
                }
                .padding(vertical = 8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    onChange(if (checked) disabled - name else disabled + name)
                },
            )
        }
    }
}

@Composable
private fun TimeRow(hour: Int, minute: Int, onPick: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TimePickerDialog(ctx, { _, h, m -> onPick(h, m) }, hour, minute, true).show()
            }
            .padding(vertical = 12.dp),
    ) {
        Text(stringResource(R.string.notify_time), style = MaterialTheme.typography.bodyLarge)
        Text(
            String.format(Locale.UK, "%02d:%02d", hour, minute),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
