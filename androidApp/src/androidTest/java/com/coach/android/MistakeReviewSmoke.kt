package com.coach.android

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** OPPO replay against an isolated production copy. Keys are an explicit test-only input,
 * read from this test package's cache, never requested from the running application. */
@RunWith(AndroidJUnit4::class)
class MistakeReviewSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val ui get() = instrumentation.uiAutomation
    private val http = OkHttpClient()
    private val samples by lazy { java.io.File(context.cacheDir,"review-metrics.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject() }
    private lateinit var base: String
    private lateinit var token: String
    private lateinit var device: String
    private lateinit var course: String
    private lateinit var keys: JSONObject

    @Test fun wrongAnswersRetryAndClear() {
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790,8791,18791,3790))
        require(context.packageName == "com.coach.reviewtest")
        base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        token = requireNotNull(args.getString("coachToken"))
        device = requireNotNull(args.getString("coachDevice"))
        course = BuildConfig.COACH_COURSE
        keys = JSONObject(java.io.File(context.cacheDir,"review-keys.json").readText())
        val kp = requireNotNull(args.getString("coachKp"))
        KeystoreCredentials(context,base).save(PairedCredentials(device,token))
        val resume = args.getString("coachPhase") == "resume"
        if (!resume) context.deleteDatabase("coach-client.db")
        try {
            context.startActivity(Intent(context,CoachActivity::class.java).putExtra("coachUrl",base).putExtra("openKp",kp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            if (!resume) {
            click(waitText("出题",15_000,enabled=true))
            waitText("第 1 / 3 题",15_000)
            val first = group("kp:$kp")
            answer(first, wrong = 2)
            scrollTo("提交本组答案").let(::click)
            scrollTo("本组 1 / 3 题正确",substring=true)
            click(waitText("← 今天"))
            } else click(waitText("← 今天",15_000))
            val first = group("kp:$kp")
            val reviewButton = waitText("到期复习")
            val reviewMark = SystemClock.elapsedRealtimeNanos()
            click(reviewButton)
            waitText("错题 2")
            waitText(first.getJSONObject(0).getJSONObject("payload").getString("stem"))
            settleAndCapture("wrong-list",reviewMark)
            samples.put("wrongCount",2)
            if (args.getString("coachPhase") == "prepare") {
                samples.put("initialWrongListMs",samples.getDouble("wrong-listMs"))
                return
            }
            // Both entries and their original answers remain in the replica, even without a new request.
            scrollTo("当时的答案：",substring=true)
            scrollTo("正确答案：",substring=true)
            scrollTo("判分依据：",substring=true)
            val retryButton = scrollTo("再练一组")
            val retryMark = SystemClock.elapsedRealtimeNanos()
            click(retryButton)
            waitText("第 1 / 3 题")
            settleAndCapture("retry",retryMark)
            val retry = group("mistakes:$kp")
            val oldIds = (0 until first.length()).map { first.getJSONObject(it).getJSONObject("payload").getString("questionId") }.toSet()
            assertEquals(3,retry.length())
            for (i in 0 until retry.length()) assertFalse(oldIds.contains(retry.getJSONObject(i).getJSONObject("payload").getString("questionId")))
            answer(retry,wrong=0)
            val clearMark=SystemClock.elapsedRealtimeNanos()
            click(scrollTo("提交本组答案"))
            waitText("错题 0")
            waitText("错题都已掌握，继续保持。")
            settleAndCapture("cleared",clearMark)
            samples.put("newQuestions",3).put("clearedCount",2)
            assertTrue(samples.getDouble("initialWrongListMs")<=1000)
            assertTrue(samples.getDouble("wrong-listMs")<=1000)
            assertTrue(samples.getDouble("retryMs")<=1000)
        } finally {
            java.io.File(context.cacheDir,"review-metrics.json").writeText(samples.toString(2))
        }
    }

    @Test fun cachedMistakesSurviveRestartWithoutMac() {
        require(context.packageName == "com.coach.reviewtest")
        val port=requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790,8791,18791,3790))
        context.startActivity(Intent(context,CoachActivity::class.java)
            .putExtra("coachUrl","http://${BuildConfig.COACH_TAIL_HOST}:$port")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(waitText("到期复习",15_000))
        waitText("错题 2")
        waitText("离线可回看错题；连接 Mac 后可再练。")
        scrollTo("正确答案：",substring=true)
        settleAndCapture("offline",SystemClock.elapsedRealtimeNanos())
        samples.put("offlineWrongCount",2)
        java.io.File(context.cacheDir,"review-metrics.json").writeText(samples.toString(2))
    }

    private fun events(): JSONArray {
        val req=Request.Builder().url("$base/v1/courses/$course/events?after=0")
            .header("Authorization","Bearer $token").header("X-Coach-Device",device).build()
        return http.newCall(req).execute().use { response ->
            assertTrue(response.isSuccessful)
            JSONObject(response.body!!.string()).getJSONArray("events")
        }
    }
    private fun group(thread: String): JSONArray {
        val all=events();val cards=JSONArray()
        for(i in 0 until all.length()) all.getJSONObject(i).let {
            if(it.optString("type")=="question_posed" && it.optString("threadId")==thread) cards.put(it)
        }
        assertEquals(3,cards.length())
        return cards
    }
    private fun answer(cards: JSONArray, wrong: Int) {
        for(i in 0 until cards.length()) {
            val p=cards.getJSONObject(i).getJSONObject("payload")
            val correct=keys.getString(p.getString("questionId"))
            val opts=p.getJSONArray("options")
            val opt=(0 until opts.length()).map { opts.getJSONObject(it) }.first {
                if(i<wrong) it.getString("optionId")!=correct else it.getString("optionId")==correct
            }
            click(scrollTo(opt.getString("text")))
        }
    }
    private fun settleAndCapture(name: String,mark: Long) {
        instrumentation.waitForIdleSync();Thread.sleep(100)
        val start=SystemClock.elapsedRealtimeNanos()
        val bitmap=ui.takeScreenshot()
        val end=SystemClock.elapsedRealtimeNanos()
        java.io.File(context.cacheDir,"review-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        samples.put(name+"Ms",(end-mark)/1_000_000.0).put(name+"FrameStartedMs",(start-mark)/1_000_000.0)
    }
    private fun find(text: String,substring: Boolean=false,node: AccessibilityNodeInfo?=ui.rootInActiveWindow): AccessibilityNodeInfo? {
        if(node==null) return null
        val shown=node.text?.toString().orEmpty()
        if(node.isVisibleToUser && (if(substring) shown.contains(text) else shown==text)) return node
        for(i in 0 until node.childCount) find(text,substring,node.getChild(i))?.let { return it }
        return null
    }
    private fun waitText(text: String,timeout: Long=10_000,enabled: Boolean=false): AccessibilityNodeInfo {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {
            find(text)?.let { node ->
                var button: AccessibilityNodeInfo?=node
                while(button!=null && !button.isClickable) button=button.parent
                if(!enabled || button?.isEnabled==true) return node
            }
            Thread.sleep(10)
        }
        throw AssertionError("Visible text missing: $text")
    }
    private fun scroll(forward: Boolean): Boolean {
        fun target(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(n==null) return null
            if(n.isScrollable) return n
            for(i in 0 until n.childCount) target(n.getChild(i))?.let { return it }
            return null
        }
        return target(ui.rootInActiveWindow)?.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)==true
    }
    private fun scrollTop() { repeat(20) { if(!scroll(false)) return;Thread.sleep(100) } }
    private fun scrollTo(text: String,substring: Boolean=false): AccessibilityNodeInfo {
        repeat(30) { find(text,substring)?.let { return it };scroll(true);Thread.sleep(150) }
        throw AssertionError("Cannot scroll to: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target=node
        while(!target.isClickable) target=target.parent ?: throw AssertionError("No clickable ancestor")
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
