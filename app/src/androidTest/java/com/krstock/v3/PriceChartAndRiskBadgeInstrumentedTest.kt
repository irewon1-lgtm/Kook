package com.krstock.v3

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.price.PriceHistoryRepository
import com.krstock.v3.ui.components.FinancialSafetyBadge
import com.krstock.v3.ui.components.PriceChartPanel
import com.krstock.v3.ui.theme.KRStockV3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PriceChartAndRiskBadgeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dailyBarsRejectFutureAndMalformedValuesAndSortByDate() {
        val json = """
            [
              {"localTradedAt":"2026-11-04","openPrice":"10,100","highPrice":"10,600",
                "lowPrice":"9,900","closePrice":"10,500","accumulatedTradingVolume":"1,000"},
              {"localTradedAt":"2026-11-06","closePrice":"10,900"},
              {"localTradedAt":"2026-11-03","openPrice":"10,000","highPrice":"10,100",
                "lowPrice":"9,900","closePrice":"10,000"},
              {"localTradedAt":"invalid","closePrice":"9,800"},
              {"localTradedAt":"2026-11-02","openPrice":"100","highPrice":"50",
                "lowPrice":"80","closePrice":"100"}
            ]
        """.trimIndent()
        val bars = PriceHistoryRepository.parseBars(json, LocalDate.parse("2026-11-04"))
        assertEquals(2, bars.size)
        assertEquals("2026-11-03", bars[0].date.toString())
        assertEquals("2026-11-04", bars[1].date.toString())
        assertEquals(10500.0, bars[1].close, 0.01)
    }

    @Test
    fun historicalLineAndCandleChartRenderWithoutLiveNetwork() {
        val bars = (1..5).flatMap { page ->
            PriceHistoryRepository.parseBars(
                SnapshotTestSupport.fixture("prices_005930_20261105/page$page.json"),
                LocalDate.parse("2026-11-04"),
            )
        }
        val result = PriceHistoryRepository.Result(
            bars = bars,
            source = PriceHistoryRepository.Source.CACHE,
            priceCutoffDate = LocalDate.parse("2026-11-04"),
            detail = "실제 수신 삼성전자 일봉 고정 샘플",
        )
        composeRule.setContent { KRStockV3Theme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PriceChartPanel(result = result, loading = false)
            }
        } }
        composeRule.onNodeWithTag("price_chart_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("chart_period_1").performScrollTo().performClick()
        composeRule.onNodeWithTag("chart_candle_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("historical_price_chart").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun riskIsRedUnavailableIsOrangeAndNeitherHidesTicker() {
        val risk = FinancialSafetyRecord(
            issuerId = "005930",
            status = FinancialSafetyStatus.FAIL,
            reasonCode = "DEBT_RATIO_EXCEEDED",
            scope = "CFS",
            debtToEquityPct = 500.0,
            currentRatioPct = 90.0,
            accountingIdentityGapPct = 0.1,
            basis = "Test",
        )
        composeRule.setContent {
            KRStockV3Theme {
                Column {
                    FinancialSafetyBadge(risk)
                    FinancialSafetyBadge(null)
                }
            }
        }
        composeRule.onNodeWithText("재무위험").assertIsDisplayed()
        composeRule.onNodeWithText("미확보").assertIsDisplayed()
        val riskPixels = composeRule.onAllNodesWithTag("financial_safety_badge").onFirst().captureToImage().toPixelMap()
        val missingPixels = composeRule.onAllNodesWithTag("financial_safety_badge").onLast().captureToImage().toPixelMap()
        assertEquals(Color(0xFFC62828).toArgb(), riskPixels[riskPixels.width / 20, riskPixels.height / 2].toArgb())
        assertEquals(Color(0xFFEF6C00).toArgb(), missingPixels[missingPixels.width / 20, missingPixels.height / 2].toArgb())
        assertEquals(2649, com.krstock.v3.data.repository.StockRepository.getAllStocks().size)
    }
}
