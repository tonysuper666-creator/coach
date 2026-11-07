package com.coach.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic course → mock library → whole paper. Restricted to the isolated TON-1362 build. */
@RunWith(AndroidJUnit4::class)
class MockCourseAliasSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation

    @Test fun courseCodePaperCanBeOpenedFromTheLedgerCourse() {
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        require(context.packageName == "com.coach.android.ton1362")
        require(BuildConfig.COACH_COURSE == "ton1362-synthetic-course")
        require(args.getString("coachPort") == "19362")
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:19362"
        KeystoreCredentials(context, base).save(PairedCredentials(
            requireNotNull(args.getString("coachDevice")), requireNotNull(args.getString("coachToken"))))
        context.deleteDatabase("coach-client.db")
        context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base)
            .putExtra("tab", "REVIEW").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val entry = await { it.text?.toString() == "模拟考与单选速刷" }
        shot("course")
        tap(entry)
        await { it.text?.toString() == "TON-1362 Synthetic whole paper" }
        shot("library")
        tap(await { it.text?.toString() == "开考" })
        await { it.text?.toString() == "开始「TON-1362 Synthetic whole paper」？" }
        tap(await { it.text?.toString() == "开考" })
        await { it.text?.toString()?.contains("A synthetic orchard sells apples.") == true }
        await { it.text?.toString() == "交卷" }
        shot("paper")
    }

    private fun await(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end) {
            ui.clearCache()
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && !Rect().also(node::getBoundsInScreen).isEmpty && match(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            walk(ui.rootInActiveWindow)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Synthetic mock course journey: expected UI missing")
    }

    private fun tap(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: throw AssertionError("No clickable ancestor")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(ui.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.cacheDir, "ton1362-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
