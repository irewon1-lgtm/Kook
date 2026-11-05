package com.krstock.v3.data.stage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommittedFinancialSnapshotTest {
    @Test fun publishedFinancialFactsParseCompletelyAndPreserveExactSourceAmounts() {
        fun source(name: String): File = listOf(File("evidence",name),File("../evidence",name)).first { it.isFile }
        val quantFile=source("real_quant_snapshot.json")
        val quantBytes=quantFile.readBytes()
        val quant=JSONObject(quantBytes.toString(Charsets.UTF_8))
        val raw=JSONObject(source("financial_safety_snapshot.json").readText())
        val codes=quant.getJSONObject("records").keys().asSequence().toSet()
        assertEquals(2649,codes.size)
        val parsed=FinancialSafetySnapshotParser.parse(raw,quant.getString("snapshot_date_kst"),codes)
        assertEquals(2649,parsed.records.size)
        for ((code,record) in parsed.records) {
            val original=raw.getJSONObject("records").getJSONObject(code)
            val facts=requireNotNull(record.facts)
            assertEquals(original.getString("status"),record.status.name)
            for ((field,amount) in facts.amounts) {
                if(original.isNull(field)) assertNull(amount)
                else assertEquals(original.get(field).toString().toBigDecimal().longValueExact(),amount)
            }
            if(record.status.name in listOf("PASS","FAIL")) {
                assertTrue(facts.verified)
                assertTrue(facts.sourceUrl.startsWith("https://dart.fss.or.kr/"))
            }
        }
        assertArrayEquals("Stage4 parsing must never write quant source data",quantBytes,quantFile.readBytes())
    }
}
