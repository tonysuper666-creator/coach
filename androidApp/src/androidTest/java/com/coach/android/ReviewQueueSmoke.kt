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

/** Synthetic-only fixture from check-review-copy.py; invoked under tools/dev/heavy --emulator. */
@RunWith(AndroidJUnit4::class)
class ReviewQueueSmoke {
    @Test fun todayQueueOpensPracticeDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790, 8791, 18791, 3790))
        // Any per-card isolated package (the runner passes --application-id); never the production one.
        require(Regex("com\\.coach\\.android\\.ton[0-9]+").matches(context.packageName))
        require(BuildConfig.COACH_COURSE == "ton1291-synthetic-review")
        val after = args.getString("coachPhase") == "after"
        val phase = if (after) "after" else "before"
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        KeystoreCredentials(context, base).save(PairedCredentials("desktop", requireNotNull(args.getString("coachToken"))))
        context.deleteDatabase("coach-client.db")
        context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base).putExtra("tab", "TODAY")
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
            // Synthetic fixture text only: name what the screen showed instead of the missing row.
            val shown = mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { shown += it }
                for (i in 0 until node.childCount) collect(node.getChild(i))
            }
            collect(ui.rootInActiveWindow)
            throw AssertionError("Missing synthetic review UI: $text; visible: ${shown.joinToString(" | ")}")
        }
        fun click(node: AccessibilityNodeInfo) {
            var target = node
            while (!target.isClickable) target = target.parent ?: throw AssertionError("No clickable ancestor")
            assertTrue(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(ui.takeScreenshot())
            java.io.File(context.cacheDir, "review-$phase-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        waitFor(if (after) "今天没有到期的复习" else "1 个知识点到期")
        screenshot("today")
        click(waitFor("到期复习"))
        waitFor(if (after) "今天没有到期的复习。" else "1 个知识点到期")
        screenshot("queue")
        if (!after) {
            click(waitFor("开始"))
            waitFor("到期复习")
            waitFor("Synthetic review question", contains = true)
            assertNull(find { it.text?.toString() == "开始讲解" })
            screenshot("practice")
        }
    }
}
