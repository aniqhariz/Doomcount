package com.doomcount.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DoomColorScheme = darkColorScheme(
    primary = DoomRed,
    onPrimary = DoomOnSurface,
    background = DoomBackground,
    surface = DoomSurface,
    onSurface = DoomOnSurface,
    onBackground = DoomOnSurface,
    outline = DoomSubtle
)

@Composable
fun DoomcountTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DoomColorScheme,
        typography = Typography,
        content = content
    )
}
