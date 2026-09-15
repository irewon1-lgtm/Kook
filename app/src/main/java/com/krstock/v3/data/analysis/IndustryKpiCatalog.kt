package com.krstock.v3.data.analysis

data class IndustryKpi(
    val name: String,
    val meaning: String,
    val expectedLink: String
)

data class IndustryKpiProfile(
    val label: String,
    val kpis: List<IndustryKpi>,
    val causalChecks: List<String>,
    val nextQuarterChain: List<String>
)

/**
 * The four KR4 metrics are anomaly sensors. This catalog contains the second-stage
 * operating sensors used to explain *why* the anomaly exists. It deliberately
 * avoids pretending that one generic KPI set works for every industry.
 */
object IndustryKpiCatalog {
    fun profileFor(rawSector: String): IndustryKpiProfile {
        val broad = IndustryInsightCatalog.profileFor(rawSector).label
        return when (broad) {
            "반도체·전자부품" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("출하량", "실제 물량 수요가 늘었는지", "출하량 증가가 매출 증가의 물량 원인인지 확인"),
                    IndustryKpi("ASP/판가", "같은 물량에서 가격이 얼마나 개선됐는지", "ASP 상승이면 매출과 마진이 함께 좋아질 가능성"),
                    IndustryKpi("가동률", "고정비를 생산량이 얼마나 흡수하는지", "가동률 상승 뒤 영업이익률 회복이 따라와야 함"),
                    IndustryKpi("수율", "신규 라인·공정의 생산 효율", "초기 수율 저하가 선투자형 마진 압박인지 판별"),
                    IndustryKpi("고부가 제품 믹스", "HBM·고사양·고마진 제품 비중", "믹스 개선이면 판가와 마진 개선이 동시에 나타나야 함")
                ),
                listOf("고객 재고 정상화 여부", "신규 소켓/고객 채택", "CAPEX 증가가 실제 생산능력 확대로 연결되는지"),
                listOf("가동률", "제품 믹스", "영업이익률", "수주·고객 매출 전환")
            )

