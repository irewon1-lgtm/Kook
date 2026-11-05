package com.krstock.v3

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Four approved-design evidence views, reached through the original launcher.
 * No setContent, source fixtures, injected scores, or replacement Activity are used.
 * CI repeats this on both system themes, including a 320dp / 1.3 font phone.
 */
@RunWith(AndroidJUnit4::class)
class NavyApprovedUiInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    @get:Rule val failureEvidence = SnapshotTestSupport.failureEvidence()

    private lateinit var context: Context
    private lateinit var savedRuntime: SnapshotTestSupport.SavedRuntime
    private var scenario: ActivityScenario<MainActivity>? = null
    private var launchRevision = 0L
    private val savedFiles = mutableMapOf<String, ByteArray?>()
    private lateinit var downloaded: JSONObject
    private lateinit var downloadedBytes: ByteArray

    @Before fun prepareRealLauncher() {
        context = ApplicationProvider.getApplicationContext()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        savedRuntime = SnapshotTestSupport.SavedRuntime()
        // A cached OHLCV body would allow a chart to render without this test's HTTP receive.
        val chart = "price_history_v2/005930.json"
        val file = File(context.filesDir, chart)
        savedFiles[chart] = file.takeIf { it.isFile }?.readBytes()
        file.delete()
        launchRevision = SnapshotAutoUpdater.lastResult.revision
    }

    @After fun restoreRuntime() {
        scenario?.close()
        if (::savedRuntime.isInitialized) savedRuntime.restore()
        if (::context.isInitialized) savedFiles.forEach { (name, bytes) ->
            val file = File(context.filesDir, name)
            if (bytes == null) file.delete() else {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
    }

    @Test fun launcherLiveScreensKeepApprovedNavyAndActualValuesWithoutClipping() {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        scenario = ActivityScenario.launch(intent)
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val result = SnapshotAutoUpdater.lastResult
            result.revision > launchRevision && result.networkSucceeded &&
                result.source == SnapshotAutoUpdater.Source.LIVE
        }
        // LIVE is published before optional finance/candidate I/O completes.
        // refreshBlocking owns this monitor for that entire existing pipeline.
        // Wait without repeatedly forcing Compose/Espresso measurement during
        // the intermediate HOLD -> verified financial-row layout transition.
        synchronized(SnapshotAutoUpdater) { SnapshotAutoUpdater.lastResult }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("main_pager").assertIsDisplayed()
        dismissOptionalUpdate()
        readAndCheckLiveOracle()
        // Core LIVE intentionally appears before optional finance I/O finishes.
        // Capture the independently validated published finance state, not that safe HOLD placeholder.
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            FinancialSafetyRepository.currentSnapshot()?.let {
                it.snapshotDate == StockRepository.quantSnapshotDate() && it.records.size == 2649
            } == true
        }
        val first = StockRepository.getAllStocks().filter { it.rankOrder != null }.minBy { it.rankOrder!! }
        val firstSafety = requireNotNull(FinancialSafetyRepository.get(first.issuerId))
        val expectedSafety = when (firstSafety.status) {
            FinancialSafetyStatus.PASS -> "자료 확인"
            FinancialSafetyStatus.FAIL -> "재무위험"
            FinancialSafetyStatus.HOLD -> "미확보"
            FinancialSafetyStatus.NOT_APPLICABLE -> "비교제외"
        }
        composeRule.onNode(hasTestTag("stock_${first.issuerId}") and
            hasAnyDescendant(hasText(expectedSafety))).assertIsDisplayed()

        composeRule.onNodeWithTag("home_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("main_home_tab").assertIsDisplayed().assertIsSelected()
        capture("home")
        assertSingleLineFits(composeRule.onNode(hasText("수익성") and
            hasAnyAncestor(hasTestTag("stock_${first.issuerId}")), useUnmergedTree = true), "Home profitability label")
        if (isApprovedPhoneViewport()) {
            assertFullyVisible("home_full_ranking_action", "home_list")
        }

        composeRule.onNodeWithTag("main_ranking_tab").performClick()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("metric_picker").performScrollTo().performClick()
        for (id in 1..4) composeRule.onNodeWithTag("metric_toggle_M0$id").assertIsSelected()
        composeRule.onNodeWithTag("metric_picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(0)
        capture("ranking", JSONObject().put("metric_mask", 15))
        if (isApprovedPhoneViewport()) {
            assertFullyVisible("ranking_full_list_action", "stock_rank_list")
        }
        val tenth = StockRepository.getAllStocks().filter { it.rankOrder != null }.sortedBy { it.rankOrder }[9]
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(10)
        val tenthRank = composeRule.onNodeWithTag("stock_rank_${tenth.issuerId}", useUnmergedTree = true)
            .assertTextEquals("10위")
        assertSingleLineFits(tenthRank, "Two-digit ranking badge")

        openActualStock("005930")
        // A loading placeholder is truthful, but cannot prove the approved quote view.
        // Wait for the real HTTP OHLCV result and compare the visible close with that body.
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            composeRule.onAllNodesWithTag("detail_quote_close").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { it.text.endsWith("원") }
            }
        }
        val quoteBars = JSONArray(File(context.filesDir, "price_history_v2/005930.json").readText())
        val lastQuote = quoteBars.getJSONObject(quoteBars.length() - 1)
        composeRule.onNodeWithTag("detail_quote_close")
            .assertTextEquals(String.format(java.util.Locale.KOREA, "%,.0f원", lastQuote.getDouble("closePrice")))
            .assertIsDisplayed()
        val volumeFact = composeRule.onNodeWithTag("detail_quote_fact_value_거래량", useUnmergedTree = true)
            .assertTextEquals(String.format(java.util.Locale.KOREA, "%,d주", lastQuote.getLong("accumulatedTradingVolume")))
        assertSingleLineFits(volumeFact, "Actual quote volume including its unit")
        composeRule.onNodeWithText("4지표").performClick()
        composeRule.onNodeWithTag("detail_metrics_page").assertIsDisplayed()
        val samsung = downloaded.getJSONObject("records").getJSONObject("005930")
        for (id in 1..4) {
            val value = samsung.getJSONObject("m0$id")
            val expected = if (value.isNull("raw")) "보류"
                else if (id == 3) String.format("%.2f배", value.getDouble("raw"))
                else String.format("%.1f%%", value.getDouble("raw"))
            composeRule.onNodeWithTag("detail_m0${id}_value").performScrollTo()
                .assertTextEquals(expected).assertIsDisplayed()
        }
        // The approved detail includes the composite score above all four metric rows.
        composeRule.onNodeWithTag("detail_metrics_composite").performScrollTo().assertIsDisplayed()
        val configuration = context.resources.configuration
        if (configuration.screenWidthDp == 360 && configuration.fontScale <= 1.01f) {
            for (id in 1..4) composeRule.onNodeWithTag("detail_m0${id}_value").assertIsDisplayed()
        }
        capture("detail", JSONObject().put("issuer", "005930").put("detail_tab", 1)
            .put("actual_record", samsung).put("actual_quote", lastQuote))
        if (isApprovedPhoneViewport()) {
            assertFullyVisible("financial_safety_evidence_entry", "detail_pager")
        }

        composeRule.onNodeWithText("차트").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            composeRule.onAllNodesWithTag("chart_source").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { "LIVE" in it.text }
            }
        }
        composeRule.onNodeWithTag("chart_interval_DAY").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("chart_period_6").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("chart_candle_toggle").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
        val daily = JSONArray(File(context.filesDir, "price_history_v2/005930.json").readText())
        assertTrue("The received OHLCV history must contain actual trading days", daily.length() > 200)
        // The existing PriceChartLive + 24-combination tests independently verify all aggregations.
        composeRule.onNodeWithTag("chart_interval_DAY").performScrollTo()
        composeRule.onNodeWithTag("historical_price_chart").assertIsDisplayed()
        capture("chart", JSONObject().put("issuer", "005930").put("detail_tab", 4)
            .put("chart_source", "LIVE").put("chart_interval", "DAY").put("chart_months", 6)
            .put("chart_style", "CANDLE").put("daily_source", daily))
        if (isApprovedPhoneViewport()) {
            assertFullyVisible("chart_last_ohlc", "detail_pager")
            assertFullyVisible("chart_last_volume", "detail_pager")
            assertFullyVisible("chart_source", "detail_pager")
        }

        checkLongestActualCompanyName()
    }

    private fun isApprovedPhoneViewport(): Boolean = context.resources.configuration.let {
        it.screenWidthDp == 360 && it.fontScale <= 1.01f
    }

    private fun assertFullyVisible(tag: String, viewportTag: String) {
        val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag(viewportTag).getUnclippedBoundsInRoot()
        assertTrue("$tag must fit fully above the viewport bottom: $bounds vs $viewport",
            bounds.bottom.value <= viewport.bottom.value + 0.5f)
        assertTrue("$tag must fit fully below the viewport top: $bounds vs $viewport",
            bounds.top.value >= viewport.top.value - 0.5f)
    }

    private fun assertSingleLineFits(node: SemanticsNodeInteraction, label: String) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$label must expose its rendered text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals("$label must remain on one readable line", 1, layout.lineCount)
            assertFalse("$label must not ellipsize actual data", layout.isLineEllipsized(0))
            // Compare actual glyph width, avoiding Compose1.7's unrelated
            // shrink-wrap vs parent-width mismatch in hasVisualOverflow.
            assertTrue("$label clips glyphs: ${layout.multiParagraph.maxIntrinsicWidth} > ${layout.size.width}",
                layout.multiParagraph.maxIntrinsicWidth <= layout.size.width + 1f)
        }
    }

    private fun readAndCheckLiveOracle() {
        downloadedBytes = File(context.filesDir, SnapshotTestSupport.CACHE).readBytes()
        downloaded = JSONObject(downloadedBytes.toString(Charsets.UTF_8))
        val rows = downloaded.getJSONObject("records")
        assertEquals(2649, rows.length())
        assertEquals(downloaded.getString("snapshot_date_kst"), GeneratedRealQuantSnapshot.snapshotDate)
        assertEquals(downloaded.getString("price_cutoff_date_kst"), StockRepository.priceCutoffDate())
        assertEquals(downloaded.getJSONObject("coverage").getInt("complete_count"), StockRepository.completeCount())
        assertEquals("삼성전자", rows.getJSONObject("005930").getString("name"))
    }

    private fun openActualStock(code: String) {
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(0)
        showSearchIfHidden()
        composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement(code)
        hideKeyboard()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_$code").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
    }

    private fun checkLongestActualCompanyName() {
        val stock = StockRepository.getAllStocks().maxWith(compareBy({ it.name.length }, { it.issuerId }))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        // Android14 GLOBAL_ACTION_BACK injects an asynchronous DOWN/UP pair.
        // Compose idle alone cannot prove the OS delivered it to the actual Activity.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("main_pager").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("main_ranking_tab").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasTestTag("main_ranking_tab") and isSelected())
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("stock_rank_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(0)
        showSearchIfHidden()
        composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement(stock.issuerId)
        hideKeyboard()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        assertTextFits("stock_name_${stock.issuerId}")
        composeRule.onNodeWithTag("stock_code_${stock.issuerId}", useUnmergedTree = true)
            .assertIsDisplayed().assertTextContains(stock.issuerId, substring = true)
        composeRule.onNodeWithTag("stock_${stock.issuerId}").performClick()
        assertTextFits("detail_title")
        val config = context.resources.configuration
        if (config.smallestScreenWidthDp < 600) {
            assertTrue("Required phone must exercise a narrow viewport", config.screenWidthDp <= 420)
        }
        SnapshotTestSupport.evidence(context, "navy-long-name-no-clipping", baseMetadata()
            .put("issuer", stock.issuerId).put("actual_company_name", stock.name)
            .put("name_length", stock.name.length).put("text_layout_overflow", false)
            .put("screen_width_dp", config.screenWidthDp))
    }

    private fun assertTextFits(tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue("$tag must expose its actual rendered text layout", layouts.isNotEmpty())
        if (layouts.any { it.hasVisualOverflow || (0 until it.lineCount).any(it::isLineEllipsized) }) {
            SnapshotTestSupport.evidence(context, "navy-text-layout-before-failure", baseMetadata()
                .put("failing_text_tag", tag).put("semantics_before_teardown", composeRule.onRoot().printToString()))
        }
        layouts.forEach { layout ->
            assertFalse("$tag clips actual company text", layout.hasVisualOverflow)
            assertTrue("$tag must not ellipsize an actual company name",
                (0 until layout.lineCount).none(layout::isLineEllipsized))
        }
    }

    private fun capture(screen: String, extra: JSONObject = JSONObject()) {
        composeRule.waitForIdle()
        assertEquals(SnapshotAutoUpdater.Source.LIVE, SnapshotAutoUpdater.lastResult.source)
        assertTrue(SnapshotAutoUpdater.lastResult.networkSucceeded)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val counts = paletteCounts(bitmap)
        bitmap.recycle()
        assertTrue("$screen must use approved #061321 navy background in either system theme", counts.getInt("background") > 0)
        assertTrue("$screen must use approved #0D1D2C navy cards", counts.getInt("surface") > 0)
        assertTrue("$screen must retain readable #F3F6FC foreground", counts.getInt("text") > 0)
        assertTrue("$screen must retain #178CFF primary emphasis", counts.getInt("primary") > 0)
        val metadata = baseMetadata().put("approved_screen", screen).put("palette_pixel_counts", counts)
        extra.keys().forEach { key -> metadata.put(key, extra.get(key)) }
        SnapshotTestSupport.evidence(context, "navy-approved-$screen", metadata)
    }

    private fun baseMetadata() = JSONObject().put("activity_class", MainActivity::class.java.name)
        .put("launch_action", Intent.ACTION_MAIN).put("launch_category", Intent.CATEGORY_LAUNCHER)
        .put("full_screen", true)
        .put("evidence_kind", "REAL_MARKET_LIVE").put("controlled_state", false)
        .put("scenario_source", "LIVE").put("screen_width_dp", context.resources.configuration.screenWidthDp)
        .put("source_provenance", JSONObject().put("cache_file", SnapshotTestSupport.CACHE)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(downloadedBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }))

    private fun paletteCounts(bitmap: Bitmap): JSONObject {
        val palette = linkedMapOf("background" to Color.rgb(6, 19, 33), "surface" to Color.rgb(13, 29, 44),
            "text" to Color.rgb(243, 246, 252), "primary" to Color.rgb(23, 140, 255))
        val counts = palette.keys.associateWith { 0 }.toMutableMap()
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            palette.forEach { (name, color) -> if (pixel == color) counts[name] = counts.getValue(name) + 1 }
        }
        return JSONObject(counts as Map<*, *>).put("sample_step_px", 3)
    }

    private fun hideKeyboard() {
        scenario!!.onActivity { activity ->
            val view = activity.window.decorView
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        composeRule.waitForIdle()
    }

    private fun showSearchIfHidden() {
        if (composeRule.onAllNodesWithTag("stock_search").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription("종목 검색").performClick()
        }
    }

    private fun dismissOptionalUpdate() {
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("나중에").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("나중에").performClick()
        }
    }
}
