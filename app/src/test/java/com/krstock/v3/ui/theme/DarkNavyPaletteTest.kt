package com.krstock.v3.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class DarkNavyPaletteTest {
    @Test fun approvedPaletteUsesNavyBlueAndLightTextInsteadOfPurple() {
        assertEquals(0xFF061321.toInt(), BackgroundLight.toArgb())
        assertEquals(0xFF178CFF.toInt(), BlueAccent.toArgb())
        assertEquals(0xFF0D1D2C.toInt(), SurfaceLight.toArgb())
        assertEquals(0xFFF3F6FC.toInt(), TextPrimaryLight.toArgb())
    }

    @Test fun lightAndDarkSystemSettingsKeepTheApprovedNavyDesign() {
        for (systemDark in listOf(false, true)) {
            val palette = approvedNavyColorScheme(systemDark)
            assertEquals(0xFF061321.toInt(), palette.background.toArgb())
            assertEquals(0xFF178CFF.toInt(), palette.primary.toArgb())
            assertEquals(0xFFF3F6FC.toInt(), palette.onBackground.toArgb())
        }
    }
}
