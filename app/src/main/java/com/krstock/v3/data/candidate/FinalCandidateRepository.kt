package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.repository.StockRepository

/**
 * App bridge for the stage-7 remote standard format.
 * No legacy fallback is used: if stage-4 safety data is missing or stale, the
 * app fails closed and shows no "final" candidates rather than silently using
 * the older 4-metric-only policy.
 */
object FinalCandidateRepository {
    fun getFinalCandidates(limit: Int = FinalCandidateEngine.DEFAULT_LIMIT): List<FinalCandidateRecord> {
        require(limit in 1..100) { "Final-candidate limit must be between 1 and 100" }
        return FinalCandidateRuntimeStore.current(StockRepository.quantSnapshotDate()).take(limit)
    }
}
