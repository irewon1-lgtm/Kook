package com.krstock.v3

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.price.PriceHistoryRepository
import com.krstock.v3.data.price.PriceHistoryAggregation.Interval
import com.krstock.v3.ui.components.PriceChartPanel
import com.krstock.v3.ui.theme.KRStockV3Theme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PriceChartTimeframeInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val failureEvidence = SnapshotTestSupport.failureEvidence()
    private val cutoff = LocalDate.parse("2026-11-04")
    private fun bars(day: LocalDate = cutoff) = (1..5).flatMap {
        PriceHistoryRepository.parseBars(SnapshotTestSupport.fixture("prices_005930_20261105/page$it.json"), day)
    }
    private fun render(result: PriceHistoryRepository.Result?) {
        composeRule.setContent { KRStockV3Theme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PriceChartPanel(result, loading = false, initialCandleMode = false)
            }
        } }
    }
    private fun click(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo().performClick()
    private fun assertCount(count: Int, interval: Interval) {
        composeRule.onNodeWithTag("chart_bar_count").performScrollTo().assert(
            SemanticsMatcher("Exact displayed count $count ${interval.label}") { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { it.text.substringAfterLast(" · ") == "${count}개 ${interval.label}" }
            }
        )
    }
    private fun evidence(name: String, interval: Interval, count: Int, months: Int, candle: Boolean) {
        val details = JSONObject().put("chart_interval",interval.name).put("chart_bar_count",count)
            .put("chart_months",months).put("chart_style",if(candle) "CANDLE" else "LINE")
            .put("chart_fixture","prices_005930_20261105").put("chart_source","CACHE")
            .put("evidence_kind","REAL_MARKET_RECORDED").put("controlled_state",false)
            .put("source_provenance",JSONObject(SnapshotTestSupport.fixture("prices_005930_20261105/provenance.json")))
        composeRule.onNodeWithTag("chart_interval_${interval.name}").performScrollTo().assertIsSelected()
        composeRule.waitForIdle()
        SnapshotTestSupport.evidence(ApplicationProvider.getApplicationContext(),"$name-controls",JSONObject(details.toString()))
        composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        SnapshotTestSupport.evidence(ApplicationProvider.getApplicationContext(),name,details)
    }

    @Test fun allTwentyFourIntervalWindowStyleCombinationsRenderRealDataAndKeepSelections() {
        render(PriceHistoryRepository.Result(bars(),PriceHistoryRepository.Source.CACHE,cutoff,"실제 삼성전자 수신 샘플"))
        composeRule.onNodeWithTag("chart_adjustment_status").performScrollTo()
            .assertTextEquals("수정주가 미확인").assertIsDisplayed()
        SnapshotTestSupport.evidence(ApplicationProvider.getApplicationContext(), "chart-adjustment-unverified",
            JSONObject().put("adjustment_status", "UNVERIFIED").put("evidence_kind", "REAL_MARKET_RECORDED")
                .put("controlled_state", false).put("source_provenance",
                    JSONObject(SnapshotTestSupport.fixture("prices_005930_20261105/provenance.json"))))
        // Independent counts from the recorded source, not the production aggregation helper.
        val counts = mapOf(Interval.DAY to listOf(21,62,124,245),
            Interval.WEEK to listOf(5,14,27,53), Interval.MONTH to listOf(2,4,7,13))
        var combinations = 0
        for (interval in Interval.entries) {
            click("chart_interval_${interval.name}")
            for ((index,months) in listOf(1,3,6,12).withIndex()) {
                click("chart_period_$months")
                val n = counts.getValue(interval)[index]
                composeRule.onNodeWithTag("chart_interval_${interval.name}").assertIsSelected()
                composeRule.onNodeWithTag("chart_period_$months").assertIsSelected()
                for (candle in listOf(false,true)) {
                    if (candle) click("chart_candle_toggle")
                    composeRule.onNodeWithTag("chart_candle_toggle").let {
                        if (candle) it.assertIsOn() else it.assertIsOff()
                    }
                    assertCount(n,interval)
                    composeRule.onNodeWithTag("chart_last_volume").performScrollTo().assertTextEquals(
                        if(interval==Interval.DAY) "거래량 22,248,738주 · 실제 1거래일"
                        else "거래량 50,178,389주 · 실제 3거래일")
                    composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
                    combinations++
                    evidence("chart-${interval.name}-${months}m-${if(candle) "candle" else "line"}",interval,n,months,candle)
                    if (candle) click("chart_candle_toggle")
                }
                composeRule.onNodeWithTag("chart_interval_${interval.name}").assertIsSelected()
                composeRule.onNodeWithTag("chart_period_$months").assertIsSelected()
            }
        }
        org.junit.Assert.assertEquals(24,combinations)
    }

    @Test fun completedWeekAndCalendarMonthShowExactRealOhlcv() {
        val day = LocalDate.parse("2026-10-30")
        render(PriceHistoryRepository.Result(bars(day),PriceHistoryRepository.Source.CACHE,day,"실제 삼성전자 수신 샘플"))
        click("chart_interval_WEEK")
        click("chart_candle_toggle")
        composeRule.onNodeWithTag("chart_last_ohlc").performScrollTo()
            .assertTextEquals("시가 213,500 · 고가 249,000 · 저가 211,500 · 종가 220,500")
        composeRule.onNodeWithTag("chart_last_volume").performScrollTo().assertTextEquals("거래량 88,057,100주 · 실제 5거래일")
        assertCount(27,Interval.WEEK)
        evidence("chart-completed-week",Interval.WEEK,27,6,true)
        click("chart_interval_MONTH")
        composeRule.onNodeWithTag("chart_candle_toggle").assertIsOn()
        composeRule.onNodeWithTag("chart_last_ohlc").performScrollTo()
            .assertTextEquals("시가 266,500 · 고가 279,500 · 저가 203,500 · 종가 220,500")
        composeRule.onNodeWithTag("chart_last_volume").performScrollTo().assertTextEquals("거래량 361,741,542주 · 실제 20거래일")
        assertCount(7,Interval.MONTH)
        evidence("chart-completed-month",Interval.MONTH,7,6,true)
    }

    @Test fun oneObservedDayStillRendersBothChartStyles() {
        val one = bars().filter { it.date == cutoff }
        render(PriceHistoryRepository.Result(one,PriceHistoryRepository.Source.CACHE,cutoff,"실제 수신 1거래일"))
        click("chart_interval_MONTH")
        composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
        assertCount(1,Interval.MONTH)
        click("chart_candle_toggle")
        composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
    }

    @Test fun missingHistoryKeepsSelectorsWithoutInventingAnyChart() {
        render(PriceHistoryRepository.Result(emptyList(),PriceHistoryRepository.Source.MISSING,cutoff,"과거 종가 자료 미확보"))
        click("chart_interval_MONTH")
        click("chart_period_12")
        click("chart_candle_toggle")
        composeRule.onNodeWithTag("chart_interval_MONTH").assertIsSelected()
        composeRule.onNodeWithTag("chart_period_12").assertIsSelected()
        composeRule.onNodeWithTag("historical_price_chart").assertDoesNotExist()
        composeRule.onNodeWithText("과거 종가 자료 미확보").assertIsDisplayed()
    }
}
