package com.coach.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 公式服务（`tools/mathsvc`，Node 常驻进程，stdin/stdout JSON 行协议）的服务端托管。
 * 手机没有 Node，公式 SVG 一律经 `GET /v1/math` 取；进程挂了下次请求自动重启。
 */
class MathProcess(private val script: File, private val node: String) : AutoCloseable {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private val ids = AtomicLong()

    @Synchronized
    fun render(tex: String, display: Boolean): JsonObject? {
        repeat(2) {
            try {
                val live = ensure()
                val id = ids.incrementAndGet().toString()
                val writer = live.outputStream.bufferedWriter()
                writer.write(buildJsonObject { put("id", id); put("tex", tex); put("display", display) }.toString() + "\n")
                writer.flush()
                while (true) {
                    val line = CompletableFuture.supplyAsync { reader!!.readLine() }.get(10, TimeUnit.SECONDS) ?: throw java.io.IOException("mathsvc closed")
                    val reply = Json.parseToJsonElement(line).jsonObject
                    if (reply["id"]?.jsonPrimitive?.content == id) return reply
                }
            } catch (error: Exception) {
                process?.destroyForcibly(); process = null; reader = null
            }
        }
        return null
    }

    private fun ensure(): Process {
        process?.takeIf { it.isAlive }?.let { return it }
        val started = ProcessBuilder(node, script.path).directory(script.parentFile.parentFile)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = started
        reader = BufferedReader(InputStreamReader(started.inputStream))
        return started
    }

    @Synchronized override fun close() { process?.destroy(); process = null }

    companion object {
        /** 找不到脚本或 Node 时返回 null：服务端照常工作，公式在客户端显示为源码 chip。 */
        fun locate(): MathProcess? {
            val home = System.getProperty("user.home")
            val script = listOfNotNull(System.getenv("COACH_MATHSVC"), System.getenv("COACH_REPO")?.let { "$it/tools/mathsvc/src/server.mjs" }, "$home/dev/coach/tools/mathsvc/src/server.mjs")
                .map(::File).firstOrNull { it.exists() && File(it.parentFile.parentFile, "node_modules").exists() } ?: return null
            val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { "$it/node" }
            val node = (listOfNotNull(System.getenv("COACH_NODE"), "/opt/homebrew/bin/node", "/usr/local/bin/node") + onPath)
                .map(::File).firstOrNull { it.canExecute() } ?: return null
            return MathProcess(script, node.path)
        }
    }
}

/** 与账本路由同一个 Application：设备鉴权的拦截器已覆盖这里。 */
fun Application.mathRoutes(math: MathProcess?) {
    routing {
        get("/v1/math") {
            val tex = call.request.queryParameters["tex"].orEmpty()
            val display = call.request.queryParameters["display"] == "true"
            if (tex.isBlank() || tex.length > 4_000) { call.respond(HttpStatusCode.BadRequest); return@get }
            if (math == null) { call.respondText("""{"error":"math_unavailable"}""", ContentType.Application.Json, HttpStatusCode.NotFound); return@get }
            val reply = math.render(tex, display)
            when {
                reply == null -> call.respondText("""{"error":"math_unavailable"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                reply["error"] != null -> call.respondText(reply.toString(), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
                else -> call.respondText(buildJsonObject { put("sha", reply.getValue("sha")); put("svg", reply.getValue("svg")) }.toString(), ContentType.Application.Json)
            }
        }
    }
}
