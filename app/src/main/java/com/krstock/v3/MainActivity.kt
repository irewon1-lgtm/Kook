package com.krstock.v3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.screens.StockListScreen
import com.krstock.v3.ui.theme.KRStockV3Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KRStockV3Theme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onStockClick = { issuerId ->
                                navController.navigate("detail/$issuerId")
                            },
                            onNavigateToList = {
                                navController.navigate("list")
                            }
                        )
                    }
                    composable("list") {
                        StockListScreen(
                            onStockClick = { issuerId ->
                                navController.navigate("detail/$issuerId")
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                    composable("detail/{issuerId}") { backStackEntry ->
                        val issuerId = backStackEntry.arguments?.getString("issuerId").orEmpty()
                        StockDetailScreen(
                            issuerId = issuerId,
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
