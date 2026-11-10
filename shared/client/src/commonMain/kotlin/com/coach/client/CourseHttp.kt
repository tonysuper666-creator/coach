package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 课程原文的一个文档（教材一章）。页号是 PDF 页；书页范围来自各页自己的书页号（有的页没有）。 */
data class SourceDoc(val docId: String, val title: String, val firstPdfPage: Int, val lastPdfPage: Int, val firstBookPage: Int?, val lastBookPage: Int?)
data class SourcePage(val docId: String, val pdfPage: Int, val bookPage: Int?, val text: String)

/** 模拟考接口的一次回答：成功时 [body] 是服务端原样的 JSON；失败时 [error] 是服务端写的原因，连不上 Mac 时两者都是 null。 */
class MockReply(val body: JsonObject?, val error: String?, val retryable: Boolean = false)
/** 题库里一道可以限时练的论述题。[fromMockPaper] 的题出自整套模拟卷，先练了那套卷就不再是生题。 */
data class EssayPrompt(val id: String, val week: Int, val marks: Double, val wordTarget: Int, val stem: String, val pack: String, val fromMockPaper: Boolean)
/** [weeks] 是课程大纲里已有知识点的周，自己贴题时从这里选；[unmatched] 是因为那一周还没有知识点而暂时练不了的题数。 */
data class EssayBankView(val prompts: List<EssayPrompt>, val weeks: List<Int>, val unmatched: Int)

/**
 * 只读的课程查询：记录页要的冻结题与评分标准、资料页要的原文。全部要联网问 Mac；
 * 返回 null 的意思就是「这次拿不到」（离线、或 Mac 上的服务还没更新到有这个接口），界面照实说，不拿占位内容顶。
 */
