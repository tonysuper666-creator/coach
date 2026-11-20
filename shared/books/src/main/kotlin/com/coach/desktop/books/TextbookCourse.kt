package com.coach.desktop.books

import java.text.Normalizer
import kotlin.random.Random

internal enum class QuestionMode(val label: String) { NONE("不出"), CHOICE("选择"), SHORT_ANSWER("简答") }
internal enum class LearningPath(val label: String) {
    READ("看书"), READ_AND_LEARN("边看边学"), QUESTION_FIRST("做题学");

    companion object {
        fun restore(saved: String?, learning: Boolean): LearningPath =
            if (saved == null) { if (learning) QUESTION_FIRST else READ }
            else entries.firstOrNull { it.name == saved }
                ?: throw IllegalArgumentException("保存的学习方式无法识别，原有进度和作答已保留。")
    }
}
internal data class BookPage(
    val number: Int, val text: String, val ocr: Boolean = false,
    val illustrations: List<BookIllustration> = emptyList(), val textLayout: PageTextLayout? = null,
    val ocrPending: Boolean = false,
)
internal data class SourceBlock(val id: String, val page: Int, val start: Int, val end: Int, val heading: Boolean, val text: String)

/** 导入时插在页首的原件页码标记；页号是书里印着的，这一行本身不是正文。 */
internal val PRINTED_PAGE_MARK = Regex("""<!--\s*PDF 第 \d+ 页 · 书第 .*? 页\s*-->""")

/**
 * 正文段：不是章标题，也不是单独一行的原件页码标记。
 * 凡是给读者数「第几段」的地方都用它（选段就问的行号、讲解出处的段号），两处的数才对得上。
 */
internal val SourceBlock.isBodyParagraph: Boolean get() = !heading && !PRINTED_PAGE_MARK.matches(text.trim())

internal data class BookLesson(
    val id: String,
    val title: String,
    val blocks: List<SourceBlock>,
    val breadcrumb: String = title,
) {
    val firstPage get() = blocks.first().page
    val lastPage get() = blocks.last().page
    val text get() = blocks.filterNot { it.heading }.joinToString("\n\n") { it.text }
}
internal data class Textbook(
    val id: String, val title: String, val format: String, val pages: List<BookPage>,
    val readingPage: Int = 1, val lessonIndex: Int = 0, val learning: Boolean = false,
    val promptSeen: Boolean = false, val questionMode: QuestionMode = QuestionMode.CHOICE,
    val structure: BookStructure? = null,
) {
    val lessons: List<BookLesson> by lazy {
        structure?.toLessons()?.takeIf { it.isNotEmpty() } ?: StudyCoachCourse.plan(id, title, pages)
    }
    val hasPendingOcr: Boolean get() = pages.any { it.ocrPending }
    // At most one distinct candidate is the answer, and a question needs only three others.
    val choiceCandidates: List<String> by lazy { StudyCoachCourse.choiceCandidates(this) }
    fun sourceBlocks(): List<SourceBlock> = StudyCoachCourse.blocks(id, pages, snapSentences = structure != null)
    val finished get() = learning && lessonIndex >= lessons.size
    fun page(pdfPage: Int): BookPage? = pages.firstOrNull { it.number == pdfPage }
    fun pageLabel(number: Int): String {
        if (format == "pptx") return "第 $number 张幻灯片"
        val chapter = if (format in setOf("txt", "mobi", "azw3")) pages.asSequence().takeWhile { it.number <= number }
            .mapNotNull { BookText.chapterTitle(it.text) }.lastOrNull() else null
        return "第 $number 页" + (chapter?.let { "（$it）" } ?: "")
    }
}
internal data class BookSummary(
    val id: String, val title: String, val pageCount: Int, val lastPage: Int,
    val readingPage: Int, val learning: Boolean, val pendingOcrPages: Int = 0,
)
internal data class Practice(val prompt: String, val reference: String, val answer: String, val options: List<String>)
internal data class PracticeAnswer(val text: String = "", val revealed: Boolean = false, val understood: Boolean? = null,
    val guessed: Boolean = false)

/**
 * Kotlin port of the archived StudyCoach deterministic-text-v1 / moduleGroups /
 * deterministicPlan path. See docs/windows-textbooks.md for the pinned source.
 * Only the single-book, source-grounded path is brought across; no old application,
 * exam assumptions, external planner, credential or learner data is needed.
 */
internal object StudyCoachCourse {
    private val heading = Regex("^(?:#{1,6}\\s+|第[一二三四五六七八九十百0-9]+[章节篇]|(?:Chapter|Section)\\s+\\d|\\d+(?:\\.\\d+)+\\s+).+", RegexOption.IGNORE_CASE)

    // Source-only recognition must not re-plan existing lessons or change saved answer keys.
    private val wordedSourceHeading = Regex(
        "^(?:Chapter|Section)\\s+(?:One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten|Eleven|Twelve)" +
            "(?:\\s*[-–—:]\\s*\\S.*)?$",
        RegexOption.IGNORE_CASE,
    )

    /** Only quote body text on the requested physical page; a title alone supplies no quote. */
    fun sourceQuote(lesson: BookLesson, pdfPage: Int): String? = lesson.blocks.asSequence()
        .filter { it.page == pdfPage && !it.heading }
        .map { block ->
            block.text.lineSequence()
                .filterNot { it.length <= 150 && wordedSourceHeading.matches(it.trim()) }
                .joinToString("\n").trim()
        }
        .firstOrNull { it.isNotEmpty() }

