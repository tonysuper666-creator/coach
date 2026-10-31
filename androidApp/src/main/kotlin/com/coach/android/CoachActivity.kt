package com.coach.android

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 凭据来源。TON-1093 的配对落地后，把 [load] 换成 Keystore 读取即可，界面与同步层不变。 */
internal object CoachCredentials {
    data class Value(val baseUrl: String, val deviceId: String, val token: String, val course: String)

    private const val PREFS = "coach-dev-credentials"

    /** 启动参数优先（`am start --es coachUrl … --es deviceId … --es token … --es course …`），并存进应用私有目录，之后从桌面图标也能打开。 */
    fun load(context: Context, intent: android.content.Intent): Value? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        intent.getStringExtra("token")?.takeIf { it.isNotBlank() }?.let {
            prefs.edit()
                .putString("url", intent.getStringExtra("coachUrl") ?: "")
                .putString("device", intent.getStringExtra("deviceId") ?: "")
                .putString("token", it)
                .putString("course", intent.getStringExtra("course") ?: "")
                .commit()
        }
        val token = prefs.getString("token", null)?.takeIf { it.isNotBlank() } ?: return null
        val url = prefs.getString("url", "")!!.ifBlank { "http://${BuildConfig.COACH_TAIL_HOST}:18791" }
        val course = prefs.getString("course", "")!!.ifBlank { BuildConfig.COACH_COURSE }
        return Value(url, prefs.getString("device", "")!!.ifBlank { "android" }, token, course)
    }
}

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
        val credentials = CoachCredentials.load(this, intent)
        if (credentials == null || credentials.course.isBlank()) {
            setContent { CoachTheme { CoachNotice(if (credentials == null) "还没有配对。请先配对这台设备。" else "没有指定课程号（coach.course）。") } }
            return
        }
        val db = ClientDatabase(AndroidSqliteDriver(ClientDatabase.Schema, this, "coach-client.db"))
        val http = HttpLedgerTransport(credentials.baseUrl, credentials.deviceId, credentials.token)
        val sync = SyncEngine(db, credentials.deviceId, http, listOf(credentials.course), { UUID.randomUUID().toString() }, ::sha256Hex)
        // 首字样本进 logcat（tag CoachTtft），总验收按它算 p50。
        val model = TutorModel(System::currentTimeMillis) { s ->
            Log.i("CoachTtft", """{"kind":"${s.kind}","ms":${s.ms},"model":"${s.model}","fallback":${s.fallback}}""")
        }
        val client = TutorClient(credentials.baseUrl, credentials.deviceId, credentials.token, model)
        val mathHttp = com.coach.client.MathHttp(credentials.baseUrl, credentials.deviceId, credentials.token)
        math = mathHttp
        transport = http
        tutor = client
        sync.start(scope)
        client.start(scope)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val controller = CoachController(
            courseId = credentials.course, sync = sync, tutor = client,
            formatClock = { clock.format(Date(it)) },
            math = com.coach.ui.MathAssets(mathHttp),
        )
        setContent { CoachApp(controller, initialKp = intent.getStringExtra("openKp"), initialAutoStart = intent.getBooleanExtra("autoStart", false)) }
        // JankStats 需要 DecorView，setContent 之后才有。
        jank = JankStats.createAndTrack(window) { frame ->
            synchronized(frameMs) { frameMs += frame.frameDurationUiNanos / 1_000_000f; if (frame.isJank) jankFrames++ }
        }
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
