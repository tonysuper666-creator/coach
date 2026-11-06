package com.coach.android

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
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
                seek { it.isEditable && label(it).contains("(a) 解题步骤") }
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
                set(seek { it.isEditable && label(it).contains("(b) 解题步骤") }, "20 - 2 * 4 = 12")
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
            shot("$form-failed-$stage")
            throw failure
        }
    }

    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text, node.hintText,
        node.contentDescription).joinToString(" ") + (0 until node.childCount).joinToString(" ") {
        node.getChild(it)?.let(::label).orEmpty()
    }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
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

    private fun seek(timeout: Long = 15_000, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            find(match)?.let { return it }
            val scroll = find { it.isScrollable }
            if (scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                // Result score may still be awaiting review; don't inject data or call submit again.
                Thread.sleep(300)
            } else Thread.sleep(250)
        }
        throw AssertionError("Expected node not reachable at $stage")
    }

    private fun finalAfter(workingLabel: String): AccessibilityNodeInfo {
        // All subparts belong to one composed question. Follow semantic tree order rather
        // than comparing y coordinates, which change while scrolling a long phone page.
        fun fields(): List<AccessibilityNodeInfo> {
            val editors = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isEditable) editors += node
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(ui.rootInActiveWindow)
            return editors
        }
        fun current(): AccessibilityNodeInfo {
            val editors = fields()
            val index = editors.indexOfFirst { label(it).contains(workingLabel) }
            assertTrue("Working field missing: $workingLabel", index >= 0)
            return editors.drop(index + 1).firstOrNull { label(it).contains("最终数值") }
                ?: throw AssertionError("Final field missing: $workingLabel")
        }
        current().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        Thread.sleep(300)
        return current()
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
