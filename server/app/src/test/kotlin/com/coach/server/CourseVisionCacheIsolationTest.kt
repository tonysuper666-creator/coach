package com.coach.server

import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import com.coach.store.sableFriendName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** TON-1943: a course OCR transcript is cached per account and never served to another account. */
class CourseVisionCacheIsolationTest {
    private class Recorder : CourseVisionTransport {
        val calls = mutableListOf<String?>()
        override suspend fun recognizePage(pageNumber: Int, pngBase64: String, pageSha256: String) =
            recognizePage(pageNumber, pngBase64, pageSha256, null, null)
        override suspend fun recognizePage(pageNumber: Int, pngBase64: String, pageSha256: String, friend: String?, device: String?): CourseVisionReply {
            calls += friend
            return CourseVisionReply(text = "transcript for ${friend ?: "owner"}")
        }
    }

    private val sha = "e".repeat(64)
    private fun input() = buildJsonObject {
        put("bookId", "synthetic")
        put("totalPages", 1)
        put("pages", buildJsonArray { add(buildJsonObject {
            put("pageNumber", 1); put("pageSha256", sha); put("pngBase64", "iVBORw0KGgo=")
        }) })
    }
    private fun text(reply: kotlinx.serialization.json.JsonObject) =
        reply.getValue("pages").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    @Test fun sameAccountReusesItsOwnTranscriptAndOtherAccountsRecognizeTheirOwn() = runBlocking {
        val dir = Files.createTempDirectory("coach-vision-scope-")
        try {
            val transport = Recorder()
            val service = CourseVisionService(dir, transport = transport)
            assertEquals("transcript for owner", text(service.recognizeBatch(input())))
            assertEquals("transcript for 甲", text(service.recognizeBatch(input(), friend = "甲", device = "a-pc")))
            assertEquals("transcript for 甲", text(service.recognizeBatch(input(), friend = "甲", device = "a-pc")))
            assertEquals("transcript for 乙", text(service.recognizeBatch(input(), friend = "乙", device = "b-pc")))
            assertEquals("transcript for owner", text(service.recognizeBatch(input())))
            assertEquals(listOf(null, "甲", "乙"), transport.calls)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun legacyRowsWithoutAnOwnerAreDropped() = runBlocking {
        val dir = Files.createTempDirectory("coach-vision-legacy-")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("course-vision-cache.sqlite")}").use { db ->
                db.createStatement().use {
                    it.execute("CREATE TABLE vision_cache(page_sha256 TEXT PRIMARY KEY, text TEXT NOT NULL, model TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                    it.execute("INSERT INTO vision_cache VALUES('$sha', 'someone else''s transcript', 'm', 1)")
                }
            }
            val transport = Recorder()
            val service = CourseVisionService(dir, transport = transport)
            assertNull(service.lookupCached(sha))
            assertNull(service.lookupCached(sha, "甲"))
            assertEquals("transcript for 甲", text(service.recognizeBatch(input(), friend = "甲", device = "a-pc")))
            assertEquals(listOf<String?>("甲"), transport.calls)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun deletingAnAccountErasesItsTranscriptsAndKeepsOtherAccounts() = runBlocking {
        val root = Files.createTempDirectory("coach-vision-erase-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, LedgerStore(root.resolve("ledger")))
        try {
            val data = root.resolve("data")
            val friends = FriendAccess(data.resolve("friends"), root.resolve("state"), root.resolve("config.json"))
            val books = UnifiedBookPipeline(data.resolve("unified-books"), UnifiedBookConfig(enabled = true, autoProcess = false))
            val service = CourseVisionService(data.resolve("course-vision"), transport = Recorder())
            friends.eraseOnAccountDeletion(service::eraseAccount)
            books.installAccountDataFinalizer(actor, friends)
            val leaving = sableFriendName("vision-erase-leaving")
            val staying = sableFriendName("vision-erase-staying")
            service.recognizeBatch(input(), friend = leaving, device = "leaving-pc")
            service.recognizeBatch(input(), friend = staying, device = "staying-pc")
            service.recognizeBatch(input())
            val accountScope = UnifiedBookScope.fromCall("leaving-pc", leaving)
            val epoch = books.beginAccountDeletion(accountScope, leaving)
            books.deleteAccount(accountScope, expectedDeletionEpoch = epoch)
            assertEquals("DELETED", books.accountDeletionStateForTest(accountScope))
            assertNull(service.lookupCached(sha, leaving))
            assertEquals("transcript for $staying", service.lookupCached(sha, staying)?.first)
            assertEquals("transcript for owner", service.lookupCached(sha)?.first)
        } finally {
            actor.close()
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }
}
