package com.krstock.v3

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

    /**
     * Espresso's global closeSoftKeyboard()/pressBack() first waits for a focused
     * root window. Hosted API-34 emulators can transiently hand focus to the IME,
     * causing RootViewWithoutFocusException even though the app is healthy.
     * Drive these two system actions from the Activity itself instead, then wait
     * until the application window has focus again before using Compose semantics.
     */
    private fun hideKeyboardAndRestoreAppFocus() {
        val activity = composeRule.activity
        activity.runOnUiThread {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.decorView.requestFocus()
        }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activity.window.decorView.hasWindowFocus()
        }
        composeRule.waitForIdle()
    }

    private fun navigateBackThroughActivity() {
        val activity = composeRule.activity
        activity.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            activity.window.decorView.hasWindowFocus()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun swipeNavigationAndMultiMetricRankingWorkEndToEnd() {
        waitForDisplayedTag("main_pager")
        composeRule.onNodeWithText("KR4 국내주식").assertIsDisplayed()
        composeRule.onNodeWithText("실데이터 4지표 연결 완료").assertIsDisplayed()

        // The most important action must be immediately visible and open the searchable ranking list.
        composeRule.onNodeWithTag("primary_stock_explorer").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("국내주식 조합순위").assertIsDisplayed()

        // Exercise the actual Activity back dispatcher without Espresso's focused-root dependency.
        navigateBackThroughActivity()
        waitForDisplayedTag("primary_stock_explorer")
        composeRule.onNodeWithText("KR4 국내주식").assertIsDisplayed()

        // Horizontal swipe navigation remains supported.
        composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("국내주식 조합순위").assertIsDisplayed()

        // Regression: changing a ranking metric after browsing lower ranks must return to the new #1.
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(12)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("metric_toggle_M02").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1위").assertIsDisplayed()
        composeRule.onNodeWithText("3개 · 각 33.3%").assertIsDisplayed()

        composeRule.onNodeWithTag("metric_toggle_M04").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1위").assertIsDisplayed()
        composeRule.onNodeWithText("2개 · 각 50%").assertIsDisplayed()
        composeRule.onNodeWithTag("metric_toggle_M04").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3개 · 각 33.3%").assertIsDisplayed()

        // Sort-mode changes must also restart from the beginning rather than preserving stale scroll position.
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(10)
        composeRule.onNodeWithTag("sort_CODE").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sort_COMBINATION").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1위").assertIsDisplayed()

        composeRule.onNodeWithTag("market_KOSDAQ").performClick()
        composeRule.onNodeWithTag("stock_search").performTextInput("삼천당제약")
        hideKeyboardAndRestoreAppFocus()
        // A stock card is taller than the remaining list viewport. Verify the unique visual
        // comparison region instead of ambiguous axis text such as "성장", which also appears
        // elsewhere on the screen.
        composeRule.onNodeWithTag("stock_000250").performScrollTo()
        composeRule.onNodeWithText("한눈 비교", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("시장 내 상대위치", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("comparison_000250", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("stock_000250").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
        composeRule.onNodeWithText("삼천당제약 (000250)").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_summary_page").assertIsDisplayed()

        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_metrics_page").assertIsDisplayed()
        composeRule.onNodeWithText("4대 정량지표").assertIsDisplayed()
        composeRule.onNodeWithTag("peer_benchmark_M01").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_analysis_page").assertIsDisplayed()
        composeRule.onNodeWithTag("integrated_analysis").assertIsDisplayed()
        composeRule.onNodeWithText("4지표 종합해석").assertIsDisplayed()
        composeRule.onNodeWithTag("quarterly_history_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("evidence_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("왜 이런 숫자가 나왔는지 최근 근거 점검").assertIsDisplayed()

        composeRule.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_source_page").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_source_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("데이터 한계와 출처").assertIsDisplayed()
    }

    @Test
    fun stage4567DataIsVisibleOnHomeListAndCandidateDetail() {
        waitForDisplayedTag("main_pager")

        // Home must expose the V2 final-candidate state and its Stage4/5 context.
        // Candidate cards are clickable and merge descendant semantics, so nested
        // badge testTags are intentionally read from the unmerged tree.
        composeRule.onNodeWithTag("home_list").performScrollToIndex(3)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stock_025560").performScrollTo()
        composeRule.onNodeWithText("FINAL #1").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("financial_safety_badge", useUnmergedTree = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("valuation_band_badge", useUnmergedTree = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()

        // The full list must surface the same candidate identity without changing
        // its existing dynamic combination-ranking controls.
        composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stock_search").performTextInput("025560")
        hideKeyboardAndRestoreAppFocus()
        composeRule.onNodeWithTag("stock_025560").performScrollTo()
        composeRule.onNodeWithText("FINAL #1").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("financial_safety_badge", useUnmergedTree = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("valuation_band_badge", useUnmergedTree = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("stock_025560").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Candidate detail must explain Stage6/7 selection and show the full
        // Stage4 safety and Stage5 valuation cards on the summary page. Verify
        // the headings after scrolling rather than requiring a potentially tall
        // card container to fit the viewport at once.
        composeRule.onNodeWithTag("detail_summary_page").assertIsDisplayed()
        composeRule.onNodeWithText("최종 조사 후보").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stage4 재무안정성").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stage5 상대 PER 밴드").performScrollTo().assertIsDisplayed()
    }
}
