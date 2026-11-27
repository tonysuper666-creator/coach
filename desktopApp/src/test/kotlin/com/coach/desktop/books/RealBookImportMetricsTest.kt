package com.coach.desktop.books

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in winbox run. Set COACH_IMPORT_PHASE to kill, resume, or rest.
 * Book paths come from the environment and are never written into the assertion text.
 */
class RealBookImportMetricsTest {
    private val TWO_GB = 2L * 1024 * 1024 * 1024
    @Test fun runRequestedPhase() {
        val phase = System.getenv("COACH_IMPORT_PHASE") ?: return
        val lib = Path.of(System.getenv("COACH_IMPORT_LIB") ?: error("COACH_IMPORT_LIB"))
        Files.createDirectories(lib)
        when (phase) {
            "kill" -> killAfterCheckpoint(lib, Path.of(System.getenv("COACH_BOOK_LARGE")!!))
            "resume" -> resume(lib)
            "full" -> measure(lib, Path.of(System.getenv("COACH_BOOK_LARGE")!!), "large-full", recognize = true)
            "cold" -> measure(lib, Path.of(System.getenv("COACH_BOOK_LARGE")!!), "large-cold", recognize = false)
            "rest" -> rest(lib)
            else -> error("unknown phase $phase")
        }
    }

    private fun killAfterCheckpoint(lib: Path, pdf: Path) {
        val library = TextbookLibrary(lib, streamPdfExtras = true, lazyPdfExtras = true)
        val started = System.nanoTime()
        val sampler = WorkingSetSampler(lib.resolve("ws-peak-kill.txt"))
        sampler.start()
        val staged = library.stageBookImportTier0(pdf)
        Files.writeString(lib.resolve("book-id.txt"), staged.book.id)
        Files.writeString(lib.resolve("pages.txt"), staged.book.pages.size.toString())
        val baseline = settledPages(lib, staged.book.id)
        Files.writeString(lib.resolve("baseline-settled.txt"), baseline.toString())
        library.indexBookTier1(staged.book.id) { page, _ ->
            val settled = settledPages(lib, staged.book.id)
            // Progress is reported inside a batch, before that batch commits.
            // Halt only once a later callback sees the previous batch on disk.
            if (settled >= baseline + 25 && page > settled) {
                Files.writeString(lib.resolve("kill-progress.txt"), page.toString())
                Files.writeString(lib.resolve("kill-settled.txt"), settled.toString())
                Files.writeString(lib.resolve("kill-ms.txt"), millis(started).toString())
                sampler.stop()
                Runtime.getRuntime().halt(86)
            }
        }
        error("indexing finished before a committed batch")
    }

    private fun resume(lib: Path) {
        val bookId = Files.readString(lib.resolve("book-id.txt")).trim()
        val library = TextbookLibrary(lib, streamPdfExtras = true, lazyPdfExtras = true)
        val started = System.nanoTime()
        val sampler = WorkingSetSampler(lib.resolve("ws-peak-resume.txt"))
        sampler.start()
        var first = -1
        val ready = library.indexBookTier1(bookId) { page, _ ->
            if (first < 0) first = page
        }
        val indexed = library.resumeBookOcr(ready.id)
        val peak = sampler.stop()
        val bad = indexed.damagedPages.size
        val ocr = indexed.pages.count { it.ocr }
        assertTrue(first >= 25, "resume did not continue from a checkpoint, first=$first")
        assertEquals("READY", indexed.indexState)
        assertTrue(peak in 1..TWO_GB, "large-book working set $peak")
        report(
            lib,
            "METRIC kind=large pages=${indexed.pages.size} resumeFrom=$first " +
                "resumeMs=${millis(started)} wsPeak=$peak ocrPages=$ocr badPages=$bad",
        )
    }

    private fun rest(lib: Path) {
        val scan = Path.of(System.getenv("COACH_BOOK_SCAN")!!)
        val mixed = Path.of(System.getenv("COACH_BOOK_MIXED")!!)
        measure(lib.resolve("scan"), scan, "scan", recognize = true)
        measure(lib.resolve("mixed"), mixed, "mixed", recognize = true)
        val badRoot = lib.resolve("bad")
        Files.createDirectories(badRoot)
        measureFailure(badRoot, truncatedPdf(badRoot.resolve("half.pdf")), "truncated")
        measureFailure(badRoot, Files.writeString(badRoot.resolve("notes.pdf"), "hello, this is not a pdf"), "not-pdf")
        measureFailure(badRoot, passwordPdf(badRoot.resolve("locked.pdf")), "password")
        measure(badRoot.resolve("damaged-lib"), damagedPagePdf(badRoot.resolve("torn.pdf")), "damaged-page", recognize = false)
    }

