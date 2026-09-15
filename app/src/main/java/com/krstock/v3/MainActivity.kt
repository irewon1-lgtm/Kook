package com.krstock.v3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.screens.StockListScreen
import com.krstock.v3.ui.theme.KRStockV3Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KRStockV3Theme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
                        val scope = rememberCoroutineScope()

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize().testTag("main_pager")
                        ) { page ->
                            when (page) {
                                0 -> HomeScreen(
                                    onStockClick = { issuerId -> navController.navigate("detail/$issuerId") },
                                    onNavigateToList = { scope.launch { pagerState.animateScrollToPage(1) } }
                                )
                                else -> StockListScreen(
                                    onStockClick = { issuerId -> navController.navigate("detail/$issuerId") },
                                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } }
                                )
                            }
                        }
                    }
                    composable("detail/{issuerId}") { backStackEntry ->
                        val issuerId = backStackEntry.arguments?.getString("issuerId").orEmpty()
                        StockDetailScreen(
                            issuerId = issuerId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
