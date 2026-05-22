package com.plymouthbins.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plymouthbins.app.data.AlarmLedger
import com.plymouthbins.app.data.ScheduledAlarm
import com.plymouthbins.app.data.WasteType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var alarms by remember { mutableStateOf<List<ScheduledAlarm>>(emptyList()) }

    LaunchedEffect(Unit) {
        alarms = AlarmLedger.read(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { pad ->
        if (alarms.isEmpty()) {
            Text(
                "No alarms scheduled. Run a refresh first.",
                modifier = Modifier.padding(pad).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }

        val now = System.currentTimeMillis()
        val past = alarms.filter { it.fireAtEpochMs <= now }
        val upcoming = alarms.filter { it.fireAtEpochMs > now }

        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (upcoming.isNotEmpty()) {
                item { SectionHeader("Upcoming (${upcoming.size})") }
                items(upcoming) { AlarmRow(it, past = false) }
            }
            if (past.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionHeader("Already fired (${past.size})")
                }
                items(past) { AlarmRow(it, past = true) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

private val FIRE_FMT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

@Composable
private fun AlarmRow(a: ScheduledAlarm, past: Boolean) {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(a.fireAtEpochMs), ZoneId.systemDefault())
    val color = if (past) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${dt.format(FIRE_FMT)}  ·  ${if (a.sameDay) "same-day" else "day-before"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${WasteType.pretty(a.waste)}  →  ${a.collectionDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
