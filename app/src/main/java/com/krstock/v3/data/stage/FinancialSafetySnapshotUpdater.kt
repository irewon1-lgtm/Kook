package com.krstock.v3.data.stage

import android.content.Context
import com.krstock.v3.data.candidate.FinalCandidateRuntimeStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Receives the optional Stage4 section independently of M01–M04 installation.
 * The publisher augments real_quant_snapshot.json; it has no separate Stage4 file.
 * A received document without valid matching safety is authoritative HOLD, while
 * transport failure alone permits a fully revalidated exact-date safety cache.
 */
object FinancialSafetySnapshotUpdater {
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/irewon1-lgtm/Kook/main/evidence/real_quant_snapshot.json"
    private const val CACHE_NAME = "kr4_stage4_financial_safety_v1.json"
    private const val TEMP_NAME = "kr4_stage4_financial_safety_v1.tmp"
    private const val MAX_BYTES = 8 * 1024 * 1024

    @Synchronized
    internal fun refreshBlocking(
        context: Context,
        expectedSnapshotDate: String,
        expectedCodes: Set<String>,
        quantText: String? = null,
    ): Boolean = refreshBlocking(context.filesDir, expectedSnapshotDate, expectedCodes, quantText)

    @Synchronized
    internal fun refreshBlocking(
        directory: File,
        expectedSnapshotDate: String,
        expectedCodes: Set<String>,
        quantText: String? = null,
        download: () -> String = ::download,
    ): Boolean {
        val cache = File(directory, CACHE_NAME)
        val received = quantText ?: try {
            download()
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            // Rejected content (including an oversized HTTP-200 response) is
            // authoritative failure, rather than transport-only unavailability.
            invalidate(cache)
            return false
        }
        if (received != null) {
            val safety = runCatching {
                require(received.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Stage4 payload too large" }
                val root = JSONObject(received)
                require(root.getString("snapshot_date_kst") == expectedSnapshotDate) { "Stage4 source snapshot mismatch" }
                root.getJSONObject("financial_safety")
            }.getOrNull()
            val parsed = safety?.let {
                runCatching { FinancialSafetySnapshotParser.parse(it, expectedSnapshotDate, expectedCodes) }.getOrNull()
            }
            if (parsed == null) {
                invalidate(cache)
                return false
            }
            // Keep valid received state on disk failure, but never retain an
            // older cache that could restore a superseded PASS while offline.
            runCatching { saveAtomically(directory, requireNotNull(safety).toString()) }
                .onFailure { runCatching { cache.delete() } }
            FinancialSafetyRuntimeStore.install(parsed)
            return true
        }

        val cached = runCatching {
            require(cache.isFile && cache.length() in 1..MAX_BYTES.toLong()) { "no valid Stage4 cache" }
            FinancialSafetySnapshotParser.parse(
                JSONObject(cache.readText(Charsets.UTF_8)), expectedSnapshotDate, expectedCodes,
            )
        }.getOrNull()
        if (cached == null) {
            invalidate(cache)
            return false
        }
        FinancialSafetyRuntimeStore.install(cached)
        return true
    }

    private fun invalidate(cache: File) {
        FinancialSafetyRuntimeStore.clear()
        FinalCandidateRuntimeStore.clear()
        // Do not let a later offline launch resurrect safety after an authoritative HOLD.
        runCatching { cache.delete() }
    }

    private fun download(): String {
        val connection = (URL("$REMOTE_URL?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "KR4-FinancialSafety/1")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val declared = connection.contentLengthLong
            require(declared <= 0 || declared <= MAX_BYTES) { "Stage4 payload too large: $declared" }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Stage4 payload exceeded $MAX_BYTES bytes" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

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