    private fun measure(lib: Path, pdf: Path, kind: String, recognize: Boolean) {
        Files.createDirectories(lib)
        val library = TextbookLibrary(lib, streamPdfExtras = true, lazyPdfExtras = true)
        val started = System.nanoTime()
        val sampler = sampler(lib)
        sampler.start()
        val staged = library.stageBookImportTier0(pdf)
        val tier0Ms = millis(started)
        val indexed = library.indexBookTier1(staged.book.id)
        val tier1Ms = millis(started) - tier0Ms
        val ready = if (recognize) library.resumeBookOcr(indexed.id) else indexed
        val rendered = library.renderPdf(ready, ready.readingPage)
        assertTrue(rendered.width > 0 && rendered.height > 0, "failed to render initial reading page ${ready.readingPage}")
        val peak = sampler.stop()
        val checkpoint = ready.pages.size >= 200 || Files.size(pdf) > 32L * 1024 * 1024
        if (ready.pages.size >= 1000) assertTrue(peak in 1..TWO_GB, "large-book working set $peak")
        val chapters = ready.tocNodes.count { it.kind == TocNodeKind.CHAPTER }
        report(
            lib,
            "METRIC kind=$kind bookId=${ready.id} bytes=${Files.size(pdf)} pages=${ready.pages.size} " +
                "chapters=$chapters tocNodes=${ready.tocNodes.size} tocSource=${ready.structure?.source.orEmpty()} " +
                "readingPage=${ready.readingPage} tier0Ms=$tier0Ms tier1Ms=$tier1Ms ms=${millis(started)} " +
                "wsPeak=$peak ocrPages=${ready.pages.count { it.ocr }} ocrPending=${ready.pages.count { it.ocrPending }} " +
                "badPages=${ready.damagedPages.size} checkpoint=${if (checkpoint) "applies" else "below-threshold"}",
        )
    }

    private fun measureFailure(root: Path, pdf: Path, kind: String) {
        val lib = root.resolve("$kind-lib")
        Files.createDirectories(lib)
        val library = TextbookLibrary(lib, streamPdfExtras = true, lazyPdfExtras = true)
        val started = System.nanoTime()
        val sampler = sampler(lib)
        sampler.start()
        val error = runCatching { library.stageBookImportTier0(pdf) }.exceptionOrNull()
        val peak = sampler.stop()
        report(
            lib,
            "METRIC kind=$kind pages=0 ms=${millis(started)} wsPeak=$peak ocrPages=0 badPages=0 " +
                "failed=${error != null} checkpoint=below-threshold",
        )
        assertTrue(error is IllegalArgumentException, "$kind expected a rejected file, got $error")
    }

    private fun report(lib: Path, line: String) {
        println(line)
        val report = System.getenv("COACH_IMPORT_REPORT")?.let(Path::of) ?: lib.resolve("metrics.txt")
        Files.writeString(report, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun sampler(lib: Path): WorkingSetSampler = WorkingSetSampler(lib.resolve("ws-peak.txt"))

    private fun millis(started: Long) = (System.nanoTime() - started) / 1_000_000L

    private class WorkingSetSampler(private val file: Path) {
        private val peak = AtomicLong()
        private val stop = AtomicBoolean()
        private val thread = Thread {
            while (!stop.get()) {
                val resident = ProcessWorkingSet.bytes()
                if (resident > 0) {
                    val next = peak.updateAndGet { current -> maxOf(current, resident) }
                    runCatching { Files.writeString(file, next.toString()) }
                }
                Thread.sleep(200)
            }
        }

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop(): Long {
            stop.set(true)
            thread.join(2_000)
            val resident = ProcessWorkingSet.bytes()
            if (resident > 0) peak.updateAndGet { current -> maxOf(current, resident) }
            val value = peak.get()
            runCatching { Files.writeString(file, value.toString()) }
            return value
        }
    }

    private fun settledPages(libDir: Path, bookId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${libDir.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            db.prepareStatement("SELECT COUNT(*) FROM pages WHERE book=? AND text_pending=0").use { sql ->
                sql.setString(1, bookId)
                sql.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }

    private fun damagedPagePdf(target: Path): Path {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(4) { index ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("GOOD-PAGE-${index + 1}")
                    stream.endText()
                }
            }
            val broken = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.TYPE0)
                setItem(COSName.BASE_FONT, COSName.getPDFName("BrokenPage"))
            }
            val fonts = COSDictionary()
            fonts.setItem(COSName.getPDFName("F1"), broken)
            val resources = PDResources()
            resources.cosObject.setItem(COSName.FONT, fonts)
            val torn = pdf.getPage(3)
            torn.resources = resources
            val contents = PDStream(pdf)
            contents.createOutputStream().use { out ->
                out.write("BT /F1 12 Tf 72 700 Td (GOOD-PAGE-4) Tj ET\n".toByteArray(Charsets.US_ASCII))
            }
            torn.setContents(contents)
            pdf.save(target.toFile())
        }
        return target
    }

    private fun truncatedPdf(target: Path): Path {
        val bytes = PDDocument().use { pdf ->
            val page = PDPage().also { pdf.addPage(it) }
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("Coastal notes page 1")
                stream.endText()
            }
            java.io.ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
        }
        Files.write(target, bytes.copyOf(bytes.size * 60 / 100))
        return target
    }

    private fun passwordPdf(target: Path): Path {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("LOCKED-TOKEN")
                stream.endText()
            }
            pdf.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()).apply {
                encryptionKeyLength = 128
            })
            pdf.save(target.toFile())
        }
        return target
    }
}
