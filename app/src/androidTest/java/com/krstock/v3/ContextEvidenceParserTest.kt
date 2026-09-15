package com.krstock.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.data.evidence.ContextEvidenceRepository
import com.krstock.v3.data.model.EvidenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextEvidenceParserTest {

    @Test
    fun nestedNewsJsonExtractsOnlyEvidenceLikeObjects() {
        val json = """
            {
              "menu": {"title":"관련 뉴스"},
              "result": {
                "items": [
                  {
                    "articleTitle":"대형 고객사 공급계약 체결",
                    "datetime":"2026-09-15T08:10:00",
                    "officeName":"테스트경제",
                    "oid":"001",
                    "aid":"12345",
                    "url":"/news/12345"
                  },
                  {
                    "title":"신규 공장 증설 투자 결정",
                    "publishDate":"20260914",
                    "pressName":"테스트뉴스",
                    "articleId":"abc"
                  }
                ]
              }
            }
        """.trimIndent()

        val rows = ContextEvidenceRepository.parse(json, EvidenceKind.NEWS)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.title.contains("공급계약") && it.source == "테스트경제" })
        assertTrue(rows.any { it.title.contains("증설") && it.publishedAt.startsWith("2026-09-14") })
        assertTrue(rows.none { it.title == "관련 뉴스" })
    }

    @Test
    fun disclosureJsonHandlesDartStyleKeysAndDeduplicatesTitles() {
        val json = """
            {
              "data": [
                {
                  "reportName":"단일판매ㆍ공급계약체결",
                  "rceptDt":"20260915",
                  "corpName":"테스트기업",
                  "rceptNo":"20260915000123"
                },
                {
                  "reportName":"단일판매ㆍ공급계약체결",
                  "rceptDt":"20260915",
                  "corpName":"테스트기업",
                  "rceptNo":"20260915000123"
                },
                {
                  "disclosureTitle":"유상증자 결정",
                  "disclosureDate":"2026-09-14",
                  "officeName":"공시",
                  "detailUrl":"/disclosure/2"
                }
              ]
            }
        """.trimIndent()

        val rows = ContextEvidenceRepository.parse(json, EvidenceKind.DISCLOSURE)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.title.contains("공급계약") })
        assertTrue(rows.any { it.title.contains("유상증자") })
        assertTrue(rows.all { it.kind == EvidenceKind.DISCLOSURE })
    }
}
