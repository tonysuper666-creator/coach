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
            "fact_ids" to s.strings("fact_ids"), "sources" to s.strings("sources")) } to null
    }.getOrElse { emptyList<JsonObject>() to "本周要点暂不可用（格式或事实映射校验失败），可以直接做题。" }
}
