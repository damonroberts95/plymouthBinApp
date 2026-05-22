package com.plymouthbins.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.Level

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val entries by AppLog.entries.collectAsState()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            val text = entries.joinToString("\n") { "${it.time} [${it.level}] ${it.message}" }
                            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("Plymouth Bins Log", text))
                            Toast.makeText(ctx, "Log copied (${entries.size} lines)", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = { AppLog.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear")
                    }
                },
            )
        }
    ) { pad ->
        if (entries.isEmpty()) {
            Text(
                "No log entries yet.",
                modifier = Modifier.padding(pad).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries) { entry ->
                    val color = when (entry.level) {
                        Level.INFO -> MaterialTheme.colorScheme.onSurface
                        Level.WARN -> Color(0xFFCC8800)
                        Level.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${entry.time}  ",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}
