package com.krstock.v3.data.stage

import com.krstock.v3.data.candidate.FinalCandidateRuntimeStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.File

class FinancialSafetySnapshotUpdaterTest {
    @get:Rule val directory = TemporaryFolder()
    private val date = "2026-11-05"
    private val codes = setOf("000001")

    @After
    fun tearDown() {
        FinancialSafetyRuntimeStore.clear()
        FinalCandidateRuntimeStore.clear()
    }

    @Test
    fun receivesIndependentSafetyFromAugmentedQuantDocument() {
        assertTrue(receive(validDocument().toString()))
        val snapshot = requireNotNull(FinancialSafetyRuntimeStore.current(date))
        assertEquals(1, snapshot.passCount)
        assertEquals(100.0, snapshot.records["000001"]?.debtToEquityPct)
    }

    @Test
    fun exactDateValidatedSafetyCacheSurvivesTransportFailure() {
        assertTrue(receive(validDocument().toString()))
        FinancialSafetyRuntimeStore.clear()
        assertTrue(receive(null))
        assertEquals(1, FinancialSafetyRuntimeStore.current(date)?.passCount)
    }

    @Test
    fun missingAuthoritativeSafetyClearsRuntimeAndCachePermanently() {
        assertTrue(receive(validDocument().toString()))
        FinalCandidateRuntimeStore.install(date, emptyList())
        val missing = validDocument().apply { remove("financial_safety") }
        assertFalse(receive(missing.toString()))
        assertNull(FinancialSafetyRuntimeStore.current(date))
        assertEquals("", FinalCandidateRuntimeStore.installedSnapshotDate())
        assertFalse(receive(null))
        assertNull(FinancialSafetyRuntimeStore.current(date))
    }

    @Test
    fun safetyDateMismatchCannotReusePreviouslyValidSameDateCache() {
        assertTrue(receive(validDocument().toString()))
        val stale = validDocument().apply {
            getJSONObject("financial_safety").put("snapshot_date_kst", "2026-11-04")
        }
        assertFalse(receive(stale.toString()))
        assertNull(FinancialSafetyRuntimeStore.current(date))
        assertFalse(receive(null))
    }

    @Test
    fun outerDocumentMustMatchActiveQuantDateEvenWhenSafetyDateMatches() {
        val stale = validDocument().apply { put("snapshot_date_kst", "2026-11-04") }
        assertFalse(receive(stale.toString()))
        assertNull(FinancialSafetyRuntimeStore.current(date))
    }

    @Test
    fun cacheCannotSupplySafetyForAnotherQuantDate() {
        assertTrue(receive(validDocument().toString()))
        assertFalse(FinancialSafetySnapshotUpdater.refreshBlocking(
            directory.root, "2026-11-06", codes, null, ::offline,
        ))
        assertNull(FinancialSafetyRuntimeStore.current(date))
        assertNull(FinancialSafetyRuntimeStore.current("2026-11-06"))
    }

    @Test
    fun cachedSafetyMustMatchTheActiveQuantIdentitySet() {
        assertTrue(receive(validDocument().toString()))
        assertFalse(FinancialSafetySnapshotUpdater.refreshBlocking(
            directory.root, date, setOf("000002"), null, ::offline,
        ))
        assertNull(FinancialSafetyRuntimeStore.current(date))
    }

    @Test
    fun invalidSafetyPolicyIsHeldWithoutFallback() {
        assertTrue(receive(validDocument().toString()))
        val invalid = validDocument().apply {
            getJSONObject("financial_safety").getJSONObject("records")
                .getJSONObject("000001").put("debt_to_equity_pct", 401.0)
        }
        assertFalse(receive(invalid.toString()))
        assertNull(FinancialSafetyRuntimeStore.current(date))
        assertFalse(receive(null))
    }

    @Test
    fun suppliedQuantDocumentDoesNotDependOnAnAdditionalNetworkRequest() {
        val valid = validDocument().toString()
        assertTrue(FinancialSafetySnapshotUpdater.refreshBlocking(
            directory.root, date, codes, valid,
        ) { throw AssertionError("supplied active quant document must be used") })
    }

    @Test
    fun successfulNetworkReceiveUsesTheSameIndependentSafetyValidation() {
        assertTrue(FinancialSafetySnapshotUpdater.refreshBlocking(
            directory.root, date, codes, null,
        ) { validDocument().toString() })
        assertEquals(1, FinancialSafetyRuntimeStore.current(date)?.passCount)
    }

    @Test
    fun downloadedMissingSafetyCannotFallbackToTheSafetyCache() {
        assertTrue(receive(validDocument().toString()))
        val missing = validDocument().apply { remove("financial_safety") }
        assertFalse(FinancialSafetySnapshotUpdater.refreshBlocking(
            directory.root, date, codes, null,
        ) { missing.toString() })
        assertNull(FinancialSafetyRuntimeStore.current(date))
        assertFalse(receive(null))
    }

    @Test
    fun rejectedNetworkPayloadCannotReuseTheSafetyCache() {
        assertTrue(receive(validDocument().toString()))
        assertFalse(FinancialSafetySnapshotUpdater.refreshBlocking(
            directory.root, date, codes, null,
        ) { throw IllegalArgumentException("Stage4 payload exceeded size limit") })
        assertNull(FinancialSafetyRuntimeStore.current(date))
        assertFalse(receive(null))
    }

    @Test
    fun failedPersistenceCannotReviveOlderPassAfterAValidFailReceive() {
        assertTrue(receive(validDocument().toString()))
        assertTrue(File(directory.root, "kr4_stage4_financial_safety_v1.tmp").mkdir())
        val updated = validDocument().apply {
            val safety = getJSONObject("financial_safety")
            safety.getJSONObject("coverage").put("pass", 0).put("fail", 1)
            safety.getJSONObject("records").getJSONObject("000001")
                .put("status", "FAIL").put("reason", "DEBT_TO_EQUITY_OVER_400")
                .put("debt_to_equity_pct", 450.0)
        }
        assertTrue(receive(updated.toString()))
        assertEquals(1, FinancialSafetyRuntimeStore.current(date)?.failCount)
        FinancialSafetyRuntimeStore.clear()
        assertFalse(receive(null))
        assertNull(FinancialSafetyRuntimeStore.current(date))
    }

    private fun receive(body: String?): Boolean = FinancialSafetySnapshotUpdater.refreshBlocking(
        directory.root, date, codes, body, ::offline,
    )

    private fun offline(): String = throw IOException("offline")

    private fun validDocument(): JSONObject = JSONObject(
        """
        {
          "snapshot_date_kst": "2026-11-05",
          "financial_safety": {
            "schema_version": 1,
            "policy_version": "STAGE4_BS_SAFETY_V1",
            "snapshot_date_kst": "2026-11-05",
            "source": "OpenDART",
            "source_file": "fixture.zip",
            "thresholds": {
              "debt_to_equity_max_pct": 400.0,
              "current_ratio_min_pct": 70.0,
              "accounting_identity_tolerance_pct": 5.0
            },
            "coverage": {"pass": 1, "fail": 0, "hold": 0, "not_applicable": 0, "total": 1},
            "records": {
              "000001": {
                "status": "PASS", "reason": "PASS", "scope": "CFS",
                "debt_to_equity_pct": 100.0, "current_ratio_pct": 150.0,
                "identity_gap_pct": 0.1, "basis": "CFS fixture"
              }
            }
          }
        }
        """.trimIndent(),
    )
}
