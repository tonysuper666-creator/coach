package com.coach.desktop.reader

import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.*

/**
 * ReaderHttpSource 的契约行为：真实的 CourseHttp.reader（CIO 传输、鉴权头、路径编码、状态透传）打到本机回环的小服务上。
 * 回环服务只在测试进程里，端口随机，不是 18791，也不是任何生产/私人设备；响应形状来自 FakeReaderService（已对照服务端源码）。
 */
class ReaderHttpSourceTest {
    class Seen(val method: String, val rawPath: String, val query: String?, val auth: String?, val device: String?, val contentType: String?, val body: String)

    /** 回环服务：把 reader 路径下的请求转给 fake（它实现了和服务端同形状的响应），并记录每个请求。 */
    private class Loopback(val fake: FakeReaderService) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        var override: ((Seen) -> Triple<Int, ByteArray, String>?)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { ex -> handle(ex) }
            server.start()
        }
        fun stop() = server.stop(0)

        private fun reply(ex: HttpExchange, status: Int, body: ByteArray, type: String) {
            ex.responseHeaders.add("Content-Type", type); ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.readBytes().decodeToString()
            val seenReq = Seen(ex.requestMethod, ex.requestURI.rawPath, ex.requestURI.rawQuery, ex.requestHeaders.getFirst("Authorization"),
                ex.requestHeaders.getFirst("X-Coach-Device"), ex.requestHeaders.getFirst("Content-Type"), body)
            seen += seenReq
            try {
                override?.invoke(seenReq)?.let { (st, b, t) -> return reply(ex, st, b, t) }
                val prefix = "/v1/courses/c1/reader"
                if (!seenReq.rawPath.startsWith(prefix)) return reply(ex, 404, ByteArray(0), "text/plain")
                val parts = seenReq.rawPath.removePrefix(prefix).trim('/').split('/').filter { it.isNotEmpty() }
                val doc = parts.getOrNull(0)?.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
                val q = (seenReq.query ?: "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val r = runBlocking {
                    when {
                        parts.isEmpty() -> fake.catalog()
                        parts.size == 2 && parts[1] == "state" -> fake.state(doc!!)
                        parts.size == 3 && parts[1] == "pages" -> fake.page(doc!!, parts[2].toInt())
                        parts.size == 4 && parts[3] == "study" -> fake.study(doc!!, parts[2].toInt())
                        parts.size == 4 && parts[3] == "boxes" -> fake.boxes(doc!!, parts[2].toInt(), q["version"].orEmpty())
                        parts.size == 4 && parts[3] == "image" -> {
                            val png = fake.image(doc!!, parts[2].toInt(), q["version"])
                            return@runBlocking if (png == null) com.coach.client.ReaderReply(404, null) else { reply(ex, 200, png, "image/png"); null }
                        }
                        parts.size == 2 && parts[1] == "progress" -> {
                            val o = Json.parseToJsonElement(body).jsonObject
                            fake.saveProgress(doc!!, o.str("version"), o.str("pdfPage").toInt(), o.str("baseRevision").toLong(), o.str("mutationId"))
                        }
                        parts.size == 2 && parts[1] == "notes" -> fake.saveNote(doc!!, Json.parseToJsonElement(body).jsonObject)
                        else -> com.coach.client.ReaderReply(404, null)
                    }
                } ?: return
                if (r.status == null) { ex.close(); return } // 传输失败：直接断开
                reply(ex, r.status!!, (r.body?.toString() ?: "").toByteArray(), "application/json")
            } catch (e: Exception) { runCatching { reply(ex, 500, "boom".toByteArray(), "text/plain") } }
        }
        private fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content
    }

    private fun fake(): FakeReaderService = FakeReaderService(Triple("a", "va1", 10), Triple("b", "vb1", 6)).apply {
        bodyText = { d, p -> "${d.id}Alpha beta\nGamma delta p$p" }; anchorFor = { _, _ -> "Gamma delta" }
    }

    private fun withServer(f: FakeReaderService = fake(), block: suspend kotlinx.coroutines.CoroutineScope.(Loopback, ReaderHttpSource, ReaderRemote) -> Unit) {
        val lb = Loopback(f)
        try {
            val http = CourseHttp(lb.base, "dev1", "tok", "c1")
            val src = ReaderHttpSource(http.reader)
            runBlocking { block(this, lb, src, ReaderRemote(src)) }
        } finally { lb.stop() }
    }

    @Test fun everyCallUsesTheDocumentedPathQueryAuthAndBody() = withServer { lb, src, _ ->
        src.catalog(); src.page("a", 3); src.image("a", 3, "va1"); src.boxes("a", 3, "va1"); src.study("a", 3); src.state("a")
        src.saveProgress("a", "va1", 4, 0L, "m1")
        src.saveNote("a", buildJsonObject { put("id", "n1"); put("version", "va1") })
        val reqs = lb.seen.toList()
        assertEquals(
            listOf("GET /v1/courses/c1/reader", "GET /v1/courses/c1/reader/a/pages/3", "GET /v1/courses/c1/reader/a/pages/3/image?version=va1",
                "GET /v1/courses/c1/reader/a/pages/3/boxes?version=va1", "GET /v1/courses/c1/reader/a/pages/3/study", "GET /v1/courses/c1/reader/a/state",
                "POST /v1/courses/c1/reader/a/progress", "POST /v1/courses/c1/reader/a/notes"),
            reqs.map { it.method + " " + it.rawPath + (it.query?.let { q -> "?$q" } ?: "") },
        )
        assertTrue(reqs.all { it.auth == "Bearer tok" && it.device == "dev1" }, "每个请求都带配对凭据")
        val progress = Json.parseToJsonElement(reqs[6].body).jsonObject
        assertEquals(setOf("version", "pdfPage", "baseRevision", "mutationId"), progress.keys)
        assertEquals("application/json", reqs[6].contentType?.substringBefore(';'))
        assertEquals(JsonPrimitive("n1"), Json.parseToJsonElement(reqs[7].body).jsonObject["id"], "批注 body 原样透传")
    }

    @Test fun documentIdsAreEncodedIntoThePathNotTheQuery() = withServer { lb, src, _ ->
        src.page("a b/ü", 1)
        assertEquals("/v1/courses/c1/reader/a%20b%2F%C3%BC/pages/1", lb.seen.single().rawPath)
    }

    @Test fun statusCodesAndJsonBodiesPassThroughAndNonJsonBodiesBecomeNull() = withServer { lb, src, _ ->
        lb.override = { r ->
            when {
                r.rawPath.endsWith("/pages/1") -> Triple(401, """{"error":"no"}""".toByteArray(), "application/json")
                r.rawPath.endsWith("/pages/2") -> Triple(409, """{"error":"资料版本已改变"}""".toByteArray(), "application/json")
                r.rawPath.endsWith("/pages/3") -> Triple(500, "boom".toByteArray(), "text/plain")
                r.rawPath.endsWith("/pages/4") -> Triple(200, "not json".toByteArray(), "text/plain")
                else -> null
            }
        }
        assertEquals(401, src.page("a", 1).status); assertEquals("no", src.page("a", 1).body!!["error"]!!.let { (it as JsonPrimitive).content })
        val conflict = src.page("a", 2); assertEquals(409, conflict.status); assertEquals("资料版本已改变", (conflict.body!!["error"] as JsonPrimitive).content)
        assertEquals(500, src.page("a", 3).status); assertNull(src.page("a", 3).body)
        val garbled = src.page("a", 4); assertEquals(200, garbled.status); assertNull(garbled.body, "200 但不是 JSON：body 为 null，由上层判缺字段")
    }

    @Test fun imageBytesAreReturnedAndAFailureIsNull() = withServer { lb, src, _ ->
        lb.fake.withImage = true
        val png = src.image("a", 1, "va1")!!
        assertEquals(0x89.toByte(), png[0]); assertEquals('P'.code.toByte(), png[1])
        lb.fake.withImage = false
        assertNull(src.image("a", 1, "va1"), "404 → null，不当成图片")
    }

    @Test fun aDeadServerIsATransportFailureNotAnException() {
        val lb = Loopback(fake()); val http = CourseHttp(lb.base, "dev1", "tok", "c1"); lb.stop()
        runBlocking {
            val src = ReaderHttpSource(http.reader)
            assertNull(src.page("a", 1).status); assertNull(src.catalog().status); assertNull(src.image("a", 1, null))
            val r = ReaderRemote(src); r.loadCatalog(); assertEquals(ReaderFailure.Offline, r.failure)
        }
    }

    @Test fun endToEndThroughTheRealTransportOpenShowHighlightSaveAndRestore() = withServer { lb, src, r ->
        lb.fake.withImage = true
        lb.fake.docs.getValue("a").apply { progressPage = 3; revision = 2 }
        r.loadCatalog(); assertEquals(listOf("a", "b"), r.documents.map { it.id })
        assertEquals(3, r.open("a")); r.show(3)
        val view = (r.page as PageState.Ready).view
        assertEquals("aAlpha beta\nGamma delta p3", view.text); assertNotNull(view.image)
        val boxes = (r.boxes as BoxesState.Ready).boxes
        assertIs<Located.Found>(QuoteLocator.locate(boxes, r.studyQuestions.single().anchor))
        r.saveProgress(5); assertEquals(SaveState.Saved(5), r.save)
        val d = NoteDraft("n1", "a", "va1", 3, view.textSha256, 0, 11, view.text.substring(0, 11), "真实传输上的批注", 0L)
        r.saveNote(d); assertIs<NoteSaveState.Saved>(r.noteSave)
        val again = ReaderRemote(src); again.loadCatalog()
        assertEquals(5, again.open("a")); assertEquals("真实传输上的批注", again.notes.single().note)
    }

    @Test fun conflictsKeepTheDraftOverTheRealTransport() = withServer { lb, _, r ->
        r.loadCatalog(); r.open("a"); r.show(1)
        lb.fake.otherDeviceSaves("a", 7)
        r.saveProgress(2)
        val c = r.save as SaveState.Conflict
        assertEquals(2, c.localPage); assertEquals(7, c.remote?.pdfPage); assertEquals(7, lb.fake.docs.getValue("a").progressPage)
        val v = (r.page as PageState.Ready).view
        val d = NoteDraft("n1", "a", "va1", 1, v.textSha256, 0, 5, v.text.substring(0, 5), "v1", 0L)
        r.saveNote(d); val rev = (r.noteSave as NoteSaveState.Saved).revision
        lb.fake.otherDeviceEditsNote("a", "n1", "对方改的")
        r.saveNote(d.copy(note = "我改的", baseRevision = rev))
        val nc = r.noteSave as NoteSaveState.Conflict
        assertEquals("对方改的", nc.server?.note)
    }

    @Test fun lateOrCancelledResponsesNeverLandOnTheNewBookOrPage() = withServer { lb, _, r ->
        r.loadCatalog(); r.open("a")
        val (entered, gate) = lb.fake.hold("a", 2)
        val slow = launch(Dispatchers.IO) { r.show(2) }
        withContext(Dispatchers.IO) { entered.await() }
        r.open("b"); r.show(1)
        gate.complete(Unit); slow.join()
        assertEquals("bAlpha beta\nGamma delta p1", (r.page as PageState.Ready).view.text, "迟到的 A 书第2页不得覆盖 B 书")
        // 取消：翻页过快时旧请求被取消，新页不受影响
        val (e2, g2) = lb.fake.hold("b", 3)
        val cancelled = launch(Dispatchers.IO) { r.show(3) }
        withContext(Dispatchers.IO) { e2.await() }
        cancelled.cancelAndJoin(); r.show(2)
        g2.complete(Unit)
        assertEquals("bAlpha beta\nGamma delta p2", (r.page as PageState.Ready).view.text)
    }

    @Test fun missingFieldsAndUnknownPagesAreExplicitOverTheRealTransport() = withServer { lb, _, r ->
        r.loadCatalog(); r.open("a")
        lb.fake.pageBodyPatch = { JsonObject(it - "text") }; r.show(1)
        assertIs<ReaderFailure.BadResponse>((r.page as PageState.Failed).failure)
        lb.fake.pageBodyPatch = { it }
        val before = lb.seen.size; r.show(99)
        assertEquals(PageState.Failed(ReaderFailure.MissingPage(99)), r.page); assertEquals(before, lb.seen.size, "未知页码不发请求")
        lb.fake.pageStatus = 401; r.show(1); assertEquals(PageState.Failed(ReaderFailure.Unauthorized), r.page)
        lb.fake.pageStatus = null; r.show(1); assertEquals(PageState.Failed(ReaderFailure.Offline), r.page)
        lb.fake.pageStatus = 200
        lb.fake.boxesPatch = { JsonObject(it + ("version" to JsonPrimitive("other"))) }; r.show(1)
        assertIs<BoxesState.Failed>(r.boxes); assertIs<PageState.Ready>(r.page)
    }
}
