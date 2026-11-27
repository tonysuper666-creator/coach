package com.coach.desktop.books

import java.io.StringWriter
import java.text.Normalizer
import kotlin.math.abs

internal data class LiteraturePaperMeta(
    val bookId: String,
    val title: String,
    val author: String = "",
    val year: String = "",
)

internal object LiteratureTextNorm {
    const val OCR_SIMILARITY_THRESHOLD = 0.98
    private val ligature = Regex("[\uFB00-\uFB06]")
    private val lineHyphen = Regex("""(?<=\p{L})[-\u2010\u2011]\s+(?=\p{Ll})""")
    private val whitespace = Regex("""\s+""")
    private val spaceBeforePunct = Regex("""\s+([,.;:!?])""")
    private val spacedDash = Regex("""\s*-\s*""")
    private val entity = Regex("""&(?:#(\d{1,7})|#x([0-9a-fA-F]{1,6})|([a-zA-Z][a-zA-Z0-9]{1,15}));""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "hellip" to "\u2026", "copy" to "\u00A9",
        "reg" to "\u00AE", "trade" to "\u2122", "times" to "\u00D7", "middot" to "\u00B7",
        "sect" to "\u00A7", "eacute" to "\u00E9", "egrave" to "\u00E8", "aacute" to "\u00E1",
        "agrave" to "\u00E0", "oacute" to "\u00F3", "uacute" to "\u00FA", "uuml" to "\u00FC",
        "ouml" to "\u00F6", "auml" to "\u00E4", "ccedil" to "\u00E7", "ntilde" to "\u00F1",
        "szlig" to "\u00DF",
    )

    fun decodeHtmlEntities(raw: String): String {
        var current = raw
        repeat(2) {
            val next = entity.replace(current) { match ->
                val dec = match.groupValues[1]
                val hex = match.groupValues[2]
                val name = match.groupValues[3]
                when {
                    dec.isNotEmpty() -> dec.toIntOrNull()?.takeIf { it in 0x20..0x10FFFF }
                        ?.let { String(Character.toChars(it)) } ?: match.value
                    hex.isNotEmpty() -> hex.toIntOrNull(16)?.takeIf { it in 0x20..0x10FFFF }
                        ?.let { String(Character.toChars(it)) } ?: match.value
                    else -> namedEntities[name] ?: namedEntities[name.lowercase()] ?: match.value
                }
            }
            if (next == current) return current.replace(whitespace, " ").trim()
            current = next
        }
        return current.replace(whitespace, " ").trim()
    }

    /** Normalize whitespace, line-break hyphens, ligatures, historical long-s, and typographic punctuation. */
    fun normalize(s: String): String = s
        .replace("\u00AD", "")
        .replace(ligature) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
        .replace(lineHyphen, "")
        .replace('\u017F', 's')
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace('\u02BC', '\'')
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u2010', '-')
        .replace('\u2011', '-')
        .replace('\u2012', '-')
        .replace('\u2013', '-')
        .replace('\u2014', '-')
        .replace('\u2015', '-')
        .replace('\uFF0C', ',')
        .replace('\uFF0E', '.')
        .replace('\uFF1B', ';')
        .replace('\uFF1A', ':')
        .replace('\uFF08', '(')
        .replace('\uFF09', ')')
        .replace(whitespace, " ")
        .replace(spaceBeforePunct, "$1")
        .replace(spacedDash, "-")
        .trim()

    /**
     * Best normalized Levenshtein similarity between [quote] and any contiguous substring of [ocrPageText].
     * Uses Sellers' semi-global dynamic programming in O(|needle| * |haystack|).
     */
    fun bestContiguousSimilarity(quote: String, ocrPageText: String): Double {
        val needle = normalize(quote)
        val haystack = normalize(ocrPageText)
        val m = needle.length
        val n = haystack.length
        if (m == 0 || n == 0) return 0.0
        if (needle in haystack) return 1.0
        var prevDist = IntArray(n + 1) { 0 }
        var currDist = IntArray(n + 1)
        var prevStart = IntArray(n + 1) { it }
        var currStart = IntArray(n + 1)
        for (i in 1..m) {
            val nc = needle[i - 1]
            currDist[0] = i
            currStart[0] = 0
            for (j in 1..n) {
                val subCost = prevDist[j - 1] + if (nc == haystack[j - 1]) 0 else 1
                val delCost = prevDist[j] + 1
                val insCost = currDist[j - 1] + 1
                if (subCost <= delCost && subCost <= insCost) {
                    currDist[j] = subCost
                    currStart[j] = prevStart[j - 1]
                } else if (delCost <= insCost) {
                    currDist[j] = delCost
                    currStart[j] = prevStart[j]
                } else {
                    currDist[j] = insCost
                    currStart[j] = currStart[j - 1]
                }
            }
            val tmpD = prevDist; prevDist = currDist; currDist = tmpD
            val tmpS = prevStart; prevStart = currStart; currStart = tmpS
        }
        var best = 0.0
        for (j in 1..n) {
            val matchedLen = j - prevStart[j]
            val denom = maxOf(m, matchedLen)
            if (denom > 0) {
                val sim = 1.0 - prevDist[j].toDouble() / denom.toDouble()
                if (sim > best) best = sim
            }
        }
        return best.coerceIn(0.0, 1.0)
    }
}

/** Shelf, reader, library, and export show the characters the title meant, not the HTML escapes. */
internal fun visibleBookTitle(raw: String): String {
    if (raw.isBlank() || '&' !in raw) return raw
    return LiteratureTextNorm.decodeHtmlEntities(raw)
}

internal object LiteratureMetadata {
    private val junkTitle = Regex(
        """(?i)^(?:untitled(?:\s+document)?|document\d*|book\d*|slide\d*|microsoft word.*|powerpoint.*|acrobat.*|indesign.*|latex|tex|dvips|scan\d*|page\s*\d+|doi[:/\s].*|\d+|none|null|nil|n/?a|tbd|todo|draft|sample|test|placeholder|no\s+title|(?:some|your|my|the|default|sample|test|dummy|temp|placeholder|insert|enter|add|unknown|generic|example|book|article|chapter|document|paper)[\s._\-]*title)$""",
    )
    private val nonTitleLine = Regex(
        """(?i)\b(?:isbn|issn|doi|copyright|all rights reserved|fair dealing|fair use|coursepack|course pack|course reading|university library|digitisation|digitization|licen[cs]e|downloaded from|stable url|published by|permission of|in progress|editor in chief|reference article)\b|https?://|©""",
    )
    private val junkAuthor = Regex(
        """(?i)^(?:admin(?:istrator)?|user|unknown|owner|author|jstor|windows user|macbook|hp|dell|lenovo|default|system|pdf|scanner|editor|editorial|staff|anonymous|none|null|nil|n/?a|tbd|todo|placeholder|no\s+author|publisher|adobe|acrobat|indesign|latex|springer|elsevier|wiley|routledge|proquest|ebsco|oxford|cambridge|taylor\s*&\s*francis|(?:some|your|my|the|default|sample|test|dummy|temp|placeholder|insert|enter|add|unknown|generic|example|book|article|chapter|document|paper)[\s._\-]*(?:author|name|writer|creator)s?)$""",
    )
    private val lowerCamelPlaceholder = Regex("""^[a-z]+[A-Z][a-zA-Z0-9]*$""")
    private val nonSurnameLead = setOf(
        "the", "a", "an", "on", "in", "of", "notes", "paper", "article", "chapter", "week", "reading",
        "course", "study", "history", "science", "scientific", "introduction", "review", "report",
        "journal", "book", "lecture", "slides", "extract", "excerpt", "collection", "archive", "guide",
        "coursepack", "document", "scan", "unit", "module", "section", "part", "appendix", "syllabus",
    )
    private val coursePrefix = Regex("""(?i)^(?:(?:[A-Z]{2,6}\d{3,6}|W\d{1,2}|week\s*\d{1,2}|reading\s*\d{1,2}|\d{1,2})[\s._\-–—:]+)+""")
    private val yearToken = Regex("""(?<!\d)(1[89]\d{2}|20\d{2})(?!\d)""")
    private val anyYearToken = Regex("""(?<!\d)(1[5-9]\d{2}|20\d{2})(?!\d)""")
    private val jstorAuthor = Regex("""(?im)^(?:Book\s+|Chapter\s+|Article\s+)?Author(?:\(s\))?\s*[:：]\s*(.+)$""")
    private val multilineAuthorLabel = Regex("""(?im)^(?:Book\s+|Chapter\s+|Article\s+)?Author(?:\(s\))?\s*[:：]\s*$""")
    private val multilineTitleLabel = Regex("""(?im)^(?:Book\s+|Chapter\s+|Article\s+)?Title\s*[:：]\s*$""")
    private val explicitTitleLine = Regex("""(?im)^(?:Book\s+|Chapter\s+|Article\s+)?Title\s*[:：]\s*(.+)$""")
    private val explicitYearLine = Regex("""(?im)^(?:Pub\.?\s*year|Publication\s+Date|Source|Published|Date|Year)\s*[:：].*?(?<!\d)(1[89]\d{2}|20\d{2})(?!\d)""")
    private val downloadNoiseLine = Regex("""(?i)downloaded|accessed|printed on|retrieved|terms of use|stable url|copyright|©""")
    private val coverAuthor = Regex("""(?im)^(?:Author(?:\(s\))?|By)\s*[:：]\s*(.+)$""")
    private val byLine = Regex("""(?m)^\s*(?:By|BY)\s+([A-Z][\p{L}.'\-]+(?:\s+(?:da|de|del|van|von|la|di|[A-Z][\p{L}.'\-]+)){1,4})\s*$""")
    private val affiliationCue = Regex("""(?i)\b(?:university|college|institute|department|school of|academy|museum|abstract|keywords|summary)\b""")
    private val personNameLine = Regex("""^([A-Z][\p{L}.'\-]+(?:\s+(?:da|de|del|van|von|la|di|[A-Z]\.?|[A-Z][\p{L}.'\-]+)){1,4})$""")
    private val filenameAuthorYear = Regex("""^([A-Za-z\u00C0-\u024F][\p{L}.'\- ]{1,48}?)\s*(?:\(|,\s*|\s+)(1[89]\d{2}|20\d{2})\)?(?:\s*[-–—:,]\s*(.+))?$""")
    private val filenameYearAuthor = Regex("""^(1[89]\d{2}|20\d{2})\s*[-–—_\s]+([A-Za-z\u00C0-\u024F][\p{L}.'\- ]{1,48}?)(?:\s*[-–—:,]\s*(.+))?$""")
    private val filenameDashAuthor = Regex("""^([A-Za-z\u00C0-\u024F][\p{L}.'\- ]{1,36}?)\s+[-–—]\s+(.+)$""")
    private val parenYear = Regex("""\((?:[^()]{0,30},?\s*)?(1[89]\d{2}|20\d{2})\)""")

