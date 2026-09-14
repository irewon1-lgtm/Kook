package com.krstock.v3.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NavyPrimary = Color(0xFF0F172A)
val NavySecondary = Color(0xFF1E293B)
val BlueAccent = Color(0xFF2563EB)
val EmeraldGreen = Color(0xFF059669)
val AmberWarning = Color(0xFFD97706)
val RoseError = Color(0xFFE11D48)

val BackgroundLight = Color(0xFFF8FAFC)
val SurfaceLight = Color(0xFFFFFFFF)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF64748B)

private val DarkColorScheme = darkColorScheme(
    primary = BlueAccent,
    secondary = EmeraldGreen,
    background = NavyPrimary,
    surface = NavySecondary,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = BlueAccent,
    secondary = EmeraldGreen,
    background = BackgroundLight,
    surface = SurfaceLight,
    onPrimary = Color.White,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight
)

@Composable
fun KRStockV3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
