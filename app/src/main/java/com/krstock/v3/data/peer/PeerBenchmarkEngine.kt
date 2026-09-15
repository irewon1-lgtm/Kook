package com.krstock.v3.data.peer

import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.PeerComparison
import com.krstock.v3.data.model.PeerGroupBasis
import com.krstock.v3.data.model.StockSummary
import kotlin.math.abs

/**
 * Deterministic, fail-closed peer median engine for KR4.
 *
 * Selection policy is metric-specific because missingness differs by metric:
 *  1) Prefer the exact KRX KIND sector when at least [MIN_EXACT_SAMPLE] usable values exist.
 *  2) Otherwise fall back to a transparent standard sector family when at least
 *     [MIN_FAMILY_SAMPLE] usable values exist.
 *  3) Otherwise do not fabricate a peer median. The metric receives an
 *     INSUFFICIENT benchmark with a reason and the best observed sample count.
 *
 * Median is used instead of mean so one-off growth spikes and valuation outliers
 * do not dominate the peer baseline. All calculations use only finite, already
 * validated raw metric values present in the installed KR4 snapshot.
 */
object PeerBenchmarkEngine {
    const val MIN_EXACT_SAMPLE: Int = 8
    const val MIN_FAMILY_SAMPLE: Int = 20

    private data class MetricSpec(
        val id: String,
        val higherIsBetter: Boolean,
        val read: (StockSummary) -> MetricValue
    )

    private data class SectorFamily(val key: String, val label: String)

    private data class BucketKey(val group: String, val metricId: String)

    private data class Distribution(val sorted: List<Double>) {
        init {
            require(sorted.isNotEmpty())
            require(sorted.zipWithNext().all { (a, b) -> a <= b })
            require(sorted.all { it.isFinite() })
        }

        val size: Int get() = sorted.size

        val median: Double by lazy {
            val n = sorted.size
            if (n % 2 == 1) sorted[n / 2]
            else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }

    private val specs = listOf(
        MetricSpec("M01", higherIsBetter = true) { it.m01RevGrowth },
        MetricSpec("M02", higherIsBetter = true) { it.m02OpMargin },
        MetricSpec("M03", higherIsBetter = false) { it.m03Per },
        MetricSpec("M04", higherIsBetter = true) { it.m04Price6m }
    )

