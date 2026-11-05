package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.stage.FinancialSafetyRuntimeStore

/**
 * In-memory store for the CI-validated stage 4~7 shortlist.
 * A shortlist is visible only when its snapshot date exactly matches the active
 * quantitative snapshot and every issuer still has a PASS in current Stage4.
 * This prevents a cached same-date shortlist from bypassing a missing safety receive.
 */
object FinalCandidateRuntimeStore {
    private data class Installed(val snapshotDate: String, val rows: List<FinalCandidateRecord>)
    @Volatile private var installed: Installed? = null

    @Synchronized
    fun install(newSnapshotDate: String, newRows: List<FinalCandidateRecord>) {
        require(newSnapshotDate.isNotBlank()) { "final-candidate snapshot date is blank" }
        require(newRows.size <= 100) { "too many final candidates: ${newRows.size}" }
        require(newRows.map { it.issuerId }.toSet().size == newRows.size) { "duplicate final-candidate code" }
        require(newRows.map { it.candidateRank } == (1..newRows.size).toList()) { "candidate rank sequence mismatch" }
        require(newRows.all { it.financialSafetyStatus == "PASS" }) { "non-PASS financial safety row" }
        installed = Installed(newSnapshotDate, newRows.toList())
    }

    @Synchronized
    fun clear() {
        installed = null
    }

    fun current(expectedSnapshotDate: String): List<FinalCandidateRecord> {
        val snapshot = installed?.takeIf { it.snapshotDate == expectedSnapshotDate } ?: return emptyList()
        val safety = FinancialSafetyRuntimeStore.current(expectedSnapshotDate) ?: return emptyList()
        return snapshot.rows.takeIf { rows ->
            rows.all { safety.records[it.issuerId]?.status == FinancialSafetyStatus.PASS }
        }.orEmpty()
    }

    fun installedSnapshotDate(): String = installed?.snapshotDate.orEmpty()
}
