package com.plymouthbins.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.Level

@Composable
fun LogTail(
    modifier: Modifier = Modifier,
    maxLines: Int = 12,
    maxHeightDp: Int = 180,
) {
    val entries by AppLog.entries.collectAsState()
    val tail = entries.takeLast(maxLines)
    val listState = rememberLazyListState()

    LaunchedEffect(tail.size) {
        if (tail.isNotEmpty()) listState.animateScrollToItem(tail.size - 1)
    }

    if (tail.isEmpty()) return

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeightDp.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(tail) { entry ->
            val color = when (entry.level) {
                Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                Level.WARN -> Color(0xFFCC8800)
                Level.ERROR -> MaterialTheme.colorScheme.error
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text(
                    "${entry.time}  ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.Text(
                    entry.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = color,
                )
            }
        }
    }
}
