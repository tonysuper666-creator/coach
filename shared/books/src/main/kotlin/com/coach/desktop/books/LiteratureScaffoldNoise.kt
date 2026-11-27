package com.coach.desktop.books

/**
 * Drops contents lines, table titles, figure captions, publication boilerplate, and errata notes
 * before a sentence can enter the one-page guide or the station follow route.
 * A body sentence that only mentions a figure number stays.
 */
internal object LiteratureScaffoldNoise {
    private val spacedDots = Regex("""(?:\.\s{1,4}){4,}""")
    private val contentsHeading = Regex(
        """(?i)^(?:table of contents|contents|list of (?:figures|tables|illustrations|plates)|目\s*录|总\s*目|图\s*目|表\s*目)[.。:：]?$""",
    )
    private val errataHeading = Regex("""(?i)^(?:errata|erratum|勘误|更正说明)[.。:：]?$""")
    private val captionStart = Regex(
        """(?i)^(?:(?:figures?|fig\.?|tables?|illustrations?|plates?)\s+\d|图\s*\d|表\s*\d)""",
    )
    private val publicationCue = Regex(
        """(?i)openstax|creative commons|\bcc\s*by\b|rice university|openly licensed|\bisbn\b|all rights reserved|nonprofit based at|版权所有|图书在版编目|版权页""",
    )
    private val errataCue = Regex(
        """(?i)\berrata\b|\berratum\b|勘误|更正说明|correction to suggest|submit it through the link on your book page""",
    )
    private const val FRONT_MATTER_PAGE = 20

    fun rejectsSentence(raw: String): Boolean {
        val line = raw.trim()
        if (line.isEmpty()) return false
        return isLeaderLine(line) ||
            isContentsHeading(line) ||
            isErrataHeading(line) ||
            captionStart.containsMatchIn(line) ||
            publicationCue.containsMatchIn(line) ||
            errataCue.containsMatchIn(line)
    }

    /**
     * A contents page, a front-matter publication page, or an errata note page.
     * Two publisher mentions on a later chapter page are not enough to drop that page:
     * those sentences are rejected on their own, and the body stays.
     */
    fun rejectsPage(lines: List<String>, pageNumber: Int): Boolean {
        val nonblank = lines.map(String::trim).filter(String::isNotEmpty)
        if (nonblank.isEmpty()) return false
        val leaders = nonblank.count(::isLeaderLine)
        if (leaders >= 4 && leaders * 2 >= nonblank.size) return true
        if (nonblank.any(::isContentsHeading) && leaders >= 3) return true
        val boilerplate = nonblank.count { publicationCue.containsMatchIn(it) || errataCue.containsMatchIn(it) }
        if (pageNumber <= FRONT_MATTER_PAGE && boilerplate >= 2) return true
        return nonblank.any(::isErrataHeading) && boilerplate >= 1
    }

    private fun isContentsHeading(line: String) = contentsHeading.matches(line.trim())

    private fun isErrataHeading(line: String) = errataHeading.matches(line.trim())

    private fun isLeaderLine(line: String): Boolean {
        val trimmed = line.trim()
        if (spacedDots.containsMatchIn(trimmed)) return true
        if (trimmed.length < 8) return false
        val marks = trimmed.count { it == '.' || it == '·' || it == '…' || it == '．' }
        return marks >= 6 && marks * 2 >= trimmed.length
    }
}
