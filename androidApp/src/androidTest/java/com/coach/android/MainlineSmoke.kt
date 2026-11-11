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

/** Synthetic-only week pack from check-mainline-copy.py; invoked under tools/dev/heavy --emulator. */
@RunWith(AndroidJUnit4::class)
class MainlineSmoke {
    @Test fun oneWeekFromSetupToBridgeWithAMissAndAGuess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790, 8791, 18791, 3790))
        require(context.packageName in setOf("com.coach.android.ton1358", "com.coach.android.ton1364"))
        require(BuildConfig.COACH_COURSE == "ton1358-synthetic-mainline")
        val retryAfterCommit = args.getString("retryAfterCommit") == "true"
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        KeystoreCredentials(context, base).save(PairedCredentials("desktop", requireNotNull(args.getString("coachToken"))))
        context.deleteDatabase("coach-client.db")
        context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base).putExtra("tab", "REVIEW")
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
        // The marks and every option's reason appear in place right after the tap, before any scrolling.
        fun answer(option: String, guessed: Boolean, outcome: String, shot: String) {
            click(waitFor(option, contains = true))
            clickWhenEnabled(if (guessed) "我是猜的" else "提交")
            if (retryAfterCommit) {
                show("Synthetic response lost after commit")
                click(show("Synthetic plums ripen early", contains = true))
                click(show(if (guessed) "提交" else "我是猜的"))
            }
            show("为什么对：", contains = true)
            if (retryAfterCommit) {
                fun row(optionText: String): AccessibilityNodeInfo {
                    var node = show(optionText, contains = true)
                    while (!node.isCheckable) node = node.parent ?: throw AssertionError("Missing option radio state")
                    return node
                }
                assertTrue("the committed option stays selected", row(option).isChecked)
                assertFalse("the edited retry choice is not selected", row("Synthetic plums ripen early").isChecked)
                show(option, contains = true)
            }
            screenshot(shot)
            show(outcome)
        }

        val entry = waitFor("选择题主线")
        screenshot("entry")
        click(entry)
        waitFor("考点 3 · 有题 3 · 会了 0")
        screenshot("weeks-before")
        click(waitFor("第 1 周 · ", contains = true))

        // A miss on the setup question: the reasons for every option are already on the page.
        waitFor("基础题 · 第 1/3 题")
        answer("Synthetic apples are grown there", guessed = false, outcome = "答错。", shot = "explained")
        // Options are shuffled on the Mac, so the plums row may sit below the fold.
        show("为什么不选：Synthetic plums ripen after", contains = true)
        show("已排进复习：明天再看一次。")
        show("Synthetic lecture 1, slide 4", contains = true)
        screenshot("explained-bottom")
        click(show("下一题"))

        // Right, but marked as a guess.
        waitFor("细节题 · 第 2/3 题")
        answer("Synthetic pears are grown there", guessed = true, outcome = "猜对：不算会。", shot = "guessed-top")
        show("已排进复习：明天再看一次。")
        screenshot("guessed")
        click(show("下一题"))

        waitFor("综合题 · 第 3/3 题")
        answer("Synthetic pears are grown there", guessed = false, outcome = "答对。", shot = "bridge-top")
        assertNull("a question answered without guessing is not queued", find { it.text?.toString() == "已排进复习：明天再看一次。" })
        show("模拟考")
        show("限时论述")
        screenshot("bridge")
        click(show("← 选周"))
        waitFor("考点 3 · 有题 3 · 会了 1")
        screenshot("weeks-after")
    }
}
