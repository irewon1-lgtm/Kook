package com.krstock.v3.data.update

import android.content.Context
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.model.RealQuantRecord
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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * Downloads only a CI-validated public snapshot and applies it after repeating
 * the critical integrity gates on-device. A bad/partial/network-failed update
 * never replaces the last known-good cache, its rollback copy, or the bundled fallback.
 */
object SnapshotAutoUpdater {
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/irewon1-lgtm/Kook/main/evidence/real_quant_snapshot.json"
    private const val CACHE_NAME = "kr4_latest_snapshot.json"
    private const val BACKUP_NAME = "kr4_previous_snapshot.json"
    private const val TEMP_NAME = "kr4_snapshot.tmp"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    enum class Source { LIVE, CACHE, BACKUP, BUNDLED }

    data class Result(
        val source: Source,
        val snapshotDate: String,
        val priceCutoffDate: String,
        val message: String,
        val networkSucceeded: Boolean,
    )

    private data class ParsedSnapshot(
        val snapshotDate: String,
        val priceCutoffDate: String,
        val dartFileName: String,
        val rows: List<RealQuantRecord>,
        val m01Available: Int,
        val m02Available: Int,
        val m03Available: Int,
        val m04Available: Int,
        val completeCount: Int,
    )

    @Volatile
    var lastResult: Result = bundledResult("앱 내장 정상본")
        private set

    suspend fun bootstrap(context: Context): Result = withContext(Dispatchers.IO) {
        refreshBlocking(context.applicationContext, installInMemory = true)
    }

    suspend fun backgroundRefresh(context: Context): Result = withContext(Dispatchers.IO) {
        refreshBlocking(context.applicationContext, installInMemory = false)
    }

