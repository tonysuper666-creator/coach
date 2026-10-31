package com.coach.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 学习页「看原文」：explicit OPPO + isolated server replay. Parameters: `coachPort`, `coachDevice`, `coachToken`, `coachKps` (comma-separated),
 * optional `coachExpect` (`kpId=pdfPage,...`, the anchor page from the course content). For each KP: put a marker in the composer, tap
 * 「看原文」, time tap → the anchor page's text loaded (header no longer says 正在取), then go back and check the marker is still in the composer.
 * Retains every sample in app-cache `source-latency.json` and the first page as `source-open.png`. No model call, no learner answer.
 */
@RunWith(AndroidJUnit4::class)
class SourceOpenSmoke {
    private val marker = "keep-this-draft"

    @Test fun tapToAnchorPageAcrossKnowledgePoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        val args: Bundle = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790, 8791, 18791, 3790))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        val ids = requireNotNull(args.getString("coachKps")).split(',')
        val expect = args.getString("coachExpect").orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        KeystoreCredentials(context, base).save(PairedCredentials(requireNotNull(args.getString("coachDevice")), requireNotNull(args.getString("coachToken"))))
        context.deleteDatabase("coach-client.db")
        val samples = JSONArray()
        val report = java.io.File(context.cacheDir, "source-latency.json")
        try {
            for (kp in ids) {
                context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base).putExtra("openKp", kp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                waitFor(ui, 20_000) { it.text?.toString()?.startsWith("看原文") == true }
                instrumentation.waitForIdleSync()
                // The draft is the part of "progress" a model-free test can set and read back.
                val composer = waitFor(ui, 5_000) { it.isEditable }
                val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, marker) }
                assertTrue(composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle))
                instrumentation.waitForIdleSync()
                // The prefetch starts when the thread opens; give it a human beat so the tap is what the learner would do.
                Thread.sleep(1_000)
                // Nodes from before the sleep may be stale: look the button up again right before the tap.
                val open = waitFor(ui, 5_000) { it.text?.toString()?.startsWith("看原文") == true }
                val mark = SystemClock.elapsedRealtimeNanos()
                click(open)
                // The header names the PDF page at once but says 正在取 until the page text is in: wait for the settled header.
                val text = waitFor(ui, 15_000) { val t = it.text?.toString().orEmpty(); "PDF 第" in t && "正在取" !in t }.text.toString()
                val detected = SystemClock.elapsedRealtimeNanos()
                instrumentation.waitForIdleSync()
                Thread.sleep(100)
                val millis = (SystemClock.elapsedRealtimeNanos() - mark) / 1_000_000.0
                val shown = Regex("PDF 第 (\\d+) 页").find(text)?.groupValues?.get(1)?.toInt() ?: -1
                if (samples.length() == 0) {
                    val shot = ui.takeScreenshot()
                    java.io.File(context.cacheDir, "source-open.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
                // Back to the study page: the draft is still there.
                click(waitFor(ui, 5_000) { it.text?.toString()?.contains("学习") == true && it.text.toString().startsWith("←") })
                instrumentation.waitForIdleSync()
                val kept = waitFor(ui, 5_000) { it.isEditable }.text?.toString().orEmpty()
                samples.put(JSONObject().put("kp", kp).put("tapToAnchorPageMs", millis).put("visibleDetectionMs", (detected - mark) / 1_000_000.0)
                    .put("headerText", text).put("shownPdfPage", shown).put("expectedPdfPage", expect[kp] ?: JSONObject.NULL).put("draftKept", kept == marker))
                report.writeText(samples.toString(2))
                android.util.Log.i("CoachSource", "kp=$kp tapToAnchorPageMs=$millis shown=$shown draftKept=${kept == marker}")
            }
        } finally { report.writeText(samples.toString(2)) }
        assertEquals(ids.size, samples.length())
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            assertTrue("Draft lost after returning: $s", s.getBoolean("draftKept"))
            if (!s.isNull("expectedPdfPage")) assertEquals("Wrong page: $s", s.getInt("expectedPdfPage"), s.getInt("shownPdfPage"))
            assertTrue("Sample $i exceeded 1.5 s: $s", s.getDouble("tapToAnchorPageMs") <= 1500.0)
        }
    }

    private fun waitFor(ui: android.app.UiAutomation, timeout: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (match(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { find(ui.rootInActiveWindow)?.let { return it }; Thread.sleep(10) }
        throw AssertionError("Expected node did not appear within ${timeout}ms")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: throw AssertionError("Node has no clickable ancestor")
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
