package com.krstock.v3.data.stage

import com.krstock.v3.data.model.FinancialSafetyFacts
import org.json.JSONObject
import java.time.LocalDate
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
            require(facts.correctionStatus == "ORIGINAL") { "unreconciled correction cannot supply verified facts" }
            require(facts.correctionCheck.isNotBlank()) { "latest final-report check missing" }
            if (status in setOf("PASS", "FAIL")) validateClassification(row, amounts)
        }
        return facts
    }

    private fun validateClassification(row: JSONObject, amounts: Map<String, Long?>) {
        require(amounts.values.all { it != null }) { "verified safety requires all five actual amounts" }
        val a = requireNotNull(amounts["assets"]).toDouble()
        val l = requireNotNull(amounts["liabilities"]).toDouble()
        val e = requireNotNull(amounts["equity"]).toDouble()
        val ca = requireNotNull(amounts["current_assets"]).toDouble()
        val cl = requireNotNull(amounts["current_liabilities"]).toDouble()
        require(a > 0 && l >= 0 && ca >= 0 && cl >= 0) { "invalid balance-sheet amount" }
        val identity = abs(a - (l + e)) / a * 100
        require(identity <= 5.0 + 1e-9) { "actual accounting identity violates policy" }
        close(row, "identity_gap_pct", identity)
        val reason = if (e <= 0) {
            require(row.isNull("debt_to_equity_pct") && row.isNull("current_ratio_pct"))
            "NONPOSITIVE_EQUITY"
        } else {
            require(cl > 0) { "current liabilities denominator is not valid" }
            val debt = l / e * 100
            val current = ca / cl * 100
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
}
