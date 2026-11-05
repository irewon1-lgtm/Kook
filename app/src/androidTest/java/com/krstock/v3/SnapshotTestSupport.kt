package com.krstock.v3

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.krstock.v3.data.candidate.FinalCandidateRuntimeStore
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.stage.FinancialSafetyRuntimeStore
import com.krstock.v3.data.update.SnapshotAutoUpdater
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Test-only, byte-for-byte historical collector output; provenance is an adjacent asset. */
internal object SnapshotTestSupport {
    /** Approved dense controls are opened through their actual public actions. */
    fun revealRankingSearch(rule: ComposeTestRule) {
        if (rule.onAllNodesWithTag("stock_search").fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithContentDescription("종목 검색").performClick()
            rule.waitForIdle()
        }
    }

    fun revealRankingMetrics(rule: ComposeTestRule) {
        if (rule.onAllNodesWithTag("metric_toggle_M01").fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag("metric_picker").performScrollTo().performClick()
            rule.waitForIdle()
        }
    }

    fun selectRankingSort(rule: ComposeTestRule, mode: String) {
        rule.onNodeWithTag("sort_picker").performScrollTo().performClick()
        rule.onNodeWithTag("sort_" + mode).performClick()
        // Reopen to inspect the selected semantics, then close by selecting it.
        rule.onNodeWithTag("sort_picker").performScrollTo().performClick()
        rule.onNodeWithTag("sort_" + mode).assertIsSelected().performClick()
        rule.waitForIdle()
    }

    const val CURRENT = "quant_20261105.json"
    const val BEFORE = "quant_20261007_before.json"
    const val SAME_DATE_AFTER = "quant_20261007_after.json"
    const val CACHE = "kr4_latest_snapshot.json"
    const val BACKUP = "kr4_previous_snapshot.json"

    fun fixture(name: String = CURRENT): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun isolatedContext(context: Context): Context {
        val directory = File(context.cacheDir, "snapshot-test-${System.nanoTime()}").apply { mkdirs() }
        return object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
    }

    fun stopPeriodicRefreshes(context: Context) {
        val work = WorkManager.getInstance(context)
        listOf("kr4-validated-snapshot-refresh-v2", "kr4-native-app-update-v1").forEach {
            work.cancelUniqueWork(it).result.get(20, TimeUnit.SECONDS)
        }
    }

    fun offline(): Nothing = throw IOException("instrumented offline attempt")

    /**
     * Historical fixture screens must own the updater monitor from Activity setup
     * through restoration. A completed cancellation or a momentary lock barrier
     * cannot exclude an already queued real HTTP receive. Direct refreshBlocking
     * calls in controlled tests remain reentrant on the instrumentation thread.
     * Never apply this rule to LIVE tests that receive on Dispatchers.IO.
     */
    fun isolatedSnapshotRuntime(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                synchronized(SnapshotAutoUpdater) { base.evaluate() }
            }
        }
    }

    fun failureEvidence(): TestWatcher = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                evidence(
                    context,
                    "${description.className.substringAfterLast('.')}-${description.methodName}-failed",
                    JSONObject().put("assertion_failure", error.toString()),
                )
            }
        }
    }

    /** Model a process starting on an actual older snapshot without changing its market values. */
    fun installHistoricalMemory(name: String) {
        val parsed = SnapshotAutoUpdater.parseAndValidate(fixture(name))
        GeneratedRealQuantSnapshot.installRuntimeSnapshot(
            parsed.snapshotDate, parsed.priceCutoffDate, parsed.dartFileName, parsed.rows,
            parsed.m01Available, parsed.m02Available, parsed.m03Available, parsed.m04Available,
            parsed.completeCount,
        )
        FinancialSafetyRuntimeStore.clear()
        FinalCandidateRuntimeStore.clear()
    }

    /** Capture the actual device screen and the values asserted in that screen's test. */
    fun evidence(context: Context, name: String, extra: JSONObject = JSONObject()) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // AGP exports this directory before uninstalling the test application.
        val additionalOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val outputRoot = additionalOutput?.takeIf { it.isNotBlank() }?.let(::File)
            ?: context.getExternalFilesDir(null)
        val directory = File(outputRoot, "snapshot-e2e").apply { mkdirs() }
        val result = SnapshotAutoUpdater.lastResult
        val json = extra.put("source", result.source.name)
            .put("snapshot_date_kst", result.snapshotDate)
            .put("price_cutoff_date_kst", result.priceCutoffDate)
            .put("network_succeeded", result.networkSucceeded)
            .put("complete_count", GeneratedRealQuantSnapshot.completeCount)
            .put("universe_count", GeneratedRealQuantSnapshot.universeCount)
            .put("revision", result.revision)
            .put("runtime_snapshot_date_kst", GeneratedRealQuantSnapshot.snapshotDate)
            .put("runtime_price_cutoff_date_kst", GeneratedRealQuantSnapshot.priceCutoffDate)
            .put("failure", result.failure ?: JSONObject.NULL)
        if (!json.has("evidence_kind")) json.put("evidence_kind", "CONTROLLED_FIXTURE")
        if (!json.has("controlled_state")) json.put("controlled_state", true)
        val configuration = context.resources.configuration
        json.put("capture_method", "UI_AUTOMATION_ORIGINAL_PNG")
            .put("api_level", android.os.Build.VERSION.SDK_INT)
            .put("device_model", android.os.Build.MODEL)
            .put("device_kind", InstrumentationRegistry.getArguments().getString("deviceKind") ?: "UNCLASSIFIED_DEVICE")
            .put("theme", if (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) "DARK" else "LIGHT")
            .put("viewport", if (configuration.smallestScreenWidthDp >= 600) "TABLET" else "PHONE")
            .put("font_scale", configuration.fontScale)
        File(directory, "$name.json").writeText(json.toString(2))
        json.optString("accessibility_window_xml").takeIf { it.isNotBlank() }?.let {
            File(directory, "$name.xml").writeText(it)
        }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Device screenshot unavailable for $name"
        }
        File(directory, "$name.png").outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        screenshot.recycle()
    }

    /** Keep unrelated instrumented tests independent of runtime installation order. */
    class SavedRuntime {
        private val date = GeneratedRealQuantSnapshot.snapshotDate
        private val priceDate = GeneratedRealQuantSnapshot.priceCutoffDate
        private val dartFile = GeneratedRealQuantSnapshot.dartFileName
        private val rows = GeneratedRealQuantSnapshot.rows.toList()
        private val m01 = GeneratedRealQuantSnapshot.m01Available
        private val m02 = GeneratedRealQuantSnapshot.m02Available
        private val m03 = GeneratedRealQuantSnapshot.m03Available
        private val m04 = GeneratedRealQuantSnapshot.m04Available
        private val complete = GeneratedRealQuantSnapshot.completeCount
        private val safety = FinancialSafetyRepository.currentSnapshot()
        private val candidateDate = FinalCandidateRuntimeStore.installedSnapshotDate()
        private val candidates = FinalCandidateRuntimeStore.current(candidateDate).toList()

        fun restore() {
            GeneratedRealQuantSnapshot.installRuntimeSnapshot(
                date, priceDate, dartFile, rows, m01, m02, m03, m04, complete,
            )
            FinancialSafetyRuntimeStore.clear()
            safety?.let(FinancialSafetyRuntimeStore::install)
            FinalCandidateRuntimeStore.clear()
            if (candidateDate.isNotBlank()) FinalCandidateRuntimeStore.install(candidateDate, candidates)
        }
    }
}
