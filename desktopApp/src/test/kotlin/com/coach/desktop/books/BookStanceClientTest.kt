package com.coach.desktop.books

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceCopy
import com.coach.protocol.BookStanceGuard
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 路线 1 客户端测试（TON-1977 #1/#4 及上线边界验收）：
 * 1. 未判定的书（没有 book-stance-forced.json 或书不在文件里）：请求不带 stance/guards，卡上没有题头和「换个角度」；
 * 2. 已判定的书：请求带 stance/guards/readThroughPage，分层卡按返回 label 显示，缺出处或不认识的层整块不显示；
 * 3. 每句可单独点击跳回该句原句的 UTF-16 区间；
 * 4. 换立场将新卡接在旧卡下面，旧卡题头不变，限制标记照带。
 */
@OptIn(ExperimentalTestApi::class)
class BookStanceClientTest {
    @get:Rule val folder = TemporaryFolder()

    private fun server(handler: (String, JsonObject?) -> String?): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { call ->
                call.use {
                    val raw = call.requestBody.use { it.readBytes().decodeToString() }
                    val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    val reply = (handler(call.requestURI.path, body) ?: "{}").toByteArray()
                    call.responseHeaders.add("Content-Type", "application/json")
                    call.sendResponseHeaders(200, reply.size.toLong())
                    call.responseBody.use { it.write(reply) }
                }
            }
            start()
        }

    private fun explainer(root: Path, library: TextbookLibrary, base: String) =
        object : BookExplainer(DesktopConnectionSettings(home = root, environment = { null }), library) {
            init {
                identity = DesktopIdentity(base, PairedCredentials("synthetic-device", "synthetic-token"))
                loaded = true
            }
        }

    /**
     * 上线边界核心单测：没有 book-stance-forced.json 时，书是「未判定」（不兜底成 knowledge），
     * 请求不带 stance/guards，回答卡上没有立场题头和「换个角度」。
     */
    @Test
    fun undeterminedBookSendsLegacyRequestAndShowsNoHeaderOrSwitcher() = runComposeUiTest {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(
            Files.writeString(root.resolve("plain.txt"), "Chapter 1\n\nShe is tolerable, but not handsome enough to tempt me."),
        )
        ExplainConsent(library.directory).allow(book.id)

        // 确认未放测试文件时返回 Undetermined，绝不兜底成 knowledge
        assertEquals(BookStanceResolution.Undetermined, BookStanceSources.forLibrary(library).resolve(book))

        val block = book.sourceBlocks().first { it.isBodyParagraph }
        val capturedBodies = CopyOnWriteArrayList<JsonObject>()
        val http = server { path, body ->
            if (path == "/v1/library/explain" && body != null) {
                capturedBodies += body
                """{"text":"原文说明达西的态度。【S1】","sources":[{"anchor":"S1","docId":"${block.id}","pdfPage":${block.page}}],"elapsedMs":5}"""
            } else null
        }
        try {
            val exp = explainer(root, library, "http://127.0.0.1:${http.address.port}")
            setContent {
                CoachTheme(darkTheme = false) {
                    BookExplainPanel(
                        book = book,
                        page = block.page,
                        consent = ExplainConsent(library.directory),
                        explainer = exp,
                        selection = block,
                        library = library,
                    )
                }
            }
            onNodeWithTag("explain-button").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, capturedBodies.size)
            val sent = capturedBodies.single()
            assertNull(sent["stance"], "未判定的书请求绝不能带 stance")
            assertNull(sent["guards"], "未判定的书请求绝不能带 guards")
            assertNull(sent["readThroughPage"], "未判定的书请求绝不能带 readThroughPage")
            assertTrue(onAllNodesWithTag("stance-answer-header").fetchSemanticsNodes().isEmpty(), "未判定的书卡上不能有立场题头")
            assertTrue(onAllNodesWithTag("stance-switch-row").fetchSemanticsNodes().isEmpty(), "未判定的书卡上不能有换个角度")
        } finally {
            http.stop(0)
        }
    }

    /**
     * 客户端 fail-closed 解析：不认识的层、缺标签、或「本书」块中有一句缺出处，整块不显示。
     */
    @Test
    fun parseStanceDropsUnknownLayerAndAnyBookBlockWithUncitedSentence() {
        val bookHash = "a".repeat(64)
        val block = SourceBlock("$bookHash:p1:0", 1, 0, 42, false, "First original line. Second original line.")
        val response = buildJsonObject {
            put("stance", "life")
            put("layers", buildJsonArray {
                // 不认识的层 -> 整块不显示
                add(buildJsonObject {
                    put("layer", "mystery_layer")
                    put("label", "未知")
                    put("text", "不应显示")
                })
                // 本书块里第二句缺出处 -> 整块不显示
                add(buildJsonObject {
                    put("layer", "book")
                    put("label", BookStanceCopy.BOOK_LIFE)
                    put("sentences", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "第一句有出处。")
                            put("citations", buildJsonArray {
                                add(buildJsonObject {
                                    put("docId", block.id)
                                    put("pdfPage", 1)
                                    put("startUtf16", 0)
                                    put("endUtf16", 20)
                                    put("quote", "First original line.")
                                })
                            })
                        })
                        add(buildJsonObject {
                            put("text", "第二句缺出处。")
                            put("citations", JsonArray(emptyList()))
                        })
                    })
                })
                // 合法的推想双标签层 -> 保留
                add(buildJsonObject {
                    put("layer", "guess")
                    put("label", BookStanceCopy.GUESS_PREFIX)
                    put("text", "按他书里的办法推想，他会先减少不必要的开销。")
                })
            })
            put("boundary", JsonNull)
            put("text", "摘要")
            put("elapsedMs", 12)
        }
        val parsed = assertIs<ExplainResult.Done>(ExplainWire.parse(response, listOf(block)))
        val stanceAnswer = assertNotNull(parsed.explanation.stanceAnswer)
        assertEquals(BookStance.LIFE, stanceAnswer.stance)
        assertEquals(1, stanceAnswer.layers.size, "未知层和含缺出处句子的本书块必须整块丢弃")
        val guess = assertIs<StanceLayer.Guess>(stanceAnswer.layers.single())
        assertEquals(BookStanceCopy.GUESS_PREFIX, guess.label)
    }

    /**
     * 已判定的书（有 book-stance-forced.json）：
     * - 讲解带 stance / guards / readThroughPage；
     * - 每句可单独点回它自己的原句 UTF-16 区间；
     * - 点「换个角度」把新卡接在旧卡下面，旧卡题头不变，guards 照带。
     */
    @Test
    fun determinedFictionBookRendersPerSentenceCitationsAndKeepsGuardsOnSwitch() = runComposeUiTest {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val page1 = "She is tolerable, but not handsome enough to tempt me. Darcy walked away in silence."
        val book = library.importBook(Files.writeString(root.resolve("pride.txt"), page1))
        ExplainConsent(library.directory).allow(book.id)

        // 写入阶段 1 测试文件 book-stance-forced.json
        Files.writeString(
            library.directory.resolve(ForcedFileBookStanceSource.FILE_NAME),
            """
            {
              "${book.id}": {
                "defaultStance": "original",
                "guards": ["fiction"]
              }
            }
            """.trimIndent(),
        )
        val resolved = assertIs<BookStanceResolution.Determined>(BookStanceSources.forLibrary(library).resolve(book))
        assertEquals(BookStance.ORIGINAL, resolved.defaultStance)
        assertEquals(setOf(BookStanceGuard.FICTION), resolved.guards)

        val block = book.sourceBlocks().first { it.isBodyParagraph }
        val q1 = "She is tolerable"
        val q2 = "Darcy walked away in silence."
        val s1 = book.page(block.page)!!.text.indexOf(q1)
        val s2 = book.page(block.page)!!.text.indexOf(q2)
        assertTrue(s1 >= 0 && s2 > s1)

        val requests = CopyOnWriteArrayList<JsonObject>()
        val http = server { path, body ->
            if (path != "/v1/library/explain" || body == null) return@server null
            requests += body
            val st = body["stance"]?.jsonPrimitive?.content ?: "original"
            buildJsonObject {
                put("stance", st)
                put("layers", buildJsonArray {
                    add(buildJsonObject {
                        put("layer", "book")
                        put("label", BookStanceCopy.BOOK_DEFAULT)
                        put("sentences", buildJsonArray {
                            add(buildJsonObject {
                                put("text", "达西说她只是勉强过得去。")
                                put("citations", buildJsonArray {
                                    add(buildJsonObject {
                                        put("docId", block.id)
                                        put("pdfPage", block.page)
                                        put("startUtf16", s1)
                                        put("endUtf16", s1 + q1.length)
                                        put("quote", q1)
                                    })
                                })
                            })
                            add(buildJsonObject {
                                put("text", "随后达西沉默离开。")
                                put("citations", buildJsonArray {
                                    add(buildJsonObject {
                                        put("docId", block.id)
                                        put("pdfPage", block.page)
                                        put("startUtf16", s2)
                                        put("endUtf16", s2 + q2.length)
                                        put("quote", q2)
                                    })
                                })
                            })
                        })
                    })
                })
                put("boundary", JsonNull)
                put("modelCalled", true)
                put("gate", buildJsonObject { put("droppedSentences", 0); put("outOfRange", 0) })
                put("text", "达西说她只是勉强过得去。随后达西沉默离开。")
                put("sources", JsonArray(emptyList()))
                put("elapsedMs", 8)
            }.toString()
        }
        val openedPassages = CopyOnWriteArrayList<BookPassage>()
        try {
            val exp = explainer(root, library, "http://127.0.0.1:${http.address.port}")
            setContent {
                CoachTheme(darkTheme = false) {
                    BookExplainPanel(
                        book = book,
                        page = block.page,
                        consent = ExplainConsent(library.directory),
                        explainer = exp,
                        selection = block,
                        library = library,
                        onOpenOriginal = { openedPassages += it },
                    )
                }
            }
            onNodeWithTag("explain-button").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("stance-answer-header").fetchSemanticsNodes().size == 1
            }
            onNodeWithText(BookStance.ORIGINAL.headerLabel).assertIsDisplayed()

            // 点第二句的出处 -> 精准高亮第二句原句而不是整段
            onNodeWithTag("stance-sentence-citation-0-1-0").performClick()
            assertEquals(1, openedPassages.size)
            assertEquals(q2, openedPassages.single().text)
            assertEquals(s2, openedPassages.single().start)
            assertEquals(s2 + q2.length, openedPassages.single().end)

            // 点「换个角度」切到知识立场 -> 新卡接在旧卡下方，旧卡题头不变，guards 仍带 fiction
            onNodeWithTag("stance-switch-knowledge").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("stance-answer-header").fetchSemanticsNodes().size == 2
            }
            assertEquals(2, requests.size)
            assertEquals("original", requests[0]["stance"]?.jsonPrimitive?.content)
            assertEquals("knowledge", requests[1]["stance"]?.jsonPrimitive?.content)
            assertEquals(listOf("fiction"), requests[1]["guards"]?.jsonArray?.map { it.jsonPrimitive.content })
            val headers = onAllNodesWithTag("stance-answer-header").fetchSemanticsNodes().map { node ->
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
            }
            assertEquals(listOf(BookStance.ORIGINAL.headerLabel, BookStance.KNOWLEDGE.headerLabel), headers)
        } finally {
            http.stop(0)
        }
    }

    /**
     * QA 回归（Fix #2）：通过 `beginBookQuestion -> finishBookQuestion -> loadLatestAskThread`
     * 以及 `saveAskTurn -> loadLatestAskThread` 保存并重开回答，必须完整恢复原题头、分层、
     * 逐句引文 UTF-16 范围、原问题与操作类型，绝不能丢失 `stanceAnswer` 退化成旧格式。
     */
    @Test
    fun persistedStanceAnswerSurvivesBeginFinishAndSaveReopen() {
        val root = folder.root.toPath()
        val libDir = root.resolve("library")
        val library = TextbookLibrary(libDir)
        val pageText = "She is tolerable, but not handsome enough to tempt me. Darcy walked away in silence."
        val book = library.importBook(Files.writeString(root.resolve("pride.txt"), pageText))
        val block = book.sourceBlocks().first { it.isBodyParagraph }
        val q1 = "She is tolerable"
        val q2 = "Darcy walked away in silence."
        val s1 = book.page(block.page)!!.text.indexOf(q1)
        val s2 = book.page(block.page)!!.text.indexOf(q2)
        assertTrue(s1 >= 0 && s2 > s1)

        val question = "达西当时的态度是什么？"
        val firstStanceAnswer = StanceAnswer(
            stance = BookStance.ORIGINAL,
            layers = listOf(
                StanceLayer.Book(
                    label = BookStanceCopy.BOOK_DEFAULT,
                    sentences = listOf(
                        StanceBookSentence(
                            text = "达西认为她只是勉强过得去。",
                            citations = listOf(StanceCitation(block.id, block.page, s1, s1 + q1.length, q1)),
                        ),
                        StanceBookSentence(
                            text = "随后达西沉默离开。",
                            citations = listOf(StanceCitation(block.id, block.page, s2, s2 + q2.length, q2)),
                        ),
                    ),
                ),
            ),
            modelCalled = true,
            droppedSentences = 0,
            outOfRange = 0,
        )
        val switchedStanceAnswer = StanceAnswer(
            stance = BookStance.KNOWLEDGE,
            layers = listOf(
                StanceLayer.Book(
                    label = BookStanceCopy.BOOK_DEFAULT,
                    sentences = listOf(
                        StanceBookSentence(
                            text = "原文显示达西拒绝邀请并认为她不够动人。",
                            citations = listOf(StanceCitation(block.id, block.page, s1, s1 + q1.length, q1)),
                        ),
                    ),
                ),
                StanceLayer.Outside(
                    label = BookStanceCopy.outsideLabel("通行讲法"),
                    text = "这是小说开篇刻画傲慢态度的经典手法。",
                ),
            ),
            modelCalled = true,
            droppedSentences = 1,
            outOfRange = 0,
        )

        val threadId = "stance-persist-thread"
        val turn1 = library.beginBookQuestion(book.id, threadId, block, "Chapter 1", question, listOf(block))
        library.finishBookQuestion(
            turn1,
            Explanation(
                text = "达西认为她只是勉强过得去。随后达西沉默离开。",
                citations = listOf(
                    Citation("S1", block.id, block.page, q1),
                    Citation("S2", block.id, block.page, q2),
                ),
                elapsedMs = 15L,
                stanceAnswer = firstStanceAnswer,
            ),
        )
        val turn2 = library.beginBookQuestion(book.id, threadId, block, "Chapter 1", question, listOf(block))
        library.finishBookQuestion(
            turn2,
            Explanation(
                text = "原文显示达西拒绝邀请并认为她不够动人。",
                citations = listOf(Citation("S1", block.id, block.page, q1)),
                elapsedMs = 22L,
                stanceAnswer = switchedStanceAnswer,
            ),
        )
        val explainStanceAnswer = StanceAnswer(
            stance = BookStance.ESOTERIC,
            layers = listOf(
                StanceLayer.Book(
                    label = BookStanceCopy.BOOK_DEFAULT,
                    sentences = listOf(
                        StanceBookSentence(
                            text = "这段描写表现了初始冷淡。",
                            citations = listOf(StanceCitation(block.id, block.page, s1, s1 + q1.length, q1)),
                        ),
                    ),
                ),
            ),
            systemNotice = BookStanceCopy.ESOTERIC_SYSTEM_NOTICE,
            modelCalled = true,
        )
        library.saveAskTurn(
            bookId = book.id,
            threadId = threadId,
            selection = block,
            sectionLocation = "Chapter 1",
            actionKind = SelectionExplainAction.EXPLAIN.kind,
            label = SelectionExplainAction.EXPLAIN.label,
            questionText = null,
            explanation = Explanation("这段描写表现了初始冷淡。", listOf(Citation("S1", block.id, block.page, q1)), 9L, explainStanceAnswer),
            sentBlocks = listOf(block),
        )

        // 重新打开数据库后逐项校验：原题头、分层、逐句引文 UTF-16 范围、原问题与操作类型
        val reopened = TextbookLibrary(libDir)
        val restored = assertNotNull(reopened.loadLatestAskThread(book.id, threadId))
        assertEquals(3, restored.turns.size)

        val r0 = restored.turns[0]
        assertEquals(SelectionExplainAction.ASK, r0.action)
        assertEquals(question, r0.questionText)
        val sa0 = assertNotNull(r0.explanation.stanceAnswer, "重开后 stanceAnswer 不能变回 null")
        assertEquals(firstStanceAnswer, sa0)
        val bookLayer0 = assertIs<StanceLayer.Book>(sa0.layers.single())
        assertEquals(2, bookLayer0.sentences.size)
        assertEquals(s1, bookLayer0.sentences[0].citations.single().startUtf16)
        assertEquals(s1 + q1.length, bookLayer0.sentences[0].citations.single().endUtf16)
        assertEquals(s2, bookLayer0.sentences[1].citations.single().startUtf16)
        assertEquals(s2 + q2.length, bookLayer0.sentences[1].citations.single().endUtf16)

        val r1 = restored.turns[1]
        assertEquals(SelectionExplainAction.ASK, r1.action)
        assertEquals(question, r1.questionText)
        val sa1 = assertNotNull(r1.explanation.stanceAnswer)
        assertEquals(switchedStanceAnswer, sa1)
        assertEquals(BookStance.KNOWLEDGE, sa1.stance)
        assertEquals(2, sa1.layers.size)
        assertIs<StanceLayer.Book>(sa1.layers[0])
        assertIs<StanceLayer.Outside>(sa1.layers[1])

        val r2 = restored.turns[2]
        assertEquals(SelectionExplainAction.EXPLAIN, r2.action)
        assertNull(r2.questionText)
        assertEquals(explainStanceAnswer, r2.explanation.stanceAnswer)
    }

    /**
     * QA 回归（Fix #3）：跑题准备工具必须通过客户端 `BookStanceSources.forLibrary(library).resolve(book)`
     * 读取实际立场判定与 `stanceSource`，配置缺失或不一致时不得从题表硬塞 stance/guards 或伪造 `forced`。
     */
    @Test
    fun corpusPrepReadsClientStanceSourceAndDetectsMissingForcedConfig() {
        val root = folder.root.toPath()
        val corpusDir = root.resolve("corpus")
        val bookDir = corpusDir.resolve("sample/demo")
        Files.createDirectories(bookDir)
        val bookFile = bookDir.resolve("demo.txt")
        Files.writeString(bookFile, "Chapter 1\n\nOnly pears grow in this orchard every autumn.")
        val questionsPath = root.resolve("questions.json")
        Files.writeString(
            questionsPath,
            """
            {
              "schema": 1,
              "books": {
                "sample/demo": {
                  "title": "Demo Orchard",
                  "file": "demo.txt",
                  "defaultStance": "knowledge",
                  "secondaryStance": null,
                  "guards": []
                }
              },
              "explainDefaults": [],
              "questions": [
                {
                  "id": "T1",
                  "entry": "ask",
                  "book": "sample/demo",
                  "locator": "Only pears grow",
                  "question": "果园里种什么？",
                  "stance": "knowledge",
                  "guards": [],
                  "readThroughPage": null,
                  "expectBoundary": false,
                  "allowNoBook": false,
                  "isClassical": false
                }
              ]
            }
            """.trimIndent(),
        )

        // 1. 无 book-stance-forced.json 且不自动生成 -> 客户端判定为 undetermined，发旧路径请求（不带 stance/guards）
        val cacheMissing = root.resolve("cache-missing")
        val missingPrepared = BookStanceCorpusPrep.prepare(
            corpusDir = corpusDir,
            questionsPath = questionsPath,
            cacheDir = cacheMissing,
            entry = "ask",
            mode = "forced",
            seedForcedFile = false,
        )
        val missingItem = missingPrepared.getValue("items").jsonArray.single().jsonObject
        assertEquals("undetermined", missingItem.getValue("stanceSource").jsonPrimitive.content)
        val missingReq = missingItem.getValue("request").jsonObject
        assertNull(missingReq["stance"], "缺少配置时跑题请求不得从题表硬塞 stance")
        assertNull(missingReq["guards"], "缺少配置时跑题请求不得从题表硬塞 guards")

        // 2. 放入自定义的 book-stance-forced.json（与题表不同：life + fiction）-> 请求必须反映客户端实际配置而非题表
        val bookHash = missingItem.getValue("bookHash").jsonPrimitive.content
        val customForced = root.resolve("custom-forced.json")
        Files.writeString(
            customForced,
            """{"$bookHash":{"defaultStance":"life","guards":["fiction"]}}""",
        )
        val cacheCustom = root.resolve("cache-custom")
        val customPrepared = BookStanceCorpusPrep.prepare(
            corpusDir = corpusDir,
            questionsPath = questionsPath,
            cacheDir = cacheCustom,
            entry = "ask",
            mode = "forced",
            forcedConfig = customForced,
            seedForcedFile = false,
        )
        val customItem = customPrepared.getValue("items").jsonArray.single().jsonObject
        assertEquals("forced", customItem.getValue("stanceSource").jsonPrimitive.content)
        val customReq = customItem.getValue("request").jsonObject
        assertEquals("life", customReq.getValue("stance").jsonPrimitive.content)
        assertEquals(listOf("fiction"), customReq.getValue("guards").jsonArray.map { it.jsonPrimitive.content })
    }
}
