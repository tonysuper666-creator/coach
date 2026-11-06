package com.coach.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * 期末押题权重：只读，来自本机私有配置 `<dir>/<course>/exam-focus.json`，或 `<dir>` 下某个子目录里 "course" 等于该课程 id 的那份（不进仓库）。
 * 文件格式 `{"weeks":[{"week":4,"weight":3,"topic":"…"}]}`；只回传周号、权重和短主题，别的字段一律不出去。
 * 鉴权由 [ledgerRoutes] 装在整个应用上的拦截器负责。
 */
fun examFocusDir(): Path = System.getenv("COACH_EXAM_FOCUS_DIR")?.let(Path::of)
    ?: Path.of(System.getProperty("user.home"), "Coach", "materials", "generated")

fun examFocus(dir: Path, course: String): JsonObject? {
    if (!course.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
    fun read(file: Path) = if (!Files.isRegularFile(file)) null
        else runCatching { Json.parseToJsonElement(Files.readString(file)) as? JsonObject }.getOrNull()
    // 目录可以按课程编号起名（如 `ABC101/`），文件里的 "course" 写账本课程 id 即可对上。
    val root = read(dir.resolve(course).resolve("exam-focus.json"))
        ?: runCatching { Files.list(dir).use { s -> s.toList() } }.getOrDefault(emptyList()).sorted().asSequence()
            .mapNotNull { read(it.resolve("exam-focus.json")) }
            .firstOrNull { (it["course"] as? JsonPrimitive)?.contentOrNull == course }
        ?: return null
    val weeks = (root["weeks"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val w = raw as? JsonObject ?: return@mapNotNull null
        val week = (w["week"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..60 } ?: return@mapNotNull null
        val weight = (w["weight"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 && it.isFinite() } ?: return@mapNotNull null
        buildJsonObject {
            put("week", week); put("weight", weight)
            put("topic", (w["topic"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(40))
        }
    }.distinctBy { it["week"] }
    if (weeks.isEmpty()) return null
    return buildJsonObject { put("course", course); put("weeks", JsonArray(weeks)) }
}

fun Application.examFocusRoutes(dir: Path = examFocusDir()) {
    routing {
        get("/v1/courses/{course}/exam-focus") {
            val focus = examFocus(dir, call.parameters["course"].orEmpty())
            if (focus == null) call.respondText("Not Found", status = HttpStatusCode.NotFound)
            else call.respondText(focus.toString(), ContentType.Application.Json)
        }
    }
}
