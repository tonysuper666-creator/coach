package com.coach.desktop.books

import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 供 `tools/book-stance/run.py` 调用的无界面准备工具：
 * 走正式的 [TextbookLibrary.importBook] 导入管线，使用和桌面客户端完全一致的
 * [ExplainWire.selectionBlocks]、[ClassicalAnnotationLayer.buildExplainContext]、
 * [ExplainWire.selectionRequest] 与 [ImportedBookWire.snapshot] 构造请求，并生成
 * `book-stance-forced.json`（按真实导入的 SHA-256 哈希索引）。
 */
object BookStanceCorpusPrep {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parseArgs(args)
        val corpusDir = Path.of(requireNotNull(opts["--corpus"]) { "missing --corpus" }).toAbsolutePath().normalize()
        val questionsPath = Path.of(requireNotNull(opts["--questions"]) { "missing --questions" }).toAbsolutePath().normalize()
        val cacheDir = Path.of(requireNotNull(opts["--cache"]) { "missing --cache" }).toAbsolutePath().normalize()
        val entry = opts["--entry"] ?: "ask"
        val onlyIds = opts["--only"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val includeLocked = (opts["--set"] == "locked")

        Files.createDirectories(cacheDir)
        val libDir = cacheDir.resolve("library")
        val library = TextbookLibrary(libDir, lazyPdfExtras = true, todayCardEnabled = false)
        val imports = ImportedBookWire(library)
        val specRoot = Json.parseToJsonElement(Files.readString(questionsPath)).jsonObject
        val booksSpec = specRoot.getValue("books").jsonObject

        val targetItems: List<JsonObject> = when (entry) {
            "explain" -> specRoot.getValue("explainDefaults").jsonArray.map { it.jsonObject }
            "ask" -> specRoot.getValue("questions").jsonArray.map { it.jsonObject }.filter { q ->
                val qEntry = q["entry"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "ask"
                val hasLock = q["lock"] != null && q["lock"] !is JsonNull
                qEntry == "ask" && (if (includeLocked) hasLock else !hasLock)
            }
            else -> throw IllegalArgumentException("Unsupported entry for stage 1 prep: $entry")
        }.filter { q ->
            val id = (q["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            onlyIds == null || id in onlyIds
        }

        val neededBookKeys = targetItems.mapNotNull { (it["book"] as? JsonPrimitive)?.contentOrNull }.toSet()
        val importedByKey = LinkedHashMap<String, Textbook>()
        val existingByHash = library.catalog().associateBy { it.id }

        // Always import all 10 corpus books when no --only filter, or only needed books when filtered
        val keysToImport = if (onlyIds == null) booksSpec.keys else neededBookKeys
        for (bookKey in keysToImport) {
            val meta = booksSpec[bookKey]?.jsonObject ?: error("Unknown book key: $bookKey")
            val relFile = (meta["file"] as JsonPrimitive).content
            val filePath = corpusDir.resolve(bookKey).resolve(relFile)
            require(Files.isRegularFile(filePath)) { "Missing corpus book file: $filePath" }
            val sha = sha256File(filePath)
            val book = if (sha in existingByHash) {
                runCatching { library.book(sha) }.getOrElse { library.importBook(filePath) }
            } else {
                System.err.println("[prep] importing $bookKey ($relFile)...")
                library.importBook(filePath)
            }
            ExplainConsent(library.directory).allow(book.id)
            importedByKey[bookKey] = book
        }

        // Write book-stance-forced.json using real imported SHA-256 hashes (also reusable on winbox!)
        val forcedJson = buildJsonObject {
            for ((bookKey, book) in importedByKey) {
                val meta = booksSpec.getValue(bookKey).jsonObject
                put(book.id, buildJsonObject {
                    put("bookKey", bookKey)
                    put("title", book.title)
                    put("defaultStance", (meta["defaultStance"] as JsonPrimitive).content)
                    val sec = (meta["secondaryStance"] as? JsonPrimitive)?.contentOrNull
                    if (sec != null) put("secondaryStance", sec)
                    put("guards", meta["guards"] ?: JsonArray(emptyList()))
                })
            }
        }
        val forcedText = json.encodeToString(JsonObject.serializer(), forcedJson)
        Files.writeString(cacheDir.resolve(ForcedFileBookStanceSource.FILE_NAME), forcedText)
        Files.writeString(libDir.resolve(ForcedFileBookStanceSource.FILE_NAME), forcedText)

        val pageTexts = LinkedHashMap<String, String>()
        val preparedItems = buildJsonArray {
            for (item in targetItems) {
                val id = (item["id"] as JsonPrimitive).content
                val bookKey = (item["book"] as JsonPrimitive).content
                val locator = (item["locator"] as JsonPrimitive).content
                val book = importedByKey.getValue(bookKey)
                val bookPages = maxOf(book.pages.size, book.pages.maxOfOrNull { it.number } ?: 1)

                val located = locateBlock(book, locator)
                    ?: error("Locator not found in $bookKey ($id): $locator")
                val (chosen, sectionIndex) = located
                val sec = book.lessons.getOrNull(sectionIndex)
                val classicalCtx = ClassicalAnnotationLayer.buildExplainContext(book, chosen, sec)
                val secCtx = if (sec != null) {
                    ExplainSectionContext(
                        location = sec.breadcrumb,
                        previousRecap = null,
                        nextTitle = book.lessons.getOrNull(sectionIndex + 1)?.title,
                        sectionBlocks = sec.blocks,
                        annotationContext = classicalCtx,
                    )
                } else {
                    ExplainSectionContext(
                        location = book.title,
                        previousRecap = null,
                        nextTitle = null,
                        sectionBlocks = book.sourceBlocks().filter { it.page == chosen.page && !it.heading }.ifEmpty { listOf(chosen) },
                        annotationContext = classicalCtx,
                    )
                }
                val sentBlocks = ExplainWire.selectionBlocks(chosen, secCtx)
                val snapshot = imports.snapshot(sentBlocks)
                    ?: error("Failed to build localSource snapshot for $id ($bookKey)")

                // Record verbatim page texts for every page referenced in sentBlocks so check.py verifies UTF-16 exactness
                for (blk in sentBlocks) {
                    val key = "${book.id}:${blk.page}"
                    if (key !in pageTexts) {
                        pageTexts[key] = book.page(blk.page)?.text.orEmpty()
                    }
                }

                val stanceWire = (item["stance"] as JsonPrimitive).content
                val stance = requireNotNull(BookStance.fromWire(stanceWire)) { "Bad stance: $stanceWire" }
                val initialStanceWire = (item["initialStance"] as? JsonPrimitive)?.contentOrNull
                val initialStance = initialStanceWire?.let { requireNotNull(BookStance.fromWire(it)) }
                val guards = (item["guards"] as? JsonArray).orEmpty().mapNotNull {
                    BookStanceGuard.fromWire((it as? JsonPrimitive)?.contentOrNull)
                }.toSet()
                val rawRtp = item["readThroughPage"]
                val readThroughPage: Int? = when {
                    rawRtp == null || rawRtp is JsonNull -> null
                    rawRtp is JsonPrimitive && rawRtp.isString && rawRtp.content == "locator" -> chosen.page
                    rawRtp is JsonPrimitive && !rawRtp.isString -> rawRtp.content.toIntOrNull()
                    else -> null
                }
                val strictSinglePage = (item["strictSinglePage"] as? JsonPrimitive)?.booleanOrNull == true
                val effectiveReadThroughPage: Int? = if (BookStanceGuard.FICTION in guards) {
                    if (readThroughPage != null) maxOf(readThroughPage, chosen.page) else chosen.page
                } else null

                val kind = if (entry == "explain") "explain" else "ask"
                val question = (item["question"] as? JsonPrimitive)?.contentOrNull

                fun buildWire(targetStance: BookStance): JsonObject {
                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val base = ExplainWire.selectionRequest(
                        requestId = reqId,
                        kind = kind,
                        chosen = chosen,
                        blocks = sentBlocks,
                        sectionContext = secCtx,
                        question = if (kind == "ask") question else null,
                        stanceRequest = ExplainStanceRequest(
                            stance = targetStance,
                            guards = guards,
                            readThroughPage = readThroughPage,
                            bookPages = bookPages,
                        ),
                    )
                    return BookLessonWire.withSource(base, snapshot)
                }

                add(buildJsonObject {
                    put("id", id)
                    put("entry", entry)
                    put("bookKey", bookKey)
                    put("bookHash", book.id)
                    put("bookTitle", book.title)
                    put("bookPages", bookPages)
                    put("locator", locator)
                    put("selectionPage", chosen.page)
                    put("selectionBlockId", chosen.id)
                    if (readThroughPage != null) put("readThroughPage", readThroughPage) else put("readThroughPage", JsonNull)
                    if (effectiveReadThroughPage != null) put("effectiveReadThroughPage", effectiveReadThroughPage) else put("effectiveReadThroughPage", JsonNull)
                    put("strictSinglePage", strictSinglePage)
                    put("meta", item)
                    put("request", buildWire(stance))
                    if (initialStance != null) {
                        put("initialStance", initialStance.wire)
                        put("initialRequest", buildWire(initialStance))
                    }
                })
            }
        }

        val preparedPayload = buildJsonObject {
            put("entry", entry)
            put("items", preparedItems)
            put("pages", buildJsonObject {
                for ((k, v) in pageTexts) put(k, v)
            })
        }
        val outFile = cacheDir.resolve("prepared-$entry.json")
        Files.writeString(outFile, json.encodeToString(JsonObject.serializer(), preparedPayload))
        System.err.println("[prep] wrote ${preparedItems.size} items to $outFile")
    }

    private fun locateBlock(book: Textbook, locator: String): Pair<SourceBlock, Int>? {
        val needle = locator.trim()
        val normNeedle = needle.replace(Regex("\\s+"), " ")
        fun matches(text: String): Boolean {
            if (text.contains(needle, ignoreCase = true)) return true
            return text.replace(Regex("\\s+"), " ").contains(normNeedle, ignoreCase = true)
        }
        val allBlocks = book.sourceBlocks()
        fun isTocPage(page: Int): Boolean {
            val text = book.page(page)?.text ?: return false
            val head = text.take(260)
            if (Regex("""(?i)\btable\s+of\s+contents\b""").containsMatchIn(head)) return true
            if (Regex("""(?i)^\s*contents\s*$""", RegexOption.MULTILINE).containsMatchIn(head)) return true
            if (Regex("""(?:\.\s*){5,}""").findAll(text).count() >= 3) return true
            val chapterLines = text.lineSequence().count { Regex("""(?i)^\s*chapter\s+\d+""").containsMatchIn(it) }
            if (chapterLines >= 4) return true
            return false
        }
        fun isBodyPage(page: Int): Boolean = !isTocPage(page)
        fun looksLikeTocLine(text: String): Boolean {
            val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
            if (lines.size >= 4) {
                val tocLike = lines.count { Regex("""(\.{3,}|\s+\d{1,4})$""").containsMatchIn(it) }
                if (tocLike * 2 >= lines.size) return true
            }
            return Regex("""\.{4,}\s*\d+""").containsMatchIn(text)
        }
        fun pickBodyBlockInLesson(lesson: BookLesson, hit: SourceBlock): SourceBlock {
            if (!hit.heading) return hit
            val idx = lesson.blocks.indexOfFirst { it.id == hit.id }
            if (idx >= 0) {
                lesson.blocks.drop(idx + 1).firstOrNull { !it.heading && it.text.length >= 20 }?.let { return it }
            }
            return lesson.blocks.firstOrNull { !it.heading } ?: hit
        }

        // 1. Prefer body blocks inside BODY-page lessons, skipping TOC / front-matter pages.
        for ((secIdx, lesson) in book.lessons.withIndex()) {
            if (!isBodyPage(lesson.firstPage) && !isBodyPage(lesson.lastPage)) continue
            val bodyHit = lesson.blocks.firstOrNull { !it.heading && isBodyPage(it.page) && !looksLikeTocLine(it.text) && matches(it.text) }
            if (bodyHit != null) return bodyHit to secIdx
            val headingHit = lesson.blocks.firstOrNull { it.heading && isBodyPage(it.page) && matches(it.text) }
            if (headingHit != null) return pickBodyBlockInLesson(lesson, headingHit) to secIdx
            if (matches(lesson.title) && isBodyPage(lesson.firstPage)) {
                lesson.blocks.firstOrNull { !it.heading && isBodyPage(it.page) }?.let { return it to secIdx }
            }
        }
        // 2. Fallback: BODY pages in allBlocks
        val bodyIdx = allBlocks.indexOfFirst { isBodyPage(it.page) && !looksLikeTocLine(it.text) && matches(it.text) }
        if (bodyIdx >= 0) {
            val hit = allBlocks[bodyIdx]
            val chosen = if (hit.heading) {
                allBlocks.drop(bodyIdx + 1).firstOrNull { !it.heading && it.page == hit.page }
                    ?: allBlocks.drop(bodyIdx + 1).firstOrNull { !it.heading }
                    ?: hit
            } else hit
            val secIdx = book.lessons.indexOfFirst { l -> l.blocks.any { it.id == chosen.id } }
                .takeIf { it >= 0 }
                ?: book.lessons.indexOfFirst { chosen.page in it.firstPage..it.lastPage }
            return chosen to secIdx
        }
        // 3. Final fallback: any lesson/block/page match
        for ((secIdx, lesson) in book.lessons.withIndex()) {
            val hit = lesson.blocks.firstOrNull { !it.heading && matches(it.text) }
            if (hit != null) return hit to secIdx
        }
        for (p in book.pages) {
            if (matches(p.text)) {
                val body = allBlocks.firstOrNull { it.page == p.number && !it.heading }
                    ?: SourceBlock("${book.id}:p${p.number}:0", p.number, 0, minOf(p.text.length, 2000), false, p.text.take(2000))
                val secIdx = book.lessons.indexOfFirst { p.number in it.firstPage..it.lastPage }
                return body to secIdx
            }
        }
        return null
    }

    private fun sha256File(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(65_536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val k = args[i]
            if (k.startsWith("--") && i + 1 < args.size) {
                out[k] = args[i + 1]
                i += 2
            } else {
                i++
            }
        }
        return out
    }
}
