package com.krstock.v3.data.stage

import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetySnapshot
import com.krstock.v3.data.model.FinancialSafetyStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage45PolicyTest {
    @After
    fun tearDown() = FinancialSafetyRuntimeStore.clear()

    @Test
    fun valuationBandBoundariesExactlyMatchStage5Policy() {
        assertEquals("LOW_RELATIVE_PER", ValuationBandPolicy.classify(10.0, 80.0)?.code)
        assertEquals("BELOW_MEDIAN_PER", ValuationBandPolicy.classify(10.0, 60.0)?.code)
        assertEquals("MID_PER", ValuationBandPolicy.classify(10.0, 40.0)?.code)
        assertEquals("ABOVE_MEDIAN_PER", ValuationBandPolicy.classify(10.0, 20.0)?.code)
        assertEquals("HIGH_RELATIVE_PER", ValuationBandPolicy.classify(10.0, 19.999)?.code)
    }

    @Test
    fun valuationBandIsDescriptiveAndRejectsNonpositiveOrMissingPer() {
        val band = ValuationBandPolicy.classify(7.5, 91.0)
        requireNotNull(band)
        assertFalse(band.changesRank)
        assertEquals("낮은 PER", band.shortLabelKo)
        assertNull(ValuationBandPolicy.classify(0.0, 90.0))
        assertNull(ValuationBandPolicy.classify(-3.0, 90.0))
        assertNull(ValuationBandPolicy.classify(null, 90.0))
        assertNull(ValuationBandPolicy.classify(10.0, null))
    }

    @Test
    fun financialSafetyStoreRequiresExactSnapshotDate() {
        FinancialSafetyRuntimeStore.install(snapshot("2026-09-16"))
        assertEquals(FinancialSafetyStatus.PASS, FinancialSafetyRuntimeStore.get("2026-09-16", "000001")?.status)
        assertNull(FinancialSafetyRuntimeStore.get("2026-09-15", "000001"))
        assertNull(FinancialSafetyRuntimeStore.current("2026-09-15"))
    }

    @Test
    fun financialSafetyStorePreservesAllExplicitStatusesWithoutGuessing() {
        val rows = linkedMapOf(
            "000001" to row("000001", FinancialSafetyStatus.PASS, "PASS"),
            "000002" to row("000002", FinancialSafetyStatus.FAIL, "DEBT_TO_EQUITY_OVER_400", debt = 450.0),
            "000003" to row("000003", FinancialSafetyStatus.HOLD, "MISSING_CURRENT_ASSETS", current = null),
            "000004" to row(
                "000004",
                FinancialSafetyStatus.NOT_APPLICABLE,
                "FINANCIAL_SECTOR_NOT_COMPARABLE",
                debt = null,
                current = null,
                identity = null,
                basis = "",
            ),
        )
        FinancialSafetyRuntimeStore.install(
            FinancialSafetySnapshot(
                policyVersion = FinancialSafetySnapshotParser.POLICY_VERSION,
                snapshotDate = "2026-09-16",
                source = "OpenDART",
                sourceFile = "fixture.zip",
                passCount = 1,
                failCount = 1,
                holdCount = 1,
                notApplicableCount = 1,
                records = rows,
            )
        )
        val stored = requireNotNull(FinancialSafetyRuntimeStore.current("2026-09-16"))
        assertEquals(4, stored.records.size)
        assertTrue(stored.records["000003"]?.currentRatioPct == null)
        assertEquals(FinancialSafetyStatus.NOT_APPLICABLE, stored.records["000004"]?.status)
    }

    private fun snapshot(date: String): FinancialSafetySnapshot {
        val row = row("000001", FinancialSafetyStatus.PASS, "PASS")
        return FinancialSafetySnapshot(
            policyVersion = FinancialSafetySnapshotParser.POLICY_VERSION,
            snapshotDate = date,
            source = "OpenDART",
            sourceFile = "fixture.zip",
            passCount = 1,
            failCount = 0,
            holdCount = 0,
            notApplicableCount = 0,
            records = mapOf(row.issuerId to row),
        )
    }

    private fun row(
        code: String,
        status: FinancialSafetyStatus,
        reason: String,
        debt: Double? = 100.0,
        current: Double? = 150.0,
        identity: Double? = 0.1,
        basis: String = "CFS TEST",
    ) = FinancialSafetyRecord(
        issuerId = code,
        status = status,
        reasonCode = reason,
        scope = if (status == FinancialSafetyStatus.NOT_APPLICABLE) "" else "CFS",
        debtToEquityPct = debt,
        currentRatioPct = current,
        accountingIdentityGapPct = identity,
        basis = basis,
    )
}
