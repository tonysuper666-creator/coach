package com.coach.store

import com.coach.domain.json
import com.coach.domain.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class ChatPersistenceTest {
    @Test fun kpCannotBeReboundToAnotherThreadId() {
        val directory = Files.createTempDirectory("chat-kp-binding-")
        try {
            LedgerStore(directory).use { store ->
                store.bindTutorThread("course", "persisted-thread", "kp")

                val error = assertFailsWith<IllegalArgumentException> {
                    store.bindTutorThread("course", "new-thread", "kp")
                }

                assertTrue(error.message.orEmpty().contains("persisted-thread"))
                assertTrue(error.message.orEmpty().contains("new-thread"))
                store.bindTutorThread("course", "persisted-thread", "kp")
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun quickAskRetryOnALaterDayKeepsItsOriginalThread() {
        val directory = Files.createTempDirectory("chat-next-day-")
        var now = 1793451600000L
        try {
            LedgerStore(directory) { now }.use { store ->
                val thread = store.quickAskForInput("course","phone","quick-id")
                val saved = store.saveChatInput("course",thread.threadId,"Synthetic quick ask","phone","quick-id")
                now += 2 * 86_400_000L
                assertEquals(thread,store.quickAskForInput("course","phone","quick-id"))
                assertEquals(saved.messageId,store.saveChatInput("course",thread.threadId,"Synthetic quick ask","phone","quick-id").messageId)
                assertEquals(1,store.events("course").count { it.type=="quick_ask_opened" })
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun v5MigrationPreservesOldHistoryAndMarksMissingSpeechWithoutInventingUserMessages() {
        val directory = Files.createTempDirectory("chat-v5-")
        try {
            LedgerStore(directory).use { store ->
                store.bindTutorThread("course","thread","kp")
                store.beginStreamingMessage("thread","old-assistant","synthetic-model")
                store.finishMessage("old-assistant","complete",null,"An old synthetic answer",1)
                store.append("course","kp_opened",json("kpId" to "kp"),thread="thread")
                store.bindTutorThread("course","event-only-thread","other-kp")
                store.append("course","kp_opened",json("kpId" to "other-kp"),thread="event-only-thread")
            }
            // A genuine pre-capability schema, with assistant-only history and a state event.
            DriverManager.getConnection("jdbc:sqlite:${directory.resolve("coach.sqlite")}").use { db ->
                db.createStatement().use { s ->
                    s.execute("DROP TABLE chat_inputs")
                    s.execute("DROP TABLE chat_history_coverage")
                    s.execute("DROP TABLE chat_history_legacy")
                    s.execute("PRAGMA user_version=5")
                }
            }
            var coverageSince = ""
            var userId = ""
            val original = "  Synthetic input\n数学 β  "
            LedgerStore(directory) { 123456L }.use { store ->
                val old = store.chatHistory("course","thread")
                coverageSince = old.str("userTextCoverageSince")
                assertEquals("true",old.str("legacyHistoryUncovered"))
                assertEquals(listOf("assistant"),old.getValue("messages").jsonArray.map { it.jsonObject.str("role") })
                val eventOnly = store.chatHistory("course","event-only-thread")
                assertEquals("true",eventOnly.str("legacyHistoryUncovered"))
                assertTrue(eventOnly.getValue("messages").jsonArray.isEmpty())
                val saved = store.saveChatInput("course","thread",original,"desktop","client-id")
                assertTrue(saved.inserted); userId = saved.messageId
                assertEquals(listOf("assistant" to "An old synthetic answer"),store.recentTurns("thread",beforeMessage=userId))
                assertEquals(listOf("assistant" to "An old synthetic answer","user" to original),store.recentTurns("thread"))
                assertFailsWith<IllegalArgumentException> { store.saveChatInput("course","thread","Changed","desktop","client-id") }
                assertFailsWith<IllegalArgumentException> { store.saveChatInput("other","thread",original,"desktop","client-id") }
            }
            LedgerStore(directory) { 999999L }.use { store ->
                val retry = store.saveChatInput("course","thread",original,"desktop","client-id")
                assertFalse(retry.inserted); assertEquals(userId,retry.messageId)
                val history = store.chatHistory("course","thread")
                assertEquals(coverageSince,history.str("userTextCoverageSince"))
                assertEquals("true",history.str("legacyHistoryUncovered"))
                val users = history.getValue("messages").jsonArray.map { it.jsonObject }.filter { it.str("role")=="user" }
                assertEquals(original,users.single().str("text"))
                assertEquals("123456",users.single().str("createdAt"))
                assertEquals(2,store.events("course").size,"State events cannot become invented user speech")
                assertTrue(store.saveChatInput("course","thread",original,"phone","client-id").inserted,
                    "Independent devices may use the same local ID for distinct sends")
            }
            assertTrue(Files.list(directory.resolve("backups")).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("pre-V006-") } })
        } finally { directory.toFile().deleteRecursively() }
    }
}
