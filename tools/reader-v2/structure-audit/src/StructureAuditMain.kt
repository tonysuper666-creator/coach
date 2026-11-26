package com.coach.desktop.books

import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.system.exitProcess

/**
 * Imports each given book the way the Windows reader does, then writes counts only.
 * Book text, outline titles and page text stay out of the report.
 *
 * Windows import, matching [com.coach.desktop.WindowsTextbooks]:
 * PDF uses tier-0 (that sets the first-open page) and then tier-1 indexing.
 * Other formats use a full import. The first-open page is the reading page
 * saved at import; tier-1 does not move it.
 */
object StructureAuditMain {
    private val explicitChapter = Regex(
        """^(?:chapter|chap\.|unit|lesson)\s+([0-9]{1,3}|[ivxlcdm]{1,8})\b|^第[0-9０-９一二三四五六七八九十百千零]+章""",
        RegexOption.IGNORE_CASE,
    )
    private val coarsePart = Regex(
        """^(?:book|part)\s+([0-9]{1,3}|[ivxlcdm]{1,8})\b|^第[0-9０-９一二三四五六七八九十百千零]+[篇部卷]""",
        RegexOption.IGNORE_CASE,
    )
    private val numberedChapter = Regex("""^(\d{1,2})[.:：．]?\s+(?!\d)(\S.*)$""")
    private val dottedSection = Regex("""^\d{1,2}\.\d+""")
    private val frontMatter = Regex(
        """^(?:cover|half[-\s]?title|title(?:\s+page)?|copyright(?:\s+page)?|contents|table\s+of\s+contents|brief\s+contents|detailed\s+contents|list\s+of\s+(?:illustrations|figures|tables|plates|maps)|illustrations|plates|preface|foreword|acknowledg(?:e)?ments?|dedication|epigraph|license|imprint|glossary|about\s+the\s+author|introduction\s+to\s+the\s+(?:book|reader)|目\s*录|总\s*目|简\s*目|版权(?:页)?|封面|扉页|序(?:言)?|自\s*序|前\s*言|插图(?:目录)?|图版目录|凡例|致谢)[.:：]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val bareNumber = Regex("""^(?:[0-9]{1,4}|[ivxlcdm]{1,8})[.:：]?$""", RegexOption.IGNORE_CASE)

    internal data class NativeOutline(val method: String, val pages: List<Int>)

    @JvmStatic fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val options = Options.parse(args)
        if (options.selfCheck) return selfCheck(options)
        require(options.books.isNotEmpty()) { "Pass at least one book path." }
        val report = measure(options)
        writeReport(options.out, report)
        val failed = report.books.count { it.error != null }
        println("AUDIT books=${report.books.size} failed=$failed out=${options.out.fileName}")
        return if (failed == report.books.size) 1 else 0
    }

    internal fun classifyOutline(items: List<BookOutlineItem>): NativeOutline {
        val explicit = items.filter { explicitChapter.containsMatchIn(it.title.trim()) }
        if (explicit.isNotEmpty()) return NativeOutline("explicit", explicit.map { it.page }.sorted())
        val numbered = items.filter { isNumberedChapter(it.title) }
        if (numbered.size in 3..80 && looksSequential(numbered)) {
            return NativeOutline("numbered", numbered.map { it.page }.sorted())
        }
        val parts = items.filter { coarsePart.containsMatchIn(it.title.trim()) && !looksLikeBodyDump(it.title) }
        if (parts.isNotEmpty()) return NativeOutline("part", parts.map { it.page }.sorted())
        val shallow = shallowBand(items)
        if (shallow != null) return shallow
        return NativeOutline("none", emptyList())
    }

    internal data class AdjustedOutline(val item: BookOutlineItem, val title: String, val kind: TocNodeKind)

    internal data class ReaderStructure(val chapterPages: List<Int>, val bodyStart: Int?, val firstChapter: Int?)

    /**
     * A real introduction before chapter 1 counts as the body.
     * Same shape as [BookBoundaries.openingNode], plus a Chinese introduction heading.
     * An earlier unnumbered note that the importer files as front matter does not.
     */
    private val substantiveIntroduction = Regex("""(?i)^Introduction(?:\s+and\s+.+)?$|^导\s*言$|^引\s*言$""")

    /** Same chapter/front split the importer uses. Body keeps an introduction; a preface stays front matter. */
    internal fun readerStructure(items: List<BookOutlineItem>): ReaderStructure {
        if (items.isEmpty()) return ReaderStructure(emptyList(), null, null)
        val adjusted = adjustOutline(items)
        val chapters = adjusted.filter { it.kind == TocNodeKind.CHAPTER }.map { it.item.page }.sorted()
        val firstChapter = chapters.firstOrNull()
        val introduction = adjusted.firstOrNull { row ->
            val title = BookTocWaterfall.cleanTocTitle(row.title).trimEnd('.', ':', '：')
            substantiveIntroduction.matches(title) && (firstChapter == null || row.item.page <= firstChapter)
        }?.item?.page
        val fallback = adjusted.firstOrNull {
            it.kind != TocNodeKind.FRONT_MATTER && it.kind != TocNodeKind.BACK_MATTER && it.kind != TocNodeKind.PART
        }?.item?.page
        return ReaderStructure(chapters, introduction ?: firstChapter ?: fallback, firstChapter)
    }

    /** Same shape as the importer's private roman-chapter heading. */
    private val romanChapterHeading = Regex("""^([IVXLC]+)\s+([A-Z][A-Z\s’'—-]{5,})$""")

    internal fun adjustOutline(items: List<BookOutlineItem>): List<AdjustedOutline> {
        if (items.isEmpty()) return emptyList()
        val labeled = items.map { it to BookTocWaterfall.classifyOutlineLabel(it.title, it.level) }
        val epub = items.first().source == HeadingSource.EPUB_TOC
        fun explicit(index: Int): Boolean {
            val (item, info) = labeled[index]
            val numbered = info.kind == TocNodeKind.CHAPTER && info.numberLabel != null
            val roman = epub && item.level <= 3 && romanChapterHeading.matches(item.title)
            return numbered || roman
        }
        val firstExplicit = labeled.indices.firstOrNull(::explicit) ?: -1
        val lastExplicit = if (firstExplicit >= 0) labeled.indices.lastOrNull(::explicit) ?: -1 else -1
        return labeled.mapIndexedNotNull { index, (item, info) ->
            if (info.isScaffoldNoise || info.title.isEmpty()) return@mapIndexedNotNull null
            val kind = when {
                !epub -> info.kind
                info.title == "正文" -> TocNodeKind.SECTION
                item.level <= 3 && romanChapterHeading.matches(item.title) -> TocNodeKind.CHAPTER
                firstExplicit >= 0 && info.kind == TocNodeKind.CHAPTER && info.numberLabel == null -> when {
                    index < firstExplicit -> TocNodeKind.FRONT_MATTER
                    index > lastExplicit -> TocNodeKind.BACK_MATTER
                    else -> TocNodeKind.SECTION
                }
                else -> info.kind
            }
            AdjustedOutline(item, info.title, kind)
        }
    }

    /**
     * Titles a correct header may show at `(page, offset)`, read from the source outline alone:
     * the last entry that has started, then each shallower entry still open above it.
     * The software's own nodes are not consulted, so a header left on an earlier chapter
     * because the software missed the next one is still counted wrong.
     */
    internal fun expectedHeaderTitles(
        adjusted: List<AdjustedOutline>,
        page: Int,
        offset: Int = 0,
    ): List<String> {
        val started = adjusted.withIndex().filter { (_, row) ->
            row.item.page < page || (row.item.page == page && row.item.startOffset <= offset)
        }.sortedWith(compareBy({ it.value.item.page }, { it.value.item.startOffset }, { it.index }))
        val chain = ArrayList<String>()
        var level = Int.MAX_VALUE
        for ((_, row) in started.asReversed()) {
            if (row.item.level < level) {
                chain += row.title
                level = row.item.level
            }
        }
        return chain
    }

    private val gutenbergEnd = Regex("""(?im)^\*{3}\s*END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK\b[^\r\n]*$""")

    /**
     * In the source text the work ends at the Gutenberg END line, so the first back-matter entry
     * after it opens there rather than at its own later heading. Read from the page text, not from software nodes.
     */
    internal fun backMatterFromGutenbergEnd(adjusted: List<AdjustedOutline>, pages: List<BookPage>): List<AdjustedOutline> {
        val endPage = pages.firstOrNull { gutenbergEnd.containsMatchIn(it.text) } ?: return adjusted
        val endOffset = gutenbergEnd.find(endPage.text)!!.range.first
        val target = adjusted.indexOfFirst { row ->
            row.kind == TocNodeKind.BACK_MATTER &&
                (row.item.page > endPage.number || (row.item.page == endPage.number && row.item.startOffset >= endOffset))
        }
        if (target < 0) return adjusted
        val opensLater = adjusted.any { row ->
            row !== adjusted[target] && row.kind != TocNodeKind.BACK_MATTER &&
                (row.item.page > endPage.number || (row.item.page == endPage.number && row.item.startOffset >= endOffset)) &&
                (row.item.page < adjusted[target].item.page ||
                    (row.item.page == adjusted[target].item.page && row.item.startOffset < adjusted[target].item.startOffset))
        }
        if (opensLater) return adjusted
        return adjusted.mapIndexed { index, row ->
            if (index != target) row
            else row.copy(item = row.item.copy(page = endPage.number, startOffset = endOffset))
        }
    }

    /** One count per sampled page: wrong when the shown header is not an open source entry at that page start. */
    internal fun scoreHeaders(
        adjusted: List<AdjustedOutline>,
        pages: List<Int>,
        headerAt: (Int) -> String,
    ): Int = pages.count { page ->
        val header = BookTocWaterfall.cleanTocTitle(headerAt(page))
        val expected = expectedHeaderTitles(adjusted, page, 0).map(BookTocWaterfall::cleanTocTitle)
        if (expected.isEmpty()) header.isNotEmpty() else header !in expected
    }

    private fun shallowBand(items: List<BookOutlineItem>): NativeOutline? {
        if (items.isEmpty()) return null
        val byLevel = items.groupBy { it.level }
        val usable = byLevel.mapValues { (_, rows) ->
            rows.filter { keepAsChapterCandidate(it.title) }
        }
        val topLevel = byLevel.keys.min()
        val top = usable.getValue(topLevel)
        val band = byLevel.keys.sorted().firstOrNull { level ->
            level != topLevel && usable.getValue(level).size in 4..40
        }
        // A one- or two-entry top is usually a wrapper. A deeper band of chapter size is the count.
        if (band != null && (top.size < 4 || top.size > 60)) {
            return NativeOutline("outline-level", usable.getValue(band).map { it.page }.sorted())
        }
        if (top.size in 1..60) return NativeOutline("outline-top", top.map { it.page }.sorted())
        if (band != null) return NativeOutline("outline-level", usable.getValue(band).map { it.page }.sorted())
        return null
    }

    private fun keepAsChapterCandidate(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        if (frontMatter.matches(trimmed)) return false
        if (bareNumber.matches(trimmed)) return false
        if (looksLikeBodyDump(trimmed)) return false
        if (dottedSection.containsMatchIn(trimmed)) return false
        return true
    }

    internal fun isNumberedChapter(title: String): Boolean {
        val trimmed = title.trim()
        if (dottedSection.containsMatchIn(trimmed)) return false
        val match = numberedChapter.matchEntire(trimmed) ?: return false
        if (match.groupValues[2].length < 4) return false
        if (looksLikeBodyDump(trimmed)) return false
        if (frontMatter.matches(trimmed)) return false
        return true
    }

    private fun looksSequential(items: List<BookOutlineItem>): Boolean {
        val numbers = items.map { numberedChapter.matchEntire(it.title.trim())!!.groupValues[1].toInt() }
        if (numbers.distinct().size != numbers.size) return false
        val min = numbers.min()
        val max = numbers.max()
        if (min > 2) return false
        return max - min + 1 <= numbers.size + 2
    }

    internal fun looksLikeBodyDump(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.length >= 180) return true
        val cjkStops = trimmed.count { it in "。！？" }
        if (cjkStops >= 1 && trimmed.length >= 30) return true
        val words = trimmed.split(Regex("""\s+""")).count { it.isNotEmpty() }
        val dots = trimmed.count { it == '.' }
        return words >= 40 && dots >= 2
    }

    private fun selfCheck(options: Options): Int {
        Files.createDirectories(options.library)
        val book = options.library.resolve("self-check.md")
        Files.writeString(
            book,
            """
            # Copyright

            Example notice for the audit fixture. All rights reserved.

            # Chapter 1 Opening

            This sentence belongs to the first made-up chapter.

            # Chapter 2 Later

            This sentence belongs to the second made-up chapter.
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        val nested = options.copy(
            selfCheck = false,
            books = listOf(book),
            library = options.library.resolve("library"),
        )
        Files.createDirectories(nested.library)
        val report = measure(nested)
        writeReport(options.out, report)
        val row = report.books.single()
        check(row.error == null) { "Self-check import failed: ${row.error}" }
        check(row.pages >= 1)
        check(row.softwareChapters >= 1) { "Self-check saw no chapter." }
        println("AUDIT self-check pages=${row.pages} chapters=${row.softwareChapters}")
        return 0
    }

    private fun measure(options: Options): Report {
        val rows = ArrayList<BookRow>()
        options.books.forEachIndexed { index, path ->
            val started = System.nanoTime()
            val row = try {
                measureOne(options, index, path, started)
            } catch (error: Throwable) {
                BookRow.error(path, ((System.nanoTime() - started) / 1_000_000L), error)
            }
            rows.add(row)
            writeReport(options.out, Report(options.revision, rows.toList()))
        }
        return Report(options.revision, rows)
    }

    private fun measureOne(options: Options, index: Int, source: Path, started: Long): BookRow {
        require(Files.isRegularFile(source)) { "Not a file." }
        val fileName = source.fileName.toString()
        val truth = options.truth[fileName.lowercase()]
        val pdfOutline = if (source.extension.equals("pdf", true)) readPdfOutline(source) else emptyList()
        Files.createDirectories(options.library)
        val work = Files.createTempDirectory(options.library, "audit-")
        try {
            val library = TextbookLibrary(work.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val isPdf = source.extension.equals("pdf", true)
            val staged = if (isPdf) library.stageBookImportTier0(source) else library.stageBookImport(source)
            val firstOpen = staged.book.readingPage
            val indexed = if (staged.book.format == "pdf" && staged.book.indexState != "READY") {
                library.indexBookTier1(staged.book.id)
            } else staged.book
            val outline = when (indexed.format) {
                "pdf" -> pdfOutline
                "epub" -> TextbookExtraction.epubNavigation(source, indexed.pages)?.outline.orEmpty()
                else -> emptyList()
            }
            val classified = when {
                outline.isNotEmpty() -> classifyOutline(outline)
                else -> classifyHeadingLines(indexed.pages)
            }
            val reader = if (outline.isNotEmpty()) readerStructure(outline) else {
                ReaderStructure(classified.pages, classified.pages.firstOrNull(), classified.pages.firstOrNull())
            }
            val autoPages = classified.pages
            val chapterPages = if (outline.isNotEmpty()) reader.chapterPages else classified.pages
            val autoBody = reader.bodyStart
            val nativeChapters = truth?.chapters ?: chapterPages.size
            val nativeBody = truth?.bodyStart ?: reader.bodyStart
            val nativeFirst = reader.firstChapter
            val softwareBody = BookBoundaries.bodyStart(indexed.pages, indexed.structure).importPage
            val softwareFirst = indexed.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }.minOfOrNull { it.startPage }
            val header = headerMismatches(indexed, outline, options.headerSamples, nativeFirst ?: nativeBody)
            val nodes = indexed.tocNodes
            val roles = indexed.structure?.pageRoles
            val bodyLike = nodes.count { looksLikeBodyDump(it.title) }
            val maxTitle = nodes.maxOfOrNull { it.title.trim().length } ?: 0
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            println(
                "AUDIT file=$fileName pages=${indexed.pages.size} softwareChapters=${nodes.count { it.kind == TocNodeKind.CHAPTER }} " +
                    "native=$nativeChapters body=$nativeBody first=$nativeFirst swBody=$softwareBody swFirst=$softwareFirst " +
                    "open=$firstOpen headerWrong=${header.wrong} ms=$elapsed",
            )
            return BookRow(
                file = fileName,
                format = indexed.format,
                pages = indexed.pages.size,
                bytes = Files.size(source),
                nativeMethod = if (truth?.chapters != null) "given" else if (outline.isNotEmpty()) "reader-kind" else classified.method,
                autoMethod = classified.method,
                nativeChapters = nativeChapters,
                autoChapters = autoPages.size,
                nativeChapterPages = chapterPages,
                nativeBodyStart = nativeBody,
                autoBodyStart = autoBody,
                nativeFirstChapter = nativeFirst,
                softwareFirstChapter = softwareFirst,
                softwareChapters = nodes.count { it.kind == TocNodeKind.CHAPTER },
                softwareSections = nodes.count { it.kind == TocNodeKind.SECTION || it.kind == TocNodeKind.SUBSECTION },
                softwareTocNodes = nodes.size,
                softwareBodyStart = softwareBody,
                softwareBodyRole = roles?.get(softwareBody)?.name,
                firstOpenPage = firstOpen,
                firstOpenRole = roles?.get(firstOpen)?.name,
                outlineItems = outline.size,
                readingPageAfterIndex = indexed.readingPage,
                headerSamples = header.samples,
                headerWrong = header.wrong,
                headerKinds = header.kinds,
                tocBodyLikeNodes = bodyLike,
                tocMaxTitleChars = maxTitle,
                structureSource = indexed.structure?.source,
                indexState = indexed.indexState,
                ocrPendingPages = indexed.pages.count { it.ocrPending },
                elapsedMs = elapsed,
                error = null,
            )
        } finally {
            if (!options.keepLibrary) {
                work.toFile().deleteRecursively()
            }
        }
    }

    private fun classifyHeadingLines(pages: List<BookPage>): NativeOutline {
        val pagesHit = ArrayList<Int>()
        for (page in pages) {
            for (raw in page.text.lineSequence()) {
                val line = raw.trim().trimStart('#').trim()
                if (explicitChapter.containsMatchIn(line) || isNumberedChapter(line)) {
                    pagesHit += page.number
                    break
                }
            }
        }
        if (pagesHit.isEmpty()) return NativeOutline("none", emptyList())
        return NativeOutline("heading-lines", pagesHit.distinct().sorted())
    }

    private data class HeaderScore(val samples: Int, val wrong: Int?, val kinds: Map<String, Int>)

    private fun laidAt(page: Int) = BookLaidPage(
        sources = listOf(BookPageSlice(page, 0, 0)),
        chapter = 0,
        title = null,
        chapterNumber = null,
        opener = false,
        text = AnnotatedString(""),
        placeholders = emptyList(),
        topPx = 0,
        heightPx = 0,
    )

    /** Page-start header from [BookBoundaries.runningHeaderTitle], compared with the source outline at that page start. */
    private fun headerMismatches(
        book: Textbook,
        outline: List<BookOutlineItem>,
        sampleCount: Int,
        contentStart: Int?,
    ): HeaderScore {
        if (outline.isEmpty()) return HeaderScore(0, null, emptyMap())
        val adjusted = backMatterFromGutenbergEnd(adjustOutline(outline), book.pages)
        val lastPage = book.pages.lastOrNull()?.number ?: return HeaderScore(0, null, emptyMap())
        val start = (contentStart ?: adjusted.firstOrNull()?.item?.page ?: 1).coerceIn(1, lastPage)
        val chosen = samplePages(start, lastPage, sampleCount)
        val kinds = LinkedHashMap<String, Int>()
        for (page in chosen) {
            val node = BookBoundaries.activeTocNode(book.tocNodes, page, 0, null, book.page(page)?.text)
            val kindName = node?.kind?.name ?: "NONE"
            kinds[kindName] = (kinds[kindName] ?: 0) + 1
        }
        val wrong = scoreHeaders(adjusted, chosen) { page -> BookBoundaries.runningHeaderTitle(book, laidAt(page)) }
        return HeaderScore(chosen.size, wrong, kinds)
    }

    internal fun samplePages(start: Int, end: Int, count: Int): List<Int> {
        if (end < start || count <= 0) return emptyList()
        val span = (start..end).toList()
        if (span.size <= count) return span
        return (0 until count).map { index ->
            val at = (index.toLong() * span.lastIndex / (count - 1)).toInt()
            span[at]
        }.distinct()
    }

    private fun readPdfOutline(source: Path): List<BookOutlineItem> = runCatching {
        BookPdfLoader.read(source.toFile(), ::extractBookPdfOutline)
    }.getOrDefault(emptyList())

    private fun writeReport(out: Path, report: Report) {
        val payload = buildJsonObject {
            put("import", "windows-lazy-pdf-tier0-then-tier1")
            put("revision", report.revision)
            put("books", buildJsonArray {
                report.books.forEach { row -> add(row.toJson()) }
            })
        }
        val json = Json { prettyPrint = true }
        Files.createDirectories(out.absolute().parent)
        Files.writeString(out, json.encodeToString(JsonObject.serializer(), payload) + "\n", StandardCharsets.UTF_8)
    }

    private data class Truth(val chapters: Int?, val bodyStart: Int?)

    private data class Options(
        val library: Path,
        val out: Path,
        val books: List<Path>,
        val truth: Map<String, Truth>,
        val headerSamples: Int,
        val revision: String,
        val selfCheck: Boolean,
        val keepLibrary: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                var library: Path? = null
                var out: Path? = null
                var truthPath: Path? = null
                var booksFile: Path? = null
                var samples = 10
                var revision = ""
                var selfCheck = false
                var keepLibrary = false
                val bookPaths = ArrayList<Path>()
                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    fun next(): String {
                        index++
                        require(index < args.size) { "Missing value after $arg" }
                        return args[index]
                    }
                    when (arg) {
                        "--library" -> library = Path.of(next())
                        "--out" -> out = Path.of(next())
                        "--truth" -> truthPath = Path.of(next())
                        "--books-file" -> booksFile = Path.of(next())
                        "--header-samples" -> samples = next().toInt()
                        "--revision" -> revision = next()
                        "--self-check" -> selfCheck = true
                        "--keep-library" -> keepLibrary = true
                        else -> {
                            require(!arg.startsWith("--")) { "Unknown argument $arg" }
                            bookPaths.add(Path.of(arg))
                        }
                    }
                    index++
                }
                val libraryPath = requireNotNull(library) { "Pass --library, a directory outside the git checkout." }
                val outPath = requireNotNull(out) { "Pass --out, a json file outside the git checkout." }
                require(!insideCheckout(libraryPath)) { "Library must be outside the git checkout." }
                require(!insideCheckout(outPath)) { "Report must be outside the git checkout." }
                if (booksFile != null) {
                    Files.readAllLines(booksFile, StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { bookPaths.add(Path.of(it)) }
                }
                return Options(
                    library = libraryPath,
                    out = outPath,
                    books = bookPaths,
                    truth = truthPath?.let(::readTruth).orEmpty(),
                    headerSamples = samples.coerceIn(1, 30),
                    revision = revision,
                    selfCheck = selfCheck,
                    keepLibrary = keepLibrary,
                )
            }

            private fun readTruth(path: Path): Map<String, Truth> {
                val root = Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8))
                val rows = when (root) {
                    is JsonArray -> root
                    is JsonObject -> root["books"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                return rows.map { it.jsonObject }.associate { row ->
                    val file = row.getValue("file").jsonPrimitive.content
                    file.lowercase() to Truth(
                        chapters = row["chapters"]?.jsonPrimitive?.content?.toIntOrNull(),
                        bodyStart = row["bodyStart"]?.jsonPrimitive?.content?.toIntOrNull(),
                    )
                }
            }
        }
    }

    internal fun insideCheckout(path: Path): Boolean {
        var dir = path.absolute().normalize().let { if (Files.isDirectory(it)) it else it.parent }
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isDirectory(dir.resolve("shared/books"))) {
                return true
            }
            dir = dir.parent
        }
        return false
    }

    private data class Report(val revision: String, val books: List<BookRow>)

    private data class BookRow(
        val file: String,
        val format: String,
        val pages: Int,
        val bytes: Long,
        val nativeMethod: String,
        val autoMethod: String,
        val nativeChapters: Int,
        val autoChapters: Int,
        val nativeChapterPages: List<Int>,
        val nativeBodyStart: Int?,
        val autoBodyStart: Int?,
        val nativeFirstChapter: Int?,
        val softwareFirstChapter: Int?,
        val softwareChapters: Int,
        val softwareSections: Int,
        val softwareTocNodes: Int,
        val softwareBodyStart: Int,
        val softwareBodyRole: String?,
        val firstOpenPage: Int,
        val firstOpenRole: String?,
        val outlineItems: Int,
        val readingPageAfterIndex: Int,
        val headerSamples: Int,
        val headerWrong: Int?,
        val headerKinds: Map<String, Int>,
        val tocBodyLikeNodes: Int,
        val tocMaxTitleChars: Int,
        val structureSource: String?,
        val indexState: String,
        val ocrPendingPages: Int,
        val elapsedMs: Long,
        val error: String?,
    ) {
        fun toJson() = buildJsonObject {
            put("file", file)
            put("format", format)
            put("pages", pages)
            put("bytes", bytes)
            put("nativeMethod", nativeMethod)
            put("autoMethod", autoMethod)
            put("nativeChapters", nativeChapters)
            put("autoChapters", autoChapters)
            put("nativeChapterPages", buildJsonArray { nativeChapterPages.take(80).forEach { add(JsonPrimitive(it)) } })
            put("nativeChapterPagesTruncated", nativeChapterPages.size > 80)
            put("nativeBodyStart", nativeBodyStart?.let(::JsonPrimitive) ?: JsonNull)
            put("autoBodyStart", autoBodyStart?.let(::JsonPrimitive) ?: JsonNull)
            put("nativeFirstChapter", nativeFirstChapter?.let(::JsonPrimitive) ?: JsonNull)
            put("softwareFirstChapter", softwareFirstChapter?.let(::JsonPrimitive) ?: JsonNull)
            put("softwareChapters", softwareChapters)
            put("softwareSections", softwareSections)
            put("softwareTocNodes", softwareTocNodes)
            put("softwareBodyStart", softwareBodyStart)
            put("softwareBodyRole", softwareBodyRole ?: "")
            put("firstOpenPage", firstOpenPage)
            put("firstOpenRole", firstOpenRole ?: "")
            put("outlineItems", outlineItems)
            put("readingPageAfterIndex", readingPageAfterIndex)
            put("headerSamples", headerSamples)
            put("headerWrong", headerWrong?.let(::JsonPrimitive) ?: JsonNull)
            put("headerKinds", buildJsonObject { headerKinds.forEach { (kind, count) -> put(kind, count) } })
            put("tocBodyLikeNodes", tocBodyLikeNodes)
            put("tocBodyMixed", tocBodyLikeNodes > 0)
            put("tocMaxTitleChars", tocMaxTitleChars)
            put("structureSource", structureSource ?: "")
            put("indexState", indexState)
            put("ocrPendingPages", ocrPendingPages)
            put("elapsedMs", elapsedMs)
            if (error != null) put("error", error)
        }

        companion object {
            fun error(path: Path, elapsedMs: Long, error: Throwable): BookRow {
                val message = error.javaClass.simpleName + ": " + error.message.orEmpty().lineSequence().first().take(160)
                println("AUDIT file=${path.fileName} error=${error.javaClass.simpleName}")
                return BookRow(
                    file = path.fileName.toString(),
                    format = path.extension.lowercase(),
                    pages = 0,
                    bytes = runCatching { Files.size(path) }.getOrDefault(0L),
                    nativeMethod = "none",
                    autoMethod = "none",
                    nativeChapters = 0,
                    autoChapters = 0,
                    nativeChapterPages = emptyList(),
                    nativeBodyStart = null,
                    autoBodyStart = null,
                    nativeFirstChapter = null,
                    softwareFirstChapter = null,
                    softwareChapters = 0,
                    softwareSections = 0,
                    softwareTocNodes = 0,
                    softwareBodyStart = 0,
                    softwareBodyRole = null,
                    firstOpenPage = 0,
                    firstOpenRole = null,
                    outlineItems = 0,
                    readingPageAfterIndex = 0,
                    headerSamples = 0,
                    headerWrong = null,
                    headerKinds = emptyMap(),
                    tocBodyLikeNodes = 0,
                    tocMaxTitleChars = 0,
                    structureSource = null,
                    indexState = "",
                    ocrPendingPages = 0,
                    elapsedMs = elapsedMs,
                    error = message,
                )
            }
        }
    }
}
