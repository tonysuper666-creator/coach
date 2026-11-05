package com.coach.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import com.coach.client.PairedCredentials
import androidx.activity.enableEdgeToEdge
import androidx.metrics.performance.JankStats
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.HttpLedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.client.db.ClientDatabase
import com.coach.ui.CoachApp
import com.coach.ui.CoachController
import com.coach.ui.CoachNotice
import com.coach.ui.CoachTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.coach.client.ChatItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CoachActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var usageReporter: com.coach.client.UsageReporter? = null
    private var transport: HttpLedgerTransport? = null
    private var tutor: TutorClient? = null
    private var jank: JankStats? = null
    private var math: com.coach.client.MathHttp? = null
    private val frameMs = ArrayList<Float>()
    private var jankFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // `--es coachUrl http://<tailHost>:<port>` 仅用于在不同端口起隔离服务端做验收；默认走配置里的地址。
        val requestedUrl = intent.getStringExtra("coachUrl")
        val serverUrl = coachServerUrl(requestedUrl, packageName)
        Log.i("CoachUrl", "package=$packageName extra=${requestedUrl ?: "<empty>"} resolved=$serverUrl")
        val store=KeystoreCredentials(this,serverUrl)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CoachTheme(dark) {
                PairingGate(store,serverUrl) { credentials ->
                    if(BuildConfig.COACH_COURSE.isBlank()) CoachNotice("课程还没有装好，请稍后重开。")
                    else {
                        val controller=remember(credentials.deviceId) { createController(serverUrl,credentials) }
                        CoachApp(controller, initialKp = intent.getStringExtra("openKp"), initialAutoStart = intent.getBooleanExtra("autoStart", false), initialDraft = intent.getStringExtra("draft"), initialTab = runCatching { com.coach.ui.AppTab.valueOf(intent.getStringExtra("tab").orEmpty()) }.getOrDefault(com.coach.ui.AppTab.TODAY))
                    }
                }
            }
        }
        jank = JankStats.createAndTrack(window) { frame ->
            synchronized(frameMs) { frameMs += frame.frameDurationUiNanos / 1_000_000f; if (frame.isJank) jankFrames++ }
        }
    }

    private fun createController(serverUrl: String, credentials: PairedCredentials): CoachController {
        val db = ClientDatabase(AndroidSqliteDriver(ClientDatabase.Schema, this, "coach-client.db"))
        val reporter = com.coach.client.UsageReporter(serverUrl, credentials.deviceId, credentials.token)
        usageReporter = reporter
        val telemetry = com.coach.client.UsageTelemetry(db, System::currentTimeMillis, reporter::send)
        val http = HttpLedgerTransport(serverUrl, credentials.deviceId, credentials.token)
        val sync = SyncEngine(db, credentials.deviceId, http, listOf(BuildConfig.COACH_COURSE), { UUID.randomUUID().toString() }, ::sha256Hex)
        // 首字样本进 logcat（tag CoachTtft），总验收按它算 p50。
        val model = TutorModel(System::currentTimeMillis, telemetry) { s ->
            Log.i("CoachTtft", """{"thread":"${s.threadId}","msg":"${s.messageId}","kind":"${s.kind}","ms":${s.ms},"model":"${s.model}","fallback":${s.fallback}}""")
        }
        // Content-free end/snapshot hashes let acceptance compare reconnect output with the ledger.
        scope.launch {
            val reported=HashSet<String>()
            model.state.collect { state ->
                state.threads.values.flatMap { it.items }.filterIsInstance<ChatItem.Tutor>().forEach { item ->
                    val message=item.message
                    val key="${message.id}:${message.epoch}:${message.status}:${message.text.length}"
                    if(message.done && message.text.isNotEmpty() && reported.add(key)) {
                        Log.i("CoachStream", """{"msg":"${message.id}","status":"${message.status}","chars":${message.text.length},"snapshots":${message.epoch},"sha256":"${sha256Hex(message.text.toByteArray(Charsets.UTF_8))}"}""")
                    }
                }
            }
        }
        // 连接状态变化进 logcat（tag CoachConn），验收用它给「断线→横幅」计时：横幅与这个状态同源。
        scope.launch { model.state.map { it.connected }.distinctUntilChanged().collect { Log.i("CoachConn", "connected=$it") } }
        val client = TutorClient(serverUrl, credentials.deviceId, credentials.token, model, telemetry)
        val mathHttp = com.coach.client.MathHttp(serverUrl, credentials.deviceId, credentials.token)
        math = mathHttp
        transport = http
        tutor = client
        sync.start(scope)
        client.start(scope)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        injectFormulaFaultSample(model)
        val controller = CoachController(
            courseId = BuildConfig.COACH_COURSE, sync = sync, tutor = client,
            formatClock = { clock.format(Date(it)) },
            math = com.coach.ui.MathAssets(mathHttp),
            course = com.coach.client.CourseHttp(serverUrl, credentials.deviceId, credentials.token, BuildConfig.COACH_COURSE),
        )
        return controller
    }

    /**
     * Debug builds only, `--ez faultSample true --es openKp <kp>`: shows a clearly labelled sample message with invalid TeX on the real study page,
     * to check how a formula the server cannot draw looks to the learner. It is never a tutor answer and is not stored anywhere.
     */
    private fun injectFormulaFaultSample(model: TutorModel) {
        val kp = intent.getStringExtra("openKp") ?: return
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra("faultSample", false)) return
        val thread = "kp:$kp"
        val id = "fault-sample-1"
        val text = "**【故障注入样本 · 不是导师的真实回答】** 下面故意写了两个错误的公式，用来检查界面怎么提示。\n\n" +
            "先看一个正确的：弹性 \$\\varepsilon=-0.17\$ 表示价格上升 1%，需求下降 0.17%。\n\n" +
            "句子里的错误公式：这里把 \$\\frac{\\Delta Q\$ 少写了一半括号，后面的文字还要接着读。\n\n" +
            "单独一行的错误公式：\n\n\$\$\\varepsilon=\\frac{\\Delta Q}{\$\$\n\n" +
            "这行文字在公式后面，不应该被挤走。"
        fun frame(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.buildJsonObject { pairs.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) } }.toString()
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            model.onFrame(frame("t" to "msg.start", "msg" to id, "threadId" to thread, "model" to "故障注入样本"))
            model.onFrame(frame("t" to "msg.snapshot", "msg" to id, "threadId" to thread, "i" to "1", "text" to text, "status" to "complete"))
            model.onFrame(frame("t" to "msg.end", "msg" to id, "threadId" to thread, "status" to "complete"))
        }
    }

    override fun onResume() {
        super.onResume()
        tutor?.telemetry?.record("open")
    }

    override fun onPause() {
        reportJank()
        super.onPause()
    }

    /** 帧耗时分位（UI 线程）。滑动或流式输出一段时间后切后台即可在 logcat 里读到。 */
    private fun reportJank() {
        val snapshot = synchronized(frameMs) { frameMs.sorted().also { frameMs.clear() } to jankFrames.also { jankFrames = 0 } }
        val (sorted, bad) = snapshot
        if (sorted.isEmpty()) return
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        Log.i("CoachJank", "frames=${sorted.size} jank=$bad p50=${"%.1f".format(pct(.5))}ms p95=${"%.1f".format(pct(.95))}ms p99=${"%.1f".format(pct(.99))}ms max=${"%.1f".format(sorted.last())}ms")
    }

    override fun onDestroy() {
        jank?.isTrackingEnabled = false
        usageReporter?.close()
        tutor?.close()
        math?.close()
        transport?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The acceptance override only selects another port on the configured Mac. */
internal fun coachServerUrl(requested: String?, packageName: String = "com.coach.android"): String {
    val configured = "http://${BuildConfig.COACH_TAIL_HOST}:${BuildConfig.COACH_PORT}"
    val accepted = if (BuildConfig.DEBUG) requested?.takeIf { candidate -> runCatching {
        val uri = java.net.URI(candidate)
        uri.scheme == "http" && uri.host == BuildConfig.COACH_TAIL_HOST && uri.port in 1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in setOf("", "/")
    }.getOrDefault(false) } else null
    // A card package must not fall back to the production port when the extra is missing or rejected.
    if (packageName != "com.coach.android") {
        val port = accepted?.let { java.net.URI(it).port }
        if (accepted == null || port == 18791 || port == 8791 || port == 8790) {
            error("Refusing production default for $packageName; coachUrl=${requested ?: "<empty>"}")
        }
        return accepted
    }
    if (!BuildConfig.DEBUG || requested.isNullOrBlank()) return configured
    return accepted ?: configured
}
