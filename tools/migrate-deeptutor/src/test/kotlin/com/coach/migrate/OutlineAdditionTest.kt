package com.coach.migrate

import com.coach.domain.*
import com.coach.domain.Module
import com.coach.store.LedgerStore
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.sql.DriverManager
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class OutlineAdditionTest {
    internal fun isolated(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("outline-addition-").toRealPath()
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun sql(dir: Path, statement: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
        db.createStatement().use { it.execute(statement) }
    }
    private fun state(dir: Path): Map<String, List<List<String?>>> = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
        listOf("events", "outline_kp", "course_modules", "course_content", "source_docs", "source_pages", "projection_cache").associateWith { table ->
            db.createStatement().use { st -> st.executeQuery("SELECT * FROM $table ORDER BY rowid").use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
            } }
        }
    }
    internal fun seed(dir: Path, legacy: Boolean = false) {
        LedgerStore(dir).use { store ->
            store.seedContent(ContentSeed(CourseContent(
                Course("C", "SYNTH", "Synthetic", examWeights = mapOf("old" to 100)),
                listOf(Module("old-module", 0, "Old")),
                listOf(KnowledgePoint("old", "old-module", 0, "【W1·Synthetic】Old", anchors = listOf(SourceAnchor("book", 2, 1))))
            ), listOf(TextbookDocument("book", "Synthetic textbook", "Synthetic source", listOf(TextbookPage(2, 1, "Synthetic page"))))))
            store.append("C", "legacy_passed", json("kpId" to "old"))
            store.snapshot("C") // Exercise cache invalidation and rollback too.
        }
        if (legacy) {
            sql(dir, "DELETE FROM course_content")
            sql(dir, "DELETE FROM course_modules")
        }
    }
    internal fun payload(pointChange: Map<String, JsonElement> = emptyMap()): JsonObject {
        val anchor = json("docId" to "book", "pdfPage" to 2, "bookPage" to 1)
        val point = json("id" to "new", "moduleId" to "new-module", "ordinal" to 1,
            "title" to "【W8·Synthetic】New", "week" to 8, "examFormats" to listOf("SINGLE_CHOICE"),
            "prerequisiteIds" to listOf("old"), "anchors" to JsonArray(listOf(anchor)), "sourceAnchor" to anchor)
        return json("course" to json("id" to "C"), "additive" to true, "retiresExisting" to false,
            "modules" to JsonArray(listOf(json("id" to "new-module", "ordinal" to 1, "name" to "New"))),
            "knowledgePoints" to JsonArray(listOf(JsonObject(point + pointChange))),
            // Never execute unvalidated operations supplied alongside the new points.
            "ops" to JsonArray(listOf(json("op" to "retire", "kpId" to "old"))))
    }

    @Test fun `legacy and structured additions preserve old facts and survive repeat after reopening`() = isolated { root ->
        for (legacy in listOf(false, true)) {
            val dir = root.resolve("database-$legacy"); seed(dir, legacy)
            val before = state(dir)
            LedgerStore(dir).use { store ->
                val events = store.events("C")
                val mastery = store.snapshot("C").projection.mastery
                assertTrue(store.addOutline(payload()))
                assertEquals(events, store.events("C").dropLast(1))
                assertEquals(listOf("old", "new"), weekPoints(store.events("C")).map { it.id })
                assertEquals(listOf("new"), weekPoints(store.events("C")).filter { 8 in it.weeks }.map { it.id })
                assertEquals(mastery.getValue("old"), store.snapshot("C").projection.mastery.getValue("old"))
                assertFalse(store.snapshot("C").projection.mastery.getValue("new").passed)
                assertEquals(project(store.events("C")), store.snapshot("C").projection)
                if (legacy) assertNull(store.courseContent("C")) else {
                    assertEquals(mapOf("old" to 100), store.courseContent("C")!!.course.examWeights)
                    assertEquals("Synthetic page", store.teachingPages("C", "new").single().second)
                    assertEquals(listOf("old", "new"), store.courseContent("C")!!.knowledgePoints.map { it.id })
                }
            }
            val installed = state(dir)
            assertEquals(before.getValue("outline_kp"), installed.getValue("outline_kp").take(1))
            for (table in listOf("source_docs", "source_pages")) assertEquals(before[table], installed[table])
            LedgerStore(dir).use { assertFalse(it.addOutline(payload())) }
            assertEquals(installed, state(dir))
        }
    }

    @Test fun `event failure rolls back metadata points and cached projection then permits retry`() = isolated { root ->
        for (legacy in listOf(false, true)) {
            val dir = root.resolve("database-$legacy"); seed(dir, legacy)
            sql(dir, "CREATE TRIGGER fail_outline BEFORE INSERT ON events WHEN NEW.type='outline_revised' BEGIN SELECT RAISE(ABORT,'injected event failure'); END")
            val before = state(dir)
            LedgerStore(dir).use { store ->
                assertFailsWith<java.sql.SQLException> { store.addOutline(payload()) }
                assertEquals(before, state(dir))
                sql(dir, "DROP TRIGGER fail_outline")
                assertTrue(store.addOutline(payload()))
                assertEquals(4, store.events("C").size)
            }
        }
    }

    @Test fun `bad anchors prerequisites ordering and conflicting repeat leave facts unchanged`() = isolated { root ->
        val dir = root.resolve("database"); seed(dir)
        val invalid = listOf(
            mapOf("prerequisiteIds" to JsonArray(listOf(JsonPrimitive("missing")))),
            mapOf("prerequisiteIds" to JsonArray(listOf(JsonPrimitive("new")))),
            mapOf("ordinal" to JsonPrimitive(0)),
            mapOf("week" to JsonPrimitive(9)),
            mapOf("anchors" to JsonArray(listOf(json("docId" to "book", "pdfPage" to 99)))),
            mapOf("sourceAnchor" to json("docId" to "book", "pdfPage" to 99))
        )
        LedgerStore(dir).use { store ->
            val before = state(dir)
            for (change in invalid) {
                assertFailsWith<IllegalArgumentException> { store.addOutline(payload(change)) }
                assertEquals(before, state(dir))
            }
            assertTrue(store.addOutline(payload()))
            val after = state(dir)
            assertFailsWith<IllegalArgumentException> { store.addOutline(payload(mapOf("title" to JsonPrimitive("【W8·Synthetic】Changed")))) }
            assertEquals(after, state(dir))
        }
    }

    @Test fun `cross course hidden and incorrect book page anchors are refused`() = isolated { root ->
        val dir = root.resolve("database"); seed(dir)
        for (change in listOf("course_id='other'", "tutor_visible=0", "learner_visible=0", "title='sealed answers'")) {
            sql(dir, "UPDATE source_docs SET $change")
            val before = state(dir)
            LedgerStore(dir).use { assertFailsWith<IllegalArgumentException> { it.addOutline(payload()) } }
            assertEquals(before, state(dir))
            sql(dir, "UPDATE source_docs SET course_id='C',tutor_visible=1,learner_visible=1,title='Synthetic textbook'")
        }
        sql(dir, "UPDATE source_pages SET book_page=100")
        LedgerStore(dir).use { assertFailsWith<IllegalArgumentException> { it.addOutline(payload()) } }
    }

    @Test fun `CLI shares server lock and installs only once`() = isolated { root ->
        val dir = root.resolve("database"); seed(dir, legacy = true)
        val input = root.resolve("input.json"); input.writeText(payload().toString())
        val preflightArgs = arrayOf("add-outline", "--input", input.toString(), "--database", dir.toString(), "--preflight")
        val expect = root.resolve("expect.json")
        fun apply(label: String) {
            expect.writeText(canonical(offlineData(dir) { outlinePreflight(dir, input.readBytes()) }))
            main(arrayOf("add-outline", "--input", input.toString(), "--database", dir.toString(),
                "--expect", expect.toString(), "--backup", root.resolve(label).toString()))
        }
        val before = state(dir)
        FileChannel.open(dir.resolve("server.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            assertFailsWith<IllegalStateException> { main(preflightArgs) }
            assertEquals(before, state(dir))
        } }
        apply("backup-first")
        val installed = state(dir)
        apply("backup-repeat")
        assertEquals(installed, state(dir))
        assertEquals(before.getValue("events").size + 1, installed.getValue("events").size)
    }
}
