package com.coach.desktop

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
    val home = System.getProperty("user.home")
    val baseUrl = env("COACH_URL", "http://127.0.0.1:18791")
    val deviceId = env("COACH_DEVICE_ID", "desktop")
    val course = env("COACH_COURSE", "ECNM10119")
    val tokenFile = File(env("COACH_TOKEN_FILE", File(home, "Coach/data/device-token").path))
    val token = tokenFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()

    val clientDir = File(home, "Coach/client").also { it.mkdirs() }
    val db = ClientDatabase(JdbcSqliteDriver("jdbc:sqlite:${File(clientDir, "ledger.sqlite").path}", Properties(), ClientDatabase.Schema))
    val sync = SyncEngine(
        db, deviceId, HttpLedgerTransport(baseUrl, deviceId, token), listOf(course),
        { UUID.randomUUID().toString() },
        { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
    )

    // 客户端首字样本：界面内可见，同时逐行追加到本地文件供总验收算 p50。
    val ttftFile = File(home, "Coach/state/client-ttft.jsonl").also { it.parentFile.mkdirs() }
    val model = TutorModel(System::currentTimeMillis) { s: TtftSample ->
        ttftFile.appendText("""{"at":"${Instant.now()}","thread":"${s.threadId}","msg":"${s.messageId}","kind":"${s.kind}","ms":${s.ms},"model":"${s.model}","fallback":${s.fallback}}""" + "\n")
    }
    val tutor = TutorClient(baseUrl, deviceId, token, model)
    val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    val packDir = File(env("COACH_PACK_DIR", File(home, "Coach/corpus/packs/$course").path))
    val mathService = MathService.locate()
    val fetch: (suspend (String, Boolean) -> com.coach.ui.markdown.MathAsset?)? = mathService?.let { service ->
        { tex, display -> kotlinx.coroutines.withContext(Dispatchers.IO) { service.render(tex, display) } }
    }
    val math = MathAssets(fetch)
    val controller = CoachController(
        courseId = course, sync = sync, tutor = tutor,
        packFor = { kpId -> File(packDir, "$kpId.md").takeIf { it.exists() }?.readText()?.take(40_000).orEmpty() },
        formatClock = { clock.format(Instant.ofEpochMilli(it)) },
        math = math,
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    sync.start(scope)
    tutor.start(scope)

    application {
        Window(onCloseRequest = { tutor.close(); exitApplication() }, title = "Coach", state = rememberWindowState(width = 900.dp, height = 700.dp)) {
            CoachApp(controller)
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { kotlinx.coroutines.delay(it * 1000); exitApplication() }
            }
        }
    }
}
