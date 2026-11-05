package com.krstock.v3

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests actual launcher navigation and user choices without injecting market values. */
@RunWith(AndroidJUnit4::class)
class NavyNavigationInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    @get:Rule val failureEvidence = SnapshotTestSupport.failureEvidence()
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun preparePrivateUiPreferences() {
        context = ApplicationProvider.getApplicationContext()
        SnapshotTestSupport.stopPeriodicRefreshes(context)
        preferences = context.getSharedPreferences("kr4_navy_ui_preferences", Context.MODE_PRIVATE)
        savedPreferences = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        assertTrue(preferences.edit().clear().commit())
    }

    @After fun restorePrivateUiPreferences() {
        scenario?.close()
        if (::preferences.isInitialized) {
            val editor = preferences.edit().clear()
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue(editor.commit())
        }
    }

    @Test fun fiveMenusKeepPagerSearchAndFavoritesWorkingAcrossActivityRecreation() {
        val launchRevision = SnapshotAutoUpdater.lastResult.revision
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        waitForTag("main_home_tab")
        waitForRefresh(launchRevision)
        dismissOptionalUpdate()
        val tabs = listOf("home", "ranking", "search", "favorites", "more")
        tabs.forEach { composeRule.onNodeWithTag("main_${it}_tab").assertIsDisplayed() }
        composeRule.onNodeWithTag("main_home_tab").assertIsSelected()
        scenario!!.onActivity { activity ->
            assertEquals(Color.rgb(6, 19, 33), activity.window.statusBarColor)
            assertEquals(Color.rgb(6, 19, 33), activity.window.navigationBarColor)
            val flags = activity.window.decorView.systemUiVisibility
            assertEquals(0, flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            assertEquals(0, flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }

        composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("main_ranking_tab").assertIsSelected()
        pressBack()
        composeRule.onNodeWithTag("main_home_tab").assertIsSelected()
        composeRule.onNodeWithTag("main_ranking_tab").performClick()
        composeRule.onNodeWithTag("stock_rank_list").assertIsDisplayed()
        composeRule.onNodeWithTag("main_search_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement("005930")
        hideKeyboard()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_005930").performClick()
        composeRule.onNodeWithTag("detail_title").assertIsDisplayed().assertTextContains("삼성전자", substring = true)
        composeRule.onNodeWithTag("main_search_tab").assertIsSelected()
        pressBack()

        composeRule.onNodeWithTag("main_favorites_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("favorites_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("favorites_add").performClick()
        composeRule.onNodeWithTag("favorites_search").performTextReplacement("005930")
        hideKeyboard()
        composeRule.onNodeWithTag("favorite_choice_005930").performClick().assertIsOn()
        composeRule.onNodeWithTag("favorites_save").performClick()
        assertEquals("삼성전자", StockRepository.getAllStocks().first { it.issuerId == "005930" }.name)
        composeRule.onNodeWithTag("favorite_stock_005930").assertIsDisplayed()
        assertEquals(setOf("005930"), preferences.getStringSet("favorite_codes", emptySet()))

        recreateAndWait()
        composeRule.onNodeWithTag("main_favorites_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("favorite_stock_005930").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
        composeRule.onNodeWithTag("main_favorites_tab").assertIsSelected()
        pressBack()
        composeRule.onNodeWithTag("favorite_remove_005930").performClick()
        composeRule.onNodeWithTag("favorites_empty").assertIsDisplayed()
        assertEquals(emptySet<String>(), preferences.getStringSet("favorite_codes", emptySet()))
        recreateAndWait()
        composeRule.onNodeWithTag("main_favorites_tab").performClick()
        composeRule.onNodeWithTag("favorites_empty").assertIsDisplayed()

        composeRule.onNodeWithTag("main_more_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("more_version").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        val beforeRefresh = SnapshotAutoUpdater.lastResult.revision
        composeRule.onNodeWithTag("more_refresh").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            SnapshotAutoUpdater.lastResult.revision > beforeRefresh &&
                composeRule.onAllNodesWithTag("more_refresh").fetchSemanticsNodes().any {
                    !it.config.contains(SemanticsProperties.Disabled)
                }
        }
        composeRule.onNodeWithTag("more_source").assertTextContains(SnapshotAutoUpdater.lastResult.source.name, substring = true)
        SnapshotTestSupport.evidence(context, "nav-five-menus-favorites-persistence", JSONObject()
            .put("evidence_kind", "REAL_MARKET_RUNTIME").put("controlled_state", false)
            .put("favorite_issuer", "005930").put("favorite_name", "삼성전자")
            .put("favorite_add_and_remove_persisted", true).put("manual_refresh_invoked", true))
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(timeoutMillis = 90_000L) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForRefresh(previousRevision: Long) {
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val result = SnapshotAutoUpdater.lastResult
            result.revision > previousRevision && (result.networkSucceeded || result.failure != null)
        }
        composeRule.waitForIdle()
    }

    private fun recreateAndWait() {
        val before = SnapshotAutoUpdater.lastResult.revision
        scenario!!.recreate()
        waitForTag("main_favorites_tab")
        waitForRefresh(before)
        dismissOptionalUpdate()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        composeRule.waitForIdle()
    }

    private fun hideKeyboard() {
        scenario!!.onActivity { activity ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.decorView.requestFocus()
        }
        composeRule.waitForIdle()
    }

    private fun dismissOptionalUpdate() {
        if (composeRule.onAllNodesWithText("나중에").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("나중에").performClick()
        }
    }
}
