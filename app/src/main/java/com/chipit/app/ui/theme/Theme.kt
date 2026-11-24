package com.chipit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ChipITColorScheme = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF003733),
    secondary = Color(0xFF81E6D9),
    tertiary = Color(0xFFF6AD55),
    background = Color(0xFF0E1318),
    surface = Color(0xFF161D24),
    surfaceVariant = Color(0xFF1F2933),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFFC8181)
)

@Composable
fun ChipITTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChipITColorScheme,
        content = content
    )
}
