package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 课程原文的一个文档（教材一章）。页号是 PDF 页；[bookPageOffset] 把它换成书页（书页 = PDF 页 − 偏移）。 */
data class SourceDoc(val docId: String, val title: String, val firstPdfPage: Int, val lastPdfPage: Int, val bookPageOffset: Int?)
data class SourcePage(val docId: String, val pdfPage: Int, val bookPage: Int?, val text: String)

/**
 * 只读的课程查询：记录页要的冻结题与评分标准、资料页要的原文。全部要联网问 Mac；
 * 返回 null 的意思就是「这次拿不到」（离线、或 Mac 上的服务还没更新到有这个接口），界面照实说，不拿占位内容顶。
 */
class CourseHttp(baseUrl: String, deviceId: String, token: String, private val course: String) {
    private val base = baseUrl.trimEnd('/')
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

    /** 提交过的作答的完整记录（冻结题、评分标准、批改与复核）。 */
    suspend fun attemptRecord(attemptId: String): JsonObject? = getObject("attempts/${attemptId.encodeURLPathPart()}")

    suspend fun sources(): List<SourceDoc>? = getObject("sources")?.let { root ->
        (root["docs"] as? JsonArray)?.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            SourceDoc(o.s("docId"), o.s("title"), o.i("firstPdfPage"), o.i("lastPdfPage"), (o["bookPageOffset"] as? JsonPrimitive)?.intOrNull)
        }
    }

    suspend fun page(docId: String, pdfPage: Int): SourcePage? = getObject("sources/${docId.encodeURLPathPart()}/pages/$pdfPage")?.let { o ->
        SourcePage(o.s("docId"), o.i("pdfPage"), (o["bookPage"] as? JsonPrimitive)?.intOrNull, o.s("text"))
    }

    /** 每个知识点已复核的构答题数；没有这个接口时返回 null（不假装知道）。 */
    suspend fun constructedSupply(): Map<String, Int>? = getObject("pool")?.let { root ->
        (root["constructed"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.intOrNull ?: 0 }
    }

    fun close() { http.close() }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.i(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
