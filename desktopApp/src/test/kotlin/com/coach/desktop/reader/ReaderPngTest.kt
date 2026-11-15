package com.coach.desktop.reader

import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.syntheticTextbook
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.IIOException
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlin.test.*

class ReaderPngTest {
    @Test fun opaqueAndTransparentPixelsMatchTheExistingPngPath() {
        for (type in listOf(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE)) {
            val page = BufferedImage(67, 53, type)
            for (y in 0 until page.height) for (x in 0 until page.width) {
                val alpha = (x * 29 + y * 17) and 255
                page.setRGB(x, y, (alpha shl 24) or (((x * 31) and 255) shl 16) or (((y * 23) and 255) shl 8) or ((x + y * 13) and 255))
            }
            val before = pixels(page)
            val legacy = ByteArrayOutputStream().also { assertTrue(ImageIO.write(page, "png", it)) }.toByteArray()
            val decoded = ImageIO.read(encodeReaderPng(page).inputStream())
            assertEquals(page.width, decoded.width)
            assertEquals(page.height, decoded.height)
            assertContentEquals(pixels(ImageIO.read(legacy.inputStream())), pixels(decoded), "PNG pixels changed for image type $type")
            assertContentEquals(before, pixels(page), "Encoding changed the shared raster")
        }
    }

    @Test fun aMissingImageIoDiskCacheDoesNotPreventLocalPageEncoding() {
        val root = Files.createTempDirectory("reader-png-cache-").toRealPath()
        val unavailableCache = Files.createFile(root.resolve("not-a-directory"))
        val legacy = child("legacy", unavailableCache)
        assertEquals(9, legacy.first, legacy.second)
        assertTrue(legacy.second.contains("LEGACY_CACHE_FAILURE"), legacy.second)
        val current = child("reader", unavailableCache)
        assertEquals(0, current.first, current.second)
        assertTrue(current.second.contains("PNG_OK"), current.second)
    }

    @Test fun localSourceStillReturnsTheRenderedPageWithoutMovingTheBookmark() = runBlocking {
        val root = Files.createTempDirectory("reader-png-source-").toRealPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        val expected = library.renderPdf(book, 2)
        val source = LocalBooksReaderSource(library)
        val encoded = assertNotNull(source.image(book.id, 2, "1"))
        val actual = ImageIO.read(encoded.inputStream())
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertContentEquals(pixels(expected), pixels(actual))
        assertEquals(book.readingPage, library.book(book.id).readingPage)
    }

    private fun pixels(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun child(mode: String, cache: Path): Pair<Int, String> {
        val classes = listOf(ReaderPngCacheChild::class.java, Class.forName("com.coach.desktop.reader.ReaderPngKt"), Unit::class.java)
        val classpath = classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-Djava.io.tmpdir=$cache", "-cp", classpath, ReaderPngCacheChild::class.java.name, mode)
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "PNG child timed out")
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
        }
    }
}

/** Separate headless JVM: changing java.io.tmpdir must not alter the Gradle worker or other tests. */
object ReaderPngCacheChild {
    @JvmStatic fun main(args: Array<String>) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(3, 4, 0x80402010.toInt())
        val encoded = try {
            if (args.single() == "legacy") {
                ImageIO.setUseCache(true)
                ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
            } else encodeReaderPng(image)
        } catch (failure: IIOException) {
            if (args.single() == "legacy") {
                println("LEGACY_CACHE_FAILURE:${failure.javaClass.simpleName}")
                exitProcess(9)
            }
            throw failure
        }
        check(encoded.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
        println("PNG_OK:${encoded.size}")
    }
}
