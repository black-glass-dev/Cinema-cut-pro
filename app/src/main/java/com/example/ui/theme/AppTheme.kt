package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CinemaCutColorScheme = lightColorScheme(
    primary = CinemaColors.Red,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = CinemaColors.Ink,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = CinemaColors.Page,
    onBackground = CinemaColors.Ink,
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = CinemaColors.Ink,
    surfaceVariant = CinemaColors.LightGray,
    onSurfaceVariant = CinemaColors.SecondaryText,
    error = EditorColors.Error,
    outline = CinemaColors.Divider,
    outlineVariant = CinemaColors.LightGray
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CinemaCutColorScheme,
        typography = EditorTypography.EditorTypography,
        content = content
    )
}
