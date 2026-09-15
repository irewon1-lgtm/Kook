package com.krstock.v3.data.evidence

import com.krstock.v3.data.model.EvidenceKind
import com.krstock.v3.data.model.EvidenceSourceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSourceCollectorTest {
    @Test
    fun parsesDartCorpCodeFromPublicSearchContract() {
        val html = """
            <a href="javascript:openCorpInfoNew('00126380','winCorpInfo','/dsae001/selectPopup.ax');">삼성전자</a>
        """.trimIndent()
        assertEquals("00126380", OfficialSourceCollector.parseCorpCodeFromDartSearch(html))
    }

    @Test
    fun parsesHomepageAndIrHomepageOnlyFromDartProfile() {
        val html = """
            <table>
              <tr><th>홈페이지</th><td><a href="https://www.example.com">www.example.com</a></td></tr>
              <tr><th>IR 홈페이지</th><td><a href="https://ir.example.com/investors">IR</a></td></tr>
            </table>
        """.trimIndent()
        val profile = OfficialSourceCollector.parseCompanyProfile("00126380", html)
        assertEquals("https://www.example.com", profile.homepage)
        assertEquals("https://ir.example.com/investors", profile.irHomepage)
    }

    @Test
    fun firstPartyIrPdfIsAcceptedAndExternalAttackerLinkIsRejected() {
        val html = """
            <html><body>
              <a href="/ir/2026/20260915-q2-results.pdf">2026 2Q 실적발표 IR 자료</a>
              <a href="https://evil.example.net/ir/fake.pdf">2026 2Q 실적발표 IR 자료 복제본</a>
              <a href="https://youtube.com/watch?v=fake">IR presentation video</a>
            </body></html>
        """.trimIndent()
        val parsed = OfficialSourceCollector.parseOfficialPage(
            "https://corp.example.com/investor/",
            html,
            EvidenceKind.IR
        )
        assertEquals(1, parsed.evidence.size)
        val row = parsed.evidence.single()
        assertTrue(row.url.startsWith("https://corp.example.com/"))
        assertEquals(EvidenceKind.IR, row.kind)
        assertEquals(EvidenceSourceTier.COMPANY_IR, row.sourceTier)
        assertEquals("2026-09-15", row.publishedAt)
        assertFalse(parsed.evidence.any { it.url.contains("evil.example.net") || it.url.contains("youtube.com") })
    }

    @Test
    fun officialPressReleaseStaysCompanyOfficialNotNews() {
        val html = """
            <a href="/newsroom/press/2026-09-10-product.html">2026-09-10 보도자료 신규 제품 출시</a>
        """.trimIndent()
        val parsed = OfficialSourceCollector.parseOfficialPage(
            "https://company.example.com/",
            html,
            EvidenceKind.OFFICIAL
        )
        val row = parsed.evidence.single()
        assertEquals(EvidenceKind.OFFICIAL, row.kind)
        assertEquals(EvidenceSourceTier.COMPANY_OFFICIAL, row.sourceTier)
        assertEquals("2026-09-10", row.publishedAt)
    }

    @Test
    fun emptyOrUntrustedProfileNeverInventsOfficialRoot() {
        val html = """
            <table><tr><th>회사명</th><td>테스트</td></tr></table>
            <a href="https://dart.fss.or.kr/">DART</a>
        """.trimIndent()
        val profile = OfficialSourceCollector.parseCompanyProfile("00000001", html)
        assertTrue(profile.homepage.isBlank())
        assertTrue(profile.irHomepage.isBlank())
    }

    @Test
    fun dateInferenceSupportsSeparatedAndCompactDates() {
        assertEquals("2026-09-15", OfficialSourceCollector.inferDate("IR_2026.09.15.pdf"))
        assertEquals("2026-09-14", OfficialSourceCollector.inferDate("results_20260914.pdf"))
        assertEquals("", OfficialSourceCollector.inferDate("results-latest.pdf"))
    }
}
