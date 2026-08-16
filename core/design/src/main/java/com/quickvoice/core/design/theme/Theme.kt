package com.quickvoice.core.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = Teal20,
    secondary = BlueGrey40,
    onSecondary = Color.White,
    secondaryContainer = BlueGreyContainerLight,
    onSecondaryContainer = BlueGrey20,
    tertiary = Green40,
    onTertiary = Color.White,
    tertiaryContainer = GreenContainerLight,
    onTertiaryContainer = Green20,
    error = Red40,
    errorContainer = RedContainerLight,
    onErrorContainer = Red20,
    background = SurfaceLight,
    onBackground = Color(0xFF191C1C),
    surface = SurfaceLight,
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7979),
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal20,
    onPrimaryContainer = Teal80,
    secondary = BlueGrey80,
    onSecondary = BlueGrey20,
    secondaryContainer = BlueGreyContainerDark,
    onSecondaryContainer = BlueGrey80,
    tertiary = Green80,
    onTertiary = Green20,
    tertiaryContainer = GreenContainerDark,
    onTertiaryContainer = Green80,
    error = Red80,
    errorContainer = RedContainerDark,
    onErrorContainer = Red80,
    background = SurfaceDark,
    onBackground = Color(0xFFE0E3E2),
    surface = SurfaceDark,
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C8),
    outline = Color(0xFF899392),
)

@Composable
fun QuickVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = QuickVoiceTypography,
        content = content,
    )
}
