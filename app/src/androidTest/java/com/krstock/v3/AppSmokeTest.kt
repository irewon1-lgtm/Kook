package com.krstock.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndKospiMasterNavigationWorks() {
        composeRule.onNodeWithText("KR4 국내주식").assertIsDisplayed()
        composeRule.onNodeWithText("KOSPI 실종목 등록 완료").assertIsDisplayed()

        composeRule.onNodeWithTag("home_list").performScrollToIndex(2)
        composeRule.onNodeWithText("전체보기 >").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("KOSPI 실종목 목록").assertIsDisplayed()
        composeRule.onNodeWithText("회사명 · 종목코드 · 업종 검색").assertIsDisplayed()
    }
}
