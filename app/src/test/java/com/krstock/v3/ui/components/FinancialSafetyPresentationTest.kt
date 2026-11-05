package com.krstock.v3.ui.components

import com.krstock.v3.data.model.*
import org.junit.Assert.*
import org.junit.Test

class FinancialSafetyPresentationTest {
    @Test fun missingFiguresAreExplicitInsteadOfZerosOrInferredFinancialHealth() {
        val rows=financialSafetyDetailRows(null,"2026-11-05").toMap()
        assertEquals("미확보",rows["자산총계"])
        assertEquals("미확보",rows["유동부채"])
        assertTrue(rows["판정 사유"]!!.contains("기준일"))
    }
    @Test fun rawExactIntegersPeriodFilingCurrencyAndOfficialSourceRemainVisible() {
        val facts=FinancialSafetyFacts(
            mapOf("assets" to 9007199254740993L,"liabilities" to 0L,"equity" to null,"current_assets" to 0L,"current_liabilities" to null),
            "KRW","2026-06-30","2026-08-14","00123456","20260814000001",
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814000001","controlled.zip","a".repeat(64),
            "ORIGINAL","Controlled filing check",false,listOf("MISSING_EQUITY"),
        )
        val safety=FinancialSafetyRecord("000001",FinancialSafetyStatus.HOLD,"MISSING_EQUITY","CFS",null,null,null,"controlled",facts)
        val rows=financialSafetyDetailRows(safety,"2026-11-05").toMap()
        assertEquals("9,007,199,254,740,993원",rows["자산총계"])
        assertEquals("0원",rows["부채총계"])
        assertEquals("미확보",rows["자본총계"])
        assertEquals("2026-06-30",rows["보고기간"])
        assertEquals("2026-08-14",rows["실제 공시일"])
        assertEquals(facts.sourceUrl,rows["공식 공시 출처"])
        assertTrue(rows["자료 검증"]!!.contains("검증 대기"))
        val mismatch=safety.copy(facts=facts.copy(correctionStatus="LATEST_CORRECTION",reconciliationError="RECEIPT_MISMATCH"))
        val correction=financialSafetyDetailRows(mismatch,"2026-11-05").toMap()["정정 확인"]!!
        assertTrue(correction.contains("접수번호"))
        assertFalse(correction.contains("대조 전"))
    }
}
