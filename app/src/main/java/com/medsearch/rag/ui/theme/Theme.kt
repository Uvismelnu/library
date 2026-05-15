package com.medsearch.rag.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Emerald700,
    onPrimary = Cream50,
    primaryContainer = Emerald100,
    onPrimaryContainer = Emerald900,
    secondary = Coral500,
    onSecondary = Cream50,
    secondaryContainer = Coral100,
    onSecondaryContainer = Color(0xFF6B1F14),
    tertiary = Amber500,
    background = Cream100,
    onBackground = Ink900,
    surface = Cream50,
    onSurface = Ink900,
    surfaceVariant = Color(0xFFE7EFEC),
    onSurfaceVariant = Ink700,
    outline = Ink300,
    error = Color(0xFFB3261E),
    onError = Cream50
)

private val DarkColors = darkColorScheme(
    primary = Emerald500,
    onPrimary = Color(0xFF052B23),
    primaryContainer = Color(0xFF0E5045),
    onPrimaryContainer = Emerald100,
    secondary = Coral500,
    onSecondary = Color(0xFF3B1410),
    tertiary = Amber500,
    background = Color(0xFF0E1815),
    onBackground = Color(0xFFDDE7E3),
    surface = Color(0xFF152221),
    onSurface = Color(0xFFDDE7E3),
    surfaceVariant = Color(0xFF253430),
    onSurfaceVariant = Color(0xFFB5C5C0),
    outline = Color(0xFF6C807B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

@Composable
fun MedSearchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = MedSearchTypography,
        content = content
    )
}
