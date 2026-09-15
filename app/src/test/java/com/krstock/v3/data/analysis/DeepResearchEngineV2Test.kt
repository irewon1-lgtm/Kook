package com.krstock.v3.data.analysis

import com.krstock.v3.data.evidence.DisclosureDiffEngine
import com.krstock.v3.data.model.*
import org.junit.Assert.*
import org.junit.Test

class DeepResearchEngineV2Test {

    @Test
    fun industryKpiCatalogUsesSectorSpecificCauseSensors() {
        val semi = IndustryKpiCatalog.profileFor("반도체 제조업")
        val software = IndustryKpiCatalog.profileFor("소프트웨어 개발 및 공급업")
        val finance = IndustryKpiCatalog.profileFor("은행업")

        assertTrue(semi.kpis.any { it.name.contains("ASP") })
        assertTrue(semi.kpis.any { it.name.contains("수율") })
        assertTrue(software.kpis.any { it.name.contains("Retention") })
        assertTrue(software.kpis.any { it.name.contains("CAC") })
        assertTrue(finance.kpis.any { it.name == "ROE" })
        assertNotEquals(semi.kpis.map { it.name }, software.kpis.map { it.name })
    }

    @Test
    fun fullTextDisclosureDiffOnlyWhenTwoRealBodiesExist() {
        val previousBody = (
            "기존 생산라인을 안정적으로 운영하고 있습니다. 기존 고객 수요에 대응하고 있으며 " +
                "비용 효율화와 품질 개선을 지속하고 있습니다. "
            ).repeat(18)
        val currentBody = (
            "신규라인 설비투자 CAPEX와 생산능력 증설을 진행합니다. 신규 공장 가동을 준비하고 있습니다. " +
                "경영진 전략 목표는 고부가 제품 확대이며 수주잔고와 고객사 수요가 증가하고 있습니다. " +
                "초기 수율과 감가상각 비용은 단기 부담이 될 수 있습니다. "
            ).repeat(14)

        val diff = DisclosureDiffEngine.compare(
            listOf(
                disclosure("c", "분기보고서 (2026.06)", "2026-08-14", currentBody),
                disclosure("p", "분기보고서 (2026.03)", "2026-05-15", previousBody)
            )
        )

        assertNotNull(diff)
        assertTrue(diff!!.available)
        assertEquals("DART 원문 본문 Diff", diff.scope)
        assertTrue(diff.newlyAppeared.contains("CAPEX·생산능력"))
        assertTrue(
            diff.newlyAppeared.contains("경영진 전망·전략") ||
                diff.strengthened.contains("경영진 전망·전략")
        )
    }

    @Test
    fun missingBodiesNeverPretendToBeDocumentRedline() {
        val diff = DisclosureDiffEngine.compare(
            listOf(
                disclosure("c", "분기보고서 (2026.06)", "2026-08-14", ""),
                disclosure("p", "분기보고서 (2026.03)", "2026-05-15", "")
            )
        )

        assertNotNull(diff)
        assertFalse(diff!!.available)
        assertTrue(diff.scope.contains("본문 Diff 대체 불가"))
        assertTrue(diff.note.contains("redline은 확정하지 않았습니다"))
    }

    @Test
    fun primaryDisclosureOutranksNewsWhenBothSupportSameStory() {
        val stock = stock(growth = 92.0, margin = 12.0, per = 35.0, momentum = 78.0)
        val result = IntegratedInterpretationEngine.analyze(
            stock,
            EvidenceBundle(
                issuerId = stock.issuerId,
                loaded = true,
                disclosures = listOf(
                    ContextEvidence(
                        id = "d1",
                        kind = EvidenceKind.DISCLOSURE,
                        title = "신규 공장 증설 및 설비투자 결정",
                        source = "DART",
                        publishedAt = "2026-09-13",
                        sourceTier = EvidenceSourceTier.DART_PRIMARY
                    )
                ),
                news = listOf(
                    ContextEvidence(
                        id = "n1",
                        kind = EvidenceKind.NEWS,
                        title = "신규 공장 증설 투자 확대 전망",
                        source = "테스트경제",
                        publishedAt = "2026-09-15",
                        sourceTier = EvidenceSourceTier.TRUSTED_MEDIA
                    )
                )
            )
        )

        assertTrue(result.evidenceHighlights.isNotEmpty())
        assertEquals(EvidenceKind.DISCLOSURE, result.evidenceHighlights.first().kind)
        assertTrue(result.causeInvestigation.contains("DART 공시"))
        assertTrue(result.causeInvestigation.contains("뉴스보다 먼저"))
    }

