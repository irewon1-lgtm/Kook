package com.krstock.v3.data.analysis

import com.krstock.v3.data.model.*
import com.krstock.v3.data.repository.StockRepository
import org.junit.Assert.*
import org.junit.Test

class IntegratedInterpretationEngineTest {

    @Test
    fun qualityGrowthExplainsInteractionInsteadOfReadingNumbers() {
        val stock = stock(growth = 88.0, margin = 86.0, per = 78.0, momentum = 82.0)
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.regimeTitle.contains("질 좋은 성장"))
        assertTrue(result.thesis.contains("같이"))
        assertTrue(result.combinationMeaning.contains("성장과 마진"))
        assertFalse(result.combinationMeaning.contains("88.0"))
        assertFalse(result.combinationMeaning.contains("86.0"))
        assertTrue(result.combinationMeaning.length >= 250)
    }

    @Test
    fun revenueFirstGrowthSeparatesInvestmentFromBadGrowth() {
        val stock = stock(growth = 90.0, margin = 12.0, per = 25.0, momentum = 70.0)
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.regimeTitle.contains("매출 선행형 성장"))
        assertTrue(result.thesis.contains("왜 성장의 대가로 마진을 포기"))
        assertTrue(result.combinationMeaning.contains("선투자"))
        assertTrue(result.combinationMeaning.contains("가격 인하"))
        assertTrue(result.consequence.contains("마진의 후행 회복"))
    }

    @Test
    fun efficiencyWithoutGrowthExplainsOperatingLeveragePossibility() {
        val stock = stock(growth = 12.0, margin = 88.0, per = 70.0, momentum = 25.0)
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.regimeTitle.contains("효율 개선형"))
        assertTrue(result.combinationMeaning.contains("비용구조 개선"))
        assertTrue(result.consequence.contains("영업레버리지"))
    }

    @Test
    fun compressionWarnsAgainstCheapPerValueTrap() {
        val stock = stock(growth = 8.0, margin = 10.0, per = 85.0, momentum = 8.0)
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.regimeTitle.contains("동반 압박"))
        assertTrue(result.thesis.contains("싸 보이는 이유"))
        assertTrue(result.combinationMeaning.contains("역영업레버리지"))
        assertTrue(result.consequence.contains("PER만 낮아지는"))
    }

    @Test
    fun evidenceCanSupportInvestmentHypothesisButNeverClaimsProof() {
        val stock = stock(growth = 92.0, margin = 15.0, per = 30.0, momentum = 75.0)
        val bundle = EvidenceBundle(
            issuerId = stock.issuerId,
            loaded = true,
            news = listOf(
                evidence("n1", EvidenceKind.NEWS, "신규 공장 증설 투자와 연구개발 확대", "경제지", "2026-09-14"),
                evidence("n2", EvidenceKind.NEWS, "대형 고객사와 공급계약 체결", "증권지", "2026-09-13")
            )
        )
        val result = IntegratedInterpretationEngine.analyze(stock, bundle)

        assertTrue(result.causeCandidates.any { it.contains("선투자형 성장") })
        assertTrue(result.causeInvestigation.contains("공급계약"))
        assertTrue(result.causeInvestigation.contains("확정"))
        assertTrue(result.causeInvestigation.contains("여러 원인") || result.causeInvestigation.contains("후보"))
        assertTrue(result.evidenceHighlights.size >= 2)
    }

    @Test
    fun evidenceFailureFailsClosedInsteadOfInventingCause() {
        val stock = stock(growth = 90.0, margin = 10.0, per = 50.0, momentum = 50.0)
        val result = IntegratedInterpretationEngine.analyze(
            stock,
            EvidenceBundle(stock.issuerId, loaded = true, error = "network")
        )

        assertTrue(result.causeInvestigation.contains("원인 검증을 보류"))
        assertTrue(result.causeInvestigation.contains("확정할 수 없"))
        assertTrue(result.evidenceHighlights.isEmpty())
    }

    @Test
    fun industryDescriptionsAreSpecificNotGeneric() {
        val software = IndustryInsightCatalog.profileFor("소프트웨어 개발 및 공급업")
        val pharma = IndustryInsightCatalog.profileFor("의약품 제조업")
        val semiconductor = IndustryInsightCatalog.profileFor("반도체 제조업")

        assertEquals("소프트웨어·IT서비스", software.label)
        assertTrue(software.economics.contains("플랫폼"))
        assertEquals("제약·바이오", pharma.label)
        assertTrue(pharma.economics.contains("파이프라인"))
        assertEquals("반도체·전자부품", semiconductor.label)
        assertTrue(semiconductor.marginLens.contains("가동률"))
    }

    @Test
    fun everyCurrentStockGetsNonemptyDeepAnalysisWithoutNanOrNullText() {
        val stocks = StockRepository.getAllStocks()
        assertEquals(2649, stocks.size)
        stocks.forEach { stock ->
            val analysis = IntegratedInterpretationEngine.analyze(stock)
            val texts = listOf(
                analysis.regimeTitle,
                analysis.thesis,
                analysis.industryContext,
                analysis.combinationMeaning,
                analysis.causeInvestigation,
                analysis.consequence,
                analysis.confidenceNote
            )
            texts.forEach { text ->
                assertTrue("blank ${stock.issuerId}", text.isNotBlank())
                assertFalse("NaN ${stock.issuerId}", text.contains("NaN"))
                assertFalse("Infinity ${stock.issuerId}", text.contains("Infinity"))
                assertFalse("null ${stock.issuerId}", text.contains("null", ignoreCase = true))
            }
            assertTrue("too short ${stock.issuerId}", analysis.combinationMeaning.length >= 180)
            assertTrue(analysis.falsifiers.size >= 2)
            assertTrue(analysis.industryKpiGuide.isNotBlank())
            assertTrue(analysis.businessState.isNotBlank())
            assertTrue(analysis.priceBurden.isNotBlank())
            assertTrue(analysis.causeConfidence.isNotBlank())
            assertTrue(analysis.futureUncertainty.isNotBlank())
        }
    }

    @Test
    fun allSixteenBinaryFactorCombinationsProduceDistinctUsefulOutput() {
        val fingerprints = mutableSetOf<String>()
        for (mask in 0 until 16) {
            fun hi(bit: Int) = if ((mask and (1 shl bit)) != 0) 90.0 else 10.0
            val result = IntegratedInterpretationEngine.analyze(
                stock(
                    growth = hi(0),
                    margin = hi(1),
                    per = hi(2),
                    momentum = hi(3),
                    code = "C%05d".format(mask)
                )
            )
            assertTrue(result.thesis.length >= 160)
            assertTrue(result.combinationMeaning.length >= 180)
            assertTrue(result.consequence.length >= 110)
            fingerprints += "${result.regimeTitle}|${result.thesis.take(80)}|${result.consequence.take(80)}"
        }
        assertTrue("interpretations collapsed into repetitive templates", fingerprints.size >= 10)
    }

    @Test
    fun tenThousandAnalysesAreDeterministic() {
        val stock = stock(growth = 91.0, margin = 18.0, per = 72.0, momentum = 87.0)
        val first = IntegratedInterpretationEngine.analyze(stock)
        repeat(10_000) {
            assertEquals(first, IntegratedInterpretationEngine.analyze(stock))
        }
    }

    private fun stock(
        growth: Double,
        margin: Double,
        per: Double,
        momentum: Double,
        code: String = "999001"
    ): StockSummary = StockSummary(
        issuerId = code,
        name = "테스트기업",
        market = "KOSDAQ",
        sector = "소프트웨어 개발 및 공급업",
        listingDate = "2020-01-01",
        isFinancial = false,
        isLossMaking = false,
        m01RevGrowth = metric("M01", growth, true),
        m02OpMargin = metric("M02", margin, true),
        m03Per = metric("M03", per, false),
        m04Price6m = metric("M04", momentum, true),
        compositeScore = (growth + margin + per + momentum) / 4.0,
        rankOrder = 1,
        isCompositeComplete = true,
        asOfDate = "2026-09-15",
        dataSource = "test",
        status = DataStatus.REAL
    )

    private fun metric(id: String, percentile: Double, higherIsBetter: Boolean): MetricValue {
        val raw = when (id) {
            "M03" -> if (percentile >= 50) 8.0 else 35.0
            else -> percentile - 50.0
        }
        val median = when (id) {
            "M03" -> 20.0
            else -> 0.0
        }
        val better = when {
            id == "M03" -> raw < median
            else -> raw > median
        }
        return MetricValue(
            id = id,
            nameKo = id,
            rawValue = raw,
            percentileScore = percentile,
            unit = if (id == "M03") "배" else "%",
            isAvailable = true,
            description = "test",
            interpretation = "test",
            caution = "test",
            source = "test",
            basis = "test",
            asOfDate = "2026-09-15",
            peerComparison = PeerComparison(
                groupLabel = "소프트웨어 개발 및 공급업",
                basis = PeerGroupBasis.KRX_EXACT_SECTOR,
                sampleSize = 25,
                median = median,
                deltaFromMedian = raw - median,
                relativeToMedianPct = if (median != 0.0) (raw / median - 1.0) * 100.0 else null,
                betterThanMedian = better,
                higherIsBetter = higherIsBetter
            )
        )
    }

    private fun evidence(
        id: String,
        kind: EvidenceKind,
        title: String,
        source: String,
        date: String
    ) = ContextEvidence(id, kind, title, source, date)
}
