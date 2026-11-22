package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class CourseMaterialRole(
    val wire: String,
    val authorityRank: Int,
    val displayOrder: Int,
    val labelZh: String,
) {
    SYLLABUS("SYLLABUS", 1, 10, "课程大纲"),
    LECTURE_SLIDES("LECTURE_SLIDES", 1, 11, "课堂讲义"),
    TUTORIAL("TUTORIAL", 1, 12, "习题课"),
    SOLUTION_KEY("SOLUTION_KEY", 1, 13, "官方答案"),
    TRANSCRIPT("TRANSCRIPT", 1, 14, "课堂转写"),
    CORE_TEXTBOOK("CORE_TEXTBOOK", 2, 20, "核心教材"),
    REQUIRED_READING("REQUIRED_READING", 3, 30, "延伸阅读"),
    USER_NOTES("USER_NOTES", 4, 40, "个人笔记");

    companion object {
        private val weekRegex = Regex(
            """(?i)(?:^|[\s_\-/(\[])(?:week|wk|w)[\s_\-]*(\d{1,2})\b|第\s*(\d{1,2})\s*周|\blec(?:ture)?[\s_\-]*(\d{1,2})\b""",
        )

        fun fromWire(value: String?): CourseMaterialRole = when (value?.trim()?.uppercase()) {
            "SYLLABUS", "HANDBOOK" -> SYLLABUS
            "LECTURE_SLIDES", "SLIDES", "LECTURE" -> LECTURE_SLIDES
            "TUTORIAL", "WORKSHOP" -> TUTORIAL
            "SOLUTION_KEY", "KEY", "ANSWERS" -> SOLUTION_KEY
            "TRANSCRIPT", "RECORDING" -> TRANSCRIPT
            "CORE_TEXTBOOK", "TEXTBOOK", "PRIMARY" -> CORE_TEXTBOOK
            "REQUIRED_READING", "PAPER", "READING" -> REQUIRED_READING
            "USER_NOTES", "NOTES" -> USER_NOTES
            else -> LECTURE_SLIDES
        }

        fun infer(
            title: String,
            format: String,
            previewText: String = "",
            pageCount: Int = 1,
            sourceFilename: String? = null,
        ): CourseMaterialRole {
            val combined = listOf(title, sourceFilename.orEmpty(), previewText.take(600))
                .joinToString(" ")
                .lowercase()
            val fmt = format.lowercase()
            if (fmt == "srt" || fmt == "vtt" || CourseTranscriptParser.looksLikeTranscript(previewText, fmt)) {
                return TRANSCRIPT
            }
            if (listOf("转写", "录音", "transcript", "audio-to-text").any { it in combined }) {
                return TRANSCRIPT
            }
            if (listOf("handbook", "syllabus", "course guide", "课程大纲", "教学大纲", "考核说明").any { it in combined }) {
                return SYLLABUS
            }
            if (listOf("solution", "answer key", "answers", "-key", "_key", " key", "参考答案", "官方答案", "标准答案").any { it in combined }) {
                return SOLUTION_KEY
            }
            if (listOf("tutorial", "workshop", "problem set", "seminar", "习题课", "讨论课").any { it in combined }) {
                return TUTORIAL
            }
            if (listOf("note", "notes", "summary", "review", "笔记", "精华", "复习整理", "错题整理").any { it in combined }) {
                return USER_NOTES
            }
            if (fmt == "pptx" || listOf("lecture", "slides", "lec ", "lec-", "讲义", "课件", "幻灯").any { it in combined }) {
                return LECTURE_SLIDES
            }
            if (pageCount >= 80 || listOf("textbook", "edition", "教材", "课本", "handbook of").any { it in combined }) {
                return CORE_TEXTBOOK
            }
            if (listOf("journal", "quarterly", "doi", "abstract", "paper", "论文", "期刊").any { it in combined }) {
                return REQUIRED_READING
            }
            return LECTURE_SLIDES
        }

        fun inferWeekNumber(
            title: String,
            sourceFilename: String? = null,
            previewText: String = "",
        ): Int? {
            for (candidate in listOf(title, sourceFilename.orEmpty(), previewText.take(400))) {
                val match = weekRegex.find(candidate) ?: continue
                val num = (1..3).firstNotNullOfOrNull { idx -> match.groupValues.getOrNull(idx)?.toIntOrNull() }
                if (num != null && num in 1..20) return num
            }
            return null
        }
    }
}

internal enum class CourseTopicStatus(val wire: String, val labelZh: String) {
    EXAMINABLE("EXAMINABLE", "必考"),
    EXCLUDED("EXCLUDED", "本课不考"),
    PENDING_MATERIALS("PENDING_MATERIALS", "缺本周材料");

