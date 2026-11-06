package oracle.price

import com.krstock.v3.data.price.PriceHistoryAggregation
import com.krstock.v3.data.price.PriceHistoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Execute the unmodified Android parser and projection methods against recorded source data. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: PriceOracleKt manifest.json output.json" }
    val manifest = JSONObject(File(args[0]).readText())
    val cases = manifest.getJSONArray("cases")
    val exported = JSONArray()
    for (index in 0 until cases.length()) {
        val sample = cases.getJSONObject(index)
        val parsed = PriceHistoryRepository.parseBars(
            sample.getJSONArray("rawRows").toString(), LocalDate.parse(sample.getString("cutoff")),
        )
        val parsedDaily = JSONArray()
        parsed.forEach { bar ->
            parsedDaily.put(JSONObject().put("date", bar.date.toString())
                .put("open", bar.open).put("high", bar.high).put("low", bar.low).put("close", bar.close)
                .put("volume", bar.volume ?: JSONObject.NULL))
        }
        val projections = JSONObject()
        for (interval in PriceHistoryAggregation.Interval.entries) for (months in listOf(1, 3, 6, 12)) {
            val candles = JSONArray()
            PriceHistoryAggregation.visible(parsed, interval, months).forEach { candle ->
                candles.put(JSONObject()
                    .put("firstTradingDate", candle.firstTradingDate.toString())
                    .put("lastTradingDate", candle.lastTradingDate.toString())
                    .put("periodStart", candle.periodStart.toString()).put("periodEnd", candle.periodEnd.toString())
                    .put("open", candle.open).put("high", candle.high).put("low", candle.low).put("close", candle.close)
                    .put("volume", candle.volume ?: JSONObject.NULL).put("tradingDays", candle.tradingDays))
            }
            // Candle and line have identical source projection; only the plot renderer differs.
            for (mode in listOf("candle", "line")) projections.put("${interval.name}/$months/$mode", candles)
        }
        sample.remove("rawRows")
        sample.put("parsedDaily", parsedDaily).put("projections", projections)
        exported.put(sample)
    }
    manifest.put("cases", exported)
    File(args[1]).writeText(manifest.toString(2) + "\n")
    println("Original Kotlin parseBars/visible exported ${cases.length()} price cases")
}
