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
 * optional `coachExpect` (`kpId=pdfPage,...`, the anchor page from the course content), optional `coachExpectSplit` (`true`: study page stays visible beside the source; `false`: covered). For each KP: put a marker in the composer, tap
 * 「看原文」, time tap → the anchor page's own text on screen (not the header, which also shows on failure) and no failure message, then close the source with the layout's button, check it is gone and the marker is still in the composer.
 * Retains every sample in app-cache `source-latency.json` and the first page as `source-open.png`. No model call, no learner answer.
 */
@RunWith(AndroidJUnit4::class)
class SourceOpenSmoke {
    private val marker = "keepthisdraft"
    private val BODY_MARK = Regex("〔PDF 第 (\\d+) 页")

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
        val expectSplit = args.getString("coachExpectSplit")?.toBooleanStrictOrNull()
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
                // The draft is the part of "progress" a model-free test can set and read back. Two entry paths, because each emulated window
                // size lets only one of them through: typing via the keyboard (`input text`, like a learner; the IME does not come up in
                // every overridden window) and accessibility ACTION_SET_TEXT (not applied by the wide layout's composer). Alternate until
                // the draft is observed in the composer; the path that worked and the attempts are recorded in the sample.
                var attempts = 0
                var entry = ""
                val setText = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, marker) }
                while (true) {
                    attempts++
                    val composer = waitFor(ui, 5_000) { it.isEditable }
                    entry = if (attempts % 2 == 1) "keyboard" else "set-text"
                    if (entry == "keyboard") {
                        composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        instrumentation.waitForIdleSync()
                        Thread.sleep(300)
                        ui.executeShellCommand("input text $marker").close()
                        Thread.sleep(500)
                    } else {
                        composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
                        Thread.sleep(400)
                    }
                    instrumentation.waitForIdleSync()
                    if (find(ui) { it.isEditable }?.text?.toString() == marker) break
                    assertTrue("Draft could not be entered after $attempts tries", attempts < 6)
                }
                // The prefetch starts when the thread opens; give it a human beat so the tap is what the learner would do.
                Thread.sleep(1_000)
                // Nodes from before the sleep may be stale: look the button up again right before the tap.
                val open = waitFor(ui, 5_000) { it.text?.toString()?.startsWith("看原文") == true }
                val draftBeforeTap = find(ui) { it.isEditable }?.text?.toString()
                val mark = SystemClock.elapsedRealtimeNanos()
                click(open)
                // The header names the PDF page at once, and also in the failure state, so it proves nothing. The page text itself
                // opens with the server's 〔PDF 第 N 页 · 书第 M 页〕 line: that line only exists once the body has been fetched and drawn.
                val bodyMark = waitFor(ui, 15_000) { BODY_MARK.containsMatchIn(it.text?.toString().orEmpty()) }
                val detected = SystemClock.elapsedRealtimeNanos()
                instrumentation.waitForIdleSync()
                Thread.sleep(100)
                val millis = (SystemClock.elapsedRealtimeNanos() - mark) / 1_000_000.0
                val shown = BODY_MARK.find(bodyMark.text.toString())?.groupValues?.get(1)?.toInt() ?: -1
                val header = waitFor(ui, 5_000) { "PDF 第" in it.text?.toString().orEmpty() && "正在取" !in it.text.toString() && !BODY_MARK.containsMatchIn(it.text.toString()) }.text.toString()
                val headerPage = Regex("PDF 第 (\\d+) 页").find(header)?.groupValues?.get(1)?.toInt() ?: -1
                val failureShown = find(ui) { it.text?.toString()?.startsWith("这一页取不到") == true } != null
                // Side by side: the study page's composer is still on screen next to the source. Overlay: the study page is covered.
                val studyVisible = find(ui) { it.isEditable } != null
                val draftWhileReading = find(ui) { it.isEditable }?.text?.toString()
                val form = "${context.resources.configuration.screenWidthDp}x${context.resources.configuration.screenHeightDp}dp"
                if (samples.length() == 0) {
                    val shot = ui.takeScreenshot()
                    java.io.File(context.cacheDir, "source-open.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
                // Close the source with whichever button this layout has (「← 学习」over the page, 「✕ 关闭原文」beside it), then check it is gone.
                val closeLabel = listOf("← 学习", "✕ 关闭原文").firstOrNull { label -> find(ui) { it.text?.toString() == label } != null } ?: throw AssertionError("No close button for the source page ($form)")
                click(waitFor(ui, 5_000) { it.text?.toString() == closeLabel })
                instrumentation.waitForIdleSync()
                val closeEnd = SystemClock.elapsedRealtime() + 5_000
                while (find(ui) { BODY_MARK.containsMatchIn(it.text?.toString().orEmpty()) } != null && SystemClock.elapsedRealtime() < closeEnd) Thread.sleep(20)
                val closed = find(ui) { BODY_MARK.containsMatchIn(it.text?.toString().orEmpty()) } == null
                val kept = waitFor(ui, 5_000) { it.isEditable }.text?.toString().orEmpty()
                samples.put(JSONObject().put("kp", kp).put("tapToAnchorPageMs", millis).put("visibleDetectionMs", (detected - mark) / 1_000_000.0)
                    .put("headerText", header).put("headerPdfPage", headerPage).put("shownPdfPage", shown).put("expectedPdfPage", expect[kp] ?: JSONObject.NULL)
                    .put("failureShown", failureShown).put("window", form).put("studyVisibleWhileReading", studyVisible).put("closeButton", closeLabel)
                    .put("sourceClosed", closed).put("draftEntry", entry).put("draftAttempts", attempts).put("draftBeforeTap", draftBeforeTap ?: JSONObject.NULL).put("draftWhileReading", draftWhileReading ?: JSONObject.NULL).put("draftAfterClose", kept).put("draftKept", kept == marker))
                report.writeText(samples.toString(2))
                android.util.Log.i("CoachSource", "kp=$kp tapToAnchorPageMs=$millis shown=$shown draftKept=${kept == marker}")
            }
        } finally { report.writeText(samples.toString(2)) }
        assertEquals(ids.size, samples.length())
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            assertFalse("Failure state shown: $s", s.getBoolean("failureShown"))
            assertEquals("Header and page text disagree: $s", s.getInt("headerPdfPage"), s.getInt("shownPdfPage"))
            assertTrue("Source still open after the close button: $s", s.getBoolean("sourceClosed"))
            assertTrue("Draft lost after returning: $s", s.getBoolean("draftKept"))
            expectSplit?.let { assertEquals("Layout differs from the expected one: $s", it, s.getBoolean("studyVisibleWhileReading")) }
            if (!s.isNull("expectedPdfPage")) assertEquals("Wrong page: $s", s.getInt("expectedPdfPage"), s.getInt("shownPdfPage"))
            assertTrue("Sample $i exceeded 1.5 s: $s", s.getDouble("tapToAnchorPageMs") <= 1500.0)
        }
    }

    private fun find(ui: android.app.UiAutomation, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (match(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(ui.rootInActiveWindow)
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