    companion object {
        fun fromWire(value: String?): CourseTopicStatus = when (value?.trim()?.uppercase()) {
            "EXCLUDED" -> EXCLUDED
            "PENDING_MATERIALS" -> PENDING_MATERIALS
            else -> EXAMINABLE
        }
    }
}

internal data class CourseExamProfile(
    val examDate: String = "",
    val examDateTentative: Boolean = true,
    val examFormat: String = "",
    val examFormatTentative: Boolean = true,
    val weightingNote: String = "",
    val durationMinutes: Int = 120,
    val closedBook: Boolean = true,
    val calculatorAllowed: String = "",
)

internal data class CourseMaterialEntry(
    val bookId: String,
    val title: String,
    val format: String,
    val role: CourseMaterialRole,
    val authorityRank: Int,
    val weekNum: Int?,
    val userLocked: Boolean,
    val pageCount: Int,
    val addedAt: Long,
    val ocrPendingCount: Int = 0,
    val needsVision: Boolean = false,
)

internal data class CourseTopicSourceLink(
    val topicId: String,
    val bookId: String,
    val bookTitle: String,
    val bookFormat: String,
    val role: CourseMaterialRole,
    val authorityRank: Int,
    val tocNodeId: String? = null,
    val startPage: Int = 1,
    val endPage: Int = 1,
    val userLocked: Int = 0,
    val scopeNote: String? = null,
)

internal data class CourseCitationRef(
    val bookId: String,
    val bookTitle: String,
    val role: CourseMaterialRole,
    val page: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val timestampLabel: String? = null,
    val quote: String,
    val verified: Boolean = true,
)

internal data class CourseKeyPoint(
    val title: String,
    val explanation: String,
    val citations: List<CourseCitationRef> = emptyList(),
)

internal data class CourseDiscrepancyItem(
    val point: String,
    val authoritativeView: String,
    val secondaryView: String,
    val advice: String,
    val citations: List<CourseCitationRef> = emptyList(),
)

internal data class CoursePracticePrompt(
    val promptId: String,
    val kind: String = "ESSAY",
    val stem: String,
    val wordTarget: Int = 300,
    val points: List<String> = emptyList(),
    val citations: List<CourseCitationRef> = emptyList(),
)

internal data class CourseTopicPack(
    val topicId: String,
    val summary: String,
    val authorityNotice: String,
    val examDateTentative: Boolean = true,
    val examFormatTentative: Boolean = true,
    val examFocus: String = "",
    val keyPoints: List<CourseKeyPoint> = emptyList(),
    val discrepancies: List<CourseDiscrepancyItem> = emptyList(),
    val practicePrompt: CoursePracticePrompt? = null,
    val citations: List<CourseCitationRef> = emptyList(),
    val generatedFromHash: String = "",
    val serverBacked: Boolean = false,
)

internal data class CourseTopicPracticeDraft(
    val topicId: String,
    val text: String = "",
    val activeSourceBookId: String? = null,
    val activePage: Int = 1,
    val companionOpen: Boolean = true,
    val updatedAt: Long = 0L,
)

internal data class CourseActiveAnchor(
    val courseId: String,
    val topicId: String? = null,
    val bookId: String? = null,
    val page: Int = 1,
    val companionOpen: Boolean = false,
    val updatedAt: Long = 0L,
)

internal data class CourseTopicEntry(
    val topicId: String,
    val collectionId: String,
    val weekLabel: String,
    val weekNum: Int?,
    val ord: Int,
    val title: String,
    val examStatus: CourseTopicStatus,
    val userLocked: Boolean,
    val examWeight: Double? = null,
    val sources: List<CourseTopicSourceLink> = emptyList(),
    val pack: CourseTopicPack? = null,
    val packSourcesHash: String? = null,
    val practiceDraft: CourseTopicPracticeDraft? = null,
    val masteryState: String = "UNSTARTED",
    val updatedAt: Long = 0L,
)

internal data class CourseDetailSnapshot(
    val courseId: String,
    val title: String,
    val examProfile: CourseExamProfile,
    val materials: List<CourseMaterialEntry>,
    val examinableTopics: List<CourseTopicEntry>,
    val pendingTopics: List<CourseTopicEntry>,
    val excludedTopics: List<CourseTopicEntry>,
    val activeAnchor: CourseActiveAnchor? = null,
    val spoilerSanitizedCount: Int = 0,
    val alignedAt: Long = 0L,
    val countdownPlan: ExamCountdownPlan? = null,
) {
    val allTopics: List<CourseTopicEntry>
        get() = examinableTopics + pendingTopics + excludedTopics
}

