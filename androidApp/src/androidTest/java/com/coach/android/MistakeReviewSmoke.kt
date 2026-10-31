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
        require(context.packageName.startsWith("com.coach.") && context.packageName != "com.coach.android") // never the production package
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
        } catch(t: Throwable) {
            runCatching { settleAndCapture("fail",SystemClock.elapsedRealtimeNanos());java.io.File(context.cacheDir,"review-fail.txt").writeText(visibleTexts().joinToString("\n")) }
            throw t
        } finally {
            java.io.File(context.cacheDir,"review-metrics.json").writeText(samples.toString(2))
        }
    }

    @Test fun cachedMistakesSurviveRestartWithoutMac() {
        require(context.packageName.startsWith("com.coach.") && context.packageName != "com.coach.android") // never the production package
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
            pick(opt.getString("text"),i+1)
        }
    }
    private fun trace(line: String)=java.io.File(context.cacheDir,"review-trace.txt").appendText("${SystemClock.elapsedRealtime()} $line\n")
    private fun snap(name: String) { runCatching {
        val bitmap=ui.takeScreenshot()
        java.io.File(context.cacheDir,"review-snap-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,60,it) }
        bitmap.recycle() } }
    private fun marks()=visibleTexts().count { it.contains("✓") }
    /** Cards are still arriving right after the first one shows, so a click can land on a stale node: retry until the ✓ shows. */
    private fun pick(optionText: String,card: Int) {
        repeat(5) {
            val before=marks();trace("pick attempt $it marks=$before text=${optionText.take(50)}")
            try { val node=scrollTo(optionText,card=card);snap("c${card}a${it}-before");tap(node);snap("c${card}a${it}-after") } catch(e: AssertionError) { if(it==4) throw e;Thread.sleep(400);return@repeat }
            val end=SystemClock.elapsedRealtime()+2_000
            while(SystemClock.elapsedRealtime()<end) { if(marks()>before) return;Thread.sleep(50) }
        }
        throw AssertionError("Option click never took effect: $optionText; "+visibleTexts().joinToString(" | "))
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
    private fun find(text: String,substring: Boolean=false,node: AccessibilityNodeInfo?=ui.rootInActiveWindow,anyVisibility: Boolean=false): AccessibilityNodeInfo? {
        if(node==null) return null
        val shown=norm(node.text?.toString().orEmpty());val want=norm(text)
        // Option rows may merge the letter with a long Markdown body: match the body's opening too.
        val hit=if(substring) shown.contains(want) else shown==want || (want.length>40 && shown.contains(want.take(40)))
        if((anyVisibility || node.isVisibleToUser) && hit) return node
        for(i in 0 until node.childCount) find(text,substring,node.getChild(i),anyVisibility)?.let { return it }
        return null
    }
    private fun norm(s: String)=s.replace('\u2019','\'').replace(Regex("\\s+")," ").trim()
    private fun visibleTexts(node: AccessibilityNodeInfo?=ui.rootInActiveWindow,out: MutableList<String> = mutableListOf()): List<String> {
        if(node==null) return out
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add((if(node.isVisibleToUser) "V " else "H ")+it.take(400)) }
        for(i in 0 until node.childCount) visibleTexts(node.getChild(i),out)
        return out
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
    /** Injects a steady 30-step finger drag; unlike `input swipe` its pace never depends on shell start-up. */
    private fun drag(x: Int,fromY: Int,toY: Int) {
        val t0=SystemClock.uptimeMillis()
        fun send(action: Int,at: Long,y: Float) {
            val e=android.view.MotionEvent.obtain(t0,at,action,x.toFloat(),y,0)
            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            ui.injectInputEvent(e,true);e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN,t0,fromY.toFloat())
        val steps=30
        for(i in 1..steps) { Thread.sleep(16);send(android.view.MotionEvent.ACTION_MOVE,SystemClock.uptimeMillis(),fromY+(toY-fromY)*i/steps.toFloat()) }
        send(android.view.MotionEvent.ACTION_UP,SystemClock.uptimeMillis(),toY.toFloat())
    }
    private fun layout(node: AccessibilityNodeInfo?=ui.rootInActiveWindow,out: StringBuilder=StringBuilder()): String {
        if(node==null) return out.toString()
        if(!node.text.isNullOrBlank()) { val r=android.graphics.Rect();node.getBoundsInScreen(r);out.append(node.text).append('@').append(r.top).append(';') }
        for(i in 0 until node.childCount) layout(node.getChild(i),out)
        return out.toString()
    }
    private fun scrollables(n: AccessibilityNodeInfo?=ui.rootInActiveWindow,out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if(n==null) return out
        if(n.isScrollable) out.add(n)
        for(i in 0 until n.childCount) scrollables(n.getChild(i),out)
        return out
    }
    private fun area(n: AccessibilityNodeInfo): Int { val r=android.graphics.Rect();n.getBoundsInScreen(r);return r.width()*r.height() }
    private var scrollNote=""
    /** One bounded drag in the page's largest scroll container; false when the content did not move. */
    private fun scroll(forward: Boolean): Boolean {
        val list=scrollables().maxByOrNull(::area) ?: run { scrollNote="no scrollable node";return false }
        val rect=android.graphics.Rect();list.getBoundsInScreen(rect)
        scrollNote="scrollable $rect of ${scrollables().size}"
        val x=rect.centerX()
        val high=rect.top+rect.height()*7/10
        val low=rect.top+rect.height()*3/10
        val from=if(forward) high else low
        val to=if(forward) low else high
        // Every card is composed even when off screen, so only positions show whether the page really moved.
        val before=layout()
        drag(x,from,to)
        instrumentation.waitForIdleSync();Thread.sleep(400)
        return layout()!=before
    }
    private val cardNumber=Regex("第 (\\d+) / \\d+ 题")
    /** Card numbers whose header is on screen; the page is a column of cards, so these say which way to drag. */
    private fun shownCards()=visibleTexts().mapNotNull { cardNumber.find(it)?.groupValues?.get(1)?.toInt() }
    /** Looks for [text] by dragging toward it. [card] (1-based) names the card the text belongs to when known;
     * otherwise the drag reverses whenever the content stops moving. A fling can carry past a long option, so
     * direction is re-decided after every drag from what is on screen, never assumed. */
    private fun scrollTo(text: String,substring: Boolean=false,card: Int?=null): AccessibilityNodeInfo {
        // Every card is composed, so the row can be asked to bring itself on screen; the page's own anchoring
        // pulls back short drags, which made drag-and-search unreliable on long cards.
        repeat(4) {
            val node=find(text,substring,anyVisibility=true) ?: return@repeat
            val page=scrollables().maxByOrNull(::area)?.let { n -> android.graphics.Rect().also(n::getBoundsInScreen) }
            val r=android.graphics.Rect();node.getBoundsInScreen(r)
            trace("reveal #$it ${text.take(30)} at ${r.centerY()} page=${page?.top}-${page?.bottom}")
            if(page!=null && r.centerY()>=page.top+60 && r.centerY()<=page.bottom-120) return node
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync();Thread.sleep(700)
        }
        var forward=true;var stuck=0;val seen=linkedSetOf<String>()
        repeat(40) {
            val hit=find(text,substring)
            // A row half under the composer or the header would take the tap meant for it: only a row well inside the page counts.
            val page=scrollables().maxByOrNull(::area)?.let { n -> android.graphics.Rect().also(n::getBoundsInScreen) }
            if(hit!=null) {
                val r=android.graphics.Rect();hit.getBoundsInScreen(r)
                if(page==null || (r.centerY()>=page.top+60 && r.centerY()<=page.bottom-120) || (stuck>=2 && r.centerY()>=page.top+20 && r.centerY()<=page.bottom-30)) { trace("found after $it drags: ${text.take(40)}");return hit }
                trace("found at ${r.centerY()} outside page ${page.top}-${page.bottom}; nudging")
                val down=r.centerY()>page.bottom-120
                // At the very end of the page the row cannot rise further; after two fruitless nudges tap it where it is.
                if(!scroll(down)) stuck++ else stuck=0
                return@repeat
            }
            val now=visibleTexts();seen.addAll(now)
            val cards=shownCards()
            val atFooter=now.any { it.contains("选完") || it.contains("提交本组答案") }
            if(card!=null && cards.isNotEmpty()) forward=cards.max()<=card && !(cards.max()==card && atFooter)
            else if(card!=null && atFooter) forward=false
            trace("drag #$it forward=$forward cards=$cards footer=$atFooter want=$card")
            if(!scroll(forward)) { forward=!forward;scroll(forward) }
        }
        throw AssertionError("Cannot scroll to: $text; "+scrollNote+"; every node seen while scrolling: "+seen.joinToString(" | "))
    }
    /** A real touch at the option's centre: the same path a finger takes, so it also proves the row is on screen. */
    private fun tap(node: AccessibilityNodeInfo) {
        val r=android.graphics.Rect();node.getBoundsInScreen(r)
        trace("tap ${r.centerX()},${r.centerY()}")
        ui.executeShellCommand("input tap ${r.centerX()} ${r.centerY()}").use { d -> java.io.FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target=node
        while(!target.isClickable) target=target.parent ?: throw AssertionError("No clickable ancestor")
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