    fun blocks(bookId: String, pages: List<BookPage>, snapSentences: Boolean = false): List<SourceBlock> = buildList {
        for (page in pages) {
            // Keep offsets in the original extracted page. Normalize only fingerprints:
            // NFKC changes string length and must never move a reader source anchor.
            val paragraph = Regex("[^\\r\\n]+(?:\\r?\\n(?!\\s*\\r?\\n|#{1,6}\\s)[^\\r\\n]+)*")
            for (match in paragraph.findAll(page.text)) {
                val raw = match.value
                // Separate Markdown/short chapter headings from their following paragraph.
                val lines = raw.lines()
                var offset = match.range.first
                var pending = ""
                var pendingStart = offset
                fun flush() {
                    if (pending.isNotBlank()) {
                        var start = pendingStart
                        var left = pending
                        // Legacy books (snapSentences=false) keep exact 2400-char boundaries so saved lesson/review IDs never drift.
                        // New structured books (snapSentences=true) snap cuts to sentence boundaries.
                        while (left.isNotEmpty()) {
                            var count = minOf(left.length, 2400)
                            if (snapSentences && count < left.length) {
                                val cut = sentenceBoundaryBefore(left, count, minIndex = 1000)
                                if (cut != null) count = cut
                            }
                            if (count < left.length && Character.isHighSurrogate(left[count - 1])) count--
                            val text = left.take(count)
                            add(SourceBlock("$bookId:p${page.number}:$start", page.number, start, start + count, false, text))
                            start += count; left = left.drop(count)
                        }
                    }
                    pending = ""
                }
                for (line in lines) {
                    if (line.length <= 150 && heading.matches(line.trim())) {
                        flush()
                        add(SourceBlock("$bookId:p${page.number}:$offset", page.number, offset, offset + line.length, true, line))
                    } else {
                        if (pending.isEmpty()) pendingStart = offset
                        pending += (if (pending.isEmpty()) "" else "\n") + line
                    }
                    offset += line.length + if (page.text.getOrNull(offset + line.length) == '\r') 2 else 1
                }
                flush()
            }
        }
    }

    fun plan(bookId: String, title: String, pages: List<BookPage>): List<BookLesson> {
        val result = mutableListOf<BookLesson>()
        var group = mutableListOf<SourceBlock>()
        var name = title
        var part = 1
        fun flush() {
            if (group.any { !it.heading && it.text.isNotBlank() }) {
                result += BookLesson(group.first().id, if (part == 1) name else "$name · $part", group.toList())
                part++
            }
            group = mutableListOf()
        }
        for (block in blocks(bookId, pages)) {
            if (block.heading) {
                flush(); name = block.text.trim().trimStart('#').trim(); part = 1
            } else if (group.sumOf { it.text.length } + block.text.length > 3000) flush()
            group += block
        }
        flush()
        return result
    }

    fun practice(book: Textbook, lesson: BookLesson, mode: QuestionMode): Practice? {
        if (mode == QuestionMode.NONE) return null
        val sentences = lesson.text.split(Regex("(?<=[。！？.!?])\\s*|\\n")).map(String::trim).filter { it.length >= 12 }
        val reference = sentences.firstOrNull() ?: lesson.text.trim().takeIf { it.isNotEmpty() } ?: return null
        if (mode == QuestionMode.SHORT_ANSWER) return Practice(
            "合上原文，用自己的话说明「${lesson.title}」的主要内容，并举一个例子。", lesson.text, "", emptyList())
        val sentence = sentences.firstOrNull { definition.containsMatchIn(it) } ?: reference
        val defined = definition.find(sentence)?.groupValues?.get(1)?.trim()?.trimEnd(',', '，', '：', ':')
        val answer = defined?.takeIf { it.length <= 40 && sentence.contains(it) }
            ?: words.find(sentence)?.value ?: return null
        val answerKey = normalized(answer)
        val distractors = book.choiceCandidates.filter { normalized(it) != answerKey }.take(3)
        if (distractors.isEmpty()) return null
        val options = (distractors + answer).shuffled(Random(lesson.id.hashCode()))
        return Practice("根据本节原文，补全这句话：\n${sentence.replaceFirst(answer, "____")}", sentence, answer, options)
    }

    private val words = Regex("[A-Za-z][A-Za-z'-]{3,}|[\\p{IsHan}]{2,6}")
    private val definition = Regex("^(.{2,40}?)(?:\\s+(?:is|are|means|refers to)\\b|是|指的是|表示|称为)", RegexOption.IGNORE_CASE)

    fun choiceCandidates(book: Textbook): List<String> {
        val definitions = book.lessons.asSequence().flatMap { lesson ->
            lesson.text.splitToSequence(Regex("(?<=[。.!?])\\s*|\\n"))
                .mapNotNull { definition.find(it.trim())?.groupValues?.get(1)?.trim() }
        }
        val vocabulary = book.pages.asSequence().flatMap { page -> words.findAll(page.text).map { it.value } }
        return (definitions + vocabulary).filter { it.length in 2..40 }.distinctBy(::normalized).take(4).toList()
    }

    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().trim()

    /** 在 [maxIndex] 之前寻找最近的句末或换行切点，避免把完整句子硬切在中间。 */
    internal fun sentenceBoundaryBefore(text: String, maxIndex: Int, minIndex: Int = 1): Int? {
        val closers = setOf('”', '’', '」', '』', '）', ')', '"', '\'', '》', '】')
        val stops = setOf('。', '！', '？', '!', '?', '\n')
        for (i in minOf(maxIndex, text.length) - 1 downTo maxOf(minIndex, 1)) {
            val ch = text[i]
            if (ch in stops) {
                var end = i + 1
                while (end < maxIndex && end < text.length && text[end] in closers) end++
                return end
            }
            if (ch == '.' && (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] in closers)) {
                var end = i + 1
                while (end < maxIndex && end < text.length && text[end] in closers) end++
                return end
            }
        }
        return null
    }
}
