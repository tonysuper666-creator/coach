package com.coach.desktop.books

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.server.LiteratureGuideService
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Path

internal object LiteratureGuideFixture {
    fun pdf(root: Path, index: Int, partialLayer: Boolean = false): Path {
        val path = root.resolve("Paper-$index.pdf")
        PDDocument().use { pdf ->
            repeat(2) { page ->
                val sheet = PDPage()
                pdf.addPage(sheet)
                PDPageContentStream(pdf, sheet).use {
                    it.beginText()
                    it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    it.newLineAtOffset(45f, 700f)
                    it.showText(if (partialLayer && page == 1) "2" else
                        "Plot $index contained flowering plants. Sample ${page + 1} showed faster growth in shade.")
                    it.endText()
                }
            }
            pdf.save(path.toFile())
        }
        return path
    }

    /** Isolated Bridge double: one pending poll followed by a model response derived from the wire payload. */
    class Bridge : AutoCloseable {
        val keys = CopyOnWriteArrayList<String>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = Json.parseToJsonElement(exchange.requestBody.reader(Charsets.UTF_8).readText()).jsonObject
                val headers = exchange.requestHeaders
                check(headers.getFirst("X-Coach-Task") == "literature_guide" && headers.getFirst("X-Coach-Background") == "1")
                check(request["model"] == JsonPrimitive(BridgeClient.HAIKU))
                keys.add(requireNotNull(headers.getFirst("X-Coach-Batch-Key")))
                val pending = keys.size == 1
                val body = if (pending) buildJsonObject { put("x_coach_batch", "pending"); put("retry_after_ms", 1000) }
                    else buildJsonObject {
                        val user = request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
                        put("choices", buildJsonArray { add(buildJsonObject {
                            put("message", buildJsonObject { put("content", reply(Json.parseToJsonElement(user).jsonObject).toString()) })
                        }) })
                        put("x_coach", buildJsonObject {
                            put("status", "complete"); put("served_by", BridgeClient.HAIKU)
                            put("finish_reason", "stop"); put("physical_calls", 1); put("fallback", false)
                        })
                    }
                val bytes = body.toString().toByteArray()
                exchange.sendResponseHeaders(if (pending) 202 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val service = LiteratureGuideService(BridgeClient(BridgeConfig("http://127.0.0.1:${http.address.port}", testTraffic = true)))
        override fun close() = http.stop(0)
    }

    fun reply(input: JsonObject): JsonObject {
        val blocks = input.getValue("blocks").jsonArray.map { it.jsonObject }
        val papers = blocks.distinctBy { it["bookId"] }
        return buildJsonObject {
            put("question", "光照如何影响植物生长？")
            put("thread", "三项观察分别比较样地、光照和生长。")
            put("papers", buildJsonArray {
                papers.forEach { b -> add(buildJsonObject {
                    put("keyword", "光照"); put("claim", "本篇比较不同样地。"); put("ref", b.getValue("ref"))
                }) }
            })
            put("stops", buildJsonArray {
                blocks.distinctBy { it["bookId"] to it["sectionId"] }.forEach { b -> add(buildJsonObject {
                    put("section", b.getValue("section")); put("ref", b.getValue("ref"))
                }) }
            })
            put("answers", buildJsonArray {
                input.getValue("questions").jsonArray.forEach { add(buildJsonArray { add("样地不同。"); add("应逐项比较。") }) }
            })
            put("quiz", buildJsonArray {
                repeat(10) { i -> add(buildJsonObject {
                    put("questionIndex", i % input.getValue("questions").jsonArray.size); put("stem", "第 ${i + 1} 次观察记下什么？")
                    put("options", buildJsonArray { add("植物生长"); add("水温") }); put("correct", 0)
                    put("reasons", buildJsonArray { add("文中记录生长。"); add("没有测量水温。") })
                    put("ref", blocks[i % blocks.size].getValue("ref"))
                }) }
            })
        }
    }
}
