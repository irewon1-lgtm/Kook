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

// Legacy exports kept for source compatibility. New UI must use MaterialTheme.colorScheme
// for surfaces/text so light and dark themes can never mix fixed light backgrounds with
// dark-theme foreground colors.
val NavyPrimary = Color(0xFF101828)
val NavySecondary = Color(0xFF1D2939)
val BlueAccent = Color(0xFF175CD3)
val BlueAccentSoft = Color(0xFFEFF4FF)
val EmeraldGreen = Color(0xFF067647)
val AmberWarning = Color(0xFFB54708)
val RoseError = Color(0xFFB42318)
val BackgroundLight = Color(0xFFF4F6F8)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceMuted = Color(0xFFF1F4F8)
val BorderLight = Color(0xFFD8DEE8)
val TextPrimaryLight = Color(0xFF111827)
val TextSecondaryLight = Color(0xFF556274)
val TextTertiaryLight = Color(0xFF7B8798)

private val FinanceLightColorScheme = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FB),
    onPrimaryContainer = Color(0xFF0B315A),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF4F0),
    onSecondaryContainer = Color(0xFF174B47),
    tertiary = Color(0xFF9A6700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF0C2),
    onTertiaryContainer = Color(0xFF533F00),
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFF98A2B3),
    outlineVariant = BorderLight,
    error = RoseError,
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A)
)

private val FinanceDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FC5F8),
    onPrimary = Color(0xFF002E57),
    primaryContainer = Color(0xFF123A62),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF7BD7CC),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF174A46),
    onSecondaryContainer = Color(0xFFB9F1EA),
    tertiary = Color(0xFFE7C76F),
    onTertiary = Color(0xFF3C2F00),
    tertiaryContainer = Color(0xFF554500),
    onTertiaryContainer = Color(0xFFFFE7A0),
    background = Color(0xFF0B1017),
    onBackground = Color(0xFFF3F6FA),
    surface = Color(0xFF111824),
    onSurface = Color(0xFFF3F6FA),
    surfaceVariant = Color(0xFF1B2432),
    onSurfaceVariant = Color(0xFFB8C3D1),
    outline = Color(0xFF778396),
    outlineVariant = Color(0xFF2D3949),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.15).sp
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
    MaterialTheme(
        colorScheme = if (darkTheme) FinanceDarkColorScheme else FinanceLightColorScheme,
        typography = AppTypography,
        content = content
    )
}
