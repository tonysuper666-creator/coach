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
}
