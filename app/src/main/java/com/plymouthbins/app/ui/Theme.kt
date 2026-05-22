package com.plymouthbins.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightPalette = lightColorScheme(
    primary = Color(0xFF1B6F3A),
    secondary = Color(0xFF4F5B45),
    tertiary = Color(0xFFC07B2A),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF8FD9A7),
    secondary = Color(0xFFB7C3A1),
    tertiary = Color(0xFFE2B07A),
)

@Composable
fun BinTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkPalette else LightPalette
    }
    MaterialTheme(colorScheme = colors, content = content)
}
