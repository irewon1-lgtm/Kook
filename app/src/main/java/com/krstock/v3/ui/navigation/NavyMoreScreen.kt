package com.krstock.v3.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.BuildConfig
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NavyMoreScreen() {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status by SnapshotAutoUpdater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val actualDates = remember(status.revision) {
        StockRepository.quantSnapshotDate() to StockRepository.priceCutoffDate()
    }
    val matches = actualDates.first == status.snapshotDate && actualDates.second == status.priceCutoffDate

    Column(
        Modifier.fillMaxSize().background(scheme.background)
            .verticalScroll(rememberScrollState()).padding(18.dp).testTag("more_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("더보기", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        MorePanel("앱 정보") {
            Text("KR4 국내주식", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                Modifier.testTag("more_version"), fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        MorePanel("데이터 갱신") {
            Text(if (matches) "데이터 출처 ${status.source.name}" else "데이터 출처 확인 중",
                Modifier.testTag("more_source"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("재무 ${actualDates.first} · 주가 ${actualDates.second}",
                Modifier.testTag("more_snapshot_dates"), fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Text("최근 갱신 ${if (matches) displayTimestamp(status.refreshedAt) else "확인 중"}",
                Modifier.testTag("more_last_refresh"), fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Text("최근 확인 ${displayTimestamp(status.lastAttemptAt)}",
                fontSize = 12.sp, color = scheme.onSurfaceVariant)
            if (matches) Text(status.message, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            if (status.failure != null || refreshError != null) {
                Text(refreshError ?: "갱신 실패 · 마지막 정상 자료를 표시합니다.",
                    Modifier.testTag("more_refresh_error"), fontSize = 12.sp, color = scheme.error)
            }
            Button(
                onClick = {
                    refreshing = true
                    refreshError = null
                    scope.launch {
                        try {
                            SnapshotAutoUpdater.backgroundRefresh(context)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            refreshError = "갱신을 완료하지 못했습니다. 마지막 정상 자료를 표시합니다."
                        } finally {
                            refreshing = false
                        }
                    }
                },
                enabled = !refreshing,
                modifier = Modifier.fillMaxWidth().testTag("more_refresh"),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (refreshing) "데이터 갱신 중…" else "데이터 새로고침")
            }
        }
        MorePanel("원천 자료") {
            Text("종목 정보: KRX KIND 상장법인목록", fontSize = 12.sp)
            Text("재무: 금융감독원 OpenDART", fontSize = 12.sp)
            Text("가격·실제 EPS: 네이버증권", fontSize = 12.sp)
            Text("결측 지표는 추정하지 않으며, 순위는 현재 확보한 실제 지표를 기준으로 산출합니다.",
                fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MorePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(), color = scheme.surface,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private fun displayTimestamp(value: String): String = if (value.isBlank()) "기록 없음" else runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrElse { value }
