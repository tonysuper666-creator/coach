package com.coach.android

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic-only real UI timing and recovery; disposable tailnet candidate, never production. */
@RunWith(AndroidJUnit4::class)
class MainlineNotesSmoke {
    @Test fun readFilterAnswerAndResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790, 8791, 18791, 3790))
        require(context.packageName == "com.coach.android.ton1394")
        require(BuildConfig.COACH_COURSE == "ton1358-synthetic-mainline")
        val mode = args.getString("mode") ?: "normal"
        val fault = mode == "fault"
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        KeystoreCredentials(context, base).save(PairedCredentials("desktop", requireNotNull(args.getString("coachToken"))))
        if (mode in setOf("normal", "fault")) context.deleteDatabase("coach-client.db")
        context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (match(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return walk(ui.rootInActiveWindow)
        }
        fun waitFor(text: String, contains: Boolean = false): AccessibilityNodeInfo {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < end) {
                find { if (contains) text in it.text?.toString().orEmpty() else it.text?.toString() == text }?.let { return it }
                Thread.sleep(50)
            }
            val seen = mutableListOf<String>()
            find { node -> node.text?.toString()?.let { seen += it }; false }
            throw AssertionError("Missing synthetic mainline UI: $text; on screen: ${seen.joinToString(" | ")}")
        }
        fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var target = node
            while (!target.isClickable) target = target.parent ?: throw AssertionError("No clickable ancestor")
            return target
        }
        fun click(node: AccessibilityNodeInfo) {
            val target = clickable(node)
            assertTrue(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        // The answer buttons turn on once an option is picked; wait for that frame instead of tapping a disabled button.
        fun clickWhenEnabled(text: String) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < end) {
                val target = clickable(waitFor(text))
                if (target.isEnabled) return click(target)
                Thread.sleep(50)
            }
            throw AssertionError("Still disabled: $text")
        }
        // Compose leaves rows scrolled out of view out of the accessibility tree: scroll the page until the text shows up.
        fun show(text: String, contains: Boolean = false): AccessibilityNodeInfo {
            fun match(node: AccessibilityNodeInfo) = if (contains) text in node.text?.toString().orEmpty() else node.text?.toString() == text
            for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                repeat(12) {
                    find(::match)?.let { node ->
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                        instrumentation.waitForIdleSync()
                        return node
                    }
                    val page = find { it.isScrollable } ?: return waitFor(text, contains)
                    if (!page.performAction(direction)) return@repeat
                    instrumentation.waitForIdleSync()
                    Thread.sleep(150)
                }
            }
            return waitFor(text, contains)
        }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            val bitmap = requireNotNull(ui.takeScreenshot())
            java.io.File(context.cacheDir, "mainline-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val samples = JSONArray()
        fun answer(option: String, guessed: Boolean, outcome: String, shot: String) {
            click(show(option, contains = true))
            val submit = if (guessed) "我是猜的" else "提交"
            show(submit)
            val ready = SystemClock.elapsedRealtime() + 5000
            while (!clickable(waitFor(submit)).isEnabled && SystemClock.elapsedRealtime() < ready) Thread.sleep(10)
            val started = SystemClock.elapsedRealtimeNanos()
            click(clickable(waitFor(submit)))
            if (fault) {
                waitFor("答案已保存在本机，正在自动重连", contains = true)
                assertFalse(clickable(waitFor(submit)).isEnabled)
                screenshot("fault-waiting")
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (find { it.isVisibleToUser && "为什么" in it.text?.toString().orEmpty() } == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            assertNotNull("Explanation must appear automatically", find { it.isVisibleToUser && "为什么" in it.text?.toString().orEmpty() })
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            samples.put(JSONObject().put("item", shot).put("ms", elapsed).put("fault", fault).put("utc", java.time.Instant.now().toString()))
            screenshot(shot)
            show(outcome)
        }
        if (mode == "resumeQuiz") {
            waitFor("桥接题 · 第 3/3 题")
            show("答对了，算掌握。")
            show("Synthetic lecture 1, slide 4", contains = true)
            screenshot("resumed-quiz")
            click(show("本周要点"))
            click(show("○ Synthetic basics"))
            show("Synthetic apples are sold out.", contains = true)
            screenshot("reading-before-restart")
            return
        }
        if (mode == "resumeNotes") {
            waitFor("本周要点")
            show("Synthetic apples are sold out.", contains = true)
            screenshot("resumed-notes")
            return
        }
        waitFor("今天")
        click(waitFor("↻"))
        screenshot("entry")
        clickWhenEnabled("继续第 1 周")
        waitFor("铺垫题 · 第 1/3 题")
        screenshot("two-click-first")
        if (!fault) {
            fun returnToToday() {
                click(show("← 选周")); click(show("← 复习")); click(waitFor("◉"))
            }
            returnToToday()
            click(waitFor("↻")); clickWhenEnabled("继续第 1 周")
            waitFor("铺垫题 · 第 1/3 题")
            screenshot("warm-two-click-first")
            returnToToday()
            click(waitFor("↻")); clickWhenEnabled("看本周要点")
            waitFor("跳过要点，直接做题")
            screenshot("reading-route")
            click(show("跳过要点，直接做题"))
            waitFor("铺垫题 · 第 1/3 题")
        }
        // Notes route can select a section and expose only its linked questions.
        click(show("本周要点"))
        waitFor("本周要点")
        screenshot("notes")
        click(show("Synthetic bridge notes", contains = true))
        click(show("做这一节的题"))
        waitFor("桥接题 · 第 1/1 题")
        screenshot("filtered")
        click(show("本周要点"))
        click(show("跳过要点，直接做题"))
        waitFor("铺垫题 · 第 1/3 题")
        answer("Synthetic apples are grown there", false, "答错了。", "setup")
        show("为什么不选：Synthetic plums ripen after", contains = true)
        show("已排进复习：明天再看一次。")
        show("回看要点：Synthetic basics")
        screenshot("setup-bottom")
        click(show("下一题"))
        waitFor("细节题 · 第 2/3 题")
        answer("Synthetic pears are grown there", true, "答对了，但标了猜的：不算掌握。", "detail")
        click(show("下一题"))
        waitFor("桥接题 · 第 3/3 题")
        answer("Synthetic pears are grown there", false, "答对了，算掌握。", "bridge")
        show("步骤批改")
        show("限时论述")
        screenshot("bridge-bottom")
        if (!fault) repeat(17) { n ->
            click(show("本周要点"))
            click(show("重新读取本周内容"))
            waitFor("跳过要点，直接做题")
            clickWhenEnabled("跳过要点，直接做题")
            waitFor("桥接题 · 第 3/3 题")
            answer("Synthetic pears are grown there", false, "答对了，算掌握。", "timed-$n")
        }
        // A section is mastered only when all its facts/questions are mastered; guessed detail stays open.
        click(show("本周要点"))
        show("○ Synthetic basics")
        show("✓ Synthetic bridge notes")
        screenshot("mastery")
        click(show("跳过要点，直接做题"))
        show("答对了，算掌握。")
        java.io.File(context.cacheDir, "mainline-timings.json").writeText(JSONObject()
            .put("mode", mode).put("samples", samples).put("initialColdClicks", 2).put("warmClicks", 2).put("notesRouteClicks", 3).toString(2))
    }
}
