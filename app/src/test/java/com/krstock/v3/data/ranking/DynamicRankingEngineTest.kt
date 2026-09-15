package com.krstock.v3.data.ranking

import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.repository.StockRepository
import org.junit.Assert.*
import org.junit.Test

class DynamicRankingEngineTest {

    private val stocks = StockRepository.getAllStocks()

    @Test
    fun allFourSelectionExactlyReproducesCommittedCompositeAndRanks() {
        val result = DynamicRankingEngine.rank(stocks, RankMetric.allIds)
        val eligible = result.values.filter { it.isEligible }
        assertEquals(1462, eligible.size)
        stocks.forEach { stock ->
            val dynamic = result.getValue(stock.issuerId)
            assertEquals(stock.isCompositeComplete, dynamic.isEligible)
            assertEquals(stock.rankOrder, dynamic.rank)
            if (stock.compositeScore == null) {
                assertNull(dynamic.score)
            } else {
                assertEquals(stock.compositeScore!!, dynamic.score!!, 0.000001)
            }
        }
    }

    @Test
    fun eachSingleMetricSelectionMatchesAuditedCoverageExactly() {
        val expected = mapOf(
            "M01" to GeneratedRealQuantSnapshot.m01Available,
            "M02" to GeneratedRealQuantSnapshot.m02Available,
            "M03" to GeneratedRealQuantSnapshot.m03Available,
            "M04" to GeneratedRealQuantSnapshot.m04Available
        )
        expected.forEach { (metricId, count) ->
            val result = DynamicRankingEngine.rank(stocks, setOf(metricId))
            assertEquals(count, result.values.count { it.isEligible })
            result.values.filter { it.isEligible }.forEach { ranked ->
                val stock = stocks.single { it.issuerId == ranked.issuerId }
                val metric = DynamicRankingEngine.metric(stock, metricId)
                assertEquals(metric.percentileScore!!, ranked.score!!, 0.000001)
            }
        }
    }

    @Test
    fun everyNonEmptySubsetOfFourMetricsProducesValidContiguousRanks() {
        val ids = listOf("M01", "M02", "M03", "M04")
        for (mask in 1 until (1 shl ids.size)) {
            val selected = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val result = DynamicRankingEngine.rank(stocks, selected)
            assertEquals(2649, result.size)
            val ranked = result.values.filter { it.rank != null }.sortedBy { it.rank }
            assertEquals((1..ranked.size).toList(), ranked.map { it.rank })
            ranked.zipWithNext().forEach { (a, b) ->
                assertTrue("score inversion $selected ${a.issuerId}/${b.issuerId}", a.score!! >= b.score!!)
            }
            result.values.forEach { row ->
                assertEquals(selected.size, row.selectedMetricCount)
                assertEquals(row.score != null, row.isEligible)
                assertEquals(row.score != null, row.rank != null)
            }
        }
    }

    @Test
    fun selectedMetricsMustAllExistForEligibility() {
        val selected = setOf("M01", "M03", "M04")
        val result = DynamicRankingEngine.rank(stocks, selected)
        stocks.forEach { stock ->
            val expectedEligible = selected.all {
                val metric = DynamicRankingEngine.metric(stock, it)
                metric.isAvailable && metric.percentileScore != null
            }
            assertEquals(expectedEligible, result.getValue(stock.issuerId).isEligible)
        }
    }

    @Test
    fun twoAndThreeMetricScoresAreEqualWeightAveragesOnly() {
        listOf(setOf("M01", "M03"), setOf("M01", "M03", "M04")).forEach { selected ->
            val result = DynamicRankingEngine.rank(stocks, selected)
            result.values.filter { it.isEligible }.take(300).forEach { ranked ->
                val stock = stocks.single { it.issuerId == ranked.issuerId }
                val expected = selected.map { DynamicRankingEngine.metric(stock, it).percentileScore!! }.average()
                assertEquals(expected, ranked.score!!, 0.000001)
            }
        }
    }

    @Test
    fun removingMetricsNeverRequiresUnselectedMissingData() {
        val four = DynamicRankingEngine.rank(stocks, RankMetric.allIds).values.count { it.isEligible }
        val three = DynamicRankingEngine.rank(stocks, setOf("M01", "M03", "M04")).values.count { it.isEligible }
        val two = DynamicRankingEngine.rank(stocks, setOf("M01", "M04")).values.count { it.isEligible }
        val one = DynamicRankingEngine.rank(stocks, setOf("M04")).values.count { it.isEligible }
        assertEquals(1462, four)
        assertTrue(three >= four)
        assertTrue(two >= three)
        assertTrue(one >= two)
        assertEquals(GeneratedRealQuantSnapshot.m04Available, one)
    }

    @Test
    fun rankingIsInvariantToInputOrder() {
        val selections = listOf(
            setOf("M01"),
            setOf("M01", "M02"),
            setOf("M01", "M03", "M04"),
            RankMetric.allIds
        )
        selections.forEach { selected ->
            val normal = DynamicRankingEngine.rank(stocks, selected)
            val reversed = DynamicRankingEngine.rank(stocks.reversed(), selected)
            assertEquals(normal, reversed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroSelectedMetricsIsRejected() {
        DynamicRankingEngine.rank(stocks, emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownMetricIsRejected() {
        DynamicRankingEngine.rank(stocks, setOf("M99"))
    }
}
