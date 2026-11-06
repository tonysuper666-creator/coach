package com.coach.server

import com.coach.core.LedgerActor
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import kotlin.test.*

class ExamFocusRoutesTest {
    @Test fun privateWeightsAreReadOnlyAuthenticatedAndStrippedToWeekWeightTopic() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("exam-focus-route")
        val focusDir = Files.createDirectories(dir.resolve("focus"))
        // 合成样例：真实押题权重只在本机私有目录里，不进仓库。
        Files.createDirectories(focusDir.resolve("SYN101"))
        Files.writeString(focusDir.resolve("SYN101/exam-focus.json"), """
            {"course":"SYN101","notes":"private reasoning that must not leave the server",
             "weeks":[{"week":2,"weight":3,"topic":"Synthetic topic B","why":"private"},
                      {"week":1,"weight":1,"topic":"Synthetic topic A"},
                      {"week":0,"weight":5},{"week":3,"weight":-1},{"week":"x","weight":2}]}
        """.trimIndent())
        Files.writeString(dir.resolve("secret.json"), """{"weeks":[{"week":1,"weight":1}]}""")
        // 目录按课程编号起名、文件里写账本课程 id 的那种布局。
        Files.createDirectories(focusDir.resolve("SYN202"))
        Files.writeString(focusDir.resolve("SYN202/exam-focus.json"), """{"course":"course_syn202","weeks":[{"week":4,"weight":2}]}""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir.resolve("ledger")).also { it.enroll("phone", "synthetic-private-token-32-characters") }
        val actor = LedgerActor(scope, store)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)); examFocusRoutes(focusDir)
        }
        engine.start(false)
        try {
            val port = engine.engine.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            suspend fun get(path: String, authenticated: Boolean = true): HttpResponse<String> = withContext(Dispatchers.IO) {
                val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(java.time.Duration.ofSeconds(5))
                if (authenticated) b.header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                client.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(401, get("/v1/courses/SYN101/exam-focus", authenticated = false).statusCode())
            val ok = get("/v1/courses/SYN101/exam-focus")
            assertEquals(200, ok.statusCode())
            assertFalse(ok.body().contains("private"), "only week/weight/topic leave the server")
            val weeks = Json.parseToJsonElement(ok.body()).jsonObject.getValue("weeks").jsonArray.map { it.jsonObject }
            assertEquals(listOf(2, 1), weeks.map { it.getValue("week").jsonPrimitive.int })
            assertEquals("Synthetic topic B", weeks.first().getValue("topic").jsonPrimitive.content)
            val byCourseId = Json.parseToJsonElement(get("/v1/courses/course_syn202/exam-focus").body()).jsonObject
            assertEquals(listOf(4), byCourseId.getValue("weeks").jsonArray.map { it.jsonObject.getValue("week").jsonPrimitive.int })
            assertEquals(404, get("/v1/courses/OTHER/exam-focus").statusCode())
            assertEquals(404, get("/v1/courses/..%2Fsecret/exam-focus").statusCode())
        } finally { engine.stopSuspend(0, 500); actor.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }
}
