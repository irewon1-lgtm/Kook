package com.krstock.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.stage.FinancialSafetySnapshotParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialSafetyParserInstrumentedTest {
    private val codes = setOf("000001", "000002", "000003", "000004")

    @Test
    fun parsesPassFailHoldAndNotApplicableWithoutInference() {
        val parsed = FinancialSafetySnapshotParser.parse(
            safety = validDocument(),
            expectedSnapshotDate = "2026-09-16",
            expectedCodes = codes,
        )

        assertEquals(1, parsed.passCount)
        assertEquals(1, parsed.failCount)
        assertEquals(1, parsed.holdCount)
        assertEquals(1, parsed.notApplicableCount)
        assertEquals(FinancialSafetyStatus.PASS, parsed.records["000001"]?.status)
        assertEquals(FinancialSafetyStatus.FAIL, parsed.records["000002"]?.status)
        assertEquals(FinancialSafetyStatus.HOLD, parsed.records["000003"]?.status)
        assertEquals(FinancialSafetyStatus.NOT_APPLICABLE, parsed.records["000004"]?.status)
        assertEquals(null, parsed.records["000003"]?.debtToEquityPct)
        assertEquals(null, parsed.records["000003"]?.currentRatioPct)
        assertEquals(null, parsed.records["000003"]?.accountingIdentityGapPct)
        assertEquals(null, parsed.records["000004"]?.debtToEquityPct)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSnapshotDateMismatch() {
        FinancialSafetySnapshotParser.parse(validDocument(), "2026-09-15", codes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPassRowThatViolatesDebtPolicy() {
        val root = validDocument()
        root.getJSONObject("records").getJSONObject("000001").put("debt_to_equity_pct", 401.0)
        FinancialSafetySnapshotParser.parse(root, "2026-09-16", codes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFailReasonThatDoesNotMatchRatio() {
        val root = validDocument()
        root.getJSONObject("records").getJSONObject("000002").put("debt_to_equity_pct", 399.0)
        FinancialSafetySnapshotParser.parse(root, "2026-09-16", codes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoverageMismatch() {
        val root = validDocument()
        root.getJSONObject("coverage").put("pass", 2)
        FinancialSafetySnapshotParser.parse(root, "2026-09-16", codes)
    }

    private fun validDocument(): JSONObject = JSONObject(
        """
        {
          "schema_version": 1,
          "policy_version": "STAGE4_BS_SAFETY_V1",
          "snapshot_date_kst": "2026-09-16",
          "source": "OpenDART",
          "source_file": "fixture.zip",
          "thresholds": {
            "debt_to_equity_max_pct": 400.0,
            "current_ratio_min_pct": 70.0,
            "accounting_identity_tolerance_pct": 5.0
          },
          "coverage": {
            "pass": 1,
            "fail": 1,
            "hold": 1,
            "not_applicable": 1,
            "total": 4
          },
          "records": {
            "000001": {
              "status": "PASS",
              "reason": "PASS",
              "scope": "CFS",
              "debt_to_equity_pct": 100.0,
              "current_ratio_pct": 150.0,
              "identity_gap_pct": 0.10,
              "basis": "CFS test"
            },
            "000002": {
              "status": "FAIL",
              "reason": "DEBT_TO_EQUITY_OVER_400",
              "scope": "CFS",
              "debt_to_equity_pct": 450.0,
              "current_ratio_pct": 120.0,
              "identity_gap_pct": 0.20,
              "basis": "CFS test"
            },
            "000003": {
              "status": "HOLD",
              "reason": "MISSING_CURRENT_ASSETS",
              "scope": "CFS",
              "debt_to_equity_pct": null,
              "current_ratio_pct": null,
              "identity_gap_pct": null,
              "basis": "CFS test"
            },
            "000004": {
              "status": "NOT_APPLICABLE",
              "reason": "FINANCIAL_SECTOR_NOT_COMPARABLE",
              "scope": "",
              "debt_to_equity_pct": null,
              "current_ratio_pct": null,
              "identity_gap_pct": null,
              "basis": ""
            }
          }
        }
        """.trimIndent()
    )
}
