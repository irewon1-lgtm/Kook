package com.krstock.v3

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.screens.StockListScreen
import com.krstock.v3.ui.theme.KRStockV3Theme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real screens and repository: the refresh must repaint an already mounted screen. */
@RunWith(AndroidJUnit4::class)
class SnapshotRefreshScreensInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val failureEvidence = SnapshotTestSupport.failureEvidence()

    private lateinit var context: Context
    private lateinit var isolated: Context
    private lateinit var saved: SnapshotTestSupport.SavedRuntime

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        saved = SnapshotTestSupport.SavedRuntime()
        isolated = SnapshotTestSupport.isolatedContext(context)
        SnapshotTestSupport.installHistoricalMemory(SnapshotTestSupport.BEFORE)
        val initial = SnapshotAutoUpdater.refreshBlocking(isolated) {
            SnapshotTestSupport.fixture(SnapshotTestSupport.BEFORE)
        }
        assertTrue(initial.networkSucceeded)
    }

    @After
    fun restore() {
        if (::saved.isInitialized) saved.restore()
        if (::isolated.isInitialized) isolated.filesDir.deleteRecursively()
    }

    @Test
    fun backgroundRefreshRepaintsVisibleHomeDatesCountsAndStage4Hold() {
        composeRule.setContent {
            KRStockV3Theme { HomeScreen(onStockClick = {}, onNavigateToList = {}) }
        }
        composeRule.onNodeWithTag("snapshot_dates").performScrollTo()
            .assertTextEquals("재무 2026-10-07 · 주가 2026-10-07")
        composeRule.onNodeWithTag("snapshot_coverage").assertTextEquals("4지표 완성 2378/2649")
        val previousRevision = SnapshotAutoUpdater.lastResult.revision

        val result = SnapshotAutoUpdater.refreshBlocking(isolated) { SnapshotTestSupport.fixture() }
        composeRule.waitForIdle()

        assertTrue(result.networkSucceeded)
        assertTrue(result.revision > previousRevision)
        composeRule.onNodeWithTag("snapshot_source").performScrollTo()
            .assertTextEquals("데이터 출처 LIVE")
        composeRule.onNodeWithTag("snapshot_dates")
            .assertTextEquals("재무 2026-11-05 · 주가 2026-11-04")
        composeRule.onNodeWithTag("snapshot_coverage").assertTextEquals("4지표 완성 2367/2649")
        assertEquals(2367, StockRepository.completeCount())
        assertNull(FinancialSafetyRepository.currentSnapshot())
        assertTrue(FinalCandidateRepository.getFinalCandidates().isEmpty())
        composeRule.onNodeWithText("4지표 상위 순위")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ranked_stock_count").performScrollTo()
            .assertTextEquals("2367종목").assertIsDisplayed()
        SnapshotTestSupport.evidence(
            context, "background-home",
            JSONObject().put("stage4_status", "MISSING").put("four_metric_ranked", 2367)
        )
    }

    @Test
    fun backgroundRefreshRepaintsVisibleRankingIdentityDatesAndCount() {
        composeRule.setContent {
            KRStockV3Theme { StockListScreen(onStockClick = {}, onBack = {}) }
        }
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_187870").assertIsDisplayed()

        SnapshotAutoUpdater.refreshBlocking(isolated) { SnapshotTestSupport.fixture() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_032580").assertIsDisplayed()
        composeRule.onNodeWithTag("stock_rank_032580", useUnmergedTree = true).assertTextEquals("1위")
        SnapshotTestSupport.evidence(context, "background-ranking", JSONObject().put("first_code", "032580"))
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("ranking_snapshot_dates").performScrollTo()
            .assertTextEquals("재무 2026-11-05 · 주가 2026-11-04")
        composeRule.onNodeWithTag("ranking_snapshot_count").performScrollTo()
            .assertTextEquals("4지표 완성 2367/2649")
    }

    @Test
    fun sameDateCorrectedRealDataRepaintsVisibleSearchRank() {
        composeRule.setContent {
            KRStockV3Theme { StockListScreen(onStockClick = {}, onBack = {}) }
        }
        composeRule.onNodeWithTag("stock_search").performTextInput("000020")
        hideKeyboard()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_rank_000020", useUnmergedTree = true).assertTextEquals("576위")
        val previousRevision = SnapshotAutoUpdater.lastResult.revision

        val result = SnapshotAutoUpdater.refreshBlocking(isolated) {
            SnapshotTestSupport.fixture(SnapshotTestSupport.SAME_DATE_AFTER)
        }
        composeRule.waitForIdle()

        assertEquals("2026-10-07", result.snapshotDate)
        assertTrue(result.revision > previousRevision)
        composeRule.onNodeWithTag("stock_rank_000020", useUnmergedTree = true).assertTextEquals("578위")
        assertEquals(578, StockRepository.getStockDetail("000020")!!.summary.rankOrder)
        SnapshotTestSupport.evidence(context, "same-date-ranking", JSONObject().put("rank", 578))
    }

    @Test
    fun sameDateCorrectedRealDataRepaintsOpenDetailRankAndRawPriceReturn() {
        composeRule.setContent {
            KRStockV3Theme { StockDetailScreen(issuerId = "000020", onBack = {}) }
        }
        composeRule.onNodeWithTag("detail_summary_rank").performScrollTo().assertTextEquals("576위")
        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_m04_value").performScrollTo().assertTextEquals("-3.3%")
        val before = StockRepository.getStockDetail("000020")!!.summary.m04Price6m.rawValue

        SnapshotAutoUpdater.refreshBlocking(isolated) {
            SnapshotTestSupport.fixture(SnapshotTestSupport.SAME_DATE_AFTER)
        }
        composeRule.waitForIdle()

        val after = StockRepository.getStockDetail("000020")!!.summary.m04Price6m.rawValue
        assertEquals(-3.310105, before!!, 0.000001)
        assertEquals(-3.658537, after!!, 0.000001)
        composeRule.onNodeWithTag("detail_m04_value").assertTextEquals("-3.7%")
        SnapshotTestSupport.evidence(context, "background-detail", JSONObject().put("rank", 578).put("m04_raw", after))
        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_summary_rank").performScrollTo().assertTextEquals("578위")
        composeRule.onNodeWithTag("detail_snapshot_dates").performScrollTo()
            .assertTextEquals("재무 2026-10-07 · 주가 2026-10-07")
    }

    @Test
    fun offlineCacheAndCorruptCacheBackupRecoveryRemainVisible() {
        val current = SnapshotTestSupport.fixture()
        File(isolated.filesDir, SnapshotTestSupport.CACHE).writeText(current)
        File(isolated.filesDir, SnapshotTestSupport.BACKUP).writeText(current)
        var result = SnapshotAutoUpdater.refreshBlocking(isolated) { SnapshotTestSupport.offline() }
        assertEquals(SnapshotAutoUpdater.Source.CACHE, result.source)
        assertTrue(result.failure != null)
        composeRule.setContent {
            KRStockV3Theme { HomeScreen(onStockClick = {}, onNavigateToList = {}) }
        }
        composeRule.onNodeWithTag("snapshot_source").performScrollTo().assertTextEquals("데이터 출처 CACHE")
        composeRule.onNodeWithTag("snapshot_dates").assertTextEquals("재무 2026-11-05 · 주가 2026-11-04")
        composeRule.onNodeWithTag("snapshot_failure").performScrollTo().assertIsDisplayed()
        SnapshotTestSupport.evidence(context, "offline-cache")

        File(isolated.filesDir, SnapshotTestSupport.CACHE).writeText("{corrupt-cache")
        result = SnapshotAutoUpdater.refreshBlocking(isolated) { SnapshotTestSupport.offline() }
        composeRule.waitForIdle()
        assertEquals(SnapshotAutoUpdater.Source.BACKUP, result.source)
        composeRule.onNodeWithTag("snapshot_source").performScrollTo().assertTextEquals("데이터 출처 BACKUP")
        composeRule.onNodeWithTag("snapshot_coverage").assertTextEquals("4지표 완성 2367/2649")
        assertEquals(current, File(isolated.filesDir, SnapshotTestSupport.BACKUP).readText())
        SnapshotTestSupport.evidence(context, "corrupt-cache-backup")
    }

    @Test
    fun invalidAndDowngradedNetworkUpdatesPreserveDiskMemoryAndVisibleValues() {
        val current = SnapshotTestSupport.fixture()
        SnapshotAutoUpdater.refreshBlocking(isolated) { current }
        val cache = File(isolated.filesDir, SnapshotTestSupport.CACHE)
        val backup = File(isolated.filesDir, SnapshotTestSupport.BACKUP)
        val cacheBytes = cache.readBytes()
        val backupBytes = backup.readBytes()
        composeRule.setContent {
            KRStockV3Theme { HomeScreen(onStockClick = {}, onNavigateToList = {}) }
        }

        val invalid = JSONObject(current).apply { getJSONObject("coverage").put("complete_count", 1) }.toString()
        for (incoming in listOf(invalid, SnapshotTestSupport.fixture(SnapshotTestSupport.BEFORE))) {
            val result = SnapshotAutoUpdater.refreshBlocking(isolated) { incoming }
            composeRule.waitForIdle()
            assertTrue(!result.networkSucceeded)
            assertTrue(cacheBytes.contentEquals(cache.readBytes()))
            assertTrue(backupBytes.contentEquals(backup.readBytes()))
            assertEquals("2026-11-05", StockRepository.quantSnapshotDate())
            assertEquals(2367, StockRepository.completeCount())
            composeRule.onNodeWithTag("snapshot_dates").performScrollTo()
                .assertTextEquals("재무 2026-11-05 · 주가 2026-11-04")
            composeRule.onNodeWithTag("snapshot_coverage").assertTextEquals("4지표 완성 2367/2649")
        }
        SnapshotTestSupport.evidence(context, "rejected-update")
    }

    private fun hideKeyboard() {
        composeRule.activity.runOnUiThread {
            val activity = composeRule.activity
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.decorView.requestFocus()
        }
        composeRule.waitUntil(10_000) { composeRule.activity.window.decorView.hasWindowFocus() }
    }
}
