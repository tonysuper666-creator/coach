package com.coach.desktop.books

import com.coach.ai.BridgeClient
import com.coach.client.CourseHttp
import com.coach.core.LedgerActor
import com.coach.server.BookMapBatchChunkOutcome
import com.coach.server.BookMapBatchService
import com.coach.server.BookMapBatchTransport
import com.coach.server.StudyModeBatchTransport
import com.coach.server.StudyModePackService
import com.coach.server.StudyModeStreamTransport
import com.coach.server.StudyModeTransportReply
import com.coach.server.ledgerRoutes
import com.coach.server.studyModeRoutes
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.path.extension
import kotlin.system.exitProcess

/**
 * Isolated acceptance prep and counts-only anchor audit for TON-1889 ("这本书讲什么"全书地图).
 *
 * - Never writes book text, quotes, or full file paths into reports or stdout.
 * - Refuses to operate on the learner's ~/Coach/books directory.
 * - Imports books via the exact Windows production pipeline (tier-0 + tier-1 for PDF; stageBookImport for others).
 */
object BookMapVerifyMain {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Usage: bookMapVerify <prepare|sample-audit|self-check> [options]")
            return 2
        }
        return when (args[0].lowercase()) {
            "prepare" -> runPrepare(args.drop(1))
            "sample-audit" -> runSampleAudit(args.drop(1))
            "self-check" -> runSelfCheck()
            else -> {
                System.err.println("Unknown command: ${args[0]}")
                2
            }
        }
    }

    private fun ensureIsolatedLibraryDir(dir: Path) {
        val norm = dir.toAbsolutePath().normalize()
        val learner = Path.of(System.getProperty("user.home"), "Coach/books").toAbsolutePath().normalize()
        require(norm != learner) { "Refusing to modify learner library ~/Coach/books; pass an isolated directory." }
    }

    private fun readBooksFile(path: Path): List<Path> =
        Files.readAllLines(path, StandardCharsets.UTF_8)
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Path.of(it) }

    private fun runPrepare(args: List<String>): Int {
        var libraryDir: Path? = null
        var manifestOut: Path? = null
        var serverUrl: String? = null
        var tokenFile: Path? = null
        var deviceId = "desktop"
        var skipGenerate = false
        var pollCap = 180
        val books = mutableListOf<Path>()

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--library" -> libraryDir = Path.of(args[++i])
                "--out" -> manifestOut = Path.of(args[++i])
                "--books-file" -> books.addAll(readBooksFile(Path.of(args[++i])))
                "--server-url" -> serverUrl = args[++i].trim().trimEnd('/')
                "--token-file" -> tokenFile = Path.of(args[++i])
                "--device-id" -> deviceId = args[++i].trim()
                "--skip-generate" -> skipGenerate = true
                "--max-polls" -> pollCap = args[++i].toInt().coerceIn(1, 600)
                else -> {
                    if (a.startsWith("--")) error("Unknown flag: $a")
                    books.add(Path.of(a))
                }
            }
            i++
        }
        val libDir = requireNotNull(libraryDir) { "Missing --library <isolated-dir>" }
        ensureIsolatedLibraryDir(libDir)
        require(books.isNotEmpty()) { "Pass --books-file <path> or book file paths." }
        Files.createDirectories(libDir)

        val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
        val rows = mutableListOf<JsonObject>()
        val token = tokenFile?.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it, StandardCharsets.UTF_8).trim() }

        for (source in books) {
            val fileName = source.fileName.toString()
            if (!Files.isRegularFile(source)) {
                rows += buildJsonObject { put("file", fileName); put("error", "file_not_found") }
                continue
            }
            try {
                val isPdf = source.extension.equals("pdf", ignoreCase = true)
                val staged = if (isPdf) library.stageBookImportTier0(source) else library.stageBookImport(source)
                val indexed = if (staged.book.format == "pdf" && staged.book.indexState != "READY") {
                    library.indexBookTier1(staged.book.id)
                } else staged.book

                // Set default study mode to BOOK_MAP so opening any imported book lands on 「这本书讲什么」.
                StudyModeStore.save(
                    library,
                    BookStudyPreference(
                        bookId = indexed.id,
                        mode = BookStudyMode.BOOK_MAP,
                        userLocked = true,
                        detailLevel = ExplainDetailLevel.BRIEF,
                        explainLang = ExplainLanguage.BILINGUAL,
                    ),
                )

                var mapPoints = 0
                var mapChapters = 0
                var mapModel = ""
                var mapStatus = "skipped"
                var mapStage = ""
                var mapErrorCode = ""

                val existingMap = BookMapStore.load(library, indexed)
                if (existingMap != null && existingMap.totalPoints > 0) {
                    mapPoints = existingMap.totalPoints
                    mapChapters = existingMap.chapters.size
                    mapModel = existingMap.model
                    mapStatus = "cached"
                } else if (!skipGenerate && !serverUrl.isNullOrBlank() && !token.isNullOrBlank()) {
                    val outcome = generateBookMapOverHttp(
                        library = library,
                        book = indexed,
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        token = token,
                        maxPolls = pollCap,
                    )
                    mapPoints = outcome.points
                    mapChapters = outcome.chapters
                    mapModel = outcome.model
                    mapStatus = outcome.status
                    mapStage = outcome.stage
                    mapErrorCode = outcome.errorCode
                }

                val spans = BookMapSkeleton.chapters(indexed)
                rows += buildJsonObject {
                    put("file", fileName)
                    put("bookId", indexed.id)
                    put("format", indexed.format)
                    put("pages", indexed.pages.size)
                    put("bodyStartPage", BookMapSkeleton.bodyStartPage(indexed))
                    put("chapters", spans.size)
                    put("mapStatus", mapStatus)
                    if (mapStage.isNotBlank()) put("mapStage", mapStage)
                    if (mapErrorCode.isNotBlank()) put("mapErrorCode", mapErrorCode)
                    put("mapChapters", mapChapters)
                    put("mapPoints", mapPoints)
                    put("model", mapModel)
                }
            } catch (e: Exception) {
                rows += buildJsonObject {
                    put("file", fileName)
                    put("error", e::class.java.simpleName)
                }
            }
        }

        val manifest = buildJsonObject {
            put("libraryReady", true)
            put("totalBooks", books.size)
            put("importedBooks", rows.count { !it.containsKey("error") })
            put("mappedBooks", rows.count { (it["mapPoints"]?.jsonPrimitive?.intOrNull ?: 0) > 0 })
            put("books", JsonArray(rows))
        }
        manifestOut?.let { out ->
            out.parent?.let { Files.createDirectories(it) }
            Files.writeString(out, json.encodeToString(JsonObject.serializer(), manifest) + "\n", StandardCharsets.UTF_8)
        }
        println("BOOK_MAP_PREP books=${books.size} imported=${rows.count { !it.containsKey("error") }} mapped=${rows.count { (it["mapPoints"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }}")
        return if (rows.all { it.containsKey("error") }) 1 else 0
    }

    private data class GenerateOutcome(
        val status: String,
        val chapters: Int = 0,
        val points: Int = 0,
        val model: String = "",
        val stage: String = "",
        val errorCode: String = "",
    )

    private fun generateBookMapOverHttp(
        library: TextbookLibrary,
        book: Textbook,
        serverUrl: String,
        deviceId: String,
        token: String,
        maxPolls: Int,
    ): GenerateOutcome = runBlocking(Dispatchers.IO) {
        val http = CourseHttp(serverUrl, deviceId, token, "library")
        try {
            var revision = 0
            while (revision <= 1) {
                val built = BookMapWire.buildBatchRequest(book, ExplainLanguage.BILINGUAL, revision = revision)
                if (built.refMap.isEmpty()) {
                    return@runBlocking GenerateOutcome("no_readable_sentences", stage = "request_parse", errorCode = "empty_refs")
                }
                var polls = 0
                var retriedWithBump = false
                while (polls < maxPolls) {
                    polls += 1
                    val reply = http.fetchBookMapBatch(book.id, built.payload)
                    val body = reply.body
                    if (body == null) {
                        val code = reply.code.orEmpty().ifBlank { "http_${reply.statusCode ?: 0}" }
                        if (revision == 0 && (reply.statusCode == 502 || reply.statusCode == 503)) {
                            revision += 1
                            retriedWithBump = true
                            break
                        }
                        return@runBlocking GenerateOutcome(
                            status = "http_error_${reply.statusCode ?: 0}",
                            stage = "http_call",
                            errorCode = code,
                        )
                    }
                    when (val parsed = BookMapWire.parseBatchResponse(book, body, built.refMap)) {
                        is BookMapBatchParseResult.Pending -> {
                            BookMapStore.savePendingJob(library, book.id, built.skeletonHash, parsed.jobKey, revision)
                            delay(parsed.retryAfterMs.coerceIn(1_000L, 15_000L))
                        }
                        is BookMapBatchParseResult.Ready -> {
                            BookMapStore.save(library, parsed.document, expected = book)
                            return@runBlocking GenerateOutcome(
                                status = "generated",
                                chapters = parsed.document.chapters.size,
                                points = parsed.document.totalPoints,
                                model = parsed.document.model,
                            )
                        }
                        is BookMapBatchParseResult.Invalid -> {
                            if (revision == 0) {
                                revision += 1
                                retriedWithBump = true
                                break
                            }
                            return@runBlocking GenerateOutcome("invalid_response", stage = "client_parse", errorCode = "invalid_response")
                        }
                    }
                }
                if (!retriedWithBump) {
                    return@runBlocking GenerateOutcome("pending_timeout", stage = "chunk_poll", errorCode = "pending_timeout")
                }
            }
            GenerateOutcome("failed", stage = "chunk_submit", errorCode = "retry_exhausted")
        } finally {
            http.close()
        }
    }

    private fun runSampleAudit(args: List<String>): Int {
        var libraryDir: Path? = null
        var outPath: Path? = null
        var perBook = 10
        var seed = 1889L
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--library" -> libraryDir = Path.of(args[++i])
                "--out" -> outPath = Path.of(args[++i])
                "--per-book" -> perBook = args[++i].toInt().coerceIn(1, 50)
                "--seed" -> seed = args[++i].toLong()
                else -> error("Unknown flag: $a")
            }
            i++
        }
        val libDir = requireNotNull(libraryDir) { "Missing --library <dir>" }
        val library = TextbookLibrary(libDir)
        val rng = Random(seed)
        val catalog = library.catalog()

        var grandSampled = 0
        var grandMatched = 0
        val bookRows = mutableListOf<JsonObject>()

        for (summary in catalog) {
            val book = library.book(summary.id) ?: continue
            val doc = BookMapStore.load(library, book)
            if (doc == null) {
                bookRows += buildJsonObject {
                    put("bookId", book.id)
                    put("chapters", BookMapSkeleton.chapters(book).size)
                    put("hasMap", false)
                }
                continue
            }
            val allPoints = doc.chapters.flatMap { ch -> ch.points.map { ch to it } }
            val sampled = allPoints.shuffled(rng).take(perBook)
            var matched = 0
            val sampleRows = buildJsonArray {
                for ((ch, pt) in sampled) {
                    val anchor = pt.primaryAnchor
                    val resolved = anchor?.let { BookMapAnchorResolver.toCompanionAnchor(book, it) }
                    val page = resolved?.let { book.page(it.pdfPage) }
                    val exactHighlight = resolved != null && page != null &&
                        resolved.pdfPage in ch.firstPage..ch.lastPage &&
                        resolved.startUtf16 in 0 until resolved.endUtf16 &&
                        resolved.endUtf16 <= page.text.length &&
                        page.text.substring(resolved.startUtf16, resolved.endUtf16) == resolved.quoteAnchor
                    if (exactHighlight) matched += 1
                    add(
                        buildJsonObject {
                            put("chapterNum", ch.chapterNum)
                            put("pdfPage", resolved?.pdfPage ?: 0)
                            put("startUtf16", resolved?.startUtf16 ?: 0)
                            put("endUtf16", resolved?.endUtf16 ?: 0)
                            put("exactHighlight", exactHighlight)
                        },
                    )
                }
            }
            grandSampled += sampled.size
            grandMatched += matched
            bookRows += buildJsonObject {
                put("bookId", book.id)
                put("hasMap", true)
                put("model", doc.model)
                put("chapters", doc.chapters.size)
                put("totalPoints", doc.totalPoints)
                put("sampled", sampled.size)
                put("exactHighlightHits", matched)
                put("samples", sampleRows)
            }
        }

        val hitRate = if (grandSampled == 0) 0.0 else grandMatched.toDouble() / grandSampled.toDouble()
        val report = buildJsonObject {
            put("booksInLibrary", catalog.size)
            put("booksWithMap", bookRows.count { it["hasMap"]?.jsonPrimitive?.content == "true" })
            put("totalSampled", grandSampled)
            put("totalExactHighlightHits", grandMatched)
            put("hitRate", hitRate)
            put("books", JsonArray(bookRows))
        }
        outPath?.let { out ->
            out.parent?.let { Files.createDirectories(it) }
            Files.writeString(out, json.encodeToString(JsonObject.serializer(), report) + "\n", StandardCharsets.UTF_8)
        }
        println("BOOK_MAP_AUDIT books=${catalog.size} mapped=${bookRows.count { it["hasMap"]?.jsonPrimitive?.content == "true" }} hits=$grandMatched/$grandSampled")
        return if (grandSampled > 0 && hitRate >= 0.95) 0 else 1
    }

    private fun runSelfCheck(): Int = runBlocking(Dispatchers.IO) {
        val work = Files.createTempDirectory("book-map-verify-selfcheck-")
        try {
            val md1 = work.resolve("fixture-one.md")
            val md2 = work.resolve("fixture-two.md")
            val canary = "CANARY_SELFCHECK_PROSE_NEVER_IN_REPORT_77123"
            Files.writeString(
                md1,
                """
                # Copyright
                Notice text only.

                # Chapter 1 Supply and Demand
                $canary Markets coordinate decentralized production through relative price signals across regions.
                Higher coinsurance lowers outpatient demand while shifting financial risk back to households.
                Elasticity estimates differ substantially between acute hospital care and preventive checkups.
                Risk pooling reduces individual variance when premiums reflect average expected claims.

                # Chapter 2 Moral Hazard
                Full insurance reduces out-of-pocket prices to zero and expands marginal medical utilization.
                Optimal insurance balances financial risk protection against efficiency loss from excess care.
                Deductibles deter low-value visits while preserving protection against catastrophic expenses.
                Empirical studies measure demand responses across randomly assigned insurance plan tiers.
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            Files.writeString(
                md2,
                """
                # 第 1 章 制度与激励
                制度安排决定了微观主体在长期博弈中的稳定策略与资源配置结果。
                当信息不对称广泛存在时，市场参与者需要借助信号传递与筛选机制降低摩擦。
                公共政策在设计补贴标准时必须同时权衡覆盖公平性与财政可持续性。
                实证研究通过自然实验识别了支付方式改革对供给行为的真实因果效应。

                # 第 2 章 风险分担
                强制参保能够有效缓解逆向选择，使健康人群与高风险人群共同分担医疗支出。
                共付比例过低容易诱发过度医疗，而共付比例过高则会削弱家庭抗风险能力。
                分层报销与封顶线设计旨在为大病家庭提供兜底保障并控制整体费用增长。
                跨国比较表明不同筹资体系在可及性、质量与人均支出之间存在明显权衡。
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            val booksFile = work.resolve("books.txt")
            Files.writeString(booksFile, "$md1\n$md2\n", StandardCharsets.UTF_8)

            // Start an isolated in-process Ktor server with BookMapBatchService.
            val serverDir = Files.createDirectories(work.resolve("server"))
            val store = LedgerStore(serverDir)
            val token = "selfcheck-token-32-characters-ok"
            store.enroll("desktop", token)
            val tokenFile = serverDir.resolve("device-token")
            Files.writeString(tokenFile, token, StandardCharsets.UTF_8)
            val actor = LedgerActor(this, store)

            val transport = object : BookMapBatchTransport {
                override fun submitOrPoll(
                    jobKey: String,
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    friend: String?,
                    device: String?,
                ): BookMapBatchChunkOutcome {
                    val chRegex = Regex("""=== CHAPTER id=(\S+) num=(\d+) .*?===\n((?:\[\d+\|p\.\d+] .*\n)+)""")
                    val refRegex = Regex("""\[(\d+)\|p\.\d+]""")
                    val chaptersJson = buildJsonArray {
                        for (m in chRegex.findAll(userPrompt)) {
                            val cid = m.groupValues[1]
                            val cnum = m.groupValues[2].toInt()
                            val refs = refRegex.findAll(m.groupValues[3]).map { it.groupValues[1].toInt() }.toList()
                            add(
                                buildJsonObject {
                                    put("chapterId", cid)
                                    put("chapterNum", cnum)
                                    put("oneLiner", "第 $cnum 章概括")
                                    put(
                                        "points",
                                        buildJsonArray {
                                            refs.take(4).forEachIndexed { idx, r ->
                                                add(
                                                    buildJsonObject {
                                                        put("summary", "第 $cnum 章核心要点 ${idx + 1}。")
                                                        put("refs", buildJsonArray { add(JsonPrimitive(r)) })
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    val resp = buildJsonObject { put("chapters", chaptersJson) }.toString()
                    return BookMapBatchChunkOutcome.Ready(resp, BridgeClient.HAIKU, 120, 80, 15L)
                }
            }
            val noopStream = object : StudyModeStreamTransport {
                override fun streamGenerate(
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    friend: String?,
                    device: String?,
                    onDelta: (String, Long) -> Unit,
                ): StudyModeTransportReply = error("unused")
            }
            val noopBatch = object : StudyModeBatchTransport {
                override fun complete(
                    task: String,
                    jobKey: String,
                    systemPrompt: String,
                    userPrompt: String,
                    maxTokens: Int,
                    useBatchApi: Boolean,
                    friend: String?,
                    device: String?,
                ): StudyModeTransportReply = error("unused")
            }
            val service = StudyModePackService(noopStream, noopBatch, bookMapV2Service = BookMapBatchService(transport))
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(), adminDevice = "desktop")
                studyModeRoutes(actor, service = service)
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            try {
                val libDir = work.resolve("books")
                val manifestPath = work.resolve("manifest.json")
                val auditPath = work.resolve("audit.json")
                val prepCode = runPrepare(
                    listOf(
                        "--library", libDir.toString(),
                        "--books-file", booksFile.toString(),
                        "--server-url", "http://127.0.0.1:$port",
                        "--token-file", tokenFile.toString(),
                        "--out", manifestPath.toString(),
                    ),
                )
                check(prepCode == 0) { "self-check prepare failed" }
                val auditCode = runSampleAudit(
                    listOf("--library", libDir.toString(), "--out", auditPath.toString(), "--per-book", "10"),
                )
                check(auditCode == 0) { "self-check sample-audit failed" }
                check(canary !in Files.readString(manifestPath, StandardCharsets.UTF_8)) { "Prose leaked into manifest" }
                check(canary !in Files.readString(auditPath, StandardCharsets.UTF_8)) { "Prose leaked into audit report" }

                // Verify TON-1876 skeleton edit rearranges persisted map cleanly.
                val lib = TextbookLibrary(libDir)
                val firstBook = lib.book(lib.catalog().first().id)!!
                val firstChapId = BookMapSkeleton.chapters(firstBook).first().tocNodeId
                val renamed = firstBook.applyStructureEdit(StructureEdit.Rename(firstChapId, "Renamed Chapter 1"))
                val reconciled = BookMapStore.load(lib, renamed)
                check(reconciled != null && reconciled.chapters.first().title == "Renamed Chapter 1") {
                    "self-check skeleton rename reconciliation failed"
                }
                println("BOOK_MAP_VERIFY self-check OK")
                0
            } finally {
                server.stop(100, 500)
                actor.close()
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}