    fun extract(
        pdf: BookPdfDocument?,
        bookId: String,
        storedTitle: String,
        sourceFilename: String?,
        firstPagesText: List<String>,
    ): LiteraturePaperMeta {
        val info = runCatching { pdf?.documentInformation }.getOrNull()
        val pdfTitle = info?.title?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulTitle(it) }
        val pdfAuthor = info?.author?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulAuthor(it) }
        val pdfMetaYears = buildSet {
            listOfNotNull(info?.subject, info?.keywords, info?.title).forEach { s ->
                yearToken.findAll(s).forEach { add(it.groupValues[1]) }
            }
        }
        val pdfCreationYear = runCatching { info?.creationDate?.get(java.util.Calendar.YEAR) }
            .getOrNull()?.takeIf { it in 1800..2099 }?.toString()

        val rawStem = (sourceFilename ?: storedTitle)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""(?i)\.(?:pdf|epub)$"""), "")
            .trim()
        val fileStem = rawStem.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        val strippedStem = fileStem.replace(coursePrefix, "").trim().ifEmpty { fileStem }

        var fileAuthor: String? = null
        var fileYear: String? = null
        var fileTitle: String? = null
        filenameAuthorYear.matchEntire(strippedStem)?.let { m ->
            fileAuthor = m.groupValues[1].trim().takeIf { isUsefulAuthor(it) }
            fileYear = m.groupValues[2]
            fileTitle = m.groupValues[3].trim().takeIf { isUsefulTitle(it) }
        } ?: filenameYearAuthor.matchEntire(strippedStem)?.let { m ->
            fileYear = m.groupValues[1]
            fileAuthor = m.groupValues[2].trim().takeIf { isUsefulAuthor(it) }
            fileTitle = m.groupValues[3].trim().takeIf { isUsefulTitle(it) }
        } ?: filenameDashAuthor.matchEntire(strippedStem)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            if (left.split(' ').size in 1..4 && isUsefulAuthor(left)) {
                fileAuthor = left
                fileYear = yearToken.find(right)?.groupValues?.get(1)
                fileTitle = right.replace(parenYear, "").trim().takeIf { isUsefulTitle(it) }
            }
        }
        if (fileYear == null) {
            fileYear = yearToken.findAll(strippedStem).lastOrNull()?.groupValues?.get(1)
                ?: anyYearToken.findAll(strippedStem).lastOrNull()?.groupValues?.get(1)
        }
        // Hyphen-separated academic slug, e.g. "Hunter-Between-Cabinet-...-1989.pdf" or "Marples-Scientific-...-2019.pdf".
        if (fileAuthor == null) {
            val slugTokens = rawStem.replace(coursePrefix, "").split('-').map(String::trim).filter(String::isNotEmpty)
            if (slugTokens.size >= 3) {
                val lead = slugTokens.first()
                if (lead.length in 3..28 && lead.first().isUpperCase() && lead.all { it.isLetter() || it == '\'' } &&
                    lead.lowercase() !in nonSurnameLead && isUsefulAuthor(lead)
                ) {
                    // Handle multi-word noble/compound surnames like "Fontes-da-Costa".
                    val compound = if (slugTokens.size >= 4 && slugTokens[1].lowercase() in setOf("da", "de", "del", "van", "von", "la", "di") &&
                        slugTokens[2].first().isUpperCase()
                    ) "${slugTokens[0]} ${slugTokens[1]} ${slugTokens[2]}" else lead
                    fileAuthor = compound
                }
            }
        }

        val page1 = firstPagesText.firstOrNull().orEmpty()
        val frontText = firstPagesText.take(2).joinToString("\n")
        val frontLines = page1.lines().map(String::trim).filter(String::isNotEmpty)

        val labeledAuthor = extractLabeledAuthor(page1, frontLines)
        val plainAuthor = extractPlainHeaderAuthor(frontLines)
        val expandedFileAuthor = fileAuthor?.let { surname -> expandSurnameFromText(surname, frontText) ?: surname }
        val pageAuthor = labeledAuthor ?: plainAuthor

        val pageTitle = extractFrontPageTitle(page1, frontLines)?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulTitle(it) }

        val finalYear = resolveConsensusYear(
            fileYear = fileYear,
            page1 = page1,
            frontText = frontText,
            pdfMetaYears = pdfMetaYears,
            pdfCreationYear = pdfCreationYear,
        )

        val decodedStored = LiteratureTextNorm.decodeHtmlEntities(storedTitle).takeIf { isUsefulTitle(it) }
        val finalTitle = (pdfTitle ?: pageTitle ?: fileTitle ?: decodedStored ?: strippedStem).take(180)
        val finalAuthor = (pageAuthor ?: pdfAuthor ?: expandedFileAuthor).orEmpty().take(80)
        return LiteraturePaperMeta(bookId, finalTitle, finalAuthor, finalYear)
    }

    private fun extractLabeledAuthor(page1: String, lines: List<String>): String? {
        jstorAuthor.find(page1)?.groupValues?.get(1)?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulAuthor(it) }?.let { return it }
        val mlIdx = lines.indexOfFirst { multilineAuthorLabel.matches(it) }
        if (mlIdx >= 0 && mlIdx + 1 < lines.size) {
            val next = LiteratureTextNorm.decodeHtmlEntities(lines[mlIdx + 1])
            if (isUsefulAuthor(next)) return next
        }
        coverAuthor.find(page1)?.groupValues?.get(1)?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulAuthor(it) }?.let { return it }
        byLine.find(page1.take(1500))?.groupValues?.get(1)?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulAuthor(it) }?.let { return it }
        return null
    }

    private fun extractPlainHeaderAuthor(lines: List<String>): String? {
        val header = lines.take(16)
        for (i in header.indices) {
            val line = header[i]
            if (nonTitleLine.containsMatchIn(line) || affiliationCue.containsMatchIn(line)) continue
            if (!personNameLine.matches(line)) continue
            val words = line.split(' ')
            if (words.size !in 2..4) continue
            val next = header.getOrNull(i + 1).orEmpty()
            val next2 = header.getOrNull(i + 2).orEmpty()
            if (affiliationCue.containsMatchIn(next) || affiliationCue.containsMatchIn(next2)) {
                val decoded = LiteratureTextNorm.decodeHtmlEntities(line)
                if (isUsefulAuthor(decoded)) return decoded
            }
        }
        return null
    }

    private fun expandSurnameFromText(surname: String, frontText: String): String? {
        val escaped = Regex.escape(surname)
        // Match "FirstName [M.] Surname" in the first 2500 chars of front matter.
        val firstLast = Regex("""\b([A-Z][\p{L}.'\-]{1,20}(?:\s+[A-Z]\.?)?\s+$escaped)\b""")
        firstLast.find(frontText.take(2500))?.groupValues?.get(1)?.trim()
            ?.takeIf { isUsefulAuthor(it) && !nonTitleLine.containsMatchIn(it) }?.let { return it }
        val lastFirst = Regex("""\b($escaped,\s*[A-Z][\p{L}.]{0,20})\b""")
        lastFirst.find(frontText.take(2500))?.groupValues?.get(1)?.trim()
            ?.takeIf { isUsefulAuthor(it) }?.let { return it }
        return null
    }

    private fun extractFrontPageTitle(page1: String, lines: List<String>): String? {
        explicitTitleLine.find(page1)?.groupValues?.get(1)?.trim()?.takeIf { isUsefulTitle(it) }?.let { return it }
        val mlIdx = lines.indexOfFirst { multilineTitleLabel.matches(it) }
        if (mlIdx >= 0 && mlIdx + 1 < lines.size) {
            val first = lines[mlIdx + 1]
            val second = lines.getOrNull(mlIdx + 2)?.takeIf {
                !it.contains(':') && !multilineAuthorLabel.matches(it) && !nonTitleLine.containsMatchIn(it) && it.length <= 60
            }
            val joined = listOfNotNull(first, second).joinToString(" ").trim()
            if (isUsefulTitle(joined)) return joined
        }
        val refArtIdx = lines.indexOfFirst { it.equals("REFERENCE ARTICLE", true) }
        if (refArtIdx >= 0 && refArtIdx + 1 < lines.size) {
            val cand = lines[refArtIdx + 1].trim()
            if (isUsefulTitle(cand)) return cand
        }
        val authorIdx = lines.indexOfFirst { jstorAuthor.matches(it) }
        if (authorIdx > 0) {
            val candidate = lines[authorIdx - 1]
            if (candidate.length in 6..180 && isUsefulTitle(candidate)) return candidate
        }
        return null
    }

    private fun resolveConsensusYear(
        fileYear: String?,
        page1: String,
        frontText: String,
        pdfMetaYears: Set<String>,
        pdfCreationYear: String?,
    ): String {
        val cleanPageLines = frontText.take(3000).lines().filterNot { downloadNoiseLine.containsMatchIn(it) }
        val cleanFront = cleanPageLines.joinToString("\n")
        val pageYears = yearToken.findAll(cleanFront).map { it.groupValues[1] }.toSet()
        val explicitPageYear = explicitYearLine.find(page1)?.groupValues?.get(1)
            ?: parenYear.find(cleanFront.take(1800))?.groupValues?.get(1)
        val metaAll = pdfMetaYears + listOfNotNull(pdfCreationYear)

        // 1. Consensus involving filename year (filename agrees with front page or PDF metadata).
        if (fileYear != null && (fileYear in pageYears || fileYear in metaAll)) return fileYear
        // 2. Consensus between front page explicit year and PDF metadata.
        if (explicitPageYear != null && explicitPageYear in metaAll) return explicitPageYear
        // 3. Any year shared between front page and PDF metadata (excluding scan dates if explicit exists).
        val pageMetaConsensus = pageYears.firstOrNull { it in pdfMetaYears }
        if (pageMetaConsensus != null) return pageMetaConsensus
        // 4. Fallback priority: filename year -> explicit front-page publication year -> PDF metadata -> first front-page year.
        return (fileYear ?: explicitPageYear ?: pdfMetaYears.firstOrNull() ?: pageYears.firstOrNull()).orEmpty().take(10)
    }

    private fun isUsefulTitle(s: String): Boolean {
        val clean = s.trim()
        if (clean.length !in 3..220) return false
        if (lowerCamelPlaceholder.matches(clean)) return false
        return !junkTitle.matches(clean) && !nonTitleLine.containsMatchIn(clean) && !clean.endsWith(".pdf", true)
    }

    private fun isUsefulAuthor(s: String): Boolean {
        val clean = s.trim()
        if (clean.length !in 2..80) return false
        if (lowerCamelPlaceholder.matches(clean)) return false
        if (junkAuthor.matches(clean)) return false
        if (Regex("""(?i)^(?:HIST|ECON|COMP|MATH|WEEK|W\d|CH\d|CHAPTER|MODULE|LECTURE)\d*""").matches(clean)) return false
        return clean.any(Char::isLetter)
    }
}

