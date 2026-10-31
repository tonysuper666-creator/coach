package com.coach.android

import android.content.Intent
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

/** Explicit OPPO + isolated server replay. Does not generate answers or contact the production port. */
@RunWith(AndroidJUnit4::class)
class PoolPreparationSmoke {
    @Test fun tapToVisibleCardsAcrossKnowledgePoints() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        val port=requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790,8791,18791,3790))
        val base="http://${BuildConfig.COACH_TAIL_HOST}:$port"
        val ids=requireNotNull(args.getString("coachKps")).split(',')
        require(ids.size>=20 && ids.distinct().size>=10)
        KeystoreCredentials(context,base).save(PairedCredentials(requireNotNull(args.getString("coachDevice")),requireNotNull(args.getString("coachToken"))))
        context.deleteDatabase("coach-client.db")
        val samples=JSONArray()
        val report=java.io.File(context.cacheDir,"pool-latency.json")
        try {
            for(kp in ids) {
                context.startActivity(Intent(context,CoachActivity::class.java).putExtra("coachUrl",base).putExtra("openKp",kp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                val button=waitForText(instrumentation.uiAutomation,"出题",15_000,enabledButton=true)
                instrumentation.waitForIdleSync()
                val mark=SystemClock.elapsedRealtimeNanos()
                click(button)
                // Long options place the submit footer below the viewport. Measure the first
                // displayed card; its 1/3 header also confirms a complete three-card group.
                waitForText(instrumentation.uiAutomation,"第 1 / 3 题",15_000)
                instrumentation.waitForIdleSync()
                // Include compositor settling; accessibility appearance alone is not a displayed frame.
                Thread.sleep(100)
                val millis=(SystemClock.elapsedRealtimeNanos()-mark)/1_000_000.0
                samples.put(JSONObject().put("kp",kp).put("tapToRenderedCardsMs",millis).put("cards",3))
                report.writeText(samples.toString(2))
                android.util.Log.i("CoachPool","kp=$kp tapToRenderedCardsMs=$millis cards=3")
                if(samples.length()==1) {
                    val screenshot=instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(context.cacheDir,"pool-ready.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    screenshot.recycle()
                }
            }
        } finally { report.writeText(samples.toString(2)) }
        assertEquals(ids.size,samples.length())
        for(i in 0 until samples.length()) assertTrue("Sample $i exceeded one second: ${samples.getJSONObject(i)}",samples.getJSONObject(i).getDouble("tapToRenderedCardsMs")<=1000.0)
    }

    private fun waitForText(ui: android.app.UiAutomation,text: String,timeout: Long,enabledButton: Boolean=false): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.text?.toString()==text) return node
            for(i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {
            find(ui.rootInActiveWindow)?.let { node ->
                if(!enabledButton) return node
                var button: AccessibilityNodeInfo?=node
                while(button!=null && !button.isClickable) button=button.parent
                if(button?.isEnabled==true) return node
            }
            Thread.sleep(10)
        }
        throw AssertionError("Visible text did not appear: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target=node
        while(!target.isClickable) target=target.parent ?: throw AssertionError("Text has no clickable ancestor")
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
