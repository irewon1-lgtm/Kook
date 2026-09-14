package com.krstock.v3.data.repository

import com.krstock.v3.data.model.*

object StockRepository {

    private val mockStocks = listOf(
        StockSummary(
            issuerId = "005930",
            name = "삼성전자",
            market = "KOSPI",
            m01RevGrowth = MetricValue("M01", "매출 증가율", 12.8, 85.0, "%", true),
            m02OpMargin = MetricValue("M02", "영업이익률", 14.5, 82.0, "%", true),
            m03Per = MetricValue("M03", "실적 기준 PER", 13.2, 78.0, "배", true),
            m04Price6m = MetricValue("M04", "최근 6개월 주가 상승률", 8.4, 70.0, "%", true),
            compositeScore = 78.75,
            rankOrder = 1,
            isCompositeComplete = true,
            asOfDate = "2026-09-13",
            dataSource = "OpenDART / KRX 공식 실적",
            status = DataStatus.REAL
        ),
        StockSummary(
            issuerId = "000660",
            name = "SK하이닉스",
            market = "KOSPI",
            m01RevGrowth = MetricValue("M01", "매출 증가율", 24.2, 95.0, "%", true),
            m02OpMargin = MetricValue("M02", "영업이익률", 18.0, 91.0, "%", true),
            m03Per = MetricValue("M03", "실적 기준 PER", 10.5, 88.0, "배", true),
            m04Price6m = MetricValue("M04", "최근 6개월 주가 상승률", 15.2, 85.0, "%", true),
            compositeScore = 89.75,
            rankOrder = 2,
            isCompositeComplete = true,
            asOfDate = "2026-09-13",
            dataSource = "OpenDART / KRX 공식 실적",
            status = DataStatus.REAL
        ),
        StockSummary(
            issuerId = "035420",
            name = "NAVER",
            market = "KOSPI",
            m01RevGrowth = MetricValue("M01", "매출 증가율", 8.5, 60.0, "%", true),
            m02OpMargin = MetricValue("M02", "영업이익률", 11.2, 65.0, "%", true),
            m03Per = MetricValue("M03", "실적 기준 PER", 22.0, 45.0, "배", true),
            m04Price6m = MetricValue("M04", "최근 6개월 주가 상승률", -2.1, 35.0, "%", true),
            compositeScore = 51.25,
            rankOrder = 3,
            isCompositeComplete = true,
            asOfDate = "2026-09-13",
            dataSource = "OpenDART / KRX 공식 실적",
            status = DataStatus.DEMO
        ),
        StockSummary(
            issuerId = "055550",
            name = "신한지주",
            market = "KOSPI",
            isFinancial = true,
            m01RevGrowth = MetricValue("M01", "매출 증가율", 4.2, 40.0, "%", true),
            m02OpMargin = MetricValue("M02", "영업이익률", null, null, "%", false, "금융업 영업이익률 미적용"),
            m03Per = MetricValue("M03", "실적 기준 PER", null, null, "배", false, "금융사 별도 코호트 처리 (N/A)"),
            m04Price6m = MetricValue("M04", "최근 6개월 주가 상승률", 5.1, 55.0, "%", true),
            compositeScore = null,
            rankOrder = null,
            isCompositeComplete = false,
            asOfDate = "2026-09-13",
            dataSource = "OpenDART / KIS API",
            status = DataStatus.WAITING_FOR_AUTH
        ),
        StockSummary(
            issuerId = "999999",
            name = "테스트적자기업",
            market = "KOSDAQ",
            isLossMaking = true,
            m01RevGrowth = MetricValue("M01", "매출 증가율", -15.0, 15.0, "%", true),
            m02OpMargin = MetricValue("M02", "영업이익률", -8.4, 10.0, "%", true),
            m03Per = MetricValue("M03", "실적 기준 PER", null, null, "배", false, "적자기업 PER 계산 불가 (N/A)"),
            m04Price6m = MetricValue("M04", "최근 6개월 주가 상승률", -25.0, 12.0, "%", true),
            compositeScore = null,
            rankOrder = null,
            isCompositeComplete = false,
            asOfDate = "2026-09-13",
            dataSource = "OpenDART",
            status = DataStatus.DEMO
        )
    )

    fun getAllStocks(): List<StockSummary> = mockStocks

    fun getStockDetail(issuerId: String): StockDetail? {
        val summary = mockStocks.find { it.issuerId == issuerId } ?: mockStocks.first()
        val report = CompanyReport(
            issuerId = summary.issuerId,
            recentChanges = "${summary.name}의 최근 3개월 분기 실적은 매출 및 영업이익 측면에서 안정적인 흐름을 보였습니다.",
            changeReason = "전방 산업 수요 회복 및 고부가가치 제품 비중 확대에 따른 수익성 개선.",
            positiveFactors = listOf("핵심 사업 부문 매출 지속 성장", "주요 원재료 수급 안정화에 따른 마진 방어"),
            riskFactors = listOf("글로벌 매크로 불확실성 지속", "환율 변동성 확대에 따른 외환 손익 영향"),
            counterArguments = "단기 실적 반등에도 불구하고 글로벌 전방 시장의 회복 속도가 지연될 가능성이 존재함.",
            nextVerificationConditions = listOf("다음 분기 영업이익률 15% 이상 유지 여부", "주요 고객사 장기 공급 계약 체결 공시"),
            updatedAt = "2026-09-13 14:00:00 (UTC)"
        )

        val news = listOf(
            NewsItem("N1", "${summary.name}, 차세대 기술 개발 발표", "한국경제", "2026-09-12", "https://news.example.com/1"),
            NewsItem("N2", "주요 전방 시장 수요 증가 전망", "매일경제", "2026-09-11", "https://news.example.com/2"),
            NewsItem("N3", "기관 및 외국인 순매수 지속", "연합인포맥스", "2026-09-10", "https://news.example.com/3")
        )

        val filings = listOf(
            FilingItem("F1", "반기보고서 (2026.06)", "2026-08-14", true, "https://dart.fss.or.kr/1"),
            FilingItem("F2", "주요사항보고서(단기차입금증액결정)", "2026-07-20", true, "https://dart.fss.or.kr/2")
        )

        return StockDetail(summary, report, news, filings)
    }
}
