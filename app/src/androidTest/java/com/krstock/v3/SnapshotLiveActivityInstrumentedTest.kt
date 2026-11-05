package com.krstock.v3

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Required live HTTP check: cache-free launch, recreation, and the public worker entry point. */
@RunWith(AndroidJUnit4::class)
class SnapshotLiveActivityInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val failureEvidence = SnapshotTestSupport.failureEvidence()

    private lateinit var context: Context
    private lateinit var savedRuntime: SnapshotTestSupport.SavedRuntime
    private val savedFiles = mutableMapOf<String, ByteArray?>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var launchRevision = 0L

    @Before
    fun startWithoutQuantCache() {
        context = ApplicationProvider.getApplicationContext()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        savedRuntime = SnapshotTestSupport.SavedRuntime()
        for (name in listOf(
            SnapshotTestSupport.CACHE, SnapshotTestSupport.BACKUP, "kr4_snapshot.tmp",
            "kr4_stage4_financial_safety_v1.json", "kr4_stage4_financial_safety_v1.tmp",
            "kr4_final_candidates_v2.json", "kr4_final_candidates_v2.tmp",
        )) {
            val file = File(context.filesDir, name)
            savedFiles[name] = file.takeIf { it.isFile }?.readBytes()
            file.delete()
        }
        // Set an actual older collector snapshot to prove bootstrap changes runtime as well as disk.
        SnapshotTestSupport.installHistoricalMemory(SnapshotTestSupport.BEFORE)
        launchRevision = SnapshotAutoUpdater.lastResult.revision
    }

    @After
    fun restore() {
        scenario?.close()
        if (::savedRuntime.isInitialized) savedRuntime.restore()
        if (::context.isInitialized) {
            savedFiles.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes == null) file.delete() else file.writeBytes(bytes)
            }
        }
    }

    @Test
    fun actualHttpColdBootRecreationAndBackgroundRefreshReachVisibleCurrentData() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForLiveSnapshot()
        dismissOptionalAppUpdate()
        assertVisibleValuesMatchDownloadedJson("cold-boot-live")

        val previousRevision = SnapshotAutoUpdater.lastResult.revision
        scenario!!.recreate()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            SnapshotAutoUpdater.lastResult.revision > previousRevision &&
                SnapshotAutoUpdater.lastResult.source == SnapshotAutoUpdater.Source.LIVE &&
                SnapshotAutoUpdater.lastResult.networkSucceeded
        }
        dismissOptionalAppUpdate()
        assertVisibleValuesMatchDownloadedJson("recreated-live")

        val beforeBackground = SnapshotAutoUpdater.lastResult.revision
        val result = runBlocking { SnapshotAutoUpdater.backgroundRefresh(context) }
        assertTrue("Real HTTP background refresh failed: ${result.failure}", result.networkSucceeded)
        assertTrue(result.revision > beforeBackground)
        composeRule.waitForIdle()
        assertVisibleValuesMatchDownloadedJson("live-background")
    }

    private fun waitForLiveSnapshot() {
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val result = SnapshotAutoUpdater.lastResult
            result.source == SnapshotAutoUpdater.Source.LIVE && result.networkSucceeded &&
                result.revision > launchRevision &&
                result.snapshotDate >= "2026-11-05" &&
                GeneratedRealQuantSnapshot.snapshotDate == result.snapshotDate &&
                composeRule.onAllNodesWithTag("main_pager").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dismissOptionalAppUpdate() {
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("나중에").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("나중에").performClick()
            composeRule.waitForIdle()
        }
    }

    private fun assertVisibleValuesMatchDownloadedJson(name: String) {
        val json = JSONObject(File(context.filesDir, SnapshotTestSupport.CACHE).readText())
        val date = json.getString("snapshot_date_kst")
        val price = json.getString("price_cutoff_date_kst")
        val complete = json.getJSONObject("coverage").getInt("complete_count")
        assertEquals(2649, json.getJSONObject("records").length())
        assertEquals(date, GeneratedRealQuantSnapshot.snapshotDate)
        assertEquals(price, GeneratedRealQuantSnapshot.priceCutoffDate)
        assertEquals(complete, StockRepository.completeCount())
        assertEquals(complete, StockRepository.getAllStocks().count { it.rankOrder != null })
        composeRule.onNodeWithTag("snapshot_source").performScrollTo()
            .assertTextEquals("데이터 출처 LIVE").assertIsDisplayed()
        composeRule.onNodeWithTag("snapshot_dates")
            .assertTextEquals("재무 $date · 주가 $price").assertIsDisplayed()
        composeRule.onNodeWithTag("snapshot_coverage")
            .assertTextEquals("4지표 완성 $complete/2649").assertIsDisplayed()
        composeRule.onNodeWithTag("snapshot_last_refresh").performScrollTo().assertIsDisplayed()
        val visible = composeRule.onNodeWithTag("snapshot_dates").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        SnapshotTestSupport.evidence(
            context, name,
            JSONObject().put("downloaded_complete_count", complete).put("visible_dates", visible),
        )
    }
}
