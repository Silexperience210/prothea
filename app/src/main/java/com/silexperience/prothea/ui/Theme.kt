package com.silexperience.prothea.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFFF48FB1),
    background = Color(0xFF0E1B2C),
    surface = Color(0xFF16283E),
    onBackground = Color(0xFFEAF2FA),
    onSurface = Color(0xFFEAF2FA)
)

private val Light = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFFC2185B),
    background = Color(0xFFF6FAFD),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun ProtheaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
