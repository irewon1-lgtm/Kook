package com.krstock.v3

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.theme.KRStockV3Theme
import com.krstock.v3.ui.navigation.NavyAppNavigation
import com.krstock.v3.update.AppAutoUpdater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF061321.toInt()
        window.navigationBarColor = 0xFF061321.toInt()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
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
                    // Render the current known-good snapshot while its updater
                    // refreshes quant, safety and candidates as one pipeline.
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

                Surface(modifier = Modifier.fillMaxSize()) {
                    NavyAppNavigation()
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
