package com.coach.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest

/** Why a course-definition lookup did or did not inject a definition. Observable, never logged with text. */
enum class DefinitionStatus {
    /** A full definition was injected. */
    INJECTED,
    /** No alias matched the question: baseline behaviour, nothing injected, no notice. */
    NONE,
    /** No sidecar index file: nothing injected. */
    NO_INDEX,
    /** The sidecar exists but is unusable as a whole (oversize, malformed, wrong version, too many entries). */
    INDEX_REJECTED,
    /** Several entries match equally and the author gave no priority to separate them. */
    AMBIGUOUS,
    /** The matched definition cannot fit inside the fixed total budget; it is never truncated. */
    TOO_LONG,
    /** The matched entry no longer matches the source (hash drift) or its text is unsafe. */
    ENTRY_INVALID,
}

/**
 * Author-declared index of course-definition paragraphs (`definitions.index.json`, private, never in the repository).
 *
 * ```
 * {"version":1,"entries":[{"id":"def-01","file":"rulings"|"guidance","sha256":"<hash of the normalized paragraph>",
 *   "aliases":["topic words in any language"],"priority":0}]}
 * ```
 * Only `guidance` and `rulings` may be referenced (never past papers). Aliases are the author's semantic
 * evidence; the code carries no course or language vocabulary and never guesses a definition.
 */