    /**
     * Bootstrap and WorkManager can overlap in the same process. Serialize the
     * whole read/validate/promote critical section so two refreshes cannot race
     * on temp/cache/backup files.
     */
    @Synchronized
    internal fun refreshBlocking(context: Context, installInMemory: Boolean): Result {
        var active = bundledResult("앱 내장 정상본")
        val cache = File(context.filesDir, CACHE_NAME)
        val backup = File(context.filesDir, BACKUP_NAME)

        // 1) Warm start from local known-good copies. Cache is preferred when
        // equally new, but a strictly newer valid rollback copy can still win.
        // Corrupt or implausibly old local files are ignored without touching them.
        val localCandidates = listOf(
            Triple(cache, Source.CACHE, "마지막 정상 자동갱신본"),
            Triple(backup, Source.BACKUP, "백업 정상본 복구"),
        )
        for ((file, source, message) in localCandidates) {
            if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) continue
            val parsed = runCatching { parseAndValidate(file.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (!isNotOlderThanBundled(parsed.snapshotDate, parsed.priceCutoffDate)) continue
            if (isOlderVersion(parsed.snapshotDate, parsed.priceCutoffDate, active.snapshotDate, active.priceCutoffDate)) continue
            if (source == Source.BACKUP && isSameVersion(
                    parsed.snapshotDate,
                    parsed.priceCutoffDate,
                    active.snapshotDate,
                    active.priceCutoffDate,
                )
            ) continue
            if (installInMemory) install(parsed)
            active = Result(
                source,
                parsed.snapshotDate,
                parsed.priceCutoffDate,
                message,
                false,
            )
        }

        // 2) Network refresh. Compare against the selected local active version,
        // not just the APK-bundled version. This prevents a background worker
        // from overwriting a newer disk cache with an older remote snapshot.
        val network = runCatching {
            val body = download()
            val parsed = parseAndValidate(body)
            require(!isOlderVersion(
                parsed.snapshotDate,
                parsed.priceCutoffDate,
                active.snapshotDate,
                active.priceCutoffDate,
            )) { "remote snapshot downgrade rejected" }
            // Only copy cache -> backup when cache is the selected newest local
            // normal form. If BACKUP won because it is newer, preserve it intact.
            saveAtomically(context, body, preserveExistingCache = active.source == Source.CACHE)
            if (installInMemory) install(parsed)
            Result(
                Source.LIVE,
                parsed.snapshotDate,
                parsed.priceCutoffDate,
                "최신 검증본 자동갱신 완료",
                true,
            )
        }

        lastResult = network.getOrElse { error ->
            active.copy(message = "${active.message} · 네트워크 갱신 보류(${error.javaClass.simpleName})")
        }
        return lastResult
    }

    private fun download(): String {
        val url = URL("$REMOTE_URL?ts=${System.currentTimeMillis()}")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "KR4-Android-AutoUpdate/3")
        }
        try {
            require(c.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${c.responseCode}" }
            val declared = c.contentLengthLong
            require(declared <= 0 || declared <= MAX_BYTES) { "snapshot too large: $declared" }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES) { "snapshot exceeded $MAX_BYTES bytes" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun parseAndValidate(text: String): ParsedSnapshot {
        require(text.length > 1000) { "snapshot payload too small" }
        val root = JSONObject(text)
        val snapshotDate = root.getString("snapshot_date_kst")
        val priceCutoffDate = root.getString("price_cutoff_date_kst")
        val snapshotDay = LocalDate.parse(snapshotDate)
        val priceDay = LocalDate.parse(priceCutoffDate)
        val today = LocalDate.now(KST)
        require(!snapshotDay.isAfter(today.plusDays(1))) { "future snapshot rejected" }
        require(!priceDay.isAfter(snapshotDay)) { "price cutoff after snapshot" }
        require(!priceDay.isBefore(snapshotDay.minusDays(10))) { "price cutoff is implausibly stale" }

        // Do not accept an arbitrary structurally-similar JSON accidentally placed
        // at the raw URL. It must identify itself as the v3 automatic collector.
        val auto = root.getJSONObject("auto_update")
        require(auto.getString("collector") == "collect_real_quant_v3.py") { "untrusted collector provenance" }
        require(auto.getString("timezone") == "Asia/Seoul") { "unexpected snapshot timezone" }
        require(auto.getString("dart_period") in setOf("Q1", "HY", "Q3", "FY")) { "invalid DART period provenance" }

        val universe = root.getInt("universe_count")
        require(universe == GeneratedRealQuantSnapshot.universeCount) {
            "universe mismatch $universe != ${GeneratedRealQuantSnapshot.universeCount}"
        }
        val records = root.getJSONObject("records")
        require(records.length() == universe) { "record count mismatch" }

        val bundledCodes = GeneratedRealQuantSnapshot.rows.asSequence().map { it.code }.toSet()
        val parsed = ArrayList<RealQuantRecord>(universe)
        val seen = HashSet<String>(universe * 2)
        val ranks = ArrayList<Int>()
        var c1 = 0
        var c2 = 0
        var c3 = 0
        var c4 = 0
        var complete = 0
        var perExplained = 0
        var sourceErrors = 0

        val keys = records.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            require(code.matches(Regex("[0-9A-Z]{6}"))) { "invalid issue code $code" }
            require(seen.add(code)) { "duplicate issue code $code" }
            val r = records.getJSONObject(code)
            require(r.getString("name").isNotBlank()) { "blank name $code" }
            require(r.getString("market") in setOf("KOSPI", "KOSDAQ")) { "bad market $code" }

            fun metric(id: String): Quad {
                val m = r.getJSONObject(id)
                val raw = nullableDouble(m, "raw")
                val pct = nullableDouble(m, "percentile")
                val reason = nullableString(m, "reason")
                val basis = nullableString(m, "basis") ?: ""
                if (raw == null) {
                    require(pct == null) { "$code $id percentile without raw" }
                    require(!reason.isNullOrBlank()) { "$code $id missing reason" }
                } else {
                    require(raw.isFinite()) { "$code $id non-finite raw" }
                    require(pct != null && pct.isFinite() && pct in 0.0..100.0) { "$code $id bad percentile" }
                    require(basis.isNotBlank()) { "$code $id missing basis" }
                }
                if (reason == "NAVER_INTEGRATION_ERROR" || reason == "NAVER_WORKER_EXCEPTION") sourceErrors++
                return Quad(raw, pct, reason, basis)
            }

            val m1 = metric("m01")
            val m2 = metric("m02")
            val m3 = metric("m03")
            val m4 = metric("m04")
            if (m1.raw != null) c1++
            if (m2.raw != null) c2++
            if (m3.raw != null) c3++
            if (m4.raw != null) c4++
            if (m3.raw != null || m3.reason == "NONPOSITIVE_EPS") perExplained++

            val composite = nullableDouble(r, "composite")
            val rank = nullableInt(r, "rank")
            val allMetrics = listOf(m1, m2, m3, m4).all { it.raw != null }
            require((composite != null) == allMetrics) { "$code composite completeness mismatch" }
            require((rank != null) == allMetrics) { "$code rank completeness mismatch" }
            if (rank != null) {
                ranks.add(rank)
                complete++
            }

            parsed.add(
                RealQuantRecord(
                    code = code,
                    m01Raw = m1.raw, m01Percentile = m1.pct, m01ReasonCode = m1.reason, m01Basis = m1.basis,
                    m02Raw = m2.raw, m02Percentile = m2.pct, m02ReasonCode = m2.reason, m02Basis = m2.basis,
                    m03Raw = m3.raw, m03Percentile = m3.pct, m03ReasonCode = m3.reason, m03Basis = m3.basis,
                    m04Raw = m4.raw, m04Percentile = m4.pct, m04ReasonCode = m4.reason, m04Basis = m4.basis,
                    compositeScore = composite,
                    rankOrder = rank,
                )
            )
        }

        require(seen == bundledCodes) { "remote issue-code set differs from installed master" }
        require(ranks.sorted() == (1..complete).toList()) { "rank sequence is not contiguous" }
        require(records.has("005930") && records.getJSONObject("005930").getString("name") == "삼성전자") {
            "Samsung anchor identity mismatch"
        }

        val coverage = root.getJSONObject("coverage")
        require(coverage.getInt("m01_available") == c1)
        require(coverage.getInt("m02_available") == c2)
        require(coverage.getInt("m03_available") == c3)
        require(coverage.getInt("m04_available") == c4)
        require(coverage.getInt("complete_count") == complete)
        require(coverage.getInt("ranked_count") == complete)

        // Fail closed on broad source degradation. Floors combine a percentage
        // floor and a small tolerated drop from the APK's bundled known-good set.
        require(c1 >= max((universe * 0.88).toInt(), GeneratedRealQuantSnapshot.bundledM01Available - 55)) { "M01 coverage regression" }
        require(c2 >= max((universe * 0.84).toInt(), GeneratedRealQuantSnapshot.bundledM02Available - 55)) { "M02 coverage regression" }
        require(c3 >= max((universe * 0.58).toInt(), GeneratedRealQuantSnapshot.bundledM03Available - 80)) { "M03 coverage regression" }
        require(c4 >= max((universe * 0.96).toInt(), GeneratedRealQuantSnapshot.bundledM04Available - 30)) { "M04 coverage regression" }
        require(complete >= max((universe * 0.50).toInt(), GeneratedRealQuantSnapshot.bundledCompleteCount - 100)) { "complete coverage regression" }
        require(perExplained >= (universe * 0.985).toInt()) { "PER state explanation regression" }
        require(sourceErrors <= 15) { "too many provider/worker errors: $sourceErrors" }

        return ParsedSnapshot(
            snapshotDate = snapshotDate,
            priceCutoffDate = priceCutoffDate,
            dartFileName = root.optString("dart_bulk_file", ""),
            rows = parsed.sortedBy { it.code },
            m01Available = c1,
            m02Available = c2,
            m03Available = c3,
            m04Available = c4,
            completeCount = complete,
        )
    }

    private data class Quad(val raw: Double?, val pct: Double?, val reason: String?, val basis: String)

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.getDouble(key)

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.getString(key)

    private fun nullableInt(o: JSONObject, key: String): Int? =
        if (!o.has(key) || o.isNull(key)) null else o.getInt(key)

    private fun install(p: ParsedSnapshot) {
        GeneratedRealQuantSnapshot.installRuntimeSnapshot(
            newSnapshotDate = p.snapshotDate,
            newPriceCutoffDate = p.priceCutoffDate,
            newDartFileName = p.dartFileName,
            newRows = p.rows,
            newM01Available = p.m01Available,
            newM02Available = p.m02Available,
            newM03Available = p.m03Available,
            newM04Available = p.m04Available,
            newCompleteCount = p.completeCount,
        )
    }

    private fun isNotOlderThanBundled(snapshotDate: String, priceDate: String): Boolean =
        !isOlderVersion(
            snapshotDate,
            priceDate,
            GeneratedRealQuantSnapshot.bundledSnapshotDate,
            GeneratedRealQuantSnapshot.bundledPriceCutoffDate,
        )

    /** Pure policy helper covered by JVM tests. */
    internal fun isOlderVersion(
        incomingSnapshotDate: String,
        incomingPriceDate: String,
        activeSnapshotDate: String,
        activePriceDate: String,
    ): Boolean {
        val incomingSnap = LocalDate.parse(incomingSnapshotDate)
        val incomingPrice = LocalDate.parse(incomingPriceDate)
        val activeSnap = LocalDate.parse(activeSnapshotDate)
        val activePrice = LocalDate.parse(activePriceDate)
        return incomingSnap < activeSnap || (incomingSnap == activeSnap && incomingPrice < activePrice)
    }

    private fun isSameVersion(
        firstSnapshotDate: String,
        firstPriceDate: String,
        secondSnapshotDate: String,
        secondPriceDate: String,
    ): Boolean = firstSnapshotDate == secondSnapshotDate && firstPriceDate == secondPriceDate

    private fun saveAtomically(context: Context, body: String, preserveExistingCache: Boolean) {
        writeSnapshotFilesAtomically(context.filesDir, body, preserveExistingCache)
    }

    /**
     * Pure file-promotion primitive covered by JVM tests. The destination is
     * never deleted before replacement. If atomic move is unsupported, a normal
     * REPLACE_EXISTING move is used; any thrown failure leaves the prior cache
     * or the rollback copy intact.
     */
    internal fun writeSnapshotFilesAtomically(directory: File, body: String, preserveExistingCache: Boolean) {
        require(body.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) { "snapshot body size invalid" }
        directory.mkdirs()
        val cache = File(directory, CACHE_NAME)
        val backup = File(directory, BACKUP_NAME)
        val temp = File(directory, TEMP_NAME)

        if (preserveExistingCache && cache.isFile && cache.length() > 0) {
            cache.copyTo(backup, overwrite = true)
            FileOutputStream(backup, true).use { it.fd.sync() }
        }

        FileOutputStream(temp, false).use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        require(temp.length() in 1..MAX_BYTES.toLong()) { "temporary cache size invalid" }

        try {
            Files.move(
                temp.toPath(),
                cache.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // Sync the promoted file as a second durability barrier. We never delete
        // the previous cache first, so failed promotion cannot create an empty slot.
        FileOutputStream(cache, true).use { it.fd.sync() }
    }

    private fun bundledResult(message: String) = Result(
        Source.BUNDLED,
        GeneratedRealQuantSnapshot.snapshotDate,
        GeneratedRealQuantSnapshot.priceCutoffDate,
        message,
        false,
    )
}
