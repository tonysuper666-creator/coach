package com.coach.server

import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/** Installed by the process that serves both listeners. Absent in tests that do not count. */
internal val reliabilityCounts = AttributeKey<ReliabilityCounts>("reliability-counts")

/**
 * Per-version counters only. The file stores version strings, closed reason codes,
 * hashed device-days and integers. Request bodies, filenames and free-text reasons never land here.
 */
internal class ReliabilityCounts(private val directory: Path) {
    private val lock = ReentrantLock()
    private val crashes = linkedMapOf<String, Long>()
    private val active = linkedMapOf<String, MutableSet<String>>()
    private val explain = linkedMapOf<String, MutableMap<String, Long>>()
    private val ask = linkedMapOf<String, MutableMap<String, Long>>()
    private val deviceVersion = hashMapOf<String, String>()

    init { load() }

    /** Remember the caller's version and count one active device-day. The device id is hashed before it is stored. */
    fun touch(versionHeader: String?, device: String, now: Instant = Instant.now()) {
        if (device.isBlank()) return
        val version = sanitizeVersion(versionHeader)
        val day = "${deviceKey(device)}|${now.atZone(ZoneOffset.UTC).toLocalDate()}"
        lock.lock()
        try {
            deviceVersion[device] = version
            active.getOrPut(version) { linkedSetOf() }.add(day)
            saveLocked()
        } finally { lock.unlock() }
    }

    fun versionOf(device: String): String {
        lock.lock()
        try { return if (device.isBlank()) "unknown" else deviceVersion[device] ?: "unknown" }
        finally { lock.unlock() }
    }

    /** [code] is a closed reason. Anything else is counted as other and the original text is dropped. Null skips the attempt. */
    fun outcome(kind: String?, versionHeader: String?, code: String?) {
        if (code == null || (kind != "explain" && kind != "ask")) return
        val reason = when (code) {
            "ok", "none" -> "ok"
            "timeout", "quota", "refusal", "source", "other" -> code
            else -> "other"
        }
        val version = sanitizeVersion(versionHeader)
        lock.lock()
        try {
            val bucket = (if (kind == "ask") ask else explain).getOrPut(version) { linkedMapOf() }
            bucket[reason] = (bucket[reason] ?: 0L) + 1L
            saveLocked()
        } finally { lock.unlock() }
    }

    /** Count consented incident blocks. Only allowlisted kind and version lines are read. */
    fun incident(body: String) {
        val hits = parseIncidents(body)
        if (hits.isEmpty()) return
        lock.lock()
        try {
            for (version in hits) crashes[version] = (crashes[version] ?: 0L) + 1L
            saveLocked()
        } finally { lock.unlock() }
    }

    fun render(): String {
        lock.lock()
        try { return renderLocked() }
        finally { lock.unlock() }
    }

