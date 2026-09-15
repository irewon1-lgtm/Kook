package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.repository.StockRepository

/**
 * App bridge for the persisted stage-7 format.
 * The installed app derives the same shortlist from the active runtime quant snapshot,
 * so normal remote data refreshes do not require a new APK.
 */
object FinalCandidateRepository {
    fun getFinalCandidates(limit: Int = FinalCandidateEngine.DEFAULT_LIMIT): List<FinalCandidateRecord> =
        FinalCandidateEngine.select(StockRepository.getAllStocks(), limit)
}
