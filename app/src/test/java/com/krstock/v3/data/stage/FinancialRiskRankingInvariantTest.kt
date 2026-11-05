package com.krstock.v3.data.stage

import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetySnapshot
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.ranking.DynamicRankingEngine
import org.junit.Assert.*
import org.junit.Test

class FinancialRiskRankingInvariantTest {
    @Test fun changingOnlyFinancialRiskPreservesAll2649MetricsAndEveryCombinationRank() {
        val stocks = StockRepository.getAllStocks().toList()
        assertEquals(2649, stocks.size)
        val date = StockRepository.quantSnapshotDate()
        val saved = FinancialSafetyRuntimeStore.current(date)
        val metrics = listOf("M01", "M02", "M03", "M04")
        val combinations = (1..15).map { mask -> metrics.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() }
        val before = combinations.map { DynamicRankingEngine.rank(stocks, it) }
        try {
            for (status in FinancialSafetyStatus.entries) {
                val records = stocks.associate { stock -> stock.issuerId to FinancialSafetyRecord(
                    stock.issuerId, status, "CONTROLLED_RISK_ONLY_CHANGE", "", null, null, null,
                    "Controlled state transition, original market metrics remain untouched",
                ) }
                FinancialSafetyRuntimeStore.install(FinancialSafetySnapshot(
                    FinancialSafetySnapshotParser.POLICY_VERSION, date, "Controlled risk state", "controlled.json",
                    if(status==FinancialSafetyStatus.PASS)2649 else 0,
                    if(status==FinancialSafetyStatus.FAIL)2649 else 0,
                    if(status==FinancialSafetyStatus.HOLD)2649 else 0,
                    if(status==FinancialSafetyStatus.NOT_APPLICABLE)2649 else 0, records,
                ))
                val after = StockRepository.getAllStocks()
                assertEquals(stocks, after)
                combinations.forEachIndexed { i, selected ->
                    assertEquals("$status $selected changed score, eligibility or rank",before[i],DynamicRankingEngine.rank(after,selected))
                }
                assertEquals(2649, after.size)
            }
        } finally {
            FinancialSafetyRuntimeStore.clear()
            saved?.let(FinancialSafetyRuntimeStore::install)
        }
    }
}
