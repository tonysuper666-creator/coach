package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 十本合成短书、二十个互不共享实词的概念。
 * 半截概念不超过 1，把两个不同概念配成一对的不超过 2。
 */
class ConceptSpotCheckTest {
    @Test fun tenBooksKeepConceptsWholeAndDoNotCrossPair() = runBlocking {
        val root = Files.createTempDirectory("concept-spot-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val books = (0 until 10).map { index ->
                library.importBook(write(root, "spot-$index.md", bookText(index)))
            }
            val colId = "spot-check"
            library.upsertCollection(MaterialCollectionRecord(colId, CollectionKind.BOOKLIST, "合成概念抽查"))
            books.forEachIndexed { index, book ->
                library.attachMaterial(CollectionMaterialRecord(colId, book.id, role = "LIST_BOOK", ordinal = index))
            }
            val extracted = books.associate { book ->
                book.id to ChapterConceptExtractor.extractForBook(book).first
            }
            val report = BooklistAlignmentEngine.alignBooklist(library, colId, HeuristicConceptSemanticJudge)
            assertEquals(10, report.booksProcessed)
            assertTrue(report.extractedMentions >= 15, "mentions=${report.extractedMentions}")
            val sharedInTwo = SHARED.count { phrase ->
                books.count { book ->
                    extracted.getValue(book.id).any { phrase.lowercase() in it.term.lowercase() }
                } >= 2
            }
            assertTrue(sharedInTwo >= 8, "shared=$sharedInTwo extracted=${extracted.values.flatten().map { it.term }}")
            val names = extracted.values.flatten().map { it.term } +
                library.booklistConcepts(colId, books.associate { it.id to it.title }, emptyMap())
                    .let { it.active + it.muted }
                    .map { it.entity.canonicalName }
            val fragments = names.filter { ChapterConceptExtractor.looksLikeFragment(it) }.distinct()
            assertTrue(fragments.size <= 1, fragments.toString())
            assertTrue(names.none { it.equals("adjustment transfers", ignoreCase = true) })
            val snapshot = library.booklistConcepts(colId, books.associate { it.id to it.title }, emptyMap())
            var mismatches = 0
            for (view in snapshot.active + snapshot.muted) {
                val byId = view.mentions.associateBy { it.mention.mentionId }
                for (link in view.links) {
                    val left = byId[link.fromMentionId]?.mention
                    val right = byId[link.toMentionId]?.mention
                    val a = left?.let { plantedIndex(it.quoteAnchor) ?: plantedIndex(it.claimSummary) }
                    val b = right?.let { plantedIndex(it.quoteAnchor) ?: plantedIndex(it.claimSummary) }
                    if (a != null && b != null && a != b) mismatches++
                }
            }
            assertTrue(mismatches <= 2, "mismatches=$mismatches links=${report.alignedLinks}")
            assertTrue(report.alignedLinks >= 5, "links=${report.alignedLinks}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun plantedIndex(text: String): Int? {
        val hits = PLANTED.mapIndexedNotNull { index, phrase ->
            if (text.contains(phrase, ignoreCase = true)) index else null
        }
        return hits.singleOrNull()
    }

    private fun bookText(index: Int): String = buildString {
        append("# Chapter 1 Domain\n\n")
        append(sentence(SHARED[index])).append("\n\n")
        append(sentence(UNIQUE[index])).append("\n\n")
        append(sentence(SHARED[(index + 5) % SHARED.size])).append("\n\n")
        if (index == 0) {
            append("Compulsory participation combined with risk adjustment transfers mitigates adverse selection across competing insurers.\n\n")
        }
    }

    private fun sentence(phrase: String): String =
        "$phrase explains how the other people use this and that within the same system during the early years."

    private fun write(dir: Path, name: String, text: String): Path =
        dir.resolve(name).also { Files.writeString(it, text) }

    private companion object {
        val SHARED = listOf(
            "velour quota",
            "kelp spindle",
            "cobalt lattice",
            "saffron ledger",
            "quartz pulley",
            "linen turbine",
            "amber filament",
            "cedar manifold",
            "ivory gasket",
            "maple conduit",
        )
        val UNIQUE = listOf(
            "pewter cistern",
            "bronze caliper",
            "copper bellows",
            "granite trestle",
            "willow harrow",
            "basalt kiln",
            "juniper loom",
            "slate hopper",
            "garnet siphon",
            "topaz rudder",
        )
        val PLANTED = SHARED + UNIQUE
    }
}
