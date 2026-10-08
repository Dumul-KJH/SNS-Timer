package com.snstimer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1B5E4A)
private val Surface = Color(0xFFF4F7F6)
private val OnSurface = Color(0xFF12241E)

private val ColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Color(0xFF3D8B74),
    background = Surface,
    surface = Color.White,
    onBackground = OnSurface,
    onSurface = OnSurface,
    surfaceVariant = Color(0xFFE3EEE9),
    outline = Color(0xFFB7C9C2),
)

@Composable
fun SnsTimerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content,
    )
}
