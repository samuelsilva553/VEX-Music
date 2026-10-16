package com.vexmusic.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VexPurple = Color(0xFF8B5CF6)

private val Dark = darkColorScheme(
    primary = VexPurple,
    background = Color(0xFF0F0F12),
    surface = Color(0xFF17171C),
    onBackground = Color(0xFFEFEDF5),
    onSurface = Color(0xFFEFEDF5),
)
private val Light = lightColorScheme(
    primary = VexPurple,
    background = Color(0xFFF6F5FA),
    surface = Color.White,
)

@Composable
fun VexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
