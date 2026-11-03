package com.coach.desktop

import kotlinx.coroutines.cancel

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.HttpLedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.TtftSample
import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.client.db.ClientDatabase
import com.coach.ui.CoachApp
import com.coach.ui.CoachController
import com.coach.ui.MathAssets
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/** 桌面端「今天 / 学习」入口：连本机（或 Tailscale 上）的 Coach 服务端，账本副本在 `~/Coach/client/`。 */
fun runCoach() {
    refuseProductionDefaults(System::getenv, System::getProperty)?.let { error(it) }
    val home = System.getProperty("user.home")
    val baseUrl = env("COACH_URL", "http://127.0.0.1:18791")
    val deviceId = env("COACH_DEVICE_ID", "desktop")
    val course = env("COACH_COURSE", "ECNM10119")
    val tokenFile = File(env("COACH_TOKEN_FILE", File(home, "Coach/data/device-token").path))
    val token = tokenFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()

    val clientDir = File(env("COACH_CLIENT_DIR", File(home, "Coach/client").path)).also { it.mkdirs() }
    val db = ClientDatabase(JdbcSqliteDriver("jdbc:sqlite:${File(clientDir, "ledger.sqlite").path}", Properties(), ClientDatabase.Schema))
    val sync = SyncEngine(
        db, deviceId, HttpLedgerTransport(baseUrl, deviceId, token), listOf(course),
        { UUID.randomUUID().toString() },
        { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
    )

    val reporter = com.coach.client.UsageReporter(baseUrl, deviceId, token)
    val telemetry = com.coach.client.UsageTelemetry(db, System::currentTimeMillis, reporter::send)
    // 客户端首字样本：界面内可见，同时逐行追加到本地文件供总验收算 p50。
    val ttftFile = File(home, "Coach/state/client-ttft.jsonl").also { it.parentFile.mkdirs() }
    val model = TutorModel(System::currentTimeMillis, telemetry) { s: TtftSample ->
        ttftFile.appendText("""{"at":"${Instant.now()}","thread":"${s.threadId}","msg":"${s.messageId}","kind":"${s.kind}","ms":${s.ms},"model":"${s.model}","fallback":${s.fallback}}""" + "\n")
    }
    val tutor = TutorClient(baseUrl, deviceId, token, model, telemetry)
    val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    val packDir = File(env("COACH_PACK_DIR", File(home, "Coach/corpus/packs/$course").path))
    val mathHttp = com.coach.client.MathHttp(baseUrl, deviceId, token)
    val math = MathAssets(mathHttp)
    val controller = CoachController(
        courseId = course, sync = sync, tutor = tutor,
        packFor = { kpId -> File(packDir, "$kpId.md").takeIf { it.exists() }?.readText()?.take(40_000).orEmpty() },
        formatClock = { clock.format(Instant.ofEpochMilli(it)) },
        math = math,
        course = com.coach.client.CourseHttp(baseUrl, deviceId, token, course),
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    sync.start(scope)
    tutor.start(scope)

    // COACH_WINDOW=宽x高（dp），用来在桌面上模拟 Fold8 外屏等窄屏。
    val size = env("COACH_WINDOW", "900x700").split("x").let { (it.getOrNull(0)?.toIntOrNull() ?: 900) to (it.getOrNull(1)?.toIntOrNull() ?: 700) }
    application {
        Window(onCloseRequest = { tutor.close(); reporter.close(); scope.cancel(); mathHttp.close(); exitApplication() }, title = "Coach", state = rememberWindowState(width = size.first.dp, height = size.second.dp)) {
            CoachApp(controller, System.getenv("COACH_OPEN_KP"), System.getenv("COACH_AUTOSTART") == "1", initialSource = System.getenv("COACH_OPEN_SOURCE") == "1")
            LaunchedEffect(Unit) { VerifyHooks.captures(window) }
            LaunchedEffect(Unit) { System.getenv("COACH_OPEN_KP")?.let { VerifyHooks.autopilot(controller, it) } }
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { kotlinx.coroutines.delay(it * 1000); exitApplication() }
            }
        }
    }
}
