package com.coach.server

import com.coach.ai.BridgeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Teaching questions for one exam point.
 *
 * Haiku 5.5 Batch writes the items. A GPT model on the bridge answers them
 * without seeing the key, the reasons, or the writer's citations. A mismatch
 * gets one more ask. Anything still inconsistent is not stored.
 * Past-paper text, TOC pages, and end-of-chapter exercises never enter the prompt.
 * Textbook spans outrank slides.
 */
object ExamPointDrillLimits {
    const val GENERATOR_MODEL: String = BridgeClient.HAIKU
    const val CHECKER_MODEL: String = BridgeClient.GPT_62_ASTRA
    const val MIN_CHOICES: Int = 3
    const val MAX_CHOICES: Int = 5
    const val MAX_TOKENS: Int = 8_000
}

data class DrillOptionDraft(
    val letter: String,
    val text: String,
    val correct: Boolean,
    val reason: String,
    val citation: JsonObject?,
)

data class ChoiceDraft(
    val id: String,
    val stem: String,
    val options: List<DrillOptionDraft>,
)

data class EssayParagraphDraft(
    val claim: String,
    val citation: JsonObject?,
)

data class EssayDraft(
    val thesis: String,
    val paragraphs: List<EssayParagraphDraft>,
)

data class SupportVote(
    val letter: String? = null,
    val supported: Boolean? = null,
    val citation: JsonObject? = null,
    val onTopic: Boolean = true,
    val duplicate: Boolean = false,
)

data class ExamPointAdmission(
    val choices: JsonArray,
    val essay: JsonObject?,
    val missingChoices: Int,
    val essayMissing: Boolean,
    val discards: Map<String, Int>,
    val checked: Int,
    val agreed: Int,
    val emptyReason: String?,
    val generatorModel: String,
    val checkerModel: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("choices", choices)
        if (essay != null) put("essay", essay) else put("essay", kotlinx.serialization.json.JsonNull)
        put("missingChoices", missingChoices)
        put("essayMissing", essayMissing)
        put("discards", buildJsonObject { discards.forEach { (reason, count) -> put(reason, count) } })
        put("checked", checked)
        put("agreed", agreed)
        if (emptyReason != null) put("emptyReason", emptyReason) else put("emptyReason", kotlinx.serialization.json.JsonNull)
        put("generatorModel", generatorModel)
        put("checkerModel", checkerModel)
    }
}

object ExamPointDrillGate {
    private val letters = listOf("A", "B", "C", "D")
    private val essayTechniques = setOf("EXPLANATION", "COMPARISON", "APPLICATION")
    private val essayFormat = Regex("""论述|简答|essay|discuss|short\s+answer""", RegexOption.IGNORE_CASE)
    private val choiceOnlyFormat = Regex("""只考选择|选择题|multiple[\s-]*choice|\bmcq\b""", RegexOption.IGNORE_CASE)

