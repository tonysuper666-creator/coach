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
    private val affiliationCue = Regex(
        """(?i)\b(?:university|college|institute|department|school\s+of|faculty|academy|museum|laboratory|labs?\b|center|centre|hospital|clinic|foundation|council|chair|fellowship|society|association|corporation|corp\.?|inc\.?|ltd\.?|llc|technologies|research|microsoft|google|meta|openai|cifar|mila|mitacs|mbzuai|nsf|onr|abstract|keywords|index\s+terms|summary|correspondence)\b|@|https?://|arxiv\s*:""",
    )
    private val nonAuthorTerm = Regex(
        """(?i)\b(?:university|college|institute|department|school|faculty|academy|museum|laboratory|center|centre|foundation|council|chair|fellow|society|association|corporation|corp|inc|ltd|llc|technologies|research|cifar|mila|mitacs|mbzuai|arxiv|preprint|proceedings|conference|journal|workshop|symposium|transactions|optimization|learning|models?|generation|evaluation|analysis|taxonomy|questions?|preference|reinforcement|neural|network|transformer|language|simulation|model(?:ing|ling)|framework|system|method|approach|algorithm|strateg(?:y|ies)|prompting|benchmark|dataset|corpus|study|survey|review|field|exams?|quiz(?:zes)?|assessment|education(?:al)?|automated|automatic|controllable|difficulty|multiple-choice|abstract|introduction|keywords|index\s+terms|direct|large|useful)\b""",
    )
    private val allCapsAcronym = Regex("""\b(?!(?:I|II|III|IV|V|VI|JR|SR)\b)[A-Z]{2,}\b""")
    private val arxivStampLine = Regex("""(?i)^arxiv\s*:\s*\d{4}\.\d+.*$""")
    private val abstractBoundaryLine = Regex("""(?i)^abstract(?:\s*[.:—–-]|\s|$)""")
    private val emailOrUrlLine = Regex("""@|https?://|(?i)^correspondence\s*:""")
    private val bareDigitOrSymbolLine = Regex("""^[\d,;\s*†‡§]+$""")
    private val personNameLine = Regex("""^([A-Z][\p{L}.'\-]+(?:\s+(?:da|de|del|van|von|la|di|[A-Z]\.?|[A-Z][\p{L}.'\-]+)){1,4})$""")
    private val personNameToken = Regex("""^(?:[A-Z]\.(?:\s*[A-Z]\.)*|[A-Z][\p{L}'\-]+)$""")
    private val nameParticles = setOf("da", "de", "del", "della", "van", "von", "la", "le", "di", "du", "dos", "das", "al", "bin", "ibn")
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
        val pdfAuthor = info?.author?.let(LiteratureTextNorm::decodeHtmlEntities)?.let(::formatAuthorList)?.takeIf { isUsefulAuthor(it) }
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

        val academicHeader = extractAcademicFrontHeader(frontLines)
        val labeledAuthor = extractLabeledAuthor(page1, frontLines)
        val explicitPageTitle = extractFrontPageTitle(page1, frontLines)
            ?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulTitle(it) }
        val academicPageTitle = academicHeader?.first
            ?.let(LiteratureTextNorm::decodeHtmlEntities)
            ?.takeIf { isUsefulTitle(it) }
        val decodedStored = LiteratureTextNorm.decodeHtmlEntities(storedTitle).takeIf { isUsefulTitle(it) }
        val storedLooksLikeFilename = decodedStored == null ||
            decodedStored.equals(rawStem, true) ||
            decodedStored.equals(fileStem, true) ||
            decodedStored.equals(strippedStem, true) ||
            (' ' !in decodedStored && '-' in decodedStored)
        val pageTitle = explicitPageTitle ?: academicPageTitle
        val knownTitle = pdfTitle ?: explicitPageTitle ?: (if (storedLooksLikeFilename) academicPageTitle else decodedStored) ?: academicPageTitle
        val plainAuthor = (academicHeader?.second ?: extractPlainHeaderAuthor(frontLines, knownTitle))
            ?.takeIf { cand -> knownTitle == null || !titleContainsCandidate(knownTitle, cand) }
        val expandedFileAuthor = fileAuthor?.let { surname -> expandSurnameFromText(surname, frontText) ?: surname }

        val finalYear = resolveConsensusYear(
            fileYear = fileYear,
            page1 = page1,
            frontText = frontText,
            pdfMetaYears = pdfMetaYears,
            pdfCreationYear = pdfCreationYear,
        )

        val preferredStored = decodedStored.takeUnless { storedLooksLikeFilename }
        val finalTitle = (pdfTitle ?: explicitPageTitle ?: preferredStored ?: academicPageTitle ?: fileTitle ?: decodedStored ?: strippedStem).take(220)
        // Priority: explicit cover label -> embedded PDF metadata -> heuristic page-1 header -> filename fallback.
        val finalAuthor = formatAuthorList((labeledAuthor ?: pdfAuthor ?: plainAuthor ?: expandedFileAuthor).orEmpty())
        return LiteraturePaperMeta(bookId, finalTitle, finalAuthor, finalYear)
    }

    internal fun extractFrontPageAcademicTitle(page1: String): String? {
        val lines = page1.lines().map(String::trim).filter(String::isNotEmpty)
        return extractAcademicFrontHeader(lines)?.first?.let(LiteratureTextNorm::decodeHtmlEntities)?.takeIf { isUsefulTitle(it) }
    }

    private fun titleContainsCandidate(title: String, candidate: String): Boolean {
        val t = title.lowercase().replace(Regex("""\s+"""), " ").trim()
        val c = candidate.lowercase().replace(Regex("""\s+"""), " ").trim()
        return c.length >= 6 && c in t
    }

    /**
     * Extract (title, authorList) from a standard academic paper's page 1 header above Abstract.
     * Supports multi-line titles and multi-author lines with superscript affiliation digits/markers.
     */
    private fun extractAcademicFrontHeader(lines: List<String>): Pair<String?, String?>? {
        val absIdx = lines.indexOfFirst { abstractBoundaryLine.containsMatchIn(it) }
        if (absIdx !in 2..45) return null
        val header = lines.subList(0, absIdx).filterNot { line ->
            arxivStampLine.matches(line) ||
                bareDigitOrSymbolLine.matches(line) ||
                emailOrUrlLine.containsMatchIn(line) ||
                nonTitleLine.containsMatchIn(line)
        }
        if (header.size < 2) return null

        // Find the first author line (either single person or multi-author list) after at least one title line.
        var firstAuthorIdx = -1
        val parsedAuthors = mutableListOf<String>()
        for (i in 1 until header.size) {
            val names = parseAuthorLine(header[i])
            if (names != null) {
                firstAuthorIdx = i
                parsedAuthors += names
                // Collect immediately following continuation author lines (e.g., 2-3 lines of authors).
                var j = i + 1
                while (j < header.size && j <= i + 4) {
                    val more = parseAuthorLine(header[j]) ?: break
                    parsedAuthors += more
                    j++
                }
                break
            }
        }
        if (firstAuthorIdx <= 0 || parsedAuthors.isEmpty()) return null

        val titleLines = header.subList(0, firstAuthorIdx)
            .filterNot { it.length <= 3 && it.all(Char::isDigit) }
        if (titleLines.isEmpty() || titleLines.size > 5) return null
        val joinedTitle = titleLines.joinToString(" ")
            .replace(Regex("""(?<=\p{L})-\s+(?=\p{Ll})"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val title = joinedTitle.takeIf { it.length in 10..220 && isUsefulTitle(it) }
        val author = formatAuthorList(parsedAuthors.distinct().joinToString(", "))
            .takeIf { isUsefulAuthor(it) && (title == null || !titleContainsCandidate(title, it)) }
        if (title == null && author == null) return null
        return title to author
    }

    private fun parseAuthorLine(rawLine: String): List<String>? {
        val line = rawLine.trim()
        if (line.length !in 4..240) return null
        if (affiliationCue.containsMatchIn(line) || nonTitleLine.containsMatchIn(line) || nonAuthorTerm.containsMatchIn(line)) return null
        if (line.contains(':') || line.contains('?') || line.contains('!')) return null
        // Strip superscript affiliation markers attached to names (e.g., "Sabina Elkins1,2,", "Nicy Scaria1 ,")
        // while keeping author-separating commas.
        val cleaned = line
            .replace(Regex("""(?<=[\p{L}])[\d*†‡§]+(?:\s*,\s*[\d*†‡§]+)*"""), "")
            .replace(Regex("""(?:^|(?<=[,\s]))[\d*†‡§]+(?:\s*,\s*[\d*†‡§]+)*(?=[,\s]|$)"""), " ")
            .replace(Regex("""(?i)\s+and\s+"""), ", ")
            .replace(" & ", ", ")
            .replace(";", ",")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split(',').map(String::trim).filter(String::isNotEmpty)
        if (segments.isEmpty()) return null
        // If there are no commas and > 4 words, try grouping consecutive "FirstName LastName" pairs
        // (e.g., "Bang Nguyen Tingting Du Mengxia Yu Lawrence Angrave Meng Jiang").
        val candidates = if (segments.size == 1 && segments[0].split(' ').size >= 4 && segments[0].split(' ').size % 2 == 0) {
            val words = segments[0].split(' ')
            if (words.size in 4..12 && words.all { personNameToken.matches(it) && !allCapsAcronym.containsMatchIn(it) }) {
                words.chunked(2).map { it.joinToString(" ") }
            } else segments
        } else segments
        if (!candidates.all(::isSinglePersonName)) return null
        return candidates
    }

    private fun isSinglePersonName(segment: String): Boolean {
        val clean = segment.trim().removePrefix("and ").trim()
        if (clean.length !in 4..50) return false
        if (nonAuthorTerm.containsMatchIn(clean) || affiliationCue.containsMatchIn(clean)) return false
        if (allCapsAcronym.containsMatchIn(clean)) return false
        val words = clean.split(' ').filter(String::isNotEmpty)
        if (words.size !in 2..4) return false
        return words.all { w -> w.lowercase() in nameParticles || personNameToken.matches(w) } &&
            words.count { it.lowercase() !in nameParticles } >= 2
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

    private fun extractPlainHeaderAuthor(lines: List<String>, knownTitle: String? = null): String? {
        val header = lines.take(16)
        for (i in header.indices) {
            val line = header[i]
            if (nonTitleLine.containsMatchIn(line) || affiliationCue.containsMatchIn(line) || nonAuthorTerm.containsMatchIn(line)) continue
            if (allCapsAcronym.containsMatchIn(line)) continue
            if (knownTitle != null && titleContainsCandidate(knownTitle, line)) continue
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

    private fun formatAuthorList(raw: String): String {
        val normalized = raw
            .replace(Regex("""\s*;\s*"""), ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd(',', ';')
        if (normalized.length <= 240) return normalized
        val parts = normalized.split(", ").filter(String::isNotBlank)
        if (parts.size <= 1) return normalized.take(240).trimEnd()
        val kept = StringBuilder()
        for (part in parts) {
            val sep = if (kept.isEmpty()) "" else ", "
            if (kept.length + sep.length + part.length + 8 > 240) break
            kept.append(sep).append(part)
        }
        return if (kept.isEmpty()) parts.first().take(232) + " et al." else "${kept}, et al."
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

    internal fun isUsefulTitle(s: String): Boolean {
        val clean = s.trim()
        if (clean.length !in 3..220) return false
        if (lowerCamelPlaceholder.matches(clean)) return false
        return !junkTitle.matches(clean) && !nonTitleLine.containsMatchIn(clean) && !clean.endsWith(".pdf", true)
    }

    private fun isUsefulAuthor(s: String): Boolean {
        val clean = s.trim()
        if (clean.length !in 2..400) return false
        if (lowerCamelPlaceholder.matches(clean)) return false
        if (junkAuthor.matches(clean)) return false
        if (nonAuthorTerm.containsMatchIn(clean)) return false
        if (emailOrUrlLine.containsMatchIn(clean)) return false
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
    /** UTF-16 spans selected from physical note lines before reading-order reflow. */
    val footnoteRanges: List<IntRange> = emptyList(),
    val measured: Boolean = false,
    /** Local exclusive word ends where the scan resolves ambiguous OCR punctuation. */
    val scannedStops: Set<Int> = emptySet(),
    val raisedCallouts: Set<Int> = emptySet(),
    /** PDF content-stream order can retain inline insertions that baseline sorting moves. */
    val streamOrder: String? = null,
) {
    private val continuous by lazy { LiteratureTextNorm.normalize(sourceOrder) }
    private val continuousStream by lazy { streamOrder?.let(LiteratureTextNorm::normalize) }
    fun containsSentence(quote: String): Boolean {
        val needle = LiteratureTextNorm.normalize(quote)
        return needle in continuous || continuousStream?.contains(needle) == true
    }
    fun overlapsWideGap(start: Int, end: Int): Boolean = wideGapRanges.any { r -> r.first < end && start <= r.last }
    fun overlapsFootnote(start: Int, end: Int): Boolean =
        footnoteRanges.any { start < it.last + 1 && end > it.first }
}

internal object LiteraturePdfLayout {
    private data class Word(val text: String, val x: Float, val right: Float, val y: Float, val height: Float, val pointSize: Float)
    private data class Row(val y: Float, val words: MutableList<Word> = mutableListOf())
    private data class DrawnRule(val displayY: Float, val minX: Float, val maxX: Float)
    private data class PageGraphics(val rules: List<DrawnRule> = emptyList(), val textBehindScan: Boolean = false)
    private data class RuleSub(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
    private val folio = Regex("""(?i)^(?:p(?:age)?\.?\s*)?(\d{1,4}|[ivxlcdm]{1,6})$""")
    private val textRule = Regex("""^[_\-=–—―\s]{4,}$""")

    /**
     * One measured line. [start] and [end] are offsets in the column text; a rule or a skipped
     * glyph has [start] == [end]. [y] grows downward, in the same space as PDFBox yDirAdj.
     */
    internal data class FootnoteLine(
        val y: Float,
        val size: Float,
        val start: Int,
        val end: Int,
        val rule: Boolean = false,
    )

    /**
     * Note zone: lines below a separator that are smaller than the body.
     * A separator is a drawn rule or a text rule. A gap, or sitting near the bottom, is not one.
     * Body above the rule stays body, including a block that is smaller than a heading.
     * A long note can start above the midline when the rule does. A short body-sized folio is not the zone.
     */
    internal fun layoutFootnoteRanges(
        lines: List<FootnoteLine>,
        bodyHeight: Float,
        separatorYs: List<Float> = emptyList(),
    ): List<IntRange> {
        if (bodyHeight <= 0f || lines.isEmpty()) return emptyList()
        if (lines.count { !it.rule && it.end > it.start } < 2) return emptyList()
        val top = lines.minOf { it.y }
        val span = lines.maxOf { it.y } - top
        if (span < bodyHeight * 4f) return emptyList()
        val readingSize = readingBodySize(lines, bodyHeight)
        val smallerThanBody = readingSize * 0.92f
        val margin = readingSize * 0.1f
        fun content(line: FootnoteLine) = !line.rule && line.end > line.start
        fun folio(line: FootnoteLine) = line.size >= smallerThanBody && line.end - line.start <= 8
        // Body must sit above the rule, and every content line below it must be smaller
        // (a short body-sized folio does not count). A rule with body under it is a heading rule.
        fun notesOnlyBelow(y: Float): Boolean {
            var seenSmall = false
            for (line in lines) {
                if (!content(line) || line.y <= y + margin) continue
                if (folio(line)) continue
                if (line.size < smallerThanBody) {
                    seenSmall = true
                    continue
                }
                return false
            }
            return seenSmall
        }
        val ruleYs = ArrayList<Float>(separatorYs.size + lines.size)
        ruleYs.addAll(separatorYs)
        for (line in lines) if (line.rule) ruleYs.add(line.y)
        val ruleY = ruleYs.filter { y ->
            val aboveBody = lines.any { line ->
                content(line) && line.y < y - margin && line.size >= smallerThanBody
            }
            aboveBody && notesOnlyBelow(y)
        }.minOrNull() ?: return emptyList()
        val ranges = mutableListOf<IntRange>()
        for (line in lines) {
            if (!content(line) || line.y <= ruleY + margin) continue
            if (line.size >= smallerThanBody) continue
            ranges += line.start until line.end
        }
        return ranges
    }

    /**
     * Size of the reading text. The most common size in the upper part of the column,
     * so a few larger headings do not become the body and a long note run does not either.
     * [fallback] is the column median, used when the upper part is too small to judge.
     */
    internal fun readingBodySize(lines: List<FootnoteLine>, fallback: Float): Float {
        val content = lines.filter { !it.rule && it.end > it.start && it.size > 0f }
        if (content.size < 4 || fallback <= 0f) return fallback
        val top = content.minOf { it.y }
        val span = content.maxOf { it.y } - top
        if (span < fallback * 4f) return fallback
        val cut = top + span * 0.60f
        val upper = content.filter { it.y <= cut }
        val sample = if (upper.size >= 4) upper else content
        val best = sample.groupBy { kotlin.math.round(it.size * 2f).toInt() }
            .maxWithOrNull(compareBy<Map.Entry<Int, List<FootnoteLine>>> { it.value.size }.thenBy { it.key })
            ?: return fallback
        val size = best.key / 2f
        if (size < fallback * 0.75f || size > fallback * 1.60f) return fallback
        return size
    }

    /**
     * Size used to decide "smaller than the body". Long footnotes can outnumber body glyphs, and
     * the column median then becomes the note size, so the note no longer looks smaller.
     * The upper half of the column supplies a slightly larger size when it has one.
     */
    internal fun proseBodyHeight(glyphs: List<Pair<Float, Float>>, columnMedian: Float): Float {
        if (columnMedian <= 0f || glyphs.size < 8) return columnMedian
        val top = glyphs.minOf { it.first }
        val span = glyphs.maxOf { it.first } - top
        if (span < columnMedian * 4f) return columnMedian
        val upperCut = top + span * 0.50f
        val upper = glyphs.mapNotNull { (y, height) -> if (y <= upperCut && height > 0f) height else null }.sorted()
        if (upper.size < 8) return columnMedian
        val higher = upper[(upper.size * 7) / 10]
        return if (higher > columnMedian * 1.08f && higher < columnMedian * 1.50f) higher else columnMedian
    }

    /** Classify physical lines before breaking the page into reading-order bands. A band
     * containing only footnotes cannot supply its own body size or see the rule above it.
     * Project the result back onto words, so each eventual slice keeps its own offsets.
     */
    private fun columnNotes(rows: List<List<Word>>, rules: List<DrawnRule>, scanLayer: Boolean): Set<Word> {
        if (rows.size < 2) return emptySet()
        var offset = 0
        val lines = rows.map { words ->
            val size = words.map { it.pointSize }.sorted()[words.size / 2]
            val start = offset
            offset += words.sumOf { it.text.length } + 1
            val baseline = words.map { it.y }.sorted()[words.size / 2]
            FootnoteLine(baseline, size, start, offset - 1,
                rule = words.all { textRule.matches(it.text) })
        }
        val median = lines.map { it.size }.sorted()[lines.size / 2]
        val body = readingBodySize(lines, median)
        val left = rows.minOf { it.first().x }
        val right = rows.maxOf { it.last().right }
        val separators = rules.filter { it.maxX >= left && it.minX <= right }.map { it.displayY }
        val ruled = layoutFootnoteRanges(lines, median, separators).mapTo(HashSet()) { it.first }
        val steps = lines.zipWithNext { a, b -> b.y - a.y }
        val bodySteps = steps.indices.filter { i ->
            lines[i].size >= body * 0.94f && lines[i + 1].size >= body * 0.94f &&
                steps[i] in body * 0.7f..body * 2.5f
        }.map(steps::get).sorted()
        val pitch = bodySteps.takeIf { it.size >= 3 }?.get(bodySteps.size / 2)
        // One loose inter-paragraph gap also occurs inside notes. Require the next
        // baseline to corroborate a return to body spacing before ending the run.
        fun bodyRhythmAt(i: Int, bodyPitch: Float): Boolean =
            steps[i - 1] >= bodyPitch * 0.94f &&
                (steps.getOrNull(i) ?: 0f) >= bodyPitch * 0.94f &&
                lines[i].size >= body * 0.85f
        val marked = mutableSetOf<Word>()
        fun markNoteRuns(words: List<Word>) {
            var start = 0
            for (end in 1..words.size) {
                if (end < words.size && words[end].x - words[end - 1].right <= body * 1.5f) continue
                val run = words.subList(start, end)
                // An OCR row may contain note and body type on opposite sides of a gutter.
                val bodyRun = run.size < words.size && run.size >= 2 &&
                    run.count { it.pointSize >= body * 0.95f } * 2 > run.size
                if (!bodyRun) marked.addAll(run)
                start = end
            }
        }
        data class HangingNote(val textLeft: Float, val size: Float)
        var hanging: HangingNote? = null
        for ((i, words) in rows.withIndex()) {
            val line = lines[i]
            val first = words.first()
            val previous = rows.getOrNull(i - 1)
            // A superscript may occupy its own PDFBox baseline. Only a marker just
            // left of the FIRST word belongs to this line, never one after body text.
            val raised = previous?.singleOrNull()?.takeIf {
                noteLabel.matches(it.text) && first.y - it.y in 0f..line.size &&
                    first.x - it.right in -1f..(line.size * 2) && it.pointSize < line.size
            }
            val nextLeft = rows.getOrNull(i + 1)?.first()?.x
            val inline = words.size > 1 && noteLabel.matches(first.text) &&
                words[1].x - first.right <= body * 2 &&
                (first.pointSize < line.size * 0.92f ||
                    nextLeft != null && abs(nextLeft - words[1].x) <= body * 0.6f && nextLeft > first.x)
            if (line.size < body * 0.95f && (raised != null || inline) &&
                first.x - left <= body * 4 && i > 0) {
                if (raised != null) marked += raised
                hanging = HangingNote(if (inline) words[1].x else first.x, line.size)
            } else {
                hanging = hanging?.takeIf { note ->
                    val step = steps[i - 1]
                    val atNoteMargin = abs(first.x - note.textLeft) <= note.size * 0.65f
                    val compact = pitch != null && step < pitch * 0.92f
                    line.size < body * 0.95f && line.size <= note.size * 1.06f &&
                        step > 0f && step <= note.size * 1.65f && (atNoteMargin || compact) &&
                        (pitch == null || !bodyRhythmAt(i, pitch))
                }
            }
            if (line.start in ruled || hanging != null) markNoteRuns(words)
        }
        if (scanLayer && lines.size >= 8) {
            // OCR often gives an entire line one font size, including superscripts. Dense
            // apparatus has both smaller type and a sustained change in baseline pitch.
            val upperSteps = steps.take(lines.size / 2).filter { it > body * 0.65f }.sorted()
            if (upperSteps.size >= 3) {
                val bodyPitch = pitch ?: upperSteps[upperSteps.size / 2]
                for (i in 1 until lines.size - 2) {
                    if (steps[i - 1] < bodyPitch * 1.35f || lines[i - 1].size < body * 0.9f) continue
                    if ((i..i + 1).any { steps[it] !in (bodyPitch * 0.45f)..(bodyPitch * 0.90f) }) continue
                    if ((i..i + 2).count { lines[it].size < body * 0.9f } < 2) continue
                    val notePitch = maxOf(steps[i], steps[i + 1])
                    val noteLeft = rows[i + 1].first().x
                    markNoteRuns(rows[i])
                    var j = i + 1
                    while (j < lines.size && steps[j - 1] <= bodyPitch * 1.10f &&
                        !bodyRhythmAt(j, bodyPitch) && lines[j].size < body * 0.96f) {
                        val compact = steps[j - 1] <= minOf(notePitch * 1.20f, bodyPitch * 0.96f)
                        // A wider paragraph break can occur inside notes. Require the
                        // same lane and another compact baseline to resume that block.
                        if (!compact && (abs(rows[j].first().x - noteLeft) > body * 0.65f ||
                                (steps.getOrNull(j) ?: Float.MAX_VALUE) > notePitch * 1.10f)) break
                        markNoteRuns(rows[j++])
                    }
                }
            }
        }
        return marked
    }

    /** A superscript word between two ordinary words occupies their line in reading
     * order. A detached note label, translation above the line, or margin word does not
     * occupy a gap bounded on both sides by adjacent body words.
     */
    private fun rejoinInlineWords(rows: MutableList<Row>): Set<Word> {
        val joined = mutableSetOf<Word>()
        for (i in 0 until rows.lastIndex) {
            val upper = rows[i]
            val lower = rows[i + 1]
            val moved = mutableSetOf<Word>()
            for ((left, right) in lower.words.zipWithNext()) {
                val size = minOf(left.pointSize, right.pointSize)
                if (lower.y - upper.y !in size * 0.15f..size * 0.68f || right.x <= left.right) continue
                val between = upper.words.filter { it.x >= left.right - 0.5f && it.right <= right.x + 0.5f }
                if (between.isEmpty() || between.any { it.pointSize >= size * 0.92f } ||
                    between.none { it.text.any(Char::isLetter) }) continue
                if (between.first().x - left.right > size || right.x - between.last().right > size ||
                    between.zipWithNext().any { (a, b) -> b.x - a.right > size }) continue
                moved += between
            }
            // A callout belongs after the sentence it annotates, even when another
            // sentence starts farther right on that baseline. A leading note label
            // has no immediately preceding sentence end at the same horizontal position.
            for (w in upper.words) {
                if (w.text.length !in 1..3 || !w.text.all(Char::isDigit)) continue
                val prior = lower.words.lastOrNull { it.right <= w.x + 0.5f } ?: continue
                val size = prior.pointSize
                if (w.x - prior.right <= size * 0.55f && w.pointSize < size * 0.92f &&
                    lower.y - w.y in size * 0.15f..size * 0.68f &&
                    LiteraturePassages.endsSentence(prior.text)) moved += w
            }
            if (moved.isEmpty()) continue
            upper.words.removeAll(moved)
            lower.words.addAll(moved)
            lower.words.sortBy(Word::x)
            joined += moved
        }
        rows.removeAll { it.words.isEmpty() }
        return joined
    }

    /** OCR occasionally puts a short marginal note on the same baseline as prose.
     * Follow its marker and its own left edge across closely spaced continuations.
     * Keep the separate strip addressable without interrupting a body sentence.
     */
    private fun sideNoteWords(rows: List<Row>): Set<Word> {
        val notes = mutableSetOf<Word>()
        for ((i, row) in rows.withIndex()) {
            val words = row.words
            for (k in 1 until words.size) {
                val gap = words[k].x - words[k - 1].right
                if (gap < maxOf(words[k].pointSize, words[k - 1].pointSize) * 1.5f) continue
                val left = words.subList(0, k)
                val right = words.subList(k, words.size)
                val onLeft = noteLabel.matches(left.first().text)
                val strip = if (onLeft) left else right
                val prose = if (onLeft) right else left
                if (strip.size < 2 || prose.size < 2 || !noteLabel.matches(strip.first().text)) continue
                val marker = strip.first()
                val first = strip[1]
                val proseSize = prose.map { it.pointSize }.sorted()[prose.size / 2]
                val noteSize = first.pointSize
                if (first.x - marker.right > noteSize || marker.pointSize > noteSize ||
                    noteSize >= proseSize * 0.92f || strip.drop(1).any { it.pointSize >= proseSize * 0.96f }) continue
                notes += strip
                val cut = (left.last().right + right.first().x) / 2
                var previousBaseline = first.y
                for (j in i + 1 until rows.size) {
                    val line = rows[j].words
                    if (line.any { it.x < cut && it.right > cut }) break
                    val next = line.filter { if (onLeft) it.right <= cut else it.x >= cut }
                    if (next.isEmpty() || abs(next.first().x - first.x) > noteSize ||
                        next.any { it.pointSize >= proseSize * 0.96f }) break
                    val baseline = next.map { it.y }.sorted()[next.size / 2]
                    if (baseline - previousBaseline > noteSize * 1.55f) break
                    notes += next
                    previousBaseline = baseline
                }
            }
        }
        return notes
    }

    /** On a justified scan most full lines end at the same two margins. OCR can assign
     * a neighbouring marginal gloss or a strip of the facing page to the body baseline.
     * Only trim a sparse outer strip at an edge supported by many complete lines.
     */
    private fun scanMarginWords(rows: List<Row>, pageWidth: Float): Set<Word> {
        val long = rows.map { it.words }.filter {
            it.size >= 5 && it.last().right - it.first().x >= pageWidth * 0.4f
        }
        if (long.size < 8) return emptySet()
        val tolerance = long.map { it[it.size / 2].pointSize }.sorted()[long.size / 2] * 0.55f
        val words = rows.flatMap { it.words }
        fun outside(left: Boolean): List<Word> {
            fun edge(line: List<Word>) = if (left) line.first().x else line.last().right
            val bound = long.map(::edge).sorted()[long.size / 2]
            if (long.count { abs(edge(it) - bound) <= tolerance } * 2 < long.size) return emptyList()
            if (long.count { line -> line.any { it.x < bound - tolerance && it.right > bound + tolerance } } * 8 > long.size) return emptyList()
            val strips = rows.map { row ->
                val line = row.words
                // A marginal word can start immediately beside the justified edge.
                val aligned = line.indices.minByOrNull { i ->
                    abs((if (left) line[i].x else line[i].right) - bound)
                }
                if (aligned != null && abs((if (left) line[aligned].x else line[aligned].right) - bound) <= tolerance) {
                    if (left) line.take(aligned) else line.drop(aligned + 1)
                } else {
                    line.filter { if (left) it.right < bound - tolerance else it.x > bound + tolerance }
                }
            }
            val outer = strips.flatMapIndexed { i, strip ->
                if (strip.isEmpty()) return@flatMapIndexed emptyList()
                val line = rows[i].words
                if (strip.size == line.size) return@flatMapIndexed strip
                val near = if (left) strip.last().right else strip.first().x
                val gap = if (left) line[strip.size].x - near else near - line[line.size - strip.size - 1].right
                // A single longer line of ragged prose can extend past the median
                // edge. Require a gutter or a second baseline in the same side column.
                if (gap > tolerance * 2) return@flatMapIndexed strip
                val alignedStrip = strips.indices.any { j ->
                    j != i && strips[j].isNotEmpty() && abs(rows[j].y - rows[i].y) <= tolerance * 6 &&
                        abs((if (left) strips[j].last().right else strips[j].first().x) - near) <= tolerance * 2
                }
                if (alignedStrip) strip else emptyList()
            }
            // A detached punctuation glyph at the edge can still finish the body line.
            return outer.filterNot { word ->
                word.text.length == 1 && word.text[0] in ",;:.!?" &&
                    abs((if (left) word.right else word.x) - bound) <= tolerance * 2
            }.takeIf { it.size * 8 <= words.size }.orEmpty()
        }
        val margins = (outside(true) + outside(false)).toMutableSet()
        val header = rows.first().words.filterNot { it in margins }
        if (header.size >= 3 && (folio.matches(header.first().text) || folio.matches(header.last().text)) &&
            rows[1].y - rows.first().y > (rows.zipWithNext { a, b -> b.y - a.y }.sorted()[rows.size / 2] * 1.5f) &&
            header.zipWithNext().any { (a, b) -> b.x - a.right > tolerance * 3 }) margins += header
        return margins
    }

    private enum class ScanMark { STOP, RAISED_CALLOUT }

    private fun darkPixel(image: BookBitmap, x: Int, y: Int): Boolean {
        val rgb = bookBitmapPixel(image, x, y)
        return ((rgb shr 16 and 255) + (rgb shr 8 and 255) + (rgb and 255)) < 3 * 140
    }

    /** One lazy render shared by physical-line inspection and punctuation checks. */
    private class ScanPage(pdf: BookPdfDocument, page: Int, direction: Int) : AutoCloseable {
        private val source = pdf.getPage(page - 1)
        val rotation = Math.floorMod(source.rotation, 360)
        private val turn = Math.floorMod(rotation - direction, 360)
        val horizontal = direction % 180 == 0 && turn % 180 == 0
        val scale = minOf(4f, 3000f / maxOf(source.cropBox.width, source.cropBox.height))
        private val rendered = lazy {
            if (source.cropBox.width <= 0 || source.cropBox.height <= 0) null
            else try {
                newBookPdfRenderer(pdf).renderImage(page - 1, scale)
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val bitmap: BookBitmap? get() = rendered.value

        fun ink(image: BookBitmap, x: Int, y: Int): Boolean =
            if (turn == 180) darkPixel(image, image.width - 1 - x, image.height - 1 - y) else darkPixel(image, x, y)

        override fun close() {
            if (rendered.isInitialized()) bitmap?.let(::releaseBookBitmap)
        }
    }

    /** Searchable scans can use one nominal font height for both body and note type.
     * Measure actual ink above the baseline of several words. Bound the horizontal
     * samples per word and skip boxes without ink near the reported baseline.
     */
    private fun printedHeight(row: List<Word>, scan: ScanPage, image: BookBitmap): Float? {
        val heights = row.filter { it.text.length >= 3 }.sortedByDescending { it.right - it.x }.take(5).mapNotNull { w ->
            val x0 = (w.x * scan.scale).toInt().coerceAtLeast(0)
            val x1 = (w.right * scan.scale).toInt().coerceAtMost(image.width - 1)
            val y1 = (w.y * scan.scale).toInt().coerceAtMost(image.height - 1)
            val y0 = ((w.y - w.pointSize) * scan.scale).toInt().coerceAtLeast(0)
            if (x1 <= x0 || y1 <= y0) return@mapNotNull null
            val xs = x0..x1 step maxOf(1, (x1 - x0) / 36)
            fun occupied(y: Int): Boolean = xs.count { x -> scan.ink(image, x, y) } >= 2
            val top = (y0..y1).firstOrNull(::occupied) ?: return@mapNotNull null
            if (top == y0 || (maxOf(top, y1 - (w.pointSize * scan.scale * 0.20f).toInt())..y1).none(::occupied)) null
                else (y1 - top + 1) / scan.scale
        }.sorted()
        return heights.getOrNull(heights.size / 2)
    }

    private fun printedRule(scan: ScanPage, image: BookBitmap, left: Float, right: Float,
                            top: Float, bottom: Float, em: Float): Boolean {
        val step = maxOf(1, scan.scale.toInt())
        val x0 = (left * scan.scale).toInt().coerceAtLeast(0)
        val x1 = (right * scan.scale).toInt().coerceAtMost(image.width - 1)
        val clearance = maxOf(2, (em * scan.scale * 0.18f).toInt())
        val minRun = maxOf(em * 4, (right - left) * 0.12f) * scan.scale
        val y0 = (top * scan.scale).toInt().coerceAtLeast(clearance)
        val y1 = (bottom * scan.scale).toInt().coerceAtMost(image.height - 1 - clearance)
        for (y in y0..y1) {
            var run = 0
            for (x in x0..x1 step step) {
                if (scan.ink(image, x, y) && !scan.ink(image, x, y - clearance) && !scan.ink(image, x, y + clearance)) {
                    run += step
                    if (run >= minRun) return true
                } else run = 0
            }
        }
        return false
    }

    /** A smaller printed block at the end of this reading column needs its own
     * separator or repeated, aligned hanging marks before it can be classified as a note. */
    private fun printedFooter(rows: List<List<Word>>, scan: ScanPage, marked: Set<Word>): List<Word> {
        val lines = rows.filter { row ->
            row.any { w -> w.text.any(Char::isLetter) } && !(row.size == 1 && folio.matches(row.first().text))
        }
        if (lines.size < 6) return emptyList()
        fun advance(row: List<Word>) = row.sumOf { (it.right - it.x).toDouble() } / row.sumOf { it.text.length }
        val upper = lines.take(lines.size / 2)
        val sampleSize = minOf(5, upper.size)
        val sample = (0 until sampleSize).map { upper[it * upper.lastIndex / (sampleSize - 1)] }
        val normalWidth = sample.map(::advance).sorted()[sample.size / 2]
        val em = sample.map { it[it.size / 2].pointSize }.sorted()[sample.size / 2]
        // Word advances survive horizontal fitting even if every OCR word reports one em.
        // This cheap prefilter never labels a line. The bitmap must confirm smaller print.
        if (lines.takeLast(2).none { advance(it) < normalWidth * 0.94 || it[it.size / 2].pointSize < em * 0.93f }) return emptyList()
        val image = scan.bitmap ?: return emptyList()
        val body = sample.mapNotNull { printedHeight(it, scan, image) }.sorted()
        if (body.size < 3) return emptyList()
        val bodyInk = body[body.size / 2]
        var start = lines.size
        while (start > 0) {
            val row = lines[start - 1]
            if (row.zipWithNext().any { (a, b) -> b.x - a.right > em * 2 }) break
            val height = printedHeight(row, scan, image) ?: break
            if (height >= bodyInk * 0.88f) break
            start--
        }
        if (start == 0 || start > lines.size - 2) return emptyList()
        if ((start until lines.size).all { i -> lines[i].all { it in marked } }) return emptyList()
        val markers = (start until lines.size).filter { printedNoteMarker(lines[it], scan, image, em) }
        for (i in start until lines.lastIndex) {
            val row = lines[i]
            val next = lines[i + 1]
            val y = row[row.size / 2].y
            val prior = lines[i - 1]
            val top = maxOf(prior[prior.size / 2].y + em * 0.30f, y - em * 6)
            val left = minOf(row.first().x, next.first().x)
            val right = maxOf(row.last().right, next.last().right)
            val ruled = printedRule(scan, image, left, right, top, y - bodyInk * 1.1f, em)
            val repeatedMark = i in markers && markers.any { j ->
                val other = lines[j]
                j > i && abs(other[0].x - row[0].x) < em * 0.5f &&
                    abs(other[1].x - row[1].x) < em * 0.5f
            }
            if (ruled || repeatedMark) return lines.subList(i, lines.size).flatten()
        }
        return emptyList()
    }

    private fun printedNoteMarker(row: List<Word>, scan: ScanPage, image: BookBitmap, em: Float): Boolean {
        if (row.size < 2) return false
        val mark = row[0]
        val word = row[1]
        if (mark.text.length > 3 || word.x - mark.right !in -1f..em || mark.right - mark.x > em * 1.5f) return false
        if (word.y - mark.y in (em * 0.10f)..(em * 0.8f)) return true
        // Some OCR layers put a superscript on the prose baseline and even read it
        // as a letter. Check that the mark is printed above a clear baseline strip.
        val xs = (mark.x * scan.scale).toInt().coerceAtLeast(0)..
            (mark.right * scan.scale).toInt().coerceAtMost(image.width - 1)
        val baseline = (word.y * scan.scale).toInt()
        val top = ((word.y - em) * scan.scale).toInt()
        if (top < 0 || baseline >= image.height) return false
        val lower = maxOf(top, baseline - maxOf(1, (em * 0.13f * scan.scale).toInt()))
        fun hasInk(y: Int) = xs.any { x -> scan.ink(image, x, y) }
        return (lower..baseline).none(::hasInk) && (top until lower).any(::hasInk)
    }

    /** Consult the visible scan only for ambiguous punctuation next to a potential sentence
     * break. Keep the stored quote verbatim. The raster supplies boundary evidence, not text.
     */
    private fun scanPunctuation(scan: ScanPage, rows: List<Row>): Map<Word, ScanMark> {
        val ambiguous = rows.flatMap { row -> row.words.zipWithNext().filter { (word, next) ->
            val gap = next.x - word.right
            word.text.length >= 2 && (word.text.last() in "’'*," && next.text.first().isUpperCase() &&
                gap in word.pointSize * 0.8f..word.pointSize * 2f ||
                word.text.last() in "!?" && next.text.first().isLowerCase() && gap < word.pointSize)
        } }
        if (ambiguous.isEmpty() || scan.rotation != 0) return emptyMap()
        val scale = scan.scale
        val bitmap = scan.bitmap ?: return emptyMap()
        fun ink(x: Int, y: Int) = darkPixel(bitmap, x, y)
        return buildMap {
            for ((w, _) in ambiguous) {
                val em = w.pointSize * scale
                if (em !in 14f..160f) continue
                val right = w.right * scale
                val baseline = w.y * scale
                if (right !in 0f..bitmap.width.toFloat() || baseline !in 0f..bitmap.height.toFloat()) continue
                val x0 = (right - em * 0.42f).toInt().coerceAtLeast(0)
                val x1 = (right + em * 0.14f).toInt().coerceAtMost(bitmap.width - 1)
                val top = (baseline - em * 0.26f).toInt().coerceAtLeast(0)
                val bottom = (baseline + em * 0.18f).toInt().coerceAtMost(bitmap.height - 1)
                val occupied = (x0..x1).filter { x -> (top..bottom).any { y -> ink(x, y) } }
                if (w.text.last() in "!?") {
                    val high = (baseline - em * 0.78f).toInt().coerceAtLeast(0)
                    val tip = (right - em * 0.18f).toInt().coerceAtLeast(x0)..x1
                    val low = (baseline - em * 0.17f).toInt().coerceAtLeast(top)
                    if (tip.none { x -> (low..bottom).any { y -> ink(x, y) } } &&
                        tip.any { x -> (high until top).any { y -> ink(x, y) } }) put(w, ScanMark.RAISED_CALLOUT)
                    continue
                }
                val last = occupied.lastOrNull() ?: continue
                val first = occupied.zipWithNext().lastOrNull { (a, b) -> b - a > 1 }?.second ?: occupied.first()
                if (first <= x0 || last >= x1) continue
                val ys = (top..bottom).filter { y -> (first..last).any { x -> ink(x, y) } }
                val width = last - first + 1
                val height = ys.last() - ys.first() + 1
                if (width.toFloat() in em * 0.06f..em * 0.28f && height.toFloat() in em * 0.06f..em * 0.29f &&
                    width.toFloat() / height in 0.60f..1.6f && abs(ys.last() - baseline) < em * 0.15f) put(w, ScanMark.STOP)
            }
        }
    }

    private val noteLabel = Regex("""(?:\d{1,3}[.)]?|\[\d{1,3}]|[*†‡§])""")

    private fun insideCropBox(position: BookPdfTextPosition, crop: BookPdfRectangle): Boolean {
        val tm = position.textMatrix
        val tx = tm.translateX
        val ty = tm.translateY
        val tolX = 3f
        val tolY = maxOf(4f, position.heightDir * 0.6f)
        // PDFTextStripper has already subtracted the CropBox origin from the text matrix.
        return tx in -tolX..(crop.width + tolX) && ty in -tolY..(crop.height + tolY)
    }

    fun extract(pdf: BookPdfDocument, page: Int): List<GuideRegion> {
        val pdfPage = pdf.getPage(page - 1)
        val crop = pdfPage.cropBox
        val graphics = pageGraphics(pdfPage)
        val words = mutableListOf<Word>()
        val glyphDirections = IntArray(4)
        var hiddenGlyphs = 0
        var glyphs = 0
        var scanLayer = false
        val stripper = object : ReadingOrderStripper() {
            override fun processTextPosition(position: BookPdfTextPosition) {
                if (!insideCropBox(position, crop)) return
                glyphs++
                val mode = graphicsState.textState.renderingMode
                if (!mode.isFill && !mode.isStroke) hiddenGlyphs++
                super.processTextPosition(position)
            }
            override fun writePage() {
                scanLayer = glyphs > 0 && (graphics.textBehindScan || hiddenGlyphs.toLong() * 10 >= glyphs.toLong() * 9)
                // OCR word boxes can overlap. Sorting their individual glyphs interleaves letters.
                if (scanLayer) sortByPosition = false
                super.writePage()
            }

            override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
                var start = 0f
                var end = 0f
                var y = 0f
                var height = 0f
                var pointSize = 0f
                val token = StringBuilder()
                fun flush() {
                    if (token.isNotEmpty() && token.any { !it.isWhitespace() }) {
                        words += Word(token.toString(), start, end, y, height, pointSize)
                    }
                    token.clear()
                }
                for (glyph in textPositions) {
                    val u = glyph.unicode
                    if (u.isNullOrEmpty() || u.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
                        flush()
                        continue
                    }
                    val x = glyph.xDirAdj
                    glyphDirections[glyph.dir.toInt() / 90]++
                    val h = glyph.heightDir.takeIf { it > 0f } ?: glyph.fontSizeInPt.coerceAtLeast(1f)
                    val pt = glyph.yScale.takeIf { it > 0f } ?: glyph.fontSizeInPt.coerceAtLeast(1f)
                    if (token.isNotEmpty() && (x - end > glyph.widthOfSpace.coerceAtLeast(1f) * 0.6f ||
                            abs(glyph.yDirAdj - y) > h * 0.35f && abs(pt - pointSize) > pt * 0.1f)) flush()
                    if (token.isEmpty()) {
                        start = x
                        y = glyph.yDirAdj
                        height = h
                        pointSize = pt
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

        val rows = mutableListOf<Row>()
        for (w in words.sortedBy { it.y }) {
            // Hidden OCR fonts can report a glyph height of half the point size.
            // Use the OCR line size so one raised marker cannot split a common baseline.
            val tolerance = if (scanLayer) w.pointSize * 0.32f else w.height * 0.35f
            val row = rows.lastOrNull()?.takeIf { abs(it.y - w.y) <= maxOf(1.5f, tolerance) }
                ?: Row(w.y).also(rows::add)
            row.words += w
        }
        rows.forEach { it.words.sortBy(Word::x) }
        val positiveHeights = words.map { it.height }.filter { it > 0f }.sorted()
        val bodyHeight = if (positiveHeights.isNotEmpty()) positiveHeights[positiveHeights.size / 2] else 10f

        val inlineWords = if (scanLayer) emptySet() else rejoinInlineWords(rows)
        val sideNotes = sideNoteWords(rows)
        val marginWords = if (scanLayer) scanMarginWords(rows, crop.width) else emptySet()
        val excluded = marginWords + sideNotes
        val noteText = if (sideNotes.isEmpty()) "" else rows.mapNotNull { row ->
            row.words.filter { it in sideNotes }.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.text }
        }.joinToString("\n")
        if (excluded.isNotEmpty()) {
            words.removeAll(excluded)
            rows.forEach { it.words.removeAll(excluded) }
            rows.removeAll { it.words.isEmpty() }
        }
        // Match the independent PDFBox reference to the same retained horizontal extent.
        val marginsByBaseline = excluded.groupBy { it.y.toInt() }

        val visibleMinX = words.minOf { it.x }
        val visibleMaxRight = words.maxOf { it.right }
        val spanWidth = (visibleMaxRight - visibleMinX).coerceAtLeast(1f)

        // Search vertical gutter across the actual visible word span (handles both cropped scans and IEEE/ACL 2-column papers).
        val halfGap = maxOf(3f, minOf(bodyHeight * 0.65f, spanWidth * 0.008f))
        val gutterMatch = (12..88).map { visibleMinX + spanWidth * it / 100f }.mapNotNull { cut ->
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
            val cleanTwoCol = overlapping.count { row ->
                row.words.any { it.right <= cut - halfGap } &&
                    row.words.any { it.x >= cut + halfGap } &&
                    row.words.none { it.right > cut - halfGap && it.x < cut + halfGap }
            }
            val pureTwoCol = support >= 3 && support > crosses * 5
            val bandedTwoCol = cleanTwoCol >= 8 && cleanTwoCol * 3 >= overlapping.size
            if (!pureTwoCol && !bandedTwoCol) return@mapNotNull null
            val cleanRows = overlapping.filter { row -> row.words.none { it.right > cut - halfGap && it.x < cut + halfGap } }
            val clearance = cleanRows.ifEmpty { overlapping }.map { row ->
                row.words.minOf { maxOf(it.x - cut, cut - it.right) }.coerceIn(0f, spanWidth * 0.12f)
            }.average()
            Triple(cut, crosses, clearance * maxOf(support - crosses, cleanTwoCol))
        }.maxByOrNull { it.third }
        val gutter = gutterMatch?.first
        val gutterCrosses = gutterMatch?.second ?: 0

        // Side: -1 = full-width band, 0 = left column, 1 = right column.
        data class RegionSlice(val side: Int, val words: List<Word>, val yMin: Float? = null, val yMax: Float? = null)

        val rawSlices: List<RegionSlice> = if (gutter == null) {
            listOf(RegionSlice(-1, words))
        } else if (gutterCrosses == 0) {
            val (leftCol, rightCol) = words.partition { (it.x + it.right) / 2 < gutter }
            val kept = mutableListOf<RegionSlice>()
            val leftSpan = if (leftCol.isNotEmpty()) leftCol.maxOf { it.right } - leftCol.minOf { it.x } else 0f
            val rightSpan = if (rightCol.isNotEmpty()) rightCol.maxOf { it.right } - rightCol.minOf { it.x } else 0f
            // Drop clipped edge facing-page slivers (much narrower than the main column and flush against page edge).
            fun smallerMargin(side: List<Word>, opposite: List<Word>): Boolean {
                if (side.isEmpty() || opposite.isEmpty()) return false
                val sideSize = side.map { it.pointSize }.sorted()[side.size / 2]
                val bodySize = opposite.map { it.pointSize }.sorted()[opposite.size / 2]
                return sideSize < bodySize * 0.82f
            }
            val leftIsEdgeSliver = leftCol.isNotEmpty() && rightCol.isNotEmpty() &&
                leftSpan < rightSpan * 0.48f && leftCol.size < rightCol.size * 0.55 &&
                leftCol.minOf { it.x } <= visibleMinX + bodyHeight * 1.5f
            val rightIsEdgeSliver = leftCol.isNotEmpty() && rightCol.isNotEmpty() &&
                rightSpan < leftSpan * 0.48f && rightCol.size < leftCol.size * 0.55 &&
                rightCol.maxOf { it.right } >= visibleMaxRight - bodyHeight * 1.5f
            if (leftCol.isNotEmpty() && !leftIsEdgeSliver && !(leftSpan < rightSpan * 0.72f && smallerMargin(leftCol, rightCol))) kept += RegionSlice(0, leftCol)
            if (rightCol.isNotEmpty() && !rightIsEdgeSliver && !(rightSpan < leftSpan * 0.72f && smallerMargin(rightCol, leftCol))) kept += RegionSlice(1, rightCol)
            kept
        } else {
            // Band-aware split for 2-column academic papers that also have full-width titles, abstracts, or figures.
            val slices = mutableListOf<RegionSlice>()
            val fullBuf = mutableListOf<Row>()
            val colBuf = mutableListOf<Row>()
            fun flushFull() {
                if (fullBuf.isEmpty()) return
                val ws = fullBuf.flatMap { it.words }
                slices += RegionSlice(-1, ws, fullBuf.first().y - bodyHeight, fullBuf.last().y + bodyHeight)
                fullBuf.clear()
            }
            fun flushCols() {
                if (colBuf.isEmpty()) return
                val yLo = colBuf.first().y - bodyHeight
                val yHi = colBuf.last().y + bodyHeight
                val ws = colBuf.flatMap { it.words }
                val (leftCol, rightCol) = ws.partition { (it.x + it.right) / 2 < gutter }
                val leftRows = colBuf.count { r -> r.words.any { (it.x + it.right) / 2 < gutter } }
                val rightRows = colBuf.count { r -> r.words.any { (it.x + it.right) / 2 >= gutter } }
                if (leftRows >= 3 && rightRows >= 3) {
                    if (leftCol.isNotEmpty()) slices += RegionSlice(0, leftCol, yLo, yHi)
                    if (rightCol.isNotEmpty()) slices += RegionSlice(1, rightCol, yLo, yHi)
                } else {
                    slices += RegionSlice(-1, ws, yLo, yHi)
                }
                colBuf.clear()
            }
            for (row in rows) {
                val crossesRow = row.words.any { it.right > gutter - halfGap && it.x < gutter + halfGap }
                if (crossesRow) {
                    flushCols()
                    fullBuf += row
                } else {
                    flushFull()
                    colBuf += row
                }
            }
            flushCols()
            flushFull()
            slices
        }

        // Word baselines use PDFBox text direction. Graphics are in unrotated crop
        // coordinates, even if the page and its OCR layer both turn through 180°.
        val textDirection = glyphDirections.indices.maxBy { glyphDirections[it] } * 90
        val readingRules = when (textDirection) {
            0 -> graphics.rules
            180 -> graphics.rules.map { rule ->
                DrawnRule(crop.height - rule.displayY, crop.width - rule.maxX, crop.width - rule.minX)
            }
            else -> emptyList()
        }
        val scan = if (scanLayer) ScanPage(pdf, page, textDirection) else null
        val noteWords = HashSet<Word>()
        val punctuation = scan.use {
            for (slices in rawSlices.groupBy { it.side }.values) {
                val members = slices.flatMapTo(HashSet()) { it.words }
                val physical = rows.mapNotNull { row -> row.words.filter { it in members }.takeIf { it.isNotEmpty() } }
                noteWords += columnNotes(physical, readingRules, scanLayer)
                if (scan != null && scan.horizontal) noteWords += printedFooter(physical, scan, noteWords)
            }
            if (scan == null) emptyMap() else scanPunctuation(scan, rows)
        }
        return rawSlices.mapNotNull { slice ->
            val stops = mutableSetOf<Int>()
            val callouts = mutableSetOf<Int>()
            val members = slice.words.toHashSet()
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
            val footnoteRanges = mutableListOf<IntRange>()
            val text = buildString {
                var previousY: Float? = null
                for ((li, line) in lines.withIndex()) {
                    val y = line.first().y
                    val size = line.map { it.height }.sorted()[line.size / 2]
                    if (size < bodyHeight * 0.80f && line.all { noteLabel.matches(it.text) }) {
                        if (isNotEmpty()) append("\n\n")
                        continue
                    }
                    if (previousY != null && y - previousY > bodyHeight * 2.7f && isNotEmpty()) append('\n')
                    val gapLimit = (if (scanLayer) line.map { it.pointSize }.sorted()[line.size / 2] else bodyHeight) * 1.5f
                    val lineStart = length
                    var wideLine = false
                    line.forEachIndexed { i, w ->
                        val beforeSpace = length
                        val noteWord = w in noteWords
                        val roleBreak = i > 0 && noteWord != (line[i - 1] in noteWords)
                        if (roleBreak) append('\n')
                        else if (i > 0 && (scanLayer || w.x - line[i - 1].right > size * 0.10f ||
                                w.text.first().isLetterOrDigit() && line[i - 1].text.last().isLetterOrDigit() &&
                                w.x > line[i - 1].right + 0.5f)) append(' ')
                        if (i > 0 && w.x - line[i - 1].right > gapLimit) {
                            val prior = line[i - 1]
                            // An attached callout may have extra space after it. A sentence
                            // ending before that space does not span a gutter or margin.
                            val afterCallout = i >= 2 && prior in inlineWords && prior.text.all(Char::isDigit) &&
                                w.x - prior.right <= w.pointSize * 2 &&
                                abs(w.pointSize - line[i - 2].pointSize) <= w.pointSize * 0.1f
                            if (scanLayer || afterCallout) wideGapRanges += beforeSpace until length
                            else wideLine = true
                        }
                        val wordStart = length
                        append(if (w.text.any { it in '\uFB00'..'\uFB06' }) Normalizer.normalize(w.text, Normalizer.Form.NFKC) else w.text)
                        if (noteWord) {
                            val last = footnoteRanges.lastOrNull()
                            if (i > 0 && !roleBreak && last != null) {
                                footnoteRanges[footnoteRanges.lastIndex] = last.first until length
                            } else footnoteRanges += wordStart until length
                        }
                        when (punctuation[w]) {
                            ScanMark.STOP -> stops += length
                            ScanMark.RAISED_CALLOUT -> callouts += length
                            null -> Unit
                        }
                    }
                    val lineEnd = length
                    if ((disjointLine[li] || wideLine) && lineEnd > lineStart) wideGapRanges += lineStart until lineEnd
                    append('\n')
                    previousY = y
                }
            }.trimEnd()
            if (text.isBlank()) return@mapNotNull null
            fun reference(sorted: Boolean) = object : BookPdfStripper() {
                override fun processTextPosition(position: BookPdfTextPosition) {
                    if (!insideCropBox(position, crop)) return
                    val y = position.yDirAdj
                    if (slice.yMin != null && y < slice.yMin) return
                    if (slice.yMax != null && y > slice.yMax) return
                    val center = position.xDirAdj + position.widthDirAdj / 2
                    if (marginsByBaseline.isNotEmpty() && (y.toInt() - 2..y.toInt() + 2).any { bucket ->
                            marginsByBaseline[bucket]?.any { abs(it.y - y) <= 2f && center in it.x..it.right } == true
                        }) return
                    if (gutter == null || slice.side < 0 || (center < gutter) == (slice.side == 0)) {
                        super.processTextPosition(position)
                    }
                }
            }.apply { startPage = page; endPage = page; sortByPosition = sorted }.getText(pdf)
            val streamOrder = if (inlineWords.isNotEmpty() && slice.words.any { it in inlineWords }) reference(false) else null
            GuideRegion(text, label, reference(!scanLayer), wideGapRanges, footnoteRanges, measured = true,
                scannedStops = stops, raisedCallouts = callouts, streamOrder = streamOrder)
        } + if (noteText.isEmpty()) emptyList() else listOf(
            GuideRegion(noteText, measured = true, footnoteRanges = listOf(noteText.indices))
        )
    }

    /**
     * Thin wide strokes, including rules drawn inside a form.
     * Rule coordinates start at the top left of the unrotated crop. Extraction aligns them to the text direction.
     * Each subpath is judged on its own, so a rule that shares a path with a taller stroke is still found.
     * A page with unreadable graphics can still be classified by its note markers.
     */
    private fun pageGraphics(pdfPage: BookPdfPage): PageGraphics {
        if (Math.floorMod(pdfPage.rotation, 180) != 0) return PageGraphics()
        val crop = pdfPage.cropBox
        if (crop.width <= 0f || crop.height <= 0f) return PageGraphics()
        return try {
            val found = mutableListOf<DrawnRule>()
            var sawText = false
            var textBehindScan = false
            object : BookPdfGraphicsEngine(pdfPage) {
                private val subs = mutableListOf<RuleSub>()
                private var minX = 0.0
                private var minY = 0.0
                private var maxX = 0.0
                private var maxY = 0.0
                private var open = false
                private var current = newBookPdfPoint()
                private var subpath = newBookPdfPoint()
                private fun closeSub() {
                    if (!open) return
                    subs += RuleSub(minX, minY, maxX, maxY)
                    open = false
                }
                private fun begin(x: Double, y: Double) {
                    if (!x.isFinite() || !y.isFinite()) return
                    closeSub()
                    minX = x
                    maxX = x
                    minY = y
                    maxY = y
                    open = true
                }
                private fun point(x: Double, y: Double) {
                    if (!x.isFinite() || !y.isFinite()) return
                    if (!open) {
                        begin(x, y)
                        return
                    }
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
                private fun commit() {
                    closeSub()
                    for (sub in subs) {
                        val width = sub.maxX - sub.minX
                        val height = sub.maxY - sub.minY
                        if (width >= 24.0 && height <= 3.0 && width >= height * 8.0) {
                            val center = ((sub.minY + sub.maxY) / 2.0).toFloat()
                            found += DrawnRule(crop.upperRightY - center, sub.minX.toFloat() - crop.lowerLeftX, sub.maxX.toFloat() - crop.lowerLeftX)
                        }
                    }
                    subs.clear()
                }
                private fun discard() {
                    open = false
                    subs.clear()
                }
                override fun moveTo(x: Float, y: Float) {
                    begin(x.toDouble(), y.toDouble())
                    current = newBookPdfPoint(x, y)
                    subpath = current
                }
                override fun lineTo(x: Float, y: Float) {
                    point(x.toDouble(), y.toDouble())
                    current = newBookPdfPoint(x, y)
                }
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                    point(x1.toDouble(), y1.toDouble())
                    point(x2.toDouble(), y2.toDouble())
                    lineTo(x3, y3)
                }
                override fun appendRectangle(a: BookPdfPoint, b: BookPdfPoint, c: BookPdfPoint, d: BookPdfPoint) {
                    closeSub()
                    val xs = listOf(a.x, b.x, c.x, d.x).map { it.toDouble() }
                    val ys = listOf(a.y, b.y, c.y, d.y).map { it.toDouble() }
                    subs += RuleSub(xs.min(), ys.min(), xs.max(), ys.max())
                    current = newBookPdfPoint(a.x.toFloat(), a.y.toFloat())
                    subpath = current
                }
                override fun strokePath() = commit()
                override fun fillPath(windingRule: BookPdfFillRule) = commit()
                override fun fillAndStrokePath(windingRule: BookPdfFillRule) = commit()
                override fun endPath() = discard()
                override fun closePath() { current = subpath }
                override fun getCurrentPoint(): BookPdfPoint = current
                override fun clip(windingRule: BookPdfFillRule) {}
                override fun shadingFill(shadingName: BookPdfName) {}
                override fun showForm(form: BookPdfForm) {
                    val saved = subs.toList()
                    val savedOpen = open
                    val savedMinX = minX
                    val savedMinY = minY
                    val savedMaxX = maxX
                    val savedMaxY = maxY
                    subs.clear()
                    open = false
                    try {
                        super.showForm(form)
                    } finally {
                        subs.clear()
                        subs.addAll(saved)
                        open = savedOpen
                        minX = savedMinX
                        minY = savedMinY
                        maxX = savedMaxX
                        maxY = savedMaxY
                    }
                }
                override fun showText(string: ByteArray) {
                    if (string.isNotEmpty()) {
                        sawText = true
                        textBehindScan = false
                    }
                    super.showText(string)
                }
                override fun drawImage(image: BookPdfImage) {
                    // Some scan producers paint the facsimile over ordinary fill-mode
                    // OCR. A background painted before the text must not enable scan mode.
                    if (!sawText || image !is BookPdfImageObject || image.isStencil ||
                        image.width < crop.width || image.height < crop.height ||
                        graphicsState.nonStrokeAlphaConstant < 1.0 || graphicsState.softMask != null ||
                        image.cosObject.containsKey("SMask") || image.cosObject.containsKey("Mask")) return
                    val matrix = graphicsState.currentTransformationMatrix
                    if (abs(matrix.shearX) > 0.01f || abs(matrix.shearY) > 0.01f) return
                    val a = matrix.transformPoint(0f, 0f)
                    val b = matrix.transformPoint(1f, 1f)
                    if (minOf(a.x, b.x) <= crop.lowerLeftX + 1 && maxOf(a.x, b.x) >= crop.upperRightX - 1 &&
                        minOf(a.y, b.y) <= crop.lowerLeftY + 1 && maxOf(a.y, b.y) >= crop.upperRightY - 1) {
                        textBehindScan = true
                    }
                }
            }.processPage(pdfPage)
            PageGraphics(found, textBehindScan)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            PageGraphics()
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

        fun displayPoint(x: Float, y: Float, direction: Int): Pair<Float, Float> {
            val sideways = direction == 90 || direction == 270
            val u = (x / if (sideways) crop.height else crop.width).coerceIn(0f, 1f)
            val v = (y / if (sideways) crop.width else crop.height).coerceIn(0f, 1f)
            return when (Math.floorMod(rotation - direction, 360)) {
                90 -> (1f - v) to u
                180 -> (1f - u) to (1f - v)
                270 -> v to (1f - u)
                else -> u to v
            }
        }

        val words = mutableListOf<PlacedWord>()
        val stripper = object : BookPdfStripper() {
            override fun processTextPosition(position: BookPdfTextPosition) {
                if (insideCropBox(position, crop)) super.processTextPosition(position)
            }
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
                    val u = glyph.unicode
                    if (u.isNullOrEmpty() || u.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) { flush(); continue }
                    val x = glyph.xDirAdj
                    val h = glyph.heightDir.takeIf { it > 0f } ?: glyph.fontSizeInPt.coerceAtLeast(1f)
                    if (token.isNotEmpty() && x - end > glyph.widthOfSpace.coerceAtLeast(1f) * 0.6f) flush()
                    if (token.isEmpty()) { start = x; y = glyph.yDirAdj; height = h } else if (h > height) height = h
                    val direction = glyph.dir.toInt()
                    val baseline = glyph.yDirAdj
                    val (x0, y0) = displayPoint(x, baseline - h, direction)
                    val (x1, y1) = displayPoint(x + glyph.widthDirAdj.coerceAtLeast(1f), baseline, direction)
                    minNx = minOf(minNx, x0, x1)
                    minNy = minOf(minNy, y0, y1)
                    maxNx = maxOf(maxNx, x0, x1)
                    maxNy = maxOf(maxNy, y0, y1)
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
        search@ for (i in pageTokens.indices) {
            if (!pageTokens[i].contains(firstKey) && !firstKey.contains(pageTokens[i])) continue
            val maxEnd = minOf(words.size, i + needleTokens.size + 8)
            val minEnd = minOf(words.size, i + maxOf(1, needleTokens.size - 4))
            for (j in minEnd..maxEnd) {
                val endTok = pageTokens[j - 1]
                if (!endTok.contains(lastKey) && !lastKey.contains(endTok)) continue
                val joined = words.subList(i, j).joinToString(" ") { it.text }
                // Substring similarity can be perfect even if an earlier line starts
                // with the same word. Prefer the span whose complete text is the quote.
                if (LiteratureTextNorm.normalize(joined) == needle) {
                    bestSpan = i until j
                    bestScore = 1.0
                    break@search
                }
                if (bestScore == 1.0) continue
                val sim = LiteratureTextNorm.bestContiguousSimilarity(needle, joined)
                if (sim > bestScore) {
                    bestScore = sim
                    bestSpan = i until j
                }
            }
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
