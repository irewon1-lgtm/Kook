package com.krstock.v3.data.price

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import org.json.JSONArray
import java.security.MessageDigest

class PriceAdjustmentProvenanceTest {
    @Test fun everyResultExposesAdjustmentProvenanceSeparatelyFromNetworkSource() {
        for (source in PriceHistoryRepository.Source.entries) {
            val result = PriceHistoryRepository.Result(emptyList(), source, LocalDate.parse("2026-11-04"), "source detail")
            assertEquals(PriceAdjustmentProvenance.Status.UNVERIFIED, result.adjustment.status)
            assertEquals("수정주가 미확인", result.adjustment.label)
            assertTrue(result.adjustment.reason.contains("공식 기업행사별 적용일·조정계수"))
            assertTrue(result.adjustment.reason.contains("기수정 여부"))
            assertEquals("제공 원본 OHLC 유지 · 추가 조정 없음", result.adjustment.treatment)
            assertTrue(result.adjustment.priceSourceUrl.startsWith("https://m.stock.naver.com/api/stock/"))
            assertEquals("가격: 네이버증권 · 공식 수정계수: 미확보", result.adjustment.sourceDetail)
        }
    }

    @Test fun actualSplitAndReverseSplitKeepEveryProvidedOhlcFieldAndVolumeExactly() {
        for ((code, hash) in mapOf(
            "134380" to "6dc38450fbd9246f1e7e1216568ee4809111dac1b0b85577729783323d9dba59",
            "331520" to "b4f7748f58fc5f6f28264b4d60adbac652ea15ca51a0478dcba54b4a8c86b6bd",
        )) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/prices_20261105/naver_${code}_prices.json")).readBytes()
            assertEquals("Original recorded source response", hash,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val payload = bytes.toString(Charsets.UTF_8)
            val source = JSONArray(payload)
            val bars = PriceHistoryRepository.parseBars(payload, LocalDate.parse("2026-11-04"))
            for (bar in bars) {
                val row = (0 until source.length()).map { source.getJSONObject(it) }
                    .single { it.getString("localTradedAt") == bar.date.toString() }
                fun price(key: String) = row.getString(key).replace(",", "").toDouble()
                assertEquals(price("openPrice"), bar.open, 0.0)
                assertEquals(price("highPrice"), bar.high, 0.0)
                assertEquals(price("lowPrice"), bar.low, 0.0)
                assertEquals(price("closePrice"), bar.close, 0.0)
                assertEquals(row.getLong("accumulatedTradingVolume"), bar.volume)
            }
            val beforeDate = if (code == "134380") "2026-03-30" else "2026-04-07"
            val afterDate = if (code == "134380") "2026-04-13" else "2026-04-30"
            assertEquals(if (code == "134380") 118600.0 else 524.0,
                bars.single { it.date.toString() == beforeDate }.close, 0.0)
            assertEquals(if (code == "134380") 11000.0 else 2615.0,
                bars.single { it.date.toString() == afterDate }.close, 0.0)
            // Suspended dates report zero O/H/L: they cannot become fabricated candles.
            assertFalse(bars.any { it.date.toString() == if (code == "134380") "2026-04-10" else "2026-04-29" })
            for (interval in PriceHistoryAggregation.Interval.entries) {
                val candles = PriceHistoryAggregation.aggregate(bars, interval)
                for (candle in candles) {
                    val members = bars.filter { it.date >= candle.firstTradingDate && it.date <= candle.lastTradingDate }
                    assertEquals(members.first().open, candle.open, 0.0)
                    assertEquals(members.maxOf { it.high }, candle.high, 0.0)
                    assertEquals(members.minOf { it.low }, candle.low, 0.0)
                    assertEquals(members.last().close, candle.close, 0.0)
                    assertEquals(members.sumOf { requireNotNull(it.volume) }, candle.volume)
                }
            }
        }
    }
}
