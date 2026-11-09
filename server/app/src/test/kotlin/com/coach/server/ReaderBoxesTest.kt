package com.coach.server

import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Invented one-page-per-spec PDFs rendered by the real local Poppler text tool: coordinates, rotation and page bounds. */
class ReaderBoxesTest {
    private fun pdf(mediaBox: String, rotate: Int, text: String?): ByteArray {
        val stream = if (text == null) "" else "BT /F1 18 Tf 30 150 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [4 0 R] /Count 1 >>", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [$mediaBox] /Rotate $rotate /Resources << /Font << /F1 3 0 R >> >> /Contents 5 0 R >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream")
        val out = StringBuilder("%PDF-1.4\n"); val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, o -> offsets += out.length; out.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"); offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun library(root: Path, files: Map<String, ByteArray>): ReaderLibrary {
        Files.createDirectories(root)
        val docs = files.map { (id, bytes) ->
            Files.write(root.resolve("$id.pdf"), bytes)
            json("id" to id, "course" to "C1", "title" to "Invented $id", "file" to "$id.pdf", "sha256" to ReaderLibrary.digest(bytes), "pages" to 1, "kind" to "reading")
        }
        Files.writeString(root.resolve("catalog.json"), json("schema" to 1, "documents" to JsonArray(docs)).toString())
        return ReaderLibrary(root)
    }

    @Test fun localPopplerBoxesFollowTheDisplayedPageForTwoDocumentsRotationAndMissingTextLayer() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-boxes")
        try {
            val lib = library(dir.resolve("lib"), mapOf(
                "flat" to pdf("0 0 400 200", 0, "Alpha beta gamma"),
                "turned" to pdf("0 0 400 200", 90, "Alpha beta gamma"),
                "scan" to pdf("0 0 400 200", 0, null)))
            val flat = lib.boxes(lib.document("C1", "flat"), 1)
            assertEquals(400.0, flat.getValue("width").jsonPrimitive.double, 0.5); assertEquals(200.0, flat.getValue("height").jsonPrimitive.double, 0.5)
            val words = flat.getValue("words").jsonArray.map { it.jsonObject }
            assertEquals(listOf("Alpha", "beta", "gamma"), words.map { it.str("text") })
            val alpha = words[0]
            assertTrue(alpha.getValue("x0").jsonPrimitive.double in 25.0..35.0, "left edge near the 30pt origin")
            assertTrue(alpha.getValue("y0").jsonPrimitive.double in 20.0..45.0, "top-left origin: baseline 150 of 200 is ~50 from the top, so the box sits in 20..45")
            assertTrue(words.all { it.getValue("x1").jsonPrimitive.double <= 400.5 && it.getValue("y1").jsonPrimitive.double <= 200.5 })

            val turned = lib.boxes(lib.document("C1", "turned"), 1)
            assertEquals(200.0, turned.getValue("width").jsonPrimitive.double, 0.5, "a quarter turn swaps the displayed size")
            assertEquals(400.0, turned.getValue("height").jsonPrimitive.double, 0.5)
            val tw = turned.getValue("words").jsonArray.map { it.jsonObject }
            assertEquals(3, tw.size)
            assertTrue(tw.all { it.getValue("x1").jsonPrimitive.double <= 200.5 && it.getValue("y1").jsonPrimitive.double <= 400.5 })
            val a = tw[0]
            assertTrue(a.getValue("y1").jsonPrimitive.double - a.getValue("y0").jsonPrimitive.double >
                2 * (a.getValue("x1").jsonPrimitive.double - a.getValue("x0").jsonPrimitive.double), "rotated text runs down the displayed page")

            val scan = lib.boxes(lib.document("C1", "scan"), 1)
            assertFalse(scan.getValue("textLayer").jsonPrimitive.boolean); assertTrue(scan.getValue("words").jsonArray.isEmpty(), "no text layer → no invented boxes")
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun doc(id: String) = ReaderDocument(id, "C1", "Invented", Path.of("x.pdf"), "a".repeat(64), 1, null, "reading", null)
    private fun html(w: String, h: String, vararg words: String) = "<doc><page width=\"$w\" height=\"$h\">" + words.joinToString(" ") + "</page></doc>"
    private fun word(x0: Number, y0: Number, x1: Number, y1: Number, t: String) = "<word xMin=\"$x0\" yMin=\"$y0\" xMax=\"$x1\" yMax=\"$y1\">$t</word>"

    @Test fun parserKeepsOnlyInBoundsWordsInTheDisplayedFrameForTwoPages() {
        val lib = ReaderLibrary(null)
        val flat = lib.parseBoxes(doc("flat"), 1, html("400.000000", "200.000000", word(30, 30, 70, 45, "Alpha"), word(75, 30, 100, 45, "A&amp;B"), word(390, 30, 450, 45, "off"), word(10, 10, 5, 20, "flip")))
        assertEquals(400.0, flat.getValue("width").jsonPrimitive.double)
        assertEquals(listOf("Alpha", "A&B"), flat.getValue("words").jsonArray.map { it.jsonObject.str("text") }, "off-page and inverted boxes are dropped, entities decoded")
        val turned = lib.parseBoxes(doc("turned"), 1, html("200.000000", "400.000000", word(30, 30, 45, 70, "Alpha")))
        assertEquals(200.0, turned.getValue("width").jsonPrimitive.double); assertEquals(400.0, turned.getValue("height").jsonPrimitive.double)
        assertEquals(1, turned.getValue("words").jsonArray.size)
    }

    @Test fun parserReportsNoTextLayerAndRejectsUnreadablePages() {
        val lib = ReaderLibrary(null)
        val scan = lib.parseBoxes(doc("scan"), 1, html("400", "200"))
        assertFalse(scan.getValue("textLayer").jsonPrimitive.boolean); assertTrue(scan.getValue("words").jsonArray.isEmpty())
        assertFailsWith<ReaderFailure> { lib.parseBoxes(doc("bad"), 1, "<doc>no page</doc>") }
        assertFailsWith<ReaderFailure> { lib.parseBoxes(doc("zero"), 1, html("0", "0")) }
    }
}
