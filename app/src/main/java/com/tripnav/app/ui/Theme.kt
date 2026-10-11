package com.tripnav.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFFFF7A1A)
val Danger = Color(0xFFE5484D)
val Ok = Color(0xFF30A46C)

private val Dark = darkColorScheme(
    primary = Brand, onPrimary = Color.Black, secondary = Color(0xFF7CC4FF),
    background = Color(0xFF0E1116), surface = Color(0xFF161B22), surfaceVariant = Color(0xFF212833),
    onBackground = Color(0xFFE6EDF3), onSurface = Color(0xFFE6EDF3), onSurfaceVariant = Color(0xFF9DA7B3),
    error = Danger,
)
private val Light = lightColorScheme(
    primary = Color(0xFFE5600A), onPrimary = Color.White, secondary = Color(0xFF0B6BCB),
    background = Color(0xFFF6F7F9), surface = Color.White, surfaceVariant = Color(0xFFE9ECF0), error = Danger,
)

@Composable
fun TripNavTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
