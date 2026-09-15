package com.krstock.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesFiltersSearchesAndOpensRealDataDetail() {
        composeRule.onNodeWithText("KR4 국내주식").assertIsDisplayed()
        composeRule.onNodeWithText("실데이터 4지표 연결 완료").assertIsDisplayed()

        composeRule.onNodeWithTag("home_list").performScrollToIndex(3)
        composeRule.onNodeWithText("전체보기 >").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("국내주식 4지표 목록").assertIsDisplayed()
        composeRule.onNodeWithText("KOSDAQ").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("stock_search").performTextInput("삼천당제약")
        composeRule.onNodeWithText("삼천당제약").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("삼천당제약 (000250)").assertIsDisplayed()
        composeRule.onNodeWithText("4대 정량지표").assertIsDisplayed()
        composeRule.onNodeWithText("데이터 한계와 출처").assertIsDisplayed()
    }
}
