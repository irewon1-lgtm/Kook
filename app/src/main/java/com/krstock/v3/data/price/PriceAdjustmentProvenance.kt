package com.krstock.v3.data.price

/**
 * A provider's OHLC fields do not establish an official adjustment basis.
 * There is deliberately no verified/adjusted state until both the complete
 * official event factors and the provider's existing OHLC basis can be proved.
 */
data class PriceAdjustmentProvenance(
    val priceSourceUrl: String = "https://m.stock.naver.com/api/stock/{code}/price",
) {
    enum class Status { UNVERIFIED }

    val status: Status get() = Status.UNVERIFIED
    val label: String get() = "수정주가 미확인"
    val reason: String get() = "공식 기업행사별 적용일·조정계수와 제공 OHLC의 기수정 여부를 확인하지 못했습니다."
    val treatment: String get() = "제공 원본 OHLC 유지 · 추가 조정 없음"
    val sourceDetail: String get() = "가격: 네이버증권 · 공식 수정계수: 미확보"
}
