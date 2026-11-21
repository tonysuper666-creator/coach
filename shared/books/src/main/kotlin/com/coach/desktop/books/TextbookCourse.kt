package com.coach.desktop.books

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
    val indexState: String = "READY",
) {
    val tocNodes: List<BookTocNode> get() = structure?.tocNodes.orEmpty()
    val lessons: List<BookLesson> by lazy {
        structure?.toLessons()?.takeIf { it.isNotEmpty() } ?: StudyCoachCourse.plan(id, title, pages)
    }
    val hasPendingOcr: Boolean get() = pages.any { it.ocrPending }
    private val cachedSourceBlocks: List<SourceBlock> by lazy {
        StudyCoachCourse.blocks(id, pages, snapSentences = structure != null)
    }
    fun sourceBlocks(): List<SourceBlock> = cachedSourceBlocks
    val finished get() = learning && lessonIndex >= lessons.size
    fun page(pdfPage: Int): BookPage? = pages.firstOrNull { it.number == pdfPage }
    fun pageLabel(number: Int): String {
        if (format == "pptx") return "第 $number 张幻灯片"
        if (format == "srt" || format == "vtt") {
            val range = CourseTranscriptParser.pageTimeRangeLabel(pages.firstOrNull { it.number == number }?.text)
            return if (range != null) "第 $number 段（$range）" else "第 $number 段"
        }
        if (format == "md" || format == "txt") {
            val range = CourseTranscriptParser.pageTimeRangeLabel(pages.firstOrNull { it.number == number }?.text)
            if (range != null) return "第 $number 段（$range）"
        }
        val chapter = if (format in setOf("txt", "mobi", "azw3")) pages.asSequence().takeWhile { it.number <= number }
            .mapNotNull { BookText.chapterTitle(it.text) }.lastOrNull() else null
        return "第 $number 页" + (chapter?.let { "（$it）" } ?: "")
    }
}
internal data class BookSummary(
    val id: String, val title: String, val pageCount: Int, val lastPage: Int,
    val readingPage: Int, val learning: Boolean, val pendingOcrPages: Int = 0,
    val indexState: String = "READY",
    val showStandalone: Boolean = true,
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

    private val sentenceSplitRegex = Regex("(?<=[。！？.!?])\\s*|\\n")

    private fun isAsciiRegexSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

    private fun mightMatchCourseHeading(text: String, tStart: Int, lineEnd: Int): Boolean {
        val ch = text[tStart]
        return when {
            ch == '#' -> true
            ch == '第' -> true
            ch == 'C' || ch == 'c' -> lineEnd - tStart >= 9 && text.regionMatches(tStart, "chapter", 0, 7, ignoreCase = true)
            ch == 'S' || ch == 's' -> lineEnd - tStart >= 9 && text.regionMatches(tStart, "section", 0, 7, ignoreCase = true)
            ch in '0'..'9' -> {
                var k = tStart + 1
                while (k < lineEnd && text[k] in '0'..'9') k++
                k + 1 < lineEnd && text[k] == '.' && text[k + 1] in '0'..'9'
            }
            else -> false
        }
    }

    fun blocks(bookId: String, pages: List<BookPage>, snapSentences: Boolean = false): List<SourceBlock> = buildList {
        val pending = StringBuilder(512)
        for (page in pages) {
            // Keep offsets in the original extracted page. Normalize only fingerprints:
            // NFKC changes string length and must never move a reader source anchor.
            val pagePrefix = "$bookId:p${page.number}:"
            val text = page.text
            val len = text.length
            var i = 0
            while (i < len) {
                while (i < len && (text[i] == '\r' || text[i] == '\n')) i++
                if (i >= len) break
                pending.setLength(0)
                var pendingStart = i
                fun flush() {
                    if (pending.isNotBlank()) {
                        val full = pending.toString()
                        if (full.length <= 2400) {
                            add(SourceBlock("$pagePrefix$pendingStart", page.number, pendingStart, pendingStart + full.length, false, full))
                        } else {
                            var start = pendingStart
                            var left = full
                            // Legacy books (snapSentences=false) keep exact 2400-char boundaries so saved lesson/review IDs never drift.
                            // New structured books (snapSentences=true) snap cuts to sentence boundaries.
                            while (left.isNotEmpty()) {
                                var count = minOf(left.length, 2400)
                                if (snapSentences && count < left.length) {
                                    val cut = sentenceBoundaryBefore(left, count, minIndex = 1000)
                                    if (cut != null) count = cut
                                }
                                if (count < left.length && Character.isHighSurrogate(left[count - 1])) count--
                                val chunkText = left.take(count)
                                add(SourceBlock("$pagePrefix$start", page.number, start, start + count, false, chunkText))
                                start += count; left = left.drop(count)
                            }
                        }
                    }
                    pending.setLength(0)
                }
                while (i < len) {
                    val lineStart = i
                    while (i < len && text[i] != '\r' && text[i] != '\n') i++
                    val lineEnd = i
                    val lineLen = lineEnd - lineStart
                    var tStart = lineStart
                    while (tStart < lineEnd && text[tStart] <= ' ') tStart++
                    val isHeadingLine = lineLen <= 150 && tStart < lineEnd &&
                        mightMatchCourseHeading(text, tStart, lineEnd) &&
                        heading.matches(text.substring(lineStart, lineEnd).trim())
                    if (isHeadingLine) {
                        flush()
                        add(
                            SourceBlock(
                                "$pagePrefix$lineStart",
                                page.number, lineStart, lineEnd, true,
                                text.substring(lineStart, lineEnd),
                            ),
                        )
                    } else {
                        if (pending.isEmpty()) pendingStart = lineStart
                        else pending.append('\n')
                        pending.append(text, lineStart, lineEnd)
                    }
                    val nextStart = when {
                        i < len && text[i] == '\n' -> i + 1
                        i + 1 < len && text[i] == '\r' && text[i + 1] == '\n' -> i + 2
                        else -> -1
                    }
                    if (nextStart < 0 || nextStart >= len || text[nextStart] == '\r' || text[nextStart] == '\n') break
                    var k = nextStart
                    while (k < len && (text[k] == ' ' || text[k] == '\t' || text[k] == '\u000B' || text[k] == '\u000C')) k++
                    if (k < len && (text[k] == '\n' || (text[k] == '\r' && k + 1 < len && text[k + 1] == '\n'))) break
                    var h = nextStart
                    while (h < len && h - nextStart < 6 && text[h] == '#') h++
                    if (h > nextStart && h < len && isAsciiRegexSpace(text[h])) break
                    i = nextStart
                }
                flush()
                if (i < len && (text[i] == '\r' || text[i] == '\n')) {
                    i += if (text[i] == '\r' && i + 1 < len && text[i + 1] == '\n') 2 else 1
                }
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

    /** 离线或尚未生成导读时的节末三行小结兜底。 */
    fun localSectionSummary(lesson: BookLesson): List<String> {
        val sentences = lesson.text.split(sentenceSplitRegex)
            .map { it.trim() }
            .filter { it.length >= 10 }
            .distinct()
        val picked = when {
            sentences.size >= 3 -> listOf(sentences.first(), sentences[sentences.size / 2], sentences.last()).distinct()
            sentences.isNotEmpty() -> sentences
            else -> lesson.text.lineSequence().map(String::trim).filter(String::isNotEmpty).take(3).toList()
        }.take(3)
        return picked.map { line ->
            if (line.length <= 64) line else line.take(62).trimEnd('，', ',', '；', ';', '、', ' ') + "…"
        }
    }

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
