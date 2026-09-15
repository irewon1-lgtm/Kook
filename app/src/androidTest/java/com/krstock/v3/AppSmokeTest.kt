package com.krstock.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitForDisplayedTag(tag: String, timeoutMillis: Long = 25_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test
    fun swipeNavigationAndMultiMetricRankingWorkEndToEnd() {
        // Auto-update bootstrap is intentionally asynchronous and can take longer
        // on a slow or partially unavailable network. Treat the loading screen as
        // valid transient state, but fail if the safe fallback/home never appears.
        waitForDisplayedTag("main_pager")
        composeRule.onNodeWithText("KR4 국내주식").assertIsDisplayed()
        composeRule.onNodeWithText("실데이터 4지표 연결 완료").assertIsDisplayed()

        // Requirement 1: move Home -> List by horizontal swipe, not by a button.
        composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("국내주식 조합순위").assertIsDisplayed()

        // Requirements 2~4: select three metrics at once and verify active-combination ranking UI.
        composeRule.onNodeWithTag("metric_toggle_M02").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("선택 3개 지표 종합순위").assertIsDisplayed()
        composeRule.onNodeWithText("각 지표 약 33.3%").assertIsDisplayed()

        // Also verify a two-metric state can be created without collapsing to single-select behavior.
        composeRule.onNodeWithTag("metric_toggle_M04").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("선택 2개 지표 종합순위").assertIsDisplayed()
        composeRule.onNodeWithText("각 지표 50%").assertIsDisplayed()
        composeRule.onNodeWithTag("metric_toggle_M04").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("선택 3개 지표 종합순위").assertIsDisplayed()

        composeRule.onNodeWithTag("market_KOSDAQ").performClick()
        composeRule.onNodeWithTag("stock_search").performTextInput("삼천당제약")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stock_000250").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
        composeRule.onNodeWithText("삼천당제약 (000250)").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_summary_page").assertIsDisplayed()

        // Requirement 1 inside detail: summary -> metrics -> analysis -> source by swiping.
        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_metrics_page").assertIsDisplayed()
        composeRule.onNodeWithText("4대 정량지표").assertIsDisplayed()
        composeRule.onNodeWithTag("peer_benchmark_M01").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_analysis_page").assertIsDisplayed()
        composeRule.onNodeWithText("정량 분석").assertIsDisplayed()

        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_source_page").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_source_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("데이터 한계와 출처").assertIsDisplayed()
    }
}
