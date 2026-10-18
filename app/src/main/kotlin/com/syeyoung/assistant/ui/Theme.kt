package com.syeyoung.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web app's palette: neutral surfaces, one ink-blue accent.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5BE0), onPrimary = Color.White, primaryContainer = Color(0xFFE6ECFD), onPrimaryContainer = Color(0xFF1B1F27),
    background = Color(0xFFF6F7F9), surface = Color.White, surfaceVariant = Color(0xFFEEF0F4), onSurface = Color(0xFF1B1F27),
    onSurfaceVariant = Color(0xFF5B6372), outline = Color(0xFFC4CAD4), outlineVariant = Color(0xFFDDE1E8), error = Color(0xFFD13B3B),
    surfaceContainer = Color(0xFFF6F7F9), surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFEEF0F4),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7D9CFF), onPrimary = Color(0xFF0E1220), primaryContainer = Color(0xFF24304F), onPrimaryContainer = Color(0xFFE7E9EE),
    background = Color(0xFF121419), surface = Color(0xFF1A1D24), surfaceVariant = Color(0xFF232733), onSurface = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFFA3AAB8), outline = Color(0xFF3B4252), outlineVariant = Color(0xFF2C313D), error = Color(0xFFFF6B6B),
    surfaceContainer = Color(0xFF121419), surfaceContainerLow = Color(0xFF1A1D24), surfaceContainerHigh = Color(0xFF232733),
)

val Warn = Color(0xFFD9A400)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

fun parseColor(hex: String?, fallback: Color = Color.Gray): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