internal data class CourseReaderContext(
    val courseId: String,
    val courseTitle: String,
    val topicId: String,
    val topicTitle: String,
    val weekLabel: String,
    val authorityNotice: String,
    val examProfile: CourseExamProfile,
    val orderedSources: List<CourseTopicSourceLink>,
    val pack: CourseTopicPack?,
    val resumeDraft: CourseTopicPracticeDraft?,
)

internal data class CourseServerCallResult(
    val body: JsonObject?,
    val error: String?,
    val trouble: ExplainTrouble? = null,
)

internal object CourseEssayWordCounter {
    private val englishWordRegex = Regex("""[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*""")

    fun countWords(text: String): Int {
        val enCount = englishWordRegex.findAll(text).count()
        val zhCount = text.count { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        }
        return enCount + zhCount
    }

    fun progressLabel(text: String, target: Int): String = "已写 ${countWords(text)} / $target 词"
}

internal object CourseJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeExamProfile(profile: CourseExamProfile): String = buildJsonObject {
        put("examDate", profile.examDate)
        put("examDateTentative", profile.examDateTentative)
        put("examFormat", profile.examFormat)
        put("examFormatTentative", profile.examFormatTentative)
        put("weightingNote", profile.weightingNote)
        put("durationMinutes", profile.durationMinutes.coerceIn(15, 360))
        put("closedBook", profile.closedBook)
        if (profile.calculatorAllowed.isNotBlank()) put("calculatorAllowed", profile.calculatorAllowed)
    }.toString()

    fun decodeExamProfile(raw: String?, fallbackDate: String? = null): CourseExamProfile {
        val obj = parseObj(raw) ?: return CourseExamProfile(examDate = fallbackDate.orEmpty())
        return CourseExamProfile(
            examDate = obj.str("examDate").ifBlank { fallbackDate.orEmpty() },
            examDateTentative = obj.bool("examDateTentative", true),
            examFormat = obj.str("examFormat"),
            examFormatTentative = obj.bool("examFormatTentative", true),
            weightingNote = obj.str("weightingNote"),
            durationMinutes = obj.int("durationMinutes", 120).coerceIn(15, 360),
            closedBook = obj.bool("closedBook", true),
            calculatorAllowed = obj.str("calculatorAllowed"),
        )
    }

    fun encodeTopicPack(pack: CourseTopicPack): String = buildJsonObject {
        put("topicId", pack.topicId)
        put("summary", pack.summary)
        put("authorityNotice", pack.authorityNotice)
        put("examDateTentative", pack.examDateTentative)
        put("examFormatTentative", pack.examFormatTentative)
        put("examFocus", pack.examFocus)
        put("generatedFromHash", pack.generatedFromHash)
        put("serverBacked", pack.serverBacked)
        put("keyPoints", buildJsonArray {
            for (kp in pack.keyPoints) {
                add(buildJsonObject {
                    put("title", kp.title)
                    put("explanation", kp.explanation)
                    put("citations", encodeCitations(kp.citations))
                })
            }
        })
        put("discrepancies", buildJsonArray {
            for (d in pack.discrepancies) {
                add(buildJsonObject {
                    put("point", d.point)
                    put("authoritativeView", d.authoritativeView)
                    put("secondaryView", d.secondaryView)
                    put("advice", d.advice)
                    put("citations", encodeCitations(d.citations))
                })
            }
        })
        pack.practicePrompt?.let { prompt ->
            put("practicePrompt", buildJsonObject {
                put("promptId", prompt.promptId)
                put("kind", prompt.kind)
                put("stem", prompt.stem)
                put("wordTarget", prompt.wordTarget)
                put("points", buildJsonArray { prompt.points.forEach { add(JsonPrimitive(it)) } })
                put("citations", encodeCitations(prompt.citations))
            })
        }
        put("citations", encodeCitations(pack.citations))
    }.toString()

    fun decodeTopicPack(raw: String?): CourseTopicPack? {
        val obj = parseObj(raw) ?: return null
        val topicId = obj.str("topicId")
        val summary = obj.str("summary")
        if (topicId.isBlank() && summary.isBlank()) return null
        val keyPoints = obj.arr("keyPoints").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            CourseKeyPoint(
                title = o.str("title"),
                explanation = o.str("explanation"),
                citations = decodeCitations(o.arr("citations")),
            )
        }
        val discrepancies = obj.arr("discrepancies").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            CourseDiscrepancyItem(
                point = o.str("point"),
                authoritativeView = o.str("authoritativeView"),
                secondaryView = o.str("secondaryView"),
                advice = o.str("advice"),
                citations = decodeCitations(o.arr("citations")),
            )
        }
        val promptObj = obj["practicePrompt"] as? JsonObject
        val practicePrompt = promptObj?.let { p ->
            CoursePracticePrompt(
                promptId = p.str("promptId").ifBlank { "$topicId-essay" },
                kind = p.str("kind").ifBlank { "ESSAY" },
                stem = p.str("stem"),
                wordTarget = p.int("wordTarget", 300),
                points = p.arr("points").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                citations = decodeCitations(p.arr("citations")),
            )
        }?.takeIf { it.stem.isNotBlank() }
        return CourseTopicPack(
            topicId = topicId,
            summary = summary,
            authorityNotice = obj.str("authorityNotice"),
            examDateTentative = obj.bool("examDateTentative", true),
            examFormatTentative = obj.bool("examFormatTentative", true),
            examFocus = obj.str("examFocus"),
            keyPoints = keyPoints,
            discrepancies = discrepancies,
            practicePrompt = practicePrompt,
            citations = decodeCitations(obj.arr("citations")),
            generatedFromHash = obj.str("generatedFromHash"),
            serverBacked = obj.bool("serverBacked", false),
        )
    }

    fun encodeCitations(citations: List<CourseCitationRef>): JsonArray = buildJsonArray {
        for (c in citations) {
            add(buildJsonObject {
                put("bookId", c.bookId)
                put("bookTitle", c.bookTitle)
                put("role", c.role.wire)
                put("page", c.page)
                put("startUtf16", c.startUtf16)
                put("endUtf16", c.endUtf16)
                c.timestampLabel?.let { put("timestampLabel", it) }
                put("quote", c.quote)
                put("verified", c.verified)
            })
        }
    }

    fun decodeCitations(arr: JsonArray): List<CourseCitationRef> = arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val bookId = o.str("bookId")
        val quote = o.str("quote")
        if (bookId.isBlank() || quote.isBlank()) return@mapNotNull null
        CourseCitationRef(
            bookId = bookId,
            bookTitle = o.str("bookTitle"),
            role = CourseMaterialRole.fromWire(o.str("role")),
            page = o.int("page", 1),
            startUtf16 = o.int("startUtf16", 0),
            endUtf16 = o.int("endUtf16", quote.length),
            timestampLabel = o["timestampLabel"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            quote = quote,
            verified = o.bool("verified", true),
        )
    }

    fun encodePracticeDraft(draft: CourseTopicPracticeDraft): String = buildJsonObject {
        put("topicId", draft.topicId)
        put("text", draft.text)
        draft.activeSourceBookId?.let { put("activeSourceBookId", it) }
        put("activePage", draft.activePage)
        put("companionOpen", draft.companionOpen)
        put("updatedAt", draft.updatedAt)
    }.toString()

    fun decodePracticeDraft(raw: String?, fallbackTopicId: String): CourseTopicPracticeDraft? {
        val obj = parseObj(raw) ?: return null
        return CourseTopicPracticeDraft(
            topicId = obj.str("topicId").ifBlank { fallbackTopicId },
            text = obj.str("text"),
            activeSourceBookId = obj["activeSourceBookId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            activePage = obj.int("activePage", 1).coerceAtLeast(1),
            companionOpen = obj.bool("companionOpen", true),
            updatedAt = obj.long("updatedAt", 0L),
        )
    }

    fun encodeActiveAnchor(anchor: CourseActiveAnchor): String = buildJsonObject {
        put("courseId", anchor.courseId)
        anchor.topicId?.let { put("topicId", it) }
        anchor.bookId?.let { put("bookId", it) }
        put("page", anchor.page)
        put("companionOpen", anchor.companionOpen)
        put("updatedAt", anchor.updatedAt)
    }.toString()

    fun decodeActiveAnchor(raw: String?): CourseActiveAnchor? {
        val obj = parseObj(raw) ?: return null
        val courseId = obj.str("courseId")
        if (courseId.isBlank()) return null
        return CourseActiveAnchor(
            courseId = courseId,
            topicId = obj["topicId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            bookId = obj["bookId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            page = obj.int("page", 1).coerceAtLeast(1),
            companionOpen = obj.bool("companionOpen", false),
            updatedAt = obj.long("updatedAt", 0L),
        )
    }

    private fun parseObj(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    private fun JsonObject.long(key: String, default: Long): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: default

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: buildJsonArray {}
}
