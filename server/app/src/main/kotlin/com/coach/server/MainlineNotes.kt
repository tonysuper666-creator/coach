package com.coach.server

import com.coach.domain.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Notes are optional: a bad authoring file must never take a valid question pack offline. */
internal fun loadMainlineNotes(file: Path, week: Int, facts: List<MainlineFact>): Pair<List<JsonObject>, String?> {
    if (!Files.isRegularFile(file)) return emptyList<JsonObject>() to "本周要点尚未提供，可以直接做题。"
    return runCatching {
        val raw = LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject
        require(raw.num("schema_version") == 1L && raw.num("week") == week.toLong()) { "要点版本或周次不匹配" }
        val sections = (raw["sections"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        require(sections.isNotEmpty()) { "要点没有章节" }
        val known = facts.associateBy { it.id }
        require(known.size == facts.size && known.isNotEmpty()) { "事实清单缺失或重复" }
        val ids = sections.map { it.str("section_id") }
        require(ids.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) } && ids.toSet().size == ids.size) { "section_id 无效或重复" }
        for (section in sections) {
            val links = section.strings("fact_ids")
            require(section.str("title").isNotBlank() && section.str("body").isNotBlank()) { "要点标题或正文为空" }
            require(links.isNotEmpty() && links.toSet().size == links.size && links.all { it in known }) { "要点含未知或重复事实" }
            require(section.strings("sources").toSet() == links.map { known.getValue(it).source }.toSet()) { "要点出处与事实不一致" }
        }
        require(sections.flatMap { it.strings("fact_ids") }.toSet() == known.keys) { "要点未覆盖全部事实" }
        // Whitelist fields: never forward unrelated authoring metadata to the learner.
        sections.map { s -> json("section_id" to s.str("section_id"), "title" to s.str("title"), "body" to s.str("body"),
            "fact_ids" to s.strings("fact_ids"), "sources" to s.strings("sources"),
            "content_version" to notesVersion(s.str("body"))) } to null
    }.getOrElse { emptyList<JsonObject>() to "本周要点暂不可用（格式或事实映射校验失败），可以直接做题。" }
}

internal fun notesVersion(body: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Optional independently checked metadata. Invalid or pending data never publishes stars. */
internal fun loadMainlineEmphasis(file: Path, week: Int, facts: List<MainlineFact>): List<JsonObject> {
    if (!Files.isRegularFile(file)) return emptyList()
    return runCatching {
        val raw = LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject
        require(raw.num("schema_version") == 1L && raw.num("week") == week.toLong())
        val unique = linkedMapOf<String, JsonObject>()
        for (element in (raw["marks"] as? JsonArray).orEmpty()) {
            val mark = element.jsonObject
            require(mark.str("fact_id") in facts.map { it.id } && mark.flag("reviewed"))
            require(mark.str("recording_kind") in setOf("lecture", "tutorial"))
            require(mark.str("recording_id").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")))
            val stamp = mark.str("timestamp")
            require(stamp.matches(Regex("(?:[0-9]{1,9}:[0-5][0-9]|[0-9]{1,9}:[0-5][0-9]:[0-5][0-9])")))
            val parts = stamp.split(':').map { it.toLong() }
            val seconds = parts.last() + parts[parts.size - 2] * 60 + if (parts.size == 3) parts[0] * 3600 else 0
            require(mark.str("summary").isNotBlank())
            val published = json("fact_id" to mark.str("fact_id"), "week" to week, "recording_id" to mark.str("recording_id"),
                "recording_kind" to mark.str("recording_kind"), "timestamp" to "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60),
                "summary" to mark.str("summary").trim())
            val key = "${mark.str("fact_id")}:${mark.str("recording_kind")}:${mark.str("recording_id")}:$seconds"
            require(unique[key] == null || unique[key] == published)
            unique[key] = published
        }
        unique.values.toList()
    }.getOrElse { emptyList() }
}