class ExamSourceIndex private constructor(
    val state: State,
    private val entries: List<Entry>,
    /** Entries rejected while loading (counts only; nothing about their content is kept). */
    val rejected: Int,
    /** Well-formed entries whose paragraph no longer matches the source or is unsafe; they still block a wrong pick. */
    val stale: Int = 0,
) {
    enum class State { MISSING, REJECTED, READY }

    class Entry(val id: String, val aliases: Set<String>, val priority: Int, val text: String?)

    /** Result of matching one question against the index. */
    class Match(val status: DefinitionStatus, val entry: Entry?)

    fun match(query: String): Match {
        when (state) {
            State.MISSING -> return Match(DefinitionStatus.NO_INDEX, null)
            State.REJECTED -> return Match(DefinitionStatus.INDEX_REJECTED, null)
            State.READY -> Unit
        }
        val lowered = query.lowercase()
        val hits = entries.map { it to it.aliases.count { alias -> lowered.contains(alias) } }.filter { it.second > 0 }
        if (hits.isEmpty()) return Match(DefinitionStatus.NONE, null)
        val topScore = hits.maxOf { it.second }
        val top = hits.filter { it.second == topScore }.map { it.first }
        val winners = if (top.size == 1) top else {
            val best = top.maxOf { it.priority }
            top.filter { it.priority == best }
        }
        if (winners.size != 1) return Match(DefinitionStatus.AMBIGUOUS, null)
        val winner = winners.single()
        return if (winner.text == null) Match(DefinitionStatus.ENTRY_INVALID, winner) else Match(DefinitionStatus.INJECTED, winner)
    }

    companion object {
        const val FILE_NAME = "definitions.index.json"
        const val MAX_BYTES = 64 * 1024
        const val MAX_ENTRIES = 64
        private val ID = Regex("[A-Za-z0-9_-]{1,32}")
        private val SHA = Regex("[0-9a-f]{64}")
        // Text that could close or forge the prompt structure around the injected evidence.
        private val UNSAFE_TEXT = Regex("</?\\s*(planning|adjudications|past-paper-reference|course-definition|system|assistant|user|developer)\\b" +
            "|(?im)^\\s*(system|assistant|developer)\\s*:", RegexOption.IGNORE_CASE)

        val missing = ExamSourceIndex(State.MISSING, emptyList(), 0)

        fun sha256(chunk: String): String = MessageDigest.getInstance("SHA-256")
            .digest(normalize(chunk).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun normalize(chunk: String): String = chunk.replace("\r\n", "\n").trim()

        /** Authoring aid: length and hash per paragraph, no text. */
        fun describeChunks(text: String): List<Triple<Int, Int, String>> =
            examChunks(text).mapIndexed { i, c -> Triple(i, normalize(c).length, sha256(c)) }

        fun parse(json: String?, guidance: String, rulings: String): ExamSourceIndex {
            if (json == null) return missing
            if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return ExamSourceIndex(State.REJECTED, emptyList(), 0)
            val root = try { Json.parseToJsonElement(json) } catch (_: Exception) { null } catch (_: StackOverflowError) { null }
            val list = (root as? JsonObject)?.takeIf { (it["version"] as? JsonPrimitive)?.intOrNull == 1 }?.get("entries") as? JsonArray
            if (list == null || list.size > MAX_ENTRIES) return ExamSourceIndex(State.REJECTED, emptyList(), 0)

            val byFile = mapOf("guidance" to hashed(guidance), "rulings" to hashed(rulings))
            class Parsed(val id: String, val aliases: Set<String>, val priority: Int, val text: String?)
            val parsed = mutableListOf<Parsed>()
            var rejected = 0
            for (element in list) {
                val entry = parseEntry(element, byFile)
                if (entry == null) rejected++ else parsed += Parsed(entry.id, entry.aliases, entry.priority, entry.text)
            }
            // A repeated id is ambiguous authorship: reject every entry that carries it.
            val duplicated = parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys
            rejected += parsed.count { it.id in duplicated }
            val kept = parsed.filter { it.id !in duplicated }.map { Entry(it.id, it.aliases, it.priority, it.text) }
            return ExamSourceIndex(State.READY, kept, rejected, kept.count { it.text == null })
        }

        private fun hashed(source: String): Map<String, String> =
            examChunks(source).associateBy({ sha256(it) }, { normalize(it) })

        /** Null = structurally invalid (dropped). A non-null entry with null text is stale or unsafe: it still blocks silently wrong picks. */
        private fun parseEntry(element: Any?, byFile: Map<String, Map<String, String>>): Entry? {
            val obj = element as? JsonObject ?: return null
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = string("id")?.takeIf { ID.matches(it) } ?: return null
            val file = byFile[string("file") ?: return null] ?: return null
            val sha = string("sha256")?.lowercase()?.takeIf { SHA.matches(it) } ?: return null
            val priority = when (val p = obj["priority"]) {
                null -> 0
                is JsonPrimitive -> p.takeIf { !it.isString }?.intOrNull?.takeIf { it in -1000..1000 } ?: return null
                else -> return null
            }
            val rawAliases = obj["aliases"] as? JsonArray ?: return null
            if (rawAliases.size !in 1..16) return null
            val aliases = LinkedHashSet<String>()
            for (raw in rawAliases) {
                val alias = ((raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null).trim().lowercase()
                if (!validAlias(alias)) return null
                aliases += alias
            }
            val text = file[sha]?.takeIf { !UNSAFE_TEXT.containsMatchIn(it) }
            return Entry(id, aliases, priority, text)
        }

        private fun validAlias(alias: String): Boolean {
            val points = alias.codePointCount(0, alias.length)
            if (points > 40 || alias.any { it.isISOControl() || it == '<' || it == '>' }) return false
            val allHan = alias.isNotEmpty() && alias.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            return points >= 3 || (allHan && points >= 2)
        }
    }
}

/** Paragraph/table-row segmentation shared by context selection and the index hashes. */
internal fun examChunks(text: String): List<String> = text.split(Regex("\\n\\s*\\n")).flatMap { paragraph ->
    // Score table entries individually: a matching late row must not
    // pull in (and get truncated behind) the entire preceding table.
    val rows = paragraph.lines()
    if (rows.firstOrNull()?.trim()?.startsWith("|") == true)
        rows.drop(2).map { rows.first() + "\n" + it }
    else listOf(paragraph)
}

/**
 * The material budget is fixed at 700 characters (overview 180 + planning 200 + adjudications 160 + past papers 160).
 * An injected definition is paid for out of those four sections down to fixed floors, never on top of them.
 */
internal object ExamBudget {
    class Caps(val overview: Int, val planning: Int, val adjudications: Int, val papers: Int)

    const val TOTAL = 700
    val base = Caps(180, 200, 160, 160)
    private val floor = Caps(60, 40, 64, 80)
    private val yields = listOf(
        base.overview - floor.overview, base.planning - floor.planning,
        base.adjudications - floor.adjudications, base.papers - floor.papers,
    )
    private val yieldTotal = yields.sum()

    /** Separator the definition block costs in addition to its own text. */
    const val SEPARATOR = 2
    val maxDefinition: Int = yieldTotal - SEPARATOR

    /** Section caps after paying for a definition of [length] characters, or null when it cannot fit. */
    fun capsFor(length: Int): Caps? {
        val need = length + SEPARATOR
        if (length < 1 || need > yieldTotal) return null
        // Proportional cut per section, largest-remainder rounding (ties in section order).
        val cuts = yields.map { need * it / yieldTotal }.toMutableList()
        val order = yields.indices.sortedWith(compareByDescending<Int> { (need * yields[it]) % yieldTotal }.thenBy { it })
        repeat(need - cuts.sum()) { cuts[order[it]]++ }
        return Caps(base.overview - cuts[0], base.planning - cuts[1], base.adjudications - cuts[2], base.papers - cuts[3])
    }
}