    private val tocHeadingLine = Regex(
        """^(?:Brief\s+Contents|Detailed\s+Contents|Contents|Table\s+of\s+Contents|List\s+of\s+(?:Figures|Tables|Boxes|Illustrations)|目\s*录|总\s*目\s*录|简\s*目)$""",
        RegexOption.IGNORE_CASE,
    )
    private val indexHeadingLine = Regex(
        """^(?:Index|Subject\s+Index|Author\s+Index|Name\s+Index|索\s*引|主题索引|人名索引)$""",
        RegexOption.IGNORE_CASE,
    )
    private val indexEntryLine = Regex(
        """^.{2,70}?[,，、]\s*(?:p\.?\s*)?\d{1,5}(?:\s*[-–—,，、]\s*\d{1,5})*\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val partDividerHeadingLine = Regex(
        """^(?:#{1,6}\s*)?(?:Part\s+(?:[IVXLC]+|\d+)|第\s*[一二三四五六七八九十\d]+\s*[部编篇])(?:\s*[:：\-—.]\s*.*|\s*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val partMiniTocLine = Regex("""^\d{1,2}\s+[A-Z\u4e00-\u9fff][^\r\n]{3,90}$""")
    private val trailingPageNum = Regex("""^(.+?\S)(?:\s*[…⋯]+|\s*(?:[.．·•\-–—_]\s*){2,}|\s+)(\d{1,4}|[ivxlcdm]{1,5})\s*$""")
    private val tocTailExcludedWord = Regex(
        """(?i)(?:^|\s)(?:in|of|at|to|by|for|from|on|with|and|or|as|than|over|under|between|about|after|before|year|years|age|chapter|section|table|figure|fig\.?|box|part|week|step|equation|eq\.?|definition|theorem|example|question|problem|exercise|case|model|stage|phase|level|type|class|grade|tier|group)$""",
    )
    private val exerciseHeadingLine = Regex(
        """^(?:#{1,6}\s*)?(?:\d+(?:\.\d+)*\s+)?(?:Exercises(?:\s+and\s+Problems)?|Chapter\s+Exercises|End[- ]of[- ]Chapter\s+(?:Exercises|Questions|Problems)|Review\s+Questions|Study\s+Questions|Discussion\s+Questions|Practice\s+Questions|Comprehension\s+Questions|Analytical\s+(?:Problems|Questions)|Numerical\s+(?:Problems|Questions)|Problems(?:\s+and\s+Applications)?|Questions\s+and\s+Problems|Essay\s+Questions|Short\s+Answer\s+Questions|Multiple\s+Choice\s+Questions|Self[- ]Test|Check\s+Your\s+Understanding|True\s*,\s*False\s*,?\s*(?:or\s+)?(?:Unclear|Uncertain)|True\s+or\s+False|章末习题|本章习题|课后习题|习题|思考题|复习思考题|练习题|判断题|简答题|计算题|案例分析题)\s*[:：.]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val exerciseInstructionLine = Regex(
        """(?i)(?:(?:state|determine|decide|indicate|explain|evaluate)\s+whether\s+(?:each\s+of\s+)?(?:the\s+following\s+)?statements?\s+(?:is|are)\s+true\s*,?\s*false|\btrue\s*,\s*false\s*,?\s*(?:or\s+)?(?:unclear|uncertain)\b|判断下列(?:说法|陈述|命题)是否正确)""",
    )
    private val chapterBoundaryLine = Regex(
        """^(?:#{1,6}\s*)?(?:(?:Chapter|Part|Unit)\s+(?:\d{1,2}|[IVXLC]+)\b|第\s*[0-9一二三四五六七八九十百千]+\s*[章节篇])""",
        RegexOption.IGNORE_CASE,
    )
    private val numericChapterHeadingLine = Regex(
        """^(?:#{1,6}\s*)?(\d{1,2})\s+([A-Z\u4e00-\u9fff][^\r\n]{2,110})$""",
    )
    private val firstSectionOfChapterLine = Regex(
        """^(?:#{1,6}\s*)?(\d{1,2})\.1\s+([A-Z\u4e00-\u9fff][^\r\n]{2,100})$""",
    )
    private val bareChapterNumberLine = Regex("""^(\d{1,2})$""")
    private val chapterUnitLead = Regex(
        """(?i)^(?:years?|months?|days?|hours?|minutes?|seconds?|percent|percentage|million|billion|thousand|hundred|dollars?|pounds?|euros?|mg|kg|km|cm|mm|per|vs\.?|and|or|to|of|for|from|with|in|on|at|by)\b""",
    )
    private val numberedItemLine = Regex("""^\s*(?:\d{1,2}[.)]|(?:Question|Problem|Exercise)\s+\d{1,2}[.:]?)\s+\S""", RegexOption.IGNORE_CASE)
    private val exercisePromptLead = Regex(
        """(?i)^\s*(?:\d{1,2}[.)]\s+)?(?:Explain|Discuss|Compare|Contrast|Calculate|Compute|Suppose|Assume|Consider|Evaluate|Determine|Show\s+that|Prove|Derive|Draw|Graph|Sketch|Use|Why|How|What|Which|Is\s+it|Are\s+the|True\s+or\s+false|Does|Do|Can|Could|Would|Should|Briefly|Illustrate|Analyze|Analyse|State|Indicate|Decide)\b""",
    )
    private val subPartMarker = Regex("""(?m)(?:^|\s)(?:\([a-e]\)|[a-e]\.)\s+[A-Z]""")
    private val exerciseSubPartPrompt = Regex(
        """(?m)(?:^|\s)(?:\([a-e]\)|[a-e]\.)\s+(?:Explain|Discuss|Compare|Contrast|Calculate|Compute|Suppose|Assume|Consider|Evaluate|Determine|Show|Prove|Derive|Draw|Graph|Sketch|Use|Using|Find|Identify|Describe|State|Indicate|Decide|Interpret|Illustrate|Analyze|Analyse|Why|How|What|Which|Is|Are|Does|Do|Can|Could|Would|Should)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val tocQuestionStemRegex = Regex(
        """(?i)(?:(?:哪一|第几)\s*[章节页部分].{0,12}(?:讲|讨论|介绍|涵盖|涉及|位于|出现)|(?:教材|本书|目录).{0,10}(?:哪一|第几)\s*[章节页]|\b(?:which|what)\s+(?:chapter|section|subsection|part|page)\b.{0,35}\b(?:covers?|discusses?|presents?|introduces?|explains?|contains?|deals?\s+with|focuses?\s+on|addresses?)\b|\btable\s+of\s+contents\b)""",
    )
    private val cjkRegex = Regex("""[\u4E00-\u9FFF]""")
    private val enWordRegex = Regex("""[a-z0-9]+""")
    private val stemStopWords = setOf(
        "the", "and", "for", "with", "from", "into", "that", "this", "these", "those", "which", "what",
        "why", "how", "when", "where", "who", "does", "do", "did", "is", "are", "was", "were", "be",
        "been", "being", "can", "could", "would", "should", "will", "may", "might", "best", "most",
        "following", "statement", "statements", "according", "textbook", "chapter", "true", "false",
        "correct", "incorrect", "describes", "explains", "defines", "definition", "about", "under",
        "when", "while", "because", "therefore", "however", "between", "among", "not", "only", "all",
    )

    fun essayEligible(examFormat: String, techniques: List<String>): Boolean {
        val named = techniques.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (named.any { it in essayTechniques }) return true
        if (essayFormat.containsMatchIn(examFormat)) return true
        if (named.isNotEmpty()) return false
        return !choiceOnlyFormat.containsMatchIn(examFormat)
    }

    fun isTocOrIndexBlock(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val lines = trimmed.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return true
        val hasTocHeading = lines.take(4).any { tocHeadingLine.matches(it.trimStart('#', ' ')) }
        val tocLineCount = lines.count(::hasTrailingPageNumber)
        val proseLines = lines.count { it.length >= 110 && (it.contains(". ") || it.contains('。')) && !hasTrailingPageNumber(it) }
        if (hasTocHeading && (tocLineCount >= 2 || proseLines == 0)) return true
        if (tocLineCount >= 4 && proseLines <= 1 && tocLineCount * 100 >= lines.size * 45) return true
        if (tocLineCount >= 6 && proseLines <= 2 && tocLineCount * 100 >= lines.size * 35) return true
        val hasIndexHeading = lines.take(3).any { indexHeadingLine.matches(it.trimStart('#', ' ')) }
        val indexCount = lines.count { indexEntryLine.matches(it) }
        if (hasIndexHeading && (indexCount >= 2 || proseLines == 0)) return true
        if (indexCount >= 4 && indexCount * 10 >= lines.size * 5) return true
        val hasPartHeading = lines.take(4).any { partDividerHeadingLine.matches(it) }
        if (hasPartHeading && proseLines == 0) {
            val miniTocEntries = lines.count { partMiniTocLine.matches(it) }
            if (miniTocEntries >= 2 || trimmed.count { !it.isWhitespace() } < 350) return true
        }
        return false
    }

    private fun hasTrailingPageNumber(rawLine: String): Boolean {
        val line = rawLine.trim()
        val m = trailingPageNum.matchEntire(line) ?: return false
        val prefix = m.groupValues[1].trim()
        val pageTok = m.groupValues[2]
        if (prefix.length < 2 || prefix.none(Char::isLetter)) return false
        if (prefix.last() in setOf('。', '！', '？', '.', '!', '?', '=', '+', '-', '/', ':', '：')) return false
        val num = pageTok.toIntOrNull()
        if (num != null) {
            if (num in 1900..2099 || num == 0) return false
            if (tocTailExcludedWord.containsMatchIn(prefix)) return false
            return true
        }
        return pageTok.all { it in "ivxlcdm" } && !tocTailExcludedWord.containsMatchIn(prefix)
    }

    private fun findExerciseStartLineIndex(rawLines: List<String>): Int =
        rawLines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && (exerciseHeadingLine.matches(trimmed) || exerciseInstructionLine.containsMatchIn(trimmed))
        }

    fun stripExerciseText(text: String): String {
        val rawLines = text.lines()
        val cutIndex = findExerciseStartLineIndex(rawLines)
        if (cutIndex >= 0) {
            val before = rawLines.subList(0, cutIndex).joinToString("\n").trim()
            return if (before.count { !it.isWhitespace() } >= 30) before else ""
        }
        return text
    }

    private fun looksLikeExerciseBlock(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (exerciseInstructionLine.containsMatchIn(trimmed)) return true
        val lines = trimmed.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.any { exerciseHeadingLine.matches(it) }) return true
        val numbered = lines.filter { numberedItemLine.containsMatchIn(it) }
        val promptLike = numbered.count { it.endsWith("?") || exercisePromptLead.containsMatchIn(it) }
        val subParts = subPartMarker.findAll(trimmed).count()
        val promptSubParts = exerciseSubPartPrompt.findAll(trimmed).count()
        val questionMarks = trimmed.count { it == '?' || it == '？' }
        // Figure/table panel captions like "(a) Outpatient ... (b) Inpatient ..." plus a rhetorical question are NOT exercises.
        if (promptSubParts >= 3) return true
        if (promptSubParts >= 2 && (numbered.isNotEmpty() || questionMarks >= 2)) return true
        if (numbered.size >= 2 && (promptLike >= 2 || (subParts >= 2 && promptSubParts >= 1 && questionMarks >= 1))) return true
        if (numbered.size < 3) return false
        return promptLike >= 1 || promptSubParts >= 2 || questionMarks >= 3
    }

    private fun isChapterOpeningBlock(
        text: String,
        inExerciseTail: Boolean = false,
        exerciseChapterNum: Int? = null,
    ): Boolean {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return false
        val topLines = lines.take(6)
        if (topLines.any { chapterBoundaryLine.containsMatchIn(it) }) return true
        if (inExerciseTail && looksLikeExerciseBlock(text)) return false
        for (line in topLines) {
            val numMatch = numericChapterHeadingLine.matchEntire(line)
            if (numMatch != null) {
                val chNum = numMatch.groupValues[1].toIntOrNull() ?: 0
                val title = numMatch.groupValues[2].trim()
                val chapterProgressOk = exerciseChapterNum == null || chNum in (exerciseChapterNum + 1)..(exerciseChapterNum + 2)
                if (chNum in 1..60 && chapterProgressOk && isPlausibleChapterTitle(title)) return true
            }
        }
        for (i in topLines.indices) {
            val bareMatch = bareChapterNumberLine.matchEntire(topLines[i])
            if (bareMatch != null && i + 1 < lines.size) {
                val chNum = bareMatch.groupValues[1].toIntOrNull() ?: 0
                val nextTitle = lines[i + 1].trimStart('#', ' ')
                val chapterProgressOk = exerciseChapterNum == null || chNum in (exerciseChapterNum + 1)..(exerciseChapterNum + 2)
                if (chNum in 1..40 && chapterProgressOk &&
                    nextTitle.firstOrNull()?.isLetter() == true &&
                    isPlausibleChapterTitle(nextTitle) && nextTitle.length >= 6
                ) return true
            }
        }
        return lines.take(15).any { line ->
            val m = firstSectionOfChapterLine.matchEntire(line) ?: return@any false
            val chNum = m.groupValues[1].toIntOrNull() ?: 0
            val title = m.groupValues[2].trim()
            val chapterProgressOk = exerciseChapterNum == null || chNum in (exerciseChapterNum + 1)..(exerciseChapterNum + 2)
            chNum in 1..60 && chapterProgressOk && !exerciseHeadingLine.matches(line) && isPlausibleChapterTitle(title)
        }
    }

    private fun isPlausibleChapterTitle(title: String): Boolean {
        if (title.length !in 4..100) return false
        if (title.split(Regex("""\s+""")).size > 12) return false
        if (title.last() in setOf('.', '?', '!', '。', '？', '！', ';', '；')) return false
        if (exerciseHeadingLine.matches(title) || exercisePromptLead.containsMatchIn(title)) return false
        if (chapterUnitLead.containsMatchIn(title)) return false
        if (hasTrailingPageNumber(title)) return false
        return title.any(Char::isLetter)
    }

    fun prepare(blocks: List<CourseMaterialTextBlock>): Pair<List<CourseMaterialTextBlock>, String?> {
        var droppedPastExam = false
        var droppedToc = false
        var droppedExercise = false
        val sorted = blocks.sortedWith(compareBy({ it.authorityRank }, { it.bookId }, { it.page }))
        val kept = mutableListOf<CourseMaterialTextBlock>()
        var currentTextbook: String? = null
        var lastTextbookPage = -1
        var inExerciseTail = false
        var tailPages = 0
        var exerciseChapterNum: Int? = null
        for (block in sorted) {
            if (block.role.equals("PAST_EXAM", ignoreCase = true) || CourseQualityGates.isPastExamTitle(block.bookTitle)) {
                droppedPastExam = true
                continue
            }
            if (isTocOrIndexBlock(block.text)) {
                droppedToc = true
                continue
            }
            if (block.role == "CORE_TEXTBOOK") {
                val consecutive = block.bookId == currentTextbook && block.page in lastTextbookPage..(lastTextbookPage + 2)
                if (!consecutive) {
                    inExerciseTail = false
                    tailPages = 0
                    exerciseChapterNum = null
                }
                currentTextbook = block.bookId
                lastTextbookPage = block.page
                if (isChapterOpeningBlock(block.text, inExerciseTail, if (inExerciseTail) exerciseChapterNum else null)) {
                    inExerciseTail = false
                    tailPages = 0
                    exerciseChapterNum = null
                }
                val rawLines = block.text.lines()
                val cutIndex = findExerciseStartLineIndex(rawLines)
                if (inExerciseTail) {
                    val stillExercise = cutIndex == 0 || looksLikeExerciseBlock(block.text)
                    if (stillExercise || tailPages < 6) {
                        val hasNoExercise = !stillExercise && rawLines.none { numberedItemLine.containsMatchIn(it.trim()) } &&
                            !exerciseSubPartPrompt.containsMatchIn(block.text) && block.text.count { it == '?' || it == '？' } < 3 &&
                            block.text.length >= 500
                        if (tailPages >= 1 && hasNoExercise) {
                            inExerciseTail = false
                            tailPages = 0
                            exerciseChapterNum = null
                        } else {
                            tailPages++
                            droppedExercise = true
                            continue
                        }
                    } else {
                        inExerciseTail = false
                        tailPages = 0
                        exerciseChapterNum = null
                    }
                }
                if (cutIndex >= 0) {
                    val stripped = stripExerciseText(block.text)
                    inExerciseTail = true
                    tailPages = 1
                    exerciseChapterNum = rawLines.getOrNull(cutIndex)?.trim()
                        ?.let { Regex("""^(?:#{1,6}\s*)?(\d{1,2})\.\d+""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    if (stripped.isBlank()) {
                        droppedExercise = true
                        continue
                    }
                    kept += block.copy(text = stripped)
                    continue
                }
            } else if (exerciseInstructionLine.containsMatchIn(block.text)) {
                val stripped = stripExerciseText(block.text)
                if (stripped.isBlank()) {
                    droppedExercise = true
                    continue
                }
                kept += block.copy(text = stripped)
                continue
            }
            kept += block
        }
        val ordered = kept.sortedWith(compareBy({ it.authorityRank }, { it.page }, { it.bookId }))
        val reason = when {
            ordered.isNotEmpty() -> null
            droppedToc && !droppedExercise && !droppedPastExam -> "材料只有目录页，无法出题"
            droppedExercise && !droppedPastExam -> "材料只有章末习题，不能作为教材结论出题"
            droppedPastExam && !droppedToc && !droppedExercise -> "只有往年题，不能进题库"
            droppedToc -> "材料只有目录页，无法出题"
            droppedPastExam -> "只有往年题，不能进题库"
            else -> "没有可引用的教材或讲义"
        }
        return ordered to reason
    }

