package com.krstock.v3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.StockDetailScreen
import com.krstock.v3.ui.screens.StockListScreen
import com.krstock.v3.ui.theme.KRStockV3Theme
import com.krstock.v3.update.AppAutoUpdater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KRStockV3Theme {
                var ready by remember { mutableStateOf(false) }
                var pendingUpdate by remember { mutableStateOf<AppAutoUpdater.PreparedUpdate?>(null) }

                val unknownSourcesLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    pendingUpdate?.let { prepared ->
                        runCatching { AppAutoUpdater.requestInstall(this@MainActivity, prepared) }
                    }
                }

                LaunchedEffect(Unit) {
                    runCatching { SnapshotAutoUpdater.bootstrap(this@MainActivity) }
                    ready = true
                }

                LaunchedEffect(ready) {
                    if (ready) {
                        val cached = AppAutoUpdater.loadPrepared(this@MainActivity)
                        pendingUpdate = cached ?: runCatching {
                            AppAutoUpdater.checkAndDownload(this@MainActivity)
                        }.getOrNull()
                    }
                }

                if (!ready) {
                    Column(
                        modifier = Modifier.fillMaxSize().testTag("snapshot_bootstrap"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("최신 검증 데이터 확인 중")
                    }
                } else {
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

                pendingUpdate?.let { prepared ->
                    AlertDialog(
                        onDismissRequest = {
                            if (!prepared.info.mandatory) pendingUpdate = null
                        },
                        title = { Text("앱 업데이트 준비 완료") },
                        text = {
                            Text(
                                "새 버전 ${prepared.info.versionName}을 자동으로 내려받고 검증했습니다.\n\n" +
                                    prepared.info.notes +
                                    "\n\n설치를 누르면 안드로이드의 공식 설치 확인 화면이 열립니다."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    when (val action = runCatching {
                                        AppAutoUpdater.requestInstall(this@MainActivity, prepared)
                                    }.getOrNull()) {
                                        is AppAutoUpdater.InstallAction.PermissionRequired ->
                                            unknownSourcesLauncher.launch(action.intent)
                                        AppAutoUpdater.InstallAction.InstallerOpened -> Unit
                                        null -> Unit
                                    }
                                }
                            ) { Text("설치") }
                        },
                        dismissButton = if (!prepared.info.mandatory) {
                            {
                                TextButton(onClick = { pendingUpdate = null }) {
                                    Text("나중에")
                                }
                            }
                        } else null,
                    )
                }
            }
        }
    }
}
