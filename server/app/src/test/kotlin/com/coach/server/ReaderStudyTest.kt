package com.coach.server

import com.coach.domain.*
import com.coach.store.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Invented content only. Page placement comes from registered sources; a source that cannot be placed is counted, never guessed. */
class ReaderStudyTest {
    private fun item(id: String, source: String, facts: List<String>) = json(
        "id" to id, "type" to "mcq", "kp_week" to 1, "kp" to "kp-orchard", "key_state" to "VERIFIED", "fact_ids" to facts, "tier" to "setup", "marks" to 1,
        "stem" to "Synthetic orchard question $id?", "options" to JsonArray(listOf("A$id", "B$id", "C$id", "D$id").map(::JsonPrimitive)), "answer" to 1,
        "explanation" to "正确：B.\n出处：$source", "source" to source)

    @Test fun pageListsOnlyItsOwnPointsAndCountsUnplaceableSources() {
        val dir = Files.createTempDirectory("reader-study")
        try {
            val root = Files.createDirectories(dir.resolve("papers/mcq-mainline"))
            val pack = json("id" to "mainline-W01", "kind" to "pack", "course" to "C1", "minutes" to 10, "sections" to JsonArray(listOf(json(
                "title" to "Week 1", "instructions" to "x", "choose" to null, "items" to JsonArray(listOf(
                    item("Q1", "教材 p.5", listOf("F1")), item("Q2", "教材 p.9", listOf("F2")), item("Q3", "", listOf("F3")), item("Q4", "Unregistered note", emptyList())))))))
            Files.writeString(root.resolve("W01.json"), pack.toString())
            Files.writeString(root.resolve("W01-facts.md"), """
```jsonl
{"fact_id":"F1","fact":"Point on five.","type":"definition","source":"教材 p.5","kp":"kp-orchard"}
{"fact_id":"F2","fact":"Point on nine.","type":"detail","source":"教材 p.9","kp":"kp-orchard"}
{"fact_id":"F3","fact":"Point with no source.","type":"detail","source":"","kp":"kp-orchard"}
```""".trimIndent())
            val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { AtomicLong(1_800_000_000_000).get() }
            val mainline = Mainline(MockLibrary(dir.resolve("papers")))
            // Textbook offset 31: printed p.N sits on PDF page N + 31.
            val locate = { s: String -> Regex("^教材 p[.]([0-9]+)$").matchEntire(s)?.let { "book" to it.groupValues[1].toInt() + 31 } }
            val five = mainline.pageStudy(store, "C1", "book", 36, locate)
            assertEquals(listOf("F1"), five.getValue("facts").jsonArray.map { it.jsonObject.str("id") })
            assertEquals(listOf("Q1"), five.getValue("questions").jsonArray.map { it.jsonObject.str("id") })
            assertEquals(3L, five.num("unlocated"), "Q3, Q4 and F3 have no placeable source")
            val none = mainline.pageStudy(store, "C1", "book", 2, locate)
            assertTrue(none.getValue("facts").jsonArray.isEmpty() && none.getValue("questions").jsonArray.isEmpty())
            val other = mainline.pageStudy(store, "OTHER", "book", 36, locate)
            assertTrue(other.getValue("questions").jsonArray.isEmpty(), "another course never sees this course's pack")
            assertEquals("new", Mainline(MockLibrary(dir.resolve("papers"))).pageStudy(store, "C1", "book", 36, locate)
                .getValue("questions").jsonArray.single().jsonObject.str("status"))
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun packDir(dir: java.nio.file.Path, sources: List<String>, factSources: List<String>): java.nio.file.Path {
        val root = Files.createDirectories(dir.resolve("papers/mcq-mainline"))
        val items = sources.mapIndexed { i, src -> item("Q$i", src, listOf("F$i")) }
        val pack = json("id" to "mainline-W01", "kind" to "pack", "course" to "C1", "minutes" to 10, "sections" to JsonArray(listOf(json(
            "title" to "Week 1", "instructions" to "x", "choose" to null, "items" to JsonArray(items)))))
        Files.writeString(root.resolve("W01.json"), pack.toString())
        val facts = factSources.mapIndexed { i, src -> json("fact_id" to "F$i", "fact" to "Point $i.", "type" to "detail", "source" to src, "kp" to "kp-orchard").toString() }
        Files.writeString(root.resolve("W01-facts.md"), (listOf("```jsonl") + facts + listOf("```")).joinToString(10.toChar().toString()))
        return dir.resolve("papers")
    }

    private val outline = listOf(
        OutlineNode("Chapter A", 10, listOf(OutlineNode("Section A1", 10), OutlineNode("Section A2", 14))),
        OutlineNode("Chapter B", 20))

    private fun withCitationStudy(sources: List<String>, block: (Mainline, LedgerStore, ReaderLibrary) -> Unit) {
        val dir = Files.createTempDirectory("reader-citations")
        try {
            val papers = packDir(dir, sources, sources)
            val reader = Files.createDirectories(dir.resolve("reader"))
            val pdf = byteArrayOf(1) // Catalog/resolve only: no rendering or private material.
            Files.write(reader.resolve("synthetic.pdf"), pdf)
            val chapters = JsonArray(listOf(
                json("title" to "Chapter A", "pdfPage" to 1),
                json("title" to "Chapter B", "pdfPage" to 35, "sections" to JsonArray(listOf(
                    json("title" to "Section B1", "pdfPage" to 35), json("title" to "Section B2", "pdfPage" to 38)))),
                json("title" to "Chapter C", "pdfPage" to 45)))
            val doc = json("id" to "book", "course" to "C1", "title" to "Invented", "file" to "synthetic.pdf",
                "sha256" to ReaderLibrary.digest(pdf), "pages" to 50, "printedPageOffset" to 31, "kind" to "textbook", "outline" to chapters)
            Files.writeString(reader.resolve("catalog.json"), json("schema" to 1, "documents" to JsonArray(listOf(doc))).toString())
            val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { 1_800_000_000_000 }
            block(Mainline(MockLibrary(papers)), store, ReaderLibrary(reader))
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun ReaderLibrary.locator(): (String) -> Pair<String, Int>? = { source ->
        runCatching { resolve("C1", source) }.getOrNull()?.let { it.str("documentId") to it.num("pdfPage").toInt() }
    }

    private fun ids(value: JsonObject, key: String) = value.getValue(key).jsonArray.map { it.jsonObject.str("id") }

    private val punctuatedCitations = listOf("。", "．", ".", "，", ",", "、").map { punctuation ->
        " \tW1 课件 p.26； 教材 p.8$punctuation \t "
    } + listOf(
        "教材 p.8",
        " ;；\n。；教材 p.8.\r\n，; ",
        "教材 p.8。;\n教材 p.8,",
        "教材 p.8\nW1 课件 p.26")

    @Test fun pageStudyResolvesCompositeCitationsWithSentencePunctuation() = withCitationStudy(punctuatedCitations) { mainline, store, reader ->
        val study = mainline.pageStudy(store, "C1", "book", 39, reader.locator())
        assertEquals(punctuatedCitations.indices.map { "Q$it" }, ids(study, "questions"))
        assertEquals(punctuatedCitations.indices.map { "F$it" }, ids(study, "facts"))
        assertEquals(0L, study.num("unlocated"), "trailing punctuation must not hide registered textbook pages")
        val adjacent = mainline.pageStudy(store, "C1", "book", 38, reader.locator())
        assertTrue(ids(adjacent, "questions").isEmpty() && ids(adjacent, "facts").isEmpty())
    }

    @Test fun documentMapResolvesCompositeCitationsIntoTheirRegisteredChapterAndSection() = withCitationStudy(punctuatedCitations) { mainline, store, reader ->
        val doc = reader.document("C1", "book")
        val map = mainline.documentMap(store, "C1", doc.id, doc.pages, doc.outline, reader.locator())
        val chapters = map.getValue("chapters").jsonArray.map { it.jsonObject }
        val sections = chapters[1].getValue("sections").jsonArray.map { it.jsonObject }
        assertEquals("Chapter B", chapters[1].str("title"))
        assertEquals("Section B2", sections[1].str("title"))
        assertEquals(punctuatedCitations.indices.map { "Q$it" }, ids(sections[1], "questions"))
        assertEquals(punctuatedCitations.indices.map { "F$it" }, ids(sections[1], "facts"))
        for (key in listOf("questions", "facts")) {
            assertTrue(sections[1].getValue(key).jsonArray.all { it.jsonObject.num("pdfPage") == 39L })
            assertTrue((chapters + sections.take(1)).all { ids(it, key).isEmpty() })
        }
        assertEquals(0L, map.num("unlocated"))
        assertEquals(0L, map.num("ambiguous"), "duplicate references to the same page are not ambiguous")
        assertEquals(0L, map.num("outsideOutline"))
    }

    private val unplaceableAndAmbiguous = listOf("W1 课件 p.26。", "", " ;；\r\n。；，.、; ", "教材 p.8；教材 p.9。")

    @Test fun pageStudyNeverGuessesUnregisteredOrEmptyCitationsOntoAPage() = withCitationStudy(unplaceableAndAmbiguous) { mainline, store, reader ->
        for (page in listOf(39, 40)) {
            val study = mainline.pageStudy(store, "C1", "book", page, reader.locator())
            assertEquals(listOf("Q3"), ids(study, "questions"), "both registered pages remain visible on page $page")
            assertEquals(listOf("F3"), ids(study, "facts"))
            assertEquals(6L, study.num("unlocated"), "only the unregistered, blank and punctuation-only sources are unlocated")
        }
        for (page in listOf(8, 26)) {
            val study = mainline.pageStudy(store, "C1", "book", page, reader.locator())
            assertTrue(ids(study, "questions").isEmpty() && ids(study, "facts").isEmpty(), "never guess printed/slide page $page")
        }
    }

    @Test fun documentMapKeepsUnregisteredCitationsUnlocatedAndMultiplePagesAmbiguous() = withCitationStudy(unplaceableAndAmbiguous) { mainline, store, reader ->
        val doc = reader.document("C1", "book")
        val map = mainline.documentMap(store, "C1", doc.id, doc.pages, doc.outline, reader.locator())
        assertEquals(6L, map.num("unlocated"))
        assertEquals(2L, map.num("ambiguous"), "sentence punctuation must not conceal a second registered page")
        for (chapter in map.getValue("chapters").jsonArray.map { it.jsonObject }) {
            val buckets = listOf(chapter) + chapter.getValue("sections").jsonArray.map { it.jsonObject }
            assertTrue(buckets.all { ids(it, "questions").isEmpty() && ids(it, "facts").isEmpty() })
        }
    }

    @Test fun mapPlacesPointsAndQuestionsByRegisteredPageAndReportsWhatItCannotPlace() {
        val dir = Files.createTempDirectory("reader-map")
        try {
            // Q0 → p.11 (A1), Q1 → p.15 (A2), Q2 → p.25 (B direct), Q3 → two pages (ambiguous), Q4 → no source, Q5 → page 3 (before every chapter)
            val sources = listOf("P 11", "P 15", "P 25", "P 11; P 15", "", "P 3")
            val papers = packDir(dir, sources, sources)
            val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { AtomicLong(1_800_000_000_000).get() }
            val locate = { s: String -> Regex("^P ([0-9]+)$").matchEntire(s)?.let { "book" to it.groupValues[1].toInt() } }
            val map = Mainline(MockLibrary(papers)).documentMap(store, "C1", "book", 40, outline, locate)
            val chapters = map.getValue("chapters").jsonArray.map { it.jsonObject }
            val a = chapters[0]; val b = chapters[1]
            val sections = a.getValue("sections").jsonArray.map { it.jsonObject }
            fun ids(o: JsonObject, key: String) = o.getValue(key).jsonArray.map { it.jsonObject.str("id") }
            assertEquals(listOf("Q0"), ids(sections[0], "questions")); assertEquals(listOf("F0"), ids(sections[0], "facts"))
            assertEquals(listOf("Q1"), ids(sections[1], "questions"))
            assertEquals(listOf("Q2"), ids(b, "questions"))
            assertEquals(14L, sections[1].num("pdfPage")); assertEquals(19L, sections[1].num("endPage")); assertEquals(19L, a.num("endPage")); assertEquals(40L, b.num("endPage"))
            assertEquals(2L, map.num("ambiguous")); assertEquals(2L, map.num("unlocated")); assertEquals(2L, map.num("outsideOutline"))
            val none = Mainline(MockLibrary(papers)).documentMap(store, "C1", "book", 40, emptyList(), locate)
            assertFalse(none.getValue("hasOutline").jsonPrimitive.boolean); assertTrue(none.getValue("chapters").jsonArray.isEmpty())
            val other = Mainline(MockLibrary(papers)).documentMap(store, "OTHER", "book", 40, outline, locate)
            assertTrue(other.getValue("chapters").jsonArray.all { c -> c.jsonObject.getValue("questions").jsonArray.isEmpty() && c.jsonObject.getValue("sections").jsonArray.all { it.jsonObject.getValue("questions").jsonArray.isEmpty() } })
            val elsewhere = Mainline(MockLibrary(papers)).documentMap(store, "C1", "other-book", 40, outline, locate)
            assertTrue(elsewhere.getValue("chapters").jsonArray.all { it.jsonObject.getValue("questions").jsonArray.isEmpty() }, "a point registered on another document is not placed in this one")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun catalogOutlineIsValidatedAndOptional() {
        val dir = Files.createTempDirectory("reader-outline")
        try {
            Files.write(dir.resolve("a.pdf"), byteArrayOf(1))
            fun lib(outline: JsonElement?): ReaderLibrary {
                val doc = json("id" to "book", "course" to "C1", "title" to "Invented", "file" to "a.pdf", "sha256" to "a".repeat(64), "pages" to 40,
                    "printedPageOffset" to 31, "kind" to "textbook", "outline" to outline)
                Files.writeString(dir.resolve("catalog.json"), json("schema" to 1, "documents" to JsonArray(listOf(doc))).toString())
                return ReaderLibrary(dir)
            }
            fun node(title: String, page: Int, sections: List<JsonObject> = emptyList()) = json("title" to title, "pdfPage" to page, "sections" to JsonArray(sections))
            assertTrue(lib(null).list("C1").single().outline.isEmpty())
            val ok = lib(JsonArray(listOf(node("A", 10, listOf(node("A1", 10), node("A2", 14))), node("B", 20)))).list("C1").single().outline
            assertEquals(listOf("A", "B"), ok.map { it.title }); assertEquals(listOf("A1", "A2"), ok[0].sections.map { it.title })
            for (bad in listOf(
                JsonArray(listOf(node("A", 41))), JsonArray(listOf(node("A", 10), node("B", 10))), JsonArray(listOf(node("A", 10, listOf(node("S", 9))))),
                JsonArray(listOf(node("A", 10, listOf(node("S", 25))), node("B", 20))), JsonArray(listOf(node("", 10)))))
                assertFailsWith<IllegalArgumentException> { lib(bad) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
