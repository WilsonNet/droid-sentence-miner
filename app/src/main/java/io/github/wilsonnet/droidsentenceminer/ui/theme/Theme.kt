package io.github.wilsonnet.droidsentenceminer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B5E4A)
private val GreenLight = Color(0xFF4CD9B4)
private val GreenDark = Color(0xFF0B201A)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBD9),
    onPrimaryContainer = GreenDark,
    secondary = Color(0xFF3E6B5D),
    tertiary = Color(0xFF6B5E3E),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EFEA),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = GreenDark,
    primaryContainer = Color(0xFF194D3E),
    onPrimaryContainer = Color(0xFFB8EBD9),
    background = Color(0xFF101613),
    surface = Color(0xFF161D1A),
    surfaceVariant = Color(0xFF223029),
)

@Composable
fun DsmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
