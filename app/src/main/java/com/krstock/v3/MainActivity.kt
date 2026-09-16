package com.krstock.v3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.krstock.v3.data.candidate.FinalCandidateSnapshotUpdater
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.screens.HomeScreen
import com.krstock.v3.ui.screens.Stage4567Screen
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
                    // Stage 4~7 final candidates are a separate fail-closed remote artifact.
                    // They are loaded only after the active quant snapshot is known.
                    runCatching { FinalCandidateSnapshotUpdater.bootstrap(this@MainActivity) }
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

                Surface(modifier = Modifier.fillMaxSize()) {
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
                        NavHost(
                            navController = navController,
                            startDestination = "main",
                            modifier = Modifier.fillMaxSize(),
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None }
                        ) {
                            composable("main") {
                                val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
                                val scope = rememberCoroutineScope()
                                val labels = listOf("홈", "조합순위", "안정·가치")

                                BackHandler(enabled = pagerState.currentPage != 0) {
                                    scope.launch { pagerState.scrollToPage(0) }
                                }

                                Column(modifier = Modifier.fillMaxSize()) {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .testTag("main_pager")
                                    ) { page ->
                                        when (page) {
                                            0 -> HomeScreen(
                                                onStockClick = { issuerId -> navController.navigate("detail/$issuerId") },
                                                onNavigateToList = { scope.launch { pagerState.animateScrollToPage(1) } }
                                            )
                                            1 -> StockListScreen(
                                                onStockClick = { issuerId -> navController.navigate("detail/$issuerId") },
                                                onBack = { scope.launch { pagerState.scrollToPage(0) } }
                                            )
                                            else -> Stage4567Screen(
                                                onStockClick = { issuerId -> navController.navigate("detail/$issuerId") }
                                            )
                                        }
                                    }

                                    NavigationBar(modifier = Modifier.fillMaxWidth().testTag("main_bottom_nav")) {
                                        labels.forEachIndexed { index, label ->
                                            NavigationBarItem(
                                                selected = pagerState.currentPage == index,
                                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                                icon = {},
                                                label = { Text(label) },
                                                alwaysShowLabel = true,
                                            )
                                        }
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
