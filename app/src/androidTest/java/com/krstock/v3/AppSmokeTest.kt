package com.krstock.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndCoreNavigationWorks() {
        composeRule.onNodeWithText("KR4 국내주식 연구").assertIsDisplayed()
        composeRule.onNodeWithText("DEMO 데이터 모드").assertIsDisplayed()

        composeRule.onNodeWithText("전체보기 >").performClick()
        composeRule.onNodeWithText("국내주식 후보 목록").assertIsDisplayed()
        composeRule.onNodeWithText("종목명 · 코드 · 업종 검색").assertIsDisplayed()
    }
}
