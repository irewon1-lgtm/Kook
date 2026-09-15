package com.krstock.v3

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krstock.v3.update.AppAutoUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAutoUpdaterInstrumentedTest {

    @Test
    fun stableChannelPreservesOriginalPackageIdForInPlaceUpdates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.krstock.v3", context.packageName)
    }

    @Test
    fun validSignedReleaseManifestParsesExactly() {
        val json = """
            {
              "versionCode": 10088,
              "versionName": "4.2.88",
              "apkUrl": "https://github.com/irewon1-lgtm/Kook/releases/latest/download/KR4.apk",
              "sha256": "${"a".repeat(64)}",
              "signingCertificateSha256": "${"c".repeat(64)}",
              "mandatory": false,
              "notes": "검증 업데이트"
            }
        """.trimIndent()

        val parsed = AppAutoUpdater.parseManifest(json)
        assertEquals(10088L, parsed.versionCode)
        assertEquals("4.2.88", parsed.versionName)
        assertEquals("a".repeat(64), parsed.sha256)
        assertEquals("검증 업데이트", parsed.notes)
        assertFalse(parsed.mandatory)
    }

    @Test
    fun manifestRejectsUntrustedApkHost() {
        val json = """
            {
              "versionCode": 10089,
              "versionName": "4.2.89",
              "apkUrl": "https://example.com/KR4.apk",
              "sha256": "${"b".repeat(64)}"
            }
        """.trimIndent()

        try {
            AppAutoUpdater.parseManifest(json)
            fail("untrusted APK host must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun manifestRejectsMalformedSha256() {
        val json = """
            {
              "versionCode": 10090,
              "versionName": "4.2.90",
              "apkUrl": "https://github.com/irewon1-lgtm/Kook/releases/latest/download/KR4.apk",
              "sha256": "1234"
            }
        """.trimIndent()

        try {
            AppAutoUpdater.parseManifest(json)
            fail("malformed SHA-256 must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