    private fun renderLocked(): String = buildString {
        append("版本\t崩溃/活跃设备天\t讲解失败率\t提问失败率\t前三失败原因\n")
        val versions = (crashes.keys + active.keys + explain.keys + ask.keys).toMutableSet()
        val ordered = versions.filter { it != "unknown" }.sorted() + if ("unknown" in versions) listOf("unknown") else emptyList()
        for (version in ordered) {
            val explainBucket = explain[version].orEmpty()
            val askBucket = ask[version].orEmpty()
            val reasons = REASONS.mapNotNull { reason ->
                val count = (explainBucket[reason] ?: 0L) + (askBucket[reason] ?: 0L)
                if (count > 0L) reason to count else null
            }.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { REASONS.indexOf(it.first) })
            val top = if (reasons.isEmpty()) "-" else reasons.take(3).joinToString("，") { "${REASON_NAMES.getValue(it.first)} ${it.second}" }
            append(version).append('\t')
            append(crashes[version] ?: 0L).append('/').append(active[version]?.size ?: 0).append('\t')
            append(rate(explainBucket)).append('\t')
            append(rate(askBucket)).append('\t')
            append(top).append('\n')
        }
    }

    private fun saveLocked() {
        try {
            val dir = privateFiles.directory(directory)
            val target = dir.resolve(FILE)
            val tmp = privateFiles.tempFile(dir, "counts-", ".tmp")
            Files.writeString(tmp, encodeLocked(), Charsets.UTF_8)
            privateFiles.secureFile(tmp)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            privateFiles.secureFile(target)
        } catch (error: Exception) {
            println("COACH reliability save failed=${error.javaClass.simpleName}")
        }
    }

    private fun encodeLocked(): String {
        fun reasons(source: Map<String, MutableMap<String, Long>>) = buildJsonObject {
            source.toSortedMap().forEach { (version, bucket) ->
                val row = buildJsonObject {
                    if ((bucket["ok"] ?: 0L) > 0L) put("ok", JsonPrimitive(bucket.getValue("ok")))
                    for (reason in REASONS) if ((bucket[reason] ?: 0L) > 0L) put(reason, JsonPrimitive(bucket.getValue(reason)))
                }
                if (row.isNotEmpty()) put(version, row)
            }
        }
        return buildJsonObject {
            val crashRows = buildJsonObject {
                crashes.filterValues { it > 0L }.toSortedMap().forEach { (version, count) -> put(version, JsonPrimitive(count)) }
            }
            if (crashRows.isNotEmpty()) put("crashes", crashRows)
            val activeRows = buildJsonObject {
                active.filterValues { it.isNotEmpty() }.toSortedMap().forEach { (version, days) ->
                    put(version, buildJsonArray { days.sorted().forEach { add(JsonPrimitive(it)) } })
                }
            }
            if (activeRows.isNotEmpty()) put("active", activeRows)
            val explainRows = reasons(explain)
            if (explainRows.isNotEmpty()) put("explain", explainRows)
            val askRows = reasons(ask)
            if (askRows.isNotEmpty()) put("ask", askRows)
        }.toString()
    }

    private fun load() {
        val file = directory.resolve(FILE)
        if (!Files.isRegularFile(file)) return
        val root = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull() ?: return
        fun long(value: JsonPrimitive?): Long? = value?.longOrNull?.takeIf { it >= 0L }
        (root["crashes"] as? JsonObject)?.forEach { (version, value) ->
            val count = long((value as? JsonPrimitive)) ?: return@forEach
            if (count > 0L) crashes[sanitizeVersion(version)] = (crashes[sanitizeVersion(version)] ?: 0L) + count
        }
        (root["active"] as? JsonObject)?.forEach { (version, value) ->
            val days = (value as? JsonArray)?.mapNotNull { item ->
                (item as? JsonPrimitive)?.content?.takeIf(ACTIVE_DAY::matches)
            }.orEmpty()
            if (days.isNotEmpty()) active.getOrPut(sanitizeVersion(version)) { linkedSetOf() }.addAll(days)
        }
        fun loadReasons(key: String, target: MutableMap<String, MutableMap<String, Long>>) {
            (root[key] as? JsonObject)?.forEach { (version, value) ->
                val row = value as? JsonObject ?: return@forEach
                val bucket = target.getOrPut(sanitizeVersion(version)) { linkedMapOf() }
                val ok = long(row["ok"] as? JsonPrimitive) ?: 0L
                if (ok > 0L) bucket["ok"] = (bucket["ok"] ?: 0L) + ok
                for (reason in REASONS) {
                    val count = long(row[reason] as? JsonPrimitive) ?: 0L
                    if (count > 0L) bucket[reason] = (bucket[reason] ?: 0L) + count
                }
            }
        }
        loadReasons("explain", explain)
        loadReasons("ask", ask)
    }

    private companion object {
        const val FILE = "counts.json"
        val ACTIVE_DAY = Regex("[0-9a-f]{12}\\|[0-9]{4}-[0-9]{2}-[0-9]{2}")
        val REASONS = listOf("timeout", "quota", "refusal", "source", "other")
        val REASON_NAMES = mapOf(
            "timeout" to "超时",
            "quota" to "额度",
            "refusal" to "拒答",
            "source" to "出处",
            "other" to "其他",
        )
        val INCIDENT_KINDS = setOf("crash", "freeze", "import_failure", "sync_failure", "unclean_exit")
    }
}

