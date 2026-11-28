package com.coach.desktop.books

import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.protocol.ReaderTransientReply
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * TON-1963 回归：
 * 1. 讲解 / 追问的每条出处都带着原页上的原句（quote），在那一页能按原样、也能按空白归一化找回；
 *    服务端给的 anchor 只是正文里的【S1】标号，不是原句。
 * 2. 服务端一次尝试断掉（无首字、中途停、桥接失败、格式无法核对、排队已满）时，客户端换新请求号自动再问一次；
 *    总时长上限不重试。
 * 3. 取消（关面板、超时）时取消通知真的发到服务端，不让被丢下的回答继续占着讲解通道。
 * 4. 选区所在页不在任何小节里（目录只切到前面几章），或给的小节里没有这段选区时，
 *    不再只发选区本身，而是带上本页和前后页；跨段选区替换掉它压住的那几段。
 */
class ExplainAnchorRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun explainer(root: Path, library: TextbookLibrary, base: String) =
        object : BookExplainer(DesktopConnectionSettings(home = root, environment = { null }), library) {
            init {
                identity = DesktopIdentity(base, PairedCredentials("synthetic-device", "synthetic-token"))
                loaded = true
            }
        }

    /** Answers like the reader service: one 【S1】 marker per sent block, anchors are labels, never quotes. */
    private fun answer(blocks: JsonArray, pageOf: (String) -> Int): String {
        val ids = blocks.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val text = ids.indices.joinToString("") { "Point ${it + 1}.【S${it + 1}】" }
        val sources = ids.mapIndexed { i, id -> """{"anchor":"S${i + 1}","docId":"$id","pdfPage":${pageOf(id)}}""" }
        return """{"text":"$text","sources":[${sources.joinToString(",")}],"elapsedMs":5}"""
    }

    private fun pageOf(id: String) = id.substringAfter(":p").substringBefore(':').toInt()

    private fun assertQuotesOnTheirPages(book: Textbook, blocks: List<SourceBlock>, result: ExplainResult) {
        val done = assertIs<ExplainResult.Done>(result)
        assertTrue(done.explanation.citations.isNotEmpty())
        for (citation in done.explanation.citations) {
            val page = assertNotNull(book.pages.firstOrNull { it.number == citation.page }, "出处页存在").text
            assertTrue(citation.quote.isNotBlank(), "出处带原句")
            assertTrue(citation.quote in page, "原句按原样在原页上：p${citation.page}")
            assertTrue(clean(citation.quote) in clean(page), "原句按空白归一化也在原页上：p${citation.page}")
            val block = blocks.single { it.id == citation.blockId }
            assertEquals(citation.quote, BookPassage.from(book, block).text, "点开出处打开的就是这句原文")
            assertTrue(citation.anchor.matches(Regex("S\\d+")), "anchor 只是正文标号")
        }
    }

    private fun server(handler: (path: String, body: JsonObject?) -> String?): HttpServer =
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

    @Test fun explainAndFollowUpCitationsCarryTheirOriginalSentence() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        // Double spaces, a hyphenated line break and a soft-wrapped line: the shapes that broke the sweep's matcher.
        val txt = library.importBook(Files.writeString(root.resolve("spaced.txt"),
            "Chapter 1  Motion\n\nA body  keeps   its state of rest unless a force acts\non it from out-\nside.\n\n" +
                "The second  law relates force,  mass and acceleration\nin one short equation."))
        // Running header and footer on every page, like the PLOS / journal layouts in the sweep.
        val pdfFile = root.resolve("journal.pdf")
        PDDocument().use { pdf ->
            repeat(2) { n ->
                val sheet = PDPage(); pdf.addPage(sheet)
                PDPageContentStream(pdf, sheet).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 750f); out.showText("Journal of Synthetic Studies")
                    out.newLineAtOffset(0f, -120f); out.showText("Finding ${n + 1} shows that samples settle within a day.")
                    out.newLineAtOffset(0f, -100f); out.showText("Method ${n + 1} repeats the count on the next morning.")
                    out.newLineAtOffset(0f, -400f); out.showText("${n + 1} / 2")
                    out.endText()
                }
            }
            pdf.save(pdfFile.toFile())
        }
        val pdfBook = library.importBook(pdfFile)
        val books = listOf(txt, pdfBook)
        books.forEach { ExplainConsent(library.directory).allow(it.id) }
        val http = server { path, body ->
            if (path == "/v1/library/explain") answer(body!!.getValue("blocks").jsonArray, ::pageOf) else null
        }
        try {
            val explainer = explainer(root, library, "http://127.0.0.1:${http.address.port}")
            for (book in books) {
                val blocks = book.lessons.flatMap { it.blocks }.filterNot { it.heading }
                assertTrue(blocks.size >= 2, "${book.format} 有两段以上正文")
                for (block in blocks) {
                    // With no section given, the block now goes out with its page and the pages either side.
                    val context = ExplainWire.selectionBlocks(block, ExplainWire.pageContext(book, block))
                    assertQuotesOnTheirPages(book, context, explainer.explainSelection("explain", block))
                    assertQuotesOnTheirPages(book, context,
                        explainer.explainSelection("ask", block, question = "What does this sentence claim?"))
                }
            }
        } finally { http.stop(0) }
    }

    @Test fun brokenOffAttemptIsAskedAgainUnderANewRequestId() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("one.txt"), "Samples settle within a day."))
        ExplainConsent(library.directory).allow(book.id)
        val block = book.lessons.first().blocks.first { !it.heading }
        for (transient in ReaderTransientReply.retryable) {
            val ids = CopyOnWriteArrayList<String>()
            val http = server { path, body ->
                if (path != "/v1/library/explain") return@server null
                ids += body!!.getValue("requestId").jsonPrimitive.content
                if (ids.size == 1) """{"text":"$transient","sources":[],"elapsedMs":30000}"""
                else answer(body.getValue("blocks").jsonArray, ::pageOf)
            }
            try {
                val explainer = explainer(root, library, "http://127.0.0.1:${http.address.port}")
                val result = explainer.explainSelection("ask", block, question = "Why a day?", requestId = "first-try")
                assertQuotesOnTheirPages(book, listOf(block), result)
                assertEquals(2, ids.size, "「$transient」只自动重试一次")
                assertEquals("first-try", ids[0])
                assertNotEquals(ids[0], ids[1], "重试换新请求号，不撞上服务端还没清掉的旧请求")
            } finally { http.stop(0) }
        }
    }

    @Test fun emptyAnswerTwiceRunningIsAskedAgainUntilTheThirdTryAnswers() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("one.txt"), "Samples settle within a day."))
        ExplainConsent(library.directory).allow(book.id)
        val block = book.lessons.first().blocks.first { !it.heading }
        val ids = CopyOnWriteArrayList<String>()
        val http = server { path, body ->
            if (path != "/v1/library/explain") return@server null
            ids += body!!.getValue("requestId").jsonPrimitive.content
            // The page-11 explain: two quick empty answers, then a real one on the third try.
            if (ids.size <= 2) """{"text":"${ReaderTransientReply.INSUFFICIENT}","sources":[],"elapsedMs":1100}"""
            else answer(body.getValue("blocks").jsonArray, ::pageOf)
        }
        try {
            val explainer = explainer(root, library, "http://127.0.0.1:${http.address.port}")
            val result = explainer.explainSelection("explain", block, requestId = "first-try")
            assertQuotesOnTheirPages(book, listOf(block), result)
            assertEquals(3, ids.size, "两次空答后第三次拿到讲解")
            assertEquals("first-try", ids[0])
            assertEquals(3, ids.distinct().size, "每次重试都换新请求号")
        } finally { http.stop(0) }
    }

    @Test fun emptyAnswerStopsAfterThreeTriesAndIsShownAsItIs() = runBlocking {
        var calls = 0
        val ids = mutableListOf<String>()
        var next = 0
        val result = retryTransientOnce("a", newRequestId = { "r${++next}" }) { id ->
            calls++; ids += id; ExplainResult.Refused(ReaderTransientReply.INSUFFICIENT)
        }
        assertEquals(3, calls, "空答最多问三次，不无限重试")
        assertEquals(listOf("a", "r1", "r2"), ids)
        assertEquals(ReaderTransientReply.INSUFFICIENT, (result as ExplainResult.Refused).message)

        calls = 0
        retryTransientOnce("a", busyPauseMs = 0) { calls++
            ExplainResult.Refused(if (calls == 1) ReaderTransientReply.GAP_TIMEOUT else ReaderTransientReply.INSUFFICIENT)
        }
        assertEquals(3, calls, "中断后再遇空答，总共仍不超过三次")

        calls = 0
        retryTransientOnce("a", busyPauseMs = 0) { calls++
            ExplainResult.Refused(if (calls == 1) ReaderTransientReply.INSUFFICIENT else ReaderTransientReply.GAP_TIMEOUT)
        }
        assertEquals(2, calls, "其他中断句仍只多问一次")
    }

    @Test fun retryStopsAfterOneMoreAttemptAndNeverRepeatsTheTotalLimit() = runBlocking {
        var calls = 0
        val twice = retryTransientOnce("a", busyPauseMs = 0, newRequestId = { "b" }) { id ->
            calls++
            ExplainResult.Refused(if (id == "a") ReaderTransientReply.BUSY else ReaderTransientReply.GAP_TIMEOUT)
        }
        assertEquals(2, calls, "最多两次，不无限重试")
        assertEquals(ReaderTransientReply.GAP_TIMEOUT, (twice as ExplainResult.Refused).message)

        calls = 0
        val total = retryTransientOnce("a") { calls++; ExplainResult.Refused(ReaderTransientReply.TOTAL_TIMEOUT) }
        assertEquals(1, calls, "总时长已到上限，不再白等一轮")
        assertEquals(ReaderTransientReply.TOTAL_TIMEOUT, (total as ExplainResult.Refused).message)

        calls = 0
        retryTransientOnce("a") { calls++; ExplainResult.Refused("这一句书里没有更多可依据的内容。") }
        assertEquals(1, calls, "书里确实没有依据的拒答不重试")
    }

    @Test fun cancellingAnExplainTellsTheServiceToStop() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("slow.txt"), "Slow answers still have to stop."))
        ExplainConsent(library.directory).allow(book.id)
        val block = book.lessons.first().blocks.first { !it.heading }
        val cancelled = CopyOnWriteArrayList<String>()
        val release = CountDownLatch(1)
        val http = server { path, body ->
            when (path) {
                "/v1/library/explain" -> { release.await(30, TimeUnit.SECONDS); """{"text":"","sources":[]}""" }
                "/v1/library/explain/cancel" -> { cancelled += body!!.getValue("requestId").jsonPrimitive.content; release.countDown(); "{}" }
                else -> null
            }
        }
        try {
            val explainer = explainer(root, library, "http://127.0.0.1:${http.address.port}")
            val outcome = withTimeoutOrNull(1_500) { explainer.explainSelection("explain", block, requestId = "abandoned") }
            assertNull(outcome, "调用方已放弃等待")
            assertEquals(listOf("abandoned"), cancelled, "放弃时把取消通知发给服务端")
        } finally { release.countDown(); http.stop(0) }
    }

    private fun threePagePdf(root: Path, library: TextbookLibrary): Textbook {
        val file = root.resolve("outline-gap.pdf")
        PDDocument().use { pdf ->
            repeat(3) { n ->
                val sheet = PDPage(); pdf.addPage(sheet)
                PDPageContentStream(pdf, sheet).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 700f); out.showText("Page ${n + 1} opens with a short claim about heat.")
                    out.newLineAtOffset(0f, -200f); out.showText("Page ${n + 1} closes with how the claim is measured.")
                    out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        return library.importBook(file).also { ExplainConsent(library.directory).allow(it.id) }
    }

    @Test fun selectionOnAPageNoSectionCoversIsSentWithItsPageAndNeighbours() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = threePagePdf(root, library)
        val text = book.pages.single { it.number == 2 }.text
        val start = text.indexOf("short claim")
        // The p335 shape: a few words picked out of one paragraph, on a page the outline never reached.
        val chosen = SourceBlock("${book.id}:p2:$start", 2, start, start + "short claim".length, false, "short claim")
        val sent = CopyOnWriteArrayList<JsonObject>()
        val http = server { path, body ->
            if (path != "/v1/library/explain") return@server null
            sent += body!!
            val blocks = body.getValue("blocks").jsonArray
            // A lone selection is what came back 「依据不足」 on every try.
            if (blocks.size <= 1) """{"text":"${ReaderTransientReply.INSUFFICIENT}","sources":[],"elapsedMs":900}"""
            else answer(blocks, ::pageOf)
        }
        try {
            val explainer = explainer(root, library, "http://127.0.0.1:${http.address.port}")
            val firstPage = book.sourceBlocks().filter { it.page == 1 && !it.heading }
            val elsewhere = ExplainSectionContext("Page 1", null, null, firstPage)
            for (context in listOf(null, elsewhere)) {
                sent.clear()
                val expected = assertNotNull(ExplainWire.pageContext(book, chosen)).sectionBlocks
                val result = explainer.explainSelection("explain", chosen, context)
                assertQuotesOnTheirPages(book, expected, result)
                assertEquals(1, sent.size, "第一次就答上，不靠重试")
                val request = sent.single()
                assertNotNull(request["sectionContext"]?.takeIf { it !is JsonNull }, "带小节上下文，服务端整页保留")
                val ids = request.getValue("blocks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                assertEquals(expected.map { it.id }, ids)
                assertEquals(setOf(1, 2, 3), ids.map(::pageOf).toSet(), "本页和前后页都在")
                assertTrue(request.getValue("blocks").jsonArray.any {
                    pageOf(it.jsonObject.getValue("id").jsonPrimitive.content) == 2 && "short claim" in it.jsonObject.getValue("text").jsonPrimitive.content
                }, "选区所在那段在里面")
                assertTrue(ids.size > 1)
                val ask = explainer.explainSelection("ask", chosen, context, question = "How is it measured?")
                assertQuotesOnTheirPages(book, expected, ask)
            }
        } finally { http.stop(0) }
    }

    @Test fun selectionAcrossTwoParagraphsStandsInForBoth() {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = threePagePdf(root, library)
        val onPage = book.sourceBlocks().filter { it.page == 2 && !it.heading && it.text.isNotBlank() }
        assertTrue(onPage.size >= 2, "第 2 页有两段")
        val (a, b) = onPage.take(2)
        val start = a.start + a.text.length / 2
        val end = b.start + b.text.length / 2
        val span = SourceBlock("${book.id}:p2:$start", 2, start, end, false,
            book.pages.single { it.number == 2 }.text.substring(start, end))
        val blocks = assertNotNull(ExplainWire.pageContext(book, span)).sectionBlocks
        assertFalse(blocks.any { it.id == a.id || it.id == b.id }, "被选区压住的两段换成选区")
        assertEquals(1, blocks.count { it.id == span.id })
        assertEquals(blocks.sortedWith(compareBy({ it.page }, { it.start })), blocks, "按页、按位置排好")
        assertTrue(blocks.any { it.page == 1 } && blocks.any { it.page == 3 })

        val whole = ExplainWire.pageContext(book, a)!!.sectionBlocks
        assertEquals(1, whole.count { it.id == a.id }, "整段选中时不重复")
        assertNull(ExplainWire.pageContext(book, a.copy(text = " ")), "空选区不造上下文")
    }
}
