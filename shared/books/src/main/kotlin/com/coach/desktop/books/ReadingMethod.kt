package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 读前、读中、读后。按书的骨架和内容自动选，手动换过以后记住。
 * 进度写在已有的章节学习记录 `practice_draft_json` 里，换学法不删别的学法的记录。
 */
internal enum class ReadingMethod(val title: String, val summary: String, val startStep: String) {
    BEFORE(
        title = "读前",
        summary = "每章先做 3–5 道自测，做完再读，读完对照。",
        startStep = "PRETEST",
    ),
    DURING(
        title = "读中",
        summary = "每节停一次，答一道小题，或用自己的话说。",
        startStep = "READ",
    ),
    AFTER(
        title = "读后",
        summary = "章末先回忆要点，再做题，错的回书看。",
        startStep = "RECALL",
    ),
    ;

    fun next(): ReadingMethod = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromWire(raw: String?): ReadingMethod =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BEFORE
    }
}

internal data class ReadingMethodSignals(
    val exerciseSections: Int = 0,
    val formulaMarks: Int = 0,
    val historyCues: Int = 0,
    val argumentCues: Int = 0,
    val literatureCues: Int = 0,
    val chapterCount: Int = 0,
    val pageCount: Int = 0,
    val format: String = "",
    val lecture: Boolean = false,
    val alreadyRead: Boolean = false,
    val forReview: Boolean = false,
)

internal data class ReadingMethodChoice(val method: ReadingMethod, val reason: String)

internal object ReadingMethodSelector {
    const val FORMULA_MARKS_FOR_TEXTBOOK = 4

    fun choose(signals: ReadingMethodSignals): ReadingMethodChoice {
        if (signals.alreadyRead || signals.forReview) {
            val reason = if (signals.forReview && !signals.alreadyRead) {
                "这本是拿来复习的，先回忆要点再做题，错的地方回书看。"
            } else {
                "你标了读过了，这一遍先回忆要点再做题，错的地方回书看。"
            }
            return ReadingMethodChoice(ReadingMethod.AFTER, reason)
        }
        if (signals.exerciseSections >= 1 && signals.formulaMarks >= FORMULA_MARKS_FOR_TEXTBOOK) {
            return ReadingMethodChoice(
                ReadingMethod.BEFORE,
                "这本书有习题，也有公式，先做几道再读，读完对照。",
            )
        }
        return ReadingMethodChoice(ReadingMethod.DURING, duringReason(signals))
    }

    private fun duringReason(signals: ReadingMethodSignals): String {
        if (signals.historyCues > 0 && signals.literatureCues == 0 && signals.exerciseSections == 0) {
            return "这是历史书，读到一节就停一下，用自己的话说这段讲了什么。"
        }
        if (signals.argumentCues > 0 && signals.exerciseSections == 0 && signals.formulaMarks < FORMULA_MARKS_FOR_TEXTBOOK) {
            return "这是按章展开的论述，没有成套习题，读到一节就停下来，用自己的话说。"
        }
        val shortLiterary = signals.format.lowercase() in setOf("txt", "epub", "md") &&
            signals.pageCount in 1..120 &&
            signals.exerciseSections == 0 &&
            signals.formulaMarks < FORMULA_MARKS_FOR_TEXTBOOK
        if (signals.literatureCues > 0 || shortLiterary) {
            return "这是文学作品，读到一节就停一下，用自己的话说刚刚读到的意思。"
        }
        if (signals.formulaMarks >= FORMULA_MARKS_FOR_TEXTBOOK && signals.exerciseSections == 0) {
            return "这本有公式，但没有章末习题，先不按先做后讲，读到一节停下来用自己的话说。"
        }
        val longProse = signals.pageCount >= 150 &&
            signals.chapterCount >= 8 &&
            signals.exerciseSections == 0 &&
            signals.formulaMarks < FORMULA_MARKS_FOR_TEXTBOOK
        if (longProse) {
            return "这是按章展开的论述，没有成套习题，读到一节就停下来，用自己的话说。"
        }
        if (signals.lecture) {
            return "这是讲义，读完一节停一下，用自己的话说这一节的要点。"
        }
        return "这本看不出成套的习题和公式，读到一节就停下来，用自己的话说。"
    }
}

