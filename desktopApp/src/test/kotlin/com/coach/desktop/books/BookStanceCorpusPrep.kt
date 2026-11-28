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
        val mode = opts["--mode"] ?: "forced"
        val onlyIds = opts["--only"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val includeLocked = (opts["--set"] == "locked")
        val forcedConfig = opts["--forced-config"]?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
        val noSeedForced = opts["--no-seed-forced"]?.equals("true", ignoreCase = true) == true

        val preparedPayload = prepare(
            corpusDir = corpusDir,
            questionsPath = questionsPath,
            cacheDir = cacheDir,
            entry = entry,
            mode = mode,
            onlyIds = onlyIds,
            includeLocked = includeLocked,
            forcedConfig = forcedConfig,
            seedForcedFile = (mode == "forced" && forcedConfig == null && !noSeedForced),
        )
        val outFile = cacheDir.resolve("prepared-$entry.json")
        Files.writeString(outFile, json.encodeToString(JsonObject.serializer(), preparedPayload))
        System.err.println("[prep] wrote ${preparedPayload.getValue("items").jsonArray.size} items to $outFile")
    }

    /**
     * 通过客户端正式 [BookStanceSources.forLibrary] 判定每本书的立场、限制标记与 `stanceSource`：
     * - 已判定：从 [BookStanceResolution.Determined] 读取 `defaultStance`、`guards` 和 `source`（仅换立场题使用 `switchTo` 切换目标立场，仍保留客户端判定的 `guards`）；
     * - 未判定（如缺少 `book-stance-forced.json` 或书不在文件里）：记录 `stanceSource = "undetermined"`，按真实客户端上线边界发旧路径请求（不带 `stance` / `guards`），让验收脚本能捕获配置未生效。
     */
    internal fun prepare(
        corpusDir: Path,
        questionsPath: Path,
        cacheDir: Path,
        entry: String = "ask",
        mode: String = "forced",
        onlyIds: Set<String>? = null,
        includeLocked: Boolean = false,
        forcedConfig: Path? = null,
        seedForcedFile: Boolean = false,
    ): JsonObject {
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
            "collection" -> specRoot.getValue("questions").jsonArray.map { it.jsonObject }.filter { q ->
                val qEntry = q["entry"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "ask"
                val hasLock = q["lock"] != null && q["lock"] !is JsonNull
                val id = (q["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                (!hasLock) && (qEntry == "collection" || (qEntry == "ask" && id !in setOf("X1", "X2", "X3")))
            }
            else -> throw IllegalArgumentException("Unsupported entry for prep: $entry")
        }.filter { q ->
            val id = (q["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            onlyIds == null || id in onlyIds
        }

        val neededBookKeys = LinkedHashSet<String>()
        for (it in targetItems) {
            (it["book"] as? JsonPrimitive)?.contentOrNull?.let(neededBookKeys::add)
            (it["books"] as? JsonArray)?.forEach { b ->
                (b as? JsonPrimitive)?.contentOrNull?.let(neededBookKeys::add)
            }
        }
        val importedByKey = LinkedHashMap<String, Textbook>()
        val existingByHash = library.catalog().associateBy { it.id }

        // Always import all corpus books when no --only filter (or in collection mode so stance pairs exist), or only needed books when filtered
        val keysToImport = if (onlyIds == null || entry == "collection") booksSpec.keys else neededBookKeys
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

        val libForcedFile = libDir.resolve(ForcedFileBookStanceSource.FILE_NAME)
        if (forcedConfig != null) {
            Files.deleteIfExists(libForcedFile)
            if (Files.isRegularFile(forcedConfig)) {
                Files.copy(forcedConfig, libForcedFile)
            }
        } else if (seedForcedFile && mode == "forced") {
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
            Files.writeString(libForcedFile, forcedText)
        }

        val stanceSourceResolver = BookStanceSources.forLibrary(library)
        val pageTexts = LinkedHashMap<String, String>()
        val preparedItems = if (entry == "collection") {
            prepareCollectionItems(
                library = library,
                booksSpec = booksSpec,
                importedByKey = importedByKey,
                stanceSourceResolver = stanceSourceResolver,
                targetItems = targetItems,
                pageTexts = pageTexts,
            )
        } else buildJsonArray {
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

                // 真实走客户端 BookStanceSources.forLibrary(library).resolve(book) 判定立场、限制标记与来源
                val resolution = stanceSourceResolver.resolve(book)
                val determined = resolution as? BookStanceResolution.Determined
                val actualStanceSource = determined?.source ?: "undetermined"
                val resolvedGuards = determined?.guards.orEmpty()

                val rawRtp = item["readThroughPage"]
                val readThroughPage: Int? = when {
                    rawRtp == null || rawRtp is JsonNull -> null
                    rawRtp is JsonPrimitive && rawRtp.isString && rawRtp.content == "locator" -> chosen.page
                    rawRtp is JsonPrimitive && !rawRtp.isString -> rawRtp.content.toIntOrNull()
                    else -> null
                }
                val strictSinglePage = (item["strictSinglePage"] as? JsonPrimitive)?.booleanOrNull == true
                val effectiveReadThroughPage: Int? = if (BookStanceGuard.FICTION in resolvedGuards) {
                    if (readThroughPage != null) maxOf(readThroughPage, chosen.page) else chosen.page
                } else null

                val kind = if (entry == "explain") "explain" else "ask"
                val question = (item["question"] as? JsonPrimitive)?.contentOrNull

                fun buildWire(stanceReq: ExplainStanceRequest?): JsonObject {
                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val base = ExplainWire.selectionRequest(
                        requestId = reqId,
                        kind = kind,
                        chosen = chosen,
                        blocks = sentBlocks,
                        sectionContext = secCtx,
                        question = if (kind == "ask") question else null,
                        stanceRequest = stanceReq,
                    )
                    return BookLessonWire.withSource(base, snapshot)
                }

                val switchTarget = (item["switchTo"] as? JsonPrimitive)?.contentOrNull?.let(BookStance::fromWire)
                val hasInitialStep = item["initialStance"] != null && item["initialStance"] !is JsonNull
                val mainStanceReq = determined?.let { det ->
                    ExplainStanceRequest(
                        stance = switchTarget ?: det.defaultStance,
                        guards = det.guards,
                        readThroughPage = readThroughPage,
                        bookPages = bookPages,
                    )
                }
                val initialStanceReq = if (determined != null && hasInitialStep) {
                    ExplainStanceRequest(
                        stance = determined.defaultStance,
                        guards = determined.guards,
                        readThroughPage = readThroughPage,
                        bookPages = bookPages,
                    )
                } else null

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
                    put("stanceSource", actualStanceSource)
                    if (determined != null) {
                        put("resolvedDefaultStance", determined.defaultStance.wire)
                        put("resolvedGuards", buildJsonArray { determined.guards.forEach { add(JsonPrimitive(it.wire)) } })
                    } else {
                        put("resolvedDefaultStance", JsonNull)
                        put("resolvedGuards", JsonArray(emptyList()))
                    }
                    if (readThroughPage != null) put("readThroughPage", readThroughPage) else put("readThroughPage", JsonNull)
                    if (effectiveReadThroughPage != null) put("effectiveReadThroughPage", effectiveReadThroughPage) else put("effectiveReadThroughPage", JsonNull)
                    put("strictSinglePage", strictSinglePage)
                    put("meta", item)
                    put("request", buildWire(mainStanceReq))
                    if (initialStanceReq != null) {
                        put("initialStance", initialStanceReq.stance.wire)
                        put("initialRequest", buildWire(initialStanceReq))
                    }
                })
            }
        }

        return buildJsonObject {
            put("entry", entry)
            put("items", preparedItems)
            put("pages", buildJsonObject {
                for ((k, v) in pageTexts) put(k, v)
            })
        }
    }

    private fun prepareCollectionItems(
        library: TextbookLibrary,
        booksSpec: JsonObject,
        importedByKey: Map<String, Textbook>,
        stanceSourceResolver: BookStanceSource,
        targetItems: List<JsonObject>,
        pageTexts: MutableMap<String, String>,
    ): JsonArray {
        val booksByStance = LinkedHashMap<String, List<String>>()
        for (bookKey in booksSpec.keys) {
            val meta = booksSpec.getValue(bookKey).jsonObject
            val st = (meta["defaultStance"] as JsonPrimitive).content
            booksByStance[st] = booksByStance[st].orEmpty() + bookKey
        }

        fun ensureCollection(colId: String, title: String, bookIds: List<String>) {
            library.withCourseDatabase { db ->
                CourseStore.ensureReady(db)
                val now = System.currentTimeMillis()
                db.prepareStatement(
                    """INSERT INTO collections(id, kind, title, metadata_json, created_at, updated_at, sort_order, exam_date, last_active_anchor_json, source_hash, user_locked, archived)
                       VALUES(?, 'BOOKLIST', ?, '{}', ?, ?, 0, NULL, NULL, NULL, 0, 0)
                       ON CONFLICT(id) DO UPDATE SET title=excluded.title, updated_at=excluded.updated_at""",
                ).use { sql ->
                    sql.setString(1, colId)
                    sql.setString(2, title)
                    sql.setLong(3, now)
                    sql.setLong(4, now)
                    sql.executeUpdate()
                }
                db.prepareStatement("DELETE FROM collection_materials WHERE collection_id=?").use { sql ->
                    sql.setString(1, colId)
                    sql.executeUpdate()
                }
                CourseStore.addMaterials(db, colId, bookIds, trustedBookIds = bookIds.toSet())
            }
        }

        fun recordPages(book: Textbook) {
            for (pg in book.pages) {
                val key = "${book.id}:${pg.number}"
                if (key !in pageTexts) pageTexts[key] = pg.text
            }
        }

        val collectionIdsByKey = HashMap<List<String>, String>()
        for ((st, keys) in booksByStance) {
            val colId = "stance-col-$st"
            val ids = keys.map { importedByKey.getValue(it).id }
            ensureCollection(colId, "Collection-$st", ids)
            collectionIdsByKey[keys] = colId
        }
        val mixedKeys = listOf(
            "nonfiction/walden",
            "chinese/zhouyi",
            "textbooks/openstax-economics-2e",
            "fiction/pride-and-prejudice",
        )
        val mixedIds = mixedKeys.mapNotNull { importedByKey[it]?.id }
        if (mixedIds.size == mixedKeys.size) {
            ensureCollection("stance-col-mixed", "Collection-mixed", mixedIds)
            collectionIdsByKey[mixedKeys] = "stance-col-mixed"
        }

        return buildJsonArray {
            for (item in targetItems) {
                val id = (item["id"] as JsonPrimitive).content
                val qEntry = (item["entry"] as? JsonPrimitive)?.contentOrNull ?: "ask"
                val rawQuestion = (item["question"] as JsonPrimitive).content
                val cleanQuestion = rawQuestion
                    .replace(Regex("""^（点「换个角度\s*→\s*[^」]+」）"""), "")
                    .trim()

                val bookKeysForItem: List<String> = if (qEntry == "collection") {
                    (item["books"] as JsonArray).map { (it as JsonPrimitive).content }
                } else {
                    val primaryKey = (item["book"] as JsonPrimitive).content
                    val defStance = (booksSpec.getValue(primaryKey).jsonObject["defaultStance"] as JsonPrimitive).content
                    booksByStance[defStance] ?: listOf(primaryKey)
                }
                val colId = collectionIdsByKey[bookKeysForItem] ?: run {
                    val customId = "stance-col-${id.lowercase()}"
                    ensureCollection(customId, "Collection-$id", bookKeysForItem.map { importedByKey.getValue(it).id })
                    customId
                }

                val booksInCol = bookKeysForItem.map { it to importedByKey.getValue(it) }
                booksInCol.forEach { (_, b) -> recordPages(b) }

                val primaryBookKey = if (qEntry == "collection") bookKeysForItem.first() else (item["book"] as JsonPrimitive).content
                val primaryBook = importedByKey.getValue(primaryBookKey)
                val bookPages = maxOf(primaryBook.pages.size, primaryBook.pages.maxOfOrNull { it.number } ?: 1)

                val locator = (item["locator"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                var selectionPage = 1
                var selectionBlockId = ""
                val overrides = LinkedHashMap<String, Int?>()
                val fictionBooksJson = buildJsonObject {
                    for ((bk, bkObj) in booksInCol) {
                        val res = stanceSourceResolver.resolve(bkObj) as? BookStanceResolution.Determined
                        val isFiction = res != null && BookStanceGuard.FICTION in res.guards
                        if (isFiction) {
                            val missingKey = (item["expectMissingRecordBook"] as? JsonPrimitive)?.contentOrNull
                            val rawRtp = item["readThroughPage"]
                            val rtp: Int? = when {
                                missingKey == bk -> null
                                bk == "fiction/pride-and-prejudice" && rawRtp is JsonPrimitive && rawRtp.isString && rawRtp.content == "locator" -> {
                                    locateBlock(bkObj, locator)?.first?.page ?: 1
                                }
                                bk == primaryBookKey && rawRtp is JsonPrimitive && rawRtp.isString && rawRtp.content == "locator" -> {
                                    locateBlock(bkObj, locator)?.first?.page ?: 1
                                }
                                bk == primaryBookKey && rawRtp is JsonPrimitive && !rawRtp.isString -> {
                                    rawRtp.content.toIntOrNull()
                                }
                                else -> 1
                            }
                            overrides[bkObj.id] = rtp
                            if (rtp != null) put(bkObj.id, rtp)
                        }
                    }
                }

                if (locator.isNotBlank()) {
                    val locBook = if (qEntry == "collection") {
                        importedByKey["fiction/pride-and-prejudice"]?.takeIf { bk ->
                            bk.id in booksInCol.map { it.second.id } &&
                                ((item["readThroughPage"] as? JsonPrimitive)?.contentOrNull == "locator" ||
                                    (item["expectMissingRecordBook"] as? JsonPrimitive)?.contentOrNull != null)
                        } ?: primaryBook
                    } else primaryBook
                    locateBlock(locBook, locator)?.let { (chosen, _) ->
                        selectionPage = chosen.page
                        selectionBlockId = chosen.id
                    }
                }

                val questionText = if (qEntry == "ask") {
                    val shortHint = (booksSpec.getValue(primaryBookKey).jsonObject["title"] as JsonPrimitive).content
                    val needsLoc = locator.isNotBlank() && !cleanQuestion.contains(locator, ignoreCase = true)
                    val prefix = if (needsLoc) "在《$shortHint》（$locator）里，" else "在《$shortHint》里，"
                    if (shortHint in cleanQuestion && !needsLoc) cleanQuestion else "$prefix$cleanQuestion"
                } else {
                    cleanQuestion
                }

                val bundle = CollectionQaRetriever.retrieve(
                    library = library,
                    collectionId = colId,
                    question = questionText,
                    readThroughPageOverrides = overrides.takeIf { it.isNotEmpty() },
                )
                val primaryRes = stanceSourceResolver.resolve(primaryBook)
                val primaryDet = primaryRes as? BookStanceResolution.Determined
                val actualStanceSource = primaryDet?.source ?: "undetermined"

                fun buildColWire(overrideStance: BookStance?): JsonObject {
                    val reqId = UUID.randomUUID().toString().replace("-", "").take(16)
                    val stanceWire = if (bundle.allStancesDetermined) {
                        overrideStance?.wire
                            ?: bundle.bookStances.map { it.defaultStance }.distinct().singleOrNull()?.wire
                            ?: "mixed"
                    } else null
                    return CollectionQaJson.encodeRequest(
                        requestId = reqId,
                        collection = bundle.collection,
                        question = questionText,
                        blocks = bundle.blocks,
                        discrepancies = bundle.matchedDiscrepancies,
                        shortLabelByBookId = bundle.shortLabelByBookId,
                        stanceWire = stanceWire,
                        overrideStance = overrideStance,
                        bookStances = bundle.bookStances,
                    )
                }

                val switchTarget = (item["switchTo"] as? JsonPrimitive)?.contentOrNull?.let(BookStance::fromWire)
                val hasInitialStep = item["initialStance"] != null && item["initialStance"] !is JsonNull
                val missingKey = (item["expectMissingRecordBook"] as? JsonPrimitive)?.contentOrNull
                val missingBookObj = missingKey?.let { importedByKey[it] }

                add(buildJsonObject {
                    put("id", id)
                    put("entry", "collection")
                    put("bookKey", if (qEntry == "collection") bookKeysForItem.joinToString(",") else primaryBookKey)
                    put("bookHash", primaryBook.id)
                    put("bookTitle", primaryBook.title)
                    put("bookPages", bookPages)
                    put("locator", locator)
                    put("selectionPage", selectionPage)
                    put("selectionBlockId", selectionBlockId)
                    put("stanceSource", actualStanceSource)
                    if (primaryDet != null) {
                        put("resolvedDefaultStance", primaryDet.defaultStance.wire)
                        put("resolvedGuards", buildJsonArray { primaryDet.guards.forEach { add(JsonPrimitive(it.wire)) } })
                    } else {
                        put("resolvedDefaultStance", JsonNull)
                        put("resolvedGuards", JsonArray(emptyList()))
                    }
                    put("fictionBooks", fictionBooksJson)
                    if (missingBookObj != null) {
                        put("missingRecordBookHash", missingBookObj.id)
                        put("missingRecordBookTitle", missingBookObj.title)
                    }
                    overrides[primaryBook.id]?.let { put("readThroughPage", it) } ?: put("readThroughPage", JsonNull)
                    overrides[primaryBook.id]?.let { put("effectiveReadThroughPage", it) } ?: put("effectiveReadThroughPage", JsonNull)
                    put("strictSinglePage", false)
                    put("meta", item)
                    put("request", buildColWire(switchTarget))
                    if (hasInitialStep) {
                        put("initialStance", (item["initialStance"] as JsonPrimitive).content)
                        put("initialRequest", buildColWire(null))
                    }
                })
            }
        }
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