    fun enrich(stocks: List<StockSummary>): List<StockSummary> {
        require(stocks.map { it.issuerId }.toSet().size == stocks.size) {
            "Peer benchmark input contains duplicate issue codes"
        }

        val exactValues = mutableMapOf<BucketKey, MutableList<Double>>()
        val familyValues = mutableMapOf<BucketKey, MutableList<Double>>()

        stocks.forEach { stock ->
            val exactSector = normalizedExactSector(stock.sector)
            val family = classifyFamily(stock.sector)
            specs.forEach { spec ->
                val raw = spec.read(stock).rawValue
                if (raw != null && raw.isFinite()) {
                    if (exactSector != null) {
                        exactValues.getOrPut(BucketKey(exactSector, spec.id)) { mutableListOf() }.add(raw)
                    }
                    if (family != null) {
                        familyValues.getOrPut(BucketKey(family.key, spec.id)) { mutableListOf() }.add(raw)
                    }
                }
            }
        }

        val exactDistributions = exactValues.mapValues { (_, values) -> Distribution(values.sorted()) }
        val familyDistributions = familyValues.mapValues { (_, values) -> Distribution(values.sorted()) }

        return stocks.map { stock ->
            val exactSector = normalizedExactSector(stock.sector)
            val family = classifyFamily(stock.sector)

            fun benchmark(metric: MetricValue, higherIsBetter: Boolean): PeerComparison {
                val exact = exactSector?.let { exactDistributions[BucketKey(it, metric.id)] }
                val broad = family?.let { familyDistributions[BucketKey(it.key, metric.id)] }

                val selected = when {
                    exact != null && exact.size >= MIN_EXACT_SAMPLE -> SelectedDistribution(
                        basis = PeerGroupBasis.KRX_EXACT_SECTOR,
                        label = exactSector,
                        distribution = exact
                    )
                    broad != null && broad.size >= MIN_FAMILY_SAMPLE -> SelectedDistribution(
                        basis = PeerGroupBasis.STANDARD_SECTOR_FAMILY,
                        label = family!!.label,
                        distribution = broad
                    )
                    else -> null
                }

                if (selected == null) {
                    val bestSample = maxOf(exact?.size ?: 0, broad?.size ?: 0)
                    val bestLabel = when {
                        (broad?.size ?: 0) >= (exact?.size ?: 0) && family != null -> family.label
                        exactSector != null -> exactSector
                        family != null -> family.label
                        else -> stock.sector.ifBlank { "미분류" }
                    }
                    return PeerComparison(
                        groupLabel = bestLabel,
                        basis = PeerGroupBasis.INSUFFICIENT,
                        sampleSize = bestSample,
                        median = null,
                        deltaFromMedian = null,
                        relativeToMedianPct = null,
                        betterThanMedian = null,
                        higherIsBetter = higherIsBetter,
                        reason = when {
                            exactSector == null && family == null -> "비교 가능한 업종 분류가 없어 피어 중앙값을 만들지 않음"
                            else -> "피어 표본 부족 · 세부업종 ${exact?.size ?: 0}개 / 표준그룹 ${broad?.size ?: 0}개"
                        }
                    )
                }

                val median = selected.distribution.median
                val raw = metric.rawValue?.takeIf { it.isFinite() }
                val delta = raw?.minus(median)
                val relativePct = if (raw != null && abs(median) > 1e-12) {
                    (raw / median - 1.0) * 100.0
                } else null
                val better = if (raw == null) null else when {
                    abs(raw - median) <= 1e-12 -> null
                    higherIsBetter -> raw > median
                    else -> raw < median
                }

                return PeerComparison(
                    groupLabel = selected.label,
                    basis = selected.basis,
                    sampleSize = selected.distribution.size,
                    median = median,
                    deltaFromMedian = delta,
                    relativeToMedianPct = relativePct,
                    betterThanMedian = better,
                    higherIsBetter = higherIsBetter,
                    reason = null
                )
            }

            stock.copy(
                m01RevGrowth = stock.m01RevGrowth.copy(peerComparison = benchmark(stock.m01RevGrowth, true)),
                m02OpMargin = stock.m02OpMargin.copy(peerComparison = benchmark(stock.m02OpMargin, true)),
                m03Per = stock.m03Per.copy(peerComparison = benchmark(stock.m03Per, false)),
                m04Price6m = stock.m04Price6m.copy(peerComparison = benchmark(stock.m04Price6m, true))
            )
        }
    }

    private data class SelectedDistribution(
        val basis: PeerGroupBasis,
        val label: String,
        val distribution: Distribution
    )

    private fun normalizedExactSector(raw: String): String? {
        val value = raw.trim().replace(Regex("\\s+"), " ")
        if (value.isBlank()) return null
        if (value == "기타" || value == "미분류" || value == "없음") return null
        return value
    }

