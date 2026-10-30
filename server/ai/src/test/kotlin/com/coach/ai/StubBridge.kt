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
    private val responses = ArrayDeque<Triple<Int, String, Long>>()
    val requests = ArrayList<String>()
    val requestedHeaders = ArrayList<Map<String, List<String>>>()

    /** 客户端在流途中主动断开（取消）时置位：证明 `cancel()` 真的断开了 HTTP。 */
    val aborted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 已写出的帧数：取消后必须明显少于整条流，证明没有把剩下的正文发完。 */
    @Volatile var framesWritten = 0

    init {
        server.createContext("/v1/chat/completions") { exchange -> handle(exchange) }
        server.createContext("/health") { exchange ->
            respond(exchange, 200, """{"ok":true,"rate_limit":{"status":"allowed","utilization":0.1}}""")
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun enqueue(status: Int, body: String) {
        responses.addLast(Triple(status, body, 0L))
    }

    fun enqueueSse(vararg frames: String) {
        enqueue(200, frames.joinToString(""))
    }

    /** 逐帧下发、帧间停顿 [delayMs]；断流与取消测试靠它把「边到边发」变成可观测的。 */
    fun enqueueStream(delayMs: Long, vararg frames: String) {
        responses.addLast(Triple(200, frames.joinToString(""), delayMs))
    }

    private fun handle(exchange: HttpExchange) {
        val request = exchange.requestBody.readBytes().decodeToString()
        requests.add(request)
        requestedHeaders.add(exchange.requestHeaders.mapValues { it.value.toList() })
        val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(request)?.groupValues?.get(1) ?: ""
        val (status, body, delay) = responses.removeFirstOrNull() ?: Triple(500, """{"error":"no stub response"}""", 0L)
        // __MODEL__ 发回时替换成请求里的模型：默认让 served_by 与请求同家（异厂校验放行）
        val text = body.replace("__MODEL__", model)
        when {
            delay <= 0 -> respond(exchange, status, text, contentType = "text/event-stream")
            else -> stream(exchange, status, text, delay)
        }
    }

    /** 分块下发（chunked），每帧写一次并 flush；客户端断开时写会抛 IOException。 */
    private fun stream(exchange: HttpExchange, status: Int, body: String, delayMs: Long) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        val frames = body.split("\n\n").filter { it.isNotBlank() }.map { it + "\n\n" }
        try {
            exchange.sendResponseHeaders(status, 0)
            exchange.responseBody.use { out ->
                for (frame in frames) {
                    // 先等再写：模拟桥「出一段话要几百毫秒」，首字时间才有意义
                    Thread.sleep(delayMs)
                    out.write(frame.toByteArray())
                    out.flush()
                    framesWritten++
                }
            }
        } catch (error: java.io.IOException) {
            aborted.set(true)
        }
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
