package com.coach.desktop.books

import java.sql.Connection
import java.text.Normalizer
import java.util.UUID

/** Page-level exam topics coexist with the course schedule. Only teaching materials supply names and links. */
internal object CourseExamPoints {
    const val LABEL = "考点"
    private val teachingRoles = setOf(
        CourseMaterialRole.CORE_TEXTBOOK, CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT,
    )
    private val sectionNumber = Regex("""(?i)^(?:(?:chapter|section|lecture|week)\s+\d+(?:\.\d+)*|第[一二三四五六七八九十\d]+[章节周]|\d+(?:\.\d+)*)[\s.、:：—-]+""")
    private val definition = Regex("""^([^:：]{2,64})[:：]\s*\S""")
    private val whitespace = Regex("""[\s\p{Z}]+""")
    private val boilerplate = setOf("contents", "outline", "summary", "references", "overview", "目录", "小结", "参考文献")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace(whitespace, " ").trim()

    // Avoid matching a short English term inside an unrelated word; Chinese terms need no word separator.
    fun termPattern(name: String): Regex {
        val start = if (name.first() in 'a'..'z') "(?<![a-z0-9])" else ""
        val end = if (name.last() in 'a'..'z') "(?![a-z0-9])" else ""
        return Regex(start + Regex.escape(name) + end)
    }

    /** Offline comparison is deliberately limited to explicit numeric definitions with identical units. */
    fun discrepancies(topic: CourseTopicEntry, pages: Map<String, List<BookPage>>): List<CourseDiscrepancyItem> {
        if (topic.weekLabel != LABEL) return emptyList()
        val assignment = Regex("""^\s*${Regex.escape(topic.title)}\s*[:：=]\s*([+-]?\d+(?:\.\d+)?)\s*([%\p{L}/]*)\s*[.。]?\s*$""", RegexOption.IGNORE_CASE)
        data class Definition(val link: CourseTopicSourceLink, val value: java.math.BigDecimal, val unit: String, val citation: CourseCitationRef)
        val definitions = topic.sources.mapNotNull { link ->
            val page = pages[link.bookId]?.firstOrNull { it.number == link.startPage } ?: return@mapNotNull null
            val line = page.text.lineSequence().firstOrNull(assignment::matches) ?: return@mapNotNull null
            val match = assignment.matchEntire(line) ?: return@mapNotNull null
            val offset = page.text.indexOf(line)
            Definition(link, match.groupValues[1].toBigDecimal(), match.groupValues[2], CourseCitationRef(
                link.bookId, link.bookTitle, link.role, page.number, offset, offset + line.length, quote = line,
            ))
        }
        val primary = definitions.firstOrNull { it.link.role == CourseMaterialRole.CORE_TEXTBOOK } ?: return emptyList()
        return definitions.filter { it.link.role in setOf(CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT) &&
            it.unit == primary.unit && it.value.compareTo(primary.value) != 0
        }.map { other ->
            CourseDiscrepancyItem(topic.title, primary.citation.quote, other.citation.quote,
                "同一项的数值不同，按教材第 ${primary.citation.page} 页核对。", listOf(primary.citation, other.citation))
        }
    }

    fun build(db: Connection, courseId: String, materials: List<CourseMaterialEntry>, excluded: List<CourseTopicLinkPlan>): List<CourseTopicPlan> {
        val teaching = materials.filter { it.role in teachingRoles && (it.userLocked || !CourseClientGates.isPastExamMaterial(it.title, it.sourceFilename)) }
            .sortedBy { it.role.authorityRank }
        if (teaching.size < 2) return emptyList()
        val pages = mutableListOf<ExamTeachingPage>()
        for (mat in teaching) {
            val excludedRanges = excluded.filter { it.bookId == mat.bookId }.map { it.startPage..it.endPage }
            val first = CourseStore.loadCorrectedBodyPage(db, mat.bookId) ?: 1
            for (page in CourseStore.loadPagesForBook(db, mat.bookId, mat.pageCount)) {
                if (page.number < first || page.ocrPending || excludedRanges.any { page.number in it }) continue
                pages += ExamTeachingPage(mat.bookId, page.number, mat.role, page.text)
            }
        }
        return identifyFromPages(courseId, pages)
    }

    /**
     * Headings that the textbook itself defines, or numbers as a section, stay even when
     * the slides never repeat the name. A bare first line still needs a second book.
     */
    fun identifyFromPages(courseId: String, pages: List<ExamTeachingPage>): List<CourseTopicPlan> {
        val titles = linkedMapOf<String, String>()
        val textbookStrong = mutableSetOf<String>()
        val indexed = mutableListOf<ExamTeachingPage>()
        for (page in pages) {
            if (page.role !in teachingRoles || page.text.isBlank()) continue
            val lines = page.text.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !PRINTED_PAGE_MARK.matches(it) }
            for ((index, line) in lines.withIndex()) {
                val defined = definition.containsMatchIn(line)
                val markdown = line.startsWith("#")
                val numbered = sectionNumber.containsMatchIn(line)
                val heading = when {
                    markdown -> line.trimStart('#', ' ')
                    defined -> definition.find(line)!!.groupValues[1]
                    numbered || index == 0 -> line
                    else -> continue
                }.replace(sectionNumber, "").trim()
                if (heading.length !in 2..64 || heading.any { it in "。！？!?；;." } || heading.none(Char::isLetter)) continue
                val key = normalize(heading)
                if (key.length < 2 || key in boilerplate) continue
                titles.putIfAbsent(key, heading)
                if (page.role == CourseMaterialRole.CORE_TEXTBOOK && (defined || numbered || markdown)) textbookStrong += key
            }
            indexed += page.copy(text = normalize(page.text))
        }
        return titles.mapNotNull { (key, title) ->
            val pattern = termPattern(key)
            val sources = indexed.filter { pattern.containsMatchIn(it.text) }.map {
                CourseTopicLinkPlan(it.bookId, startPage = it.page, endPage = it.page, role = it.role)
            }
            val textbookBacked = key in textbookStrong && sources.any { it.role == CourseMaterialRole.CORE_TEXTBOOK }
            // A name that is only a slide title stays on its weekly topic. A textbook definition does not.
            if (sources.distinctBy { it.bookId }.size < 2 && !textbookBacked) return@mapNotNull null
            val id = UUID.nameUUIDFromBytes("$courseId|point|$key".toByteArray(Charsets.UTF_8))
            CourseTopicPlan("point-$id", LABEL, null, 0, title, CourseTopicStatus.EXAMINABLE, sources = sources)
        }.mapIndexed { index, topic -> topic.copy(ord = index + 1) }
    }
}

internal data class ExamTeachingPage(
    val bookId: String,
    val page: Int,
    val role: CourseMaterialRole,
    val text: String,
)
