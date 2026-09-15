package com.krstock.v3.data.analysis

import com.krstock.v3.data.evidence.ContextEvidenceRepository
import com.krstock.v3.data.evidence.DartPrimaryBundle
import com.krstock.v3.data.evidence.DartPrimarySourceRepository
import com.krstock.v3.data.model.*
import com.krstock.v3.data.repository.StockRepository
import org.junit.Assert.*
import org.junit.Test

class ResearchForensicsExtremeTest {

    @Test
    fun pcDisclosureIndexExtractsDartReceiptAndIdentity() {
        val html = """
            <table>
              <tr>
                <td><a href="https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260915001234">반기보고서 (2026.06)</a></td>
                <td>2026.09.15</td>
              </tr>
            </table>
        """.trimIndent()

        val items = ContextEvidenceRepository.parsePcDisclosureHtml(html)
        assertEquals(1, items.size)
        assertEquals("20260915001234", items.single().receiptNo)
        assertEquals(EvidenceKind.DISCLOSURE, items.single().kind)
        assertTrue(items.single().url.contains("dart.fss.or.kr"))
        assertEquals("2026-09-15", items.single().publishedAt)
    }

    @Test
    fun dartViewerMetadataParserSurvivesWhitespaceAndQuotes() {
        val html = """
            <html><body>
              <div>사업의 내용 및 연구개발</div>
              <script>
                viewDoc( '20260915001234' , '9911223' , '17' , '1200' , '4500' , 'dart3.xsd' );
              </script>
            </body></html>
        """.trimIndent()

        val sections = DartPrimarySourceRepository.parseViewerSections(html)
        assertEquals(1, sections.size)
        val section = sections.single()
        assertEquals("20260915001234", section.rcpNo)
        assertEquals("9911223", section.dcmNo)
        assertEquals("17", section.eleId)
        assertTrue(section.context.contains("사업의 내용"))
    }

    @Test
    fun dartHtmlSanitizerRemovesExecutableNoiseButKeepsKoreanEvidence() {
        val html = """
            <style>.secret{display:none}</style>
            <script>alert('절대 남으면 안 됨')</script>
            <div>신규 공장 가동률이 상승하면서 고정비 부담이 감소하였습니다.</div>
            <p>고부가 제품 판매 확대가 수익성 개선에 영향을 주었습니다.</p>
        """.trimIndent()

        val paragraphs = DartPrimarySourceRepository.htmlToParagraphs(html)
        val all = paragraphs.joinToString(" ")
        assertFalse(all.contains("alert"))
        assertFalse(all.contains("display:none"))
        assertTrue(all.contains("가동률"))
        assertTrue(all.contains("수익성 개선"))
    }

    @Test
    fun directPrimaryCauseReceivesPrimaryConfirmedGrade() {
        val stock = stock(growth = 90.0, margin = 10.0)
        val primary = primary(
            "설비투자 증가와 신규 공장 초기 가동의 영향으로 고정비 부담이 확대되었습니다."
        )

        val result = ResearchForensicsEngine.synthesize(
            stock,
            EvidenceBundle(stock.issuerId, loaded = true),
            primary,
            IndustryKpiCatalog.forSector(stock.sector)
        )

        assertEquals(EvidenceGrade.PRIMARY_CONFIRMED, result.grade)
        assertTrue(result.gradeReason.contains("DART 원문"))
        assertTrue(result.rootCauseRead.contains("선행투자") || result.rootCauseRead.contains("투자"))
    }

    @Test
    fun primaryAndIndependentHeadlineBecomeStrongSupportWithoutPretendingProof() {
        val stock = stock(growth = 90.0, margin = 10.0)
        val primary = primary("신규 설비투자 및 연구개발 계획")
        val evidence = EvidenceBundle(
            issuerId = stock.issuerId,
            news = listOf(
                ContextEvidence("n1", EvidenceKind.NEWS, "신규 생산라인 증설 계획 발표", "경제지", "2026-09-14")
            ),
            loaded = true
        )

        val result = ResearchForensicsEngine.synthesize(
            stock,
            evidence,
            primary,
            IndustryKpiCatalog.forSector(stock.sector)
        )

        assertEquals(EvidenceGrade.STRONGLY_SUPPORTED, result.grade)
        assertTrue(result.gradeReason.contains("같은 방향"))
        assertFalse(result.gradeReason.contains("확정되었습니다"))
    }

