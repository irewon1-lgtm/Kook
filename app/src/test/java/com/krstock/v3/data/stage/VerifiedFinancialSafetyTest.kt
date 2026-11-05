package com.krstock.v3.data.stage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Deliberately controlled parser inputs; never published as market observations. */
class VerifiedFinancialSafetyTest {
    @get:Rule val directory = TemporaryFolder()
    private val day = "2026-11-05"
    private val codes = setOf("000001")

    @Test fun verifiedRawAmountsAndOfficialFilingCanBeReceivedIndependently() {
        val root = valid()
        assertTrue(FinancialSafetySnapshotUpdater.refreshBlocking(directory.root, day, codes, null) { root.toString() })
        assertEquals(1, FinancialSafetyRuntimeStore.current(day)?.passCount)
        FinancialSafetyRuntimeStore.clear()
    }
    @Test fun parsesVerifiedSchemaWithoutDependingOnQuantEmbeddedSection() {
        val parsed = FinancialSafetySnapshotParser.parse(valid(), day, codes)
        assertEquals(100.0, parsed.records["000001"]!!.debtToEquityPct!!, 0.0)
        assertEquals(160.0, parsed.records["000001"]!!.currentRatioPct!!, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun forgedRatioCannotOverrideActualRawValues() {
        val root=valid();record(root).put("debt_to_equity_pct",99.0);parse(root)
    }
    @Test(expected = IllegalArgumentException::class) fun futureFilingCannotClaimValidatedFacts() {
        val root=valid();record(root).put("filing_date","2026-11-06");parse(root)
    }
    @Test(expected = IllegalArgumentException::class) fun unresolvedCorrectionCannotClaimValidatedFacts() {
        val root=valid();record(root).put("correction_status","LATEST_CORRECTION");parse(root)
    }
    @Test(expected = IllegalArgumentException::class) fun fractionalAmountIsRejectedInsteadOfTruncated() {
        val root=valid();record(root).put("assets",100.5);parse(root)
    }
    @Test(expected = IllegalArgumentException::class) fun validZeroCannotBeSilentlyChangedToMissing() {
        val root=valid();record(root).put("current_assets",JSONObject.NULL);parse(root)
    }
    @Test(expected = IllegalArgumentException::class) fun missingProvenanceCannotRemainPass() {
        val root=valid();record(root).put("corp_code","");parse(root)
    }
    @Test(expected = IllegalArgumentException::class) fun receiptUrlMustIdentifyActualReceipt() {
        val root=valid();record(root).put("source_url","https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814009999");parse(root)
    }
    @Test fun unfamiliarVerificationFailureRemainsExplicitHold() {
        val root=valid();val row=record(root)
        row.put("status","HOLD").put("reason","DART_IDENTITY_UNCONFIRMED").put("facts_verified",false)
          .put("validation_issues",org.json.JSONArray(listOf("DART_IDENTITY_UNCONFIRMED")))
          .put("debt_to_equity_pct",JSONObject.NULL).put("current_ratio_pct",JSONObject.NULL).put("identity_gap_pct",JSONObject.NULL)
        root.getJSONObject("coverage").put("pass",0).put("hold",1)
        assertEquals("DART_IDENTITY_UNCONFIRMED",parse(root).records["000001"]!!.reasonCode)
    }
    @Test fun sixDecimalSerializationCannotHideARealDebtLimitViolation() {
        val root=valid();val row=record(root)
        row.put("assets",500000000001L).put("liabilities",400000000001L).put("equity",100000000000L)
            .put("status","FAIL").put("reason","DEBT_TO_EQUITY_OVER_400").put("debt_to_equity_pct",400.0)
        root.getJSONObject("coverage").put("pass",0).put("fail",1)
        assertEquals(com.krstock.v3.data.model.FinancialSafetyStatus.FAIL,parse(root).records["000001"]!!.status)
    }
    @Test fun sixDecimalSerializationCannotHideARealCurrentRatioViolation() {
        val root=valid();val row=record(root)
        row.put("assets",1000000000000L).put("liabilities",500000000000L).put("equity",500000000000L)
            .put("current_assets",349999999999L).put("current_liabilities",500000000000L)
            .put("status","FAIL").put("reason","CURRENT_RATIO_UNDER_70").put("current_ratio_pct",70.0)
        root.getJSONObject("coverage").put("pass",0).put("fail",1)
        assertEquals(com.krstock.v3.data.model.FinancialSafetyStatus.FAIL,parse(root).records["000001"]!!.status)
    }
    private fun parse(root:JSONObject)=FinancialSafetySnapshotParser.parse(root,day,codes)
    private fun record(root:JSONObject)=root.getJSONObject("records").getJSONObject("000001")
    private fun valid()=JSONObject("""{
      "schema_version":2,"policy_version":"STAGE4_BS_SAFETY_V1","snapshot_date_kst":"2026-11-05",
      "source":"FSS OpenDART","source_file":"2026_2Q_BS_20261105030346.zip",
      "thresholds":{"debt_to_equity_max_pct":400.0,"current_ratio_min_pct":70.0,"accounting_identity_tolerance_pct":5.0},
      "coverage":{"pass":1,"fail":0,"hold":0,"not_applicable":0,"total":1},
      "records":{"000001":{"issuer_code":"000001","issuer_name":"Controlled test issuer","status":"PASS","reason":"PASS","scope":"CFS",
       "assets":100,"liabilities":50,"equity":50,"current_assets":80,"current_liabilities":50,
       "debt_to_equity_pct":100.0,"current_ratio_pct":160.0,"identity_gap_pct":0.0,"basis":"controlled BS fixture",
       "currency":"KRW","unit":"KRW","report_period":"2026-06-30","filing_date":"2026-08-14",
       "corp_code":"00123456","receipt_no":"20260814000001","source_url":"https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814000001",
       "source_file":"2026_2Q_BS_20261105030346.zip","source_zip_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
       "correction_status":"ORIGINAL","correction_check":"LATEST_FINAL_REPORT_CONFIRMED","facts_verified":true,"validation_issues":[]}}
    }""")
}
