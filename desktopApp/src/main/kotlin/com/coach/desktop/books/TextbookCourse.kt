package com.coach.desktop.books

import java.text.Normalizer
import kotlin.random.Random

internal enum class QuestionMode(val label: String) { NONE("不出"), CHOICE("选择"), SHORT_ANSWER("简答") }
internal data class BookPage(val number: Int, val text: String)
internal data class SourceBlock(val id: String, val page: Int, val start: Int, val end: Int, val heading: Boolean, val text: String)
internal data class BookLesson(val id: String, val title: String, val blocks: List<SourceBlock>) {
    val firstPage get() = blocks.first().page
    val lastPage get() = blocks.last().page
    val text get() = blocks.filterNot { it.heading }.joinToString("\n\n") { it.text }
}
internal data class Textbook(
    val id: String, val title: String, val format: String, val pages: List<BookPage>,
    val readingPage: Int = 1, val lessonIndex: Int = 0, val learning: Boolean = false,
    val promptSeen: Boolean = false, val questionMode: QuestionMode = QuestionMode.CHOICE,
) {
    val lessons: List<BookLesson> by lazy { StudyCoachCourse.plan(id, title, pages) }
    val finished get() = learning && lessonIndex >= lessons.size
}
internal data class BookSummary(val id: String, val title: String, val pageCount: Int, val readingPage: Int, val learning: Boolean)
internal data class Practice(val prompt: String, val reference: String, val answer: String, val options: List<String>)
internal data class PracticeAnswer(
    val text: String = "", val revealed: Boolean = false, val understood: Boolean? = null,
    /** The learner marked a right choice as a guess: correct is not evidence of mastery. */
    val guessed: Boolean = false,
)

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

    fun blocks(bookId: String, pages: List<BookPage>): List<SourceBlock> = buildList {
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
                        // Bound lesson chunks without dropping any extracted source text.
                        while (left.isNotEmpty()) {
                            var count = minOf(left.length, 2400)
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
        val words = Regex("[A-Za-z][A-Za-z'-]{3,}|[\\p{IsHan}]{2,6}")
        val definition = Regex("^(.{2,40}?)(?:\\s+(?:is|are|means|refers to)\\b|是|指的是|表示|称为)", RegexOption.IGNORE_CASE)
        val sentence = sentences.firstOrNull { definition.containsMatchIn(it) } ?: reference
        val defined = definition.find(sentence)?.groupValues?.get(1)?.trim()?.trimEnd(',', '，', '：', ':')
        val answer = defined?.takeIf { it.length <= 40 && sentence.contains(it) }
            ?: words.find(sentence)?.value ?: return null
        val candidates = book.lessons.flatMap { item ->
            item.text.split(Regex("(?<=[。.!?])\\s*|\\n")).mapNotNull { definition.find(it.trim())?.groupValues?.get(1)?.trim() }
        } + words.findAll(book.pages.joinToString("\n") { it.text }).map { it.value }.toList()
        val distractors = candidates.filter { it.length in 2..40 && normalized(it) != normalized(answer) }
            .distinctBy(::normalized).take(3)
        if (distractors.isEmpty()) return null
        val options = (distractors + answer).shuffled(Random(lesson.id.hashCode()))
        return Practice("根据本节原文，补全这句话：\n${sentence.replaceFirst(answer, "____")}", sentence, answer, options)
    }

    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().trim()
}