    @Test
    fun analysisKeepsBusinessPriceCauseAndUncertaintySeparate() {
        val stock = stock(growth = 90.0, margin = 10.0, per = 10.0, momentum = 90.0)
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.businessState.isNotBlank())
        assertTrue(result.priceBurden.isNotBlank())
        assertTrue(result.causeConfidence.isNotBlank())
        assertTrue(result.futureUncertainty.isNotBlank())
        assertTrue(result.confidenceNote.contains("사업 상태:"))
        assertTrue(result.confidenceNote.contains("가격 부담:"))
        assertTrue(result.confidenceNote.contains("원인 분석 확신도:"))
        assertTrue(result.confidenceNote.contains("미래 예측 불확실성:"))
        assertTrue(result.confidenceNote.contains("하나의 점수로 합치지 않습니다"))
    }

    @Test
    fun revenueFirstKeepsCompetingCausesAndPredictsObservableChain() {
        val stock = stock(
            growth = 93.0,
            margin = 8.0,
            per = 28.0,
            momentum = 82.0,
            sector = "반도체 제조업"
        )
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.causeCandidates.size >= 3)
        assertTrue(result.causeCandidates.any { it.contains("선투자") })
        assertTrue(result.causeCandidates.any { it.contains("원가") })
        assertTrue(result.causeCandidates.any { it.contains("저마진") })
        assertTrue(result.nextQuarterWatch.any { it.contains("가동률") })
        assertTrue(result.nextQuarterWatch.any { it.contains("영업이익률") })
        assertTrue(result.industryKpiGuide.contains("수율"))
    }

    @Test
    fun noEvidenceFailsClosedInsteadOfSelectingAStory() {
        val stock = stock(growth = 92.0, margin = 9.0, per = 30.0, momentum = 70.0)
        val result = IntegratedInterpretationEngine.analyze(stock)

        assertTrue(result.causeInvestigation.contains("사실처럼 단정하지 않습니다"))
        assertTrue(result.causeConfidence.startsWith("낮음"))
        assertTrue(result.disclosureDiffNote.contains("보류"))
    }

    private fun disclosure(id: String, title: String, date: String, body: String) = ContextEvidence(
        id = id,
        kind = EvidenceKind.DISCLOSURE,
        title = title,
        source = "DART",
        publishedAt = date,
        sourceTier = EvidenceSourceTier.DART_PRIMARY,
        receiptNo = "20260915000${if (id == "c") "1" else "2"}",
        bodyText = body
    )

    private fun stock(
        growth: Double,
        margin: Double,
        per: Double,
        momentum: Double,
        sector: String = "소프트웨어 개발 및 공급업"
    ): StockSummary = StockSummary(
        issuerId = "999001",
        name = "테스트기업",
        market = "KOSDAQ",
        sector = sector,
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
        val raw = if (id == "M03") {
            if (percentile >= 50) 8.0 else 35.0
        } else {
            percentile - 50.0
        }
        val median = if (id == "M03") 20.0 else 0.0
        val better = if (id == "M03") raw < median else raw > median
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
                groupLabel = sectorLabel(id),
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

    private fun sectorLabel(id: String): String = if (id.isNotBlank()) "테스트업종" else "테스트업종"
}