/** Each region is read down one column of the original PDF, independently of the saved reader text. */
internal data class GuideRegion(
    val text: String,
    val printedPage: String? = null,
    val sourceOrder: String = text,
    val wideGapRanges: List<IntRange> = emptyList(),
) {
    private val continuous by lazy { LiteratureTextNorm.normalize(sourceOrder) }
    fun containsSentence(quote: String) = LiteratureTextNorm.normalize(quote) in continuous
    fun overlapsWideGap(start: Int, end: Int): Boolean = wideGapRanges.any { r -> r.first < end && start <= r.last }
}

internal object LiteraturePdfLayout {
    private data class Word(val text: String, val x: Float, val right: Float, val y: Float, val height: Float)
    private data class Row(val y: Float, val words: MutableList<Word> = mutableListOf())
    private val folio = Regex("""(?i)^(?:p(?:age)?\.?\s*)?(\d{1,4}|[ivxlcdm]{1,6})$""")

    private fun insideCropBox(position: BookPdfTextPosition, crop: BookPdfRectangle): Boolean {
        val tm = position.textMatrix
        val tx = tm.translateX
        val ty = tm.translateY
        val tolX = 3f
        val tolY = maxOf(4f, position.heightDir * 0.6f)
        return tx >= crop.lowerLeftX - tolX && tx <= crop.upperRightX + tolX &&
            ty >= crop.lowerLeftY - tolY && ty <= crop.upperRightY + tolY
    }

