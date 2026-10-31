package com.coach.migrate

import com.coach.domain.*
import com.coach.domain.Module
import com.coach.store.LedgerStore
import java.nio.file.*
import java.sql.DriverManager
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test
import kotlinx.serialization.json.*

class SeedContentTest {
    private fun fixture(root: Path): Path {
        val snap = privateDir(root.resolve("snapshot"))
        fun file(path: String, text: String) { val p = snap.resolve(path); privateDir(p.parent); p.writeText(text) }
        file("copy-stats.json", "{}")
        file("data/user/workspace/courses/courses.json", """{"courses":[{"id":"synthetic-course","name":"ECNM10119 synthetic","instructions":"Teach clearly","resources":[{"kind":"mastery_path","ref_id":"main"}]}]}""")
        val titles = linkedMapOf(
            "01" to listOf("Ch1 synthetic", "Ch1 revised-order"),
            "02" to listOf("Ch6 HHI= synthetic", "Ch5 NPV= synthetic", "Coinsurance copayment deductible"),
            "03" to listOf("Ch6 differentiated product oligopoly", "Ch6 Hill-Burton"),
            "05" to (1..6).map { "Ch9 synthetic RS $it" },
            "06" to listOf("Ch2 CRHIE"),
            "07" to listOf("Ch11 RAND HIE 设计", "Ch11 实证证据归类"),
            "09" to listOf("Ch10 三个预测"),
            "11" to listOf("Ch4 假说对应 Grossman 参数")
        )
        val modules = titles.map { (number, names) -> buildJsonObject {
            put("id", "module-$number"); put("name", "$number synthetic module")
            put("knowledge_points", JsonArray(names.mapIndexed { i, name -> buildJsonObject {
                put("id", "kp-$number-$i"); put("module_id", "module-$number"); put("name", name); put("type", "memory")
                put("prerequisite_ids", JsonArray(emptyList()))
            } }))
        } }
        val state = buildJsonObject {
            put("modules", JsonArray(modules))
            put("mastery_levels", json("kp-01-0" to 1.0))
            put("review_schedule", json("secret" to "PRIVATE_LEARNER_DATA"))
            put("lesson_evidence", json("kp-01-0" to json(
                "coverage" to "PRIVATE_LEARNER_DATA", "sources" to JsonArray(listOf(json(
                    "text" to "PRIVATE_LEARNER_DATA", "ref" to json("document_key" to "原件/synthetic.pdf", "page_index" to 9, "page_label" to "9")
                )))
            )))
        }
        val dbPath = snap.resolve("data/user/workspace/learning/mastery/mastery.sqlite3")
        privateDir(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE mastery_paths(path_id TEXT PRIMARY KEY,state_json TEXT)") }
            db.prepareStatement("INSERT INTO mastery_paths VALUES(?,?)").use { ps -> ps.setString(1,"main"); ps.setString(2,state.toString()); ps.executeUpdate() }
        }
        for(ch in 1..12) file("library/ECNM10119-健康经济学/教材/${ch.toString().padStart(2,'0')}-synthetic.md", """
            # Synthetic chapter $ch
            来源：synthetic.pdf
            <!-- PDF 第 ${ch * 10} 页 · 书第 ${ch * 10 - 1} 页 -->
            Synthetic textbook chapter $ch, first page. ${if(ch == 2) "Coinsurance copayment deductible" else ""}
            <!-- PDF 第 ${ch * 10 + 1} 页 · 书第 ${ch * 10} 页 -->
            Synthetic textbook second page.
        """.trimIndent())
        file("exam-weights.md", """
            | 分值 | 题 | 对应知识点 | 状态 |
            |---|---|---|---|
            | 30 | Synthetic RS | 05 RS 图与均衡（6 个知识点） | ignored |
            | 15 | Synthetic joint essay | 07·RAND/Oregon 设计与发现；06·因果识别与 RCT | ignored |
            | 13 | Synthetic hospital essay | 03·本地医院市场结构 | ignored |
            | 6 | Synthetic hospital MCQ | 03·美国医院趋势 | ignored |
            | 6 | Synthetic concentration | 02·HHI | ignored |
            | 6 | Synthetic experiment | 06·因果识别、设计 | ignored |
            | 6 | Synthetic discounting | 02·NPV/IRR | ignored |
            | 6 | Synthetic predictions | 09·三个预测 | ignored |
            | 6 | Synthetic health | 11·假说对应 Grossman 参数 | ignored |
            | 6 | Synthetic moral hazard | 07·ex ante/ex post | ignored |
        """.trimIndent())
        return snap
    }