    @Test
    fun headlineOnlyNeverUpgradesToConfirmedCause() {
        val stock = stock(growth = 90.0, margin = 10.0)
        val evidence = EvidenceBundle(
            issuerId = stock.issuerId,
            news = listOf(ContextEvidence("n1", EvidenceKind.NEWS, "공장 증설 추진", "뉴스", "2026-09-14")),
            loaded = true
        )
        val result = ResearchForensicsEngine.synthesize(
            stock,
            evidence,
            DartPrimaryBundle(loaded = true),
            IndustryKpiCatalog.forSector(stock.sector)
        )

        assertEquals(EvidenceGrade.PLAUSIBLE, result.grade)
        assertTrue(result.gradeReason.contains("제목"))
        assertTrue(result.rootCauseRead.contains("단정") || result.rootCauseRead.contains("확정"))
    }

    @Test
    fun noEvidenceFailsClosedAsUnverified() {
        val stock = stock(growth = 90.0, margin = 10.0)
        val result = ResearchForensicsEngine.synthesize(
            stock,
            EvidenceBundle(stock.issuerId, loaded = true),
            DartPrimaryBundle(loaded = true, error = "viewer shape changed"),
            IndustryKpiCatalog.forSector(stock.sector)
        )

        assertEquals(EvidenceGrade.UNVERIFIED, result.grade)
        assertTrue(result.gradeReason.contains("확보하지 못"))
        assertTrue(result.sourceError?.contains("viewer") == true)
    }

    @Test
    fun sameRevenueFirstPatternSeparatesInvestmentCostAndPricingExplanations() {
        val stock = stock(growth = 92.0, margin = 8.0)
        val kpis = IndustryKpiCatalog.forSector(stock.sector)
        val investment = ResearchForensicsEngine.synthesize(
            stock, EvidenceBundle(stock.issuerId, loaded = true),
            primary("신규 설비투자와 생산능력 확대, 공장 가동률 정상화 계획"), kpis
        )
        val cost = ResearchForensicsEngine.synthesize(
            stock, EvidenceBundle(stock.issuerId, loaded = true),
            primary("원재료 및 인건비 부담으로 제조원가 상승"), kpis
        )
        val pricing = ResearchForensicsEngine.synthesize(
            stock, EvidenceBundle(stock.issuerId, loaded = true),
            primary("시장 경쟁에 따른 판매가격 조정과 저가 제품믹스 확대"), kpis
        )

        assertTrue(investment.rootCauseRead.contains("선행투자") || investment.rootCauseRead.contains("투자"))
        assertTrue(cost.rootCauseRead.contains("비용") || cost.rootCauseRead.contains("원가"))
        assertTrue(pricing.rootCauseRead.contains("가격") || pricing.rootCauseRead.contains("믹스"))
        assertEquals(3, setOf(investment.rootCauseRead, cost.rootCauseRead, pricing.rootCauseRead).size)
    }

    @Test
    fun industryKpiCatalogIsSpecificAcrossDifferentEconomics() {
        val software = IndustryKpiCatalog.forSector("소프트웨어 개발 및 공급업")
        val pharma = IndustryKpiCatalog.forSector("의약품 제조업")
        val semi = IndustryKpiCatalog.forSector("반도체 제조업")
        val finance = IndustryKpiCatalog.forSector("은행 및 금융업")

        assertTrue(software.any { it.name.contains("반복매출") })
        assertTrue(pharma.any { it.name.contains("임상") })
        assertTrue(semi.any { it.name.contains("가동률") })
        assertTrue(finance.any { it.name.contains("NIM") })
        assertNotEquals(software.map { it.name }.toSet(), pharma.map { it.name }.toSet())
        listOf(software, pharma, semi, finance).flatten().forEach {
            assertTrue(it.terms.isNotEmpty())
            assertTrue(it.whyItMatters.length >= 12)
        }
    }

