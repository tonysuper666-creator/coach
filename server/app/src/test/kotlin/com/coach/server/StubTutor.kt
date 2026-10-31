package com.coach.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.ArrayDeque

/**
 * 应用层测试用的假桥副本：只替换 HTTP/SSE，Ktor 路由、ChatService、落库、过滤全是生产代码。
 * 真实 CLI 一律不调用（期中前保护，DESIGN §13.2）。
 */
class StubTutor : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val replies = ArrayDeque<Triple<String, Long, Int>>()
    private val executor = java.util.concurrent.Executors.newCachedThreadPool()
    @Volatile var framesWritten = 0
    @Volatile var aborted = false
    @Volatile var requests = 0
    val requestBodies = java.util.Collections.synchronizedList(ArrayList<String>())

    init {
        server.createContext("/v1/chat/completions") { exchange -> handle(exchange) }
        server.createContext("/health") { exchange ->
            val body = """{"ok":true,"rate_limit":{"status":"allowed","utilization":0.1}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.executor = executor
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** 逐帧下发，帧间停顿 [delayMs]；以 `data:` 开头的参数按原样当 SSE 帧（终态帧、`[DONE]` 用）。 */
    fun stream(delayMs: Long, vararg chunks: String) {
        replies.addLast(Triple(chunks.joinToString("") { if (it.startsWith("data:")) it else frame(it) }, delayMs, 200))
    }

    /** 整段非流式回包（兜底路径）。 */
    fun completion(text: String, model: String = "claude-sonnet-5-5") {
        replies.addLast(Triple(completionBody(text,model), -1L, 200))
    }

    fun httpError(status: Int) { replies.addLast(Triple("{}",-1L,status)) }

    private fun handle(exchange: HttpExchange) {
        requests++
        requestBodies.add(exchange.requestBody.readBytes().decodeToString())
        val (body, delay, status) = replies.pollFirst() ?: Triple("""{"error":"no stub reply"}""", -1L, 200)
        if (delay < 0) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            return
        }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        val frames = body.split("\n\n").filter { it.isNotBlank() }.map { it + "\n\n" }
        try {
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                for (frame in frames) {
                    Thread.sleep(delay)
                    out.write(frame.toByteArray())
                    out.flush()
                    framesWritten++
                }
            }
        } catch (error: Throwable) {
            // 客户端断开 = 取消；真实桥在这里杀掉 CLI 进程组（本测试只验证服务端确实断开了）
            aborted = true
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun frame(content: String): String = sse(
            kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive("claude-sonnet-5-5"))
                put(
                    "choices",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("index", kotlinx.serialization.json.JsonPrimitive(0))
                                put(
                                    "delta",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("content", kotlinx.serialization.json.JsonPrimitive(content))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        fun terminal(status: String, reason: String? = null): String = sse(
            kotlinx.serialization.json.buildJsonObject {
                put("choices", kotlinx.serialization.json.buildJsonArray { })
                put("model", kotlinx.serialization.json.JsonPrimitive("claude-sonnet-5-5"))
                put(
                    "x_coach",
                    kotlinx.serialization.json.buildJsonObject {
                        put("status", kotlinx.serialization.json.JsonPrimitive(status))
                        put(
                            "reason",
                            if (reason == null) kotlinx.serialization.json.JsonNull
                            else kotlinx.serialization.json.JsonPrimitive(reason),
                        )
                        put("finish_reason", kotlinx.serialization.json.JsonPrimitive("stop"))
                        put("served_by", kotlinx.serialization.json.JsonPrimitive("claude-sonnet-5-5"))
                        put("fallback", kotlinx.serialization.json.JsonPrimitive(false))
                        put("physical_calls", kotlinx.serialization.json.JsonPrimitive(1))
                        put("request_id", kotlinx.serialization.json.JsonPrimitive("rid-stub"))
                        put("queue_ms", kotlinx.serialization.json.JsonPrimitive(2))
                        put("ttft_ms", kotlinx.serialization.json.JsonPrimitive(900))
                        put("elapsed_ms", kotlinx.serialization.json.JsonPrimitive(1500))
                    },
                )
            },
        )

        const val DONE = "data: [DONE]\n\n"

        private fun sse(obj: kotlinx.serialization.json.JsonObject): String = "data: $obj\n\n"

        private fun completionBody(text: String, model: String): String =
            """{"model":"$model","choices":[{"index":0,"message":{"role":"assistant","content":""" +
                "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" +
                """}}],"x_coach":{"status":"complete","reason":null,"finish_reason":"stop",""" +
                """"served_by":"$model","fallback":false,"physical_calls":1,""" +
                """"request_id":"rid-stub","queue_ms":1,"ttft_ms":800,"elapsed_ms":2100}}"""
    }
}
