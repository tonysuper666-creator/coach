package com.coach.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RenderedMath(val sha: String, val svg: String)

/** 服务端公式服务的客户端：失败（TeX 写错、服务不可用、断线）一律返回 null，界面显示源码 chip。 */
class MathHttp(baseUrl: String, deviceId: String, token: String) {
    private val url = baseUrl.trimEnd('/') + "/v1/math"
    init { validateCoachBaseUrl(baseUrl) }
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 3_000 }
        defaultRequest { header("Authorization", "Bearer $token"); header("X-Coach-Device", deviceId) }
    }

    suspend fun render(tex: String, display: Boolean): RenderedMath? = try {
        val response = http.get(url) { parameter("tex", tex); parameter("display", display) }
        if (!response.status.isSuccess()) null
        else Json.parseToJsonElement(response.bodyAsText()).jsonObject.let { RenderedMath(it.getValue("sha").jsonPrimitive.content, it.getValue("svg").jsonPrimitive.content) }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    fun close() { http.close() }
}
