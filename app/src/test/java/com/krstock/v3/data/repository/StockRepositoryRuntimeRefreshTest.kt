package com.krstock.v3.data.repository

import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.model.RealQuantRecord
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StockRepositoryRuntimeRefreshTest {
    @Test
    fun sameDateCorrectionUpdatesRowsRanksSearchAndDetails() = withRestoredSnapshot { original ->
        val before = StockRepository.getAllStocks()
        val first = original.single { it.rankOrder == 1 }
        val second = original.single { it.rankOrder == 2 }
        val corrected = original.map { row ->
            when (row.code) {
                first.code -> second.copy(code = first.code)
                second.code -> first.copy(code = second.code)
                else -> row
            }
        }

        install(corrected)

        val refreshed = StockRepository.getAllStocks()
        assertNotSame(before, refreshed)
        assertSame(refreshed, StockRepository.getAllStocks())
        assertEquals(2, refreshed.single { it.issuerId == first.code }.rankOrder)
        assertEquals(1, refreshed.single { it.issuerId == second.code }.rankOrder)
        assertEquals(second.m04Raw, StockRepository.getStockDetail(first.code)!!.summary.m04Price6m.rawValue)
        assertEquals(2, StockRepository.searchStocks(first.code).single().rankOrder)
    }

    @Test
    fun newerSnapshotUpdatesDatesAndCompleteCountsInAlreadyUsedRepository() = withRestoredSnapshot { original ->
        val before = StockRepository.getAllStocks()
        val previousComplete = StockRepository.completeCount()
        val removed = original.single { it.rankOrder == 1 }
        val nextDate = LocalDate.parse(GeneratedRealQuantSnapshot.snapshotDate).plusDays(1).toString()
        val updated = original.map { row ->
            if (row.code == removed.code) {
                row.copy(
                    m04Raw = null, m04Percentile = null, m04ReasonCode = "NAVER_PRICE_MISSING",
                    m04Basis = "", compositeScore = null, rankOrder = null,
                )
            } else row.copy(rankOrder = row.rankOrder?.minus(1))
        }

        install(updated, nextDate)

        assertNotSame(before, StockRepository.getAllStocks())
        assertEquals(previousComplete - 1, StockRepository.completeCount())
        assertEquals(nextDate, StockRepository.quantSnapshotDate())
        assertEquals(nextDate, StockRepository.priceCutoffDate())
        val detail = StockRepository.getStockDetail(removed.code)!!.summary
        assertEquals(nextDate, detail.asOfDate)
        assertEquals(nextDate, detail.m04Price6m.asOfDate)
        assertFalse(detail.isCompositeComplete)
        assertNull(detail.rankOrder)
    }

    private fun withRestoredSnapshot(block: (List<RealQuantRecord>) -> Unit) {
        val rows = GeneratedRealQuantSnapshot.rows
        val snapshotDate = GeneratedRealQuantSnapshot.snapshotDate
        val priceDate = GeneratedRealQuantSnapshot.priceCutoffDate
        val dartFile = GeneratedRealQuantSnapshot.dartFileName
        try {
            block(rows)
        } finally {
            install(rows, snapshotDate, priceDate, dartFile)
        }
    }

    private fun install(
        rows: List<RealQuantRecord>,
        snapshotDate: String = GeneratedRealQuantSnapshot.snapshotDate,
        priceDate: String = snapshotDate,
        dartFile: String = GeneratedRealQuantSnapshot.dartFileName,
    ) = GeneratedRealQuantSnapshot.installRuntimeSnapshot(
        snapshotDate, priceDate, dartFile, rows,
        rows.count { it.m01Raw != null }, rows.count { it.m02Raw != null },
        rows.count { it.m03Raw != null }, rows.count { it.m04Raw != null },
        rows.count { it.compositeScore != null },
    )
}
