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

// Every role is set: the ones left out fall back to Material's baseline purple, which tinted
// tonal buttons, chips, cards and sheets lilac against the green brand.
private val LightColors =
    lightColorScheme(
        primary = Green,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB8EBD9),
        onPrimaryContainer = GreenDark,
        inversePrimary = GreenLight,
        secondary = Color(0xFF3E6B5D),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCFE8DD),
        onSecondaryContainer = Color(0xFF0A2019),
        tertiary = Color(0xFF6B5E3E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF4E2BB),
        onTertiaryContainer = Color(0xFF241A04),
        background = Color(0xFFF7FAF8),
        onBackground = Color(0xFF171D1A),
        surface = Color(0xFFF7FAF8),
        onSurface = Color(0xFF171D1A),
        surfaceVariant = Color(0xFFDCE5E0),
        onSurfaceVariant = Color(0xFF404945),
        surfaceTint = Green,
        inverseSurface = Color(0xFF2C3330),
        inverseOnSurface = Color(0xFFECF2EE),
        outline = Color(0xFF707975),
        outlineVariant = Color(0xFFC0C9C4),
        surfaceBright = Color(0xFFF7FAF8),
        surfaceDim = Color(0xFFD6DCD9),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF1F6F3),
        surfaceContainer = Color(0xFFEBF1EE),
        surfaceContainerHigh = Color(0xFFE5ECE8),
        surfaceContainerHighest = Color(0xFFDFE7E3),
    )

private val DarkColors =
    darkColorScheme(
        primary = GreenLight,
        onPrimary = GreenDark,
        primaryContainer = Color(0xFF194D3E),
        onPrimaryContainer = Color(0xFFB8EBD9),
        inversePrimary = Green,
        secondary = Color(0xFFB4CCC1),
        onSecondary = Color(0xFF1F352D),
        secondaryContainer = Color(0xFF38504A),
        onSecondaryContainer = Color(0xFFCFE8DD),
        tertiary = Color(0xFFD8C6A0),
        onTertiary = Color(0xFF3B2F15),
        tertiaryContainer = Color(0xFF534529),
        onTertiaryContainer = Color(0xFFF4E2BB),
        background = Color(0xFF101613),
        onBackground = Color(0xFFDEE4E0),
        surface = Color(0xFF101613),
        onSurface = Color(0xFFDEE4E0),
        surfaceVariant = Color(0xFF2C3833),
        onSurfaceVariant = Color(0xFFBFC9C4),
        surfaceTint = GreenLight,
        inverseSurface = Color(0xFFDEE4E0),
        inverseOnSurface = Color(0xFF2C3330),
        outline = Color(0xFF89938F),
        outlineVariant = Color(0xFF3A4440),
        surfaceBright = Color(0xFF353C39),
        surfaceDim = Color(0xFF101613),
        surfaceContainerLowest = Color(0xFF0B100E),
        surfaceContainerLow = Color(0xFF161D1A),
        surfaceContainer = Color(0xFF1A221E),
        surfaceContainerHigh = Color(0xFF222A26),
        surfaceContainerHighest = Color(0xFF2A3330),
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
