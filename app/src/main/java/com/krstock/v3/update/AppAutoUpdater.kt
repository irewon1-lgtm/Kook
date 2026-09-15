package com.krstock.v3.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.krstock.v3.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Native APK self-update channel for the KR4 app.
 *
 * Security invariants:
 *  - only a higher versionCode is accepted;
 *  - APK bytes must match the SHA-256 published with the GitHub Release;
 *  - packageName must stay identical;
 *  - candidate APK signer must exactly match the currently installed app signer;
 *  - a partial/bad download never replaces the last prepared update.
 *
 * Android does not allow a normal sideloaded app to silently install itself.
 * The APK is downloaded automatically, then Android's trusted package installer
 * asks the user for the final install confirmation.
 */
object AppAutoUpdater {
    private const val UPDATE_MANIFEST_URL =
        "https://github.com/irewon1-lgtm/Kook/releases/latest/download/update.json"
    private const val UPDATE_DIR = "app_updates"
    private const val PREPARED_META = "prepared_update.json"
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_APK_BYTES = 120L * 1024L * 1024L

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
        val mandatory: Boolean,
    )

    data class PreparedUpdate(
        val info: UpdateInfo,
        val apk: File,
    )

    sealed class InstallAction {
        data object InstallerOpened : InstallAction()
        data class PermissionRequired(val intent: Intent) : InstallAction()
    }

    suspend fun loadPrepared(context: Context): PreparedUpdate? = withContext(Dispatchers.IO) {
        runCatching { loadPreparedBlocking(context.applicationContext) }.getOrNull()
    }

    suspend fun checkAndDownload(context: Context): PreparedUpdate? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val info = fetchManifest()
        if (info.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
            clearObsoletePrepared(appContext)
            return@withContext null
        }

        val dir = updateDir(appContext)
        val target = File(dir, "KR4-${info.versionCode}.apk")
        val existing = if (target.isFile) {
            runCatching { validateCandidate(appContext, target, info) }.getOrDefault(false)
        } else false

        if (!existing) {
            target.delete()
            val temp = File(dir, "KR4-${info.versionCode}.apk.part")
            temp.delete()
            downloadToFile(info.apkUrl, temp, MAX_APK_BYTES)
            check(validateCandidate(appContext, temp, info)) { "downloaded APK validation failed" }
            if (!temp.renameTo(target)) {
                FileOutputStream(target).use { out -> temp.inputStream().use { it.copyTo(out) } }
                temp.delete()
            }
        }

        val prepared = PreparedUpdate(info, target)
        savePreparedMeta(appContext, prepared)
        pruneOldApks(dir, target.name)
        prepared
    }

    fun requestInstall(context: Context, prepared: PreparedUpdate): InstallAction {
        val appContext = context.applicationContext
        check(prepared.info.versionCode > BuildConfig.VERSION_CODE.toLong()) { "not an upgrade" }
        check(validateCandidate(appContext, prepared.apk, prepared.info)) { "prepared APK no longer valid" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallAction.PermissionRequired(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            prepared.apk,
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(install)
        return InstallAction.InstallerOpened
    }

    private fun loadPreparedBlocking(context: Context): PreparedUpdate? {
        val dir = updateDir(context)
        val meta = File(dir, PREPARED_META)
        if (!meta.isFile || meta.length() !in 2..MAX_MANIFEST_BYTES.toLong()) return null
        val info = parseManifest(meta.readText(Charsets.UTF_8))
        if (info.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
            clearObsoletePrepared(context)
            return null
        }
        val apk = File(dir, "KR4-${info.versionCode}.apk")
        if (!apk.isFile || !validateCandidate(context, apk, info)) {
            apk.delete()
            meta.delete()
            return null
        }
        return PreparedUpdate(info, apk)
    }

    private fun fetchManifest(): UpdateInfo {
        val text = downloadText(UPDATE_MANIFEST_URL, MAX_MANIFEST_BYTES)
        return parseManifest(text)
    }

    internal fun parseManifest(text: String): UpdateInfo {
        val root = JSONObject(text)
        val versionCode = root.getLong("versionCode")
        val versionName = root.getString("versionName").trim()
        val apkUrl = root.getString("apkUrl").trim()
        val sha256 = root.getString("sha256").trim().lowercase()
        val notes = root.optString("notes", "새 버전이 준비되었습니다.").trim()
        val mandatory = root.optBoolean("mandatory", false)
        require(versionCode > 0) { "invalid versionCode" }
        require(versionName.isNotBlank()) { "blank versionName" }
        require(apkUrl.startsWith("https://github.com/irewon1-lgtm/Kook/releases/")) { "untrusted APK URL" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "invalid APK SHA-256" }
        return UpdateInfo(versionCode, versionName, apkUrl, sha256, notes, mandatory)
    }

    private fun savePreparedMeta(context: Context, prepared: PreparedUpdate) {
        val root = JSONObject().apply {
            put("versionCode", prepared.info.versionCode)
            put("versionName", prepared.info.versionName)
            put("apkUrl", prepared.info.apkUrl)
            put("sha256", prepared.info.sha256)
            put("notes", prepared.info.notes)
            put("mandatory", prepared.info.mandatory)
        }
        val dir = updateDir(context)
        val tmp = File(dir, "$PREPARED_META.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        val meta = File(dir, PREPARED_META)
        meta.delete()
        check(tmp.renameTo(meta)) { "failed to promote update metadata" }
    }

    private fun validateCandidate(context: Context, apk: File, info: UpdateInfo): Boolean {
        if (!apk.isFile || apk.length() !in 1..MAX_APK_BYTES) return false
        if (sha256(apk) != info.sha256) return false
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        if (packageVersionCode(archive) != info.versionCode) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        return signerDigests(installed) == signerDigests(archive) && signerDigests(installed).isNotEmpty()
    }

    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return signatures.map { bytesToHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun downloadText(url: String, maxBytes: Int): String {
        val temp = File.createTempFile("kr4-update-", ".json")
        return try {
            downloadToFile(url, temp, maxBytes.toLong())
            temp.readText(Charsets.UTF_8)
        } finally {
            temp.delete()
        }
    }

    private fun downloadToFile(url: String, destination: File, maxBytes: Long) {
        require(url.startsWith("https://")) { "HTTPS required" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/octet-stream, application/json;q=0.9, */*;q=0.5")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "KR4-AppUpdater/${BuildConfig.VERSION_NAME}")
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val declared = connection.contentLengthLong
            require(declared <= 0 || declared <= maxBytes) { "download too large: $declared" }
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maxBytes) { "download exceeded $maxBytes bytes" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            require(destination.length() > 0) { "empty download" }
        } catch (t: Throwable) {
            destination.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun updateDir(context: Context): File =
        File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }

    private fun clearObsoletePrepared(context: Context) {
        val dir = updateDir(context)
        File(dir, PREPARED_META).delete()
        dir.listFiles()?.filter { it.name.endsWith(".apk") || it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    private fun pruneOldApks(dir: File, keepName: String) {
        dir.listFiles()?.filter {
            (it.name.endsWith(".apk") || it.name.endsWith(".part")) && it.name != keepName
        }?.forEach { it.delete() }
    }
}
