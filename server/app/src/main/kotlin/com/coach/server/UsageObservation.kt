package com.coach.server

import com.coach.client.UsageVocabulary
import io.ktor.server.application.*
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.*
import java.time.format.DateTimeFormatter

internal val observedDevice = AttributeKey<String>("observed-device")
private val requestStart = AttributeKey<Long>("usage-request-start")
private val responseReady = AttributeKey<Long>("usage-response-ready")
private val usageLabels = mapOf(
    "open" to "打开应用", "screen" to "切屏", "tap" to "点按", "error" to "错误", "disconnect" to "断线",
    "reconnect" to "连接恢复", "first_word" to "首字", "app" to "应用", "today" to "今天页", "study" to "学习页",
    "review" to "复习页", "compact" to "窄屏", "wide" to "宽屏", "connection" to "连接", "open_kp" to "打开知识点",
    "next" to "讲解或下一步", "test" to "出题", "ask" to "追问", "answer" to "提交答案", "cancel" to "停止回答",
    "settings" to "学习设置", "explain" to "换个方式讲解", "quick_ask" to "随手问", "select_option" to "选择选项",
    "scope" to "学习范围", "offline_pack" to "离线练习包", "journal" to "学习日志", "network" to "网络", "server" to "服务端", "unknown" to "其他操作")
private fun JsonObject.usageLabel(key: String): String = getValue(key).jsonPrimitive.content.let { usageLabels[it] ?: "其他操作" }

/** One owner for both listeners. Metadata only, separate from learning facts and backups. */
class UsageObservation(private val logs: Path, private val state: Path) : AutoCloseable {
    private val access = ServiceLog(logs, "access") { LocalDate.now(ZoneOffset.UTC) }
    private val connection = run {
        val directory = state.resolve("diagnostics")
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("usage.sqlite")}").also { db ->
            db.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("CREATE TABLE IF NOT EXISTS usage(device TEXT NOT NULL,id TEXT NOT NULL,received INTEGER NOT NULL,event TEXT NOT NULL,PRIMARY KEY(device,id))")
            }
        }
    }

    @Synchronized fun accept(device: String, events: List<JsonObject>) {
        require(events.size in 1..50 && events.all(UsageVocabulary::valid))
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT OR IGNORE INTO usage VALUES(?,?,?,?)").use { s ->
                events.forEach { event ->
                    s.setString(1, device); s.setString(2, event.getValue("id").jsonPrimitive.content)
                    s.setLong(3, System.currentTimeMillis()); s.setString(4, event.toString()); s.addBatch()
                }
                s.executeBatch()
            }
            connection.commit()
        } catch (e: Exception) { connection.rollback(); throw e } finally { connection.autoCommit = true }
    }

    @Synchronized fun access(device: String?, route: String, method: String, status: Int, elapsed: Long, ready: Long?, firstWord: Long? = null) {
        val row = buildJsonObject {
            put("at", Instant.now().toString()); put("device", device ?: "unverified")
            put("route", route); put("method", method.takeIf { it in setOf("GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD","WS") } ?: "OTHER"); put("status", status); put("durationMs", elapsed)
            put("responseReadyMs", ready?.let(::JsonPrimitive) ?: JsonNull)
            put("firstWordMs", firstWord?.let(::JsonPrimitive) ?: JsonNull)
        }
        runCatching { access.write((row.toString()+"\n").toByteArray()) }
    }

    /** Uses receive day for offline uploads and also shows the original device timestamp. */
    @Synchronized fun summarize(day: LocalDate = LocalDate.now(ZoneOffset.UTC)): Path {
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rows = mutableListOf<Pair<String, JsonObject>>()
        connection.prepareStatement("SELECT device,event FROM usage WHERE received>=? AND received<? ORDER BY received,rowid").use { s ->
            s.setLong(1,start); s.setLong(2,end)
            s.executeQuery().use { rs -> while (rs.next()) rows += rs.getString(1) to Json.parseToJsonElement(rs.getString(2)).jsonObject }
        }
        val accessFile = logs.resolve("access-$day.log")
        val requests = if (Files.exists(accessFile)) Files.readAllLines(accessFile).mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } else emptyList()
        val text = buildString {
            append("# 今天卡在哪 · $day (UTC)\n\n生成：${Instant.now()}。按上报日汇总，操作时间来自设备；离线补发可能跨日。\n\n")
            append("请求 ${requests.count { it["method"]?.jsonPrimitive?.content != "WS" }} 次；客户端事件 ${rows.size} 条。无客户端事件时无法判断是否打开应用。\n\n")
            val devices = (rows.map { it.first } + requests.map { it.getValue("device").jsonPrimitive.content }).distinct()
            for (device in devices) {
                val events = rows.filter { it.first == device }.map { it.second }.sortedBy { it.getValue("at").jsonPrimitive.long }
                val http = requests.filter { it.getValue("device").jsonPrimitive.content == device }
                val errors = http.count { it.getValue("status").jsonPrimitive.int >= 400 }
                val slow = http.count { it.getValue("durationMs").jsonPrimitive.long > 5_000 && it["method"]?.jsonPrimitive?.content != "WS" }
                val words = events.filter { it.getValue("kind").jsonPrimitive.content == "first_word" }.map { it.getValue("ms").jsonPrimitive.long }.sorted()
                append("## 设备 $device\n\nHTTP 错误 $errors 次；超过 5 秒请求 $slow 次；断线 ${events.count { it.getValue("kind").jsonPrimitive.content == "disconnect" }} 次；客户端错误 ${events.count { it.getValue("kind").jsonPrimitive.content == "error" }} 次。\n")
                append(if (words.isEmpty()) "首字：无客户端样本。\n" else "首字 p95：${words[(kotlin.math.ceil(words.size*.95).toInt()-1).coerceAtLeast(0)]} ms（${words.size} 次）。\n")
                val last = events.lastOrNull()
                if (last != null) append("最后操作：${last.usageLabel("kind")} / ${last.usageLabel("target")}。\n")
                append("\n操作路径（设备时间）：\n\n")
                events.forEach { e -> append("- ${Instant.ofEpochMilli(e.getValue("at").jsonPrimitive.long)} ${e.usageLabel("kind")} / ${e.usageLabel("target")}${e.getValue("ms").jsonPrimitive.long.takeIf { it>=0 }?.let { " (${it}ms)" }.orEmpty()}\n") }
                http.filter { it.getValue("status").jsonPrimitive.int >= 400 }.forEach { r -> append("- ${r.getValue("at").jsonPrimitive.content} HTTP ${r.getValue("status")} ${r.getValue("route").jsonPrimitive.content}\n") }
                append("\n")
            }
        }
        Files.createDirectories(state)
        val output = state.resolve("usage-${day.format(DateTimeFormatter.BASIC_ISO_DATE)}.md")
        val temp = Files.createTempFile(state,"usage-", ".tmp")
        try {
            Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temp,text)
            Files.move(temp,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
        connection.prepareStatement("DELETE FROM usage WHERE received<?").use { it.setLong(1,start-14*86_400_000L); it.executeUpdate() }
        return output
    }
    @Synchronized override fun close() { access.close(); connection.close() }
}

