package com.krstock.v3.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.screens.StockListScreen
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class MainMenu(val label: String, val route: String, val tag: String) {
    HOME("홈", "main", "main_home_tab"),
    RANKING("종목순위", "main", "main_ranking_tab"),
    SEARCH("종목검색", "search", "main_search_tab"),
    FAVORITES("관심종목", "favorites", "main_favorites_tab"),
    MORE("더보기", "more", "main_more_tab"),
}

/** Keep the original swipeable home/ranking pair and share navigation with all five menus. */
@Composable
fun NavyAppNavigation() {
    val navController = rememberNavController()
    // This state stays attached to the app while a detail or auxiliary route is open.
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val navigationIntentGeneration = remember { mutableLongStateOf(0L) }
    val entry by navController.currentBackStackEntryAsState()
    var detailOrigin by rememberSaveable { mutableStateOf(MainMenu.HOME) }
    var currentDetailTab by rememberSaveable { mutableIntStateOf(0) }
    val route = entry?.destination?.route ?: "main"
    val selectedMenu = when (route) {
        "search" -> MainMenu.SEARCH
        "favorites" -> MainMenu.FAVORITES
        "more" -> MainMenu.MORE
        "detail/{issuerId}" -> detailOrigin
        else -> if (pagerState.currentPage == 0) MainMenu.HOME else MainMenu.RANKING
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun showMain(page: Int) {
        navController.navigate("main") {
            popUpTo("main") { inclusive = false }
            launchSingleTop = true
        }
        scope.launch { pagerState.scrollToPage(page) }
    }
    fun showDetail(issuerId: String) {
        detailOrigin = selectedMenu
        currentDetailTab = 0
        navController.navigate("detail/$issuerId")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("main") {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().testTag("main_pager"),
                ) { page ->
                    when (page) {
                        0 -> HomeScreen(
                            onStockClick = ::showDetail,
                            onNavigateToList = {
                                navigationIntentGeneration.longValue++
                                scope.launch { pagerState.animateScrollToPage(1) }
                            },
                            onSearch = {
                                navigationIntentGeneration.longValue++
                                navController.navigate("search") { launchSingleTop = true }
                            },
                        )
                        else -> StockListScreen(
                            onStockClick = ::showDetail,
                            onBack = {
                                navigationIntentGeneration.longValue++
                                scope.launch { pagerState.scrollToPage(0) }
                            },
                        )
                    }
                }
            }
            composable("search") {
                StockListScreen(
                    onStockClick = ::showDetail,
                    onBack = { navController.popBackStack() },
                    initialSearch = true,
                )
            }
            composable("favorites") { NavyFavoritesScreen(onStockClick = ::showDetail) }
            composable("more") { NavyMoreScreen() }
            composable("detail/{issuerId}") { detailEntry ->
                StockDetailScreen(
                    issuerId = detailEntry.arguments?.getString("issuerId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSearch = { navController.navigate("search") { launchSingleTop = true } },
                    onTabChanged = { currentDetailTab = it },
                )
            }
        }
        if (route != "detail/{issuerId}" || currentDetailTab == 4) {
            NavyBottomMenus(selectedMenu) { menu ->
                // Mark the intent before launching a pager coroutine. During the
                // same input frame its entry/page can still describe the old screen.
                navigationIntentGeneration.longValue++
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                when (menu) {
                    MainMenu.HOME -> showMain(0)
                    MainMenu.RANKING -> showMain(1)
                    else -> navController.navigate(menu.route) {
                        popUpTo("main") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }
    GuardedNavigationBackHandler(navController, pagerState, navigationIntentGeneration) {
        scope.launch { pagerState.scrollToPage(0) }
    }
}

/**
 * Navigation Compose2.8.0's predictive callback can still run after a menu click
 * has reduced its captured stack to one entry. Own dispatch after the NavHost,
 * and resolve Back from the live controller without indexing that stale stack.
 */
@Composable
private fun GuardedNavigationBackHandler(
    navController: NavHostController,
    pagerState: PagerState,
    navigationIntentGeneration: State<Long>,
    returnHome: () -> Unit,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity()
    val latestReturnHome by rememberUpdatedState(returnHome)
    val callback = remember(navController, pagerState, navigationIntentGeneration, activity) {
        object : OnBackPressedCallback(true) {
            private var startedEntryId: String? = null
            private var startedPage: Int? = null
            private var startedNavigationIntentGeneration: Long? = null

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                startedEntryId = navController.currentBackStackEntry?.id
                startedPage = pagerState.currentPage
                startedNavigationIntentGeneration = navigationIntentGeneration.value
            }

            override fun handleOnBackCancelled() {
                startedEntryId = null
                startedPage = null
                startedNavigationIntentGeneration = null
            }

            override fun handleOnBackPressed() {
                val originId = startedEntryId
                val originPage = startedPage
                val originNavigationGeneration = startedNavigationIntentGeneration
                handleOnBackCancelled()
                // A menu selection during the gesture already changed the
                // destination. Committing the old gesture must not pop or close it.
                if (originPage != null &&
                    (navigationIntentGeneration.value != originNavigationGeneration ||
                        navController.currentBackStackEntry?.id != originId || pagerState.currentPage != originPage)) return
                when {
                    navController.previousBackStackEntry != null -> navController.popBackStack()
                    pagerState.currentPage != 0 -> latestReturnHome()
                    else -> activity?.finish()
                }
            }
        }
    }
    DisposableEffect(dispatcher, lifecycleOwner, callback) {
        dispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun NavyBottomMenus(selectedMenu: MainMenu, onSelect: (MainMenu) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.background)) {
        HorizontalDivider(color = scheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(61.dp).testTag("main_bottom_navigation")) {
            MainMenu.entries.forEach { menu ->
                val selected = selectedMenu == menu
                val color = if (selected) scheme.primary else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(menu) })
                        .testTag(menu.tag).padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    MainMenuIcon(menu, color, selected)
                    Text(menu.label, fontSize = 10.sp, lineHeight = 13.sp,
                        color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun MainMenuIcon(menu: MainMenu, color: Color, selected: Boolean) {
    Canvas(Modifier.size(23.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(width = 1.7f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (menu) {
            MainMenu.HOME -> {
                val home = Path().apply {
                    moveTo(3f * unit, 10f * unit)
                    lineTo(12f * unit, 2.5f * unit)
                    lineTo(21f * unit, 10f * unit)
                    lineTo(20f * unit, 10f * unit)
                    lineTo(20f * unit, 21f * unit)
                    lineTo(14.5f * unit, 21f * unit)
                    lineTo(14.5f * unit, 14f * unit)
                    lineTo(9.5f * unit, 14f * unit)
                    lineTo(9.5f * unit, 21f * unit)
                    lineTo(4f * unit, 21f * unit)
                    lineTo(4f * unit, 10f * unit)
                    close()
                }
                if (selected) drawPath(home, color) else drawPath(home, color, style = stroke)
            }
            MainMenu.RANKING -> {
                listOf(4f to 14f, 10f to 9f, 16f to 3f).forEach { (x, y) ->
                    drawRect(color, topLeft = point(x, y), size = Size(3.5f * unit, (21f - y) * unit), style = stroke)
                }
            }
            MainMenu.SEARCH -> {
                drawCircle(color, radius = 7.5f * unit, center = point(10f, 10f), style = stroke)
                drawLine(color, point(15.5f, 15.5f), point(21.5f, 21.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            MainMenu.FAVORITES -> {
                val star = Path()
                repeat(10) { i ->
                    val angle = -PI / 2 + i * PI / 5
                    val radius = if (i % 2 == 0) 10f else 4.4f
                    val x = (12f + radius * cos(angle).toFloat()) * unit
                    val y = (12f + radius * sin(angle).toFloat()) * unit
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                if (selected) drawPath(star, color) else drawPath(star, color, style = stroke)
            }
            MainMenu.MORE -> listOf(4f, 12f, 20f).forEach { x ->
                drawCircle(color, radius = 1.6f * unit, center = point(x, 12f))
            }
        }
    }
}
