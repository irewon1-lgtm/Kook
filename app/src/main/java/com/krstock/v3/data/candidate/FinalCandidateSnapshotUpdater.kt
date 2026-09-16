package com.krstock.v3.data.candidate

import android.content.Context
import com.krstock.v3.data.model.CandidateMetricSnapshot
import com.krstock.v3.data.model.FinalCandidateRecord
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
import kotlin.math.abs

/**
 * Downloads only the CI-generated KR4_FINAL_CANDIDATE_V2 snapshot.
 * The file is accepted only when it matches the active quant snapshot date and
 * every candidate passed stage-4 financial safety. Network/cache failures leave
 * the candidate list empty rather than falling back to the obsolete V1 policy.
 */
object FinalCandidateSnapshotUpdater {
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/irewon1-lgtm/Kook/main/evidence/final_candidates.json"
    private const val CACHE_NAME = "kr4_final_candidates_v2.json"
    private const val TEMP_NAME = "kr4_final_candidates_v2.tmp"
    private const val MAX_BYTES = 1024 * 1024
    private const val SCHEMA_VERSION = "KR4_FINAL_CANDIDATE_V2"
    private const val POLICY_VERSION = "STAGE4567_SAFETY_AND_RELATIVE_PER_BAND_V1"

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
                    FinalCandidateRuntimeStore.install(expectedSnapshot, it)
                    installed = true
                }
        }

        val network = runCatching {
            val body = download()
            val parsed = parseAndValidate(body, expectedSnapshot)
            saveAtomically(context.filesDir, body)
            FinalCandidateRuntimeStore.install(expectedSnapshot, parsed)
            true
        }.getOrDefault(false)

        if (!network && !installed) FinalCandidateRuntimeStore.clear()
        return network || installed
    }

    private fun download(): String {
        val connection = (URL("$REMOTE_URL?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "KR4-FinalCandidate/2")
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val declared = connection.contentLengthLong
            require(declared <= 0 || declared <= MAX_BYTES) { "candidate payload too large: $declared" }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES) { "candidate payload exceeded $MAX_BYTES bytes" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseAndValidate(text: String, expectedSnapshot: String): List<FinalCandidateRecord> {
        require(text.length > 200) { "candidate payload too small" }
        val root = JSONObject(text)
        require(root.getString("schema_version") == SCHEMA_VERSION) { "unexpected candidate schema" }
        require(root.getString("policy_version") == POLICY_VERSION) { "unexpected candidate policy" }
        require(root.getString("snapshot_date_kst") == expectedSnapshot) { "candidate snapshot mismatch" }
        require(root.getInt("universe_count") == 2649) { "candidate universe mismatch" }
        val count = root.getInt("candidate_count")
        require(count in 1..100) { "candidate count out of range: $count" }
        val array = root.getJSONArray("candidates")
        require(array.length() == count) { "candidate count mismatch" }

        val rows = ArrayList<FinalCandidateRecord>(count)
        val seen = HashSet<String>()
        var previousSourceRank = 0
        for (index in 0 until array.length()) {
            val row = array.getJSONObject(index)
            val code = row.getString("code")
            require(code.matches(Regex("[0-9A-Z]{6}"))) { "invalid candidate code $code" }
            require(seen.add(code)) { "duplicate candidate code $code" }
            val candidateRank = row.getInt("candidate_rank")
            val sourceRank = row.getInt("source_rank")
            require(candidateRank == index + 1) { "candidate rank gap" }
            require(sourceRank > previousSourceRank) { "source rank is not strictly increasing" }
            previousSourceRank = sourceRank
            require(row.getString("research_status") == "FINAL_RESEARCH_CANDIDATE") { "bad research status" }

            val metrics = row.getJSONObject("metrics")
            val m01 = metric(metrics.getJSONObject("M01"))
            val m02 = metric(metrics.getJSONObject("M02"))
            val m03 = metric(metrics.getJSONObject("M03"))
            val m04 = metric(metrics.getJSONObject("M04"))
            require(m03.rawValue > 0.0) { "nonpositive trailing PER candidate" }

            val safety = row.getJSONObject("financial_safety")
            require(safety.getString("status") == "PASS") { "financial safety did not pass" }
            val valuation = row.getJSONObject("valuation")
            require(valuation.getString("method") == "MARKET_RELATIVE_TRAILING_PER_PERCENTILE") { "unexpected valuation method" }
            require(!valuation.getBoolean("changes_rank")) { "valuation band must not rerank" }
            val valuationPct = valuation.getDouble("m03_percentile")
            require(abs(valuationPct - m03.percentileScore) < 1e-6) { "valuation percentile mismatch" }
            val band = valuation.getString("band")
            require(band in setOf(
                "LOW_RELATIVE_PER", "BELOW_MEDIAN_PER", "MID_PER", "ABOVE_MEDIAN_PER", "HIGH_RELATIVE_PER"
            )) { "invalid valuation band" }

            val flagsJson = row.getJSONArray("selection_flags")
            val flags = List(flagsJson.length()) { flagsJson.getString(it) }
            require(flags.contains("FINANCIAL_SAFETY_PASS")) { "financial-safety flag missing" }
            require(flags.contains("VALUATION_BAND_CLASSIFIED")) { "valuation-band flag missing" }
            require(flags.contains("EXISTING_COMPOSITE_RANK_REUSED")) { "rank-reuse flag missing" }

            rows += FinalCandidateRecord(
                schemaVersion = row.getString("schema_version"),
                policyVersion = row.getString("policy_version"),
                candidateRank = candidateRank,
                sourceRank = sourceRank,
                issuerId = code,
                name = row.getString("name"),
                market = row.getString("market"),
                sector = row.optString("sector", "기타"),
                compositeScore = row.getDouble("composite_score"),
                financialSnapshotDate = row.getString("financial_snapshot_date"),
                priceCutoffDate = row.getString("price_cutoff_date"),
                m01 = m01,
                m02 = m02,
                m03 = m03,
                m04 = m04,
                selectionFlags = flags,
                researchStatus = row.getString("research_status"),
                selectionReasonKo = row.getString("selection_reason_ko"),
                financialSafetyStatus = safety.getString("status"),
                financialSafetyReason = safety.optString("reason", ""),
                financialSafetyBasis = safety.optString("basis", ""),
                debtToEquityPct = nullableDouble(safety, "debt_to_equity_pct"),
                currentRatioPct = nullableDouble(safety, "current_ratio_pct"),
                accountingIdentityGapPct = nullableDouble(safety, "identity_gap_pct"),
                valuationBand = band,
                valuationBandKo = valuation.optString("band_ko", ""),
                valuationPercentile = valuationPct,
            )
        }
        return rows
    }

    private fun metric(o: JSONObject): CandidateMetricSnapshot {
        val raw = o.getDouble("raw")
        val percentile = o.getDouble("percentile")
        require(raw.isFinite()) { "non-finite candidate metric" }
        require(percentile.isFinite() && percentile in 0.0..100.0) { "invalid candidate percentile" }
        val basis = o.getString("basis")
        require(basis.isNotBlank()) { "candidate metric basis missing" }
        return CandidateMetricSnapshot(
            id = o.getString("id"),
            rawValue = raw,
            percentileScore = percentile,
            basis = basis,
            asOfDate = o.getString("as_of_date"),
            source = "KR4 validated remote snapshot",
        )
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
        require(temp.length() in 1..MAX_BYTES.toLong()) { "candidate temp file size invalid" }
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
