package com.coach.server

import com.coach.domain.*
import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class LibraryExplainTest {
    private fun blocks(vararg entries: Pair<String, String>) = JsonArray(entries.map { json("id" to it.first, "text" to it.second) })
    private fun request(selection: String, blocks: JsonArray) = json("requestId" to "ab12", "kind" to "explain", "selection" to selection, "blocks" to blocks)
    private val id = "0123abcd:p12:340"

    private fun lesson(book: String, count: Int = 8, text: (Int) -> String = { "完整自写块 $it🌱" }): JsonObject =
        json("requestId" to "ab12", "kind" to "lesson", "bookPages" to count,
            "blocks" to blocks(*(1..count).map { "$book:p$it:0" to text(it) }.toTypedArray()),
            "localSource" to json("schema" to 1, "book" to book, "format" to "pdf", "title" to "Self-authored lesson",
                "consented" to true, "pages" to JsonArray((1..count).map { n ->
                    json("number" to n, "length" to text(n).length, "ocr" to false, "sha256" to "a".repeat(64)) })))

    @Test fun entirePersonalLessonKeepsMoreThanSixBlocksAndCitesLastPageInOneCall() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            h.stub.stream(1, """{"paragraphs":[{"text":"整节各部分相互关联。","anchors":["S1","S8"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val input = lesson(h.version) { "  完整自写块 $it🌱  " }
            val (status, body) = h.post(input)
            assertEquals(200, status)
            assertEquals(listOf(1L, 8L), body["sources"]!!.jsonArray.map { it.jsonObject.num("pdfPage") })
            assertEquals(1, h.stub.requests)
            val prompt = LedgerJson.parseToJsonElement(h.stub.requestBodies.single()).jsonObject
            val messages = prompt.getValue("messages").jsonArray.map { it.jsonObject }
            val packed = messages.single { it.str("role") == "system" }.str("content")
            for (n in 1..8) assertTrue(packed.contains("  完整自写块 $n🌱  "))
            assertTrue(messages.single { it.str("role") == "user" }.str("content").contains("all 8"))
            val receipt = h.imports!!.register("phone", input, libraryExplainRequest(input))
            assertEquals(8, receipt.blocks.size)
            assertTrue(h.imports.isCurrent(receipt))
            assertNull(h.store.learnerSourcePage(h.version, 8))
        }
    }

    /** TON-1855: a broad question about the current section is answered from the whole section, not refused. */
    @Test fun sectionQuestionPromptAnswersBroadQuestionsFromTheWholeSectionAndNamesCrossSectionGaps() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            h.stub.stream(1, """{"paragraphs":[{"text":"本节最重要的概念是能量守恒，上一节的做功不在本节展开。","anchors":["S1","S3"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val base = lesson(h.version, 3) { "完整自写能量块 $it。" }
            val input = JsonObject(base - "bookPages" + mapOf(
                "kind" to JsonPrimitive("ask"),
                "selection" to JsonPrimitive("完整自写能量块 2。"),
                "question" to JsonPrimitive("这一段最重要的一个概念是什么？"),
                "sectionContext" to json("location" to "物理 › 第二章 › 第二节", "previousRecap" to "上一节介绍了机械功的定义。", "nextTitle" to "第三节 功率"),
            ))
            val (status, body) = h.post(input)
            assertEquals(200, status)
            assertEquals(listOf(1L, 3L), body.getValue("sources").jsonArray.map { it.jsonObject.num("pdfPage") },
                "Citations may point at any block of the section, not only the selected one")
            val messages = LedgerJson.parseToJsonElement(h.stub.requestBodies.last()).jsonObject.getValue("messages").jsonArray
                .map { it.jsonObject }
            val system = messages.single { it.str("role") == "system" }.str("content")
            for (n in 1..3) assertTrue(system.contains("完整自写能量块 $n。"), "The whole section is the evidence")
            assertTrue(system.contains(readerAskInstruction(withSection = true)))
            assertTrue(system.contains("the most important concept"))
            assertTrue(system.contains("not covered in this section"))
            assertTrue(system.contains("上一节介绍了机械功的定义。") && system.contains("第三节 功率"))
            assertFalse(system.contains("If unsupported, return empty paragraphs."))
            val user = messages.single { it.str("role") == "user" }.str("content")
            assertTrue(user.contains("这一段最重要的一个概念是什么？"))
            assertTrue(user.contains("starting directly with {\"paragraphs\":["))
            assertTrue(system.contains("One to three short paragraphs"))
            assertTrue(system.contains("never type a double quote"))

            // Live check: a third short paragraph for the cross-section note is accepted for questions only.
            h.stub.stream(1, """{"paragraphs":[{"text":"本节先讲能量守恒。","anchors":["S1"]},{"text":"再讲转化过程。","anchors":["S2"]},{"text":"功率不在本节，在下一节《第三节 功率》。","anchors":["S3"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val (threeStatus, threeBody) = h.post(JsonObject(input + ("requestId" to JsonPrimitive("ab14"))))
            assertEquals(200, threeStatus)
            assertEquals(listOf(1L, 2L, 3L), threeBody.getValue("sources").jsonArray.map { it.jsonObject.num("pdfPage") })
            assertEquals(3, readerMaxParagraphs(lesson = false, kind = "ask"))
            assertEquals(2, readerMaxParagraphs(lesson = false, kind = "explain"))
            assertEquals(6, readerMaxParagraphs(lesson = true, kind = "lesson"))

            // Leading prose triggers STREAM_JSON and retries with explicit format correction on formatAttempt > 0.
            h.stub.stream(1, "好的，根据本节内容：", StubTutor.terminal("complete"), StubTutor.DONE)
            h.stub.stream(1, """{"paragraphs":[{"text":"重试后直接输出合法 JSON。","anchors":["S1"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val (retryStatus, retryBody) = h.post(JsonObject(input + ("requestId" to JsonPrimitive("ab15"))))
            assertEquals(200, retryStatus)
            assertEquals(listOf(1L), retryBody.getValue("sources").jsonArray.map { it.jsonObject.num("pdfPage") })
            val retryUser = LedgerJson.parseToJsonElement(h.stub.requestBodies.last()).jsonObject.getValue("messages").jsonArray
                .map { it.jsonObject }.single { it.str("role") == "user" }.str("content")
            assertTrue(retryUser.contains("Previous attempt failed JSON format validation"))
        }
    }

    @Test fun sectionGuideAndContextualRephraseWithFollowUpHistoryPreserveCitationGate() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            // 1) kind = guide 带四样上下文返回 brief + recap + 带出处校验的正文
            h.stub.stream(1, """{"brief":["本节讲能量守恒","承接上一节做功概念","把握转化与守恒两个要点"],"recap":"本节说明能量在不同形式间转化但总量守恒。","paragraphs":[{"text":"承接上一节做功概念，本节系统阐述能量守恒定律。","anchors":["S1","S2"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val baseGuide = lesson(h.version, 2) { "完整自写能量块 $it。" }
            val guideInput = JsonObject(baseGuide + mapOf(
                "kind" to JsonPrimitive("guide"),
                "sectionContext" to json("location" to "物理 › 第二章 › 第二节", "previousRecap" to "上一节介绍了机械功的定义。", "nextTitle" to "第三节 功率"),
            ))
            val (guideStatus, guideBody) = h.post(guideInput)
            assertEquals(200, guideStatus)
            assertEquals(3, guideBody["brief"]!!.jsonArray.size)
            assertTrue(guideBody.str("recap").contains("守恒"))
            assertEquals(listOf(1L, 2L), guideBody["sources"]!!.jsonArray.map { it.jsonObject.num("pdfPage") })
            val guidePrompt = LedgerJson.parseToJsonElement(h.stub.requestBodies.last()).jsonObject["messages"]!!.jsonArray
                .map { it.jsonObject }.single { it.str("role") == "system" }.str("content")
            assertTrue(guidePrompt.contains("物理 › 第二章 › 第二节"))
            assertTrue(guidePrompt.contains("上一节介绍了机械功的定义。"))
            assertTrue(guidePrompt.contains("第三节 功率"))

            // 2) 选段 rephrase / ask 追问携带整节上下文和对话历史
            h.stub.stream(1, """{"paragraphs":[{"text":"换个说法，能量只是换了形态并没有凭空消失。","anchors":["S1"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val rephraseInput = JsonObject(baseGuide - "bookPages" + mapOf(
                "kind" to JsonPrimitive("rephrase"),
                "selection" to JsonPrimitive("完整自写能量块 1。"),
                "sectionContext" to json("location" to "物理 › 第二章 › 第二节", "previousRecap" to "上一节介绍了机械功的定义。", "nextTitle" to "第三节 功率"),
                "history" to JsonArray(listOf(json("question" to "讲讲", "answer" to "这段讲能量转化。"))),
            ))
            val (repStatus, repBody) = h.post(rephraseInput)
            assertEquals(200, repStatus)
            assertEquals(1, repBody["sources"]!!.jsonArray.size)
            val repPrompt = LedgerJson.parseToJsonElement(h.stub.requestBodies.last()).jsonObject["messages"]!!.jsonArray
                .map { it.jsonObject }.single { it.str("role") == "system" }.str("content")
            assertTrue(repPrompt.contains("完整自写能量块 2。"), "选段提问应携带整节其余块作为上下文")
            assertTrue(repPrompt.contains("这段讲能量转化。"), "追问应携带历史问答")

            // 3) 导读同一次调用返回 hardPoints（1–2 段校验存在）与 3 行 summary，且伪造段落 ID 被拦截剔除
            h.stub.stream(
                1,
                """{"brief":["要点一","要点二"],"recap":"前情提要","summary":["小结1","小结2","小结3"],"hardPoints":[{"blockId":"S1","label":"核心转换","explanation":"这一段点明了能量形式的转化规律。"},{"blockId":"S99","label":"伪造段","explanation":"不存在的段不能通过。"},{"blockId":"deadbeef:p1:0","label":"伪造哈希","explanation":"不存在的哈希不能通过。"}],"paragraphs":[{"text":"本节说明能量转化。","anchors":["S1","S2"]}]}""",
                StubTutor.terminal("complete"),
                StubTutor.DONE,
            )
            val beforeHpReqs = h.stub.requests
            val (hpStatus, hpBody) = h.post(guideInput)
            assertEquals(200, hpStatus)
            assertEquals(beforeHpReqs + 1, h.stub.requests, "难点标注和节末小结必须复用导读同一次调用")
            assertEquals(listOf("小结1", "小结2", "小结3"), hpBody["summary"]!!.jsonArray.map { it.jsonPrimitive.content })
            val returnedHps = hpBody["hardPoints"]!!.jsonArray
            assertEquals(1, returnedHps.size, "不存在的段落 id（S99 / 伪造哈希）必须被服务端剔除")
            assertEquals("${h.version}:p1:0", returnedHps.single().jsonObject.str("blockId"))
            assertEquals(1L, returnedHps.single().jsonObject.num("pdfPage"))

            // 4) 导读伪造出处仍会被严格拦截
            h.stub.stream(1, """{"brief":["要点"],"recap":"摘要","paragraphs":[{"text":"伪造出处。","anchors":["S9"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val (_, badGuide) = h.post(guideInput)
            assertTrue(badGuide["sources"]!!.jsonArray.isEmpty(), "导读里的伪造出处必须被拦截")

            // 5) 无结构书分节（kind = structure）：单调递增合法块 id 通过；乱序/重复/不存在块 id 拒收且不重试
            val structBook = "f".repeat(64)
            val structBlocks = blocks(
                "$structBook:p1:0" to "第一页开头片段",
                "$structBook:p2:0" to "第二页开头片段",
                "$structBook:p3:0" to "第三页开头片段",
            )
            val structInput = json(
                "requestId" to "aa01",
                "kind" to "structure",
                "bookPages" to 3,
                "blocks" to structBlocks,
                "localSource" to json(
                    "schema" to 1,
                    "book" to structBook,
                    "format" to "txt",
                    "title" to "Unstructured book",
                    "consented" to true,
                    "pages" to JsonArray((1..3).map { n ->
                        val txt = when (n) { 1 -> "第一页开头片段"; 2 -> "第二页开头片段"; else -> "第三页开头片段" }
                        json("number" to n, "length" to txt.length, "ocr" to false, "sha256" to "a".repeat(64))
                    }),
                ),
            )
            h.stub.stream(
                1,
                """{"sections":[{"startBlockId":"S1","title":"第一主题"},{"startBlockId":"$structBook:p3:0","title":"第二主题"}]}""",
                StubTutor.terminal("complete"),
                StubTutor.DONE,
            )
            val reqBeforeStruct = h.stub.requests
            val (okStructStatus, okStructBody) = h.post(structInput)
            assertEquals(200, okStructStatus)
            assertEquals(reqBeforeStruct + 1, h.stub.requests, "无结构分节只能单次调用")
            assertEquals(true, okStructBody["valid"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(
                listOf("$structBook:p1:0", "$structBook:p3:0"),
                okStructBody["sections"]!!.jsonArray.map { it.jsonObject.str("startBlockId") },
            )

            for ((reqId, badPayload) in listOf(
                "aa02" to """{"sections":[{"startBlockId":"S2","title":"乱序1"},{"startBlockId":"S1","title":"乱序2"}]}""",
                "aa03" to """{"sections":[{"startBlockId":"S1","title":"重复1"},{"startBlockId":"S1","title":"重复2"}]}""",
                "aa04" to """{"sections":[{"startBlockId":"S99","title":"不存在"}]}""",
            )) {
                h.stub.stream(1, badPayload, StubTutor.terminal("complete"), StubTutor.DONE)
                val countBefore = h.stub.requests
                val (badStatus, badBody) = h.post(JsonObject(structInput + ("requestId" to JsonPrimitive(reqId))))
                assertEquals(200, badStatus)
                assertEquals(countBefore + 1, h.stub.requests, "校验失败不重试")
                assertEquals(false, badBody["valid"]?.jsonPrimitive?.booleanOrNull)
                assertTrue(badBody["sections"]!!.jsonArray.isEmpty())
            }
        }
    }

    @Test fun lessonRequiresPersonalConsentAndCannotReclassifyCatalogOrForgePages() = runBlocking {
        val lastPage = json("kind" to "lesson", "bookPages" to 100_000,
            "blocks" to blocks("${"a".repeat(64)}:p100000:0" to "自写尾页。"))
        assertEquals(100_000, libraryExplainRequest(lastPage).pages.single().pdfPage)
        Harness(local = true, personal = true).use { h ->
            val input = lesson(h.version)
            assertEquals(409, h.post(input).first, "Known course version cannot become a personal lesson")
            h.catalog()
            assertEquals(409, h.post(JsonObject(input - "localSource")).first)
            assertEquals(400, h.post(JsonObject(input + json("bookPages" to 7))).first)
            val source = input["localSource"]!!.jsonObject
            assertEquals(400, h.post(JsonObject(input + ("localSource" to JsonObject(source + json("consented" to false))))).first)
            assertEquals(400, h.post(JsonObject(input + json("blocks" to blocks("course:p1:0" to "Synthetic")))).first)
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun oversizedLessonIsExplicitlyRefusedBeforeAnyProviderCall() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            for (input in listOf(lesson(h.version, 4) { "x".repeat(6_001) }, lesson(h.version, 129))) {
                val (status, body) = h.post(input)
                assertEquals(413, status)
                assertEquals("这一节太长，先选一段问。", body.str("error"))
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun lessonRejectsUnknownAnchorsWithoutRetryingAndWithholdsRevokedSnapshots() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            h.stub.stream(1, """{"paragraphs":[{"text":"伪造的出处。","anchors":["S9"]}]}""",
                StubTutor.terminal("complete"), StubTutor.DONE)
            val (_, rejected) = h.post(lesson(h.version))
            assertTrue(rejected["sources"]!!.jsonArray.isEmpty())
            assertEquals(1, h.stub.requests)
            h.reply(delayMs = 250)
            val pending = async { h.post(lesson(h.version)) }
            withTimeout(5_000) { while (h.stub.requests < 2) delay(5) }
            DriverManager.getConnection("jdbc:sqlite:${h.dir.resolve("personal-imports/sources.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate("DELETE FROM blocks WHERE page=8") }
            }
            assertTrue(pending.await().second["sources"]!!.jsonArray.isEmpty())
        }
    }

    private fun personal(book: String, text: String = "自写🌱 Synthetic selected.", start: Int = 5,
        hash: String = "a".repeat(64)): JsonObject = JsonObject(request(text,
        blocks("$book:p1:$start" to text, "$book:p1:100" to "PRIVATE_NEIGHBOUR")) +
        ("localSource" to json("schema" to 1, "book" to book, "format" to "png", "title" to "Self-authored scan",
            "consented" to true, "pages" to JsonArray(listOf(json("number" to 1, "length" to 200,
                "ocr" to true, "sha256" to hash))))))

    @Test fun firstConsentedPersonalImportExplainsWithoutCourseRegistrationAndPersistsOnlySentBlocks() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            h.reply()
            val input = personal(h.version)
            val (status, body) = h.post(input)
            assertEquals(200, status)
            assertEquals("${h.version}:p1:5", body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertFalse(h.stub.requestBodies.single().contains("PRIVATE_NEIGHBOUR"))
            val receipt = h.imports!!.register("phone", input, libraryExplainRequest(input))
            assertTrue(ImportedBookSources(h.dir.resolve("personal-imports")).isCurrent(receipt))
            assertFalse(h.imports.isCurrent(receipt.copy(device = "other-device")))
            assertNull(h.store.learnerSourcePage(h.version, 1), "Personal import must not grant course authority")
            assertEquals(409, h.post(JsonObject(input - "localSource")).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun catalogCanAlsoAllowPersonalUploadsWhilePreservingTheRegisteredSource() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            val library = requireNotNull(h.library)
            val upload = personal(h.version)
            assertEquals(409, h.post(upload).first)
            h.catalog(JsonObject(h.entry() + ("allowPersonalImport" to JsonPrimitive(true))))

            h.reply()
            val (status, personalAnswer) = h.post(upload)
            assertEquals(200, status)
            assertEquals("${h.version}:p1:5", personalAnswer.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertFalse(h.stub.requestBodies.single().contains("SYNTHETIC_NEIGHBOUR_SECRET"))

            h.reply()
            val (courseStatus, courseAnswer) = h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected")))
            assertEquals(200, courseStatus)
            assertEquals(h.localId, courseAnswer.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(h.sourceId, library.document("SYNTHETIC", "synthetic").explainSourceId)
            h.catalog(h.entry())
            assertEquals(409, h.post(upload).first)
        }
    }

    @Test fun personalReceiptsRejectMissingConsentImpossibleOffsetsAndConflictingSnapshotsBeforeModel() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            val input = personal(h.version)
            val source = input.getValue("localSource").jsonObject
            for (bad in listOf(JsonObject(source + ("consented" to JsonPrimitive(false))),
                JsonObject(source + ("book" to JsonPrimitive("0".repeat(64)))))) {
                assertEquals(400, h.post(JsonObject(input + ("localSource" to bad))).first)
            }
            assertEquals(400, h.post(personal(h.version, start = 199)).first)
            h.imports!!.register("phone", input, libraryExplainRequest(input))
            assertEquals(409, h.post(personal(h.version, hash = "b".repeat(64))).first)
            assertEquals(409, h.post(personal(h.version, text = "不同的原文" )).first)
            assertEquals(413, h.post(JsonObject(input + ("padding" to JsonPrimitive("x".repeat(131_073))))).first)
            assertEquals(0, h.stub.requests)
            // Selecting a prefix later is legitimate; overlapping UTF-16 characters must still match.
            val prefix = personal(h.version, text = "自写🌱")
            val receipt = h.imports.register("phone", prefix, libraryExplainRequest(prefix))
            assertTrue(h.imports.isCurrent(receipt))
        }
    }

    @Test fun personalReceiptCannotReclassifyKnownCatalogBookOrRestoreRevokedCoursePermissions() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            for (entry in listOf(h.entry(source = null), h.entry(title = "Synthetic answers"), h.entry(source = "unknown"))) {
                h.catalog(entry)
                assertEquals(409, h.post(personal(h.version)).first)
            }
            Files.writeString(h.libraryRoot.resolve("catalog.json"), "broken")
            assertEquals(409, h.post(personal(h.version)).first)
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun deletingPersonalRegistrationWhileGeneratingWithholdsReplyAndCitations() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog(); h.reply(delayMs = 250)
            val pending = async { h.post(personal(h.version)) }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            DriverManager.getConnection("jdbc:sqlite:${h.dir.resolve("personal-imports/sources.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate("DELETE FROM blocks") }
            }
            val (status, body) = pending.await()
            assertEquals(200, status)
            assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertFalse(body.str("text").contains("有依据的解释"))
        }
    }

    @Test fun savedOcrPageBindingPreservesUtf16AnchorsAndCannotGrantOtherPages() = runBlocking {
        Harness(local = true).use { h ->
            val prefix = "自写🌱\n"
            val quote = "Synthetic selected."
            val text = prefix + quote
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic scan", 12, text) }
            h.savedBinding(text)
            val block = "${h.version}:p12:${prefix.length}"
            for ((anchor, selection) in listOf(
                "${h.version}:p12:${prefix.length - 1}" to quote,
                "${h.version}:p13:0" to "SYNTHETIC_UNREAD_SECRET.",
                "${h.version}:p11:0" to quote,
                block to "Forged text.")) {
                assertEquals(409, h.post(request(selection, blocks(anchor to selection))).first)
            }
            assertEquals(0, h.stub.requests)
            h.reply()
            val (status, body) = h.post(request(quote, blocks(block to quote)))
            assertEquals(200, status)
            assertEquals(block, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertFalse(h.stub.requestBodies.single().contains(prefix))
            // The snapshot is not a second authority: changing the ledger revokes it.
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic scan", 12, "Different trusted text.") }
            assertEquals(409, h.post(request(quote, blocks(block to quote))).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun savedBookBindingsFailClosedOnSnapshotTamperAmbiguityAndRevocation() = runBlocking {
        Harness(local = true).use { h ->
            val text = "Synthetic selected."
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic scan", 12, text) }
            val block = "${h.version}:p12:0"
            val input = request(text, blocks(block to text))
            val snapshot = h.savedBinding(text)
            Files.writeString(snapshot, "{}")
            assertEquals(409, h.post(input).first)
            h.savedBinding(text)
            h.catalog(h.entry()) // Same original in PDF and saved-page registries is ambiguous.
            assertEquals(409, h.post(input).first)
            h.catalog()
            h.reply(delayMs = 250)
            val pending = async { h.post(input) }
            withTimeout(5_000) { while (h.stub.requests == 0) delay(10) }
            Files.delete(h.libraryRoot.resolve("local-books.json"))
            val (_, body) = pending.await()
            assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertFalse(body.str("text").contains("有依据的解释"))
            assertEquals(409, h.post(input).first)
        }
    }

    @Test fun boundParagraphExtractionAndQuestionRetainAllAuthorizationGates() = runBlocking {
        Harness(local = true).use { h ->
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic book", 12,
                "An _economic_ explanation of resources and a_b costs of 1-2.") }
            val paragraph = "An eco-\nnomic explanation of re-\nsources and a_b costs of 1-2."
            val question = "Why are resources relevant?"
            val valid = JsonObject(request(paragraph, blocks(h.localId to paragraph)) + json("kind" to "ask", "question" to question))
            for (invalid in listOf("", "x".repeat(501))) {
                assertEquals(400, h.post(JsonObject(valid + json("question" to invalid))).first)
            }
            for ((block, selection) in listOf(
                "${"0".repeat(64)}:p12:0" to paragraph,
                "${h.version}:p13:0" to paragraph,
                h.localId to paragraph.replace("a_b", "ab"),
                h.localId to paragraph.replace("1-2", "12"))) {
                assertEquals(409, h.post(request(selection, blocks(block to selection))).first)
            }
            assertEquals(0, h.stub.requests)
            h.reply()
            val (status, body) = h.post(valid)
            assertEquals(200, status)
            assertEquals(h.localId, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertTrue(h.stub.requestBodies.single().contains(question))
            h.actor.execute { h.update("UPDATE outline_kp SET anchors='[]'") }
            assertEquals(409, h.post(valid).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun blocksBecomeAnchorablePagesCarryingTheirIds() = Harness().use { h ->
        val untrusted = libraryExplainRequest(request("price elasticity", blocks(id to "Synthetic price   elasticity is a ratio.", "0123abcd:p12:900" to "Next synthetic block.")))
        assertFalse(untrusted.scope.permits(id, 12, true), "Parsing client text cannot mint a trusted scope")
        val parsed = untrusted.authorize(h.store)
        assertEquals(listOf(id), parsed.pages.map { it.docId })
        assertEquals("price elasticity", parsed.pages.single().text)
        assertTrue(parsed.scope.permits(id, 12, true))
        assertFalse(parsed.scope.permits(id, 13, true))
        assertFalse(parsed.scope.permits("0123abcd:p12:900", 12, true))
        assertFalse(parsed.scope.permits(id, 12, false))
        assertEquals(12, parsed.pages.first().pdfPage)
        assertEquals("price elasticity", parsed.quote)
    }

    @Test fun malformedRequestsAreRefused() {
        val ok = blocks(id to "Synthetic text here.")
        for (bad in listOf(
            request("", ok), request("not in the blocks", ok), request("Synthetic", blocks("not-an-id" to "Synthetic")),
            request("Synthetic", blocks(id to "Synthetic", id to "Synthetic")), request("Synthetic", JsonArray(emptyList())),
            request("Synthetic", blocks(*Array(5) { "0123abcd:p1:$it" to "Synthetic" })),
            request("Synthetic", blocks(id to "x".repeat(8_001))),
            request("Synthetic", blocks("0123abcd:p0:0" to "Synthetic")),
            request("Synthetic", blocks(id to "Synthetic", "other:p1:0" to "Synthetic")),
            request("Synthetic", blocks("sealed:p1:0" to "Synthetic")),
            json("selection" to "Synthetic"),
        )) assertEquals(HttpStatusCodeBad, assertFailsWith<MockError> { libraryExplainRequest(bad) }.status.value)
    }
    @Test fun callerVisibilityAndScopeCannotPromoteNeighboursOrOtherPages() = Harness().use { h ->
        val input = request("Synthetic selected", blocks(
            id to "Unread prefix. Synthetic selected. Unread suffix.",
            "0123abcd:p13:0" to "Synthetic not yet read.",
        )) + mapOf("scope" to json("allowsInternalReference" to true), "learnerVisible" to JsonPrimitive(true))
        val parsed = libraryExplainRequest(JsonObject(input)).authorize(h.store)
        assertEquals("Synthetic selected", parsed.pages.single().text)
        assertFalse(parsed.scope.permits("0123abcd:p13:0", 13, true))
        val pack = SourcePack.prepare("", parsed.pages)
        assertTrue(pack.containsSelection(parsed.quote))
        assertFalse(pack.render().contains("Unread"))
        assertFalse(pack.render().contains("not yet read"))
        assertNotNull(checkedReaderPayload("""{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", pack))
        Unit
    }

    private inner class Harness(local: Boolean = false, val sourceId: String = "0123abcd", personal: Boolean = false) : AutoCloseable {
        val dir = Files.createTempDirectory("synthetic-library-scope-")
        val libraryRoot = Files.createDirectory(dir.resolve("private-library"))
        // Binding tests need bytes and their identity only; the importer tests validate actual PDFs with Poppler.
        val bookFile = libraryRoot.resolve("synthetic.pdf").also { Files.writeString(it, "Synthetic private file identity") }
        val version = ReaderLibrary.digest(Files.readAllBytes(bookFile))
        val localId get() = "$version:p12:340"
        fun entry(course: String = "SYNTHETIC", source: String? = sourceId, title: String = "Synthetic book", pages: Int = 13) =
            JsonObject(json("id" to "synthetic", "course" to course, "title" to title, "file" to "synthetic.pdf",
                "sha256" to version, "pages" to pages, "kind" to "textbook") +
                (source?.let { mapOf("explainSourceId" to JsonPrimitive(it)) } ?: emptyMap()))
        fun catalog(vararg entries: JsonObject) = Files.writeString(libraryRoot.resolve("catalog.json"),
            json("schema" to 1, "documents" to JsonArray(entries.toList())).toString())
        fun savedBinding(text: String): java.nio.file.Path {
            catalog()
            val snapshot = libraryRoot.resolve("saved-pages.json")
            Files.writeString(snapshot, json("schema" to 1, "book" to version, "format" to "png", "pages" to JsonArray(listOf(
                json("number" to 12, "text" to text, "ocr" to true),
                json("number" to 13, "text" to "SYNTHETIC_UNREAD_SECRET.", "ocr" to true)))).toString())
            val entry = json("id" to "saved-scan", "course" to "SYNTHETIC", "title" to "Synthetic scan",
                "file" to "synthetic.pdf", "sha256" to version, "explainSourceId" to sourceId,
                "pagesFile" to "saved-pages.json", "pagesSha256" to ReaderLibrary.digest(Files.readAllBytes(snapshot)))
            Files.writeString(libraryRoot.resolve("local-books.json"), json("schema" to 1, "books" to JsonArray(listOf(entry))).toString())
            return snapshot
        }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir)
        val stub = StubTutor()
        fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { it.executeUpdate(sql) }
        }
        init {
            catalog(entry())
            store.enroll("phone", "synthetic-private-token-32-characters")
            store.registerSourcePage("SYNTHETIC", sourceId, "Synthetic book", 12,
                "Synthetic price elasticity is a ratio. Synthetic selected. SYNTHETIC_NEIGHBOUR_SECRET.")
            store.registerSourcePage("SYNTHETIC", sourceId, "Synthetic book", 13, "SYNTHETIC_UNREAD_SECRET.")
            store.registerSourcePage("SYNTHETIC", "harmless-id", "Synthetic answers", 12, "SYNTHETIC_ANSWER_SECRET.")
            store.registerSourcePage("SYNTHETIC", "ordinary-id", "Synthetic sealed material", 12, "SYNTHETIC_HELDOUT_SECRET.", learnerVisible = false)
            for ((kp, page) in listOf("k1" to 12, "k2" to 13)) update(
                """INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id)
                    VALUES('$kp','SYNTHETIC','m',$page,'x','x','[]','[]','[{"docId":"$sourceId","pdfPage":$page}]',0,NULL)""")
            store.append("SYNTHETIC", "kp_opened", json("kpId" to "k1"))
        }
        val actor = LedgerActor(jobs, store)
        val library = if (local) ReaderLibrary(libraryRoot) else null
        val imports = if (personal) ImportedBookSources(dir.resolve("personal-imports")) else null
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 16))
            libraryExplainRoutes(actor, ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack }), library, imports)
        }.also { it.start(false) }
        val client = HttpClient.newHttpClient()
        suspend fun post(input: JsonObject): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
            val port = engine.engine.resolvedConnectors().single().port
            val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/explain"))
                .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(), HttpResponse.BodyHandlers.ofString())
            response.statusCode() to LedgerJson.parseToJsonElement(response.body()).jsonObject
        }
        fun reply(delayMs: Long = 1) = stub.stream(delayMs,
            """{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", StubTutor.terminal("complete"), StubTutor.DONE)
        override fun close() { engine.stop(100, 1000); runBlocking { actor.close() }; jobs.cancel(); stub.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun registeredLearnedSelectionReachesModelWithoutClientOrServerNeighbours() = runBlocking {
        Harness().use { h ->
            h.reply()
            val (status, body) = h.post(request("Synthetic selected", blocks(
                id to "SYNTHETIC_CLIENT_PREFIX. Synthetic selected. SYNTHETIC_CLIENT_SUFFIX.",
                "0123abcd:p13:0" to "SYNTHETIC_UNREAD_SECRET.")))
            assertEquals(200, status)
            assertTrue(body.str("text").contains("有依据的解释。"))
            assertEquals(id, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(1, h.stub.requests)
            val wire = h.stub.requestBodies.single()
            assertTrue(wire.contains("Synthetic selected"))
            for (secret in listOf("SYNTHETIC_CLIENT_PREFIX", "SYNTHETIC_CLIENT_SUFFIX", "SYNTHETIC_NEIGHBOUR_SECRET",
                "SYNTHETIC_UNREAD_SECRET", "SYNTHETIC_ANSWER_SECRET", "SYNTHETIC_HELDOUT_SECRET")) {
                assertFalse(wire.contains(secret)); assertFalse(body.toString().contains(secret))
            }
        }
    }

    @Test fun unregisteredUnlearnedProtectedAndForgedTextAreRejectedBeforeModel() = runBlocking {
        Harness().use { h ->
            for ((block, quote) in listOf("unknown:p12:0" to "Synthetic selected", "0123abcd:p13:0" to "SYNTHETIC_UNREAD_SECRET",
                "harmless-id:p12:0" to "SYNTHETIC_ANSWER_SECRET", "ordinary-id:p12:0" to "SYNTHETIC_HELDOUT_SECRET",
                id to "SYNTHETIC_FORGED_TEXT")) {
                val forged = JsonObject(request(quote, blocks(block to quote)) + json("course" to "SYNTHETIC", "currentKps" to listOf("k2"),
                    "learnerVisible" to true, "scope" to json("allowsInternalReference" to true)))
                assertEquals(409, h.post(forged).first, block)
            }
            h.actor.execute { h.update("UPDATE outline_kp SET anchors='[]'") }
            assertEquals(409, h.post(request("Synthetic selected", blocks(id to "Synthetic selected"))).first)
            assertEquals(0, h.stub.requests, "No model request, retry or fallback may promote a client claim")
        }
    }

    @Test fun registeredSelectionRevokedDuringGenerationWithholdsAnswerAndCitations() = runBlocking {
        for (change in listOf("UPDATE source_docs SET learner_visible=0", "UPDATE source_docs SET blob_sha256='new-synthetic-version'",
            "UPDATE source_docs SET kind='sealed'", "UPDATE outline_kp SET anchors='[]'")) Harness().use { h ->
            h.reply(delayMs = 150)
            val response = async { h.post(request("Synthetic selected", blocks(id to "Synthetic selected"))) }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            h.actor.execute { h.update(change) }
            val (status, body) = withTimeout(5000) { response.await() }
            assertEquals(200, status); assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertTrue(body.str("text").contains("当前不可见")); assertFalse(body.toString().contains("有依据的解释。"))
        }
    }

    @Test fun explicitlyBoundLocalFileExplainsLearnedSelectionAndKeepsLocalCitationAlias() = runBlocking {
        Harness(local = true).use { h ->
            h.reply()
            val input = JsonObject(request("Synthetic selected", blocks(
                h.localId to "CLIENT_PREFIX. Synthetic selected. CLIENT_SUFFIX.",
                "${h.version}:p13:0" to "SYNTHETIC_UNREAD_SECRET")) + json(
                "course" to "CLIENT_FAKE_COURSE", "explainSourceId" to "CLIENT_FAKE_SOURCE"))
            val (status, body) = h.post(input)
            assertEquals(200, status)
            assertTrue(body.str("text").contains("有依据的解释。"))
            assertEquals(h.localId, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(1, h.stub.requests)
            val wire = h.stub.requestBodies.single()
            assertTrue(wire.contains("Synthetic selected"))
            for (secret in listOf("CLIENT_PREFIX", "CLIENT_SUFFIX", "SYNTHETIC_UNREAD_SECRET", "SYNTHETIC_NEIGHBOUR_SECRET",
                "CLIENT_FAKE_COURSE", "CLIENT_FAKE_SOURCE")) assertFalse(wire.contains(secret), secret)
        }
    }

    @Test fun boundNamespacedLearnerSourceExplainsOnlyItsLearnedPage() = runBlocking {
        Harness(local = true, sourceId = "textbook:synthetic.chapter-1").use { h ->
            h.reply()
            val (status, body) = h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected")))
            assertEquals(200, status)
            assertTrue(body.str("text").contains("有依据的解释。"))
            assertEquals(h.localId, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(409, h.post(request("SYNTHETIC_UNREAD_SECRET", blocks(
                "${h.version}:p13:0" to "SYNTHETIC_UNREAD_SECRET"))).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun localHashCannotBypassOptInByNamingAnExistingLedgerDocument() = runBlocking {
        Harness(local = true).use { h ->
            h.actor.execute {
                registerSourcePage("SYNTHETIC", h.version, "Synthetic hash-named book", 12, "Synthetic selected")
                h.update("""UPDATE outline_kp SET anchors='[{"docId":"${h.version}","pdfPage":12}]' WHERE kp_id='k1'""")
            }
            for (entries in listOf(emptyArray(), arrayOf(h.entry(source = null)))) {
                h.catalog(*entries)
                val forged = JsonObject(request("Synthetic selected", blocks(h.localId to "Synthetic selected")) +
                    json("explainSourceId" to h.version, "course" to "SYNTHETIC", "learnerVisible" to true))
                assertEquals(409, h.post(forged).first)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun localBindingRejectsUnknownAmbiguousWrongCourseProtectedAndOutOfRangeMappings() = runBlocking {
        Harness(local = true).use { h ->
            val duplicate = JsonObject(h.entry() + ("id" to JsonPrimitive("second-registration")))
            for (entries in listOf(arrayOf(h.entry(source = "unknown")), arrayOf(h.entry(course = "OTHER")),
                arrayOf(h.entry(title = "Synthetic answers")), arrayOf(h.entry(pages = 11)), arrayOf(h.entry(), duplicate))) {
                h.catalog(*entries)
                assertEquals(409, h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))).first)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun localBindingDoesNotAuthorizeUnreadProtectedOrForgedSelections() = runBlocking {
        Harness(local = true).use { h ->
            for ((source, page, quote) in listOf(Triple("0123abcd", 13, "SYNTHETIC_UNREAD_SECRET"),
                Triple("harmless-id", 12, "SYNTHETIC_ANSWER_SECRET"), Triple("ordinary-id", 12, "SYNTHETIC_HELDOUT_SECRET"),
                Triple("0123abcd", 12, "SYNTHETIC_FORGED_TEXT"))) {
                h.catalog(h.entry(source = source))
                val forged = JsonObject(request(quote, blocks("${h.version}:p$page:0" to quote)) +
                    json("currentKps" to listOf("k2"), "scope" to json("allowsInternalReference" to true), "learnerVisible" to true))
                assertEquals(409, h.post(forged).first, source)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun changedFileAndUnreadableCatalogFailClosedBeforeModel() = runBlocking {
        Harness(local = true).use { h ->
            val original = Files.readAllBytes(h.bookFile)
            Files.writeString(h.bookFile, "Changed synthetic private file")
            assertEquals(409, h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))).first)
            Files.write(h.bookFile, original)
            Files.writeString(h.libraryRoot.resolve("catalog.json"), "invalid synthetic catalog")
            assertEquals(409, h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))).first)
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun localBindingRevokedOrFileChangedDuringGenerationWithholdsAnswerAndCitations() = runBlocking {
        for (change in listOf<(Harness) -> Unit>({ it.catalog(it.entry(source = null)); Unit },
            { it.catalog(it.entry(course = "OTHER")); Unit }, { Files.writeString(it.bookFile, "Changed synthetic file"); Unit })) {
            Harness(local = true).use { h ->
                h.reply(delayMs = 150)
                val response = async { h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))) }
                withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
                change(h)
                val (status, body) = withTimeout(5000) { response.await() }
                assertEquals(200, status)
                assertTrue(body.getValue("sources").jsonArray.isEmpty())
                assertTrue(body.str("text").contains("当前不可见"))
                assertFalse(body.toString().contains("有依据的解释。"))
            }
        }
    }

    private val HttpStatusCodeBad = 400
}