    fun extract(pdf: BookPdfDocument, page: Int): List<GuideRegion> {
        val pdfPage = pdf.getPage(page - 1)
        val crop = pdfPage.cropBox
        val words = mutableListOf<Word>()
        val stripper = object : BookPdfStripper() {
            override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
                var start = 0f
                var end = 0f
                var y = 0f
                var height = 0f
                val token = StringBuilder()
                fun flush() {
                    if (token.isNotEmpty() && token.any { !it.isWhitespace() }) {
                        words += Word(token.toString(), start, end, y, height)
                    }
                    token.clear()
                }
                for (glyph in textPositions) {
                    if (!insideCropBox(glyph, crop)) {
                        flush()
                        continue
                    }
                    val u = glyph.unicode
                    if (u.isNullOrEmpty() || u.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
                        flush()
                        continue
                    }
                    val x = glyph.xDirAdj
                    val h = glyph.heightDir.takeIf { it > 0f } ?: glyph.fontSizeInPt.coerceAtLeast(1f)
                    if (token.isNotEmpty() && x - end > glyph.widthOfSpace.coerceAtLeast(1f) * 0.6f) flush()
                    if (token.isEmpty()) {
                        start = x
                        y = glyph.yDirAdj
                        height = h
                    } else if (h > height) {
                        height = h
                    }
                    token.append(u)
                    end = x + glyph.widthDirAdj
                }
                flush()
            }
        }
        stripper.startPage = page
        stripper.endPage = page
        stripper.sortByPosition = true
        stripper.writeText(pdf, StringWriter())
        if (words.isEmpty()) return emptyList()

