package com.krstock.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.history.QuarterlyHistoryRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuarterlyHistoryParserTest {
    @Test
    fun parserPreservesNullsAndPeriodOrder() {
        val root = JSONObject(
            """
            {
              "schema_version":1,
              "generated_at_kst":"2026-09-15T20:00:00+09:00",
              "records":{
                "005930":{
                  "points":[
                    {"period":"2026Q2","fiscal_year":2026,"quarter":2,"revenue":200000000000.0,"operating_income":25000000000.0,"operating_margin":12.5,"revenue_yoy":10.1,"revenue_qoq":3.2,"scope":"CFS","basis":"DIRECT_3M","source_file":"hy.zip","reason":null},
                    {"period":"2026Q1","fiscal_year":2026,"quarter":1,"revenue":null,"operating_income":null,"operating_margin":null,"revenue_yoy":null,"revenue_qoq":null,"scope":"CFS","basis":"","source_file":"q1.zip","reason":"DART_NO_REVENUE"}
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val history = QuarterlyHistoryRepository.parseIssuer(root, "005930", "test")
        assertTrue(history.loaded)
        assertEquals(2, history.points.size)
        assertEquals("2026Q1", history.points.first().period)
        assertNull(history.points.first().revenue)
        assertEquals("DART_NO_REVENUE", history.points.first().reason)
        assertEquals("2026Q2", history.points.last().period)
        assertEquals(12.5, history.points.last().operatingMargin!!, 0.0001)
    }
}