    @Test
    fun everyRegisteredStockHasSafeIndustryKpisAndUnverifiedBaseline() {
        val stocks = StockRepository.getAllStocks()
        assertEquals(2649, stocks.size)
        stocks.forEach { stock ->
            val kpis = IndustryKpiCatalog.forSector(stock.sector)
            assertTrue("no KPI ${stock.issuerId}", kpis.size >= 3)
            val result = ResearchForensicsEngine.synthesize(
                stock,
                EvidenceBundle(stock.issuerId, loaded = true),
                DartPrimaryBundle(loaded = true),
                kpis
            )
            assertTrue(result.rootCauseRead.isNotBlank())
            assertTrue(result.alternativeHypotheses.size >= 2)
            assertTrue(result.nextChecks.isNotEmpty())
            assertFalse(result.rootCauseRead.contains("NaN"))
            assertFalse(result.rootCauseRead.contains("Infinity"))
            assertFalse(result.rootCauseRead.contains("null", ignoreCase = true))
        }
    }

    @Test
    fun tenThousandForensicSynthesesAreDeterministic() {
        val stock = stock(growth = 91.0, margin = 12.0)
        val evidence = EvidenceBundle(
            stock.issuerId,
            news = listOf(ContextEvidence("n1", EvidenceKind.NEWS, "신규 공장 증설", "뉴스", "2026-09-14")),
            loaded = true
        )
        val primary = primary("신규 설비투자 및 연구개발 계획")
        val kpis = IndustryKpiCatalog.forSector(stock.sector)
        val first = ResearchForensicsEngine.synthesize(stock, evidence, primary, kpis)
        repeat(10_000) {
            assertEquals(first, ResearchForensicsEngine.synthesize(stock, evidence, primary, kpis))
        }
    }

    private fun primary(vararg excerpts: String): DartPrimaryBundle = DartPrimaryBundle(
        loaded = true,
        currentTitle = "반기보고서",
        currentDate = "2026-09-14",
        excerpts = excerpts.mapIndexed { index, text ->
            PrimarySourceExcerpt(
                receiptNo = "2026091400000$index",
                reportTitle = "반기보고서",
                publishedAt = "2026-09-14",
                topic = "테스트",
                excerpt = text,
                sourceUrl = "https://dart.fss.or.kr/"
            )
        }
    )

    private fun stock(growth: Double, margin: Double): StockSummary = StockSummary(
        issuerId = "999001",
        name = "테스트기업",
        market = "KOSDAQ",
        sector = "반도체 제조업",
        listingDate = "2020-01-01",
        isFinancial = false,
        isLossMaking = false,
        m01RevGrowth = metric("M01", growth, true),
        m02OpMargin = metric("M02", margin, true),
        m03Per = metric("M03", 50.0, false),
        m04Price6m = metric("M04", 50.0, true),
        compositeScore = (growth + margin + 100.0) / 4.0,
        rankOrder = 1,
        isCompositeComplete = true,
        asOfDate = "2026-09-15",
        dataSource = "test",
        status = DataStatus.REAL
    )

    private fun metric(id: String, percentile: Double, higherIsBetter: Boolean): MetricValue {
        val raw = when (id) {
            "M03" -> 20.0
            else -> percentile - 50.0
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
            source = "test",
            basis = "test",
            asOfDate = "2026-09-15",
            peerComparison = PeerComparison(
                groupLabel = "반도체 제조업",
                basis = PeerGroupBasis.KRX_EXACT_SECTOR,
                sampleSize = 30,
                median = median,
                deltaFromMedian = raw - median,
                relativeToMedianPct = null,
                betterThanMedian = better,
                higherIsBetter = higherIsBetter
            )
        )
    }
}