        val visibleMinX = words.minOf { it.x }
        val visibleMaxRight = words.maxOf { it.right }
        val spanWidth = (visibleMaxRight - visibleMinX).coerceAtLeast(1f)

        val rows = mutableListOf<Row>()
        for (w in words.sortedBy { it.y }) {
            val row = rows.lastOrNull()?.takeIf { abs(it.y - w.y) <= maxOf(1.5f, w.height * 0.35f) }
                ?: Row(w.y).also(rows::add)
            row.words += w
        }
        rows.forEach { it.words.sortBy(Word::x) }
        val positiveHeights = words.map { it.height }.filter { it > 0f }.sorted()
        val bodyHeight = if (positiveHeights.isNotEmpty()) positiveHeights[positiveHeights.size / 2] else 10f

        // Search vertical gutter across the actual visible word span (handles both cropped and uncropped scans).
        val gutter = (12..88).map { visibleMinX + spanWidth * it / 100f }.mapNotNull { cut ->
            val halfGap = maxOf(6f, spanWidth * 0.012f)
            val left = words.filter { it.right <= cut - halfGap }
            val right = words.filter { it.x >= cut + halfGap }
            if (left.size < 3 || right.size < 3) return@mapNotNull null
            val overlapTop = maxOf(left.minOf { it.y }, right.minOf { it.y })
            val overlapBottom = minOf(left.maxOf { it.y }, right.maxOf { it.y })
            if (overlapBottom - overlapTop < bodyHeight * 2) return@mapNotNull null
            val overlapping = rows.filter { it.y in (overlapTop - bodyHeight)..(overlapBottom + bodyHeight) }
            val support = minOf(
                overlapping.count { row -> row.words.any { it.right < cut } },
                overlapping.count { row -> row.words.any { it.x > cut } },
            )
            val crosses = overlapping.count { row -> row.words.any { it.right > cut - halfGap && it.x < cut + halfGap } }
            if (support < 3 || support <= crosses * 5) return@mapNotNull null
            val clearance = overlapping.map { row ->
                row.words.minOf { maxOf(it.x - cut, cut - it.right) }.coerceIn(0f, spanWidth * 0.12f)
            }.average()
            cut to (clearance * (support - crosses))
        }.maxByOrNull { it.second }?.first

