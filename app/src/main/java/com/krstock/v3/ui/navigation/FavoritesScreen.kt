package com.krstock.v3.ui.navigation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater

private const val FAVORITES_PREFERENCES = "kr4_navy_ui_preferences"
private const val FAVORITE_CODES = "favorite_codes"

/** User choices are isolated from the market-data caches and existing preferences. */
@Composable
internal fun NavyFavoritesScreen(onStockClick: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) {
        context.getSharedPreferences(FAVORITES_PREFERENCES, Context.MODE_PRIVATE)
    }
    var favoriteCodes by remember(preferences) { mutableStateOf(preferences.favoriteCodes()) }
    var showAddDialog by remember { mutableStateOf(false) }
    val snapshot by SnapshotAutoUpdater.state.collectAsState()
    val stocks = remember(snapshot.revision) { StockRepository.getAllStocks() }
    val favoriteStocks = remember(stocks, favoriteCodes) {
        stocks.filter { it.issuerId in favoriteCodes }.sortedWith(compareBy({ it.name }, { it.issuerId }))
    }
    val unavailableCodes = remember(stocks, favoriteCodes) {
        favoriteCodes - stocks.map { it.issuerId }.toSet()
    }
    val scheme = MaterialTheme.colorScheme

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == FAVORITE_CODES) favoriteCodes = store.favoriteCodes()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun save(codes: Set<String>) {
        favoriteCodes = codes.toSet()
        preferences.edit().putStringSet(FAVORITE_CODES, codes.toSet()).apply()
    }

    Column(Modifier.fillMaxSize().background(scheme.background).padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("관심종목", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("직접 선택한 ${favoriteCodes.size}종목", fontSize = 11.sp, color = scheme.onSurfaceVariant)
            }
            Button(onClick = { showAddDialog = true }, modifier = Modifier.testTag("favorites_add")) {
                Text("종목 추가", fontSize = 12.sp)
            }
        }
        if (favoriteCodes.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("favorites_empty"),
                color = scheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("아직 관심종목이 없습니다.", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("종목 추가에서 회사명이나 종목코드를 찾아 선택하세요.", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("favorites_list"),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(favoriteStocks, key = { it.issuerId }) { stock ->
                    Surface(
                        color = scheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onStockClick(stock.issuerId) }
                                .testTag("favorite_stock_${stock.issuerId}")
                                .padding(start = 13.dp, top = 10.dp, bottom = 10.dp, end = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stock.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("${stock.issuerId} · ${stock.market}", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                                Text(stock.sector, fontSize = 10.sp, color = scheme.onSurfaceVariant)
                            }
                            IconButton(
                                onClick = { save(favoriteCodes - stock.issuerId) },
                                modifier = Modifier.testTag("favorite_remove_${stock.issuerId}")
                                    .semantics { contentDescription = "${stock.name} 관심종목 삭제" },
                            ) {
                                Text("×", fontSize = 25.sp, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                items(unavailableCodes.sorted(), key = { "unavailable:$it" }) { code ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 13.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(code, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("현재 종목 목록에서 확인되지 않는 저장 코드", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { save(favoriteCodes - code) },
                            modifier = Modifier.testTag("favorite_remove_$code"),
                        ) { Text("삭제") }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFavoritesDialog(
            stocks = stocks,
            initialCodes = favoriteCodes,
            onDismiss = { showAddDialog = false },
            onSave = { codes ->
                save(codes)
                showAddDialog = false
            },
        )
    }
}

private fun SharedPreferences.favoriteCodes(): Set<String> =
    getStringSet(FAVORITE_CODES, emptySet()).orEmpty().toSet()

@Composable
private fun AddFavoritesDialog(
    stocks: List<StockSummary>,
    initialCodes: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCodes by remember { mutableStateOf(initialCodes.toSet()) }
    val filtered = remember(stocks, query) {
        val needle = query.trim()
        stocks.filter {
            needle.isEmpty() || it.name.contains(needle, ignoreCase = true) ||
                it.issuerId.contains(needle, ignoreCase = true)
        }.sortedWith(compareBy({ it.name }, { it.issuerId }))
    }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("관심종목 선택", fontSize = 19.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("회사명 · 종목코드", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().testTag("favorites_search"),
                )
                Text("선택 ${selectedCodes.size}종목 · 검색 결과 ${filtered.size}종목", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 280.dp)) {
                    if (filtered.isEmpty()) {
                        item { Text("검색 결과가 없습니다.", Modifier.padding(vertical = 14.dp), fontSize = 12.sp) }
                    }
                    items(filtered, key = { it.issuerId }) { stock ->
                        val selected = stock.issuerId in selectedCodes
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .toggleable(value = selected, role = Role.Checkbox) {
                                    selectedCodes = if (it) selectedCodes + stock.issuerId else selectedCodes - stock.issuerId
                                }
                                .testTag("favorite_choice_${stock.issuerId}")
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stock.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("${stock.issuerId} · ${stock.market}", fontSize = 10.sp, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedCodes.toSet()) }, modifier = Modifier.testTag("favorites_save")) {
                Text("선택 완료")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
