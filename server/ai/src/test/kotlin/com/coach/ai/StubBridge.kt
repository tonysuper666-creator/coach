package com.coach.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration

/**
 * 本地假桥：只替换 HTTP 传输与 SSE 帧，其余（解析、终态判定、fail-closed）全是生产代码。
 * live 用真桥，这里只测协议形状与「断流不写结果」。
 */
class StubBridge : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val responses = ArrayDeque<Pair<Int, String>>()
    val requests = ArrayList<String>()
    val requestedHeaders = ArrayList<Map<String, List<String>>>()

    init {
        server.createContext("/v1/chat/completions") { exchange -> handle(exchange) }
        server.createContext("/health") { exchange ->
            respond(exchange, 200, """{"ok":true,"rate_limit":{"status":"allowed","utilization":0.1}}""")
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun enqueue(status: Int, body: String) {
        responses.addLast(status to body)
    }

    fun enqueueSse(vararg frames: String) {
        enqueue(200, frames.joinToString(""))
    }

    private fun handle(exchange: HttpExchange) {
        val request = exchange.requestBody.readBytes().decodeToString()
        requests.add(request)
        requestedHeaders.add(exchange.requestHeaders.mapValues { it.value.toList() })
        val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(request)?.groupValues?.get(1) ?: ""
        val (status, body) = responses.removeFirstOrNull() ?: (500 to """{"error":"no stub response"}""")
        // __MODEL__ 发回时替换成请求里的模型：默认让 served_by 与请求同家（异厂校验放行）
        respond(exchange, status, body.replace("__MODEL__", model), contentType = "text/event-stream")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        fun frame(content: String): String =
            "data: " + """{"choices":[{"index":0,"delta":{"content":"$content"}}]}""" + "\n\n"

        fun terminal(status: String, reason: String? = null, served: String = "__MODEL__"): String =
            "data: " + """{"choices":[],"x_coach":{"status":"$status","reason":${reason?.let { "\"$it\"" } ?: "null"},""" +
                """"finish_reason":"stop","served_by":"$served","fallback":false,"physical_calls":1,""" +
                """"request_id":"rid-1","queue_ms":3,"ttft_ms":900,"elapsed_ms":1500}}""" + "\n\n"

        const val DONE = "data: [DONE]\n\n"

        fun completion(text: String, served: String = "__MODEL__"): String =
            """{"model":"$served","choices":[{"index":0,"message":{"role":"assistant","content":${quote(text)}}}],""" +
                """"x_coach":{"status":"complete","reason":null,"finish_reason":"stop","served_by":"$served",""" +
                """"fallback":false,"physical_calls":1,"request_id":"rid-2","queue_ms":1,"ttft_ms":800,"elapsed_ms":2100}}"""

        /** 桥没遵守 `X-Coach-Fallback: off` 时的回包（fallback=true）。 */
        fun fallbackCompletion(text: String): String =
            """{"model":"__MODEL__","choices":[{"index":0,"message":{"role":"assistant","content":${quote(text)}}}],""" +
                """"x_coach":{"status":"complete","reason":null,"finish_reason":"stop","served_by":"__MODEL__",""" +
                """"fallback":true,"physical_calls":2,"request_id":"rid-3","queue_ms":0,"ttft_ms":700,"elapsed_ms":1900}}"""

        /** 非 200 但错误体里带 `x_coach`（CLI 跑了一半超时）：物理调用数不能丢。 */
        fun errorWithTerminal(status: Int): String =
            """{"error":{"type":"bridge_error","code":$status,"message":"cli timeout"},"x_coach":{""" +
                """"status":"failed","reason":"timeout","finish_reason":"error","served_by":"claude-sonnet-5-5",""" +
                """"fallback":false,"physical_calls":1,"request_id":"rid-4","queue_ms":0,"ttft_ms":-1,"elapsed_ms":90000}}"""

        private fun quote(text: String): String =
            "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

fun stubConfig(bridge: StubBridge): BridgeConfig =
    BridgeConfig(baseUrl = bridge.baseUrl, connectTimeout = Duration.ofSeconds(2), requestTimeout = Duration.ofSeconds(20))