        val rawColumns: List<Pair<Int, List<Word>>> = if (gutter == null) {
            listOf(0 to words)
        } else {
            val (leftCol, rightCol) = words.partition { (it.x + it.right) / 2 < gutter }
            val kept = mutableListOf<Pair<Int, List<Word>>>()
            val leftSpan = if (leftCol.isNotEmpty()) leftCol.maxOf { it.right } - leftCol.minOf { it.x } else 0f
            val rightSpan = if (rightCol.isNotEmpty()) rightCol.maxOf { it.right } - rightCol.minOf { it.x } else 0f
            // Drop clipped edge facing-page slivers (much narrower than the main column and flush against page edge).
            val leftIsEdgeSliver = leftCol.isNotEmpty() && rightCol.isNotEmpty() &&
                leftSpan < rightSpan * 0.48f && leftCol.size < rightCol.size * 0.55 &&
                leftCol.minOf { it.x } <= visibleMinX + bodyHeight * 1.5f
            val rightIsEdgeSliver = leftCol.isNotEmpty() && rightCol.isNotEmpty() &&
                rightSpan < leftSpan * 0.48f && rightCol.size < leftCol.size * 0.55 &&
                rightCol.maxOf { it.right } >= visibleMaxRight - bodyHeight * 1.5f
            if (leftCol.isNotEmpty() && !leftIsEdgeSliver) kept += 0 to leftCol
            if (rightCol.isNotEmpty() && !rightIsEdgeSliver) kept += 1 to rightCol
            kept
        }

