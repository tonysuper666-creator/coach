package com.coach.desktop.books

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.server.LiteratureGuideService
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange
import org.apache.pdfbox.pdmodel.common.PDPageLabels
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

internal object LiteratureGuideFixture {
    fun pdf(
        root: Path,
        index: Int,
        partialLayer: Boolean = false,
        author: String = "Author$index",
        year: Int = 2000 + index,
        title: String = "Botany Study &amp; Plot $index",
    ): Path {
        val path = root.resolve("${author}_$year-Paper-$index.pdf")
        PDDocument().use { pdf ->
            pdf.documentInformation.title = title
            pdf.documentInformation.author = author
            repeat(2) { page ->
                val sheet = PDPage()
                pdf.addPage(sheet)
                PDPageContentStream(pdf, sheet).use {
                    it.beginText()
                    it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    it.newLineAtOffset(45f, 700f)
                    it.showText(
                        if (partialLayer && page == 1) "2"
                        else "Plot $index contained flowering plants. Sample ${page + 1} showed faster growth in shade.",
                    )
                    it.endText()
                }
            }
            pdf.save(path.toFile())
        }
        return path
    }

    /** Synthetic PDF with facing pages / wide-gap marginalia on page 1 and clean body prose on page 2. */
    fun facingAndMarginaliaPdf(root: Path, name: String = "Hunter_1989_Facing.pdf"): Path {
        val path = root.resolve(name)
        PDDocument().use { pdf ->
            pdf.documentInformation.title = "Facing Archive Study"
            pdf.documentInformation.author = "M. Hunter"
            val p1 = PDPage()
            pdf.addPage(p1)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            PDPageContentStream(pdf, p1).use { stream ->
                fun line(x: Float, y: Float, size: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(x, y)
                    stream.showText(text)
                    stream.endText()
                }
                // Single-column body with a 1-line inline marginal note (wide gap > 1.5x bodyHeight, no full-column gutter).
                line(42f, 720f, 12f, "The early keeper organized the repository")
                line(42f, 704f, 12f, "and catalogued rare specimens")
                line(340f, 704f, 12f, "See margin note 7.")
                line(42f, 688f, 12f, "from visiting naturalists.")
                // Clean separate paragraph below with no wide gap.
                line(42f, 640f, 12f, "Therefore the catalog proved that provenance stayed intact across decades.")
                line(42f, 624f, 12f, "Witnesses confirmed the custody register during annual inspections.")
            }
            val p2 = PDPage()
            pdf.addPage(p2)
            PDPageContentStream(pdf, p2).use { stream ->
                fun line(x: Float, y: Float, size: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(x, y)
                    stream.showText(text)
                    stream.endText()
                }
                // Classic offset two-column body + smaller marginalia column.
                line(42f, 720f, 12f, "The keeper transferred the records")
                line(42f, 705f, 12f, "to the archive because witnesses")
                line(42f, 690f, 12f, "confirmed the provenance.")
                line(42f, 675f, 12f, "Other registers survived as well.")
                line(350f, 717f, 9f, "A marginal note")
                line(350f, 704f, 9f, "from another hand")
                line(350f, 691f, 9f, "appears beside the text.")
                line(350f, 678f, 9f, "More commentary here.")
            }
            pdf.save(path.toFile())
        }
        return path
    }

