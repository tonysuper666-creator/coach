package com.coach.desktop.books

import com.coach.protocol.learning.LearningTable
import com.coach.protocol.learning.learningTables

import com.coach.client.CourseHttp
import com.coach.core.LedgerActor
import com.coach.desktop.LearningProfiles
import com.coach.desktop.books.exam.LocalExamStore
import com.coach.server.*
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class LearningMigrationTest {
    private val sample = "A synthetic chapter for the learning synchronization test."
    private val book = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sample.encodeToByteArray()))

    private fun importSample(dir: Path) {
        val source = Files.writeString(dir.resolve("Synthetic.txt"),sample)
        assertEquals(book,TextbookLibrary(dir).importBook(source).id)
    }

    private fun prepare(dir: Path, seed: Boolean) {
        val library = TextbookLibrary(dir)
        LocalExamStore(library)
        if (seed) {
            importSample(dir)
            library.withCourseDatabase { db ->
                db.createStatement().use { sql ->
                    sql.execute("UPDATE books SET touched=1,reading_page=4 WHERE id='$book'")
                    sql.execute("INSERT INTO book_read_spots VALUES('$book',4,12)")
                    sql.execute("INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id) VALUES('$book','n1','v1',4,1,5,'test','my note','hash',1,'m1')")
                    sql.execute("INSERT INTO book_highlights(book,page,start,end,quote) VALUES('$book',4,1,5,'test')")
                    sql.execute("INSERT INTO book_ask_turns(book,thread_id,selection_id,selection_page,selection_start,selection_end,selection_text,label,answer_text,created_at) VALUES('$book','t','s',4,1,5,'test','q','reply',1)")
                    sql.execute("INSERT INTO answers(book,lesson,mode,text,revealed) VALUES('$book','L1','CHOICE','B',1)")
                    sql.execute("INSERT INTO book_reviews(book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text) VALUES('$book','L1','CHOICE','Synthetic','L1','Q','R','S','[]','B')")
                    sql.execute("INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,guessed) VALUES('$book','L1','CHOICE','ANSWER','B',1,0)")
                    sql.execute("INSERT INTO book_fsrs_cards(book,lesson,mode,due_day,updated_at) VALUES('$book','L1','CHOICE',17,1)")
                    sql.execute("INSERT INTO collections(id,kind,title,created_at,updated_at) VALUES('c','COURSE','C',1,1)")
                    sql.execute("INSERT INTO course_topics(topic_id,collection_id,week_label,ord,title,updated_at) VALUES('t','c','1',1,'Topic',1)")
                    sql.execute("INSERT INTO concept_mastery_ledger(concept_key,collection_id,book_id,toc_node_id,canonical_name,updated_at) VALUES('concept','c','$book','toc','Concept',1)")
                    sql.execute(StudyModeStore.PREFERENCES_DDL)
                    sql.execute("INSERT INTO book_study_preferences(book_id,study_mode,updated_at) VALUES('$book','READ',1)")
                    sql.execute("INSERT INTO book_learning_paths VALUES('$book','READ')")
                    sql.execute("INSERT INTO book_ladders VALUES('$book','{}')")
                    sql.execute("INSERT INTO book_bookmarks VALUES('$book',4,'mark',1)")
                    sql.execute("INSERT INTO book_chapter_progress(book_id,toc_node_id,pages_total,updated_at) VALUES('$book','toc',4,1)")
                    sql.execute("INSERT INTO book_ask_drafts(book,page,question,updated_at) VALUES('$book',4,'Question',1)")
                    sql.execute("INSERT INTO concept_entities(concept_id,collection_id,canonical_name) VALUES('e','c','Concept')")
                    for (mention in listOf("m1","m2")) {
                        sql.execute("INSERT INTO concept_mentions(mention_id,concept_id,book_id,page,quote_anchor,mention_role,claim_summary) VALUES('$mention','e','$book',4,'q','PRIMARY_DEF','summary')")
                    }
                    sql.execute("INSERT INTO concept_links(link_id,collection_id,concept_id,from_mention_id,to_mention_id,relation_type,diff_summary,confidence) VALUES('link','c','e','m1','m2','PREREQUISITE','diff',0.5)")
                    sql.execute("INSERT INTO learner_evidence_events(event_id,concept_key,book_id,page,signal_type,created_at) VALUES('ev','concept','$book',4,'PAGE_DWELL',1)")
                    sql.execute("INSERT INTO learning_gain_cohorts(cohort_id,book_id,chapter_id,concept_key) VALUES('co','$book','toc','concept')")
                    sql.execute("INSERT INTO margin_whisper_audit(whisper_id,book_id,toc_node_id,page,rule,collapsed_text,expanded_text,pdf_page_anchor,trigger_evidence_json,created_at) VALUES('w','$book','toc',4,'rule','a','b',4,'[]',1)")
                    sql.execute("INSERT INTO collection_materials(collection_id,material_id,added_at) VALUES('c','$book',1)")
                    sql.execute("INSERT INTO course_topic_sources(topic_id,book_id,start_page,end_page,source_role) VALUES('t','$book',1,4,'PRIMARY')")
                    // Simulate the previous schema before the user has ever enabled learning sync.
                    sql.execute("ALTER TABLE book_ask_turns DROP COLUMN write_id")
                    sql.execute("ALTER TABLE book_ask_turns DROP COLUMN answer_status")
                    sql.execute("PRAGMA user_version=8")
                }
            }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("exams.sqlite")}").use { db ->
                db.createStatement().use { sql ->
                    sql.execute("INSERT INTO local_exam_papers VALUES('$book','p','{}','h')")
                    for (owner in listOf("learner","agent-thread")) sql.execute("""INSERT INTO local_exam_attempts
                        (id,owner,book,paper_id,paper_json,paper_hash,started_at) VALUES('$owner','$owner','$book','p','{}','h',7)""")
                }
            }
        }
        LearningMigrationBackup.prepare(dir)
        LearningJournal.installProfile(TextbookLibrary(dir))
    }

    private fun rows(dir: Path, t: LearningTable): List<String> {
        val file = dir.resolve(if (t.exam) "exams.sqlite" else "library.sqlite")
        return DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { sql ->
                val filter = if (t.kind == "exam_attempt") " WHERE owner='learner'" else ""
                val cols = t.columns.filterNot { t.event && it == "id" }
                sql.executeQuery("SELECT ${cols.joinToString(",")} FROM ${t.name}$filter").use { r ->
                    buildList { while (r.next()) add(cols.joinToString("|") { r.getString(it) ?: "<null>" }) }.sorted()
                }
            }
        }
    }

    @Test fun fixedKeysAndRollback() {
        val fields = buildJsonObject { put("book","a".repeat(64)); put("lesson","L1"); put("mode","CHOICE") }
        assertEquals("e3567cb8-d31b-579c-85f6-495d6f9b3ac1",learningTables.single { it.kind == "answer" }.legacyId(fields))
        val dir = Files.createTempDirectory("learning-rollback")
        try {
            prepare(dir,false)
            val library = TextbookLibrary(dir)
            library.withCourseDatabase { db -> db.createStatement().use { sql ->
                sql.execute("BEGIN IMMEDIATE")
                sql.execute("INSERT INTO book_read_spots VALUES('$book',4,12)")
                assertTrue(sql.executeQuery("SELECT 1 FROM learning_sync_outbox WHERE kind='read_spot'").use { it.next() })
                sql.execute("ROLLBACK")
                assertFalse(sql.executeQuery("SELECT 1 FROM learning_sync_outbox WHERE kind='read_spot'").use { it.next() })
            } }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun migrateRestoreAndIsolate() = runBlocking {
        val root = Files.createTempDirectory("learning-pullback")
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("service"))
        val actor = LedgerActor(job,store)
        val tokens = listOf(UUID_TOKEN_X,UUID_TOKEN_Y)
        val issuer = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            createContext("/api/coach/whoami") { ex ->
                val i = tokens.indexOf(ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer "))
                val body = """{"sub":"synthetic-$i","username":"user$i","display_name":"User"}""".toByteArray()
                ex.sendResponseHeaders(if (i >= 0) 200 else 401,body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val friends = FriendAccess(root.resolve("friends"),root.resolve("state"),root.resolve("config"),
            SableSessions(actor,root.resolve("vault"),URI("http://127.0.0.1:${issuer.address.port}")))
        val books = UnifiedBookPipeline(root.resolve("books"),enabled=true,autoProcess=false)
        val server = job.embeddedServer(Netty,host="127.0.0.1",port=0) {
            ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=8),friends=friends,publicOnly=true,unifiedBooks=books)
            unifiedBookRoutes(actor,books,friends,learningSync=true)
        }.apply { start(false) }
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            val sessions = mutableMapOf<Pair<Path,String>,JsonObject>()
            fun lookup(dir: Path, token: String, kind: String, id: String): HttpResponse<String> {
                val credential = sessions.getValue(dir to token)
                val request = HttpRequest.newBuilder(URI("$base/learning-sync/record?kind=$kind&id=$id"))
                    .header("Authorization","Bearer " + credential.getValue("token").jsonPrimitive.content)
                    .header("X-Coach-Device",credential.getValue("deviceId").jsonPrimitive.content)
                    .GET().build()
                return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString())
            }
            suspend fun sync(dir: Path, token: String) {
                val c = sessions.getOrPut(dir to token) {
                    val response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                        HttpRequest.newBuilder(URI("$base/v1/public/sable")).timeout(java.time.Duration.ofSeconds(10))
                            .header("Authorization","Bearer $token").POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString())
                    assertEquals(200,response.statusCode())
                    Json.parseToJsonElement(response.body()).jsonObject
                }
                val http = CourseHttp(base,c.getValue("deviceId").jsonPrimitive.content,c.getValue("token").jsonPrimitive.content,"library")
                try {
                    val remote = object : LearningRemote {
                        override suspend fun changes(epoch: String?, after: Long) = http.learningChanges(epoch,after)
                        override suspend fun push(change: JsonObject) = http.pushLearningChange(change)
                    }
                    val profile = assertNotNull(http.learningProfile())
                    val scope = profile.getValue("scope").jsonPrimitive.content
                    val epoch = profile.getValue("epoch").jsonPrimitive.content
                    for ((file,isExam) in listOf("library.sqlite" to false,"exams.sqlite" to true)) {
                        val path = dir.resolve(file)
                        LearningTransfer(path,isExam).sync(scope,remote,epoch) { true }
                        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                            db.createStatement().use { q ->
                                q.executeQuery("SELECT kind,seq FROM learning_sync_outbox ORDER BY seq LIMIT 1").use { r ->
                                    if (r.next()) fail("Unacknowledged ${r.getString(1)} at ${r.getLong(2)} in $file")
                                }
                            }
                        }
                    }
                } finally { http.close() }
            }
            val a = root.resolve("A"); val b = root.resolve("B"); val y = root.resolve("Y")
            prepare(a,true); prepare(b,false); prepare(y,false)
            val copy = Files.createDirectory(root.resolve("copy"))
            for (file in listOf("library.sqlite","exams.sqlite")) {
                Files.copy(a.resolve("before-learning-sync").resolve(file),copy.resolve(file))
            }
            prepare(copy,false)
            sync(a,tokens[0]); sync(copy,tokens[0]); sync(b,tokens[0]); sync(y,tokens[1])
            assertTrue(TextbookLibrary(b).catalog().none { it.id == book })
            importSample(b)
            sync(b,tokens[0])
            for (t in learningTables) {
                assertTrue(rows(a,t).isNotEmpty(),t.name)
                assertEquals(rows(a,t),rows(b,t),t.name)
                assertTrue(rows(y,t).isEmpty(),t.name)
            }
            assertEquals(1,rows(b,learningTables.single { it.kind=="exam_attempt" }).size)
            println("Restored rows: " + learningTables.joinToString { "${it.kind}=${rows(b,it).size}" })
            sync(a,tokens[0])
            for ((dir,note) in listOf(a to "first offline edit",b to "second offline edit")) {
                val library = TextbookLibrary(dir)
                val prior = library.allNotes(book).single()
                library.saveNote(book,prior.copy(note=note),prior.revision,note)
            }
            sync(b,tokens[0]); sync(a,tokens[0]); sync(b,tokens[0]); sync(a,tokens[0])
            for (dir in listOf(a,b)) assertEquals(setOf("first offline edit","second offline edit"),
                TextbookLibrary(dir).allNotes(book).map { it.note }.toSet())

            BookBookmarkStore(a).remove(book,4)
            sync(a,tokens[0]); sync(b,tokens[0])
            assertTrue(BookBookmarkStore(b).list(book).isEmpty())
            val markId = learningTables.single { it.kind == "bookmark" }.legacyId(buildJsonObject { put("book",book); put("page",4) })
            val tombstone = Json.parseToJsonElement(lookup(a,tokens[0],"bookmark",markId).body()).jsonObject
            assertEquals(JsonNull,tombstone["body"])
            assertEquals(1,tombstone.getValue("round").jsonPrimitive.int)
            assertEquals(404,lookup(y,tokens[1],"bookmark",markId).statusCode())
            BookBookmarkStore(b).add(book,4,"again")
            sync(b,tokens[0]); sync(a,tokens[0])
            assertEquals("again",BookBookmarkStore(a).list(book).single().note)
            assertEquals(2,Json.parseToJsonElement(lookup(a,tokens[0],"bookmark",markId).body()).jsonObject.getValue("round").jsonPrimitive.int)

            // The same natural key is a distinct record under a second authenticated account.
            BookBookmarkStore(y).add(book,4,"account Y")
            sync(y,tokens[1]); sync(a,tokens[0])
            assertEquals("account Y",BookBookmarkStore(y).list(book).single().note)
            assertEquals("again",BookBookmarkStore(a).list(book).single().note)

            val cred = sessions.getValue(a to tokens[0])
            val removal = HttpRequest.newBuilder(URI("$base/v1/account"))
                .header("Authorization","Bearer " + cred.getValue("token").jsonPrimitive.content)
                .header("X-Coach-Device",cred.getValue("deviceId").jsonPrimitive.content).DELETE().build()
            assertEquals(200,HttpClient.newHttpClient().send(removal,HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(401,lookup(b,tokens[0],"bookmark",markId).statusCode())
            val registeredAgain = root.resolve("new-registration")
            prepare(registeredAgain,false)
            sync(registeredAgain,tokens[0])
            assertEquals(404,lookup(registeredAgain,tokens[0],"bookmark",markId).statusCode())
            assertEquals(200,lookup(y,tokens[1],"bookmark",markId).statusCode())
            assertEquals("again",BookBookmarkStore(a).list(book).single().note)
        } finally {
            server.stop(0,1000); issuer.stop(0); actor.close(); job.cancel(); root.toFile().deleteRecursively()
        }
    }

    @Test fun conflictingLegacyBindingsStayLocal() {
        val root = Files.createTempDirectory("learning-profiles")
        try {
            val legacy = root.resolve("legacy")
            prepare(legacy,true)
            val x = "sable-" + "1".repeat(64)
            val y = "sable-" + "2".repeat(64)
            val sx = "local-" + "a".repeat(64)
            val sy = "local-" + "b".repeat(64)
            val profiles = LearningProfiles(legacy)
            TextbookLibrary(legacy).saveImportAccountKey(book,y)
            val px = profiles.select(sx,x,false)
            assertNotEquals(legacy,px.directory)
            assertNotEquals(px.directory,profiles.select(sy,y,false).directory)
            assertEquals(px,profiles.select(sx,null,true))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun verifiedOwnerKeepsBothLegacyDatabasesAcrossReopen() {
        val root = Files.createTempDirectory("learning-owner")
        val x = "sable-" + "a".repeat(64)
        val session = "local-" + "b".repeat(64)
        try {
            for (hasBookKey in listOf(false,true)) {
                val dir = root.resolve("legacy-$hasBookKey")
                prepare(dir,true)
                if (hasBookKey) TextbookLibrary(dir).saveImportAccountKey(book,x)
                val firstEpoch = java.util.UUID.randomUUID().toString()
                val secondEpoch = java.util.UUID.randomUUID().toString()
                val selected = LearningProfiles(dir).select(session,x,false,firstEpoch)
                assertEquals(dir,selected.directory)
                assertEquals(selected,LearningProfiles(dir).select(session,null,true))
                assertTrue(Files.isRegularFile(selected.directory.resolve("exams.sqlite")))
                val newGeneration = LearningProfiles(dir).select(session,x,false,secondEpoch)
                assertNotEquals(selected.directory,newGeneration.directory)
                assertFalse(Files.exists(newGeneration.directory.resolve("exams.sqlite")))
                assertNull(LearningProfiles(dir).select(session,x,false,firstEpoch).scope)
                assertEquals(newGeneration,LearningProfiles(dir).select(session,null,true))
                assertTrue(Files.isRegularFile(selected.directory.resolve("exams.sqlite")))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    companion object {
        private const val UUID_TOKEN_X = "synthetic-learning-account-token-x"
        private const val UUID_TOKEN_Y = "synthetic-learning-account-token-y"
    }
}
