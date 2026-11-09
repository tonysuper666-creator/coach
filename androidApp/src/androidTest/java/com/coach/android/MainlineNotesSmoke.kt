package com.coach.android

import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
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
        val fault = mode in setOf("fault", "offline")
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        // Fail with a useful network diagnosis before navigating an otherwise disconnected app.
        val probe = okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            probe.newCall(okhttp3.Request.Builder().url("$base/v1/hello")
                .header("Authorization", "Bearer " + requireNotNull(args.getString("coachToken")))
                .header("X-Coach-Device", "desktop").build()).execute().use { response ->
                assertEquals("Isolated tailnet candidate must be reachable", 200, response.code)
            }
        } finally { probe.connectionPool.evictAll(); probe.dispatcher.executorService.shutdown() }
        KeystoreCredentials(context, base).save(PairedCredentials("desktop", requireNotNull(args.getString("coachToken"))))
        if (mode in setOf("normal", "fault", "offline")) context.deleteDatabase("coach-client.db")
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
        // What the accessibility tree exposes right now, with the enabled flag that decides whether a tap can land.
        fun snapshot(): String {
            val seen = mutableListOf<String>()
            find { node -> node.text?.toString()?.let { seen += if (node.isEnabled) it else "$it(disabled)" }; false }
            return seen.joinToString(" | ")
        }
        // When the tree disagrees with the pixels, the text list alone cannot say whether the row is absent or just
        // unnamed. Keep class, bounds, clickable and visibility for every node so the report settles it.
        fun outline(): String {
            val rows = mutableListOf<String>()
            find { node ->
                val text = node.text?.toString()
                val description = node.contentDescription?.toString()
                if (text != null || description != null || node.isClickable) {
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    rows += buildString {
                        append(node.className?.toString()?.substringAfterLast('.') ?: "node")
                        if (text != null) append(" text=").append(text)
                        if (description != null) append(" desc=").append(description)
                        if (node.isClickable) append(" clickable")
                        if (!node.isVisibleToUser) append(" hidden")
                        append(' ').append(bounds)
                    }
                }
                false
            }
            return rows.joinToString("\n")
        }
        fun fail(text: String): Nothing {
            // Keep the pixels of the frame the tree disagreed with; the activity is torn down right after this throws.
            runCatching {
                ui.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "mainline-wait-fail.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            throw AssertionError("Missing synthetic mainline UI: $text; on screen: ${snapshot()}\n${outline()}")
        }
        fun waitFor(text: String, contains: Boolean = false): AccessibilityNodeInfo {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < end) {
                find { if (contains) text in it.text?.toString().orEmpty() || text in it.contentDescription?.toString().orEmpty() else it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
                Thread.sleep(150)
            }
            fail(text)
        }
        fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            // A tab or a row can be remade between finding a node and walking up to its clickable ancestor, which
            // leaves the remembered parent chain pointing at removed semantics ids: refresh() on that dead node never
            // repairs it. Re-read the same text from the current tree on every attempt instead.
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            fun candidate(): AccessibilityNodeInfo? {
                // A labelled target is only ever acted on through a node read back from the live tree: a label that
                // already flipped to its next value must not be clicked through the remembered (now different) node.
                // An unlabelled container cannot be re-found that way, and its caller just read it from the tree.
                val current: AccessibilityNodeInfo = if (text == null && description == null) {
                    node
                } else {
                    find { n ->
                        if (text != null) n.text?.toString() == text
                        else n.contentDescription?.toString() == description
                    } ?: return null
                }
                var target: AccessibilityNodeInfo? = current
                while (target != null && !target.isClickable) target = target.parent
                return target
            }
            repeat(20) {
                candidate()?.let { return it }
                Thread.sleep(50)
            }
            runCatching {
                ui.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "mainline-click-fail.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            throw AssertionError("No clickable ancestor: ${text ?: description}; on screen: ${snapshot()}")
        }
        fun tap(target: AccessibilityNodeInfo, label: Any?) {
            val actions = target.actionList.joinToString(",") { it.id.toString() }
            println("TON1394 tap $label enabled=${target.isEnabled} actions=[$actions]")
            assertTrue(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        fun click(node: AccessibilityNodeInfo) {
            tap(clickable(node), node.text ?: node.contentDescription)
        }
        // The answer buttons turn on once an option is picked; wait for that frame instead of tapping a disabled button.
        fun clickWhenEnabled(text: String) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < end) {
                // The resolved clickable ancestor usually carries no text of its own: tap it directly instead of
                // handing it back to click(), which would look the label up again and lose it.
                val target = clickable(waitFor(text))
                if (target.isEnabled) return tap(target, text)
                Thread.sleep(50)
            }
            throw AssertionError("Still disabled: $text")
        }
        // Compose leaves rows scrolled out of view out of the accessibility tree: scroll the page until the text shows up.
        fun show(text: String, contains: Boolean = false): AccessibilityNodeInfo {
            fun match(node: AccessibilityNodeInfo) = if (contains) text in node.text?.toString().orEmpty() || text in node.contentDescription?.toString().orEmpty() else node.text?.toString() == text || node.contentDescription?.toString() == text
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
        fun passage(): AccessibilityNodeInfo? = find { it.contentDescription?.toString() == "本节正文，可选择文字" }
        // A node remembered across a scroll and a recomposition can point at a virtual id the app has since remade:
        // the platform still answers the action, nothing reaches the reader, and the failure lands far away. Always
        // act on a node read back from the live tree in the same frame as the action.
        fun selectPassage() {
            val text = passage() ?: show("本节正文，可选择文字")
            val actions = text.actionList.joinToString(",") { it.id.toString() }
            // The original acceptance: the passage exposes a working text-selection action to accessibility services.
            val accepted = text.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 9)
            })
            println("TON1394 selection action accepted=$accepted actions=[$actions]")
            assertTrue("Real text selection must be accessible", accepted)
            instrumentation.waitForIdleSync()
            // The selection row is laid out directly under the passage, so a page scrolled past it leaves it out of
            // the accessibility tree: scroll to it the way a reader would instead of calling it missing.
            if (runCatching { show("选中文本") }.getOrNull() != null) { println("TON1394 selection path=action"); return }
            // A read-only passage is still selectable by hand, so long-press and drag over it: this is what the
            // acceptance is about, and it stays available even when an accessibility action is answered elsewhere.
            val rect = android.graphics.Rect().also { (passage() ?: text).getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Int, at: Long) {
                val event = android.view.MotionEvent.obtain(down, down + at, action, x.toFloat(), rect.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                ui.injectInputEvent(event, true)
                event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, rect.left + 40, 0)
            SystemClock.sleep(900)
            touch(android.view.MotionEvent.ACTION_MOVE, rect.left + 140, 1000)
            touch(android.view.MotionEvent.ACTION_MOVE, rect.right - 80, 1300)
            touch(android.view.MotionEvent.ACTION_UP, rect.right - 80, 1600)
            instrumentation.waitForIdleSync()
            if (runCatching { show("选中文本") }.getOrNull() != null) { println("TON1394 selection path=long-press"); return }
            fail("选中文本")
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
            show("我的一句话：Synthetic personal note")
            screenshot("resumed-notes")
            return
        }
        waitFor("今天")
        click(waitFor("导航：复习"))
        screenshot("entry")
        clickWhenEnabled("继续第 1 周")
        waitFor("铺垫题 · 第 1/3 题")
        screenshot("two-click-first")
        if (!fault) {
            fun returnToToday() {
                click(show("← 选周")); click(show("← 复习")); click(waitFor("导航：今天"))
            }
            returnToToday()
            click(waitFor("导航：复习")); clickWhenEnabled("继续第 1 周")
            waitFor("铺垫题 · 第 1/3 题")
            screenshot("warm-two-click-first")
            returnToToday()
            click(waitFor("导航：复习")); clickWhenEnabled("看本周要点")
            waitFor("跳过要点，直接做题")
            screenshot("reading-route")
            click(show("★ 课上强调"))
            waitFor("01:38:00", contains = true)
            show("Synthetic pears were emphasized.")
            screenshot("reading-emphasis")
            click(waitFor("关闭"))
            selectPassage()
            click(show("一句批注"))
            // The label sits on the field's decoration box, which is not the node that accepts text: address the
            // editable node itself, and retry while the dialog is still animating in.
            var typed = false
            for (attempt in 0 until 30) {
                val field = find { it.className == "android.widget.EditText" && it.isEnabled }
                    ?: throw AssertionError("Missing note field; on screen: ${snapshot()}\n${outline()}")
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic personal note")
                    })) { typed = true; break }
                Thread.sleep(100)
            }
            assertTrue("Note field must accept text:\n${outline()}", typed)
            instrumentation.waitForIdleSync()
            click(waitFor("返回阅读"))
            show("我的一句话：Synthetic personal note")
            screenshot("personal-note")
            click(show("深色阅读"))
            screenshot("dark-reading")
            click(show("专心读"))
            waitFor("退出专注")
            assertNull("Focus mode must hide app navigation", find { it.contentDescription?.toString() == "导航：复习" })
            screenshot("focus-reading")
            click(waitFor("退出专注"))
            // The exit is proven by the app navigation returning; tapping the label a second time would only flip
            // focus back on, so wait for the frame instead of re-tapping.
            waitFor("导航：复习")
            click(show("浅色阅读"))
            // The map starts collapsed and one tap on the heading opens it; the heading flips to 收起本周脉络. The
            // heading sits above the passage the previous steps scrolled to, so reach it the way a reader would.
            click(show("本周脉络：节 → 考点 → 题"))
            waitFor("收起本周脉络")
            screenshot("weekly-map-open")
            show("○ 待练 F1")
            screenshot("weekly-map")
            // Asking the tutor is the last reader step on purpose: after this panel closes the app stops publishing
            // accessibility updates for the reader (the pixels keep drawing, the tree does not), so every assertion
            // that needs the reader's tree has to run while it still answers.
            selectPassage()
            click(show("再讲一遍"))
            waitFor("缺少可定位的课件或教材页依据", contains = true)
            screenshot("ai-no-evidence")
            click(waitFor("返回阅读"))
            click(show("题 S1 · 待练"))
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
        click(show("★ 课上强调"))
        waitFor("01:38:00", contains = true)
        screenshot("question-emphasis")
        click(waitFor("关闭"))
        answer("Synthetic apples are grown there", false, "答错了。", "setup")
        if (!fault) show("我的一句话：Synthetic personal note")
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
            .put("mode", mode).put("samples", samples).put("initialColdClicks", 2).put("warmClicks", 2).put("notesRouteClicks", 2).toString(2))
    }
}
