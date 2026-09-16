package com.krstock.v3.data.stage

import android.content.Context
import com.krstock.v3.data.repository.StockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Fail-closed app bridge for the Stage-4 financial-safety section embedded in
 * evidence/real_quant_snapshot.json. It is deliberately separate from the four
 * ranking metrics: safety and valuation display must never mutate the KR4 rank.
 */
data class FinancialSafetyRecord(
    val issuerId: String,
    val status: String,
    val reason: String,
    val scope: String,
    val debtToEquityPct: Double?,
    val currentRatioPct: Double?,
    val accountingIdentityGapPct: Double?,
    val basis: String,
)

data class FinancialSafetyCoverage(
    val pass: Int,
    val fail: Int,
    val hold: Int,
    val notApplicable: Int,
    val total: Int,
)

object Stage4567SnapshotRepository {
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/irewon1-lgtm/Kook/main/evidence/real_quant_snapshot.json"
    private const val CACHE_NAME = "kr4_stage4567_safety_v1.json"
    private const val TEMP_NAME = "kr4_stage4567_safety_v1.tmp"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val POLICY_VERSION = "STAGE4_BS_SAFETY_V1"

    @Volatile private var installedSnapshotDate: String = ""
    @Volatile private var installedRows: Map<String, FinancialSafetyRecord> = emptyMap()
    @Volatile private var installedCoverage: FinancialSafetyCoverage? = null

    private data class Parsed(
        val snapshotDate: String,
        val rows: Map<String, FinancialSafetyRecord>,
        val coverage: FinancialSafetyCoverage,
    )

    suspend fun bootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
        refreshBlocking(context.applicationContext)
    }

    @Synchronized
    internal fun refreshBlocking(context: Context): Boolean {
        val expectedSnapshot = StockRepository.quantSnapshotDate()
        val cache = File(context.filesDir, CACHE_NAME)
        var installed = false

        if (cache.isFile && cache.length() in 1..MAX_BYTES.toLong()) {
            runCatching { parseAndValidate(cache.readText(Charsets.UTF_8), expectedSnapshot) }
                .onSuccess {
                    install(it)
                    installed = true
                }
        }

        val network = runCatching {
            val body = download()
            val parsed = parseAndValidate(body, expectedSnapshot)
            saveAtomically(context.filesDir, body)
            install(parsed)
            true
        }.getOrDefault(false)

        if (!network && !installed) clear()
        return network || installed
    }

    fun current(expectedSnapshotDate: String): Map<String, FinancialSafetyRecord> =
        if (installedSnapshotDate == expectedSnapshotDate) installedRows else emptyMap()

    fun coverage(expectedSnapshotDate: String): FinancialSafetyCoverage? =
        if (installedSnapshotDate == expectedSnapshotDate) installedCoverage else null

    fun installedSnapshotDate(): String = installedSnapshotDate

    private fun clear() {
        installedSnapshotDate = ""
        installedRows = emptyMap()
        installedCoverage = null
    }

    private fun install(parsed: Parsed) {
        installedSnapshotDate = parsed.snapshotDate
        installedRows = parsed.rows
        installedCoverage = parsed.coverage
    }

    private fun download(): String {
        val c = (URL("$REMOTE_URL?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "KR4-Stage4567/1")
        }
        try {
            require(c.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${c.responseCode}" }
            val declared = c.contentLengthLong
            require(declared <= 0 || declared <= MAX_BYTES) { "Stage4 payload too large: $declared" }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES) { "Stage4 payload exceeded $MAX_BYTES bytes" }
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun parseAndValidate(text: String, expectedSnapshot: String): Parsed {
        require(text.length > 1000) { "Stage4 payload too small" }
        val root = JSONObject(text)
        require(root.getString("snapshot_date_kst") == expectedSnapshot) { "Stage4 snapshot mismatch" }
        require(root.getInt("universe_count") == 2649) { "Stage4 universe mismatch" }

        val safety = root.getJSONObject("financial_safety")
        require(safety.getString("policy_version") == POLICY_VERSION) { "unexpected Stage4 policy" }
        require(safety.getString("snapshot_date_kst") == expectedSnapshot) { "Stage4 section date mismatch" }
        val records = safety.getJSONObject("records")
        require(records.length() == 2649) { "Stage4 record count mismatch" }

        val coverageJson = safety.getJSONObject("coverage")
        val coverage = FinancialSafetyCoverage(
            pass = coverageJson.getInt("pass"),
            fail = coverageJson.getInt("fail"),
            hold = coverageJson.getInt("hold"),
            notApplicable = coverageJson.getInt("not_applicable"),
            total = coverageJson.getInt("total"),
        )
        require(coverage.total == 2649) { "Stage4 coverage total mismatch" }
        require(coverage.pass + coverage.fail + coverage.hold + coverage.notApplicable == coverage.total) {
            "Stage4 coverage sum mismatch"
        }

        val rows = LinkedHashMap<String, FinancialSafetyRecord>(2649)
        var pass = 0
        var fail = 0
        var hold = 0
        var notApplicable = 0
        val keys = records.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            require(code.matches(Regex("[0-9A-Z]{6}"))) { "invalid Stage4 code $code" }
            val row = records.getJSONObject(code)
            val status = row.getString("status")
            require(status in setOf("PASS", "FAIL", "HOLD", "NOT_APPLICABLE")) { "bad Stage4 status $status" }
            val debt = nullableDouble(row, "debt_to_equity_pct")
            val current = nullableDouble(row, "current_ratio_pct")
            val identity = nullableDouble(row, "identity_gap_pct")
            if (status == "PASS") {
                require(debt != null && debt.isFinite() && debt <= 400.0) { "PASS debt gate failed $code" }
                require(current != null && current.isFinite() && current >= 70.0) { "PASS current-ratio gate failed $code" }
                require(identity != null && identity.isFinite() && identity <= 5.0) { "PASS identity gate failed $code" }
            }
            when (status) {
                "PASS" -> pass++
                "FAIL" -> fail++
                "HOLD" -> hold++
                "NOT_APPLICABLE" -> notApplicable++
            }
            rows[code] = FinancialSafetyRecord(
                issuerId = code,
                status = status,
                reason = row.optString("reason", ""),
                scope = row.optString("scope", ""),
                debtToEquityPct = debt,
                currentRatioPct = current,
                accountingIdentityGapPct = identity,
                basis = row.optString("basis", ""),
            )
        }
        require(rows.size == 2649) { "Stage4 duplicate/missing code" }
        require(pass == coverage.pass && fail == coverage.fail && hold == coverage.hold && notApplicable == coverage.notApplicable) {
            "Stage4 counted coverage mismatch"
        }
        return Parsed(expectedSnapshot, rows.toMap(), coverage)
    }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.getDouble(key)

    private fun saveAtomically(directory: File, body: String) {
        directory.mkdirs()
        val cache = File(directory, CACHE_NAME)
        val temp = File(directory, TEMP_NAME)
        FileOutputStream(temp, false).use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        require(temp.length() in 1..MAX_BYTES.toLong()) { "Stage4 temp file size invalid" }
        try {
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        FileOutputStream(cache, true).use { it.fd.sync() }
    }
}