    @Test fun `snapshot content preserves source order and locators without learner data`() {
        val root = Files.createTempDirectory("seed-content-source").toRealPath()
        try {
            val snap = fixture(root)
            val before = Files.readAllBytes(snap.resolve("data/user/workspace/learning/mastery/mastery.sqlite3"))
            val a = contentSeed(snap); val b = contentSeed(snap)
            assertEquals(canonical(LedgerJson.encodeToJsonElement(ContentSeed.serializer(),a)), canonical(LedgerJson.encodeToJsonElement(ContentSeed.serializer(),b)))
            assertContentEquals(before, Files.readAllBytes(snap.resolve("data/user/workspace/learning/mastery/mastery.sqlite3")))
            assertFalse(a.toString().contains("PRIVATE_LEARNER_DATA"))
            assertEquals("", a.content.course.instructionsMd)
            assertEquals(listOf("kp-01-0", "kp-01-1", "kp-02-0"), a.content.knowledgePoints.take(3).map { it.id })
            assertTrue(a.content.knowledgePoints.all { it.anchors.isNotEmpty() })
            assertTrue(a.content.knowledgePoints.all { it.kind == null })
            val first = a.content.knowledgePoints.first().anchors.first()
            assertEquals("SOURCE_REFERENCE", first.basis); assertEquals(10, first.pdfPage); assertEquals(9, first.bookPage)
            val slides = a.content.knowledgePoints.single { it.id == "kp-02-2" }
            assertTrue(slides.anchors.all { it.basis == "TEXT_MATCH" && it.pdfPage == 20 })
            assertEquals(100, a.content.course.examWeights.values.sum())
            assertTrue(a.content.course.examWeightGroups.all { it.points == it.allocations.values.sum() })
            assertEquals(listOf(8,7), a.content.course.examWeightGroups[1].allocations.values.toList())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `repeated install is byte stable and revisions preserve existing learning`() {
        val root = Files.createTempDirectory("seed-content-install").toRealPath()
        try {
            val seed = contentSeed(fixture(root)); val database = root.resolve("coach")
            LedgerStore(database).use { store ->
                assertTrue(store.seedContent(seed))
                val events = store.events(seed.content.course.id)
                assertEquals(listOf("outline_revised", "exam_weights_set"), events.map { it.type })
                assertTrue(store.snapshot(seed.content.course.id).projection.mastery.values.none { it.passed })
                assertFalse(store.seedContent(seed))
                assertEquals(events, store.events(seed.content.course.id))
                for(kp in seed.content.knowledgePoints) assertTrue(store.teachingPages(seed.content.course.id, kp.id).all { it.second.contains("Synthetic textbook") })
                val learner = store.append(seed.content.course.id, "legacy_passed", json("kpId" to "kp-01-0"))
                val revised = seed.copy(content=seed.content.copy(course=seed.content.course.copy(title="Revised synthetic course")))
                assertTrue(store.seedContent(revised))
                assertEquals(learner, store.events(seed.content.course.id).single { it.id == learner.id })
                assertTrue(store.snapshot(seed.content.course.id).projection.mastery.getValue("kp-01-0").passed)
                assertEquals(revised.content, store.courseContent(seed.content.course.id))
            }
            val before = Files.readAllBytes(database.resolve("coach.sqlite"))
            val revised = seed.copy(content=seed.content.copy(course=seed.content.course.copy(title="Revised synthetic course")))
            LedgerStore(database).use { assertFalse(it.seedContent(revised)) }
            assertContentEquals(before, Files.readAllBytes(database.resolve("coach.sqlite")))
            DriverManager.getConnection("jdbc:sqlite:${database.resolve("coach.sqlite")}").use { db ->
                for(table in listOf("questions", "messages", "question_kp")) db.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM $table").use { rs -> assertTrue(rs.next()); assertEquals(0,rs.getInt(1)) }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `unresolved weight hints fail before installing content`() {
        assertFailsWith<IllegalStateException> {
            examWeightGroups("| 6 | unknown | no such hint | pending |", emptyList(), emptyList())
        }
    }

    @Test fun `explicit database dispatches the structured CLI alongside the corpus seeder`() {
        val root = Files.createTempDirectory("seed-content-cli").toRealPath()
        try {
            val snap = fixture(root)
            val database = root.resolve("coach")
            val out = root.resolve("output")
            main(arrayOf("seed-content", "--snapshot", snap.toString(), "--database", database.toString(), "--out", out.toString()))
            assertTrue(out.resolve("content.json").isRegularFile())
            LedgerStore(database).use { store ->
                assertEquals(contentSeed(snap).content, store.courseContent("synthetic-course"))
                assertEquals(listOf("outline_revised", "exam_weights_set"), store.events("synthetic-course").map { it.type })
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
