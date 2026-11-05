package com.krstock.v3

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.theme.KRStockV3Theme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Exercise the actual production detail tab and HTTP source on the Android device. */
@RunWith(AndroidJUnit4::class)
class PriceChartLiveInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val failureEvidence = SnapshotTestSupport.failureEvidence()

    @Test fun realStockDetailDownloadsAndShowsDailyWeeklyMonthlyActualPrices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        val quant = runBlocking { SnapshotAutoUpdater.bootstrap(context) }
        assertTrue("Actual quant snapshot is required: ${quant.failure}",quant.networkSucceeded)
        assertEquals(2649,StockRepository.registeredCount())
        val cutoff = LocalDate.parse(StockRepository.priceCutoffDate())
        val cache = File(context.filesDir,"price_history_v2/005930.json")
        cache.delete()
        composeRule.setContent { KRStockV3Theme { StockDetailScreen("005930",onBack={}) } }
        composeRule.onNodeWithText("차트").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis=90_000) {
            composeRule.onAllNodesWithTag("chart_source").fetchSemanticsNodes()
                .any { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { it.text.contains("LIVE") } }
        }
        assertTrue("The chart must persist received source OHLCV",cache.isFile)
        val source = JSONArray(cache.readText())
        val rows = (0 until source.length()).map { source.getJSONObject(it) }
            .sortedBy { it.getString("localTradedAt") }
        assertTrue(rows.size>200)
        assertTrue(rows.all { LocalDate.parse(it.getString("localTradedAt"))<=cutoff })
        val lastDay = LocalDate.parse(rows.last().getString("localTradedAt"))
        for (interval in listOf("DAY","WEEK","MONTH")) {
            composeRule.onNodeWithTag("chart_interval_$interval").performScrollTo().performClick().assertIsSelected()
            val start = when(interval) {
                "WEEK" -> lastDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                "MONTH" -> lastDay.withDayOfMonth(1)
                else -> lastDay
            }
            val actualDays = rows.filter { LocalDate.parse(it.getString("localTradedAt"))>=start }
            val open=actualDays.first().getDouble("openPrice")
            val high=actualDays.maxOf { it.getDouble("highPrice") }
            val low=actualDays.minOf { it.getDouble("lowPrice") }
            val close=actualDays.last().getDouble("closePrice")
            val volume=actualDays.sumOf { it.getLong("accumulatedTradingVolume") }
            val summary=String.format("시가 %,.0f · 고가 %,.0f · 저가 %,.0f · 종가 %,.0f",open,high,low,close)
            composeRule.onNodeWithTag("chart_last_ohlc").performScrollTo().assertTextEquals(summary)
            composeRule.onNodeWithTag("chart_last_volume").performScrollTo()
                .assertTextEquals(String.format("거래량 %,d주 · 실제 %d거래일",volume,actualDays.size))
            composeRule.onNodeWithTag("chart_candle_toggle").performScrollTo().performClick()
            composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
            SnapshotTestSupport.evidence(context,"chart-live-$interval",JSONObject()
                .put("issuer","005930").put("chart_source","LIVE").put("chart_interval",interval)
                .put("evidence_kind","REAL_MARKET_LIVE").put("controlled_state",false)
                .put("open",open).put("high",high).put("low",low).put("close",close)
                .put("volume",volume).put("observed_days",actualDays.size).put("daily_source",source))
            composeRule.onNodeWithTag("chart_candle_toggle").performScrollTo().performClick()
        }
    }
}
