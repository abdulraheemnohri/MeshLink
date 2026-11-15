package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val MeshDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = MeshObsidian,
    primaryContainer = Color(0xFF003642),
    onPrimaryContainer = NeonCyan,
    secondary = ElectricTeal,
    onSecondary = MeshObsidian,
    secondaryContainer = Color(0xFF0B3B36),
    onSecondaryContainer = Color(0xFF6EE7B7),
    tertiary = MeshAmber,
    onTertiary = MeshObsidian,
    background = MeshObsidian,
    onBackground = TextPrimaryDark,
    surface = MeshDeepNavy,
    onSurface = TextPrimaryDark,
    surfaceVariant = MeshSlateSurface,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = MeshSlateElevated,
    outline = MeshBorderSubtle,
    error = MeshCrimson,
    onError = Color.White
)

private val MeshLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F4FC),
    onPrimaryContainer = Color(0xFF003544),
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF0F3D38),
    tertiary = MeshAmber,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFCBD5E1),
    error = MeshCrimson,
    onError = Color.White
)

val MeshShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MeshLinkTheme(
    themeMode: String = "DARK",
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }

    val colorScheme = if (useDark) MeshDarkColorScheme else MeshLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MeshTypography,
        shapes = MeshShapes,
        content = content
    )
}