    /**
     * Broad fallback families are intentionally transparent and deterministic.
     * Exact KRX sectors always win when they have enough metric-specific samples.
     */
    private fun classifyFamily(raw: String): SectorFamily? {
        val s = raw.replace(Regex("\\s+"), "").lowercase()
        if (s.isBlank() || s == "기타" || s == "미분류") return null

        fun has(vararg words: String) = words.any { s.contains(it) }

        return when {
            has("은행", "금융", "보험", "증권", "신탁", "여신", "신용카드") ->
                SectorFamily("FINANCE", "금융·보험")

            has("의약품", "제약", "생물학적", "생명공학", "바이오", "의약물질") ->
                SectorFamily("PHARMA_BIO", "제약·바이오")

            has("의료용기기", "의료기기", "의료용품", "의료서비스", "병원", "진단기기") ->
                SectorFamily("MEDICAL_HEALTH", "의료기기·헬스케어")

            has("디스플레이", "액정", "oled") ->
                SectorFamily("DISPLAY", "디스플레이")

            has("반도체", "전자부품", "컴퓨터", "통신장비", "영상기기", "음향기기") ->
                SectorFamily("SEMICON_ELECTRONICS", "반도체·전자부품")

            has("일차전지", "이차전지", "축전지", "전기장비", "전동기", "발전기", "변압기", "전선", "배선", "조명") ->
                SectorFamily("ELECTRICAL_BATTERY", "전기장비·배터리")

            has("자동차", "자동차용", "차체", "트레일러") ->
                SectorFamily("AUTO", "자동차·부품")

            has("조선", "선박", "항공기", "철도장비", "운송장비") ->
                SectorFamily("TRANSPORT_EQUIPMENT", "조선·운송장비")

            has("기계", "공작기계", "특수목적", "일반목적", "산업용로봇", "펌프", "밸브", "베어링") ->
                SectorFamily("MACHINERY", "기계·산업장비")

            has("철강", "1차철강", "비철금속", "알루미늄", "금속가공", "구조용금속", "압연") ->
                SectorFamily("METAL", "철강·금속")

            has("화학", "합성수지", "합성고무", "플라스틱", "고무제품", "도료", "접착", "비료") ->
                SectorFamily("CHEMICAL", "화학·소재")

            has("시멘트", "콘크리트", "유리", "도자기", "비금속광물", "골재", "건축자재") ->
                SectorFamily("BUILDING_MATERIAL", "건자재")

            has("건설", "건축", "토목", "건축기술", "엔지니어링") ->
                SectorFamily("CONSTRUCTION", "건설·엔지니어링")

            has("석유", "정유", "원유", "석탄", "연료", "가스제조") ->
                SectorFamily("ENERGY", "에너지·정유·가스")

            has("발전업", "전기업", "전력", "수도", "폐기물", "환경정화") ->
                SectorFamily("UTILITY_ENV", "유틸리티·환경")

            has("해운", "항공운송", "육상운송", "운송업", "물류", "창고") ->
                SectorFamily("TRANSPORT_LOGISTICS", "운송·물류")

            has("식품", "음료", "주류", "담배", "농업", "축산", "수산", "사료") ->
                SectorFamily("FOOD", "음식료·농축수산")

            has("소프트웨어", "프로그래밍", "시스템통합", "정보서비스", "데이터베이스", "컴퓨터프로그래밍") ->
                SectorFamily("SOFTWARE_IT", "소프트웨어·IT서비스")

            has("포털", "게임", "영화", "방송", "콘텐츠", "출판", "음악", "영상물", "광고") ->
                SectorFamily("CONTENT_MEDIA", "인터넷·콘텐츠·미디어")

            has("전기통신업", "무선통신", "유선통신", "통신서비스") ->
                SectorFamily("TELECOM", "통신서비스")

            has("부동산", "임대업") ->
                SectorFamily("REAL_ESTATE", "부동산·임대")

            has("도매", "소매", "유통", "전자상거래", "백화점", "편의점") ->
                SectorFamily("RETAIL_DISTRIBUTION", "유통·도소매")

            has("섬유", "의복", "봉제", "가죽", "신발") ->
                SectorFamily("TEXTILE_APPAREL", "섬유·의류")

            has("화장품", "가구", "생활용품", "레저", "여행", "숙박", "음식점") ->
                SectorFamily("CONSUMER", "소비재·레저")

            has("종이", "펄프", "포장", "골판지") ->
                SectorFamily("PAPER_PACKAGING", "종이·포장")

            has("연구개발", "전문서비스", "컨설팅", "교육", "사업지원", "인력공급") ->
                SectorFamily("PROFESSIONAL_SERVICE", "전문·사업서비스")

            has("제조업", "제조") ->
                SectorFamily("OTHER_MANUFACTURING", "기타 제조")

            has("서비스업", "서비스") ->
                SectorFamily("OTHER_SERVICE", "기타 서비스")

            else -> null
        }
    }
}
