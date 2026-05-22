package com.plymouthbins.app.data

import androidx.compose.ui.graphics.Color
import com.plymouthbins.app.R

enum class WasteCategory { GENERAL, RECYCLING, GARDEN, FOOD, GLASS, OTHER }

object WasteType {

    fun pretty(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("Empty ").removePrefix("empty ")
        s = s.replace(Regex("(?i)\\bresidual\\b"), "General Waste")
        s = s.replace(Regex("(?i)\\s*\\d+\\s*l\\b"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    fun category(raw: String): WasteCategory {
        val s = raw.lowercase()
        return when {
            "recycl" in s -> WasteCategory.RECYCLING
            "garden" in s -> WasteCategory.GARDEN
            "food" in s -> WasteCategory.FOOD
            "glass" in s -> WasteCategory.GLASS
            "residual" in s || "general" in s || "rubbish" in s || "refuse" in s -> WasteCategory.GENERAL
            else -> WasteCategory.OTHER
        }
    }

    fun iconRes(raw: String): Int = when (category(raw)) {
        WasteCategory.RECYCLING -> R.drawable.ic_waste_recycling
        WasteCategory.GARDEN -> R.drawable.ic_waste_garden
        WasteCategory.FOOD -> R.drawable.ic_waste_food
        WasteCategory.GLASS -> R.drawable.ic_waste_glass
        else -> R.drawable.ic_waste_general
    }

    fun containerColor(raw: String, dark: Boolean): Color = when (category(raw)) {
        WasteCategory.RECYCLING -> if (dark) Color(0xFF1F3A24) else Color(0xFFD8EBC9)
        WasteCategory.GARDEN -> if (dark) Color(0xFF2E3B1B) else Color(0xFFE2EDC2)
        WasteCategory.FOOD -> if (dark) Color(0xFF2C2C2C) else Color(0xFFE3E1DE)
        WasteCategory.GLASS -> if (dark) Color(0xFF1F3340) else Color(0xFFCFE2EE)
        WasteCategory.GENERAL -> if (dark) Color(0xFF3D2E22) else Color(0xFFEAD9C5)
        WasteCategory.OTHER -> if (dark) Color(0xFF2C2C2C) else Color(0xFFE3E1DE)
    }

    fun accentColor(raw: String, dark: Boolean): Color = when (category(raw)) {
        WasteCategory.RECYCLING -> if (dark) Color(0xFF8FD9A7) else Color(0xFF1B6F3A)
        WasteCategory.GARDEN -> if (dark) Color(0xFFC1D188) else Color(0xFF55692B)
        WasteCategory.FOOD -> if (dark) Color(0xFFB0B0B0) else Color(0xFF555555)
        WasteCategory.GLASS -> if (dark) Color(0xFFA8C7DB) else Color(0xFF2E6F8E)
        WasteCategory.GENERAL -> if (dark) Color(0xFFCAA98C) else Color(0xFF6F4A2C)
        WasteCategory.OTHER -> if (dark) Color(0xFFCCCCCC) else Color(0xFF555555)
    }
}
