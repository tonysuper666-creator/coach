package com.coach.desktop.reader

import com.coach.desktop.productionPorts
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ExplanationConnectionIsolationTest {
    @Test fun developmentRefusesEveryReservedPortBeforeReadingCredentials() {
        val token = Files.createTempFile("synthetic-token-", ".txt")
        try {
            Files.writeString(token, "synthetic-token")
            for (port in productionPorts) {
                val env = mapOf("COACH_URL" to "127.0.0.1:$port", "COACH_COURSE" to "SYNTHETIC",
                    "COACH_READER_WEEK" to "1", "COACH_TOKEN_FILE" to token.toString())
                val failure = assertFailsWith<IllegalStateException> { explanationConnection("fruit1", env::get) { null } }
                assertTrue(failure.message.orEmpty().contains("production port $port"))
            }
            val env = mapOf("COACH_COURSE" to "SYNTHETIC", "COACH_READER_WEEK" to "1", "COACH_TOKEN_FILE" to token.toString())
            val failure = assertFailsWith<IllegalStateException> {
                explanationConnection("fruit1", env::get) { if (it == "coach.url") "127.0.0.1" else null }
            }
            assertTrue(failure.message.orEmpty().contains("production port 18791"))
        } finally { Files.deleteIfExists(token) }
    }

    @Test fun explicitTokenFileWinsOverInheritedToken(): Unit = runBlocking {
        SyntheticCitationServer().use { h ->
            val token = h.dir.resolve("token")
            Files.writeString(token, "synthetic-private-token-32-characters")
            val env = mapOf("COACH_URL" to h.base, "COACH_COURSE" to "SYNTHETIC", "COACH_DEVICE_ID" to "phone",
                "COACH_TOKEN_FILE" to token.toString(), "COACH_READER_WEEK" to "1", "COACH_TOKEN" to "incorrect-inherited-token")
            val (http, session) = explanationConnection("fruit1", env::get) { null }
            try { session.load(); assertNotNull(session.question, "Synthetic question must load with the token file") }
            finally { http.close() }
        }
    }
}
