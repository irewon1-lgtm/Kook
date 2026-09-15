package com.krstock.v3.data.evidence

import com.krstock.v3.data.analysis.IndustryKpiCatalog
import com.krstock.v3.data.analysis.ResearchForensicsEngine
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.ResearchForensics
import com.krstock.v3.data.model.StockSummary
import java.util.concurrent.ConcurrentHashMap

object ResearchForensicsRepository {
    private val cache = ConcurrentHashMap<String, ResearchForensics>()

    suspend fun load(stock: StockSummary, evidence: EvidenceBundle): ResearchForensics {
        val key = buildString {
            append(stock.issuerId).append('|').append(stock.asOfDate).append('|')
            evidence.disclosures.take(6).forEach {
                append(it.receiptNo.ifBlank { it.id }).append(':').append(it.publishedAt).append('|')
            }
        }
        cache[key]?.let { return it }

        val kpis = IndustryKpiCatalog.forSector(stock.sector)
        val primary = DartPrimarySourceRepository.load(stock.issuerId, evidence.disclosures, kpis)
        val result = ResearchForensicsEngine.synthesize(stock, evidence, primary, kpis)
        cache[key] = result
        return result
    }
}