    fun parseDrafts(raw: JsonObject): List<ChoiceDraft> {
        val rows = raw["choices"] as? JsonArray ?: return emptyList()
        return rows.mapIndexedNotNull { index, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { optEl ->
                val opt = optEl as? JsonObject ?: return@mapNotNull null
                DrillOptionDraft(
                    letter = opt.str("letter").trim().uppercase(),
                    text = opt.str("text").trim(),
                    correct = (opt["correct"] as? JsonPrimitive)?.booleanOrNull == true,
                    reason = opt.str("reason").trim(),
                    citation = opt["citation"] as? JsonObject,
                )
            }
            ChoiceDraft(
                id = obj.str("id").ifBlank { "c${index + 1}" },
                stem = obj.str("stem").trim(),
                options = options,
            )
        }.take(ExamPointDrillLimits.MAX_CHOICES)
    }

    fun parseEssay(raw: JsonObject): EssayDraft? {
        val obj = raw["essay"] as? JsonObject ?: return null
        val paragraphs = (obj["paragraphs"] as? JsonArray).orEmpty().mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            EssayParagraphDraft(claim = row.str("claim").trim(), citation = row["citation"] as? JsonObject)
        }
        val thesis = obj.str("thesis").trim()
        if (thesis.isBlank() && paragraphs.isEmpty()) return null
        return EssayDraft(thesis, paragraphs)
    }

    fun parseVotes(raw: JsonObject?): Pair<Map<String, SupportVote>, List<SupportVote>> {
        if (raw == null) return emptyMap<String, SupportVote>() to emptyList()
        val answers = (raw["answers"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            if (id.isBlank()) return@mapNotNull null
            id to SupportVote(
                letter = obj.str("letter").trim().uppercase().ifBlank { null },
                citation = obj["support"] as? JsonObject ?: obj["citation"] as? JsonObject,
                onTopic = (obj["onTopic"] as? JsonPrimitive)?.booleanOrNull ?: true,
                duplicate = (obj["duplicate"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.toMap()
        val essay = (raw["essay"] as? JsonArray).orEmpty().map { el ->
            val obj = el as? JsonObject
            if (obj == null) {
                SupportVote(supported = false)
            } else {
                SupportVote(
                    supported = (obj["supported"] as? JsonPrimitive)?.booleanOrNull == true,
                    citation = obj["support"] as? JsonObject ?: obj["citation"] as? JsonObject,
                )
            }
        }
        return answers to essay
    }

    /** Stem and option text only. The checker must not see the key, the reasons, or the writer's citations. */
    fun blindPayload(choices: List<ChoiceDraft>, essay: EssayDraft?): JsonObject = buildJsonObject {
        put("choices", buildJsonArray {
            for (choice in choices) {
                add(buildJsonObject {
                    put("id", choice.id)
                    put("stem", choice.stem)
                    put("options", buildJsonArray {
                        for (opt in choice.options) {
                            add(buildJsonObject {
                                put("letter", opt.letter)
                                put("text", opt.text)
                            })
                        }
                    })
                })
            }
        })
        if (essay != null) {
            put("essay", buildJsonObject {
                put("thesis", essay.thesis)
                put("paragraphs", buildJsonArray {
                    essay.paragraphs.forEach { add(buildJsonObject { put("claim", it.claim) }) }
                })
            })
        }
    }

    fun admit(
        blocks: List<CourseMaterialTextBlock>,
        essayWanted: Boolean,
        drafts: List<ChoiceDraft>,
        votes: Map<String, List<SupportVote>>,
        essay: EssayDraft?,
        essayVotes: List<List<SupportVote>>,
        generatorModel: String,
        checkerModel: String,
        topicTitle: String = "",
        requireChinese: Boolean = false,
    ): ExamPointAdmission {
        val discards = linkedMapOf<String, Int>()
        val choiceLocalDiscards = linkedMapOf<String, Int>()
        val choiceCheckerDiscards = linkedMapOf<String, Int>()
        fun drop(reason: String) { discards[reason] = (discards[reason] ?: 0) + 1 }
        if (!generatorModel.startsWith("claude-haiku")) {
            drop("wrong_vendor")
            return emptyAdmission(3, essayWanted, discards, 0, 0, "出题模型不是 Haiku，整批未入库", generatorModel, checkerModel)
        }
        val checkerOk = checkerModel.startsWith("gpt-")
        val byPage = blocks.groupBy { it.bookId to it.page }
        val byBook = blocks.groupBy { it.bookId }
        val textbookIds = blocks.filter { it.role == "CORE_TEXTBOOK" }.map { it.bookId }.toSet()
        val sourceTexts = blocks.map { it.text }
        val stored = mutableListOf<JsonObject>()
        val seenDrafts = mutableListOf<ChoiceDraft>()
        var checked = 0
        var agreed = 0
        var choiceChecked = 0
        for (draft in drafts) {
            val local = localChoiceFailure(draft, byPage, byBook, textbookIds, sourceTexts, topicTitle, requireChinese)
                ?: duplicateChoiceReason(draft, seenDrafts)
            if (local != null) {
                drop(local)
                choiceLocalDiscards[local] = (choiceLocalDiscards[local] ?: 0) + 1
                continue
            }
            seenDrafts += draft
            val history = votes[draft.id].orEmpty()
            if (!checkerOk || history.isEmpty()) {
                val reason = if (!checkerOk) "wrong_vendor" else "answer_mismatch"
                drop(reason)
                choiceCheckerDiscards[reason] = (choiceCheckerDiscards[reason] ?: 0) + 1
                continue
            }
            val verdicts = history.map { vote ->
                checked += 1
                choiceChecked += 1
                val reason = checkerChoiceReason(draft, vote, byPage, byBook, textbookIds)
                if (reason == null) agreed += 1
                reason
            }
            val finalReason = verdicts.last()
            if (finalReason == null) {
                stored += storedChoice(draft, byPage, byBook)
            } else {
                drop(finalReason)
                choiceCheckerDiscards[finalReason] = (choiceCheckerDiscards[finalReason] ?: 0) + 1
            }
        }
        val kept = stored.take(ExamPointDrillLimits.MAX_CHOICES)
        val missing = (ExamPointDrillLimits.MIN_CHOICES - kept.size).coerceAtLeast(0)
        val essayResult = if (!essayWanted) {
            null to false
        } else {
            judgeEssay(essay, essayVotes, checkerOk, byPage, byBook, textbookIds, sourceTexts, ::drop) { ok ->
                checked += 1
                if (ok) agreed += 1
            }
        }
        val emptyReason = when {
            kept.isNotEmpty() -> null
            drafts.isEmpty() -> "模型没有写出可用的题"
            choiceChecked > 0 -> when {
                (choiceCheckerDiscards["answer_mismatch"] ?: 0) > 0 -> "核对全部拒掉（答案不一致）"
                (choiceCheckerDiscards["off_topic"] ?: 0) > 0 -> "核对全部拒掉（题目偏离考点）"
                (choiceCheckerDiscards["source_mismatch"] ?: 0) > 0 -> "核对全部拒掉（核对出处对不上原文）"
                else -> "核对全部拒掉"
            }
            choiceCheckerDiscards.isNotEmpty() -> when {
                (choiceCheckerDiscards["wrong_vendor"] ?: 0) > 0 -> "核对模型不符合要求，未入库"
                else -> "核对全部拒掉"
            }
            choiceLocalDiscards.isNotEmpty() -> {
                val top = choiceLocalDiscards.maxByOrNull { it.value }?.key
                when (top) {
                    "source_mismatch" -> "引用对不上原文，这些题没有留下"
                    "toc_question" -> "生成了目录定位题，未留下"
                    "off_topic" -> "题目偏离本考点，未留下"
                    "recall_stem" -> "题干复述原文，未通过检查"
                    "duplicate_question" -> "题目重复，未留下"
                    else -> "模型没有写出可用的题"
                }
            }
            else -> "模型没有写出可用的题"
        }
        return ExamPointAdmission(
            choices = JsonArray(kept),
            essay = essayResult.first,
            missingChoices = missing,
            essayMissing = essayResult.second,
            discards = discards,
            checked = checked,
            agreed = agreed,
            emptyReason = emptyReason,
            generatorModel = generatorModel,
            checkerModel = checkerModel,
        )
    }

    private fun judgeEssay(
        essay: EssayDraft?,
        votes: List<List<SupportVote>>,
        checkerOk: Boolean,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
        drop: (String) -> Unit,
        count: (Boolean) -> Unit,
    ): Pair<JsonObject?, Boolean> {
        val local = localEssayFailure(essay, byPage, byBook, textbookIds, sourceTexts)
        if (local != null || essay == null) {
            drop(local ?: "malformed")
            return null to true
        }
        if (!checkerOk || votes.isEmpty()) {
            drop(if (!checkerOk) "wrong_vendor" else "essay_unsupported")
            return null to true
        }
        val finals = votes.map { round ->
            val aligned = essay.paragraphs.size == round.size
            var ok = aligned
            for (index in essay.paragraphs.indices) {
                val vote = if (aligned) round[index] else null
                val verdict = vote != null && checkerEssaySupportOk(vote, byPage, byBook, textbookIds)
                count(verdict)
                if (!verdict) ok = false
            }
            ok
        }
        if (finals.last()) return storedEssay(essay, byPage, byBook) to false
        drop("essay_unsupported")
        return null to true
    }

    internal fun localChoiceFailure(
        draft: ChoiceDraft,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
        @Suppress("UNUSED_PARAMETER") topicTitle: String = "",
        requireChinese: Boolean = false,
    ): String? {
        if (draft.stem.isBlank() || draft.options.size != 4) return "malformed"
        if (draft.options.map { it.letter } != letters) return "malformed"
        if (draft.options.count { it.correct } != 1) return "malformed"
        if (draft.options.any { it.text.isBlank() || it.reason.isBlank() }) return "malformed"
        if (requireChinese && !cjkRegex.containsMatchIn(draft.stem)) return "wrong_language"
        if (tocQuestionStemRegex.containsMatchIn(draft.stem)) return "toc_question"
        if (!CourseQualityGates.auditZeroOverlap(listOf(draft.stem), sourceTexts).passed) return "recall_stem"
        for (opt in draft.options) {
            val verified = verifiedSupport(opt.citation, byPage, byBook, textbookIds) ?: return "source_mismatch"
            if (!isSubstantiveQuote(verified.quote)) return "source_mismatch"
        }
        return null
    }

    internal fun duplicateChoiceReason(draft: ChoiceDraft, prior: List<ChoiceDraft>): String? {
        if (prior.isEmpty()) return null
        val normStem = normalizeForCompare(draft.stem)
        val stemTokens = contentTokens(draft.stem)
        val correctText = draft.options.firstOrNull { it.correct }?.text.orEmpty()
        val correctTokens = contentTokens(correctText)
        val combinedTokens = stemTokens + correctTokens
        val correctQuote = normalizeForCompare(draft.options.firstOrNull { it.correct }?.citation?.str("quote").orEmpty())
        for (other in prior) {
            val otherNormStem = normalizeForCompare(other.stem)
            if (normStem.isNotEmpty() && normStem == otherNormStem) return "duplicate_question"
            val otherStemTokens = contentTokens(other.stem)
            val otherCorrectText = other.options.firstOrNull { it.correct }?.text.orEmpty()
            val otherCorrectTokens = contentTokens(otherCorrectText)
            val correctSim = jaccard(correctTokens, otherCorrectTokens)
            if (correctSim >= 0.45) {
                val stemSim = jaccard(stemTokens, otherStemTokens)
                if (stemSim >= 0.72) return "duplicate_question"
                val combinedSim = jaccard(combinedTokens, otherStemTokens + otherCorrectTokens)
                val otherQuote = normalizeForCompare(other.options.firstOrNull { it.correct }?.citation?.str("quote").orEmpty())
                val sameQuote = correctQuote.length >= 20 && otherQuote.length >= 20 &&
                    (correctQuote == otherQuote || correctQuote in otherQuote || otherQuote in correctQuote)
                if (combinedSim >= 0.55 && sameQuote) return "duplicate_question"
            }
        }
        return null
    }

    private fun isSubstantiveQuote(quote: String): Boolean {
        val trimmed = quote.trim()
        if (cjkRegex.containsMatchIn(trimmed)) {
            return trimmed.count { !it.isWhitespace() } >= 8
        }
        val words = enWordRegex.findAll(trimmed.lowercase()).count()
        return words >= 4 || (trimmed.length >= 24 && words >= 3)
    }

    private fun normalizeForCompare(text: String): String =
        text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun contentTokens(text: String): Set<String> = buildSet {
        enWordRegex.findAll(text.lowercase()).forEach { m ->
            val w = m.value
            if (w.length >= 2 && w !in stemStopWords) add(w)
        }
        val zh = text.filter { it in '\u4E00'..'\u9FFF' }
        for (i in 0 until (zh.length - 1)) add(zh.substring(i, i + 2))
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    internal fun localEssayFailure(
        essay: EssayDraft?,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
        sourceTexts: List<String>,
    ): String? {
        if (essay == null) return "malformed"
        if (essay.thesis.isBlank() || essay.paragraphs.size !in 2..6) return "malformed"
        if (essay.paragraphs.any { it.claim.isBlank() }) return "malformed"
        val prose = listOf(essay.thesis) + essay.paragraphs.map { it.claim }
        if (!CourseQualityGates.auditZeroOverlap(prose, sourceTexts).passed) return "recall_stem"
        for (paragraph in essay.paragraphs) {
            val verified = verifiedSupport(paragraph.citation, byPage, byBook, textbookIds) ?: return "source_mismatch"
            if (!isSubstantiveQuote(verified.quote)) return "source_mismatch"
        }
        return null
    }

    internal fun checkerChoiceReason(
        draft: ChoiceDraft,
        vote: SupportVote,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): String? {
        if (!vote.onTopic) return "off_topic"
        if (vote.duplicate) return "duplicate_question"
        val key = draft.options.single { it.correct }.letter
        if (!vote.letter.equals(key, ignoreCase = true)) return "answer_mismatch"
        if (verifiedSupport(vote.citation, byPage, byBook, textbookIds) != null) return null
        // The checker named the wrong book, but the quote is already in the textbook.
        if (textbookHasQuote(vote.citation, byBook, textbookIds)) return null
        return "source_mismatch"
    }

    internal fun checkerEssaySupportOk(
        vote: SupportVote,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): Boolean = vote.supported == true && (
        verifiedSupport(vote.citation, byPage, byBook, textbookIds) != null ||
            textbookHasQuote(vote.citation, byBook, textbookIds)
        )

    private fun verifiedSupport(
        raw: JsonObject?,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): CourseVerifiedCitation? {
        if (raw == null) return null
        val verified = CourseQualityGates.verifyCitation(raw, byPage, byBook) ?: return null
        if (textbookIds.isNotEmpty() && verified.bookId !in textbookIds) return null
        return verified
    }

    private fun textbookHasQuote(
        raw: JsonObject?,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
        textbookIds: Set<String>,
    ): Boolean {
        if (raw == null || textbookIds.isEmpty()) return false
        val quote = raw.str("quote").trim()
        if (quote.length < 12) return false
        return textbookIds.flatMap { byBook[it].orEmpty() }.any { block ->
            block.text.contains(quote) || Utf16AnchorVerifier.findNormalizedUtf16Span(block.text, quote) != null
        }
    }

    private fun storedChoice(
        draft: ChoiceDraft,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
    ): JsonObject = buildJsonObject {
        put("id", draft.id)
        put("stem", draft.stem)
        put("options", buildJsonArray {
            for (opt in draft.options) {
                val verified = CourseQualityGates.verifyCitation(opt.citation!!, byPage, byBook)!!
                add(buildJsonObject {
                    put("letter", opt.letter)
                    put("text", opt.text)
                    put("correct", opt.correct)
                    put("reason", opt.reason)
                    put("citation", verified.toJson())
                })
            }
        })
    }

    private fun storedEssay(
        essay: EssayDraft,
        byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        byBook: Map<String, List<CourseMaterialTextBlock>>,
    ): JsonObject = buildJsonObject {
        put("thesis", essay.thesis)
        put("paragraphs", buildJsonArray {
            for (paragraph in essay.paragraphs) {
                val verified = CourseQualityGates.verifyCitation(paragraph.citation!!, byPage, byBook)!!
                add(buildJsonObject {
                    put("claim", paragraph.claim)
                    put("citation", verified.toJson())
                })
            }
        })
    }

    fun emptyAdmission(
        missing: Int,
        essayMissing: Boolean,
        discards: Map<String, Int>,
        checked: Int,
        agreed: Int,
        emptyReason: String?,
        generatorModel: String,
        checkerModel: String,
    ) = ExamPointAdmission(
        choices = buildJsonArray {},
        essay = null,
        missingChoices = missing,
        essayMissing = essayMissing,
        discards = discards,
        checked = checked,
        agreed = agreed,
        emptyReason = emptyReason,
        generatorModel = generatorModel,
        checkerModel = checkerModel,
    )
}

class ExamPointDrillPipeline(
    private val generator: BatchAiTransport,
    private val checker: BatchAiTransport,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(input: JsonObject): JsonObject {
        val topicId = input.str("topicId").ifBlank { "point" }
        val topicTitle = input.str("topicTitle").ifBlank { "这个考点" }
        val requireChinese = input.str("outputLanguage").equals("zh", ignoreCase = true)
        val techniques = (input["examTechniques"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val essayWanted = ExamPointDrillGate.essayEligible(input.str("examFormat"), techniques)
        val blocks = readBlocks(input)
        val (kept, emptyReason) = ExamPointDrillGate.prepare(blocks)
        if (kept.isEmpty()) {
            return ExamPointDrillGate.emptyAdmission(
                ExamPointDrillLimits.MIN_CHOICES, essayWanted, emptyMap(), 0, 0, emptyReason, "", "",
            ).toJson()
        }
        val scope = input.str("sourcesHash").ifBlank {
            kept.joinToString("|") { "${it.bookId}:${it.page}:${it.startUtf16}:${it.text.hashCode()}" }
        }.take(80)
        val first = generator.generateSection(
            systemPrompt = generatorSystem(essayWanted),
            userPrompt = sourcePrompt(topicTitle, kept, essayWanted),
            task = "exam_point_drill",
            batchKey = "drill-gen-$topicId-$scope",
            maxTokens = ExamPointDrillLimits.MAX_TOKENS,
        )
        if (!first.model.startsWith("claude-haiku")) {
            return ExamPointDrillGate.emptyAdmission(
                ExamPointDrillLimits.MIN_CHOICES, essayWanted, mapOf("wrong_vendor" to 1), 0, 0,
                "出题模型不是 Haiku，整批未入库", first.model, "",
            ).toJson()
        }
        val firstJson = extractJsonObject(first.text)
        var drafts = ExamPointDrillGate.parseDrafts(firstJson)
        var essay = if (essayWanted) ExamPointDrillGate.parseEssay(firstJson) else null
        val indexes = citationIndex(kept)
        val sourceTexts = kept.map { it.text }
        val textbookIds = kept.filter { it.role == "CORE_TEXTBOOK" }.map { it.bookId }.toSet()
        val failedReasons = linkedMapOf<String, String>()
        val firstSeen = mutableListOf<ChoiceDraft>()
        for (draft in drafts) {
            val reason = ExamPointDrillGate.localChoiceFailure(draft, indexes.byPage, indexes.byBook, textbookIds, sourceTexts, topicTitle, requireChinese)
                ?: ExamPointDrillGate.duplicateChoiceReason(draft, firstSeen)
            if (reason != null) failedReasons[draft.id] = reason else firstSeen += draft
        }
        val failedIds = failedReasons.keys.toList()
        val essayNeedsRewrite = essayWanted &&
            ExamPointDrillGate.localEssayFailure(essay, indexes.byPage, indexes.byBook, textbookIds, sourceTexts) != null
        if (failedIds.isNotEmpty() || essayNeedsRewrite) {
            val repair = generator.generateSection(
                systemPrompt = generatorSystem(essayWanted),
                userPrompt = repairPrompt(topicTitle, kept, essayWanted, failedIds, essayNeedsRewrite, failedReasons),
                task = "exam_point_drill",
                batchKey = "drill-repair-$topicId-$scope",
                maxTokens = ExamPointDrillLimits.MAX_TOKENS,
            )
            if (repair.model.startsWith("claude-haiku")) {
                val repaired = extractJsonObject(repair.text)
                val rewritten = ExamPointDrillGate.parseDrafts(repaired).associateBy { it.id }
                drafts = drafts.map { draft -> if (draft.id in failedIds) rewritten[draft.id] ?: draft else draft }
                val extras = rewritten.filterKeys { id -> drafts.none { it.id == id } }.values
                drafts = (drafts + extras).take(ExamPointDrillLimits.MAX_CHOICES)
                if (essayNeedsRewrite) essay = ExamPointDrillGate.parseEssay(repaired) ?: essay
            }
        }
        val ready = mutableListOf<ChoiceDraft>()
        for (draft in drafts) {
            val reason = ExamPointDrillGate.localChoiceFailure(draft, indexes.byPage, indexes.byBook, textbookIds, sourceTexts, topicTitle, requireChinese)
                ?: ExamPointDrillGate.duplicateChoiceReason(draft, ready)
            if (reason == null) ready += draft
        }
        val essayReady = essay?.takeUnless {
            ExamPointDrillGate.localEssayFailure(it, indexes.byPage, indexes.byBook, textbookIds, sourceTexts) != null
        }
        val votes = linkedMapOf<String, MutableList<SupportVote>>()
        val essayVotes = mutableListOf<List<SupportVote>>()
        var checkerModel = ""
        if (ready.isNotEmpty() || essayReady != null) {
            val checked = askChecker(topicTitle, kept, ready, essayReady, "drill-check-$topicId-$scope")
            checkerModel = checked.model
            if (checked.model.startsWith("gpt-")) {
                val (answers, essayRound) = ExamPointDrillGate.parseVotes(extractJsonObject(checked.text))
                ready.forEach { draft -> votes.getOrPut(draft.id) { mutableListOf() }.add(answers[draft.id] ?: SupportVote()) }
                if (essayReady != null) essayVotes += essayRound
                val again = ready.filter { draft ->
                    val vote = answers[draft.id]
                    choiceNeedsRecheck(draft, vote, indexes, textbookIds)
                }
                val essayAgain = essayReady != null && !essayRoundAgrees(essayReady, essayRound, indexes, textbookIds)
                if (again.isNotEmpty() || essayAgain) {
                    val second = askChecker(
                        topicTitle, kept, again, if (essayAgain) essayReady else null, "drill-recheck-$topicId-$scope",
                    )
                    if (second.model.startsWith("gpt-")) {
                        checkerModel = second.model
                        val (answers2, essayRound2) = ExamPointDrillGate.parseVotes(extractJsonObject(second.text))
                        again.forEach { draft -> votes.getOrPut(draft.id) { mutableListOf() }.add(answers2[draft.id] ?: SupportVote()) }
                        if (essayAgain) essayVotes += essayRound2
                    }
                }
            }
        }
        return ExamPointDrillGate.admit(
            blocks = kept,
            essayWanted = essayWanted,
            drafts = drafts,
            votes = votes,
            essay = essay,
            essayVotes = essayVotes,
            generatorModel = first.model,
            checkerModel = checkerModel,
            topicTitle = topicTitle,
            requireChinese = requireChinese,
        ).toJson()
    }

    private suspend fun askChecker(
        topicTitle: String,
        blocks: List<CourseMaterialTextBlock>,
        choices: List<ChoiceDraft>,
        essay: EssayDraft?,
        batchKey: String,
    ): BatchTransportReply = checker.generateSection(
        systemPrompt = """
            你看不到标准答案，也不要猜测出题人的意图。只根据材料独立作答。
            每道单选给出 letter，并在 support 里抄一处逐字支持该选项的原句（bookId、page、quote）；同时判断这道题是否真正考查当前考点 topicTitle 本身：
            - 中文题干对英文考点，按语义判断，不得因为语言不同或题干未出现英文考点原词就判离题。
            - 只要题目考查的是当前考点主题下的具体指标、机制、推导结果、参数变动影响或相关行为反应，均属于在考点上（onTopic 设为 true）。
            - 只有当题目明显考查同页另一个无关概念、目录定位或章末习题时，才设 onTopic 为 false；拿不准时默认 onTopic 为 true。
            - 若与本组前面某道题重复考查同一个定义，设 duplicate 为 true。
            绝不能把章末习题、判断题待判陈述（True/False/Unclear）或分析题题设当作教材结论出处。
            有教材时 support 必须来自 CORE_TEXTBOOK。同一句两边都有时，bookId 填教材。
            论述提纲的每个要点按顺序给出：有原句支撑则 supported 为 true 并附 support，否则 supported 为 false。
            只输出 JSON：{"answers":[{"id":"...","letter":"A","onTopic":true,"duplicate":false,"support":{"bookId":"...","page":1,"quote":"..."}}],"essay":[{"supported":true,"support":{"bookId":"...","page":1,"quote":"..."}}]}
        """.trimIndent(),
        userPrompt = buildJsonObject {
            put("topicTitle", topicTitle)
            put("materials", materialsJson(blocks))
            put("paper", ExamPointDrillGate.blindPayload(choices, essay))
        }.toString(),
        task = "exam_point_check",
        batchKey = batchKey,
        maxTokens = ExamPointDrillLimits.MAX_TOKENS,
    )

    private fun choiceNeedsRecheck(
        draft: ChoiceDraft,
        vote: SupportVote?,
        indexes: CitationIndex,
        textbookIds: Set<String>,
    ): Boolean {
        return ExamPointDrillGate.checkerChoiceReason(
            draft, vote ?: SupportVote(), indexes.byPage, indexes.byBook, textbookIds,
        ) != null
    }

    private fun essayRoundAgrees(
        essay: EssayDraft,
        round: List<SupportVote>,
        indexes: CitationIndex,
        textbookIds: Set<String>,
    ): Boolean = essay.paragraphs.size == round.size && essay.paragraphs.indices.all { index ->
        ExamPointDrillGate.checkerEssaySupportOk(round[index], indexes.byPage, indexes.byBook, textbookIds)
    }

    private fun generatorSystem(essayWanted: Boolean) = """
        你在为备考写教学题。题干考理解，不考原句复述，不得连续摘抄材料里 8 个英文词或 12 个汉字。
        语言要求：所有题干（stem）、选项（text）、理由（reason）以及论述提纲（thesis、claim）一律使用简体中文书写；专业术语、缩写和公式可在中文句子中保留英文原词；原句引用（citation.quote）必须保持材料原文语言逐字摘抄，不得翻译。
        每道题必须直接考查 topicTitle（当前考点本身），严禁拿同页出现的其他概念或定义凑数。如果材料里关于当前考点本身的实质内容不足，宁可少写（如写 3 道，或确实无内容时返回空数组 []），也不要偏题。
        写 3 到 5 道单选题，各题必须考查当前考点的不同角度（例如：核心机制、适用前提/边界、与相近概念区分、实证含义、情境应用），禁止多道题重复考同一个定义。
        严禁出「哪一节/哪一章/第几页讲 X」这类目录定位题；严禁把章末习题、判断题（True/False/Unclear）的待判陈述或分析题题设当作教材结论出处。
        每题恰好 4 个选项，字母固定为 A、B、C、D，恰好一个 correct 为 true。
        每个选项（包括正确项和 3 个错项）都要写一句忠实于原文的对错理由 reason，不能凭空加入原文没提到的判断或概念；并给 citation：bookId、page、startUtf16、endUtf16、quote。quote 必须是材料里能直接支撑该条 reason 的完整连续原句或子句（不要只摘一两个词）。
        材料里有 CORE_TEXTBOOK 时，引用只能来自教材，不能引用课件或讲义。
        不要使用往年题，不要输出材料里没有的页码。
        ${if (essayWanted) "另外写一份论述提纲：thesis，2 到 6 个 paragraphs，每段 claim 紧扣当前考点并挂一处完整教材原句。论点用自己的话，不要照抄原文。" else "不要写论述提纲，essay 设为 null。"}
        只输出 JSON：{"choices":[{"id":"c1","stem":"...","options":[{"letter":"A","text":"...","correct":false,"reason":"...","citation":{"bookId":"...","page":1,"startUtf16":0,"endUtf16":1,"quote":"..."}}]}],"essay":null}
    """.trimIndent()

    private fun sourcePrompt(topicTitle: String, blocks: List<CourseMaterialTextBlock>, essayWanted: Boolean) = buildJsonObject {
        put("topicTitle", topicTitle)
        put("essay", essayWanted)
        put("materials", materialsJson(blocks))
    }.toString()

    private fun repairPrompt(
        topicTitle: String,
        blocks: List<CourseMaterialTextBlock>,
        essayWanted: Boolean,
        ids: List<String>,
        essay: Boolean,
        reasons: Map<String, String> = emptyMap(),
    ) = buildJsonObject {
        put("topicTitle", topicTitle)
        put("essay", essayWanted && essay)
        put("rewriteChoiceIds", JsonArray(ids.map { JsonPrimitive(it) }))
        if (reasons.isNotEmpty()) {
            put("failureReasons", buildJsonObject { reasons.forEach { (id, r) -> put(id, r) } })
        }
        put("note", "这些题存在出处对不上、引文过短、偏离当前考点、与前题重复、目录定位、复述原文或未使用中文（wrong_language）的问题。请只重写点名的题，确保所有题干、选项、理由和提纲使用简体中文（仅 citation.quote 保留材料原文语言），紧扣当前考点本身、与其余题考查不同角度，且每个选项的理由都有完整原文支持。不要假设哪个选项该对。")
        put("materials", materialsJson(blocks))
    }.toString()

    private fun materialsJson(blocks: List<CourseMaterialTextBlock>) = buildJsonArray {
        for (block in blocks) {
            add(buildJsonObject {
                put("blockId", block.blockId)
                put("bookId", block.bookId)
                put("bookTitle", block.bookTitle)
                put("role", block.role)
                put("page", block.page)
                put("startUtf16", block.startUtf16)
                put("text", block.text)
            })
        }
    }

    private fun readBlocks(input: JsonObject): List<CourseMaterialTextBlock> =
        (input["blocks"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = obj.str("text")
            if (text.isBlank()) return@mapNotNull null
            val role = obj.str("role").ifBlank { "LECTURE_SLIDES" }
            CourseMaterialTextBlock(
                blockId = obj.str("blockId").ifBlank { "${obj.str("bookId")}:p${obj.int("page")}" },
                bookId = obj.str("bookId"),
                bookTitle = obj.str("bookTitle"),
                role = role,
                authorityRank = obj.int("authorityRank").takeIf { it > 0 } ?: if (role == "CORE_TEXTBOOK") 1 else 2,
                page = obj.int("page").coerceAtLeast(1),
                startUtf16 = obj.int("startUtf16"),
                text = text,
            )
        }

    private fun citationIndex(blocks: List<CourseMaterialTextBlock>) = CitationIndex(
        blocks.groupBy { it.bookId to it.page },
        blocks.groupBy { it.bookId },
    )

    private fun extractJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return buildJsonObject {}
        return runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrDefault(buildJsonObject {})
    }

    private data class CitationIndex(
        val byPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        val byBook: Map<String, List<CourseMaterialTextBlock>>,
    )
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
