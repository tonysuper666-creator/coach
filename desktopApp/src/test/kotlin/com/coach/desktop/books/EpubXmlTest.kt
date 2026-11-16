package com.coach.desktop.books

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class EpubXmlTest {
    private val body = "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Ignored</title></head>" +
        "<body><h1>Chapter</h1><p>Repeat😀 &amp; &#160;</p><p>Repeat😀</p><table><tr><td>A</td><td>B</td></tr></table></body></html>"
    private val doctypes = listOf(
        "<!DOCTYPE html>",
        "<!DOCTYPE html SYSTEM 'about:legacy-compat'>",
        "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Strict//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd'>",
        "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Transitional//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd'>",
        "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Frameset//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-frameset.dtd'>",
        "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'https://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>",
    )
    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-epub-xml-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun epub(path: Path, chapter: String, override: Pair<String, String>? = null) {
        val members = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>",
            "OPS/package.opf" to "<package><metadata><title>Native</title></metadata><manifest><item id='a' href='a.xhtml'/></manifest><spine><itemref idref='a'/></spine></package>",
            "OPS/a.xhtml" to chapter,
        )
        if (override != null) members[override.first] = override.second
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            members.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry()
            }
        }
    }

    @Test fun standardDoctypesKeepCanonicalPagesAndExactSourcePathsWithoutChangingBytes() = temporary { root ->
        val baseline = root.resolve("baseline.epub")
        epub(baseline, body)
        val pages = TextbookExtraction.extract(baseline, "epub", "fallback").pages
        val original = TextbookExtraction.epubSourceMapping(baseline, pages)
        for ((i, doctype) in doctypes.withIndex()) {
            val xml = "<?xml version='1.0'?>\n$doctype\n$body"
            val file = root.resolve("standard$i.epub"); epub(file, xml)
            val bytes = Files.readAllBytes(file)
            assertEquals(pages, TextbookExtraction.extract(file, "epub", "fallback").pages)
            val mapped = TextbookExtraction.epubSourceMapping(file, pages)
            assertEquals(EpubSourceMapping.Status.MAPPED, mapped.status)
            assertEquals(original.elements.map { it.copy(memberSha256 = "") }, mapped.elements.map { it.copy(memberSha256 = "") })
            val hash = MessageDigest.getInstance("SHA-256").digest(xml.toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(mapped.elements.all { it.memberSha256 == hash })
            assertContentEquals(bytes, Files.readAllBytes(file))
            val stale = TextbookExtraction.epubSourceMapping(file, listOf(pages.single().copy(text = "changed")))
            assertEquals(EpubSourceMapping.Status.STALE_TEXT, stale.status)
            assertTrue(stale.elements.isEmpty())
        }
    }

    @Test fun bomUtf16AndDoctypeLookingCommentsAreParsedAsXmlRatherThanStrippedWithRegex() {
        val expected = EpubXml.parse(body.toByteArray()).textContent
        for (encoding in listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE")) {
            val xml = "<?xml version='1.0' encoding='$encoding'?>\n<!-- <!DOCTYPE evil SYSTEM 'https://invalid.invalid/'> -->" +
                "\n<!DOCTYPE html>\n$body"
            assertEquals(expected, EpubXml.parse(xml.toByteArray(charset(encoding))).textContent)
        }
        assertEquals(expected, EpubXml.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + body.toByteArray()).textContent)
    }

    @Test fun internalDeclarationsAreRefusedEvenWhenTheirEntitiesAreNeverUsed() {
        val declarations = listOf(
            "<!ENTITY harmless 'changed'>",
            "<!ENTITY leak SYSTEM 'file:///not-a-learner-file'>",
            "<!ENTITY image SYSTEM 'file:///not-a-learner-file' NDATA png>",
            "<!NOTATION png SYSTEM 'https://invalid.invalid/notation'>",
            "<!ENTITY % param SYSTEM 'https://invalid.invalid/external.dtd'>%param;",
            "<!ENTITY a '1234567890'><!ENTITY b '&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;'>",
            "<!ELEMENT html ANY>",
            "<!ATTLIST html injected CDATA 'changed'>",
        )
        for (decl in declarations) {
            assertFails { EpubXml.parse("<!DOCTYPE html [$decl]>$body".toByteArray()) }
        }
    }

    @Test fun customOrMismatchedDtdsAndMalformedDeclarationsAreRefused() {
        for (decl in listOf(
            "<!DOCTYPE html SYSTEM 'file:///not-a-learner-file'>",
            "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'https://invalid.invalid/evil.dtd'>",
            "<!DOCTYPE html PUBLIC 'custom' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>",
            "<!DOCTYPE other>", "<!DOCTYPE html><!DOCTYPE html>", "<!DOCTYPE html [",
        )) assertFails { EpubXml.parse("$decl$body".toByteArray()) }
        assertFails { EpubXml.parse("<!DOCTYPE html><other/>".toByteArray()) }
        // We do not download a DTD to resolve named XHTML entities or silently omit them.
        assertFails { EpubXml.parse("${doctypes.last()}${body.replace("&#160;", "&nbsp;")}".toByteArray()) }
    }

    @Test fun fileEntitiesAreRefusedAndXincludeDoesNotReadFiles() = temporary { root ->
        val canary = root.resolve("canary.txt"); Files.writeString(canary, "PRIVATE_FILE_CANARY")
        val uri = canary.toUri()
        assertFails { EpubXml.parse("<!DOCTYPE html [<!ENTITY leak SYSTEM '$uri'>]><html>&leak;</html>".toByteArray()) }
        val xml = "<!DOCTYPE html><html xmlns:xi='http://www.w3.org/2001/XInclude'><body>Safe<xi:include href='$uri' parse='text'/></body></html>"
        assertEquals("Safe", EpubXml.parse(xml.toByteArray()).textContent)
    }

    @Test fun externalDtdEntitiesParametersXincludeAndSchemaMakeNoNetworkRequests() {
        val requests = AtomicInteger()
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            requests.incrementAndGet()
                            val payload = "NETWORK_CANARY"
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload".toByteArray())
                        }
                    } catch (_: SocketException) { if (!server.isClosed) throw AssertionError("listener failed") }
                }
            }.apply { start() }
            try {
                val uri = "http://127.0.0.1:${server.localPort}/external"
                for (xml in listOf(
                    "<!DOCTYPE html SYSTEM '$uri'>$body",
                    "<!DOCTYPE html [<!ENTITY leak SYSTEM '$uri'>]><html>&leak;</html>",
                    "<!DOCTYPE html [<!ENTITY % param SYSTEM '$uri'>%param;]>$body",
                )) assertFails { EpubXml.parse(xml.toByteArray()) }
                val xml = "<!DOCTYPE html><html xmlns:xi='http://www.w3.org/2001/XInclude' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:noNamespaceSchemaLocation='$uri'><body>Safe<xi:include href='$uri' parse='text'/></body></html>"
                assertEquals("Safe", EpubXml.parse(xml.toByteArray()).textContent)
            } finally { server.close(); worker.join(2000) }
            assertFalse(worker.isAlive)
            assertEquals(0, requests.get())
        }
    }

    @Test fun everyEpubXmlEntryPointRejectsEntitiesAndFailedImportsLeaveSavedBooksIntact() = temporary { root ->
        val safe = root.resolve("safe.epub"); epub(safe, "<!DOCTYPE html>$body")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(safe)
        for (member in listOf("META-INF/container.xml", "META-INF/encryption.xml", "OPS/package.opf", "OPS/a.xhtml")) {
            val file = root.resolve("unsafe.epub")
            epub(file, body, member to "<!DOCTYPE html [<!ENTITY unused 'expansion'>]>$body")
            assertFails { library.importBook(file) }
            assertEquals(listOf(book.id), library.catalog().map { it.id })
            assertEquals(book.pages, library.book(book.id).pages)
            assertTrue(Files.list(library.directory).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".import-") } })
        }
    }
}
