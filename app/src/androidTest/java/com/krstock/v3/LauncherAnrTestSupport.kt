package com.krstock.v3

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.StringWriter

/** Recover only the independently observed API34 Pixel Launcher infrastructure ANR. */
internal object LauncherAnrTestSupport {
    fun restoreExistingActivityIfLauncherAnr(activity: ComponentActivity): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val serviceInfo = automation.serviceInfo
        if (serviceInfo.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = serviceInfo
        }
        val root = automation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "android") return false
        val nodes = descendants(root)
        val title = nodes.firstOrNull {
            it.packageName?.toString() == "android" && it.text?.toString() in setOf(
                "Pixel Launcher isn't responding", "Pixel Launcher isn’t responding",
            )
        } ?: return false
        val close = nodes.singleOrNull {
            it.packageName?.toString() == "android" &&
                it.viewIdResourceName == "android:id/aerr_close" && it.isEnabled && it.isClickable
        } ?: return false

        // Retain the actual obstruction before any recovery; never dismiss a KR4
        // ANR or a dialog identified only by a generic "Close app" label.
        SnapshotTestSupport.evidence(
            instrumentation.targetContext, "external-launcher-anr-before-recovery",
            JSONObject().put("external_anr_title", title.text.toString())
                .put("external_anr_close_id", close.viewIdResourceName)
                .put("accessibility_window_xml", windowXml(root))
                .put("recovery_action", "CLOSE_EXTERNAL_LAUNCHER_ANR_AND_REORDER_EXISTING_ACTIVITY"),
        )
        check(close.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Confirmed external Pixel Launcher ANR close action failed"
        }
        instrumentation.runOnMainSync {
            check(!activity.isFinishing && !activity.isDestroyed)
            activity.startActivity(Intent(activity, activity.javaClass).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ))
        }
        Log.i("KR4TestReadiness", "Closed confirmed android-owned Pixel Launcher ANR; reordered existing activity")
        return true
    }

    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
    }

    private fun windowXml(root: AccessibilityNodeInfo): String {
        val output = StringWriter()
        val serializer = Xml.newSerializer().apply { setOutput(output); startDocument("UTF-8", true) }
        fun write(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            serializer.startTag(null, "node")
                .attribute(null, "package", node.packageName?.toString().orEmpty())
                .attribute(null, "class", node.className?.toString().orEmpty())
                .attribute(null, "text", node.text?.toString().orEmpty())
                .attribute(null, "content-desc", node.contentDescription?.toString().orEmpty())
                .attribute(null, "resource-id", node.viewIdResourceName.orEmpty())
                .attribute(null, "bounds", bounds.toShortString())
                .attribute(null, "enabled", node.isEnabled.toString())
                .attribute(null, "clickable", node.isClickable.toString())
            for (index in 0 until node.childCount) node.getChild(index)?.let(::write)
            serializer.endTag(null, "node")
        }
        serializer.startTag(null, "hierarchy")
        write(root)
        serializer.endTag(null, "hierarchy")
        serializer.endDocument()
        return output.toString()
    }
}
