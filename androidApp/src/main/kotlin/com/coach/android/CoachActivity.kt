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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CoachActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: HttpLedgerTransport? = null
    private var tutor: TutorClient? = null
    private var jank: JankStats? = null
    private var math: com.coach.client.MathHttp? = null
    private val frameMs = ArrayList<Float>()
    private var jankFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val serverUrl="http://${BuildConfig.COACH_TAIL_HOST}:${BuildConfig.COACH_PORT}"
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
                        CoachApp(controller, initialKp = intent.getStringExtra("openKp"), initialAutoStart = intent.getBooleanExtra("autoStart", false))
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
        val http = HttpLedgerTransport(serverUrl, credentials.deviceId, credentials.token)
        val sync = SyncEngine(db, credentials.deviceId, http, listOf(BuildConfig.COACH_COURSE), { UUID.randomUUID().toString() }, ::sha256Hex)
        // 首字样本进 logcat（tag CoachTtft），总验收按它算 p50。
        val model = TutorModel(System::currentTimeMillis) { s ->
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
        val client = TutorClient(serverUrl, credentials.deviceId, credentials.token, model)
        val mathHttp = com.coach.client.MathHttp(serverUrl, credentials.deviceId, credentials.token)
        math = mathHttp
        transport = http
        tutor = client
        sync.start(scope)
        client.start(scope)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val controller = CoachController(
            courseId = BuildConfig.COACH_COURSE, sync = sync, tutor = client,
            formatClock = { clock.format(Date(it)) },
            math = com.coach.ui.MathAssets(mathHttp),
        )
        return controller
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
        tutor?.close()
        math?.close()
        transport?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
