package com.krstock.v3.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.repository.StockRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockListScreen(
    onStockClick: (String) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val allStocks = remember { StockRepository.getAllStocks() }

    val filteredStocks = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            allStocks
        } else {
            allStocks.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.issuerId.contains(searchQuery)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("전체 국내주식 종목", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("< 뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("종목명 또는 종목코드 검색") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredStocks) { stock ->
                    StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
                }
            }
        }
    }
}
