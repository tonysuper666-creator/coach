package com.coach.desktop.books

import com.coach.desktop.DesktopConnectionSettings
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class BookLessonTransportTest {
    @Test fun wholeLessonEntryUsesPairedHttpOnceAndRequiresImportConsent(): Unit = runBlocking {
        val root = Files.createTempDirectory("lesson-transport-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        server.createContext("/") { exchange ->
            val input = exchange.requestBody.use { it.readBytes().decodeToString() }
            val reply = when (exchange.requestURI.path) {
                "/v1/pair" -> """{"deviceId":"lesson-test","token":"${"a".repeat(43)}"}"""
                "/v1/hello" -> """{"epoch":"test","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                "/v1/library/explain" -> {
                    val wire = Json.parseToJsonElement(input).jsonObject
                    received += wire
                    val id = wire.getValue("blocks").jsonArray.last().jsonObject.getValue("id").jsonPrimitive.content
                    val page = id.substringAfter(":p").substringBefore(':').toInt()
                    """{"text":"Processes connect across the whole lesson.【S9】","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S9"}]}"""
                }
                else -> "{}"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val file = root.resolve("self-authored.txt")
            Files.writeString(file, (1..9).joinToString("\n\n") { "Self authored paragraph $it explains a different plant process." })
            val book = library.importBook(file)
            val blocks = book.lessons.first().blocks.filterNot { it.heading }
            assertTrue(blocks.size > 6)
            val settings = DesktopConnectionSettings(home = root, environment = { null }, mac = false,
                property = { if (it == "jpackage.app-path") "Coach.exe" else null })
            ReadingSettings(library.directory).chooseAiExplain(false)
            val explainer = BookExplainer(settings, library)
            assertTrue(explainer.pair("http://127.0.0.1:${server.address.port}", "123456"))
            assertIs<ExplainResult.Refused>(explainer.explainLesson(blocks, book.pages.size))
            assertTrue(received.isEmpty(), "No book text leaves before consent")
            ExplainConsent(library.directory).allow(book.id)
            val reply = assertIs<ExplainResult.Done>(explainer.explainLesson(blocks, book.pages.size))
            assertEquals(blocks.last().id, reply.explanation.citations.single().blockId)
            val wire = received.single()
            assertEquals("lesson", wire.getValue("kind").jsonPrimitive.content)
            assertEquals(blocks.map { it.id }, wire.getValue("blocks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertEquals(blocks.map { it.text }, wire.getValue("blocks").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertTrue(wire.getValue("localSource").jsonObject.getValue("consented").jsonPrimitive.boolean)
            assertIs<ExplainResult.Refused>(explainer.explainLesson(List(129) { blocks.first() }, book.pages.size))
            assertEquals(1, received.size, "An over-budget lesson is never truncated or sent")
        } finally { server.stop(0); root.toFile().deleteRecursively() }
    }
}
