package com.coach.android

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only the TON-1292 synthetic paper, on an isolated server. No answer injection through HTTP.
 * Run with coachDevice/coachToken/coachPort, coachForm (portrait/landscape), and optional
 * coachReopen=true after force-stop. Screenshots contain only the synthetic paper.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticalMockSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private var stage = "launch"
    private val scrollTrace = mutableListOf<String>()

    @Test fun syntheticStepByStepAndReopen() {
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port == 19292) { "This smoke is restricted to the TON-1292 isolated port" }
        require(context.packageName.endsWith(".ton1292"))
        val form = requireNotNull(args.getString("coachForm"))
        require(form in setOf("portrait", "landscape"))
        val reopen = args.getString("coachReopen") == "true"
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        KeystoreCredentials(context, base).save(PairedCredentials(
            requireNotNull(args.getString("coachDevice")), requireNotNull(args.getString("coachToken"))))
        if (!reopen) context.deleteDatabase("coach-client.db")
        try {
            context.startActivity(Intent(context, CoachActivity::class.java)
                .putExtra("coachUrl", base).putExtra("tab", "REVIEW")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            tap(await(30_000) { it.text?.toString() == "模拟考与单选速刷" })
            if (reopen) {
                stage = "reopen"
                seek { it.text?.toString() == "7 / 12" }.let(::tap)
            } else {
                stage = "start"
                tap(await { it.text?.toString() == "开考" })
                await { it.text?.toString()?.startsWith("开始「TON-1292 合成分步验收") == true }
                tap(await { it.text?.toString() == "开考" })
                stage = "input-a"
                workingField("(a) 解题步骤")
                // Full rubric: final-only credit must work without any working text.
                // Move the current part's final field into view without choosing the next part.
                finalAfter("(a) 解题步骤").let { set(it, "abc") }
                await { it.text?.toString()?.startsWith("请输入十进制数") == true }
                shot("$form-invalid")
                set(finalAfter("(a) 解题步骤"), "4.0")
                await { it.text?.toString()?.startsWith("须保留两位小数") == true }
                shot("$form-precision")
                set(finalAfter("(a) 解题步骤"), "4.00")
                await { it.text?.toString()?.startsWith("格式正确") == true }
                stage = "input-b"
                set(workingField("(b) 解题步骤"), "20 - 2 * 4 = 12")
                set(finalAfter("(b) 解题步骤"), "13.00")
                shot("$form-before-submit-b")
                stage = "blank-c"
                seek { it.text?.toString()?.contains("Leave blank to verify missing work") == true }
                assertNull(find { it.text?.toString() == "标准图 · 交卷后对照" })
                shot("$form-before-submit-c")
                stage = "submit"
                tap(await { it.text?.toString() == "交卷" })
                await { it.text?.toString() == "交卷？" }
                tap(await { it.text?.toString() == "交卷" })
            }
            stage = "score"
            // The real worker and review complete asynchronously in the isolated server.
            await(timeout = 60_000) { it.text?.toString() == "7 / 12 分" }
            shot("$form-${if (reopen) "reopened" else "submitted"}-score")
            stage = "figure"
            seek { it.text?.toString() == "标准图 · 交卷后对照" }
            // Leave room below the heading for the actual SVG, not just its title.
            find { it.isScrollable }?.let { scrollOverlapping(it, true) }
            Thread.sleep(350)
            shot("$form-figure")
            stage = "wrong-step"
            seek { it.text?.toString()?.contains("错误发生在抄写结果这一步") == true }
            shot("$form-wrong-step")
            val config = context.resources.configuration
            assertEquals("Wrong device orientation", form == "landscape", config.screenWidthDp > config.screenHeightDp)
            if (form == "landscape") assertTrue("Not a tablet width", config.screenWidthDp >= 800)
            java.io.File(context.cacheDir, "$form-device.txt").writeText(
                "Synthetic UI journey passed; reopened=$reopen; ${config.screenWidthDp}x${config.screenHeightDp}dp; score=7/12\n")
        } catch (failure: Throwable) {
            dumpEditors()
            shot("$form-failed-$stage")
            throw failure
        }
    }

    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text, node.hintText,
        node.contentDescription).joinToString(" ") + (0 until node.childCount).joinToString(" ") {
        node.getChild(it)?.let(::label).orEmpty()
    }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        ui.clearCache()
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val bounds = Rect().also(n::getBoundsInScreen)
            if (n.isVisibleToUser && !bounds.isEmpty && match(n)) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        return walk(ui.rootInActiveWindow)
    }

    private fun await(timeout: Long = 15_000, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            find(match)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Expected node missing at $stage")
    }

    private fun seek(timeout: Long = 15_000, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        seekNode(timeout) { find(match) }

    private fun seekNode(timeout: Long, current: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + timeout
        var forward = true
        var reversed = false
        while (SystemClock.elapsedRealtime() < end) {
            current()?.let { return it }
            val scroll = find { it.isScrollable }
            val before = visibleContent()
            if (scroll != null) scrollOverlapping(scroll, forward)
            Thread.sleep(350)
            current()?.let { return it }
            scrollTrace += "$stage forward=$forward scroll=${scroll?.let { Rect().also(it::getBoundsInScreen) }} " +
                nodes().mapNotNull { it.text?.toString() }.joinToString(" | ").take(1600)
            if (!reversed && scroll != null && before == visibleContent()) {
                forward = false
                reversed = true
            }
        }
        throw AssertionError("Expected node not reachable at $stage")
    }

    private fun scrollOverlapping(scroll: AccessibilityNodeInfo, forward: Boolean) {
        val bounds = Rect().also(scroll::getBoundsInScreen)
        val window = Rect().also { ui.rootInActiveWindow?.getBoundsInScreen(it) }
        if (!bounds.intersect(window) || bounds.height() < 10) return
        // Compose can prune off-screen editors. Keep 60% overlap so a small editor
        // is exposed to the next semantic traversal instead of skipping a whole page.
        val x = bounds.exactCenterX()
        val start = bounds.top + bounds.height() * if (forward) 0.8f else 0.4f
        val finish = bounds.top + bounds.height() * if (forward) 0.4f else 0.8f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Cannot scroll at $stage", ui.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        for (step in 1..20) {
            Thread.sleep(10)
            send(MotionEvent.ACTION_MOVE, start + (finish - start) * step / 20)
        }
        Thread.sleep(150) // Release without a fling, preserving the intended overlap.
        send(MotionEvent.ACTION_UP, finish)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        ui.clearCache()
        ui.rootInActiveWindow?.let { it.refresh(); walk(it) }
        return result
    }

    private fun visibleContent(): List<String> = nodes().filter { it.isVisibleToUser }.map {
        val bounds = Rect().also(it::getBoundsInScreen)
        "${it.text}|${it.hintText}|$bounds"
    }

    private fun editors(): List<AccessibilityNodeInfo> = nodes().filter { it.isEditable }

    private fun showField(current: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        // Do not require visibility to match a semantic editor. Scroll with overlap
        // if Compose has not exposed it yet, then request and refresh the exact node.
        seekNode(15_000, current).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        Thread.sleep(300)
        return (current() ?: throw AssertionError("Editor disappeared at $stage")).also {
            assertTrue("Stale editor at $stage", it.refresh())
        }
    }

    private fun workingField(workingLabel: String): AccessibilityNodeInfo = showField {
        editors().firstOrNull { label(it).contains(workingLabel) }
    }

    private fun finalAfter(workingLabel: String): AccessibilityNodeInfo {
        // All subparts belong to one composed question. Follow semantic tree order rather
        // than comparing y coordinates, which change while scrolling a long phone page.
        fun current(): AccessibilityNodeInfo? {
            val editors = editors()
            val index = editors.indexOfFirst { label(it).contains(workingLabel) }
            if (index < 0) return null
            return editors.drop(index + 1).firstOrNull { label(it).contains("最终数值") }
        }
        return showField(::current)
    }

    private fun dumpEditors() {
        val diagnostic = editors().mapIndexed { index, editor ->
            val bounds = Rect().also(editor::getBoundsInScreen)
            "editor[$index] label=<${label(editor)}> visible=${editor.isVisibleToUser} bounds=$bounds"
        }.joinToString("\n", prefix = "Synthetic input diagnostics at $stage:\n", postfix = "\n")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", diagnostic + scrollTrace.takeLast(15).joinToString("\n", postfix = "\n"))
        })
    }

    private fun set(node: AccessibilityNodeInfo, value: String) {
        assertTrue("Cannot type at $stage", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        // Validation can reflow the form and move this editor outside the viewport.
        // Read back this exact node, not any visible field containing a substring.
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (node.refresh() && node.text?.toString() == value) return
            Thread.sleep(100)
        }
        throw AssertionError("Typed value missing at $stage: expected <$value>, read <${node.text}>")
    }

    private fun tap(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: throw AssertionError("No clickable parent at $stage")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        ui.takeScreenshot()?.let { bitmap ->
            java.io.File(context.cacheDir, "ton1292-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
