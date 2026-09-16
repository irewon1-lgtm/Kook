package com.krstock.v3.data.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage4567PolicyTest {
    @Test
    fun valuationBandsMatchValidatedStage5Policy() {
        assertEquals("LOW_RELATIVE_PER", Stage4567Policy.valuationBand(5.0, 80.0)?.code)
        assertEquals("BELOW_MEDIAN_PER", Stage4567Policy.valuationBand(8.0, 60.0)?.code)
        assertEquals("MID_PER", Stage4567Policy.valuationBand(12.0, 40.0)?.code)
        assertEquals("ABOVE_MEDIAN_PER", Stage4567Policy.valuationBand(18.0, 20.0)?.code)
        assertEquals("HIGH_RELATIVE_PER", Stage4567Policy.valuationBand(30.0, 19.999)?.code)
        assertNull(Stage4567Policy.valuationBand(-3.0, 100.0))
        assertNull(Stage4567Policy.valuationBand(null, 50.0))
        assertNull(Stage4567Policy.valuationBand(10.0, null))
    }

    @Test
    fun safetyLabelsAreExplicitAndFailClosed() {
        assertEquals("통과", Stage4567Policy.safetyLabelKo("PASS"))
        assertEquals("탈락", Stage4567Policy.safetyLabelKo("FAIL"))
        assertEquals("검증보류", Stage4567Policy.safetyLabelKo("HOLD"))
        assertEquals("비교제외", Stage4567Policy.safetyLabelKo("NOT_APPLICABLE"))
        assertEquals("미확인", Stage4567Policy.safetyLabelKo("UNKNOWN"))
    }
}