class CourseHttp(baseUrl: String, private val deviceId: String, private val token: String, private val course: String) {
    private val base = baseUrl.trimEnd('/')
    // Only a digest of this paired account scope reaches SQLite; credentials never enter note data.
    fun readerState(sync: SyncEngine, week: Int): JsonObject = sync.readerState(course, week, "$base\u0000$deviceId\u0000$token")
    fun saveReaderState(sync: SyncEngine, week: Int, state: JsonObject) = sync.saveReaderState(course, week, "$base\u0000$deviceId\u0000$token", state)
    init { validateCoachBaseUrl(baseUrl) }
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 3_000 }
        defaultRequest { header("Authorization", "Bearer $token"); header("X-Coach-Device", deviceId) }
    }

    private suspend fun getObject(path: String): JsonObject? = try {
        val response = http.get("$base/v1/courses/${course.encodeURLPathPart()}/$path")
        if (!response.status.isSuccess()) null else Json.parseToJsonElement(response.bodyAsText()).jsonObject
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private suspend fun mockCall(path: String, body: JsonObject?, traceId: String? = null): MockReply = try {
        val url = "$base/v1/courses/${course.encodeURLPathPart()}/$path"
        SubmissionTrace.mark(traceId, "http.postStart")
        val response: HttpResponse = if (body == null) http.get(url) else http.post(url) { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
        // postReturned = the suspending call returned; it makes no claim about when headers arrived.
        SubmissionTrace.mark(traceId, "http.postReturned")
        val parsed = runCatching {
            SubmissionTrace.mark(traceId, "http.bodyAsTextStart")
            val text = response.bodyAsText()
            SubmissionTrace.mark(traceId, "http.bodyAsTextDone")
            Json.parseToJsonElement(text).jsonObject.also { SubmissionTrace.mark(traceId, "http.parsed") }
        }.getOrNull()
        if (response.status.isSuccess()) MockReply(parsed, null, parsed == null) else MockReply(null, parsed?.s("error")?.ifBlank { null } ?: "服务端拒绝了这次请求（${response.status.value}）", response.status.value in setOf(408, 429) || response.status.value >= 500)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        MockReply(null, null, true)
    }

    /** 模拟考首页：可考的卷子、速刷题量、未交卷的考试、历次成绩。 */
    suspend fun mocks(): MockReply = mockCall("mocks", null)
    suspend fun startPaper(paperId: String): MockReply = mockCall("mocks", buildJsonObject { put("paperId", paperId) })
    /** [week] 为 null 时按押题权重抽。 */
    suspend fun startDrill(week: Int?): MockReply = mockCall("mocks", buildJsonObject { put("drill", buildJsonObject { week?.let { put("week", it) } }) })
    /** 进行中返回卷面（不含答案与细则）；交卷后返回成绩。 */
    suspend fun mock(mockId: String): MockReply = mockCall("mocks/${mockId.encodeURLPathPart()}", null)
    /** [seq] must grow with every save of this exam; the Mac ignores a save numbered no higher than one it already stored. */
    suspend fun saveMock(mockId: String, answers: JsonObject, seq: Long): MockReply =
        mockCall("mocks/${mockId.encodeURLPathPart()}/answers", buildJsonObject { put("answers", answers); put("seq", seq) })
    suspend fun submitMock(mockId: String, answers: JsonObject, auto: Boolean): MockReply =
        mockCall("mocks/${mockId.encodeURLPathPart()}/submit", buildJsonObject { put("answers", answers); put("auto", auto) })

    /** 选择题主线：各周的题数与覆盖面板。 */
    suspend fun mainline(): MockReply = mockCall("mainline", null)
    /** 一周的题，按铺垫 → 细节 → 桥接排好；不含答案和讲解。 */
    suspend fun mainlineWeek(week: Int): MockReply = mockCall("mainline/$week?layout=shuffled-v1", null)
    suspend fun explainSelection(week: Int, input: JsonObject): MockReply = try {
        val response = http.post("$base/v1/courses/${course.encodeURLPathPart()}/mainline/$week/explain") {
            timeout { requestTimeoutMillis = 95_000 }
            setBody(TextContent(input.toString(), ContentType.Application.Json))
        }
        val parsed = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (response.status.isSuccess()) MockReply(parsed, null)
        else MockReply(null, parsed.s("error").ifBlank { "暂时无法确认，请稍后重试。" })
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { MockReply(null, "连不上 Mac，阅读位置已保存。可返回做题或手动重试。") }
    suspend fun cancelSelection(week: Int, id: String) {
        mockCall("mainline/$week/explain/cancel", buildJsonObject { put("requestId", id) })
    }
    /**
     * 一道题答一次：返回对错、全部讲解、复习安排和新的覆盖数。同一个 [submissionId] 重发只算一次。
     * [choice] 是屏幕上的第几个选项，[layout] 原样带回这道题读下来时的 `layout`，Mac 据此认出是哪一种打乱顺序。
     */
    suspend fun answerMainline(week: Int, itemId: String, choice: Int, guessed: Boolean, submissionId: String, layout: Int?): MockReply =
        mockCall("mainline/$week/answer", buildJsonObject {
            put("itemId", itemId); put("choice", choice); put("guessed", guessed); put("submissionId", submissionId); layout?.let { put("layout", it) }
        }, submissionId)

    /** 提交过的作答的完整记录（冻结题、评分标准、批改与复核）。 */
    suspend fun attemptRecord(attemptId: String): JsonObject? = getObject("attempts/${attemptId.encodeURLPathPart()}")

    suspend fun sources(): List<SourceDoc>? = getObject("sources")?.let { root ->
        (root["docs"] as? JsonArray)?.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            SourceDoc(o.s("docId"), o.s("title"), o.i("firstPdfPage"), o.i("lastPdfPage"), (o["firstBookPage"] as? JsonPrimitive)?.intOrNull, (o["lastBookPage"] as? JsonPrimitive)?.intOrNull)
        }
    }

    suspend fun page(docId: String, pdfPage: Int): SourcePage? = getObject("sources/${docId.encodeURLPathPart()}/pages/$pdfPage")?.let { o ->
        SourcePage(o.s("docId"), o.i("pdfPage"), (o["bookPage"] as? JsonPrimitive)?.intOrNull, o.s("text"))
    }

    /** 每个知识点已复核的构答题数；没有这个接口时返回 null（不假装知道）。 */
    suspend fun constructedSupply(): Map<String, Int>? = getObject("pool")?.let { root ->
        (root["constructed"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.intOrNull ?: 0 }
    }

    /** 期末押题权重（服务端私有配置）；没有配置或接口不通时返回 null，面板就只按掌握度排。 */
    suspend fun examFocus(): ExamFocus? = ExamFocus.parse(getObject("exam-focus"))
    suspend fun essays(): EssayBankView? = getObject("essays")?.let { root ->
        EssayBankView((root["prompts"] as? JsonArray).orEmpty().mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            EssayPrompt(o.s("id"), o.i("week"), (o["marks"] as? JsonPrimitive)?.doubleOrNull ?: 20.0, o.i("wordTarget"), o.s("stem"), o.s("pack"),
                (o["fromMockPaper"] as? JsonPrimitive)?.booleanOrNull == true)
        }, (root["weeks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }, root.i("unmatched"))
    }

    /** Separate private reader surface; it shares auth/timeouts but has no connection to the AI client. */
    val reader = ReaderHttp(
        courseId = course,
        request = { path, body ->
            try {
                val url = readerUrl(path)
                val r = if (body == null) http.get(url) else http.post(url) { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
                ReaderReply(r.status.value, runCatching { Json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { ReaderReply(null, null) }
        },
        bytes = { path ->
            try { http.get(readerUrl(path)).let { if (it.status.isSuccess()) it.bodyAsBytes() else null } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        },
        imageTransfer = { path, stage ->
            try {
                stage("http_call_started")
                val url = readerUrl(path)
                val probe = Url(url).parameters["readerProbe"]
                // Scoped execution separates headers from body completion and avoids retaining a saved response copy.
                http.prepareGet(url).execute { response ->
                    stage("http_headers_received")
                    if (probe != null) {
                        if (response.headers["X-Coach-Reader-Probe"] != probe) {
                            stage("http_probe_mismatch")
                            return@execute null
                        }
                        stage("http_probe_verified")
                    }
                    if (!response.status.isSuccess()) null else {
                        val bytes = response.bodyAsBytes()
                        stage("http_body_received")
                        val expected = response.headers["Content-Length"]?.toLongOrNull()
                        bytes.takeIf { expected == null || expected == it.size.toLong() }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { stage("http_cancelled"); throw e }
            catch (_: Exception) { stage("http_failed"); null }
        },
        text = { path ->
            try { http.get(readerUrl(path)).let { if (it.status.isSuccess()) it.bodyAsText() else null } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        },
    )
    private fun readerUrl(path: String) = "$base/v1/courses/${course.encodeURLPathPart()}/reader" + if (path.isBlank()) "" else "/$path"

    fun close() { http.close() }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.i(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
