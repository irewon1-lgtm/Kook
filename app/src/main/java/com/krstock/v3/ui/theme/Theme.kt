package com.krstock.v3.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Approved B design: one navy palette under both system theme settings.
// Legacy export names remain for compatibility with existing presentation code.
val NavyPrimary = Color(0xFF061321)
val NavySecondary = Color(0xFF0D1D2C)
val BlueAccent = Color(0xFF178CFF)
val BlueAccentSoft = Color(0xFF102D52)
val EmeraldGreen = Color(0xFFB7C5D8)
val AmberWarning = Color(0xFFFF8500)
val RoseError = Color(0xFFE62F35)
val BackgroundLight = NavyPrimary
val SurfaceLight = NavySecondary
val SurfaceMuted = Color(0xFF142638)
val BorderLight = Color(0xFF35485C)
val TextPrimaryLight = Color(0xFFF3F6FC)
val TextSecondaryLight = Color(0xFFADB8C8)
val TextTertiaryLight = Color(0xFF8191A5)

private val ApprovedNavyColorScheme = darkColorScheme(
    primary = BlueAccent,
    onPrimary = Color.White,
    primaryContainer = BlueAccentSoft,
    onPrimaryContainer = Color(0xFFE3EFFF),
    secondary = Color(0xFFB5C4D7),
    onSecondary = NavyPrimary,
    secondaryContainer = Color(0xFF253546),
    onSecondaryContainer = Color(0xFFE0E6EF),
    tertiary = AmberWarning,
    onTertiary = Color(0xFF17120B),
    tertiaryContainer = Color(0xFF46301B),
    onTertiaryContainer = Color(0xFFFFDFC0),
    background = NavyPrimary,
    onBackground = TextPrimaryLight,
    surface = NavySecondary,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFF526275),
    outlineVariant = BorderLight,
    error = RoseError,
    onError = Color.White,
    errorContainer = Color(0xFF641D25),
    onErrorContainer = Color(0xFFFFDDE0),
)

@Suppress("UNUSED_PARAMETER")
fun approvedNavyColorScheme(systemDark: Boolean) = ApprovedNavyColorScheme

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
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
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
)

@Composable
fun KRStockV3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = approvedNavyColorScheme(darkTheme),
        typography = AppTypography,
        content = content
    )
}
