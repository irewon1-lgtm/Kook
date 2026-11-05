package com.krstock.v3.data.stage

import com.krstock.v3.data.model.FinancialSafetyFacts
import org.json.JSONObject
import java.time.LocalDate
import java.net.URI
import java.math.BigInteger
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/** Checks official-fact schema independently of the four-metric parser. */
internal object VerifiedFinancialFactsParser {
    private val amountKeys = listOf("assets", "liabilities", "equity", "current_assets", "current_liabilities")
    private val currencies = setOf("KRW", "USD", "CNY", "JPY", "EUR", "HKD", "TWD", "SGD", "GBP")

    fun parse(row: JSONObject, code: String, snapshotDate: String): FinancialSafetyFacts {
        require(row.getString("issuer_code") == code) { "financial fact issuer mismatch" }
        require(row.getString("issuer_name").isNotBlank()) { "financial fact issuer name missing" }
        val amounts = amountKeys.associateWith { key ->
            if (row.isNull(key)) null else try {
                row.get(key).toString().toBigDecimal().longValueExact()
            } catch (_: Exception) {
                throw IllegalArgumentException("$code $key must be an exact integer")
            }
        }
        val issues = row.getJSONArray("validation_issues").let { array ->
            (0 until array.length()).map { array.getString(it).also { issue -> require(issue.isNotBlank()) } }
        }
        val facts = FinancialSafetyFacts(
            amounts, row.getString("currency"), row.getString("report_period"),
            row.getString("filing_date"), row.getString("corp_code"), row.getString("receipt_no"),
            row.getString("source_url"), row.getString("source_file"), row.getString("source_zip_sha256"),
            row.getString("correction_status"), row.getString("correction_check"),
            row.getBoolean("facts_verified"), issues,
            row.optBoolean("values_reconciled", false),
            row.optJSONObject("correction_reconciliation")?.optString("source_api_sha256", "") ?: "",
            row.optString("reconciliation_error", ""),
        )
        require(row.getString("unit") == facts.currency) { "financial currency/unit mismatch" }
        val status = row.getString("status")
        if (status in setOf("PASS", "FAIL")) require(facts.verified) { "unverified facts cannot pass/fail" }
        if (status == "HOLD") require(!facts.verified && issues.isNotEmpty()) { "HOLD needs explicit verification issues" }
        if (facts.verified) {
            require(issues.isEmpty()) { "verified financial facts have unresolved issues" }
            require(facts.currency in currencies) { "financial currency/unit unknown" }
            require(facts.corporationCode.matches(Regex("[0-9]{8}"))) { "official corporation mapping missing" }
            require(facts.receiptNo.matches(Regex("[0-9]{14}"))) { "official filing receipt missing" }
            val period = LocalDate.parse(facts.reportPeriod)
            val filing = LocalDate.parse(facts.filingDate)
            require(period <= filing && filing <= LocalDate.parse(snapshotDate)) { "financial period/filing date mismatch" }
            require(facts.receiptNo.startsWith(facts.filingDate.replace("-", ""))) { "receipt/filing date mismatch" }
            require(facts.sourceUrl == "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + facts.receiptNo) {
                "financial filing source mismatch"
            }
            require(facts.sourceFile.matches(Regex("[0-9]{4}_[1-4]Q_BS_[0-9]{14}\\.zip"))) { "official BS source missing" }
            require(facts.sourceZipSha256.matches(Regex("[a-f0-9]{64}"))) { "official BS checksum missing" }
            if (facts.correctionStatus != "ORIGINAL") {
                require(facts.correctionStatus == "LATEST_CORRECTION" && facts.correctionReconciled) {
                    "unreconciled correction cannot supply verified facts"
                }
                validateCorrectionProof(row, facts)
            }
            require(facts.correctionCheck.isNotBlank()) { "latest final-report check missing" }
            if (status in setOf("PASS", "FAIL")) validateClassification(row, amounts)
        }
        return facts
    }

