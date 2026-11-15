package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

/** Synthetic original bytes and a separately seeded textbook blob; no learner can install a mapping. */
class BookOriginalShaMapTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("book-original-map-")
        val original = sha256("synthetic PDF bytes".toByteArray())
        val page = "<!-- PDF 第 1 页 · 书第 1 页 -->\nA synthetic definition.\n"
        val markdown = "synthetic preface\n$page"
        val blob = sha256(markdown.toByteArray())
        val reference = "A synthetic definition."
        val options = listOf(Option("a", "one"), Option("b", "two"), Option("c", "three"), Option("d", "four"))
        val stem = "Which definition?"
        val content = CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
            listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", 1, quote = reference)))))
        val seed = ContentSeed(content, listOf(TextbookDocument("D", "Synthetic", markdown, listOf(TextbookPage(1, 1, page)))))
        var store = LedgerStore(dir)
        val source = BookPracticeSource(original, "lesson", 1, 0, page.length, reference)
        val question: String
        init {
            store.enroll("phone", "synthetic-book-practice-private-token-32c")
            store.seedContent(seed)
            val id = store.enqueueSupply("C", "K", "Synthetic recall", reference)
            val job = store.supplyJobs().single { it.id == id }
            check(store.queueBlindReview(store.claimJob(id)!!, job, JsonArray(emptyList()), "synthetic-generator"))
            val review = store.supplyJobs().single { it.kind == "blind_review" }
            check(store.storeVerifiedBatch(store.claimJob(review.id)!!, review, listOf(VerifiedPoolQuestion(
                stem, options, "a", "Synthetic explanation", reference, "synthetic-generator", "synthetic-reviewer"))) == 1)
            question = query("SELECT question_id FROM questions")
            // This fallback exists solely so this identical test can exercise the old PR240 implementation.
            sql("""CREATE TABLE IF NOT EXISTS book_original_sha(course TEXT NOT NULL,original_sha256 TEXT NOT NULL,
                blob_sha256 TEXT NOT NULL,PRIMARY KEY(course,original_sha256))""")
        }
        fun sql(statement: String, vararg values: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.prepareStatement(statement).use { ps -> values.forEachIndexed { i, s -> ps.setString(i + 1, s) }; ps.executeUpdate() }
        }
        fun query(statement: String): String = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { s -> s.executeQuery(statement).use { r -> check(r.next()); r.getString(1) } }
        }
        fun associate(originalSha: String = original, blobSha: String = blob) {
            sql("INSERT INTO book_original_sha VALUES(?,?,?)", "C", originalSha, blobSha)
        }
        fun install(s: BookPracticeSource = source) = store.seedContent(seed.copy(bookPracticeMappings = listOf(
            BookPracticeMapping(s, "K", question, questionFingerprint(stem, options)))))
        fun rejected(code: String, operation: () -> Unit) {
            val before = store.events("C")
            assertEquals(code, assertFailsWith<ReviewVariantRejected> { operation() }.code)
            assertEquals(before, store.events("C"))
        }
        override fun close() { store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun explicitOriginalMappingAllowsReviewedPoseAndSurvivesReopen() = Fixture().use { f ->
        f.rejected("BOOK_FILE_UNSEEDED") { f.install() }
        f.associate()
        f.install()
        val pose = f.store.poseBookPractice("C", "phone", f.source)
        assertEquals(pose, f.store.poseBookPractice("C", "phone", f.source))
        f.store.close(); f.store = LedgerStore(f.dir)
        assertEquals(pose, f.store.poseBookPractice("C", "phone", f.source))
        assertEquals(1, f.store.events("C").count { it.type == "question_posed" })
    }

    @Test fun originalAndBlobIdentityMustRemainExplicitAndExact() = Fixture().use { f ->
        assertFails { f.associate(f.blob, f.blob) }
        f.associate(); f.install()
        f.rejected("BOOK_MAPPING_UNAVAILABLE") { f.store.poseBookPractice("C", "phone", f.source.copy(bookSha256 = sha256("modified PDF bytes".toByteArray()))) }
        f.rejected("BOOK_REFERENCE_MISMATCH") { f.store.poseBookPractice("C", "phone", f.source.copy(reference = "different")) }
        f.sql("UPDATE source_pages SET text=? WHERE doc_id='D'", f.page.replace("definition", "definitioN"))
        f.rejected("BOOK_BLOCK_MISMATCH") { f.store.poseBookPractice("C", "phone", f.source) }
        f.sql("UPDATE source_pages SET text=? WHERE doc_id='D'", f.page)
        f.sql("DELETE FROM book_original_sha WHERE course='C'")
        f.rejected("BOOK_FILE_UNSEEDED") { f.store.poseBookPractice("C", "phone", f.source) }
    }

    @Test fun mappingChangedAfterPoseDoesNotReuseAttempt() = Fixture().use { f ->
        f.associate(); f.install(); f.store.poseBookPractice("C", "phone", f.source)
        val replacement = (f.markdown + "changed").toByteArray()
        val replacementSha = sha256(replacement)
        Files.write(f.dir.resolve("blobs").resolve(replacementSha), replacement)
        f.sql("UPDATE source_docs SET blob_sha256=? WHERE doc_id='D'", replacementSha)
        f.sql("UPDATE book_original_sha SET blob_sha256=? WHERE course='C'", replacementSha)
        f.rejected("BOOK_MAPPING_CHANGED") { f.store.poseBookPractice("C", "phone", f.source) }
    }

    @Test fun directBlobAndLegacyPoseStayCompatibleButCorruptedBlobIsRejected() = Fixture().use { f ->
        val direct = f.source.copy(bookSha256 = f.blob)
        f.install(direct)
        val pose = f.store.poseBookPractice("C", "phone", direct)
        val current = LedgerJson.parseToJsonElement(f.query("SELECT mapping_json FROM book_practice_poses")).jsonObject
        val legacy = JsonObject(current - "sourceBlobSha256").toString()
        f.sql("UPDATE book_practice_poses SET mapping_json=?", legacy)
        assertEquals(pose, f.store.poseBookPractice("C", "phone", direct))
        Files.writeString(f.dir.resolve("blobs").resolve(f.blob), "tampered blob")
        f.rejected("BOOK_FILE_UNSEEDED") { f.store.poseBookPractice("C", "phone", direct) }
    }
}
