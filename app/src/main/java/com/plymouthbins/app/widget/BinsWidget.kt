package com.plymouthbins.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import com.plymouthbins.app.MainActivity
import com.plymouthbins.app.data.BinCollection
import com.plymouthbins.app.data.ScheduleCache
import com.plymouthbins.app.data.WasteType
import com.plymouthbins.app.data.visibleToday
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class BinsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = ScheduleCache.read(context).visibleToday().take(3)
        provideContent { WidgetContent(rows) }
    }
}

private val rowFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
private fun WidgetContent(rows: List<BinCollection>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFFF6F4EF)))
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Plymouth Bins",
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ColorProvider(Color(0xFF555555)),
            ),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Text(
                "No upcoming collections",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(Color(0xFF777777)),
                ),
            )
        } else {
            rows.forEach { r ->
                WidgetRow(r)
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun WidgetRow(c: BinCollection) {
    val today = LocalDate.now()
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, c.date).toInt()
    val sub = when {
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        else -> "in $days days"
    }
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(8.dp)
            .background(ColorProvider(Color(0xFFEEE9DF)))
            .padding(8.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            WasteType.pretty(c.wasteType),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ColorProvider(Color(0xFF202020)),
            ),
        )
        Text(
            "${c.date.format(rowFmt)}  ·  $sub",
            style = TextStyle(
                fontSize = 12.sp,
                color = ColorProvider(Color(0xFF555555)),
            ),
        )
    }
}

class BinsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BinsWidget()
}
