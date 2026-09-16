package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.FinalCandidateRecord

/**
 * In-memory store for the CI-validated stage 4~7 shortlist.
 * A shortlist is visible only when its snapshot date exactly matches the active
 * quantitative snapshot. This prevents mixing stale safety data with newer M01~M04.
 */
object FinalCandidateRuntimeStore {
    @Volatile private var snapshotDate: String = ""
    @Volatile private var rows: List<FinalCandidateRecord> = emptyList()

    @Synchronized
    fun install(newSnapshotDate: String, newRows: List<FinalCandidateRecord>) {
        require(newSnapshotDate.isNotBlank()) { "final-candidate snapshot date is blank" }
        require(newRows.size <= 100) { "too many final candidates: ${newRows.size}" }
        require(newRows.map { it.issuerId }.toSet().size == newRows.size) { "duplicate final-candidate code" }
        require(newRows.map { it.candidateRank } == (1..newRows.size).toList()) { "candidate rank sequence mismatch" }
        require(newRows.all { it.financialSafetyStatus == "PASS" }) { "non-PASS financial safety row" }
        snapshotDate = newSnapshotDate
        rows = newRows.toList()
    }

    @Synchronized
    fun clear() {
        snapshotDate = ""
        rows = emptyList()
    }

    fun current(expectedSnapshotDate: String): List<FinalCandidateRecord> =
        if (snapshotDate == expectedSnapshotDate) rows else emptyList()

    fun installedSnapshotDate(): String = snapshotDate
}
