package com.krstock.v3.data.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real committed market snapshot; no fabricated replacement financial values. */
class SnapshotReceiveTest {
    private fun document(): JSONObject = JSONObject(File("../evidence/real_quant_snapshot.json").readText())

    @Test
    fun normalFourMetricSnapshotDoesNotRequireStage4() {
        val root = document().apply { remove("financial_safety") }
        val parsed = SnapshotAutoUpdater.parseAndValidate(root.toString())
        assertEquals(root.getString("snapshot_date_kst"), parsed.snapshotDate)
        assertEquals(root.getString("price_cutoff_date_kst"), parsed.priceCutoffDate)
        assertEquals(2649, parsed.rows.size)
        assertEquals(root.getJSONObject("coverage").getInt("complete_count"), parsed.completeCount)
    }

    @Test
    fun malformedOrStaleStage4DoesNotBlockIndependentFourMetrics() {
        val root = document().put("financial_safety", JSONObject().put("snapshot_date_kst", "2000-01-01"))
        assertEquals(2649, SnapshotAutoUpdater.parseAndValidate(root.toString()).rows.size)
    }

    @Test
    fun coverageAccountingAndRankGatesRemainMandatory() {
        val badCoverage = document()
        badCoverage.getJSONObject("coverage").put("complete_count", 0)
        assertRejected(badCoverage)
        val badRank = document()
        val records = badRank.getJSONObject("records")
        val code = records.keys().asSequence().first { !records.getJSONObject(it).isNull("rank") }
        records.getJSONObject(code).put("rank", 99999)
        assertRejected(badRank)
    }

    private fun assertRejected(root: JSONObject) {
        try {
            SnapshotAutoUpdater.parseAndValidate(root.toString())
            fail("Invalid four-metric data must not be accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
