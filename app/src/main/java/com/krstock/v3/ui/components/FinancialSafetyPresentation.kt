package com.krstock.v3.ui.components

import com.krstock.v3.data.model.FinancialSafetyRecord
import java.util.Locale

/** Shared by the clickable badge and detail card; exact source integers only. */
internal fun financialSafetyDetailRows(safety: FinancialSafetyRecord?, snapshotDate: String): List<Pair<String, String>> {
    val facts = safety?.facts
    val rows = mutableListOf(
        "판정" to (safety?.status?.let(::financialSafetyStatusKo) ?: "HOLD · 미확보"),
        "판정 사유" to (safety?.reasonCode?.let(::financialSafetyReasonKo)
            ?: "기준일이 일치하는 검증된 재무자료가 없습니다"),
        "자료 기준일" to snapshotDate,
        "보고기간" to facts?.reportPeriod.orEmpty().ifBlank { "미확보" },
        "실제 공시일" to facts?.filingDate.orEmpty().ifBlank { "미확보" },
        "연결·별도" to when (safety?.scope) { "CFS" -> "연결 (CFS)"; "OFS" -> "별도 (OFS)"; else -> "미확보" },
        "자료 검증" to if (facts?.verified == true) "공식 원문·공시 검증 완료" else "원문 수치 · 검증 대기",
    )
    for ((key, label) in listOf(
        "assets" to "자산총계", "liabilities" to "부채총계", "equity" to "자본총계",
        "current_assets" to "유동자산", "current_liabilities" to "유동부채",
    )) {
        val amount = facts?.amounts?.get(key)
        val unit = if (facts?.currency == "KRW") "원" else " " + facts?.currency.orEmpty()
        rows += label to (amount?.let { String.format(Locale.KOREA, "%,d", it) + unit } ?: "미확보")
    }
    rows += "통화·단위" to facts?.currency.orEmpty().ifBlank { "미확보" }
    rows += "부채비율" to (safety?.debtToEquityPct?.let { String.format(Locale.KOREA, "%.2f%%", it) } ?: "미확보")
    rows += "유동비율" to (safety?.currentRatioPct?.let { String.format(Locale.KOREA, "%.2f%%", it) } ?: "미확보")
    rows += "회계 일치 오차" to (safety?.accountingIdentityGapPct?.let { String.format(Locale.KOREA, "%.3f%%", it) } ?: "미확보")
    rows += "기존 판정 기준" to "자본 > 0 · 부채비율 ≤ 400% · 유동비율 ≥ 70% · 회계 오차 ≤ 5%"
    rows += "법인번호" to facts?.corporationCode.orEmpty().ifBlank { "미확보" }
    rows += "공시 접수번호" to facts?.receiptNo.orEmpty().ifBlank { "미확보" }
    rows += "정정 확인" to when (facts?.correctionStatus) {
        "ORIGINAL" -> "최종보고서 조회 · 원공시 확인"
        "LATEST_CORRECTION" -> "정정공시 발견 · 원문 수치 대조 전 보류"
        else -> "미확보"
    }
    if (!facts?.validationIssues.isNullOrEmpty()) rows += "검증 보류 항목" to facts!!.validationIssues.joinToString("\n") { financialSafetyReasonKo(it) }
    rows += "공식 공시 출처" to facts?.sourceUrl.orEmpty().ifBlank { "미확보" }
    rows += "OpenDART 원본" to facts?.sourceFile.orEmpty().ifBlank { safety?.basis.orEmpty().ifBlank { "미확보" } }
    rows += "원본 ZIP SHA-256" to facts?.sourceZipSha256.orEmpty().ifBlank { "미확보" }
    return rows
}
