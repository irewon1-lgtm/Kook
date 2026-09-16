package com.krstock.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage4567NavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMillis: Long = 25_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test
    fun stage4567WorkspaceIsReachableAndVisible() {
        waitForTag("main_pager")
        composeRule.onNodeWithTag("main_bottom_nav").assertIsDisplayed()
        composeRule.onNodeWithText("안정·가치").performClick()
        composeRule.waitForIdle()
        waitForTag("stage4567_list")
        composeRule.onNodeWithText("안정 · 가치 · 최종후보").assertIsDisplayed()
        composeRule.onNodeWithTag("stage4567_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("stage4567_search").assertIsDisplayed()
    }
}
