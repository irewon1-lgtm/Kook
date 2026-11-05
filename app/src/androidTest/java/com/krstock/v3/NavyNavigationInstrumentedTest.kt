package com.krstock.v3

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        pressBack("main_home_tab", "home_list")
        composeRule.onNodeWithTag("main_home_tab").assertIsSelected()
        composeRule.onNodeWithTag("main_ranking_tab").performClick()
        composeRule.onNodeWithTag("stock_rank_list").assertIsDisplayed()
        composeRule.onNodeWithTag("main_search_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement("005930")
        hideKeyboard()
        composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("stock_005930").performClick()
        composeRule.onNodeWithTag("detail_title").assertIsDisplayed().assertTextContains("삼성전자", substring = true)
        composeRule.onNodeWithTag("main_bottom_navigation").assertDoesNotExist()
        composeRule.onNodeWithText("4지표").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("main_bottom_navigation").assertDoesNotExist()
        openChartAndAwaitMenu()
        composeRule.onNodeWithTag("main_search_tab").assertIsSelected()
        pressBack("main_search_tab", "stock_rank_list")

        composeRule.onNodeWithTag("main_favorites_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("favorites_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("favorites_add").performClick()
        composeRule.onNodeWithTag("favorites_search").performTextReplacement("005930")
        hideKeyboard()
        composeRule.onNodeWithTag("favorites_search").assertIsDisplayed()
        composeRule.onNodeWithTag("favorites_save").assertIsDisplayed()
        composeRule.onNodeWithTag("favorite_choice_005930").performClick().assertIsOn()
        composeRule.onNodeWithTag("favorites_save").performClick()
        assertEquals("삼성전자", StockRepository.getAllStocks().first { it.issuerId == "005930" }.name)
        composeRule.onNodeWithTag("favorite_stock_005930").assertIsDisplayed()
        assertEquals(setOf("005930"), preferences.getStringSet("favorite_codes", emptySet()))

        recreateAndWait()
        composeRule.onNodeWithTag("main_favorites_tab").performClick().assertIsSelected()
        composeRule.onNodeWithTag("favorite_stock_005930").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
        composeRule.onNodeWithTag("main_bottom_navigation").assertDoesNotExist()
        openChartAndAwaitMenu()
        try {
            composeRule.onNodeWithTag("main_favorites_tab").assertIsSelected()
        } catch (failure: Throwable) {
            runCatching {
                SnapshotTestSupport.evidence(context, "nav-restored-favorites-origin-failed", JSONObject()
                    .put("evidence_kind", "REAL_MARKET_RUNTIME").put("controlled_state", false)
                    .put("expected_origin", "FAVORITES")
                    .put("compose_semantics", composeRule.onRoot(useUnmergedTree = true).printToString()))
            }
            throw failure
        }
        pressBack("main_favorites_tab", "favorite_stock_005930")
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

    @Test fun backGestureAndImmediateBottomNavigationRemainSafeAfterRecreation() {
        val launchRevision = SnapshotAutoUpdater.lastResult.revision
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        waitForTag("main_home_tab")
        waitForRefresh(launchRevision)
        dismissOptionalUpdate()

        // The Home magnifier opens the real search route and its editable field.
        composeRule.onNodeWithContentDescription("종목 검색").performClick()
        composeRule.onNodeWithTag("main_search_tab").assertIsSelected()
        composeRule.onNodeWithTag("stock_search").assertIsDisplayed()
        hideKeyboard()
        pressBack("main_home_tab", "home_list")
        composeRule.onNodeWithTag("main_home_tab").assertIsSelected()

        val pendingRankingClick = checkNotNull(composeRule.onNodeWithTag("main_ranking_tab")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        scenario!!.onActivity { activity ->
            val dispatcher = activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            assertTrue(pendingRankingClick())
            // The pager coroutine has not run: entry and currentPage still look
            // like Home, so the synchronous navigation intent must cancel Back.
            dispatcher.onBackPressed()
            assertFalse("Pending Ranking navigation must not be mistaken for a root Back", activity.isFinishing)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("main_ranking_tab").assertIsSelected()
        composeRule.onNodeWithTag("stock_rank_list").assertIsDisplayed()
        pressBack("main_home_tab", "home_list")
        composeRule.onNodeWithTag("main_home_tab").assertIsSelected()

        repeat(2) { cycle ->
            composeRule.onNodeWithTag("main_search_tab").performClick()
            composeRule.onNodeWithTag("stock_search").performScrollTo().performTextReplacement("005930")
            hideKeyboard()
            composeRule.onNodeWithTag("stock_rank_list").performScrollToIndex(1)
            composeRule.onNodeWithTag("stock_005930").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
            composeRule.onNodeWithTag("main_bottom_navigation").assertDoesNotExist()
            openChartAndAwaitMenu()
            if (cycle == 1) recreateAndWait()
            composeRule.onNodeWithTag("detail_title").assertIsDisplayed()
            composeRule.onNodeWithTag("detail_chart_page").assertIsDisplayed()
            composeRule.onNodeWithTag("main_bottom_navigation").assertIsDisplayed()
            val rankingClick = checkNotNull(composeRule.onNodeWithTag("main_ranking_tab")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)

            // Deliberately change the actual production back stack within the same
            // input frame as Android's predictive Back dispatch. No idle wait may
            // hide the stale callback that crashed Navigation Compose2.8.0.
            scenario!!.onActivity { activity ->
                val dispatcher = activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                assertTrue(rankingClick())
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
                dispatcher.onBackPressed()
                assertFalse("A Back gesture from the old detail must not close the new destination", activity.isFinishing)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("main_ranking_tab").assertIsSelected()
            composeRule.onNodeWithTag("stock_rank_list").assertIsDisplayed()
            composeRule.onNodeWithTag("detail_title").assertDoesNotExist()

            // Returning from detail recomposes main. Its Back handling must still
            // respect a Search selection made during a Ranking-origin gesture.
            val searchClick = checkNotNull(composeRule.onNodeWithTag("main_search_tab")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            scenario!!.onActivity { activity ->
                val dispatcher = activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                assertTrue(searchClick())
                dispatcher.onBackPressed()
                assertFalse("The new Search destination must remain open", activity.isFinishing)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("main_search_tab").assertIsSelected()
            composeRule.onNodeWithTag("stock_search").assertIsDisplayed()
            hideKeyboard()
            pressBack("main_ranking_tab", "stock_rank_list")
            composeRule.onNodeWithTag("main_ranking_tab").assertIsSelected()
            pressBack("main_home_tab", "home_list")
            composeRule.onNodeWithTag("main_home_tab").assertIsSelected()
        }
    }

    private fun openChartAndAwaitMenu() {
        composeRule.onNodeWithText("차트").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onNodeWithTag("main_bottom_navigation").isDisplayed()
        }
        composeRule.onNodeWithTag("detail_chart_page").assertIsDisplayed()
        composeRule.onNodeWithTag("main_bottom_navigation").assertIsDisplayed()
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

    private fun pressBack(expectedMenuTag: String, expectedContentTag: String) {
        // GLOBAL_ACTION_BACK injects asynchronously in Android14. Compose can
        // report idle before the system has delivered either key event.
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        try {
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.onAllNodesWithTag(expectedMenuTag).fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrElse(SemanticsProperties.Selected) { false } == true &&
                    composeRule.onNodeWithTag(expectedContentTag).isDisplayed()
            }
        } catch (failure: Throwable) {
            // Capture before @After closes the Activity; the outer failure rule
            // otherwise records the teardown launcher rather than this state.
            runCatching {
                SnapshotTestSupport.evidence(context, "nav-back-$expectedMenuTag-failed", JSONObject()
                    .put("evidence_kind", "REAL_MARKET_RUNTIME").put("controlled_state", false)
                    .put("expected_menu_tag", expectedMenuTag).put("expected_content_tag", expectedContentTag)
                    .put("compose_semantics", composeRule.onRoot(useUnmergedTree = true).printToString()))
            }
            throw failure
        }
        composeRule.onNodeWithTag(expectedMenuTag).assertIsSelected()
        composeRule.onNodeWithTag(expectedContentTag).assertIsDisplayed()
    }

    private fun hideKeyboard() {
        lateinit var keyboardWindow: View
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var hideResult: Int? = null
        var hideRequestAccepted = false
        val hideReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                hideResult = resultCode
            }
        }
        scenario!!.onActivity { activity ->
            // AlertDialog owns another window. The Activity token is rejected
            // once the dialog's ComposeView is the IME's served view.
            keyboardWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                    ?: activity.window.decorView
            } else activity.window.decorView
            val focusedView = keyboardWindow.findFocus() ?: keyboardWindow
            hideRequestAccepted = inputMethodManager.hideSoftInputFromWindow(
                focusedView.windowToken, 0, hideReceiver,
            )
            focusedView.clearFocus()
            ViewCompat.getWindowInsetsController(keyboardWindow)?.hide(WindowInsetsCompat.Type.ime())
        }
        try {
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                var hidden = false
                scenario!!.onActivity {
                    // Insets can still be false while a prior SHOW is queued.
                    // The IME acknowledges only after hideWindow unregisters
                    // its Back callback; require that before navigation Back.
                    val hideAcknowledged = hideResult == InputMethodManager.RESULT_HIDDEN ||
                        hideResult == InputMethodManager.RESULT_UNCHANGED_HIDDEN
                    val noInputSession = !hideRequestAccepted && !inputMethodManager.isActive
                    hidden = (hideAcknowledged || noInputSession) &&
                        ViewCompat.getRootWindowInsets(keyboardWindow)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
                hidden
            }
        } catch (failure: Throwable) {
            runCatching {
                SnapshotTestSupport.evidence(context, "nav-hide-keyboard-failed", JSONObject()
                    .put("evidence_kind", "REAL_MARKET_RUNTIME").put("controlled_state", false)
                    .put("keyboard_window_class", keyboardWindow.javaClass.name)
                    .put("hide_request_accepted", hideRequestAccepted)
                    .put("hide_result", hideResult ?: JSONObject.NULL))
            }
            throw failure
        }
    }

    private fun dismissOptionalUpdate() {
        if (composeRule.onAllNodesWithText("나중에").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("나중에").performClick()
        }
    }
}
