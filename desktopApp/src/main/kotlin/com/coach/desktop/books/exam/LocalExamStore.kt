package com.coach.desktop.books.exam

import com.coach.desktop.books.TextbookLibrary
import com.coach.domain.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * An owned local contract, not a server or second application. It never writes library answers or the ledger.
 * Papers use the existing mock JSON shape; attempts use mockId/answers/answersSeq and hundredth-point results.
 */
internal class LocalExamStore(
    private val library: TextbookLibrary,
    private val agentRun: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val owner = agentRun?.let { agentThreadId(it, "local-exam") } ?: "learner"
    val databasePath: Path = library.directory.resolve("exams.sqlite")

    init {
        database().use { db -> db.createStatement().use { sql ->
            sql.execute("""CREATE TABLE IF NOT EXISTS local_exam_papers(
                book TEXT NOT NULL,paper_id TEXT NOT NULL,paper_json TEXT NOT NULL,paper_hash TEXT NOT NULL,
                PRIMARY KEY(book,paper_id))""")
            sql.execute("""CREATE TABLE IF NOT EXISTS local_exam_attempts(
                id TEXT PRIMARY KEY,owner TEXT NOT NULL,book TEXT NOT NULL,paper_id TEXT NOT NULL,
                paper_json TEXT NOT NULL,paper_hash TEXT NOT NULL,started_at INTEGER NOT NULL,
                answers_json TEXT NOT NULL DEFAULT '{}',answers_seq INTEGER NOT NULL DEFAULT 0 CHECK(answers_seq>=0),
                submitted_at INTEGER,result_json TEXT,
                FOREIGN KEY(book,paper_id) REFERENCES local_exam_papers(book,paper_id),
                CHECK((submitted_at IS NULL)=(result_json IS NULL)))""")
            sql.execute("""CREATE UNIQUE INDEX IF NOT EXISTS local_exam_one_active
                ON local_exam_attempts(owner,book) WHERE submitted_at IS NULL""")
        } }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000"); it.execute("PRAGMA foreign_keys=ON") }
    }

    /** The SQLite writer lock covers read/check/write too, including independent store objects/processes. */
    private fun <T> transaction(block: (Connection) -> T): T = database().use { db ->
        db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block(db)
            db.createStatement().use { it.execute("COMMIT") }
            result
        } catch (failure: Throwable) {
            db.createStatement().use { it.execute("ROLLBACK") }
            throw failure
        }
    }

    /** Same identity is idempotent; a changed paper requires a new ID, never overwrites an active snapshot. */
    fun register(paper: LocalExamPaper): JsonObject {
        val frozen = paper.freeze(library)
        return transaction { db ->
            val previous = paper(db, frozen.book, frozen.id)
            require(previous == null || previous.hash == frozen.hash) { "同一题卷身份不能覆盖，请为新版本使用新的题卷id。" }
            if (previous == null) db.prepareStatement(
                "INSERT INTO local_exam_papers(book,paper_id,paper_json,paper_hash) VALUES(?,?,?,?)"
            ).use { sql ->
                sql.setString(1, frozen.book); sql.setString(2, frozen.id)
                sql.setString(3, canonicalJson(frozen.snapshot)); sql.setString(4, frozen.hash)
                sql.executeUpdate()
            }
            summary(frozen)
        }
    }

    fun papers(book: String): List<JsonObject> = database().use { db ->
        db.prepareStatement("SELECT paper_json,paper_hash FROM local_exam_papers WHERE book=? ORDER BY paper_id").use { sql ->
            sql.setString(1, book)
            sql.executeQuery().use { rows -> buildList {
                while (rows.next()) add(summary(rows.readPaper()))
            } }
        }
    }

    private fun summary(paper: LocalExamPaper): JsonObject = json("id" to paper.id, "bookId" to paper.book,
        "title" to paper.title, "kind" to paper.kind, "origin" to paper.origin, "key_state" to paper.keyState.name,
        "items" to paper.items.size, "max" to paper.max, "snapshotSha256" to paper.hash)

    private fun paper(db: Connection, book: String, id: String): LocalExamPaper? =
        db.prepareStatement("SELECT paper_json,paper_hash FROM local_exam_papers WHERE book=? AND paper_id=?").use { sql ->
            sql.setString(1, book); sql.setString(2, id)
            sql.executeQuery().use { rows ->
                if (rows.next()) rows.readPaper() else null
            }
        }

    fun start(book: String, paperId: String): JsonObject = transaction { db ->
        val paper = paper(db, book, paperId) ?: throw IllegalArgumentException("没有这本教材可用的本地题卷。")
        val open = findActive(db, book)
        if (open != null) {
            require(open.paper.id == paperId) { "这本教材还有未交卷的尝试，请先恢复或交卷。" }
            return@transaction view(open)
        }
        val id = "local-" + UUID.randomUUID()
        db.prepareStatement("""INSERT INTO local_exam_attempts(id,owner,book,paper_id,paper_json,paper_hash,started_at)
            VALUES(?,?,?,?,?,?,?)""").use { sql ->
            sql.setString(1, id); sql.setString(2, owner); sql.setString(3, book); sql.setString(4, paperId)
            sql.setString(5, canonicalJson(paper.snapshot)); sql.setString(6, paper.hash); sql.setLong(7, clock())
            sql.executeUpdate()
        }
        view(session(db, book, id))
    }

    fun get(book: String, attempt: String): JsonObject = database().use { view(session(it, book, attempt)) }
    fun active(book: String): JsonObject? = database().use { findActive(it, book)?.let(::view) }

    /** Full answer snapshots, as with MockEngine.save; stale saves cannot clobber newer drafts. */
    fun save(book: String, attempt: String, answers: JsonObject, sequence: Long): JsonObject = transaction { db ->
        require(sequence > 0) { "作答保存必须提供递增序号。" }
        val current = session(db, book, attempt)
        check(current.submittedAt == null) { "这次尝试已经交卷，不能再改作答。" }
        val clean = current.paper.clean(answers)
        val saved = db.prepareStatement("""UPDATE local_exam_attempts SET answers_json=?,answers_seq=?
            WHERE id=? AND owner=? AND book=? AND submitted_at IS NULL AND answers_seq<?""").use { sql ->
            sql.setString(1, canonicalJson(clean)); sql.setLong(2, sequence); sql.setString(3, attempt)
            sql.setString(4, owner); sql.setString(5, book); sql.setLong(6, sequence)
            sql.executeUpdate() == 1
        }
        JsonObject(view(session(db, book, attempt)) + ("saved" to JsonPrimitive(saved)))
    }

    /** Submission, frozen results and close are atomic; a retry returns the original result verbatim. */
    fun submit(book: String, attempt: String): JsonObject = transaction { db ->
        val current = session(db, book, attempt)
        if (current.submittedAt == null) {
            val result = current.paper.grade(current.id, current.answers)
            db.prepareStatement("""UPDATE local_exam_attempts SET submitted_at=?,result_json=?
                WHERE id=? AND owner=? AND book=? AND submitted_at IS NULL""").use { sql ->
                sql.setLong(1, clock()); sql.setString(2, canonicalJson(result))
                sql.setString(3, attempt); sql.setString(4, owner); sql.setString(5, book)
                check(sql.executeUpdate() == 1)
            }
        }
        view(session(db, book, attempt))
    }

    fun history(book: String): List<JsonObject> = database().use { db ->
        db.prepareStatement("""SELECT * FROM local_exam_attempts WHERE owner=? AND book=? AND submitted_at IS NOT NULL
            ORDER BY submitted_at DESC,id""").use { sql ->
            sql.setString(1, owner); sql.setString(2, book)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(view(rows.session())) } }
        }
    }

    private data class Session(val id: String, val paper: LocalExamPaper, val startedAt: Long,
        val answers: JsonObject, val answersSeq: Long, val submittedAt: Long?, val result: JsonObject?)

    private fun ResultSet.readPaper(): LocalExamPaper {
        val paper = LocalExamPaper(LedgerJson.parseToJsonElement(getString("paper_json")).jsonObject)
        check(paper.hash == getString("paper_hash")) { "本地题卷快照校验失败。" }
        return paper
    }

    private fun ResultSet.session(): Session {
        val paper = readPaper()
        return Session(getString("id"), paper, getLong("started_at"),
            LedgerJson.parseToJsonElement(getString("answers_json")).jsonObject, getLong("answers_seq"),
            getLong("submitted_at").let { if (wasNull()) null else it },
            getString("result_json")?.let { LedgerJson.parseToJsonElement(it).jsonObject })
    }

    private fun session(db: Connection, book: String, id: String): Session =
        db.prepareStatement("SELECT * FROM local_exam_attempts WHERE id=? AND owner=? AND book=?").use { sql ->
            sql.setString(1, id); sql.setString(2, owner); sql.setString(3, book)
            sql.executeQuery().use { rows ->
                require(rows.next()) { "找不到这本教材、当前身份的本地考试尝试。" }; rows.session()
            }
        }

    private fun findActive(db: Connection, book: String): Session? =
        db.prepareStatement("SELECT * FROM local_exam_attempts WHERE owner=? AND book=? AND submitted_at IS NULL").use { sql ->
            sql.setString(1, owner); sql.setString(2, book)
            sql.executeQuery().use { rows -> if (rows.next()) rows.session() else null }
        }

    private fun view(session: Session): JsonObject = JsonObject(json(
        "mockId" to session.id, "bookId" to session.paper.book, "paperId" to session.paper.id,
        "status" to if (session.submittedAt == null) "active" else "submitted",
        "purpose" to if (session.paper.kind == "paper") Purpose.FULL_MOCK.name else Purpose.PRACTICE.name,
        "timing" to "UNTIMED", "origin" to session.paper.origin, "key_state" to session.paper.keyState.name,
        "startedAt" to session.startedAt, "submittedAt" to session.submittedAt,
        "snapshotSha256" to session.paper.hash, "answersSeq" to session.answersSeq, "answers" to session.answers,
        "paper" to if (session.submittedAt == null) session.paper.redacted() else session.paper.snapshot,
        "result" to session.result,
    ) + agentRunMetadata(agentRun))
}
