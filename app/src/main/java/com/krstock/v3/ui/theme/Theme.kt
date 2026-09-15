package com.krstock.v3.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val NavyPrimary = Color(0xFF101828)
val NavySecondary = Color(0xFF1D2939)
val BlueAccent = Color(0xFF175CD3)
val BlueAccentSoft = Color(0xFFEFF4FF)
val EmeraldGreen = Color(0xFF067647)
val AmberWarning = Color(0xFFB54708)
val RoseError = Color(0xFFB42318)

val BackgroundLight = Color(0xFFF5F7FA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceMuted = Color(0xFFF8FAFC)
val BorderLight = Color(0xFFE4E7EC)
val TextPrimaryLight = Color(0xFF101828)
val TextSecondaryLight = Color(0xFF667085)
val TextTertiaryLight = Color(0xFF98A2B3)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF84ADFF),
    secondary = Color(0xFF75E0A7),
    background = Color(0xFF0C111D),
    surface = NavyPrimary,
    surfaceVariant = NavySecondary,
    outlineVariant = Color(0xFF344054),
    onPrimary = Color(0xFF0C111D),
    onBackground = Color(0xFFF2F4F7),
    onSurface = Color(0xFFF2F4F7)
)

private val LightColorScheme = lightColorScheme(
    primary = BlueAccent,
    secondary = EmeraldGreen,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceMuted,
    outline = Color(0xFFD0D5DD),
    outlineVariant = BorderLight,
    onPrimary = Color.White,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun KRStockV3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
