package com.krstock.v3.data.stage

/**
 * App-side mirror of the validated Stage 4~7 display policy.
 * These helpers never alter the existing KR4 composite rank.
 */
data class ValuationBandView(
    val code: String,
    val labelKo: String,
)

object Stage4567Policy {
    fun valuationBand(perRaw: Double?, m03Percentile: Double?): ValuationBandView? {
        if (perRaw == null || m03Percentile == null || perRaw <= 0.0) return null
        require(m03Percentile in 0.0..100.0) { "M03 percentile out of range: $m03Percentile" }
        return when {
            m03Percentile >= 80.0 -> ValuationBandView("LOW_RELATIVE_PER", "시장 내 낮은 PER 구간")
            m03Percentile >= 60.0 -> ValuationBandView("BELOW_MEDIAN_PER", "시장 중앙값보다 낮은 PER 구간")
            m03Percentile >= 40.0 -> ValuationBandView("MID_PER", "시장 중간 PER 구간")
            m03Percentile >= 20.0 -> ValuationBandView("ABOVE_MEDIAN_PER", "시장 중앙값보다 높은 PER 구간")
            else -> ValuationBandView("HIGH_RELATIVE_PER", "시장 내 높은 PER 구간")
        }
    }

    fun safetyLabelKo(status: String): String = when (status) {
        "PASS" -> "통과"
        "FAIL" -> "탈락"
        "HOLD" -> "검증보류"
        "NOT_APPLICABLE" -> "비교제외"
        else -> "미확인"
    }

    fun safetyReasonKo(reason: String): String = when (reason) {
        "PASS" -> "재무안정성 기준 통과"
        "NONPOSITIVE_EQUITY" -> "자본총계가 0 이하"
        "DEBT_TO_EQUITY_OVER_400" -> "부채/자본 비율 400% 초과"
        "CURRENT_RATIO_UNDER_70" -> "유동비율 70% 미만"
        "ACCOUNTING_IDENTITY_MISMATCH" -> "자산=부채+자본 회계등식 검증 보류"
        "NONPOSITIVE_ASSETS" -> "자산총계가 0 이하"
        "NONPOSITIVE_CURRENT_LIABILITIES" -> "유동부채가 0 이하라 비율 계산 보류"
        "DART_NO_BALANCE_SHEET" -> "OpenDART 재무상태표 확인 불가"
        "FINANCIAL_SECTOR_NOT_COMPARABLE" -> "금융업은 일반 부채비율 기준과 직접 비교하지 않음"
        else -> if (reason.startsWith("MISSING_")) "필수 재무상태표 항목 누락" else reason.ifBlank { "검증 상태 미확인" }
    }
}
