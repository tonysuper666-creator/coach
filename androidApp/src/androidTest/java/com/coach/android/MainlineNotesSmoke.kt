package com.coach.android

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import com.coach.client.SubmissionTrace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic-only real UI timing and recovery; disposable tailnet candidate, never production. */
@RunWith(AndroidJUnit4::class)
class MainlineNotesSmoke {
    @get:Rule val compose = createEmptyComposeRule()

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
        // Opt-in diagnosis only (instrumentation arg ton1394Trace=1); off leaves the run unchanged.
        SubmissionTrace.enabled = args.getString("ton1394Trace") == "1"
        SubmissionTrace.clear()
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
        // Query Compose's current semantics directly. UiAutomation's cached accessibility tree can stop
        // updating after a selection/dialog/focus transition even while Compose keeps rendering new frames.
        fun target(text: String, contains: Boolean = false): SemanticsNodeInteraction = compose.onAllNodes(
            hasText(text, substring = contains) or hasContentDescription(text, substring = contains)).onFirst()
        fun fail(text: String): Nothing {
            runCatching {
                ui.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "mainline-wait-fail.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            throw AssertionError("Missing synthetic mainline UI: $text\n${compose.onAllNodes(isRoot()).printToString(maxDepth = 50)}")
        }
        fun waitFor(text: String, contains: Boolean = false): SemanticsNodeInteraction {
            val node = target(text, contains)
            try {
                compose.waitUntil(timeoutMillis = 30_000) { runCatching { node.fetchSemanticsNode() }.isSuccess }
            } catch (_: ComposeTimeoutException) { fail(text) }
            return node
        }
        var clicks = 0
        var coldClicks: Int? = null
        var warmClicks: Int? = null
        var notesClicks: Int? = null
        fun click(node: SemanticsNodeInteraction) {
            node.assertIsEnabled().performClick()
            clicks++
        }
        fun clickWhenEnabled(text: String) {
            val node = waitFor(text)
            compose.waitUntil(timeoutMillis = 10_000) { runCatching { node.assertIsEnabled() }.isSuccess }
            click(node)
        }
        fun show(text: String, contains: Boolean = false): SemanticsNodeInteraction {
            val node = waitFor(text, contains)
            // Static controls and dialog rows have no scroll parent. A clipped row must be scrolled into view;
            // assertIsDisplayed below still fails if scrolling was unsuccessful.
            if (!node.isDisplayed()) node.performScrollTo()
            node.assertIsDisplayed()
            return node
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
        fun selectPassage() {
            val passage = compose.onNodeWithTag("reader-text").performScrollTo()
            var accepted = false
            passage.performSemanticsAction(SemanticsActions.SetSelection) { accepted = it(0, 9, false) }
            assertTrue("Reader semantics must accept real text selection", accepted)
            show("选中文本")
        }
        val samples = JSONArray()
        fun answer(option: String, guessed: Boolean, outcome: String, shot: String) {
            click(show(option, contains = true))
            val submit = if (guessed) "我是猜的" else "提交"
            show(submit)
            val ready = SystemClock.elapsedRealtime() + 5000
            while (!runCatching { waitFor(submit).assertIsEnabled() }.isSuccess && SystemClock.elapsedRealtime() < ready) Thread.sleep(10)
            val startedUtcMs = System.currentTimeMillis()
            SubmissionTrace.clear()
            val started = SystemClock.elapsedRealtimeNanos()
            val button = waitFor(submit)
            button.assertIsEnabled()
            val located = SystemClock.elapsedRealtimeNanos()
            SubmissionTrace.mark("test", "test.clickStart")
            button.performClick()
            clicks++
            val dispatched = SystemClock.elapsedRealtimeNanos()
            if (fault) {
                waitFor("答案已保存在本机，正在自动重连", contains = true)
                waitFor(submit).assertIsNotEnabled()
                screenshot("fault-waiting")
            }
            val explanations = compose.onAllNodes(hasText("为什么", substring = true))
            val polls = JSONArray()
            fun explanationVisible(): Boolean {
                SubmissionTrace.mark("test", "test.queryStart")
                val began = SystemClock.elapsedRealtimeNanos()
                val nodes = explanations.fetchSemanticsNodes()
                val fetched = SystemClock.elapsedRealtimeNanos()
                val displayed = nodes.indices.any { explanations[it].isDisplayed() }
                SubmissionTrace.mark("test", "test.queryEnd")
                polls.put(JSONObject().put("nodes", nodes.size).put("displayed", displayed)
                    .put("fetchMs", (fetched - began) / 1_000_000.0)
                    .put("displayMs", (SystemClock.elapsedRealtimeNanos() - fetched) / 1_000_000.0))
                return displayed
            }
            compose.waitUntil(timeoutMillis = 15_000) { explanationVisible() }
            val visible = SystemClock.elapsedRealtimeNanos()
            assertTrue("Explanation must appear automatically", explanationVisible())
            val verified = SystemClock.elapsedRealtimeNanos()
            fun ms(from: Long, to: Long) = (to - from) / 1_000_000.0
            // Keep the original conservative total and timer boundaries. These segments expose
            // synchronization cost without subtracting it from the 800ms acceptance gate.
            samples.put(JSONObject().put("item", shot).put("ms", ms(started, verified))
                .put("locateMs", ms(started, located)).put("dispatchMs", ms(located, dispatched))
                .put("awaitVisibleMs", ms(dispatched, visible)).put("verifyMs", ms(visible, verified))
                .put("visibilityPolls", polls)
                .put("trace", JSONArray().also { arr -> SubmissionTrace.snapshot().forEach { arr.put(JSONObject().put("id", it.id).put("event", it.event).put("ns", it.nanos)) } }).put("startedUtcMs", startedUtcMs)
                .put("fault", fault).put("utc", java.time.Instant.now().toString()))
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
        val coldStart = clicks
        click(waitFor("导航：复习"))
        screenshot("entry")
        clickWhenEnabled("继续第 1 周")
        waitFor("铺垫题 · 第 1/3 题")
        coldClicks = clicks - coldStart
        assertTrue("Cold entry must reach the first question within two clicks", coldClicks <= 2)
        screenshot("two-click-first")
        if (!fault) {
            fun returnToToday() {
                click(show("← 选周")); click(show("← 复习")); click(waitFor("导航：今天"))
            }
            returnToToday()
            val warmStart = clicks
            click(waitFor("导航：复习")); clickWhenEnabled("继续第 1 周")
            waitFor("铺垫题 · 第 1/3 题")
            warmClicks = clicks - warmStart
            assertTrue("Warm entry must reach the first question within two clicks", warmClicks <= 2)
            screenshot("warm-two-click-first")
            returnToToday()
            val notesStart = clicks
            click(waitFor("导航：复习")); clickWhenEnabled("看本周要点")
            waitFor("跳过要点，直接做题")
            notesClicks = clicks - notesStart
            screenshot("reading-route")
            click(show("★ 课上强调"))
            waitFor("01:38:00", contains = true)
            show("Synthetic pears were emphasized.")
            screenshot("reading-emphasis")
            click(waitFor("关闭"))
            selectPassage()
            click(show("一句批注"))
            compose.onNodeWithTag("reader-note").performTextInput("Synthetic personal note")
            compose.onNodeWithTag("reader-note").assertTextContains("Synthetic personal note")
            click(waitFor("返回阅读"))
            show("我的一句话：Synthetic personal note")
            screenshot("personal-note")
            click(show("深色阅读"))
            screenshot("dark-reading")
            click(show("专心读"))
            waitFor("退出专注")
            target("导航：复习").assertDoesNotExist()
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
            // Keep the selection-only tutor evidence check and subsequent map navigation in the same run.
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
            .put("mode", mode).put("samples", samples).put("initialColdClicks", coldClicks)
            .put("warmClicks", warmClicks ?: JSONObject.NULL).put("notesRouteClicks", notesClicks ?: JSONObject.NULL).toString(2))
    }
}