internal data class ChapterMethodProgress(
    val step: String,
    val sectionIndex: Int = 0,
    val answers: Map<String, String> = emptyMap(),
    val wrongIds: List<String> = emptyList(),
    val returnedIds: List<String> = emptyList(),
    val recall: String = "",
) {
    fun hasWork(method: ReadingMethod): Boolean =
        answers.isNotEmpty() ||
            recall.isNotBlank() ||
            wrongIds.isNotEmpty() ||
            returnedIds.isNotEmpty() ||
            sectionIndex > 0 ||
            step != method.startStep
}

internal data class ReadingMethodBookState(
    val active: ReadingMethod,
    val userLocked: Boolean,
    val reason: String,
    val alreadyRead: Boolean,
    val forReview: Boolean,
    val chapters: Map<String, Map<ReadingMethod, ChapterMethodProgress>> = emptyMap(),
) {
    fun progress(chapterId: String, method: ReadingMethod): ChapterMethodProgress =
        chapters[chapterId]?.get(method) ?: ChapterMethodProgress(method.startStep)

    fun withProgress(chapterId: String, method: ReadingMethod, progress: ChapterMethodProgress): ReadingMethodBookState {
        val chapter = (chapters[chapterId] ?: emptyMap()) + (method to progress)
        return copy(chapters = chapters + (chapterId to chapter))
    }

    /** 换章时把磁盘上那一章已经做过的步骤补进来。手上已经有的步骤不退回去。 */
    fun absorb(loaded: ReadingMethodBookState?): ReadingMethodBookState {
        if (loaded == null) return this
        val ids = chapters.keys + loaded.chapters.keys
        val merged = ids.associateWith { id ->
            val methods = chapters[id].orEmpty().keys + loaded.chapters[id].orEmpty().keys
            methods.associateWith { method ->
                val kept = progress(id, method)
                val disk = loaded.progress(id, method)
                if (kept.hasWork(method) || !disk.hasWork(method)) kept else disk
            }
        }
        return copy(chapters = merged)
    }

    fun switched(to: ReadingMethod): ReadingMethodBookState = copy(
        active = to,
        userLocked = true,
        reason = "你换成了「${to.title}」。${to.summary}",
    )

    fun markedAlreadyRead(): ReadingMethodBookState {
        val choice = ReadingMethodSelector.choose(
            ReadingMethodSignals(alreadyRead = true, forReview = true),
        )
        return copy(
            active = choice.method,
            userLocked = false,
            reason = choice.reason,
            alreadyRead = true,
            forReview = true,
        )
    }

    fun hasProgress(): Boolean = chapters.values.any { methods ->
        methods.any { (method, progress) -> progress.hasWork(method) }
    }

    fun encode(): String = buildJsonObject {
        put("active", active.name)
        put("userLocked", userLocked)
        put("reason", reason)
        put("alreadyRead", alreadyRead)
        put("forReview", forReview)
        put("chapters", buildJsonObject {
            chapters.forEach { (chapterId, methods) ->
                put(chapterId, buildJsonObject {
                    methods.forEach { (method, progress) ->
                        put(method.name, encodeProgress(progress))
                    }
                })
            }
        })
    }.toString()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): ReadingMethodBookState? {
            if (raw.isNullOrBlank()) return null
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            return decode(obj)
        }

        fun decode(obj: JsonObject): ReadingMethodBookState? {
            val active = obj["active"]?.jsonPrimitive?.contentOrNull ?: return null
            val chapters = (obj["chapters"] as? JsonObject).orEmpty().mapNotNull { (chapterId, value) ->
                val methods = (value as? JsonObject)?.mapNotNull { (methodName, progress) ->
                    val method = ReadingMethod.entries.firstOrNull { it.name == methodName } ?: return@mapNotNull null
                    val body = progress as? JsonObject ?: return@mapNotNull null
                    method to decodeProgress(body, method)
                }?.toMap().orEmpty()
                chapterId to methods
            }.toMap()
            return ReadingMethodBookState(
                active = ReadingMethod.fromWire(active),
                userLocked = obj["userLocked"]?.jsonPrimitive?.booleanOrNull == true,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                alreadyRead = obj["alreadyRead"]?.jsonPrimitive?.booleanOrNull == true,
                forReview = obj["forReview"]?.jsonPrimitive?.booleanOrNull == true,
                chapters = chapters,
            )
        }

        fun resolve(saved: ReadingMethodBookState?, signals: ReadingMethodSignals): ReadingMethodBookState {
            val merged = signals.copy(
                alreadyRead = signals.alreadyRead || saved?.alreadyRead == true,
                forReview = signals.forReview || saved?.forReview == true,
            )
            val auto = ReadingMethodSelector.choose(merged)
            if (saved == null) {
                return ReadingMethodBookState(
                    active = auto.method,
                    userLocked = false,
                    reason = auto.reason,
                    alreadyRead = merged.alreadyRead,
                    forReview = merged.forReview,
                )
            }
            if (saved.userLocked) {
                return saved.copy(alreadyRead = merged.alreadyRead, forReview = merged.forReview)
            }
            return saved.copy(
                active = auto.method,
                reason = auto.reason,
                alreadyRead = merged.alreadyRead,
                forReview = merged.forReview,
            )
        }

        fun hasProgress(raw: String?): Boolean = decode(raw)?.hasProgress() == true

        private fun encodeProgress(progress: ChapterMethodProgress) = buildJsonObject {
            put("step", progress.step)
            put("sectionIndex", progress.sectionIndex)
            put("answers", buildJsonObject { progress.answers.forEach { (k, v) -> put(k, v) } })
            put("wrongIds", kotlinx.serialization.json.buildJsonArray {
                progress.wrongIds.forEach { add(JsonPrimitive(it)) }
            })
            put("returnedIds", kotlinx.serialization.json.buildJsonArray {
                progress.returnedIds.forEach { add(JsonPrimitive(it)) }
            })
            put("recall", progress.recall)
        }

        private fun decodeProgress(obj: JsonObject, method: ReadingMethod): ChapterMethodProgress {
            fun strings(key: String): List<String> =
                (obj[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val answers = (obj["answers"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap()
            return ChapterMethodProgress(
                step = obj["step"]?.jsonPrimitive?.contentOrNull ?: method.startStep,
                sectionIndex = obj["sectionIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                answers = answers,
                wrongIds = strings("wrongIds"),
                returnedIds = strings("returnedIds"),
                recall = obj["recall"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }
}

internal object ReadingMethodSignalsReader {
    private val exerciseTitle = Regex(
        """(?i)exercises?|problems?|review\s+questions?|study\s+questions?|homework|习题|练习题|思考题|复习题""",
    )
    private val formulaMark = Regex("""公式|equation|formula|[=∑∫≤≥≈≠]""", RegexOption.IGNORE_CASE)
    private val historyCue = Regex("""(?i)history|historical|dynasty|历史|战争""")
    private val argumentCue = Regex("""(?i)treatise|essay|discourse|inquiry|论著|学说""")
    private val literatureCue = Regex("""(?i)novel|poem|poetry|fiction|小说|散文|杂文""")
    private val lectureKind = Regex("""(?i)lecture|slides?""")

    fun fromBook(book: Textbook, materialKind: String? = null): ReadingMethodSignals {
        val titles = book.tocNodes.map { it.title }
        val cueText = (titles + book.title).joinToString("\n")
        val exerciseFromTitles = titles.count { exerciseTitle.containsMatchIn(it) }
        val bodyStart = runCatching { book.bodyStart.importPage }.getOrDefault(1)
        var formulaMarks = 0
        var exerciseLines = 0
        var seen = 0
        for (page in book.pages) {
            if (page.number < bodyStart) continue
            if (seen >= 40 || formulaMarks >= 12) break
            seen++
            formulaMarks += formulaMark.findAll(page.text).count()
            formulaMarks += page.illustrations.count { it.kind == PageVisualKind.FORMULA }
            for (line in page.text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.length in 1..80 && exerciseTitle.containsMatchIn(trimmed)) exerciseLines++
            }
        }
        val chapters = book.tocNodes.count { it.kind == TocNodeKind.CHAPTER }.takeIf { it > 0 }
            ?: titles.size
        val lecture = book.format.equals("pptx", ignoreCase = true) ||
            (materialKind != null && lectureKind.containsMatchIn(materialKind))
        return ReadingMethodSignals(
            exerciseSections = maxOf(exerciseFromTitles, exerciseLines),
            formulaMarks = formulaMarks,
            historyCues = historyCue.findAll(cueText).count(),
            argumentCues = argumentCue.findAll(cueText).count(),
            literatureCues = literatureCue.findAll(cueText).count(),
            chapterCount = chapters,
            pageCount = book.pages.size,
            format = book.format,
            lecture = lecture,
        )
    }
}
