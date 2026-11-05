package com.krstock.v3.data.price

import com.krstock.v3.ui.components.chartCandleWidth
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceChartGeometryTest {
    @Test fun firstAndLastCandleBodiesFitInsideTheCanvasEvenWithOnlyTwoMonths() {
        val canvas = 320f
        val padding = 7f
        val usable = canvas - 2 * padding
        for (count in listOf(1,2,5,7,13,53,245)) {
            val width = chartCandleWidth(usable,count,padding,1f)
            val firstCenter = if (count==1) canvas/2 else padding
            val lastCenter = if (count==1) canvas/2 else canvas-padding
            assertTrue("$count bars: left edge must stay on canvas",firstCenter-width/2>=0f)
            assertTrue("$count bars: right edge must stay on canvas",lastCenter+width/2<=canvas)
            assertTrue("Candles remain visible",width>=1f)
        }
    }
}