/** Log only route templates: arbitrary paths/queries can contain private text. */
internal fun usageRoute(path: String): String {
    if (path in setOf("/v1/pair", "/v1/pairing-code", "/v1/devices", "/v1/prewarm", "/v1/usage", "/v1/hello", "/v1/commands", "/v1/ws", "/v1/math", "/offline-pack")) return path
    if (Regex("/v1/devices/[^/]+").matches(path)) return "/v1/devices/{device}"
    val parts = path.split('/')
    if (parts.size >= 5 && parts[1] == "v1" && parts[2] == "courses") {
        val tail = parts.drop(4)
        if (tail.size == 1 && tail[0] in setOf("events", "snapshot", "today", "constructed")) return "/v1/courses/{course}/${tail[0]}"
        if (tail.size == 2 && tail[0] in setOf("attempts", "quick", "journal")) return "/v1/courses/{course}/${tail[0]}/{id}"
        if (tail.size == 3 && tail[0] == "journal" && tail[2] == "summary") return "/v1/courses/{course}/journal/{day}/summary"
    }
    return "/unmatched"
}

fun Application.observeUsage(observation: UsageObservation) {
    install(createApplicationPlugin("UsageAccess") {
        onCall { call -> call.attributes.put(requestStart, System.nanoTime()) }
        onCallRespond { call, _ -> call.attributes.getOrNull(requestStart)?.let { call.attributes.put(responseReady, (System.nanoTime()-it)/1_000_000) } }
        on(ResponseSent) { call ->
            val elapsed = call.attributes.getOrNull(requestStart)?.let { (System.nanoTime()-it)/1_000_000 } ?: 0
            observation.access(call.attributes.getOrNull(observedDevice),usageRoute(call.request.path()),
                call.request.httpMethod.value,call.response.status()?.value ?: 500,elapsed,call.attributes.getOrNull(responseReady))
        }
    })
}

/** Per-socket command → first visible outgoing frame. Progress/start frames are excluded. */
internal class WsFirstWords(private val device: String, private val observation: UsageObservation?, private val nano: () -> Long = System::nanoTime) {
    private val pending = LinkedHashMap<String, Long>()
    private val messages = LinkedHashMap<String, String>()
    @Synchronized fun received(frame: JsonObject) {
        if (frame["t"]?.jsonPrimitive?.content in setOf("msg.ask", "tutor.next", "tutor.test", "tutor.explain")) {
            val thread = frame["threadId"]?.jsonPrimitive?.content ?: return
            pending[thread] = nano()
            if (pending.size > 128) pending.remove(pending.keys.first())
        }
    }
    @Synchronized fun sent(frame: JsonObject) {
        val type = frame["t"]?.jsonPrimitive?.content
        val id = frame["msg"]?.jsonPrimitive?.content ?: return
        if (type == "msg.start") {
            messages[id] = frame["threadId"]?.jsonPrimitive?.content ?: return
            if (messages.size > 128) messages.remove(messages.keys.first())
        }
        if ((type in setOf("msg.delta", "msg.snapshot") && !frame["text"]?.jsonPrimitive?.content.isNullOrEmpty()) ||
            (type == "msg.math" && !frame["tex"]?.jsonPrimitive?.content.isNullOrEmpty())) {
            val thread = messages.remove(id) ?: frame["threadId"]?.jsonPrimitive?.content ?: return
            val start = pending.remove(thread) ?: return
            val ms = (nano()-start)/1_000_000
            observation?.access(device,"/v1/ws","WS",200,ms,null,ms)
        }
        if (type == "msg.end") messages.remove(id)?.let { pending.remove(it) }
    }
}
