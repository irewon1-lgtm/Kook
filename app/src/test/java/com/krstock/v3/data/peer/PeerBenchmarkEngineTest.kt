package com.krstock.v3.data.peer

import com.krstock.v3.data.model.DataStatus
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.PeerGroupBasis
import com.krstock.v3.data.model.StockSummary
import org.junit.Assert.*
import org.junit.Test

class PeerBenchmarkEngineTest {

    @Test
    fun exactSectorMedianUsesTrueEvenSampleMedianAndWinsOverBroadFamily() {
        val exact = (1..8).map { i ->
            stock("E%05d".format(i), "소프트웨어 개발 및 공급업", m01 = i.toDouble())
        }
        val broadNoise = (1..24).map { i ->
            stock("B%05d".format(i), "컴퓨터 프로그래밍 서비스업", m01 = 1000.0 + i)
        }

        val enriched = PeerBenchmarkEngine.enrich(exact + broadNoise)
        val peer = enriched.first { it.issuerId == "E00001" }.m01RevGrowth.peerComparison!!

        assertEquals(PeerGroupBasis.KRX_EXACT_SECTOR, peer.basis)
        assertEquals(8, peer.sampleSize)
        assertEquals(4.5, peer.median!!, 0.0000001)
        assertEquals(-3.5, peer.deltaFromMedian!!, 0.0000001)
        assertEquals(false, peer.betterThanMedian)
    }

    @Test
    fun smallExactSectorFallsBackToTransparentStandardFamily() {
        val tinyExact = (1..3).map { i ->
            stock("T%05d".format(i), "소프트웨어 개발 및 공급업", m01 = i.toDouble())
        }
        val familyPeers = (1..20).map { i ->
            stock("F%05d".format(i), "컴퓨터 프로그래밍 서비스업", m01 = (10 + i).toDouble())
        }

        val enriched = PeerBenchmarkEngine.enrich(tinyExact + familyPeers)
        val peer = enriched.first { it.issuerId == "T00001" }.m01RevGrowth.peerComparison!!

        assertEquals(PeerGroupBasis.STANDARD_SECTOR_FAMILY, peer.basis)
        assertEquals("소프트웨어·IT서비스", peer.groupLabel)
        assertEquals(23, peer.sampleSize)
        assertEquals(19.0, peer.median!!, 0.0000001)
    }

    @Test
    fun perDirectionTreatsLowerThanMedianAsBetter() {
        val stocks = (1..8).map { i ->
            stock("P%05d".format(i), "의약품 제조업", m03 = i * 10.0)
        }

        val enriched = PeerBenchmarkEngine.enrich(stocks)
        val lowPer = enriched.first { it.issuerId == "P00001" }.m03Per.peerComparison!!
        val highPer = enriched.first { it.issuerId == "P00008" }.m03Per.peerComparison!!

        assertEquals(45.0, lowPer.median!!, 0.0000001)
        assertEquals(true, lowPer.betterThanMedian)
        assertEquals(false, highPer.betterThanMedian)
        assertFalse(lowPer.higherIsBetter)
    }

    @Test
    fun percentageMetricsTreatHigherThanMedianAsBetter() {
        val stocks = (1..8).map { i ->
            stock("G%05d".format(i), "의료용 기기 제조업", m02 = i.toDouble())
        }

        val enriched = PeerBenchmarkEngine.enrich(stocks)
        val high = enriched.first { it.issuerId == "G00008" }.m02OpMargin.peerComparison!!

        assertEquals(4.5, high.median!!, 0.0000001)
        assertEquals(true, high.betterThanMedian)
        assertTrue(high.higherIsBetter)
    }

    @Test
    fun unavailableTargetMetricCanStillShowPeerMedianWithoutFakeDelta() {
        val peers = (1..8).map { i ->
            stock("A%05d".format(i), "자동차 부품 제조업", m01 = i.toDouble())
        }
        val unavailableTarget = stock("TARGET", "자동차 부품 제조업", m01 = null)

        val target = PeerBenchmarkEngine.enrich(peers + unavailableTarget).first { it.issuerId == "TARGET" }
        val peer = target.m01RevGrowth.peerComparison!!

        assertEquals(PeerGroupBasis.KRX_EXACT_SECTOR, peer.basis)
        assertEquals(4.5, peer.median!!, 0.0000001)
        assertNull(peer.deltaFromMedian)
        assertNull(peer.relativeToMedianPct)
        assertNull(peer.betterThanMedian)
    }

    @Test
    fun heterogeneousOtherSectorFailsClosedInsteadOfInventingMedian() {
        val stocks = (1..30).map { i ->
            stock("X%05d".format(i), "기타", m01 = i.toDouble())
        }

        val enriched = PeerBenchmarkEngine.enrich(stocks)
        enriched.forEach { stock ->
            val peer = stock.m01RevGrowth.peerComparison!!
            assertEquals(PeerGroupBasis.INSUFFICIENT, peer.basis)
            assertFalse(peer.isSufficient)
            assertNull(peer.median)
            assertNotNull(peer.reason)
        }
    }

    @Test
    fun familyBelowMinimumAlsoFailsClosed() {
        val stocks = (1 until PeerBenchmarkEngine.MIN_FAMILY_SAMPLE).map { i ->
            stock("S%05d".format(i), if (i % 2 == 0) "소프트웨어 개발 및 공급업" else "컴퓨터 프로그래밍 서비스업", m01 = i.toDouble())
        }

        val enriched = PeerBenchmarkEngine.enrich(stocks)
        val target = enriched.first()
        val peer = target.m01RevGrowth.peerComparison!!
        assertEquals(PeerGroupBasis.INSUFFICIENT, peer.basis)
        assertNull(peer.median)
    }

    @Test
    fun duplicateIssueCodesAreRejected() {
        val a = stock("DUP001", "의약품 제조업", m01 = 1.0)
        val b = stock("DUP001", "의약품 제조업", m01 = 2.0)
        try {
            PeerBenchmarkEngine.enrich(listOf(a, b))
            fail("duplicate codes should fail closed")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun stock(
        code: String,
        sector: String,
        m01: Double? = 10.0,
        m02: Double? = 10.0,
        m03: Double? = 10.0,
        m04: Double? = 10.0
    ): StockSummary = StockSummary(
        issuerId = code,
        name = code,
        market = "KOSDAQ",
        sector = sector,
        listingDate = "2020-01-01",
        isFinancial = false,
        isLossMaking = m03 == null,
        m01RevGrowth = metric("M01", m01, "%"),
        m02OpMargin = metric("M02", m02, "%"),
        m03Per = metric("M03", m03, "배"),
        m04Price6m = metric("M04", m04, "%"),
        compositeScore = null,
        rankOrder = null,
        isCompositeComplete = false,
        asOfDate = "2026-09-15",
        dataSource = "test",
        status = DataStatus.REAL
    )

    private fun metric(id: String, raw: Double?, unit: String): MetricValue = MetricValue(
        id = id,
        nameKo = id,
        rawValue = raw,
        percentileScore = raw?.let { 50.0 },
        unit = unit,
        isAvailable = raw != null,
        reason = if (raw == null) "TEST_MISSING" else null,
        description = "test",
        interpretation = "test",
        caution = "test",
        source = "test",
        basis = if (raw != null) "test" else "",
        asOfDate = "2026-09-15"
    )
}
