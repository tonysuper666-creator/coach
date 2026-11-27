package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.IncidentRedactor
import com.coach.domain.json
import com.coach.domain.str
import com.coach.store.LedgerStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReliabilityCountsTest {
    @Test fun versionMathDropsSecretsAndMatchesTheTable() {
        val root = Files.createTempDirectory("coach-rates-")
        try {
            val counts = ReliabilityCounts(root.resolve("reliability"))
            val secret = "synthetic.token.value.not.real.0123456789abcdefABCDEF"
            counts.outcome("explain", secret, "ok")
            counts.outcome("explain", "2.3.0", "ok")
            counts.outcome("explain", "2.3.0", "ok")
            counts.outcome("explain", "2.3.0", "timeout")
            counts.outcome("ask", "2.3.0", "ok")
            counts.outcome("ask", "2.3.0", "refusal")
            counts.outcome("explain", "2.3.0", "C:\\Users\\learner\\Books\\synthetic-chapter.pdf")
            counts.outcome("guide", "2.3.0", "timeout")
            counts.outcome("explain", "2.3.0", null)
            counts.incident("version: 2.3.0\nkind: crash\n" + IncidentRedactor.syntheticBlob())
            counts.incident("kind: not_a_crash\nversion: 9.9.9\n\nversion: 2.3.0\nkind: import_failure\n")
            counts.touch("2.3.0", "device-a", Instant.parse("2026-11-26T03:00:00Z"))
            counts.touch("2.3.0", "device-a", Instant.parse("2026-11-26T18:00:00Z"))
            val table = counts.render()
            assertEquals(
                listOf(
                    "版本\t崩溃/活跃设备天\t讲解失败率\t提问失败率\t前三失败原因",
                    "2.3.0\t2/1\t50.0% (2/4)\t50.0% (1/2)\t超时 1，拒答 1，其他 1",
                    "unknown\t0/0\t0.0% (0/1)\tn/a\t-",
                ).joinToString("\n", postfix = "\n"),
                table,
            )
            val stored = Files.readString(root.resolve("reliability").resolve("counts.json"))
            for (needle in IncidentRedactor.needles() + listOf(secret, "device-a", "9.9.9", "synthetic-chapter.pdf", "learner")) {
                assertFalse(stored.contains(needle), needle)
            }
            assertTrue(stored.contains(deviceKey("device-a")))
            assertEquals("unknown", sanitizeVersion(secret))
            assertEquals("quota", reliabilityCodeForFriend("free_grant_exhausted"))
            assertEquals("other", reliabilityCodeForFriend("daily_quota"))
            assertEquals("other", reliabilityCodeForFriend("unavailable"))
            assertEquals("timeout", classifyReaderText("讲解正文迟迟未开始，已停止等待。可继续阅读或手动重试。"))
            assertEquals("refusal", classifyReaderText("依据不足，无法确认。"))
            assertEquals("source", classifyReaderText("缺少可定位的课件或教材页依据，暂时无法确认。"))
            assertEquals("source", classifyReaderText("回答缺少可核对的页码依据，暂时无法确认。"))
            assertEquals("other", classifyReaderText("追问未完成，暂时无法确认。可返回阅读或手动重试。"))
            assertEquals("other", classifyReaderText("讲解服务暂不可用，无法确认。"))
            assertNull(classifyReaderText("追问已取消。"))
            assertNull(classifyReaderText("追问正在处理，请稍后手动重试。"))
            assertEquals("ok", classifyReaderText("有依据的解释。"))
            assertEquals("timeout", classifyChat("failed", "first_token_timeout"))
            assertEquals("quota", classifyChat("failed", "http_429"))
            assertEquals("refusal", classifyChat("failed", "empty_visible_body"))
            assertEquals("source", classifyChat("failed", "citation_rejected"))
            assertNull(classifyChat("cancelled", "cancelled"))
            assertNull(reliabilityCodeForMock(HttpStatusCode.BadRequest, "选中追问格式不对。"))
            assertEquals("source", reliabilityCodeForMock(HttpStatusCode.ServiceUnavailable, "来源尚未核实"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun httpCountsExplainAskAndIncidentsWithoutKeepingTheRequest() {
        Harness().use { harness ->
            assertEquals(400, harness.post("/v1/library/explain", """{"kind":"explain","requestId":"aa16"}""").statusCode())
            val incident = "version: 2.3.0\nkind: crash\n" + IncidentRedactor.syntheticBlob()
            assertEquals(204, harness.post("/v1/incidents", incident).statusCode())
            harness.stub.stream(1, """{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", StubTutor.terminal("complete"), StubTutor.DONE)
            val success = harness.post("/v1/library/explain", harness.explainBody("ab12", "explain"))
            assertEquals(200, success.statusCode(), success.body())
            assertEquals("有依据的解释。【S1】", jsonText(success.body()))
            harness.stub.stream(3_000, """{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", StubTutor.terminal("complete"), StubTutor.DONE)
            val timedOut = harness.post("/v1/library/explain", harness.explainBody("ab13", "explain"))
            assertEquals(200, timedOut.statusCode(), timedOut.body())
            assertTrue(jsonText(timedOut.body()).contains("已停止等待"), timedOut.body())
            harness.stub.stream(1, """{"paragraphs":[]}""", StubTutor.terminal("complete"), StubTutor.DONE)
            val refused = harness.post("/v1/library/explain", harness.explainBody("ab14", "explain"))
            assertEquals(200, refused.statusCode(), refused.body())
            assertEquals("依据不足，无法确认。", jsonText(refused.body()))
            harness.stub.stream(1, """{"paragraphs":[]}""", StubTutor.terminal("complete"), StubTutor.DONE)
            val asked = harness.post("/v1/library/explain", harness.explainBody("ab15", "ask"))
            assertEquals(200, asked.statusCode(), asked.body())
            assertEquals("依据不足，无法确认。", jsonText(asked.body()))
            val stored = Files.walk(harness.root.resolve("reliability")).use { stream ->
                stream.filter { Files.isRegularFile(it) }.map { Files.readString(it) }.toList().joinToString("\n")
            }
            for (needle in IncidentRedactor.needles() + listOf("Synthetic passage.", "有依据的解释", "What does this sentence mean?", "phone", "synthetic.token")) {
                assertFalse(stored.contains(needle), needle)
            }
            assertTrue(stored.contains(deviceKey("phone")))
            val table = harness.counts.render()
            assertEquals(
                listOf(
                    "版本\t崩溃/活跃设备天\t讲解失败率\t提问失败率\t前三失败原因",
                    "2.3.0\t1/1\t66.7% (2/3)\t100.0% (1/1)\t拒答 2，超时 1",
                ).joinToString("\n", postfix = "\n"),
                table,
            )
            val script = repoFile("tools/dev/version-rates.py")
            val python = pythonCommand()
            assertTrue(python != null, "python3 is required to print the version table")
            val command = ProcessBuilder(python + listOf(script.toString(), "--data", harness.root.toString()))
                .redirectErrorStream(true)
            command.environment()["PYTHONIOENCODING"] = "utf-8"
            val printed = command.start().inputStream.bufferedReader(Charsets.UTF_8).readText().replace("\r\n", "\n")
            assertEquals(table, printed)
        }
    }

    private class Harness : AutoCloseable {
        val root: Path = Files.createTempDirectory("coach-rates-http-")
        val counts = ReliabilityCounts(root.resolve("reliability"))
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("ledger")).also { it.enroll("phone", TOKEN) }
        val actor = LedgerActor(jobs, store)
        val stub = StubTutor()
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            attributes.put(reliabilityCounts, counts)
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
            incidentReportRoutes(root.resolve("incident-reports"))
            libraryExplainRoutes(
                actor,
                ReaderExplanation(
                    actor,
                    BridgeClient(BridgeConfig(stub.baseUrl)),
                    timeouts = ReaderTimeouts(firstMs = 400, gapMs = 400, totalMs = 5_000),
                    readSources = { pack, _ -> pack },
                ),
                imports = ImportedBookSources(root.resolve("personal-imports")),
            )
        }.apply { start(false) }
        val client: HttpClient = HttpClient.newHttpClient()

        fun explainBody(requestId: String, kind: String): String {
            val book = "a".repeat(64)
            val text = "Synthetic passage."
            return json(
                "requestId" to requestId,
                "kind" to kind,
                "question" to "What does this sentence mean?",
                "selection" to text,
                "blocks" to JsonArray(listOf(json("id" to "$book:p1:0", "text" to text))),
                "localSource" to json(
                    "schema" to 1,
                    "book" to book,
                    "format" to "txt",
                    "title" to "Self-authored book",
                    "consented" to true,
                    "pages" to JsonArray(listOf(json("number" to 1, "length" to 200, "ocr" to false, "sha256" to "b".repeat(64)))),
                ),
            ).toString()
        }

        fun post(path: String, body: String): HttpResponse<String> = runBlocking {
            val port = engine.engine.resolvedConnectors().single().port
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("X-Coach-Device", "phone")
                .header("Authorization", "Bearer $TOKEN")
                .header("X-Coach-Version", "2.3.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        }

        override fun close() {
            engine.stop(100, 1_000)
            runBlocking { actor.close() }
            jobs.cancel()
            stub.close()
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val TOKEN = "synthetic-private-token-32-characters"
        fun jsonText(body: String): String = com.coach.domain.LedgerJson.parseToJsonElement(body).jsonObject.str("text")
        fun repoFile(relative: String): Path {
            var dir = Path.of("").toAbsolutePath()
            while (dir.parent != null && !Files.isRegularFile(dir.resolve(relative))) dir = dir.parent
            return dir.resolve(relative)
        }
        fun pythonCommand(): List<String>? {
            val candidates = listOf(listOf("python3"), listOf("python"), listOf("py", "-3"))
            for (command in candidates) {
                val ok = runCatching {
                    val process = ProcessBuilder(command + listOf("-c", "import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)"))
                        .redirectErrorStream(true).start()
                    process.inputStream.readBytes()
                    process.waitFor() == 0
                }.getOrDefault(false)
                if (ok) return command
            }
            return null
        }
    }
}
