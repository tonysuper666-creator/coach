package com.coach.desktop.books

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ChapterStructureEditTest {
    @Test fun renamedChapterOwnsTheHeaderAboveNestedSectionsAfterReopening() {
        val root = Files.createTempDirectory("coach-chapter-header-")
        try {
            val file = root.resolve("sample.md")
            Files.writeString(file, (1..6).joinToString("\u000c") { "A sample paragraph for physical page $it." })
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(file)
            val outline = listOf("Chapter 1 First", "1.1 Section", "1.1.1 Detail", "Chapter 2 Second", "2.1 Sibling", "Index")
                .mapIndexed { index, title -> BookOutlineItem(title, 1, index + 1, HeadingSource.PDF_OUTLINE) }
            val book = library.saveBookStructure(imported.id, BookStructurePlanner.plan(imported.id, imported.title,
                imported.format, imported.pages, outline))
            fun headers(book: Textbook) = book.pages.map { page ->
                val laid = BookLaidPage(listOf(BookPageSlice(page.number, 0, page.text.length)), 0, null, null,
                    false, androidx.compose.ui.text.AnnotatedString(page.text), emptyList(), 0, 1)
                BookBoundaries.runningHeaderTitle(book, laid)
            }
            val original = headers(book)
            assertEquals(outline.map { it.title }, original)
            val renamed = library.editStructure(book, StructureEdit.Rename(book.chapterSpans.first().tocNodeId, "Revised"))
            val expected = List(3) { "Revised" } + original.drop(3)
            for (copy in listOf(renamed, TextbookLibrary(library.directory).book(book.id))) {
                assertEquals(expected, headers(copy))
                assertEquals(TocNodeKind.SUBSECTION, BookBoundaries.activeTocNode(copy.tocNodes, 3)?.kind)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun editsKeepSourcePagesAndRebuildChaptersLessonsCachesAndDwellTogether() {
        val root = Files.createTempDirectory("coach-structure-edit-")
        try {
            val source = root.resolve("sample.md")
            Files.writeString(source, (1..6).joinToString("\u000c") { page ->
                val heading = when (page) {
                    1 -> "# Chapter 1 First\n\n"
                    5 -> "# Chapter 2 Last\n\n"
                    else -> ""
                }
                heading + "This is the sample paragraph for page $page. ".repeat(8)
            })
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(source)
            val first = book.chapterSpans.first()
            val guide = CachedSectionGuide(emptyList(), "old", emptyList(), "")
            library.recordPageDwell(book.id, first.tocNodeId, 3, first.pagesTotal)
            library.saveSectionGuide(book.id, "sample", guide)
            val split = library.editStructure(book, StructureEdit.Split(first.tocNodeId, 3, "Middle"))
            val mid = split.chapterSpans.single { it.title == "Middle" }
            assertEquals(3..4, mid.firstPage..mid.lastPage)
            assertEquals(setOf(3), library.chapterProgressMap(book.id).getValue(mid.tocNodeId).dwelledPages)
            assertTrue(mid.sectionIndices.isNotEmpty())
            assertTrue(mid.sectionIndices.all { split.lessons[it].blocks.all { b -> b.page in 3..4 } })
            assertNull(library.sectionGuide(book.id, "sample"))
            library.saveSectionGuide(book.id, "sample", guide, book)
            assertNull(library.sectionGuide(book.id, "sample"))
            assertFails { library.editStructure(book, StructureEdit.Rename(first.tocNodeId, "Stale")) }
            val renamed = library.editStructure(split, StructureEdit.Rename(mid.tocNodeId, "Revised"))
            val reopened = TextbookLibrary(library.directory).book(book.id)
            assertEquals(renamed.tocNodes, reopened.tocNodes)
            assertEquals("Revised", BookChapterResolver.chapterForPage(reopened, 3)?.title)
            val section = reopened.chapterSpans.single { it.title == "Revised" }.sectionIndices.first()
            assertEquals("Revised", ChapterCompanionGrouping.groupForSection(reopened, section)?.chapterTitle)
            assertTrue(reopened.lessons[section].breadcrumb.contains("Revised"))
            val merged = library.editStructure(reopened, StructureEdit.MergeWithPrevious(mid.tocNodeId))
            assertEquals(book.chapterSpans.map { it.title }, merged.chapterSpans.map { it.title })
            assertEquals(setOf(3), library.chapterProgressMap(book.id).getValue(first.tocNodeId).dwelledPages)
            // Save in an independent process, then halt it without running JVM shutdown hooks.
            val cp = (generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList() +
                System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
            val args = root.resolve("child.args")
            Files.writeString(args, "-cp\n\"" + cp.replace("\\", "/") + "\"\n")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@$args", StructureSaveChild::class.java.name, library.directory.toString(), book.id)
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start()
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS))
                assertEquals(23, process.exitValue(), Files.readString(root.resolve("child.log")))
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
            val corrected = TextbookLibrary(library.directory).book(book.id)
            assertEquals(3, corrected.bodyStart.importPage)
            assertEquals(3, corrected.readingPage)
            assertEquals(3, corrected.chapterSpans.first().firstPage)
            assertTrue(corrected.lessons.isNotEmpty())
            assertTrue(corrected.lessons.all { it.firstPage >= 3 })
            assertEquals(book.pages, corrected.pages)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

internal object StructureSaveChild {
    @JvmStatic fun main(args: Array<String>) {
        TextbookLibrary(Path.of(args[0])).saveBodyStart(args[1], 3)
        Runtime.getRuntime().halt(23)
    }
}
