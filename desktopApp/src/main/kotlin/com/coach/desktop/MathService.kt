package com.coach.desktop

import com.coach.ui.markdown.MathAsset
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 公式服务（tools/mathsvc，Node 常驻进程，stdin/stdout JSON 行协议）的桌面客户端。
 * 找不到 Node 或脚本时 [locate] 返回 null，界面把公式显示成源码 chip，不影响其余功能。
 */
class MathService private constructor(private val process: Process) {
    private val writer = process.outputStream.bufferedWriter()
    private val reader = BufferedReader(InputStreamReader(process.inputStream))
    private val ids = AtomicLong()

    @Synchronized
    fun render(tex: String, display: Boolean): MathAsset? = runCatching {
        val id = ids.incrementAndGet().toString()
        writer.write(buildJsonObject { put("id", id); put("tex", tex); put("display", display) }.toString() + "\n")
        writer.flush()
        while (true) {
            val line = reader.readLine() ?: return@runCatching null
            val reply = Json.parseToJsonElement(line).jsonObject
            if (reply["id"]?.jsonPrimitive?.content != id) continue
            if (reply["error"] != null) return@runCatching null
            return@runCatching MathAsset(tex, display, reply.getValue("sha").jsonPrimitive.content, reply.getValue("svg").jsonPrimitive.content)
        }
        @Suppress("UNREACHABLE_CODE") null
    }.getOrNull()

    companion object {
        fun locate(): MathService? {
            val home = System.getProperty("user.home")
            val script = File(System.getenv("COACH_MATHSVC") ?: File(home, "dev/coach/tools/mathsvc/src/server.mjs").path)
            if (!script.exists()) return null
            val node = listOfNotNull(System.getenv("COACH_NODE"), "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
                .map(::File).firstOrNull { it.canExecute() } ?: return null
            return runCatching {
                val process = ProcessBuilder(node.path, script.path).directory(script.parentFile.parentFile)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
                MathService(process)
            }.getOrNull()
        }
    }
}
