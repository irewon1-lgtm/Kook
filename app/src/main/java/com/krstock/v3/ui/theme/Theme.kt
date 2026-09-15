package com.krstock.v3.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Premium-neutral finance palette. Kept as named exports for source compatibility.
val NavyPrimary = Color(0xFF2B2830)
val NavySecondary = Color(0xFF403943)
val BlueAccent = Color(0xFF6D4C7D)
val BlueAccentSoft = Color(0xFFF0E8F2)
val EmeraldGreen = Color(0xFF7A6258)
val AmberWarning = Color(0xFF8A672D)
val RoseError = Color(0xFFB42318)
val BackgroundLight = Color(0xFFF6F3F1)
val SurfaceLight = Color(0xFFFFFCFA)
val SurfaceMuted = Color(0xFFF0ECE9)
val BorderLight = Color(0xFFD9D2D8)
val TextPrimaryLight = Color(0xFF1B191D)
val TextSecondaryLight = Color(0xFF655F68)
val TextTertiaryLight = Color(0xFF8A838D)

private val FinanceLightColorScheme = lightColorScheme(
    primary = Color(0xFF6D4C7D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E7F3),
    onPrimaryContainer = Color(0xFF34243B),
    secondary = Color(0xFF82685D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E7E1),
    onSecondaryContainer = Color(0xFF3C2B24),
    tertiary = Color(0xFF8A672D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E8CC),
    onTertiaryContainer = Color(0xFF463511),
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFF958C96),
    outlineVariant = BorderLight,
    error = RoseError,
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A)
)

private val FinanceDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD8BCE3),
    onPrimary = Color(0xFF3A2840),
    primaryContainer = Color(0xFF503A57),
    onPrimaryContainer = Color(0xFFF3E5F6),
    secondary = Color(0xFFD9BCAF),
    onSecondary = Color(0xFF3A2922),
    secondaryContainer = Color(0xFF554038),
    onSecondaryContainer = Color(0xFFF2DED5),
    tertiary = Color(0xFFE1C488),
    onTertiary = Color(0xFF3D2F0C),
    tertiaryContainer = Color(0xFF54451F),
    onTertiaryContainer = Color(0xFFF5E3B5),
    background = Color(0xFF171519),
    onBackground = Color(0xFFF4F0F3),
    surface = Color(0xFF201D21),
    onSurface = Color(0xFFF4F0F3),
    surfaceVariant = Color(0xFF2B272D),
    onSurfaceVariant = Color(0xFFCEC5CF),
    outline = Color(0xFF958B97),
    outlineVariant = Color(0xFF403A42),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6)
)

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
        colorScheme = if (darkTheme) FinanceDarkColorScheme else FinanceLightColorScheme,
        typography = AppTypography,
        content = content
    )
}