    /** Synthetic PDF whose page 1 is a coursepack copyright cover page, followed by clean body + bibliographic footnote. */
    fun copyrightCoverPdf(root: Path, name: String = "Coursepack_1995_Cover.pdf"): Path {
        val path = root.resolve(name)
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val cover = PDPage()
            pdf.addPage(cover)
            PDPageContentStream(pdf, cover).use { stream ->
                fun line(y: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, 11f)
                    stream.newLineAtOffset(45f, y)
                    stream.showText(text)
                    stream.endText()
                }
                line(730f, "Course Reading Pack - University Library Digitisation Service")
                line(710f, "Copyright Licensing Agency CLA licence notice: All rights reserved.")
                line(690f, "This extract is reproduced under permission of the publisher for course materials.")
                line(670f, "ISBN 978-0-123456-78-9. Fair dealing terms apply.")
                line(650f, "Author: E. Mercer")
                line(630f, "Year: 1995")
                line(610f, "An ordinary cover remark about the syllabus schedule.")
            }
            val body = PDPage()
            pdf.addPage(body)
            PDPageContentStream(pdf, body).use { stream ->
                fun line(y: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(45f, y)
                    stream.showText(text)
                    stream.endText()
                }
                line(720f, "We argue that the inventory system transformed personal cabinets into public archives.")
                line(700f, "Curators relied on written provenance to separate verified artifacts from hearsay.")
                line(680f, "Therefore the register functioned as an enduring institutional memory.")
                // Bibliographic footnote line (author, title, (Place, Year), pp. N-M).
                line(620f, "M. Hunter, Establishing the New Science (Woodbridge, 1989), pp. 12-15.")
                line(600f, "Ibid., p. 44.")
            }
            pdf.save(path.toFile())
        }
        return path
    }

    /** Synthetic JSTOR-style PDF with cover page, HTML entities in metadata title, and wrong off-by-1 PDF page labels. */
    fun jstorWrongPageLabelsPdf(root: Path, name: String = "Fontes_2002_Jstor.pdf"): Path {
        val path = root.resolve(name)
        PDDocument().use { pdf ->
            pdf.documentInformation.title = "The Culture of Curiosity &amp; Collecting &#8217;Notes&#8217;"
            // Deliberately wrong JSTOR-style page labels: cover gets labeled "147", page 2 gets "148" (off by 1 from real printed 147).
            val labels = PDPageLabels(pdf)
            val range = PDPageLabelRange().apply {
                style = PDPageLabelRange.STYLE_DECIMAL
                start = 147
            }
            labels.setLabelItem(0, range)
            pdf.documentCatalog.pageLabels = labels

            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val cover = PDPage()
            pdf.addPage(cover)
            PDPageContentStream(pdf, cover).use { stream ->
                fun line(y: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, 11f)
                    stream.newLineAtOffset(45f, y)
                    stream.showText(text)
                    stream.endText()
                }
                line(730f, "The Culture of Curiosity and Collecting")
                line(710f, "Author(s): Palmira Fontes da Costa")
                line(690f, "Source: Notes and Records of the Royal Society, Vol. 56, No. 2 (May, 2002), pp. 147-163")
                line(670f, "Published by: Royal Society")
                line(650f, "Stable URL: https://www.jstor.org/stable/1234567")
                line(630f, "Downloaded from JSTOR on Tuesday.")
            }
            val p2 = PDPage()
            pdf.addPage(p2)
            PDPageContentStream(pdf, p2).use { stream ->
                fun line(y: Float, text: String) {
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(45f, y)
                    stream.showText(text)
                    stream.endText()
                }
                line(720f, "Curiosity operated as a central epistemic category inside the early society.")
                line(700f, "Members evaluated singular specimens through collective witnessing at weekly meetings.")
                line(680f, "Consequently written minutes linked each curious object to trusted testimony.")
            }
            pdf.save(path.toFile())
        }
        return path
    }

    /** Isolated Bridge double supporting First-Cut initial + 1-shot targeted repair flows. */
    class Bridge(
        private val firstPassUnanswerableIndex: Int? = null,
        private val keepRepairUnanswerable: Boolean = false,
        private val extraPaperOnFirstPass: Boolean = false,
    ) : AutoCloseable {
        val keys = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<JsonObject>()
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
                    val userJson = Json.parseToJsonElement(user).jsonObject
                    requests.add(userJson)
                    val isRepair = (userJson["mode"] as? JsonPrimitive)?.contentOrNull == "repair"
                    val result = if (isRepair) {
                        repairReply(userJson, unanswerable = keepRepairUnanswerable)
                    } else {
                        reply(
                            userJson,
                            unanswerableIndex = firstPassUnanswerableIndex,
                            includeExtraPaper = extraPaperOnFirstPass,
                        )
                    }
                    put("choices", buildJsonArray {
                        add(buildJsonObject { put("message", buildJsonObject { put("content", result.toString()) }) })
                    })
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

    fun reply(
        input: JsonObject,
        unanswerableIndex: Int? = null,
        includeExtraPaper: Boolean = false,
        omitBookId: String? = null,
    ): JsonObject {
        val blocks = input.getValue("blocks").jsonArray.map { it.jsonObject }
        val papers = blocks.distinctBy { it["bookId"] }
        val questions = input.getValue("questions").jsonArray
        return buildJsonObject {
            put("question", "这组文献如何围绕同一核心议题展开？")
            put("thread", "几篇研究从不同证据切入，共同说明机制与差异。")
            put("papers", buildJsonArray {
                papers.forEach { b ->
                    val bookId = b.getValue("bookId").jsonPrimitive.content
                    if (bookId == omitBookId) return@forEach
                    add(buildJsonObject {
                        put("bookId", bookId)
                        put("keyword", "核心论点")
                        put("claim", "本篇基于原文记录分析了核心机制。")
                        put("ref", b.getValue("ref"))
                    })
                }
                if (includeExtraPaper) {
                    add(buildJsonObject {
                        put("bookId", "unknown-extra-book")
                        put("keyword", "多余卡片")
                        put("claim", "应当被忽略的额外卡片。")
                        put("ref", blocks.first().getValue("ref"))
                    })
                }
            })
            put("answers", buildJsonArray {
                questions.forEachIndexed { idx, _ ->
                    if (idx == unanswerableIndex) {
                        add(buildJsonObject {
                            put("questionIndex", idx)
                            put("answerable", false)
                            put("sentences", buildJsonArray {})
                        })
                    } else {
                        val ref1 = blocks[idx % blocks.size].getValue("ref")
                        val ref2 = blocks[(idx + 1) % blocks.size].getValue("ref")
                        add(buildJsonObject {
                            put("questionIndex", idx)
                            put("answerable", true)
                            put("sentences", buildJsonArray {
                                add(buildJsonObject {
                                    put("text", "第一句给出基于原文证据的直接结论。")
                                    put("refs", buildJsonArray { add(ref1) })
                                })
                                add(buildJsonObject {
                                    put("text", "第二句补充不同样本或文献之间的对照。")
                                    put("refs", buildJsonArray { add(ref2) })
                                })
                            })
                        })
                    }
                }
            })
        }
    }

    fun repairReply(input: JsonObject, unanswerable: Boolean = false): JsonObject {
        val blocks = input.getValue("blocks").jsonArray.map { it.jsonObject }
        val repair = input.getValue("repair").jsonObject
        return buildJsonObject {
            if (repair["needQuestion"]?.jsonPrimitive?.booleanOrNull == true) {
                put("question", "补问后的总问题是什么？")
            }
            if (repair["needThread"]?.jsonPrimitive?.booleanOrNull == true) {
                put("thread", "补问后的主线串起全部文献。")
            }
            val missingBooks = repair["missingBookIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if (missingBooks.isNotEmpty()) {
                put("papers", buildJsonArray {
                    missingBooks.forEach { bookId ->
                        val blk = blocks.first { it.getValue("bookId").jsonPrimitive.content == bookId }
                        add(buildJsonObject {
                            put("bookId", bookId)
                            put("keyword", "补齐论点")
                            put("claim", "补问后完成该篇文献的核心概括。")
                            put("ref", blk.getValue("ref"))
                        })
                    }
                })
            }
            val failedQs = repair["failedQuestions"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            if (failedQs.isNotEmpty()) {
                put("answers", buildJsonArray {
                    failedQs.forEach { fq ->
                        val qIdx = fq.getValue("questionIndex").jsonPrimitive.int
                        if (unanswerable) {
                            add(buildJsonObject {
                                put("questionIndex", qIdx)
                                put("answerable", false)
                                put("sentences", buildJsonArray {})
                            })
                        } else {
                            val ref1 = blocks.first().getValue("ref")
                            val ref2 = blocks[minOf(1, blocks.lastIndex)].getValue("ref")
                            add(buildJsonObject {
                                put("questionIndex", qIdx)
                                put("answerable", true)
                                put("sentences", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", "补问后找到直接正文依据并给出第一句实答。")
                                        put("refs", buildJsonArray { add(ref1) })
                                    })
                                    add(buildJsonObject {
                                        put("text", "第二句进一步交代该机制在原文中的条件。")
                                        put("refs", buildJsonArray { add(ref2) })
                                    })
                                })
                            })
                        }
                    }
                })
            }
        }
    }
}