internal fun sanitizeVersion(raw: String?): String {
    val text = raw?.trim().orEmpty()
    return if (ReliabilityVersion.matches(text)) text else "unknown"
}

private val ReliabilityVersion = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}")

internal fun deviceKey(device: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(device.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(12)
}

internal fun rate(bucket: Map<String, Long>): String {
    val ok = bucket["ok"] ?: 0L
    val fail = listOf("timeout", "quota", "refusal", "source", "other").sumOf { bucket[it] ?: 0L }
    val total = ok + fail
    if (total == 0L) return "n/a"
    val tenths = (fail * 1000L + total / 2L) / total
    return String.format(Locale.US, "%.1f%% (%d/%d)", tenths / 10.0, fail, total)
}

/** Fixed reader replies only. A normal explanation stays a success. Null means the attempt is outside the rate. */
internal fun classifyReaderText(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "other"
    if (trimmed == "追问已取消。" || trimmed.startsWith("追问正在处理")) return null
    if (trimmed.startsWith("讲解正文迟迟未开始") || trimmed.startsWith("讲解正文中途停止更新") || trimmed.startsWith("讲解已达到本次总时长上限")) return "timeout"
    if (trimmed == "依据不足，无法确认。") return "refusal"
    if (trimmed.startsWith("回答缺少可核对") || trimmed.startsWith("缺少可定位") || trimmed.startsWith("原文已变化") || trimmed.contains("来源或可见范围尚未核实")) return "source"
    if (trimmed.startsWith("追问未完成") || trimmed.startsWith("讲解服务暂不可用") || trimmed.startsWith("依据读取或追问暂不可用") || trimmed.contains("暂时连不上")) return "other"
    return "ok"
}

internal fun reliabilityCodeForFriend(code: String): String =
    if (code == FREE_GRANT_EXHAUSTED) "quota" else "other"

/** Format and size errors stay out of the rate. A checked source failure still counts. */
internal fun reliabilityCodeForMock(status: HttpStatusCode, message: String?): String? {
    if (status == HttpStatusCode.BadRequest || status == HttpStatusCode.PayloadTooLarge) return null
    val text = message.orEmpty()
    val source = "尚未核实" in text || "无法讲解" in text || "无法确认" in text
    if (status.value in 400..499) return if (source) "source" else null
    return if (source) "source" else "other"
}

/** Closed codes only. The raw bridge reason is never returned. */
internal fun classifyChat(status: String, reason: String?): String? {
    if (status == "cancelled" || reason == "cancelled") return null
    if (status == "complete") return "ok"
    val normalized = reason.orEmpty().lowercase()
    if ("timeout" in normalized) return "timeout"
    if (normalized == "rate_limited" || "quota" in normalized || normalized.startsWith("http_429")) return "quota"
    if (normalized == "empty_visible_body") return "refusal"
    if ("citation" in normalized || "source" in normalized) return "source"
    return "other"
}

private fun parseIncidents(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    val kinds = setOf("crash", "freeze", "import_failure", "sync_failure", "unclean_exit")
    return body.split(Regex("\n\\s*\n")).mapNotNull { block ->
        var version: String? = null
        var kind: String? = null
        for (line in block.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("version:")) version = sanitizeVersion(trimmed.removePrefix("version:"))
            if (trimmed.startsWith("kind:")) {
                val value = trimmed.removePrefix("kind:").trim()
                if (value in kinds) kind = value
            }
        }
        if (kind == null) null else version ?: "unknown"
    }
}
