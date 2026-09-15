package com.krstock.v3.data.repository

import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.PeerGroupBasis
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.peer.PeerBenchmarkEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PeerBenchmarkRepositoryExtremeTest {

    private val stocks = StockRepository.getAllStocks()

    @Test
    fun everyMetricHasTransparentFailClosedPeerMetadata() {
        var sufficient = 0
        var insufficient = 0
        var available = 0
        var availableWithPeer = 0

        stocks.forEach { stock ->
            metrics(stock).forEach { metric ->
                val peer = metric.peerComparison
                assertNotNull("missing peer metadata ${stock.issuerId}/${metric.id}", peer)
                peer!!
                assertTrue(peer.groupLabel.isNotBlank())
                assertTrue(peer.sampleSize in 0..stocks.size)
                assertEquals(metric.id != "M03", peer.higherIsBetter)

                if (metric.isAvailable) available++

                if (peer.isSufficient) {
                    sufficient++
                    assertNull(peer.reason)
                    assertNotNull(peer.median)
                    assertTrue(peer.median!!.isFinite())
                    when (peer.basis) {
                        PeerGroupBasis.KRX_EXACT_SECTOR ->
                            assertTrue(peer.sampleSize >= PeerBenchmarkEngine.MIN_EXACT_SAMPLE)
                        PeerGroupBasis.STANDARD_SECTOR_FAMILY ->
                            assertTrue(peer.sampleSize >= PeerBenchmarkEngine.MIN_FAMILY_SAMPLE)
                        PeerGroupBasis.INSUFFICIENT -> fail("sufficient peer marked insufficient")
                    }
                    if (metric.isAvailable) {
                        availableWithPeer++
                        assertNotNull(peer.deltaFromMedian)
                        assertTrue(peer.deltaFromMedian!!.isFinite())
                        peer.relativeToMedianPct?.let { assertTrue(it.isFinite()) }
                    } else {
                        assertNull(peer.deltaFromMedian)
                        assertNull(peer.betterThanMedian)
                    }
                } else {
                    insufficient++
                    assertEquals(PeerGroupBasis.INSUFFICIENT, peer.basis)
                    assertNull(peer.median)
                    assertNull(peer.deltaFromMedian)
                    assertNull(peer.relativeToMedianPct)
                    assertNull(peer.betterThanMedian)
                    assertFalse(peer.reason.isNullOrBlank())
                }
            }
        }

        assertEquals(stocks.size * 4, sufficient + insufficient)
        assertTrue("peer coverage too low: $availableWithPeer/$available", availableWithPeer * 100 >= available * 70)
        println("PEER_MEDIAN_COVERAGE available=$available withPeer=$availableWithPeer sufficientCells=$sufficient insufficientCells=$insufficient")
    }

    @Test
    fun exactSectorPeerLabelsMatchRawKrxSector() {
        stocks.forEach { stock ->
            metrics(stock).forEach { metric ->
                val peer = metric.peerComparison!!
                if (peer.basis == PeerGroupBasis.KRX_EXACT_SECTOR) {
                    assertEquals(stock.sector.trim().replace(Regex("\\s+"), " "), peer.groupLabel)
                }
            }
        }
    }

    @Test
    fun betterThanMedianDirectionIsCorrectForEveryAvailableComparison() {
        stocks.forEach { stock ->
            metrics(stock).forEach { metric ->
                val raw = metric.rawValue
                val peer = metric.peerComparison!!
                val median = peer.median
                if (raw != null && median != null && kotlin.math.abs(raw - median) > 1e-12) {
                    val expected = if (metric.id == "M03") raw < median else raw > median
                    assertEquals("direction mismatch ${stock.issuerId}/${metric.id}", expected, peer.betterThanMedian)
                    assertEquals(raw - median, peer.deltaFromMedian!!, 0.0000001)
                }
            }
        }
    }

    @Test
    fun peerEngineIsOrderInvariantAcrossTwelveFullUniversePermutations() {
        val baseline = peerFingerprint(PeerBenchmarkEngine.enrich(stocks))
        repeat(12) { seed ->
            val shuffled = stocks.shuffled(Random(seed + 9173))
            val rerun = PeerBenchmarkEngine.enrich(shuffled)
            assertEquals("peer output changed with input ordering seed=$seed", baseline, peerFingerprint(rerun))
        }
    }

    @Test
    fun repositoryReadsKeepPeerMetadataStableTenThousandTimes() {
        val first = StockRepository.getAllStocks()
        val baseline = peerFingerprint(first)
        repeat(10_000) {
            val current = StockRepository.getAllStocks()
            assertSame(first, current)
        }
        assertEquals(baseline, peerFingerprint(StockRepository.getAllStocks()))
    }

    private fun metrics(stock: StockSummary): List<MetricValue> = listOf(
        stock.m01RevGrowth,
        stock.m02OpMargin,
        stock.m03Per,
        stock.m04Price6m
    )

    private fun peerFingerprint(input: List<StockSummary>): String = input
        .sortedBy { it.issuerId }
        .joinToString("|") { stock ->
            metrics(stock).joinToString(";") { metric ->
                val p = metric.peerComparison!!
                listOf(
                    stock.issuerId,
                    metric.id,
                    p.basis.name,
                    p.groupLabel,
                    p.sampleSize.toString(),
                    p.median?.toString() ?: "NA",
                    p.deltaFromMedian?.toString() ?: "NA",
                    p.betterThanMedian?.toString() ?: "NA"
                ).joinToString(":")
            }
        }
}