    private fun validateCorrectionProof(row: JSONObject, facts: FinancialSafetyFacts) {
        val proof = row.getJSONObject("correction_reconciliation")
        require(proof.getString("receipt_no") == facts.receiptNo &&
            proof.getString("corp_code") == facts.corporationCode &&
            proof.getString("report_period") == facts.reportPeriod &&
            proof.getString("scope") == row.getString("scope") &&
            proof.getString("currency") == facts.currency) { "correction proof identity/period/scope mismatch" }
        require(proof.optString("validation_error", "").isBlank() &&
            proof.optJSONObject("mismatches")?.length() == 0) { "correction proof contains counterevidence" }
        for ((key, expected) in listOf("observed_receipt_nos" to facts.receiptNo,
            "observed_corp_codes" to facts.corporationCode, "observed_currencies" to facts.currency)) {
            val observed = proof.getJSONArray(key)
            require(observed.length() == 1 && observed.getString(0) == expected) { "correction observed identity mismatch" }
        }
        val year = facts.sourceFile.substring(0, 4)
        val reportCode = mapOf('1' to "11013", '2' to "11012", '3' to "11014", '4' to "11011").getValue(facts.sourceFile[5])
        val expectedParams = mapOf("corp_code" to facts.corporationCode, "bsns_year" to year,
            "reprt_code" to reportCode, "fs_div" to row.getString("scope"))
        val params = proof.getJSONObject("source_api_params")
        require(params.keys().asSequence().toSet() == expectedParams.keys &&
            expectedParams.all { (key, value) -> params.getString(key) == value } &&
            proof.getString("bsns_year") == year && proof.getString("reprt_code") == reportCode) { "correction API query mismatch" }
        require(facts.correctionProofSha256.matches(Regex("[a-f0-9]{64}"))) { "correction original response checksum missing" }
        val source = URI(proof.getString("source_api_url"))
        require(source.scheme == "https" && source.host == "opendart.fss.or.kr" &&
            source.path == "/api/fnlttSinglAcntAll.json" && source.userInfo == null &&
            source.port == -1 && source.fragment == null) { "correction API source invalid" }
        if (!source.rawQuery.isNullOrEmpty()) {
            val actualQuery = source.rawQuery.split("&").map { it.split("=", limit = 2) }
            require(actualQuery.all { it.size == 2 } && actualQuery.size == expectedParams.size &&
                actualQuery.associate { it[0] to it[1] } == expectedParams) { "correction API source query mismatch" }
        }
        val amounts = proof.getJSONObject("amounts")
        require(amounts.keys().asSequence().toSet() == amountKeys.toSet()) { "correction proof requires all five actual amounts" }
        for (key in amountKeys) {
            val amount = try { amounts.get(key).toString().toBigDecimal().longValueExact() }
                catch (_: Exception) { throw IllegalArgumentException("correction amount is not an exact integer") }
            require(facts.amounts[key] != null && facts.amounts[key] == amount) { "corrected amount does not match source observation" }
            require(proof.getJSONObject("bulk_amounts").get(key).toString().toBigDecimal().longValueExact() == amount) {
                "correction original amount mismatch"
            }
        }
    }

    private fun validateClassification(row: JSONObject, amounts: Map<String, Long?>) {
        require(amounts.values.all { it != null }) { "verified safety requires all five actual amounts" }
        val a = requireNotNull(amounts["assets"]).toDouble()
        val l = requireNotNull(amounts["liabilities"]).toDouble()
        val e = requireNotNull(amounts["equity"]).toDouble()
        val ca = requireNotNull(amounts["current_assets"]).toDouble()
        val cl = requireNotNull(amounts["current_liabilities"]).toDouble()
        require(a > 0 && l >= 0 && ca >= 0 && cl >= 0) { "invalid balance-sheet amount" }
        // Match the producer's exact integer subtraction before division. Do
        // not grant an extra policy margin to compensate for Double rounding.
        val exactGap = BigInteger.valueOf(requireNotNull(amounts["assets"]))
            .subtract(BigInteger.valueOf(requireNotNull(amounts["liabilities"]))
                .add(BigInteger.valueOf(requireNotNull(amounts["equity"])))).abs()
        val identity = sourcePercent(exactGap, requireNotNull(amounts["assets"]))
        require(identity <= 5.0) { "actual accounting identity violates policy" }
        close(row, "identity_gap_pct", identity)
        val reason = if (e <= 0) {
            require(row.isNull("debt_to_equity_pct") && row.isNull("current_ratio_pct"))
            "NONPOSITIVE_EQUITY"
        } else {
            require(cl > 0) { "current liabilities denominator is not valid" }
            val debt = sourcePercent(BigInteger.valueOf(requireNotNull(amounts["liabilities"])), requireNotNull(amounts["equity"]))
            val current = sourcePercent(BigInteger.valueOf(requireNotNull(amounts["current_assets"])), requireNotNull(amounts["current_liabilities"]))
            close(row, "debt_to_equity_pct", debt)
            close(row, "current_ratio_pct", current)
            when {
                debt > 400 -> "DEBT_TO_EQUITY_OVER_400"
                current < 70 -> "CURRENT_RATIO_UNDER_70"
                else -> "PASS"
            }
        }
        require(row.getString("reason") == reason) { "financial reason disagrees with actual amounts" }
        require(row.getString("status") == if (reason == "PASS") "PASS" else "FAIL") {
            "financial status disagrees with actual amounts"
        }
    }

    private fun close(row: JSONObject, key: String, expected: Double) {
        require(!row.isNull(key) && row.getDouble(key).isFinite() &&
            abs(row.getDouble(key) - expected) <= 0.0000011) { "financial $key disagrees with actual amounts" }
    }

    // Python divides the original integers before rounding to Double. Keep the
    // rational quotient sufficiently precise so large Long values preserve the
    // same boundary decision; do not round either input before division.
    private fun sourcePercent(numerator: BigInteger, denominator: Long): Double =
        numerator.toBigDecimal().divide(BigDecimal.valueOf(denominator), MathContext(50)).toDouble() * 100.0
}
