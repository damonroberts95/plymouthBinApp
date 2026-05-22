package com.plymouthbins.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.plymouthbins.app.MainActivity
import com.plymouthbins.app.R
import com.plymouthbins.app.data.BinCollection
import com.plymouthbins.app.data.ScheduleCache
import com.plymouthbins.app.data.WasteCategory
import com.plymouthbins.app.data.WasteType
import com.plymouthbins.app.data.visibleToday
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class BinsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val rows = ScheduleCache.read(context).visibleToday()
        provideContent { WidgetContent(rows) }
    }
}

class BinsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BinsWidget()
}

class NextBinWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val next = ScheduleCache.read(context).visibleToday().firstOrNull()
        provideContent { NextBinContent(next) }
    }
}

class NextBinWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextBinWidget()
}

suspend fun updateWidgets(context: android.content.Context) {
    runCatching { BinsWidget().updateAll(context) }
    runCatching { NextBinWidget().updateAll(context) }
}

private val rowFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
private fun WidgetContent(rows: List<BinCollection>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(R.color.widget_bg_surface))
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Plymouth Bins",
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ColorProvider(R.color.widget_text_secondary),
            ),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Text(
                "No upcoming collections",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(R.color.widget_text_muted),
                ),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(rows) { r ->
                    WidgetRow(r)
                    Spacer(modifier = GlanceModifier.height(6.dp))
                }
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
    val accent = ColorProvider(wasteAccentRes(c.wasteType))
    androidx.glance.layout.Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(8.dp)
            .background(ColorProvider(wasteBgRes(c.wasteType)))
            .padding(8.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(WasteType.iconRes(c.wasteType)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent),
            modifier = GlanceModifier.size(24.dp),
        )
        Spacer(modifier = GlanceModifier.size(8.dp))
        Column {
            Text(
                WasteType.pretty(c.wasteType),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                ),
            )
            Text(
                "${c.date.format(rowFmt)}  ·  $sub",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(R.color.widget_text_secondary),
                ),
            )
        }
    }
}

private val smallFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

@Composable
private fun NextBinContent(c: BinCollection?) {
    val today = LocalDate.now()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(c?.let { wasteBgRes(it.wasteType) } ?: R.color.widget_bg_surface))
            .padding(6.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        if (c == null) {
            Text(
                "—",
                style = TextStyle(
                    fontSize = 14.sp,
                    color = ColorProvider(R.color.widget_text_muted),
                ),
            )
            return@Column
        }
        val accent = ColorProvider(wasteAccentRes(c.wasteType))
        Image(
            provider = ImageProvider(WasteType.iconRes(c.wasteType)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent),
            modifier = GlanceModifier.size(32.dp),
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, c.date).toInt()
        val sub = when {
            days == 0 -> "Today"
            days == 1 -> "Tomorrow"
            else -> c.date.format(smallFmt)
        }
        Text(
            sub,
            maxLines = 1,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            ),
        )
    }
}

private fun wasteBgRes(waste: String): Int = when (WasteType.category(waste)) {
    WasteCategory.RECYCLING -> R.color.widget_bg_recycling
    WasteCategory.GARDEN -> R.color.widget_bg_garden
    WasteCategory.FOOD -> R.color.widget_bg_food
    WasteCategory.GLASS -> R.color.widget_bg_glass
    WasteCategory.GENERAL -> R.color.widget_bg_general
    else -> R.color.widget_bg_surface
}

private fun wasteAccentRes(waste: String): Int = when (WasteType.category(waste)) {
    WasteCategory.RECYCLING -> R.color.widget_accent_recycling
    WasteCategory.GARDEN -> R.color.widget_accent_garden
    WasteCategory.FOOD -> R.color.widget_accent_food
    WasteCategory.GLASS -> R.color.widget_accent_glass
    WasteCategory.GENERAL -> R.color.widget_accent_general
    else -> R.color.widget_text_title
}
