package com.coach.desktop.books

import com.coach.protocol.learning.LearningTable
import com.coach.protocol.learning.learningTables
import com.coach.protocol.learning.draftVersion

import com.coach.client.CourseHttp
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopIdentity
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
    private class LostDraftReceipt : java.io.IOException()
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

    @Test fun installingDraftVersionsRestartsAnOlderPartialSnapshotAndPreservesQueuedEdits() {
        val dir = Files.createTempDirectory("draft-upgrade")
        try {
            val library = TextbookLibrary(dir)
            importSample(dir)
            library.withCourseDatabase { db ->
                db.createStatement().use { it.execute(StudyModeStore.PREFERENCES_DDL) }
                LearningJournal.install(db,learningTables.filterNot { it.exam })
                db.createStatement().use { q ->
                    q.executeUpdate("INSERT INTO learning_sync_meta VALUES ('stage_token','old'),('stage_after','1')")
                    q.executeUpdate("INSERT INTO learning_sync_stage VALUES(1,'{}')")
                }
            }
            fun queued() = library.withCourseDatabase { db -> db.createStatement().use {
                it.executeQuery("SELECT COUNT(*) FROM learning_sync_outbox").use { r -> r.next(); r.getInt(1) }
            } }
            val pending = queued()
            assertTrue(pending > 0)
            LearningJournal.installLibrary(library)
            assertEquals(pending,queued())
            library.withCourseDatabase { db -> db.createStatement().use { q ->
                assertTrue(q.executeQuery("SELECT 1 FROM learning_sync_meta WHERE key='restore_required' AND value='1'").use { it.next() })
                assertFalse(q.executeQuery("SELECT 1 FROM learning_sync_meta WHERE key LIKE 'stage_%'").use { it.next() })
                assertFalse(q.executeQuery("SELECT 1 FROM learning_sync_stage").use { it.next() })
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
            suspend fun sync(dir: Path, token: String, pauseSnapshot: Boolean = false, loseDraftReceipt: Boolean = false) {
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
                        override suspend fun snapshot(epoch: String, token: String?, after: Long) =
                            if (pauseSnapshot && token != null) null else http.learningSnapshot(epoch,token,after)
                        override suspend fun changes(epoch: String?, after: Long, stream: String) = http.learningChanges(epoch,after,stream)
                        override suspend fun push(change: JsonObject) = http.pushLearningChange(change).also { ack ->
                            if (ack != null && loseDraftReceipt && change["kind"] == JsonPrimitive("ask_draft")) {
                                throw LostDraftReceipt()
                            }
                        }
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
            fun changePage(dir: Path, page: Int) = TextbookLibrary(dir).withCourseDatabase { db ->
                db.prepareStatement("UPDATE books SET reading_page=? WHERE id=?").use {
                    it.setInt(1,page); it.setString(2,book); assertEquals(1,it.executeUpdate())
                }
            }
            changePage(a,10)
            changePage(b,20)
            BookBookmarkStore(b).add(book,9)
            sync(a,tokens[0]); sync(b,tokens[0])
            BookBookmarkStore(a).add(book,11)
            sync(a,tokens[0]); sync(b,tokens[0])
            val progress = learningTables.single { it.kind == "book_progress" }
            assertEquals(rows(a,progress),rows(b,progress))
            for (dir in listOf(a,b)) assertEquals(setOf(4,9,11),BookBookmarkStore(dir).list(book).map { it.page }.toSet())
            for (dir in listOf(a,b)) {
                val library = TextbookLibrary(dir)
                library.saveReviewRating(library.allReviews(book).single(),com.coach.domain.fsrs.FsrsRating.GOOD,
                    PracticeAnswer("C",revealed=true,understood=true))
            }
            sync(b,tokens[0]); sync(a,tokens[0]); sync(b,tokens[0]); sync(a,tokens[0])
            for (kind in listOf("card","mistake","mastery","evidence","review_event")) {
                val table = learningTables.single { it.kind == kind }
                assertEquals(rows(a,table),rows(b,table),kind)
            }
            TextbookLibrary(b).withCourseDatabase { db -> db.createStatement().use { q ->
                q.executeQuery("SELECT reps,last_review_day,stability,difficulty FROM book_fsrs_cards").use { r ->
                    assertTrue(r.next())
                    val day = r.getLong(2)
                    val expected = (1..2).fold(com.coach.domain.fsrs.Fsrs5.newCard(17)) { state,_ ->
                        com.coach.domain.fsrs.Fsrs5.review(state,com.coach.domain.fsrs.FsrsRating.GOOD,day)
                    }
                    assertEquals(2,r.getInt(1))
                    assertEquals(expected.stability!!,r.getDouble(3),1e-10)
                    assertEquals(expected.difficulty!!,r.getDouble(4),1e-10)
                }
                q.executeQuery("SELECT COUNT(*) FROM learner_evidence_events WHERE signal_type='POST_QUIZ_T1'").use {
                    assertTrue(it.next()); assertEquals(2,it.getInt(1))
                }
                q.executeQuery("SELECT p_mastery FROM concept_mastery_ledger WHERE has_active_test=1").use {
                    assertTrue(it.next())
                    val expected = 1 / (1 + (0.85 / 0.15) * kotlin.math.exp(-2 * 1.15))
                    assertEquals(expected,it.getDouble(1),1e-10)
                }
            } }
            for ((dir,note) in listOf(a to "first offline edit",b to "second offline edit")) {
                val library = TextbookLibrary(dir)
                val prior = library.allNotes(book).single()
                library.saveNote(book,prior.copy(note=note),prior.revision,note)
            }
            sync(b,tokens[0]); sync(a,tokens[0]); sync(b,tokens[0]); sync(a,tokens[0])
            for (dir in listOf(a,b)) assertEquals(setOf("first offline edit","second offline edit"),
                TextbookLibrary(dir).allNotes(book).map { it.note }.toSet())

            val texts = listOf("first offline question\nwith a second line", "second offline question")
            val initialDraft = assertNotNull(TextbookLibrary(a).askDraft(book))
            assertEquals(initialDraft,TextbookLibrary(b).askDraft(book))
            for ((dir,text) in listOf(a,b).zip(texts)) {
                TextbookLibrary(dir).saveAskDraft(book,initialDraft.copy(text=text))
            }
            sync(a,tokens[0])
            assertFailsWith<LostDraftReceipt> { sync(b,tokens[0],loseDraftReceipt=true) }
            sync(b,tokens[0]); sync(a,tokens[0]); sync(b,tokens[0])
            val versions = rows(a,draftVersion)
            assertEquals(1,versions.size)
            assertEquals(versions,rows(b,draftVersion))
            sync(y,tokens[1])
            assertTrue(rows(y,draftVersion).isEmpty())
            val active = TextbookLibrary(a).askDraft(book)
            assertEquals(active,TextbookLibrary(b).askDraft(book))
            for (dir in listOf(a,b)) {
                val notes = TextbookLibrary(dir).loadBookNotes(book,"Synthetic")
                val recovered = notes.questions.single { it.lines.first().first == "找回的提问草稿" }.lines.single().second
                assertEquals(texts.toSet(),setOf(assertNotNull(active).text,recovered))
                assertContains(renderBookNotesMarkdown(notes),recovered)
            }
            println("Draft retry after committed push: A=1 active + 1 recovered; B=1 active + 1 recovered")

            BookBookmarkStore(a).remove(book,4)
            sync(a,tokens[0]); sync(b,tokens[0])
            assertTrue(BookBookmarkStore(b).list(book).none { it.page == 4 })
            val markId = learningTables.single { it.kind == "bookmark" }.legacyId(buildJsonObject { put("book",book); put("page",4) })
            val tombstone = Json.parseToJsonElement(lookup(a,tokens[0],"bookmark",markId).body()).jsonObject
            assertEquals(JsonNull,tombstone["body"])
            assertEquals(1,tombstone.getValue("round").jsonPrimitive.int)
            assertEquals(404,lookup(y,tokens[1],"bookmark",markId).statusCode())
            BookBookmarkStore(a).add(book,4,"A again")
            BookBookmarkStore(b).add(book,4,"B again")
            sync(b,tokens[0]); sync(a,tokens[0]); sync(b,tokens[0])
            val restoredMark = BookBookmarkStore(b).list(book).single { it.page == 4 }.note
            assertTrue(restoredMark in setOf("A again","B again"))
            assertEquals(restoredMark,BookBookmarkStore(a).list(book).single { it.page == 4 }.note)
            assertEquals(2,Json.parseToJsonElement(lookup(a,tokens[0],"bookmark",markId).body()).jsonObject.getValue("round").jsonPrimitive.int)

            // The same natural key is a distinct record under a second authenticated account.
            BookBookmarkStore(y).add(book,4,"account Y")
            sync(y,tokens[1]); sync(a,tokens[0])
            assertEquals("account Y",BookBookmarkStore(y).list(book).single().note)
            assertEquals(restoredMark,BookBookmarkStore(a).list(book).single { it.page == 4 }.note)

            val replacement = root.resolve("interrupted-snapshot")
            prepare(replacement,false)
            for (page in 100..171) BookBookmarkStore(a).add(book,page)
            sync(a,tokens[0])
            val accountDir = root.resolve("books/accounts").resolve("sable-" + com.coach.store.sableFriendName("synthetic-0").substringAfterLast(':'))
            val backup = accountDir.resolve("learning-backups/latest.sqlite")
            val oldCopy = Files.copy(backup,root.resolve("old-checkpoint.sqlite"))
            sync(replacement,tokens[0],pauseSnapshot=true)
            assertTrue(BookBookmarkStore(replacement).list(book).isEmpty())
            TextbookLibrary(replacement).withCourseDatabase { db -> db.createStatement().use { q ->
                q.executeQuery("SELECT COUNT(*) FROM learning_sync_stage").use { r -> assertTrue(r.next()); assertTrue(r.getInt(1)>0) }
            } }
            BookBookmarkStore(a).remove(book,100)
            BookBookmarkStore(a).add(book,175)
            sync(a,tokens[0])
            sync(replacement,tokens[0])
            assertEquals(BookBookmarkStore(a).list(book),BookBookmarkStore(replacement).list(book))
            assertEquals(versions,rows(replacement,draftVersion))
            // The registry survives losing the per-account DB. A valid but older SQLite file must not roll it back.
            val newest = Files.copy(backup,root.resolve("new-checkpoint.sqlite"))
            val live = accountDir.resolve("learning.sqlite")
            Files.move(live,root.resolve("simulated-disk-loss.sqlite"))
            Files.copy(oldCopy,backup,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(503,lookup(a,tokens[0],"bookmark",markId).statusCode())
            assertFalse(Files.exists(live))
            Files.copy(newest,backup,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(200,lookup(a,tokens[0],"bookmark",markId).statusCode())
            assertTrue(Files.isRegularFile(live))
            BookBookmarkStore(a).add(book,177)
            sync(a,tokens[0]); sync(b,tokens[0])
            assertTrue(BookBookmarkStore(b).list(book).any { it.page == 177 })
            TextbookLibrary(a).withCourseDatabase { db -> db.createStatement().use { q ->
                q.executeQuery("SELECT count(DISTINCT value) FROM learning_sync_meta WHERE key IN ('epoch','transport')").use { r ->
                    assertTrue(r.next()); assertEquals(2,r.getInt(1))
                }
            } }
            val cred = sessions.getValue(a to tokens[0])
            val removal = HttpRequest.newBuilder(URI("$base/v1/account"))
                .header("Authorization","Bearer " + cred.getValue("token").jsonPrimitive.content)
                .header("X-Coach-Device",cred.getValue("deviceId").jsonPrimitive.content).DELETE().build()
            assertEquals(200,HttpClient.newHttpClient().send(removal,HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(401,lookup(b,tokens[0],"bookmark",markId).statusCode())
            assertFalse(Files.exists(backup))
            val registeredAgain = root.resolve("new-registration")
            prepare(registeredAgain,false)
            sync(registeredAgain,tokens[0])
            assertEquals(404,lookup(registeredAgain,tokens[0],"bookmark",markId).statusCode())
            Files.move(live,root.resolve("new-generation.sqlite"))
            Files.copy(newest,live)
            assertEquals(503,lookup(registeredAgain,tokens[0],"bookmark",markId).statusCode())
            Files.move(root.resolve("new-generation.sqlite"),live,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(200,lookup(y,tokens[1],"bookmark",markId).statusCode())
            assertEquals(restoredMark,BookBookmarkStore(a).list(book).single { it.page == 4 }.note)
        } finally {
            server.stop(0,1000); issuer.stop(0); actor.close(); job.cancel(); root.toFile().deleteRecursively()
        }
    }

    @Test fun onlyAValidProfileResponseCanBindTheLocalDirectory() = runBlocking {
        val root = Files.createTempDirectory("learning-profile-response")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val scope = "sable-" + "1".repeat(64)
        val epoch = java.util.UUID.randomUUID().toString()
        val profiles = LearningProfiles(root.resolve("library"))
        val identity = DesktopIdentity("http://127.0.0.1:${server.address.port}", PairedCredentials("test-device", "test-token", "friend"))
        val response = java.util.concurrent.atomic.AtomicReference("text/html" to "<html></html>")
        server.createContext("/learning-sync/profile") { exchange ->
            val (type, body) = response.get()
            val bytes = body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            for (reply in listOf(response.get(), "application/json" to "{", "application/json" to """{"scope":"$scope","epoch":"invalid"}""")) {
                response.set(reply)
                assertNull(profiles.open(identity, true).scope)
            }
            response.set("application/json" to """{"scope":"$scope","epoch":"$epoch"}""")
            val verified = profiles.open(identity, true)
            assertEquals(scope, verified.scope)
            assertEquals(epoch, verified.epoch)
            assertEquals(verified, profiles.open(identity, false))
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
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
                for (file in listOf("library.sqlite","exams.sqlite")) {
                    DriverManager.getConnection("jdbc:sqlite:${dir.resolve(file)}").use { db -> db.createStatement().use { q ->
                        q.executeQuery("SELECT count(*) FROM sqlite_master WHERE name GLOB 'learning_sync_*'").use { r ->
                            assertTrue(r.next()); assertEquals(0,r.getInt(1))
                        }
                        q.executeQuery("SELECT singleton FROM local_learning_retired").use { assertTrue(it.next()) }
                    } }
                }
                assertTrue(BookBookmarkStore(dir).list(book).any { it.page == 4 })
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
