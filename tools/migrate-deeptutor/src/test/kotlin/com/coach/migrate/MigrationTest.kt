package com.coach.migrate

import org.junit.Test
import kotlin.test.*
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.*
import kotlinx.serialization.json.*

class MigrationTest {
    private val MAIN_PATH="synthetic-path"
    @Test fun `reads committed WAL data on the copy without changing the source`() {
        val root=Files.createTempDirectory("migration-wal").toRealPath()
        try {
            val original=root.resolve("original.sqlite3")
            DriverManager.getConnection("jdbc:sqlite:$original").use { db ->
                db.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("CREATE TABLE mastery_paths(path_id TEXT PRIMARY KEY,state_json TEXT)")
                    st.execute("INSERT INTO mastery_paths VALUES('p','{}')")
                }
                val before=inventory(root)
                val copied=privateDir(root.resolve("staged")).resolve("mastery.sqlite3")
                copyFile(original,copied)
                copyFile(root.resolve("original.sqlite3-wal"),root.resolve("staged/mastery.sqlite3-wal"))
                assertEquals("p",readDatabase(copied).getValue("mastery_paths").single().str("path_id"))
                val after=inventory(root).lines().filterNot { it.startsWith("staged") || it.startsWith("\t") }
                assertEquals(before.lines().filterNot { it.startsWith("\t") },after)
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `revisions preserve identity and change content hash`() {
        val a=buildJsonObject { put("_source_pk","same"); put("answer","a") }
        val b=buildJsonObject { put("_source_pk","same"); put("answer","b") }
        val first=legacy("mastery.sqlite3","mastery_interactions",a)
        val revision=legacy("mastery.sqlite3","mastery_interactions",b)
        assertEquals(first["id"],revision["id"])
        assertNotEquals(first["content_hash"],revision["content_hash"])
        assertNotEquals(first["id"],legacy("chat_history.db","mastery_interactions",a)["id"])
    }
    @Test fun `ambiguous questions remain unresolved and explicit modules narrow candidates`() {
        val kps=listOf("a","b").map { id -> buildJsonObject { put("id",id);put("name","【W1·Ch1–2】Synthetic");put("module_id",id) } }
        val q=buildJsonObject { put("id","q");put("tags",JsonArray(listOf(JsonPrimitive("Ch1")))) }
        assertEquals("需模型/人工确认",matchQuestion(q,kps).str("status"))
        assertEquals("deterministic",matchQuestion(JsonObject(q+mapOf("module_id" to JsonPrimitive("b"))),kps).str("status"))
        assertEquals(setOf(1,2),chapters("【W1·Ch1–2】"))
    }
    @Test fun `restricted directories and symlinks are excluded`() {
        val root=Files.createTempDirectory("migration-files")
        try {
            root.resolve("ok.md").writeText("synthetic")
            root.resolve("答案").createDirectory().resolve("private.md").writeText("synthetic")
            root.resolve("sealed").createDirectory().resolve("private.md").writeText("synthetic")
            root.resolve(".env").writeText("synthetic")
            Files.createSymbolicLink(root.resolve("link.md"),root.resolve("ok.md"))
            assertEquals(listOf("ok.md"),safeFiles(root).map { it.name })
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `idle guard checks log timestamp and recent append`() {
        val root=Files.createTempDirectory("migration-idle")
        try {
            val log=root.resolve("bridge.log")
            val now=Instant.parse("2026-10-29T21:00:00Z")
            log.writeText("2026-10-29T20:40:00+00:00 synthetic\n")
            Files.setLastModifiedTime(log,java.nio.file.attribute.FileTime.from(now.minusSeconds(1200)))
            idle(log,now)
            Files.setLastModifiedTime(log,java.nio.file.attribute.FileTime.from(now.minusSeconds(1)))
            assertFailsWith<IllegalArgumentException> { idle(log,now) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `canonical output ignores object insertion order`() {
        val a=buildJsonObject { put("z",1);put("a",buildJsonObject { put("y",2);put("b",3) }) }
        val b=buildJsonObject { put("a",buildJsonObject { put("b",3);put("y",2) });put("z",1) }
        assertEquals(canonical(a),canonical(b))
    }
    @Test fun `full dry run preserves legacy history and withholds unpassed reviews`() {
        val root=Files.createTempDirectory("migration-e2e").toRealPath()
        try {
            val source=root.resolve("source").createDirectory()
            fun write(name:String,value:String) { val f=source.resolve(name);f.parent.createDirectories();f.writeText(value) }
            val state=buildJsonObject {
                put("modules",JsonArray(listOf(buildJsonObject {
                    put("id","m");put("name","Chapter 1")
                    put("knowledge_points",JsonArray(listOf("passed","unpassed").map { id -> buildJsonObject {
                        put("id",id);put("name","【W1·Ch1】Synthetic 【Essay】");put("type","memory");put("module_id","m")
                    } }))
                })))
                put("mastery_levels",buildJsonObject { put("passed",1.0) })
                put("repetition_states",buildJsonObject {
                    for(id in listOf("passed","unpassed")) put(id,buildJsonObject { put("next_review_at",200);put("last_review_at",100) })
                })
            }
            write("data/user/workspace/courses/courses.json","{\"courses\":[]}")
            write("data/learning_journal/journal.json","{\"records\":[]}")
            write("data/digests/questions/ECNM10119-期中练习题.json","[{\"id\":\"q\",\"question_type\":\"single_choice\",\"tags\":[\"Ch1\"]}]")
            write("tools/patch_backups/gate_types_before.json","{\"passed\":\"concept\"}")
            write("data/user/workspace/notebook/synthetic.json","{\"records\":[{\"id\":\"n\",\"title\":\"Ch1\",\"output\":\"synthetic\"}]}")
            write("library/ECNM10119-健康经济学/教材/synthetic.md","<!-- PDF 第 1 页 · 书第 1 页 --> synthetic")
            write("library/ECNM10119-健康经济学/资料/synthetic.md","synthetic")
            write("library/ECNM10119-健康经济学/答案/private.md","excluded")
            write("data/user/runtime/bridge.log","2020-01-01T00:00:00+00:00 synthetic\n")
            Files.setLastModifiedTime(source.resolve("data/user/runtime/bridge.log"),java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
            val mastery=source.resolve("data/user/workspace/learning/mastery/mastery.sqlite3")
            mastery.parent.createDirectories()
            DriverManager.getConnection("jdbc:sqlite:$mastery").use { db -> db.createStatement().use { st ->
                st.execute("CREATE TABLE mastery_paths(path_id TEXT PRIMARY KEY,state_json TEXT,owner_session_id TEXT)")
                db.prepareStatement("INSERT INTO mastery_paths VALUES (?,?,'session')").use { ps -> ps.setString(1,MAIN_PATH);ps.setString(2,state.toString());ps.executeUpdate() }
                st.execute("CREATE TABLE mastery_interactions(interaction_id TEXT PRIMARY KEY,path_id TEXT,user_answer TEXT,question_json TEXT)")
                st.execute("INSERT INTO mastery_interactions VALUES('i','$MAIN_PATH','synthetic','{\"question_id\":\"q\"}')")
                st.execute("INSERT INTO mastery_interactions VALUES('group','$MAIN_PATH','synthetic','{\"question_id\":\"group\",\"question_type\":\"group\",\"questions\":[{\"question_id\":\"i\"}]}')")
                st.execute("CREATE TABLE mastery_learning_evidence(path_id TEXT,ordinal INTEGER,evidence_json TEXT,PRIMARY KEY(path_id,ordinal))")
                st.execute("INSERT INTO mastery_learning_evidence VALUES('$MAIN_PATH',0,'{\"question_id\":\"q\"}')")
                st.execute("CREATE TABLE mastery_events(id INTEGER PRIMARY KEY,path_id TEXT)")
            } }
            val chat=source.resolve("data/user/chat_history.db")
            DriverManager.getConnection("jdbc:sqlite:$chat").use { db -> db.createStatement().use { st ->
                st.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)")
                st.execute("CREATE TABLE assessment_attempts(id TEXT PRIMARY KEY)")
                st.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,session_id TEXT,role TEXT,content TEXT,metadata_json TEXT,events_json TEXT)")
                st.execute("INSERT INTO messages VALUES('message','session','assistant','Visible body','hidden-model-turn','hidden-tool-event')")
            } }
            val weights=root.resolve("weights.md");weights.writeText("| 10 | synthetic | Ch1 | pending |")
            val before=inventory(source.resolve("data"))
            val snap=snapshot(source,root.resolve("staging"),weights)
            val a=mapSnapshot(snap);val b=mapSnapshot(snap)
            assertEquals(canonical(a.mapping),canonical(b.mapping));assertEquals(a.report,b.report)
            assertEquals(before,inventory(source.resolve("data")))
            val attempt=a.mapping.array("legacy_attempts").single().jsonObject
            assertEquals(JsonPrimitive(false),attempt["window_eligible"])
            val group=a.mapping.array("legacy_attempt_groups").single().jsonObject
            assertEquals(listOf(attempt.getValue("id")),group.array("child_ids"))
            val unknownType=a.mapping.array("knowledge_points").map { it.jsonObject }.single { it.str("id")=="unpassed" }
            assertEquals(JsonNull,unknownType["type"])
            assertEquals("memory",unknownType.str("current_source_type"))
            val schedules=a.mapping.array("legacy_schedule").map { it.jsonObject }.associateBy { it.str("kp_id") }
            assertEquals(JsonPrimitive(false),schedules.getValue("unpassed")["queue_eligible"])
            assertEquals(JsonPrimitive(true),schedules.getValue("passed")["queue_eligible"])
            assertEquals("concept",a.mapping.array("knowledge_points").first().jsonObject.str("type"))
            assertFalse(canonical(a.mapping).contains("private.md"))
            val message=a.mapping.obj("chat_archive").array("messages").single().jsonObject.obj("source_record")
            assertEquals("Visible body",message.str("content"))
            assertFalse(canonical(a.mapping).contains("hidden-model-turn"));assertFalse(canonical(a.mapping).contains("hidden-tool-event"))
            assertFalse(safeFiles(snap).any { it.name.endsWith("-shm") })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `page overlap preserves document identity`() {
        fun anchor(doc:String)=buildJsonObject { put("document_key",doc);put("page_index",2) }
        val kps=listOf("one","two").map { doc -> buildJsonObject {
            put("id",doc);put("name","Ch1 synthetic");put("anchors",anchor(doc))
        } }
        val q=buildJsonObject { put("id","q");put("tags",JsonArray(listOf(JsonPrimitive("Ch1"))));put("source_evidence",anchor("two")) }
        assertEquals(listOf(JsonPrimitive("two")),matchQuestion(q,kps).array("kp_ids"))
        val other=JsonObject(q+mapOf("source_evidence" to anchor("three")))
        assertEquals("需模型/人工确认",matchQuestion(other,kps).str("status"))
    }

}

class ChoiceMappingTest {
    @Test fun `legacy keys become option identities independently of letter ordering`() {
        fun source(reverse: Boolean) = buildJsonObject {
            put("question_type","single_choice"); put("question","Synthetic import question")
            put("correct_answer",if(reverse) "D" else "A")
            put("options", buildJsonObject {
                val texts=listOf("right","wrong one","wrong two","wrong three").let { if(reverse) it.reversed() else it }
                listOf("A","B","C","D").zip(texts).forEach { (k,v) -> put(k,v) }
            })
        }
        val first=mapChoiceQuestion(source(false));val second=mapChoiceQuestion(source(true))
        assertEquals(first["correctOptionId"],second["correctOptionId"])
        assertEquals(first["fingerprint"],second["fingerprint"])
        assertEquals("UNREVIEWED",first.str("keyState"))
        assertFalse(first.containsKey("correct_answer"));assertFalse(first.containsKey("key"))
        assertEquals(4,first.array("options").size)
    }
}

class FiveOptionMigrationTest {
    @Test fun `five-option source preserves the fifth answer and never discards an option`() {
        val q=buildJsonObject {
            put("question_type","single_choice");put("question","Synthetic five-option source question")
            put("correct_answer","E")
            put("options",buildJsonObject { for(i in 0..4) put(('A'+i).toString(),"Synthetic option $i") })
        }
        val mapped=mapChoiceQuestion(q)
        assertEquals(5,mapped.array("options").size)
        assertEquals("Synthetic option 4",mapped.array("options").single { it.jsonObject.str("optionId")==mapped.str("correctOptionId") }.jsonObject.str("text"))
        assertFalse(mapped.containsKey("importIssue"))
    }
}
