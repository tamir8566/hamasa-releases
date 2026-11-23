package com.tamir.hamasa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette taken from the design book: dark slate, parchment, gold accent, ink. */
object HColors {
    val Slate = Color(0xFF1F2328)
    val SlateLight = Color(0xFF2B3138)
    val Parchment = Color(0xFFE9D9B8)
    val ParchmentDim = Color(0xFFB9A98B)
    val Gold = Color(0xFFE8B04B)
    val GoldDeep = Color(0xFFB8842A)
    val Ink = Color(0xFF14171A)
    val Ember = Color(0xFFE06A3B)
    val Moss = Color(0xFF6F7F3A)
    val Sky = Color(0xFF5A8FD6)
    val Danger = Color(0xFFC94C4C)
    val Rare = Color(0xFF4F8FE0)
    val Legendary = Color(0xFFF2C14E)
}

private val Dark = darkColorScheme(
    primary = HColors.Gold,
    onPrimary = HColors.Ink,
    secondary = HColors.Parchment,
    onSecondary = HColors.Ink,
    background = HColors.Slate,
    onBackground = HColors.Parchment,
    surface = HColors.SlateLight,
    onSurface = HColors.Parchment,
    surfaceVariant = Color(0xFF353C45),
    onSurfaceVariant = HColors.ParchmentDim,
    error = HColors.Danger,
    outline = Color(0xFF4A525C)
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 11.sp)
)

@Composable
fun HamasaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Type, content = content)
}