        return rawColumns.mapNotNull { (index, column) ->
            val members = column.toHashSet()
            val lines = rows.mapNotNull { row -> row.words.filter { it in members }.takeIf { it.isNotEmpty() } }
            if (lines.isEmpty()) return@mapNotNull null
            val edgeLines = (lines.take(2) + lines.takeLast(2))
            val label = edgeLines.firstNotNullOfOrNull { line ->
                folio.matchEntire(line.joinToString(" ") { it.text })?.groupValues?.get(1)
            } ?: edgeLines.firstNotNullOfOrNull { line ->
                val candidates = listOf(line.first() to line.getOrNull(1), line.last() to line.getOrNull(line.lastIndex - 1))
                candidates.firstOrNull { (w, neighbor) ->
                    folio.matches(w.text) && neighbor != null &&
                        maxOf(neighbor.x - w.right, w.x - neighbor.right) > bodyHeight * 2
                }?.first?.text
            }

            // Detect alternating horizontally-disjoint rows (interleaved unsplit facing columns).
            val disjointLine = BooleanArray(lines.size)
            for (li in 0 until lines.lastIndex) {
                val a = lines[li]
                val b = lines[li + 1]
                val aMin = a.first().x; val aMax = a.last().right
                val bMin = b.first().x; val bMax = b.last().right
                if (aMax < bMin - bodyHeight * 1.5f || bMax < aMin - bodyHeight * 1.5f) {
                    // Only flag if vertical spacing is tight (same paragraph flow), not separated paragraphs.
                    if (b.first().y - a.first().y <= bodyHeight * 2.7f) {
                        disjointLine[li] = true
                        disjointLine[li + 1] = true
                    }
                }
            }

            val wideGapRanges = mutableListOf<IntRange>()
            val text = buildString {
                var previousY: Float? = null
                for ((li, line) in lines.withIndex()) {
                    val y = line.first().y
                    val size = line.map { it.height }.sorted()[line.size / 2]
                    if (size < bodyHeight * 0.80f) {
                        if (isNotEmpty()) append("\n\n")
                        continue
                    }
                    if (previousY != null && y - previousY > bodyHeight * 2.7f && isNotEmpty()) append('\n')
                    val hasWideGap = disjointLine[li] ||
                        line.zipWithNext().any { (a, b) -> b.x - a.right > bodyHeight * 1.5f }
                    val lineStart = length
                    line.forEachIndexed { i, w ->
                        if (i > 0 && (w.x - line[i - 1].right > size * 0.10f ||
                                w.text.first().isLetterOrDigit() && line[i - 1].text.last().isLetterOrDigit() &&
                                w.x > line[i - 1].right + 0.5f)) append(' ')
                        append(if (w.text.any { it in '\uFB00'..'\uFB06' }) Normalizer.normalize(w.text, Normalizer.Form.NFKC) else w.text)
                    }
                    val lineEnd = length
                    if (hasWideGap && lineEnd > lineStart) wideGapRanges += lineStart until lineEnd
                    append('\n')
                    previousY = y
                }
            }.trimEnd()
            if (text.isBlank()) return@mapNotNull null
            val reference = object : BookPdfStripper() {
                override fun processTextPosition(position: BookPdfTextPosition) {
                    if (!insideCropBox(position, crop)) return
                    val center = position.xDirAdj + position.widthDirAdj / 2
                    if (gutter == null || (center < gutter) == (index == 0)) super.processTextPosition(position)
                }
            }.apply { startPage = page; endPage = page; sortByPosition = true }.getText(pdf)
            GuideRegion(text, label, reference, wideGapRanges)
        }
    }

    internal data class QuoteCropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Locate a contiguous, non-interleaved quote's normalized [0..1] display-space bounding box on [page]
     * for targeted 300 dpi single-block Tesseract OCR verification. Returns null if the quote spans a wide
     * intra-line gap or disjoint facing-page columns.
     */
    fun locateQuoteCropRect(pdf: BookPdfDocument, page: Int, quote: String): QuoteCropRect? {
        val needle = LiteratureTextNorm.normalize(quote)
        if (needle.length < 6) return null
        val pdfPage = pdf.getPage(page - 1)
        val crop = pdfPage.cropBox
        if (crop.width <= 0f || crop.height <= 0f) return null
        val rotation = ((pdfPage.rotation % 360) + 360) % 360

        data class PlacedWord(
            val text: String, val x: Float, val right: Float, val y: Float, val height: Float,
            val nx0: Float, val ny0: Float, val nx1: Float, val ny1: Float,
        )

        fun normPt(tx: Float, ty: Float): Pair<Float, Float> {
            val u = ((tx - crop.lowerLeftX) / crop.width).coerceIn(0f, 1f)
            val v = ((crop.upperRightY - ty) / crop.height).coerceIn(0f, 1f)
            return when (rotation) {
                90 -> (1f - v) to u
                180 -> (1f - u) to (1f - v)
                270 -> v to (1f - u)
                else -> u to v
            }
        }

        val words = mutableListOf<PlacedWord>()
        val stripper = object : BookPdfStripper() {
            override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
                var start = 0f; var end = 0f; var y = 0f; var height = 0f
                var minNx = 1f; var minNy = 1f; var maxNx = 0f; var maxNy = 0f
                val token = StringBuilder()
                fun flush() {
                    if (token.isNotEmpty() && token.any { !it.isWhitespace() }) {
                        words += PlacedWord(token.toString(), start, end, y, height, minNx, minNy, maxNx, maxNy)
                    }
                    token.clear()
                    minNx = 1f; minNy = 1f; maxNx = 0f; maxNy = 0f
                }
                for (glyph in textPositions) {
                    if (!insideCropBox(glyph, crop)) { flush(); continue }
                    val u = glyph.unicode
                    if (u.isNullOrEmpty() || u.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) { flush(); continue }
                    val x = glyph.xDirAdj
                    val h = glyph.heightDir.takeIf { it > 0f } ?: glyph.fontSizeInPt.coerceAtLeast(1f)
                    if (token.isNotEmpty() && x - end > glyph.widthOfSpace.coerceAtLeast(1f) * 0.6f) flush()
                    if (token.isEmpty()) { start = x; y = glyph.yDirAdj; height = h } else if (h > height) height = h
                    val tm = glyph.textMatrix
                    val gx = tm.translateX
                    val gy = tm.translateY
                    val gw = glyph.widthDirAdj.coerceAtLeast(1f)
                    val pts = listOf(
                        normPt(gx, gy),
                        normPt(gx + gw, gy),
                        normPt(gx, gy + h),
                        normPt(gx + gw, gy + h),
                    )
                    for ((px, py) in pts) {
                        if (px < minNx) minNx = px
                        if (py < minNy) minNy = py
                        if (px > maxNx) maxNx = px
                        if (py > maxNy) maxNy = py
                    }
                    token.append(u)
                    end = x + glyph.widthDirAdj
                }
                flush()
            }
        }
        stripper.startPage = page
        stripper.endPage = page
        stripper.sortByPosition = true
        stripper.writeText(pdf, StringWriter())
        if (words.isEmpty()) return null

        val positiveHeights = words.map { it.height }.filter { it > 0f }.sorted()
        val bodyHeight = if (positiveHeights.isNotEmpty()) positiveHeights[positiveHeights.size / 2] else 10f

        // Match needle words sequentially against normalized word stream.
        val needleTokens = needle.lowercase().split(' ').filter { it.isNotEmpty() }
        if (needleTokens.size < 3) return null
        val pageTokens = words.map { LiteratureTextNorm.normalize(it.text).lowercase() }
        val firstKey = needleTokens.first()
        val lastKey = needleTokens.last()
        var bestSpan: IntRange? = null
        var bestScore = 0.0
        for (i in pageTokens.indices) {
            if (!pageTokens[i].contains(firstKey) && !firstKey.contains(pageTokens[i])) continue
            val maxEnd = minOf(words.size, i + needleTokens.size + 8)
            val minEnd = minOf(words.size, i + maxOf(1, needleTokens.size - 4))
            for (j in minEnd..maxEnd) {
                val endTok = pageTokens[j - 1]
                if (!endTok.contains(lastKey) && !lastKey.contains(endTok)) continue
                val joined = words.subList(i, j).joinToString(" ") { it.text }
                val sim = LiteratureTextNorm.bestContiguousSimilarity(needle, joined)
                if (sim > bestScore) {
                    bestScore = sim
                    bestSpan = i until j
                    if (sim >= 0.995) break
                }
            }
            if (bestScore >= 0.995) break
        }
        val span = bestSpan ?: return null
        if (bestScore < 0.92) return null
        val matched = words.subList(span.first, span.last + 1)
        // Refuse to crop if matched words span a wide intra-line gap (> 1.5x bodyHeight).
        val matchedRows = mutableListOf<MutableList<PlacedWord>>()
        for (w in matched.sortedBy { it.y }) {
            val row = matchedRows.lastOrNull()?.takeIf { abs(it.first().y - w.y) <= maxOf(1.5f, w.height * 0.4f) }
            if (row != null) row += w else matchedRows += mutableListOf(w)
        }
        for (row in matchedRows) {
            row.sortBy { it.x }
            if (row.zipWithNext().any { (a, b) -> b.x - a.right > bodyHeight * 1.5f }) return null
        }
        val padX = (bodyHeight * 2.0f / crop.width).coerceIn(0.015f, 0.06f)
        val padY = (bodyHeight * 1.6f / crop.height).coerceIn(0.012f, 0.05f)
        val l = (matched.minOf { it.nx0 } - padX).coerceIn(0f, 1f)
        val t = (matched.minOf { it.ny0 } - padY).coerceIn(0f, 1f)
        val r = (matched.maxOf { it.nx1 } + padX).coerceIn(0f, 1f)
        val b = (matched.maxOf { it.ny1 } + padY).coerceIn(0f, 1f)
        if (r - l < 0.02f || b - t < 0.008f) return null
        return QuoteCropRect(l, t, r, b)
    }
}