            "소프트웨어·IT서비스" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("고객수", "성장이 신규 고객 유입에서 나오는지", "고객수 증가가 반복매출 증가로 이어지는지 확인"),
                    IndustryKpi("ARPU/객단가", "기존 고객당 매출 확대", "가격 인상·업셀링이 마진 개선까지 만드는지 확인"),
                    IndustryKpi("Retention/해지율", "매출의 반복 가능성", "성장률이 높아도 해지율 악화면 성장의 질을 낮게 봄"),
                    IndustryKpi("신규계약/수주잔고", "향후 매출의 선행지표", "계약 증가 후 매출 인식 속도를 확인"),
                    IndustryKpi("CAC/영업비", "성장을 위해 지불하는 획득비용", "고객 획득비가 과도하면 매출 성장과 마진 하락이 구조적일 수 있음")
                ),
                listOf("구독·클라우드 반복매출 비중", "대형 프로젝트 일회성 매출 여부", "인력 증가율과 매출 증가율의 차이"),
                listOf("신규계약", "고객수·ARPU", "반복매출", "영업이익률")
            )

            "제약·바이오" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("처방/판매량", "기존 제품의 실제 수요", "판매량 증가가 반복매출 성장인지 확인"),
                    IndustryKpi("신제품 매출 비중", "새 성장축의 실적 기여", "출시 후 매출 기여가 확대되는지 확인"),
                    IndustryKpi("R&D 비율", "현재 이익을 미래 파이프라인에 얼마나 재투자하는지", "마진 하락이 연구개발 선투자 때문인지 판별"),
                    IndustryKpi("임상·허가 마일스톤", "파이프라인의 가치 전환 단계", "허가·임상 진전이 실제 계약·매출로 이어지는지 확인"),
                    IndustryKpi("기술료/마일스톤 비중", "일회성 이익 여부", "일회성 매출을 반복 성장으로 오인하지 않도록 분리")
                ),
                listOf("특허 만료·약가 변화", "기술이전 계약의 조건과 현금 유입", "임상비 증가와 현금소진 속도"),
                listOf("임상·허가", "출시/기술이전", "매출 기여", "R&D 부담 대비 이익률")
            )

            "의료기기·헬스케어" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("설치대수", "장비 보급 기반이 확대되는지", "설치 증가 후 소모품·서비스 매출이 따라오는지 확인"),
                    IndustryKpi("가동/사용량", "설치된 장비가 실제 사용되는지", "사용량이 낮으면 설치대수만으로 성장의 질을 판단하지 않음"),
                    IndustryKpi("소모품·서비스 비중", "반복매출 구조", "반복매출 비중 상승은 마진 안정성 개선 신호"),
                    IndustryKpi("해외 허가", "시장 확장 가능성", "허가 이후 실제 유통·매출 전환 속도를 확인")
                ),
                listOf("병원 채택 속도", "해외 유통 마진", "장비 판매와 소모품 매출의 시차"),
                listOf("허가·설치", "사용량", "소모품 반복매출", "영업이익률")
            )

            "전기장비·배터리" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("수주잔고", "향후 매출의 가시성", "수주잔고 증가가 실제 매출 인식으로 이어지는지 확인"),
                    IndustryKpi("생산능력", "증설로 공급 가능한 물량", "CAPEX 후 생산능력 증가가 가동률 상승으로 연결되는지 확인"),
                    IndustryKpi("가동률/수율", "신규 라인 경제성", "초기 비용 이후 수율 개선과 마진 회복이 나타나야 함"),
                    IndustryKpi("원재료 스프레드", "판가와 원재료 비용의 차이", "가격 전가가 늦으면 매출 증가에도 마진이 눌릴 수 있음")
                ),
                listOf("고객사 CAPEX", "원재료 가격 전가 조건", "증설 일정 지연 여부"),
                listOf("수주잔고", "증설·가동률", "수율", "영업이익률")
            )

            "자동차·부품" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("완성차 생산량", "고객사의 기본 물량", "생산량 변화와 부품 매출의 연결 확인"),
                    IndustryKpi("신규 차종/수주", "향후 탑재 물량", "신규 채택 이후 양산 매출 전환을 확인"),
                    IndustryKpi("차종·제품 믹스", "고부가 전장/ADAS 비중", "믹스 개선이 ASP와 마진을 높이는지 확인"),
                    IndustryKpi("환율·원재료", "외생적인 마진 변수", "환율 효과와 본질적 가격결정력을 분리")
                ),
                listOf("상위 고객 의존도", "단가 재협상", "전장화 콘텐츠 증가"),
                listOf("신규 차종", "양산 물량", "제품 믹스", "영업이익률")
            )

            "조선·운송장비" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("신규수주", "향후 매출 파이프라인", "신규수주가 수주잔고의 질을 개선하는지 확인"),
                    IndustryKpi("수주잔고", "다년 매출 가시성", "저가 과거수주 소진과 고가 신규수주 인식 시점을 확인"),
                    IndustryKpi("선가/계약단가", "미래 마진의 핵심", "고가 수주가 매출로 전환될 때 마진 상승이 따라와야 함"),
                    IndustryKpi("공정률", "매출·이익 인식 속도", "공정 지연과 원가 변경 위험을 확인")
                ),
                listOf("후판 등 원재료", "환율", "예정원가 변경·충당금"),
                listOf("신규수주", "수주잔고 질", "공정률", "마진 인식")
            )

            "호텔·레저·여행" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("Occupancy/점유율", "객실 수요", "점유율 상승이 매출 성장의 물량 원인인지 확인"),
                    IndustryKpi("ADR/객실단가", "객실 가격결정력", "ADR 상승이 RevPAR와 마진 개선으로 이어지는지 확인"),
                    IndustryKpi("RevPAR", "점유율과 객실단가의 결합 성과", "외형 성장의 질을 가장 직접적으로 확인"),
                    IndustryKpi("객실/점포 수", "신규 공급에 따른 외형 증가", "신규 출점 효과와 기존점 성장을 분리")
                ),
                listOf("외국인·관광객 수요", "신규 호텔 안정화 비용", "기존점 동일매출"),
                listOf("점유율", "ADR", "RevPAR", "영업이익률")
            )

            "금융·보험" -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("ROE", "자본 대비 이익 창출력", "일반 영업이익률 대신 수익성의 핵심 기준"),
                    IndustryKpi("NIM/보험마진", "핵심 영업 스프레드", "금리·상품구조가 본업 수익성에 미친 영향 확인"),
                    IndustryKpi("충당금/손해율", "위험비용", "이익 개선이 위험비용 축소 때문인지 분리"),
                    IndustryKpi("자본비율", "배당·성장 여력", "주주환원 가능성과 재무건전성을 함께 확인")
                ),
                listOf("연체율·자산건전성", "금리 민감도", "배당·자사주 정책"),
                listOf("핵심 스프레드", "위험비용", "ROE", "자본비율·주주환원")
            )

            else -> IndustryKpiProfile(
                broad,
                listOf(
                    IndustryKpi("물량/고객수", "외형 성장의 실제 수요 원인", "매출 증가가 실제 수요 증가인지 확인"),
                    IndustryKpi("판가/믹스", "가격결정력과 고부가 비중", "매출 성장과 마진의 연결을 확인"),
                    IndustryKpi("가동률/생산성", "고정비 흡수와 운영 효율", "투자 이후 이익률 회복 여부를 확인"),
                    IndustryKpi("수주/계약", "향후 매출 가시성", "선행지표가 실제 매출로 전환되는지 확인")
                ),
                listOf("일회성 매출·비용 분리", "원재료·인건비 변화", "CAPEX와 생산능력 변화"),
                listOf("수요·수주", "매출 전환", "믹스·생산성", "영업이익률")
            )
        }
    }
}
