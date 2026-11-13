package com.coach.desktop

import kotlinx.coroutines.cancel

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

private fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

internal fun resolveCourse(environment: String?, packaged: String?): String =
    sequenceOf(environment, packaged).firstOrNull { !it.isNullOrBlank() }?.trim()
        ?: error("No course configured. Set coach.course in ~/Coach/config/local.properties when building Coach, or set COACH_COURSE.")

/** Installed clients resolve their Mac locally and enter learning only with their own identity. */
fun runCoach() {
    refuseProductionDefaults(System::getenv, System::getProperty)?.let { error(it) }
    val settings = DesktopConnectionSettings()
    val course = resolveCourse(System.getenv("COACH_COURSE"), System.getProperty("coach.course"))
    val size = env("COACH_WINDOW", "900x700").split("x").let { (it.getOrNull(0)?.toIntOrNull() ?: 900) to (it.getOrNull(1)?.toIntOrNull() ?: 700) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "LerningCoach", state = rememberWindowState(width = size.first.dp, height = size.second.dp)) {
            DesktopPairingGate(settings) { identity ->
                val session = remember(identity) { DesktopLearningSession(settings, identity, course) }
                DisposableEffect(session) {
                    session.start()
                    onDispose { session.close() }
                }
                CoachApp(session.controller, System.getenv("COACH_OPEN_KP"), System.getenv("COACH_AUTOSTART") == "1", initialSource = System.getenv("COACH_OPEN_SOURCE") == "1",
                    courseActions = { view -> NotesExportAction(view, session.controller, session.mathHttp) })
                LaunchedEffect(session) { System.getenv("COACH_OPEN_KP")?.let { VerifyHooks.autopilot(session.controller, it) } }
            }
            LaunchedEffect(Unit) { VerifyHooks.captures(window) }
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { kotlinx.coroutines.delay(it * 1000); exitApplication() }
            }
        }
    }
}

/** The gate disposes all network clients and the ledger when an identity is revoked. */
internal class DesktopLearningSession(settings: DesktopConnectionSettings, identity: DesktopIdentity, course: String) : AutoCloseable {
    init {
        requireDevelopmentServer(settings, identity.serverUrl)
    }
    val home = settings.home.toString()
    private val baseUrl = identity.serverUrl
    private val deviceId = identity.credentials.deviceId
    private val token = identity.credentials.token

    private val clientDir = settings.clientDirectory.toFile().also { it.mkdirs() }
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${File(clientDir, "ledger.sqlite").path}", Properties(), ClientDatabase.Schema)
    private val db = ClientDatabase(driver)
    private val transport = HttpLedgerTransport(baseUrl, deviceId, token)
    private val sync = SyncEngine(
        db, deviceId, transport, listOf(course),
        { UUID.randomUUID().toString() },
        { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
        agentRunId = settings.agentRunId,
    )

    private val reporter = com.coach.client.UsageReporter(baseUrl, deviceId, token)
    private val telemetry = com.coach.client.UsageTelemetry(db, System::currentTimeMillis, reporter::send)
    // 客户端首字样本：界面内可见，同时逐行追加到本地文件供总验收算 p50。
    private val ttftFile = File(home, "Coach/state/client-ttft.jsonl").also { it.parentFile.mkdirs() }
    private val model = TutorModel(System::currentTimeMillis, telemetry) { s: TtftSample ->
        ttftFile.appendText("""{"at":"${Instant.now()}","thread":"${s.threadId}","msg":"${s.messageId}","kind":"${s.kind}","ms":${s.ms},"model":"${s.model}","fallback":${s.fallback}}""" + "\n")
    }
    private val tutor = TutorClient(baseUrl, deviceId, token, model, telemetry, agentRunId = settings.agentRunId)
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val packDir = File(env("COACH_PACK_DIR", File(home, "Coach/corpus/packs/$course").path))
    val mathHttp = com.coach.client.MathHttp(baseUrl, deviceId, token)
    private val math = MathAssets(mathHttp)
    private val courseHttp = com.coach.client.CourseHttp(baseUrl, deviceId, token, course)
    val controller = CoachController(
        courseId = course, sync = sync, tutor = tutor,
        packFor = { kpId -> File(packDir, "$kpId.md").takeIf { it.exists() }?.readText()?.take(40_000).orEmpty() },
        formatClock = { clock.format(Instant.ofEpochMilli(it)) },
        math = math,
        course = courseHttp,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun start() { sync.start(scope); tutor.start(scope) }
    override fun close() {
        scope.cancel()
        tutor.close()
        transport.close()
        reporter.close()
        mathHttp.close()
        courseHttp.close()
        runBlocking { scope.coroutineContext[Job]?.join() }
        driver.close()
    }
}
