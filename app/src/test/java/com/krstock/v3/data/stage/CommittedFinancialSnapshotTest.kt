package com.krstock.v3.data.stage

import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.ranking.DynamicRankingEngine
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.update.SnapshotAutoUpdater
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class CommittedFinancialSnapshotTest {
    private fun source(name: String): File = listOf(File("evidence",name),File("../evidence",name)).first { it.isFile }

    @Test fun publishedFinancialFactsParseCompletelyAndPreserveExactSourceAmounts() {
        val quantFile=source("real_quant_snapshot.json")
        val quantBytes=quantFile.readBytes()
        val quant=JSONObject(quantBytes.toString(Charsets.UTF_8))
        val financialFile=source("financial_safety_snapshot.json")
        val financialBytes=financialFile.readBytes()
        val raw=JSONObject(financialBytes.toString(Charsets.UTF_8))
        val codes=quant.getJSONObject("records").keys().asSequence().toSet()
        assertEquals(2649,codes.size)
        assertEquals(2649,SnapshotAutoUpdater.parseAndValidate(quantBytes.toString(Charsets.UTF_8)).rows.size)
        // Independent financial validity is always checked, even while the
        // quant publisher has advanced ahead of the financial collection job.
        val financialCodes=raw.getJSONObject("records").keys().asSequence().toSet()
        val financialDate=raw.getString("snapshot_date_kst")
        assertEquals(2649,financialCodes.size)
        val parsed=FinancialSafetySnapshotParser.parse(raw,financialDate,financialCodes)
        assertEquals(2649,parsed.records.size)
        var checkedAmounts=0
        for ((code,record) in parsed.records) {
            val original=raw.getJSONObject("records").getJSONObject(code)
            val facts=requireNotNull(record.facts)
            assertEquals(original.getString("status"),record.status.name)
            for ((field,amount) in facts.amounts) {
                if(original.isNull(field)) assertNull(amount)
                else assertEquals(original.get(field).toString().toBigDecimal().longValueExact(),amount)
                checkedAmounts++
            }
            if(record.status.name in listOf("PASS","FAIL")) {
                assertTrue(facts.verified)
                assertTrue(facts.sourceUrl.startsWith("https://dart.fss.or.kr/"))
            }
        }
        assertEquals("Every original nullable financial amount must be checked",2649*5,checkedAmounts)
        // Also check the real current join. A valid older artifact must be
        // rejected for the active quant date/identity set, never silently used.
        if (financialDate==quant.getString("snapshot_date_kst") && financialCodes==codes) {
            assertEquals(parsed,FinancialSafetySnapshotParser.parse(raw,quant.getString("snapshot_date_kst"),codes))
        } else {
            assertThrows(IllegalArgumentException::class.java) {
                FinancialSafetySnapshotParser.parse(raw,quant.getString("snapshot_date_kst"),codes)
            }
        }
        assertArrayEquals("Stage4 parsing must never write quant source data",quantBytes,quantFile.readBytes())
        assertArrayEquals("Financial validation must preserve the original source bytes",financialBytes,financialFile.readBytes())
    }

    @Test fun advancedQuantHoldsEarlierFinancialFactsWithoutChangingAnyMetricOrCombinationRank() {
        val quantFile=source("real_quant_snapshot.json")
        val financialFile=source("financial_safety_snapshot.json")
        val quantBytes=quantFile.readBytes()
        val financialBytes=financialFile.readBytes()
        val raw=JSONObject(financialBytes.toString(Charsets.UTF_8))
        val financialDate=raw.getString("snapshot_date_kst")
        val financialCodes=raw.getJSONObject("records").keys().asSequence().toSet()
        val financial=FinancialSafetySnapshotParser.parse(raw,financialDate,financialCodes)
        val originalQuant=SnapshotAutoUpdater.parseAndValidate(quantBytes.toString(Charsets.UTF_8))
        // CONTROLLED DATE METADATA ONLY: preserve every real price, metric,
        // percentile and rank; this is not a newly collected market snapshot.
        val advancedDate=maxOf(originalQuant.snapshotDate,LocalDate.parse(financialDate).plusDays(1).toString())
        val advanced=SnapshotAutoUpdater.parseAndValidate(JSONObject(quantBytes.toString(Charsets.UTF_8))
            .put("snapshot_date_kst",advancedDate).toString())
        assertEquals(originalQuant.rows,advanced.rows)
        val codes=advanced.rows.map { it.code }.toSet()
        assertEquals(2649,codes.size)
        val saved=SnapshotAutoUpdater.ParsedSnapshot(
            GeneratedRealQuantSnapshot.snapshotDate,GeneratedRealQuantSnapshot.priceCutoffDate,
            GeneratedRealQuantSnapshot.dartFileName,GeneratedRealQuantSnapshot.rows.toList(),
            GeneratedRealQuantSnapshot.m01Available,GeneratedRealQuantSnapshot.m02Available,
            GeneratedRealQuantSnapshot.m03Available,GeneratedRealQuantSnapshot.m04Available,
            GeneratedRealQuantSnapshot.completeCount,
        )
        val savedFinancial=FinancialSafetyRuntimeStore.current(saved.snapshotDate)
        val metrics=listOf("M01","M02","M03","M04")
        val masks=(1..15).map { mask -> metrics.filterIndexed { i,_ -> mask and (1 shl i)!=0 }.toSet() }
        try {
            install(advanced)
            FinancialSafetyRuntimeStore.clear()
            val stocks=StockRepository.getAllStocks().toList()
            val ranks=masks.map { DynamicRankingEngine.rank(stocks,it) }
            FinancialSafetyRuntimeStore.install(financial)
            assertThrows(IllegalArgumentException::class.java) {
                FinancialSafetySnapshotParser.parse(raw,advancedDate,codes)
            }
            assertNull("Earlier financial data must remain HOLD for advanced quant",FinancialSafetyRuntimeStore.current(advancedDate))
            assertTrue(FinancialSafetyRepository.getAll().isEmpty())
            assertEquals(stocks,StockRepository.getAllStocks())
            masks.forEachIndexed { i,selected -> assertEquals(ranks[i],DynamicRankingEngine.rank(StockRepository.getAllStocks(),selected)) }
            assertEquals(originalQuant,SnapshotAutoUpdater.parseAndValidate(quantBytes.toString(Charsets.UTF_8)))
        } finally {
            install(saved)
            FinancialSafetyRuntimeStore.clear()
            savedFinancial?.let(FinancialSafetyRuntimeStore::install)
        }
        assertArrayEquals(quantBytes,quantFile.readBytes())
        assertArrayEquals(financialBytes,financialFile.readBytes())
    }

    private fun install(snapshot: SnapshotAutoUpdater.ParsedSnapshot) {
        GeneratedRealQuantSnapshot.installRuntimeSnapshot(
            snapshot.snapshotDate,snapshot.priceCutoffDate,snapshot.dartFileName,snapshot.rows,
            snapshot.m01Available,snapshot.m02Available,snapshot.m03Available,snapshot.m04Available,snapshot.completeCount,
        )
    }
}
