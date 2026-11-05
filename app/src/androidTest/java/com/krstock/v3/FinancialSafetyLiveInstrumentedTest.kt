package com.krstock.v3

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.stage.FinancialSafetySnapshotUpdater
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.theme.KRStockV3Theme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Real GitHub publication -> production independent parser/store -> actual detail dialog.
 * PRs exercise the explicitly recorded candidate branch; main exercises the default main URL.
 */
@RunWith(AndroidJUnit4::class)
class FinancialSafetyLiveInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val failureEvidence = SnapshotTestSupport.failureEvidence()

    @Test fun publishedOfficialFactsAreReceivedAndDisplayedWithoutChangingAnyQuantMetric() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        val quant = runBlocking { SnapshotAutoUpdater.bootstrap(context) }
        assertTrue("Actual quant HTTP required: " + quant.failure, quant.networkSucceeded)
        val before = StockRepository.getAllStocks().toList()
        assertEquals(2649, before.size)
        val ref = InstrumentationRegistry.getArguments().getString("financialEvidenceRef") ?: "main"
        val (url, bytes) = downloadPublished(ref)
        val body = bytes.toString(Charsets.UTF_8)
        val published = JSONObject(body)
        val directory = File(context.cacheDir,"financial-http-proof").apply { mkdirs() }
        if (ref == "main") {
            // The bootstrap above must use the production default URL successfully.
            assertNotNull("Default Android auto-receive must install published Stage4",FinancialSafetyRepository.currentSnapshot())
        } else {
            assertTrue(FinancialSafetySnapshotUpdater.refreshBlocking(
                directory, StockRepository.quantSnapshotDate(), before.map { it.issuerId }.toSet(), null,
            ) { body })
        }
        assertEquals(before, StockRepository.getAllStocks())
        val snapshot = requireNotNull(FinancialSafetyRepository.currentSnapshot())
        assertEquals(StockRepository.quantSnapshotDate(),snapshot.snapshotDate)
        val source = published.getJSONObject("records")
        val samsung = snapshot.records.getValue("005930")
        assertTrue("Samsung official filing must be verified", samsung.facts?.verified == true)
        val selected = mutableStateOf("005930")
        composeRule.setContent { KRStockV3Theme {
            key(selected.value) { StockDetailScreen(selected.value,onBack={}) }
        } }
        val examples = linkedMapOf("PASS" to "005930")
        for (status in listOf("FAIL","HOLD","NOT_APPLICABLE")) {
            val code = source.keys().asSequence().firstOrNull { source.getJSONObject(it).getString("status") == status }
            assertNotNull("Actual collected example for " + status,code)
            examples[status] = requireNotNull(code)
        }
        for ((status, code) in examples) {
            composeRule.runOnIdle { selected.value = code }
            composeRule.onNodeWithTag("financial_safety_badge").performScrollTo().performClick()
            composeRule.onNodeWithTag("financial_safety_details").assertIsDisplayed()
            val row = source.getJSONObject(code)
            for (field in listOf("assets","liabilities","equity","current_assets","current_liabilities")) {
                if (row.isNull(field)) continue
                val amount = row.get(field).toString().toBigDecimal().longValueExact()
                val unit = if (row.getString("currency") == "KRW") "원" else " " + row.getString("currency")
                val expected = String.format(Locale.KOREA,"%,d",amount) + unit
                composeRule.onAllNodesWithText(expected).onFirst().performScrollTo().assertIsDisplayed()
                val installed = requireNotNull(snapshot.records.getValue(code).facts)
                assertEquals(amount,installed.amounts[field])
                SnapshotTestSupport.evidence(context,"financial-live-" + status + "-" + field,
                    metadata(status,code,url,ref,bytes,row).put("amount_field",field).put("displayed_amount",expected))
            }
            for (field in listOf("report_period","filing_date")) {
                val expected = row.optString(field,"")
                if (expected.isNotBlank()) composeRule.onAllNodesWithText(expected).onFirst().performScrollTo().assertIsDisplayed()
            }
            for (field in listOf("debt_to_equity_pct","current_ratio_pct")) {
                if (!row.isNull(field)) {
                    val ratio=String.format(Locale.KOREA,"%.2f%%",row.getDouble(field))
                    composeRule.onAllNodesWithText(ratio).onFirst().performScrollTo().assertIsDisplayed()
                }
            }
            val sourceUrl = row.optString("source_url","")
            if (sourceUrl.isNotBlank()) composeRule.onNodeWithTag("financial_safety_source").performScrollTo().assertTextEquals(sourceUrl)
            SnapshotTestSupport.evidence(context,"financial-live-" + status + "-source",metadata(status,code,url,ref,bytes,row))
            composeRule.onNodeWithText("닫기").performClick()
            composeRule.onNodeWithTag("financial_safety_details").assertDoesNotExist()
        }
        directory.deleteRecursively()
    }

    @Test fun independentBackgroundReceiptRepaintsAlreadyMountedFinancialDetailsAndPreservesRanks() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        val quant=runBlocking { SnapshotAutoUpdater.bootstrap(context) }
        assertTrue(quant.networkSucceeded)
        val before=StockRepository.getAllStocks().toList()
        val actualQuant=File(context.filesDir,SnapshotTestSupport.CACHE).readText()
        val ref=InstrumentationRegistry.getArguments().getString("financialEvidenceRef") ?: "main"
        val (url,bytes)=downloadPublished(ref)
        val body=bytes.toString(Charsets.UTF_8)
        val published=JSONObject(body)
        val row=published.getJSONObject("records").getJSONObject("005930")
        val isolated=SnapshotTestSupport.isolatedContext(context)
        SnapshotAutoUpdater.refreshBlocking(isolated,safetyFetch={ "{\"snapshot_date_kst\":\""+quant.snapshotDate+"\"}" }) { actualQuant }
        assertNull(FinancialSafetyRepository.currentSnapshot())
        composeRule.setContent { KRStockV3Theme { StockDetailScreen("005930",onBack={}) } }
        composeRule.onNodeWithTag("financial_safety_badge").performScrollTo()
        composeRule.onNodeWithText("미확보").assertIsDisplayed()
        val oldRevision=SnapshotAutoUpdater.lastResult.revision
        SnapshotAutoUpdater.refreshBlocking(isolated,safetyFetch={ body }) { actualQuant }
        composeRule.waitForIdle()
        assertTrue(SnapshotAutoUpdater.lastResult.revision>oldRevision)
        assertEquals(before,StockRepository.getAllStocks())
        composeRule.onNodeWithTag("financial_safety_badge").performScrollTo()
        composeRule.onNodeWithText("자료 확인").assertIsDisplayed()
        composeRule.onNodeWithTag("financial_safety_badge").performClick()
        val expected=String.format(Locale.KOREA,"%,d원",row.getLong("assets"))
        composeRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        SnapshotTestSupport.evidence(context,"financial-background-repaint",metadata("PASS","005930",url,ref,bytes,row)
            .put("evidence_kind","CONTROLLED_FIXTURE").put("controlled_state",true)
            .put("scenario","actual published market facts; controlled missing-artifact then background receive")
            .put("quant_values_and_ranks_unchanged",true))
        composeRule.onNodeWithText("닫기").performClick()
        isolated.filesDir.deleteRecursively()
    }

    private fun downloadPublished(ref:String):Pair<String,ByteArray> {
        val url = "https://raw.githubusercontent.com/irewon1-lgtm/Kook/" + ref + "/evidence/financial_safety_snapshot.json"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        val bytes = try {
            assertEquals("Published independent financial artifact",200,connection.responseCode)
            connection.inputStream.use { input -> input.readBytes().also { require(it.size <= 16*1024*1024) } }
        } finally { connection.disconnect() }
        return url to bytes
    }

    private fun metadata(status:String,code:String,url:String,ref:String,bytes:ByteArray,row:JSONObject):JSONObject =
        JSONObject().put("evidence_kind","REAL_MARKET_LIVE").put("controlled_state",false)
            .put("issuer",code).put("financial_status",status).put("financial_source_record",row)
            .put("financial_evidence_url",url).put("financial_evidence_ref",ref)
            .put("financial_feed_scope",if(ref=="main")"PRODUCTION_MAIN" else "CANDIDATE_BRANCH")
            .put("financial_payload_sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
