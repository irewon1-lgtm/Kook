package com.krstock.v3

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.model.RealQuantRecord
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.components.FinancialSafetyBadge
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.screens.StockListScreen
import com.krstock.v3.ui.theme.KRStockV3Theme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs unchanged screens in each required Android14 viewport/theme CI cell. */
@RunWith(AndroidJUnit4::class)
class CompleteUiGalleryInstrumentedTest {
    @get:Rule(order = 0) val snapshotIsolation = SnapshotTestSupport.isolatedSnapshotRuntime()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 2) val failureEvidence = SnapshotTestSupport.failureEvidence()
    private lateinit var context: Context
    private lateinit var isolated: Context
    private lateinit var saved: SnapshotTestSupport.SavedRuntime
    private lateinit var recordedRows: List<RealQuantRecord>

    @Before fun prepareRecordedMarketData() {
        context = ApplicationProvider.getApplicationContext()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        saved = SnapshotTestSupport.SavedRuntime()
        isolated = SnapshotTestSupport.isolatedContext(context)
        SnapshotTestSupport.installHistoricalMemory(SnapshotTestSupport.CURRENT)
        recordedRows = GeneratedRealQuantSnapshot.rows.toList()
        assertEquals(2649, StockRepository.getAllStocks().size)
    }

    @After fun restore() {
        if (::saved.isInitialized) saved.restore()
        if (::isolated.isInitialized) isolated.filesDir.deleteRecursively()
    }

    private fun recorded(name: String, details: JSONObject = JSONObject()) {
        composeRule.waitForIdle()
        SnapshotTestSupport.evidence(context, name, details
            .put("evidence_kind", "REAL_MARKET_RECORDED").put("controlled_state", false)
            .put("source_provenance", JSONObject(SnapshotTestSupport.fixture("quant_fixture_provenance.json"))))
    }

    private fun controls() = composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(0)
    private fun click(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo().performClick()
    private fun hideKeyboard() {
        composeRule.activity.runOnUiThread {
            val view = composeRule.activity.window.decorView
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.requestFocus()
        }
        composeRule.waitForIdle()
    }

    @Test fun allFifteenMetricCombinationsSearchSortAndFullUniverseRemainReachable() {
        composeRule.setContent { KRStockV3Theme { StockListScreen(onStockClick = {}, onBack = {}) } }
        val records = JSONObject(SnapshotTestSupport.fixture()).getJSONObject("records")
        var selected = 15
        for (mask in 1..15) {
            assertTrue("Actual runtime rows must remain the recorded source throughout all15 combinations",
                recordedRows == GeneratedRealQuantSnapshot.rows)
            controls()
            // Add before removing so the UI's last-selected protection is exercised honestly.
            for (bit in 0..3) if (mask and (1 shl bit) != 0 && selected and (1 shl bit) == 0) {
                click("metric_toggle_M0${bit + 1}")
            }
            for (bit in 0..3) if (mask and (1 shl bit) == 0 && selected and (1 shl bit) != 0) {
                click("metric_toggle_M0${bit + 1}")
            }
            selected = mask
            for (bit in 0..3) {
                val chip = composeRule.onNodeWithTag("metric_toggle_M0${bit + 1}").performScrollTo()
                if (mask and (1 shl bit) != 0) chip.assertIsSelected() else chip.assertIsNotSelected()
            }
            val metrics = (1..4).filter { mask and (1 shl (it - 1)) != 0 }.map { "m0$it" }
            // Independent source-JSON oracle; no call to the production ranking engine.
            val eligible = records.keys().asSequence().mapNotNull { code ->
                val row = records.getJSONObject(code)
                if (metrics.any { row.getJSONObject(it).isNull("percentile") }) null
                else code to metrics.sumOf { row.getJSONObject(it).getDouble("percentile") } / metrics.size
            }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).toList()
            val firstCode = eligible.first().first
            recorded("ranking-mask-$mask-controls", JSONObject().put("metric_mask", mask))
            composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
            composeRule.onNodeWithTag("stock_$firstCode").assertExists()
            composeRule.onNodeWithTag("stock_rank_$firstCode", useUnmergedTree = true).assertTextEquals("1위")
            recorded("ranking-mask-$mask-first", JSONObject().put("metric_mask", mask).put("first_code", firstCode))
        }
        for (sort in listOf("CODE", "NAME", "COMBINATION")) {
            controls()
            click("sort_$sort")
            composeRule.onNodeWithTag("sort_$sort").assertIsSelected()
            recorded("ranking-sort-$sort", JSONObject().put("sort", sort))
        }
        controls()
        click("sort_CODE")
        // Includes every issuer, including unranked companies with missing metric reasons.
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(2649)
        val lastCode = StockRepository.getAllStocks().maxBy { it.issuerId }.issuerId
        composeRule.onNodeWithTag("stock_$lastCode").assertExists()
        recorded("ranking-universe-last", JSONObject().put("universe_count", 2649).put("last_code", lastCode))
        for (query in listOf("005930", "삼성전자")) {
            controls()
            composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement(query)
            hideKeyboard()
            composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
            composeRule.onNodeWithTag("stock_005930").assertExists()
            recorded("ranking-search-${if (query == "005930") "code" else "name"}", JSONObject().put("search", query))
        }
        val incomplete = records.keys().asSequence().first { code ->
            (1..4).any { records.getJSONObject(code).getJSONObject("m0$it").isNull("raw") }
        }
        controls()
        composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement(incomplete)
        hideKeyboard()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_$incomplete").assertExists()
        composeRule.onNodeWithText("선택지표 결측 · 조합순위 보류", substring = true, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        recorded("ranking-missing-metric-reason", JSONObject().put("issuer", incomplete).put("missing_metric_visible", true))
    }

    @Test fun everyDetailTabAndItsScrollableContentIsVisible() {
        composeRule.setContent { KRStockV3Theme { StockDetailScreen("005930", onBack = {}) } }
        val pages = listOf("detail_summary_page", "detail_metrics_page", "detail_analysis_page", "detail_source_page", "detail_chart_page")
        for ((index, tag) in pages.withIndex()) {
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
            recorded("detail-tab-$index", JSONObject().put("detail_tab", index).put("issuer", "005930"))
            when (index) {
                0 -> composeRule.onNodeWithTag("detail_snapshot_dates").performScrollTo().assertIsDisplayed()
                1 -> composeRule.onNodeWithTag("peer_benchmark_M04").performScrollTo().assertIsDisplayed()
                2 -> composeRule.onNodeWithTag("evidence_panel").performScrollTo().assertIsDisplayed()
                3 -> composeRule.onNodeWithTag("detail_source_card").performScrollTo().assertIsDisplayed()
                4 -> composeRule.onNodeWithTag("chart_interval_MONTH").performScrollTo().assertIsDisplayed()
            }
            recorded("detail-tab-$index-scrolled", JSONObject().put("detail_tab", index).put("issuer", "005930"))
            if (index < pages.lastIndex) {
                composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
                composeRule.waitForIdle()
            }
        }
    }

    @Test fun liveCacheBackupBundledAndFailedRefreshScreensAreExplicitlyControlled() {
        // Force a date mismatch with the previous mutable source state before fallback.
        val previous = SnapshotAutoUpdater.lastResult.snapshotDate
        SnapshotTestSupport.installHistoricalMemory(if (previous == "2026-11-05") SnapshotTestSupport.BEFORE else SnapshotTestSupport.CURRENT)
        val bundled = SnapshotAutoUpdater.refreshBlocking(isolated, safetyFetch = { SnapshotTestSupport.fixture() }) { SnapshotTestSupport.offline() }
        assertEquals(SnapshotAutoUpdater.Source.BUNDLED, bundled.source)
        composeRule.setContent { KRStockV3Theme { HomeScreen(onStockClick = {}, onNavigateToList = {}) } }
        fun capture(source: String, failed: Boolean = false) {
            composeRule.onNodeWithTag("snapshot_source").performScrollTo().assertTextEquals("데이터 출처 $source")
            if (failed) composeRule.onNodeWithTag("snapshot_failure").performScrollTo().assertIsDisplayed()
            SnapshotTestSupport.evidence(context, "source-${if (failed) "FAILED" else source}",
                JSONObject().put("scenario_source", if (failed) "FAILED" else source))
        }
        capture("BUNDLED")
        capture("BUNDLED", failed = true)
        SnapshotAutoUpdater.refreshBlocking(isolated, safetyFetch = { SnapshotTestSupport.fixture() }) { SnapshotTestSupport.fixture() }
        composeRule.waitForIdle()
        capture("LIVE")
        SnapshotAutoUpdater.refreshBlocking(isolated, safetyFetch = { SnapshotTestSupport.fixture() }) { SnapshotTestSupport.offline() }
        composeRule.waitForIdle()
        capture("CACHE")
        // A first-ever successful receive has no prior normal cache to rotate.
        // Exercise the real second successful receive before corrupting cache.
        val rotated = SnapshotAutoUpdater.refreshBlocking(isolated, safetyFetch = { SnapshotTestSupport.fixture() }) { SnapshotTestSupport.fixture() }
        assertTrue(rotated.networkSucceeded)
        val backup = File(isolated.filesDir, SnapshotTestSupport.BACKUP)
        assertTrue("Backup must be created by the real validated rotation", backup.isFile)
        assertEquals(SnapshotTestSupport.fixture(), backup.readText())
        File(isolated.filesDir, SnapshotTestSupport.CACHE).writeText("{corrupt")
        SnapshotAutoUpdater.refreshBlocking(isolated, safetyFetch = { SnapshotTestSupport.fixture() }) { SnapshotTestSupport.offline() }
        composeRule.waitForIdle()
        capture("BACKUP")
    }

    @Test fun eachSafetyStatusBadgeOpensItsReasonWithoutRemovingAnyStock() {
        val current = mutableStateOf<FinancialSafetyRecord?>(null)
        composeRule.setContent { KRStockV3Theme { Column { FinancialSafetyBadge(current.value) } } }
        for (status in FinancialSafetyStatus.entries) {
            val reason = when (status) {
                FinancialSafetyStatus.PASS -> "PASS"
                FinancialSafetyStatus.FAIL -> "DEBT_TO_EQUITY_OVER_400"
                FinancialSafetyStatus.HOLD -> "DART_NO_BALANCE_SHEET"
                FinancialSafetyStatus.NOT_APPLICABLE -> "FINANCIAL_SECTOR_NOT_COMPARABLE"
            }
            composeRule.runOnIdle {
                current.value = FinancialSafetyRecord("005930", status, reason, "CFS",
                    if (status == FinancialSafetyStatus.FAIL) 500.0 else 100.0, 90.0, 0.1, "CONTROLLED UI STATUS FIXTURE")
            }
            composeRule.onNodeWithTag("financial_safety_badge").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("financial_safety_details").assertIsDisplayed()
            SnapshotTestSupport.evidence(context, "safety-${status.name}", JSONObject().put("safety_status", status.name))
            composeRule.onNodeWithText("닫기").performClick()
            composeRule.onNodeWithTag("financial_safety_details").assertDoesNotExist()
            assertEquals(2649, StockRepository.getAllStocks().size)
        }
    }
}
