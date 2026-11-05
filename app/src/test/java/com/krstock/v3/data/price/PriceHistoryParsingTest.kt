package com.krstock.v3.data.price

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PriceHistoryParsingTest {
    private val cutoff = LocalDate.parse("2026-11-04")

    @Test fun missingOhlcIsNeverInventedFromClose() {
        for (missing in listOf("openPrice", "highPrice", "lowPrice")) {
            val row = org.json.JSONObject(valid()).removeField(missing)
            assertTrue("Missing $missing must not create a candle", PriceHistoryRepository.parseBars("[$row]", cutoff).isEmpty())
        }
        assertTrue(PriceHistoryRepository.parseBars("""[{"localTradedAt":"2026-11-04","closePrice":"100"}]""", cutoff).isEmpty())
    }

    @Test fun missingOrFractionalVolumeStaysUnavailableAndLargeIntegersStayExact() {
        for (value in listOf("null", "-1", "1.5", "9223372036854775808")) {
            val row = org.json.JSONObject(valid()).put("accumulatedTradingVolume", value)
            assertNull(PriceHistoryRepository.parseBars("[$row]", cutoff).single().volume)
        }
        val row = org.json.JSONObject(valid()).put("accumulatedTradingVolume", "9,007,199,254,740,993")
        assertEquals(9007199254740993L, PriceHistoryRepository.parseBars("[$row]", cutoff).single().volume)
    }

    @Test fun conflictingDuplicateDatesCannotSilentlySelectOnePrice() {
        val row = valid()
        val conflicting = org.json.JSONObject(row).put("closePrice", "102")
        try {
            PriceHistoryRepository.parseBars("[$row,$conflicting]", cutoff)
            fail("Conflicting source prices must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, PriceHistoryRepository.parseBars("[$row,$row]", cutoff).size)
    }

    @Test fun wholeNumericVolumeMayUseDecimalOrScientificNotationWithoutTruncation() {
        for (value in listOf("1000.0", "1e3")) {
            val row = org.json.JSONObject(valid()).put("accumulatedTradingVolume", value)
            assertEquals(1000L, PriceHistoryRepository.parseBars("[$row]", cutoff).single().volume)
        }
    }

    @Test fun invalidAndFuturePricesRemainExcluded() {
        val row = valid()
        val future = org.json.JSONObject(row).put("localTradedAt", "2026-11-05")
        val invalid = org.json.JSONObject(row).put("highPrice", "99")
        assertEquals(1, PriceHistoryRepository.parseBars("[$future,$invalid,$row]", cutoff).size)
    }

    private fun valid() = """{"localTradedAt":"2026-11-04","openPrice":"100","highPrice":"105","lowPrice":"95","closePrice":"101","accumulatedTradingVolume":"1000"}"""
    private fun org.json.JSONObject.removeField(key: String): org.json.JSONObject = apply { remove(key) }
}
