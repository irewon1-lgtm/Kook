package com.krstock.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.update.SnapshotAutoUpdater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs Android's actual JSONObject parser, including the formerly rejected public artifact. */
@RunWith(AndroidJUnit4::class)
class SnapshotParserInstrumentedTest {
    @Test
    fun novemberFifthPublicSnapshotWithoutStage4PassesAllQuantGates() {
        val body = SnapshotTestSupport.fixture()
        assertFalse(JSONObject(body).has("financial_safety"))
        val parsed = SnapshotAutoUpdater.parseAndValidate(body)

        assertEquals("2026-11-05", parsed.snapshotDate)
        assertEquals("2026-11-04", parsed.priceCutoffDate)
        assertEquals(2649, parsed.rows.size)
        assertEquals(2561, parsed.m01Available)
        assertEquals(2410, parsed.m02Available)
        assertEquals(2617, parsed.m03Available)
        assertEquals(2619, parsed.m04Available)
        assertEquals(2367, parsed.completeCount)
        assertEquals("032580", parsed.rows.single { it.rankOrder == 1 }.code)
        assertEquals((1..2367).toList(), parsed.rows.mapNotNull { it.rankOrder }.sorted())
    }

    @Test
    fun missingOrStaleOrMalformedStage4DoesNotInvalidateIndependentQuant() {
        val body = SnapshotTestSupport.fixture()
        val missing = SnapshotAutoUpdater.parseAndValidate(body)
        val stale = JSONObject(body).put(
            "financial_safety",
            JSONObject().put("snapshot_date_kst", "2026-09-15"),
        )
        val malformed = JSONObject(body).put("financial_safety", "invalid separate artifact")

        for (variant in listOf(stale, malformed)) {
            val parsed = SnapshotAutoUpdater.parseAndValidate(variant.toString())
            assertEquals(missing.rows, parsed.rows)
            assertEquals(missing.completeCount, parsed.completeCount)
            assertEquals(missing.snapshotDate, parsed.snapshotDate)
        }
    }

    @Test
    fun quantIntegrityFailuresStillRejectRealPayloadMutations() {
        val original = SnapshotTestSupport.fixture()
        val corruptions: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "untrusted collector" to { it.getJSONObject("auto_update").put("collector", "unknown.py"); Unit },
            "wrong timezone" to { it.getJSONObject("auto_update").put("timezone", "UTC"); Unit },
            "coverage mismatch" to { it.getJSONObject("coverage").put("complete_count", 1); Unit },
            "missing identity" to { it.getJSONObject("records").remove("005930"); Unit },
            "Samsung anchor mismatch" to {
                it.getJSONObject("records").getJSONObject("005930").put("name", "invalid identity"); Unit
            },
            "noncontiguous ranks" to {
                it.getJSONObject("records").getJSONObject("032580").put("rank", 999999); Unit
            },
            "future snapshot" to { it.put("snapshot_date_kst", "2999-01-01"); Unit },
            "price after snapshot" to { it.put("price_cutoff_date_kst", "2026-11-06"); Unit },
            "stale price" to { it.put("price_cutoff_date_kst", "2026-10-01"); Unit },
            "percentile out of range" to {
                it.getJSONObject("records").getJSONObject("005930").getJSONObject("m01")
                    .put("percentile", 101.0); Unit
            },
        )
        corruptions.forEach { (description, mutate) ->
            val json = JSONObject(original)
            mutate(json)
            val error = runCatching { SnapshotAutoUpdater.parseAndValidate(json.toString()) }.exceptionOrNull()
            if (error == null) fail("Accepted $description")
            assertTrue("Unexpected parser failure for $description: $error", error is IllegalArgumentException)
        }
    }
}
