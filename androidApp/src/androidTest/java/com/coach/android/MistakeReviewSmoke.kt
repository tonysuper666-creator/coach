package com.coach.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.Anchors
import com.coach.client.LedgerPage
import com.coach.client.PairedCredentials
import com.coach.client.PoolTrace
import com.coach.client.RowText
import com.coach.client.ScrollDriver
import com.coach.client.ScrollMove
import com.coach.client.ScrollView
import com.coach.client.anchorsOf
import com.coach.client.cardNumberIn
import com.coach.client.drainLedger
import com.coach.client.movedBy
import com.coach.client.mistakeReview
import com.coach.domain.Event
import com.coach.domain.LedgerJson
import kotlinx.serialization.decodeFromString
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
    /** 每轮一个标识：失败图与层级必须能证明是本轮写的，不能和 09:53 那张旧图混起来。 */
    private val runId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    private var deadline = 0L
    private val selectedCards = mutableSetOf<Int>()

    /** 单轮自设预算：420 秒的进程超时会把进程直接杀掉，catch 跑不到，证据一张也写不出来。 */
    private fun beginRun() {
        deadline = SystemClock.elapsedRealtime() + RUN_BUDGET_MS
        listOf("review-fail.png", "review-fail.txt", "review-fail-run.txt").forEach { java.io.File(context.cacheDir, it).delete() }
        context.cacheDir.listFiles { f -> f.name.startsWith("review-snap-") }?.forEach { it.delete() }
    }

    private fun checkTime(where: String) {
        if (SystemClock.elapsedRealtime() > deadline)
            throw AssertionError("time budget ${RUN_BUDGET_MS / 1000}s exhausted at $where")
    }

    private fun step(name: String) {
        val message = "COACH_STEP ${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} $name"
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        trace(message)
    }

    @get:org.junit.Rule val failureEvidence = object : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
            object : org.junit.runners.model.Statement() {
                override fun evaluate() {
                    try {
                        base.evaluate()
                    } catch (t: Throwable) {
                        recordFailure(t)
                        throw t
                    }
                }
            }
    }

    @Test fun wrongAnswersRetryAndClear() {
        step("wrongAnswersRetryAndClear started")
        beginRun()
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790,8791,18791,3790))
        require(context.packageName.startsWith("com.coach.") && context.packageName != "com.coach.android") // never the production package
        base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        token = requireNotNull(args.getString("coachToken"))
        device = requireNotNull(args.getString("coachDevice"))
        course = BuildConfig.COACH_COURSE
        keys = JSONObject(java.io.File(context.cacheDir,"review-keys.json").readText())
        val kp = requireNotNull(args.getString("coachKp"))
        PoolTrace.enabled = true
        KeystoreCredentials(context,base).save(PairedCredentials(device,token))
        val phase = args.getString("coachPhase")
        val wrongCount = if (phase == "prepare3" || phase == "resume3") 3 else 2
        val resume = phase == "resume" || phase == "resume3"
        if (!resume) context.deleteDatabase("coach-client.db")
        try {
            step("launch activity")
            context.startActivity(Intent(context,CoachActivity::class.java).putExtra("coachUrl",base).putExtra("openKp",kp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            if (!resume) {
            samples.remove("baselineWrongCount")
            val baseline = reviewFromLedger()
            assertFalse("验收知识点已有历史错题，三连对会同时移除它们；请选择独立知识点", baseline.items.any { it.kpId == kp })
            samples.put("baselineWrongCount", baseline.items.size)
            samples.put("baselineAttemptIds", JSONArray(baseline.items.map { it.attemptId }.sorted()))
            returnToToday()
            click(waitText("到期复习"))
            waitText("错题 ${baseline.items.size}", 15_000)
            context.startActivity(Intent(context,CoachActivity::class.java).putExtra("coachUrl",base).putExtra("openKp",kp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            step("request initial questions")
            click(waitText("出题",15_000,enabled=true))
            waitQuestionPage(15_000)
            val first = group("kp:$kp")
            step("answer initial questions")
            answer(first, wrong = wrongCount)
            submitAnswers()
            scrollTo("本组 ${3 - wrongCount} / 3 题正确",substring=true)
            step("initial answers graded")
            returnToToday()
            } else returnToToday()
            val baselineCount = samples.getInt("baselineWrongCount")
            val first = group("kp:$kp")
            val reviewButton = waitText("到期复习")
            step("open mistake list")
            val reviewMark = SystemClock.elapsedRealtimeNanos()
            click(reviewButton)
            waitText("错题 ${baselineCount + wrongCount}")
            settleAndCapture("wrong-list",reviewMark)
            val newStems = (0 until wrongCount).map { first.getJSONObject(it).getJSONObject("payload").getString("stem") }
            newStems.forEach { scrollTo(it) }
            step("mistake list captured")
            samples.put("wrongCount",wrongCount)
            if (phase == "prepare" || phase == "prepare3") {
                samples.put("initialWrongListMs",samples.getDouble("wrong-listMs"))
                return
            }
            // Entries and their original answers remain in the replica, even without a new request.
            scrollTo("当时的答案：",substring=true)
            scrollTo("正确答案：",substring=true)
            scrollTo("判分依据：",substring=true)
            val retryButton = retryFor(newStems.first(), reviewFromLedger().items.map { it.card.stem })
            step("open retry questions")
            val retryMark = SystemClock.elapsedRealtimeNanos()
            click(retryButton)
            samples.put("retryClickReturnedMs", (SystemClock.elapsedRealtimeNanos()-retryMark)/1_000_000.0)
            waitQuestionPage()
            samples.put("retryHeadingObservedMs", (SystemClock.elapsedRealtimeNanos()-retryMark)/1_000_000.0)
            settleAndCapture("retry",retryMark)
            step("retry questions captured")
            val retry = group("mistakes:$kp")
            val oldIds = (0 until first.length()).map { first.getJSONObject(it).getJSONObject("payload").getString("questionId") }.toSet()
            assertEquals(3,retry.length())
            for (i in 0 until retry.length()) assertFalse(oldIds.contains(retry.getJSONObject(i).getJSONObject("payload").getString("questionId")))
            step("answer retry questions")
            answer(retry,wrong=0)
            samples.put("retryStagesMs", JSONObject(PoolTrace.snapshot("mistakes:$kp")))
            samples.put("retryTimelineMs", JSONArray(PoolTrace.timeline("mistakes:$kp").map { (stage,ms) -> JSONObject().put("stage",stage).put("ms",ms) }))
            PoolTrace.begin("mistakes:$kp")
            val clearMark=SystemClock.elapsedRealtimeNanos()
            submitAnswers()
            waitText("错题 $baselineCount")
            if (baselineCount == 0) waitText("错题都已掌握，继续保持。")
            settleAndCapture("cleared",clearMark)
            val expectedIds = samples.getJSONArray("baselineAttemptIds")
            assertEquals((0 until expectedIds.length()).map { expectedIds.getString(it) }, reviewFromLedger().items.map { it.attemptId }.sorted())
            step("cleared list captured")
            samples.put("newQuestions",3).put("clearedCount",wrongCount)
            assertTrue(samples.getDouble("initialWrongListMs")<=1000)
            assertTrue(samples.getDouble("wrong-listMs")<=1000)
            assertTrue(samples.getDouble("retryMs")<=1000)
        } finally {
            samples.put("lastStagesMs", JSONObject(PoolTrace.snapshot("mistakes:$kp")))
            samples.put("lastTimelineMs", JSONArray(PoolTrace.timeline("mistakes:$kp").map { (stage,ms) -> JSONObject().put("stage",stage).put("ms",ms) }))
            PoolTrace.enabled = false
            java.io.File(context.cacheDir,"review-metrics.json").writeText(samples.toString(2))
        }
    }

    @Test fun cachedMistakesSurviveRestartWithoutMac() {
        step("cachedMistakesSurviveRestartWithoutMac started")
        beginRun()
        require(context.packageName.startsWith("com.coach.") && context.packageName != "com.coach.android") // never the production package
        val port=requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790,8791,18791,3790))
        context.startActivity(Intent(context,CoachActivity::class.java)
            .putExtra("coachUrl","http://${BuildConfig.COACH_TAIL_HOST}:$port")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(waitText("到期复习",15_000))
        step("open offline mistake list")
        val offlineCount = samples.getInt("baselineWrongCount") + samples.getInt("wrongCount")
        waitText("错题 $offlineCount")
        waitText("离线可回看错题；连接 Mac 后可再练。")
        scrollTo("正确答案：",substring=true)
        settleAndCapture("offline",SystemClock.elapsedRealtimeNanos())
        step("offline mistake list captured")
        samples.put("offlineWrongCount",offlineCount)
        java.io.File(context.cacheDir,"review-metrics.json").writeText(samples.toString(2))
    }

    /** After the isolated server restarts, read the cleared state from the ledger again. */
    @Test fun clearedMistakesSurviveServerRestart() {
        step("clearedMistakesSurviveServerRestart started")
        beginRun()
        require(context.packageName == "com.coach.android.ton1146")
        val port = requireNotNull(args.getString("coachPort")).toInt()
        require(port !in setOf(8790, 8791, 18791, 3790))
        context.startActivity(Intent(context, CoachActivity::class.java)
            .putExtra("coachUrl", "http://${BuildConfig.COACH_TAIL_HOST}:$port")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(waitText("到期复习", 15_000))
        val baselineCount = samples.getInt("baselineWrongCount")
        waitText("错题 $baselineCount")
        if (baselineCount == 0) waitText("错题都已掌握，继续保持。")
        settleAndCapture("restart-cleared", SystemClock.elapsedRealtimeNanos())
        samples.put("restartClearedCount", baselineCount)
        java.io.File(context.cacheDir, "review-metrics.json").writeText(samples.toString(2))
    }

    /** 一页最多 500 条：副本已有 700+ 条时只读第一页就看不到刚出的题，所以按 throughSeq 读到末端。 */
    private fun events(): JSONArray {
        val all=JSONArray()
        drainLedger(0L) { after ->
            val req=Request.Builder().url("$base/v1/courses/$course/events?after=$after&limit=500")
                .header("Authorization","Bearer $token").header("X-Coach-Device",device).build()
            http.newCall(req).execute().use { response ->
                assertTrue("events after=$after -> HTTP ${response.code}",response.isSuccessful)
                val body=JSONObject(response.body!!.string())
                val page=body.getJSONArray("events")
                val cards=(0 until page.length()).map { page.getJSONObject(it) }
                cards.forEach { all.put(it) }
                LedgerPage(cards,body.getLong("throughSeq"),body.optBoolean("hasMore",false))
            }
        }
        return all
    }
    private fun reviewFromLedger(): com.coach.client.MistakeReview {
        val all = events()
        return mistakeReview((0 until all.length()).map { LedgerJson.decodeFromString<Event>(all.getJSONObject(it).toString()) })
    }
    /** The first retry after this stem must precede every other mistake stem. */
    private fun retryFor(stem: String, stems: List<String>): AccessibilityNodeInfo {
        assertEquals("错题题干不唯一，无法安全关联重练入口", 1, stems.count { norm(it) == norm(stem) })
        scrollTo(stem)
        repeat(3) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null || !node.refresh()) return
                if (!node.text.isNullOrBlank() && node.isVisibleToUser) nodes.add(node)
                for (i in 0 until node.childCount) collect(node.getChild(i))
            }
            collect(freshRoot())
            val start = nodes.indexOfFirst { norm(it.text.toString()).contains(norm(stem).take(40)) }
            if (start < 0) throw AssertionError("新错题离开视口，无法安全关联重练入口")
            for (node in nodes.drop(start + 1)) {
                val shown = norm(node.text.toString())
                if (shown == "再练一组") return node
                if (stems.any { norm(it) != norm(stem) && shown.contains(norm(it).take(40)) })
                    throw AssertionError("新错题后先出现另一题，无法安全关联重练入口")
            }
            val list = page() ?: throw AssertionError("找不到错题列表")
            val r = android.graphics.Rect().also(list::getBoundsInScreen)
            drag(r.centerX(), r.top + r.height() * 7 / 10, r.top + r.height() * 5 / 10)
            instrumentation.waitForIdleSync()
        }
        throw AssertionError("无法唯一定位新错题对应的再练入口")
    }
    private fun group(thread: String): JSONArray {
        val all=events();val cards=JSONArray()
        for(i in 0 until all.length()) all.getJSONObject(i).let {
            if(it.optString("type")=="question_posed" && it.optString("threadId")==thread) cards.put(it)
        }
        assertEquals("question_posed for $thread among ${all.length()} ledger events",3,cards.length())
        return cards
    }
    private var answerRound=0
    private fun answer(cards: JSONArray, wrong: Int) {
        answerRound++
        selectedCards.clear()
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
        java.io.File(context.cacheDir,"review-snap-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        java.io.File(context.cacheDir,"review-snap-$name.txt").writeText("elapsed=${SystemClock.elapsedRealtime()}\n"+layout()) } }
    /** Verify this option's radio state even when its centred checkmark is clipped by the viewport. */
    private fun chosen(optionText: String): Boolean {
        var row=find(optionText)
        while(row!=null) {
            if(row.isCheckable) return row.isChecked
            row=row.parent
        }
        // Older accessibility implementations expose the marker as text instead of a checkable row.
        val nodes=visibleTexts();val want=norm(optionText).take(40)
        val at=nodes.indexOfFirst { norm(it.drop(2)).contains(want) }
        return at>=0 && (nodes[at].contains("✓") || (at>0 && nodes[at-1].contains("✓")))
    }
    private fun marks()=visibleTexts().count { it.contains("✓") }
    /** A touch only counts once the selected option is visible in the fresh tree. */
    private fun pick(optionText: String,card: Int) {
        step("select answer card=$card")
        repeat(3) {
            trace("pick attempt $it card=$card text=${optionText.take(50)}")
            try {
                val node=scrollTo(optionText,card=card)
                snap("r${answerRound}c${card}a$it-before");tap(node)
                val until=SystemClock.elapsedRealtime()+2_000
                while(SystemClock.elapsedRealtime()<until && !chosen(optionText)) Thread.sleep(100)
                snap("r${answerRound}c${card}a$it-after")
                assertTrue("第 $card 题点按后未选中",chosen(optionText))
                selectedCards.add(card)
                return
            }
            catch(e: AssertionError) { if(it==2) throw e;Thread.sleep(400) }
        }
    }
    /** 失败一定落下本轮的新图与层级：文件名带 [runId]，`review-fail.png` 只在本轮截图成功后覆盖。 */
    private fun recordFailure(t: Throwable) {
        val shot=runCatching { settleAndCapture("fail-$runId",SystemClock.elapsedRealtimeNanos()) }
        runCatching {
            val dump=java.io.File(context.cacheDir,"review-fail-$runId.txt")
            dump.writeText("run=$runId elapsed=${SystemClock.elapsedRealtime()} port=${runCatching { base }.getOrNull()}\n"+
                "error=${t.javaClass.simpleName}: ${t.message}\n\n"+
                visibleTexts().joinToString("\n")+"\n\nLAYOUT\n"+layout()+"\n")
            dump.copyTo(java.io.File(context.cacheDir,"review-fail.txt"),overwrite=true)
            java.io.File(context.cacheDir,"review-fail-run.txt").writeText("$runId ${t.message}\n")
            // settleAndCapture 写的是 review-fail-<runId>.png（name = "fail-$runId"），这里必须搬同一份。
            if(shot.isSuccess) java.io.File(context.cacheDir,"review-fail-$runId.png")
                .copyTo(java.io.File(context.cacheDir,"review-fail.png"),overwrite=true)
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
    private fun freshRoot(): AccessibilityNodeInfo? {
        if(android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache()
        return ui.rootInActiveWindow
    }
    private fun find(text: String,substring: Boolean=false,node: AccessibilityNodeInfo?=freshRoot(),anyVisibility: Boolean=false): AccessibilityNodeInfo? {
        if(node==null) return null
        if(!node.refresh()) return null
        val shown=norm(node.text?.toString().orEmpty());val want=norm(text)
        // Option rows may merge the letter with a long Markdown body: match the body's opening too.
        val hit=if(substring) shown.contains(want) else shown==want || (want.length>40 && shown.contains(want.take(40)))
        if((anyVisibility || node.isVisibleToUser) && hit) return node
        for(i in 0 until node.childCount) find(text,substring,node.getChild(i),anyVisibility)?.let { return it }
        return null
    }
    private fun norm(s: String)=s.replace('\u2019','\'').replace(Regex("\\s+")," ").trim()
    private fun visibleTexts(node: AccessibilityNodeInfo?=freshRoot(),out: MutableList<String> = mutableListOf()): List<String> {
        if(node==null) return out
        if(!node.refresh()) return out
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
    /** Submit can be below the viewport or visible at its edge. */
    private fun submitAnswers() {
        click(scrollTo("提交本组答案"))
    }
    /** On wide layouts Today remains alongside the study pane and needs no back button. */
    private fun returnToToday() {
        val end=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<end) {
            find("到期复习")?.let { return }
            find("← 今天")?.let { click(it);return }
            Thread.sleep(100)
        }
        throw AssertionError("无法返回今天：未找到今天面板或返回按钮")
    }
    /** The list can preserve its scroll position: any visible card establishes that the page loaded. */
    private fun waitQuestionPage(timeout: Long=10_000) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {
            if((1..3).any { find("第 $it / 3 题") != null }) return
            Thread.sleep(10)
        }
        throw AssertionError("No visible question heading on the three-question page")
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
    private fun layout(node: AccessibilityNodeInfo?=freshRoot(),out: StringBuilder=StringBuilder()): String {
        if(node==null) return out.toString()
        if(!node.refresh()) return out.toString()
        if(!node.text.isNullOrBlank()) { val r=android.graphics.Rect();node.getBoundsInScreen(r);out.append(node.text).append('@').append(r.flattenToString()).append(';') }
        for(i in 0 until node.childCount) layout(node.getChild(i),out)
        return out.toString()
    }
    private fun scrollables(n: AccessibilityNodeInfo?=freshRoot(),out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if(n==null) return out
        if(!n.refresh()) return out
        if(n.isScrollable) out.add(n)
        for(i in 0 until n.childCount) scrollables(n.getChild(i),out)
        return out
    }
    private fun area(n: AccessibilityNodeInfo): Int { val r=android.graphics.Rect();n.getBoundsInScreen(r);return r.width()*r.height() }
    private fun page(): AccessibilityNodeInfo? = scrollables().maxByOrNull(::area)

    /** 一次观察：题号、目标位置、页底栏，外加几个稳定锚点的屏幕坐标（判断“真的前进了”）。 */
    private class Look(val view: ScrollView,val anchors: Anchors,val pageNode: AccessibilityNodeInfo?)
    /**
     * 只走一遍树。13:36 的旧实现每轮读两次整树（实测约 2.7 秒/次），三次重试就吃掉 420 秒，
     * 而判据还是“任意文字位置变了”——吸附回弹的十几像素也算前进，于是题号停在 2 也不认输。
     * 锚点交给 [anchorsOf]：只用落在题页矩形内的行，题号行优先，出视口的题号与固定页眉都不参与。
     */
    private fun look(text: String,substring: Boolean): Look {
        val list=page();val pageRect=list?.let { n -> android.graphics.Rect().also(n::getBoundsInScreen) }
        val rows=ArrayList<RowText>()
        var target: AccessibilityNodeInfo?=null;var atFooter=false
        val want=norm(text)
        fun walk(n: AccessibilityNodeInfo?) {
            if(n==null) return
            if(!n.refresh()) return
            val shown=norm(n.text?.toString().orEmpty())
            if(shown.isNotEmpty() && n.isVisibleToUser) {
                val r=android.graphics.Rect();n.getBoundsInScreen(r)
                rows.add(RowText(shown,r.top,r.centerY()))
                if(shown.contains("选完") || shown.contains("提交本组答案")) atFooter=true
                val hit=if(substring) shown.contains(want) else shown==want || (want.length>40 && shown.contains(want.take(40)))
                if(hit && target==null) target=n
            }
            for(i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(freshRoot())
        val focus=target?.let { n -> android.graphics.Rect().also(n::getBoundsInScreen) }
        val pageTop=pageRect?.top ?: 0;val pageBottom=pageRect?.bottom ?: 0
        return Look(ScrollView(targetFound=focus!=null,targetCenterY=focus?.centerY() ?: 0,
            pageTop=pageTop,pageBottom=pageBottom,visibleCards=rows.mapNotNull { cardNumberIn(it.text) },atFooter=atFooter,
            pageScrollable=list!=null),anchorsOf(rows,pageTop,pageBottom),list)
    }
    /** 容器自己的翻页：不依赖手指拖多远，也不再拿“布局动没动”当判据。 */
    private fun scrollPage(list: AccessibilityNodeInfo,forward: Boolean) {
        list.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
    /** 看图说话：目标在不在页内、哪些题号在屏幕上；[card] 是目标所属的题号，[text] 只用来在失败时写清要找什么。 */
    private fun scrollTo(text: String,substring: Boolean=false,card: Int?=null): AccessibilityNodeInfo {
        // 先请已组合的行自己滚进来：短拖会被吸附回原位，这一步最省。
        repeat(3) {
            if(text=="提交本组答案") find(text)?.let { return it }
            val node=find(text,substring,anyVisibility=true) ?: return@repeat
            val pageRect=page()?.let { n -> android.graphics.Rect().also(n::getBoundsInScreen) }
            val r=android.graphics.Rect();node.getBoundsInScreen(r)
            trace("reveal #$it ${text.take(30)} at ${r.centerY()} page=${pageRect?.top}-${pageRect?.bottom}")
            if(pageRect!=null && r.centerY()>=pageRect.top+60 && r.centerY()<=pageRect.bottom-60) return node
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync();Thread.sleep(400)
        }
        val driver=ScrollDriver(card, towardEnd=text=="提交本组答案" || text.startsWith("本组 "))
        var moved: Boolean?=null
        while(true) {
            checkTime("scrollTo ${text.take(30)}")
            if(text=="提交本组答案") find(text)?.let { return it }
            val before=look(text,substring)
            if(text=="提交本组答案" && before.view.atFooter && find("选完",substring=true)!=null) {
                val missing=(1..3).firstOrNull { it !in selectedCards }
                throw AssertionError("第 ${missing ?: "未知"} 题未选中，提交按钮仍不可用")
            }
            val move=driver.next(before.view,moved)
            when(move) {
                ScrollMove.Tap -> return find(text,substring)
                    ?: throw AssertionError("目标被判定可点却找不到：${text.take(40)}；可见题号 ${before.view.visibleCards}")
                ScrollMove.NudgeDown, ScrollMove.NudgeUp -> {
                    trace("nudge ${if(move==ScrollMove.NudgeDown) "down" else "up"} ${text.take(30)} at ${before.view.targetCenterY}")
                    find(text,substring,anyVisibility=true)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                }
                is ScrollMove.ScrollPage -> { trace("page forward=${move.forward} cards=${before.view.visibleCards}");before.pageNode?.let { scrollPage(it,move.forward) } }
                is ScrollMove.Drag -> { trace("drag forward=${move.forward} cards=${before.view.visibleCards}");before.pageNode?.let { dragIn(it,move.forward) } }
                is ScrollMove.Unreachable ->
                    throw AssertionError("Cannot scroll to: ${text.take(60)}; ${move.reason}; seen: "+
                        visibleTexts().take(24).joinToString(" | "))
            }
            instrumentation.waitForIdleSync();Thread.sleep(250)
            val after=look(text,substring).anchors
            moved=movedBy(before.anchors,after)
            trace("movement move=$move moved=$moved before=${before.anchors} after=$after")
        }
    }
    /** 手指拖 40% 页高，只在容器不认翻页动作时用。 */
    private fun dragIn(list: AccessibilityNodeInfo,forward: Boolean) {
        val rect=android.graphics.Rect();list.getBoundsInScreen(rect)
        val high=rect.top+rect.height()*7/10
        val low=rect.top+rect.height()*3/10
        drag(rect.centerX(),if(forward) high else low,if(forward) low else high)
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

    private companion object {
        /** 外层 tools/dev/check-mistake-review.py 在 420 秒直接杀进程：留 120 秒把本轮新图与新层级写完再失败。 */
        const val RUN_BUDGET_MS=300_000L
    }
}
