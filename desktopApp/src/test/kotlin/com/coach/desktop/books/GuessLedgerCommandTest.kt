package com.coach.desktop.books

import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.LedgerStore
import com.coach.store.sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

class GuessLedgerCommandTest {
    @Test fun submittedGuessSurvivesLedgerRestartWithoutChangingAttempt() {
        val dir = Files.createTempDirectory("guess-ledger-")
        val options = listOf(Option("one", "First"), Option("two", "Second"), Option("three", "Third"), Option("four", "Fourth"))
        val letters = JsonObject(assignLetters(listOf(ChoiceKey(options, "one")), null, Random(1042)).single().mapValues { JsonPrimitive(it.value) })
        fun command(attempt: String, guessed: Boolean?): WireCommand {
            val snapshot = json("attemptId" to attempt, "questionId" to "q", "fingerprint" to questionFingerprint("Synthetic choice", options), "kpIds" to listOf("kp"), "purpose" to "PRACTICE", "policyVersion" to 1, "groupId" to "synthetic-group", "letterMap" to letters, "format" to "SINGLE_CHOICE")
            val payload = JsonObject(json("attemptId" to attempt, "answer" to letters.str("one"), "posedSnapshot" to snapshot, "offline" to true) + if (guessed == null) json() else json("guessed" to guessed))
            val body = CommandBody("submit_answer", "course", 1793188800000L, payload)
            val bytes = canonicalJson(LedgerJson.encodeToJsonElement(body))
            return WireCommand("synthetic-device", "command-$attempt", bytes, sha256(bytes.toByteArray()))
        }
        try {
            LedgerStore(dir).use { db ->
                db.registerQuestion("q", "course", "q", "SINGLE_CHOICE", "Synthetic choice", "VERIFIED", "one", options)
                assertEquals("OK", db.command(command("guessed-attempt", true)).status)
                assertEquals("OK", db.command(command("ordinary-attempt", false)).status)
                assertEquals("OK", db.command(command("legacy-attempt", null)).status)
            }
            LedgerStore(dir).use { db ->
                val answers = db.events("course").filter { it.type == "answer_submitted" }.associateBy { it.payload.str("attemptId") }
                assertEquals(setOf("guessed-attempt", "ordinary-attempt", "legacy-attempt"), answers.keys)
                assertTrue(answers.getValue("guessed-attempt").payload.flag("guessed"))
                assertFalse(answers.getValue("ordinary-attempt").payload.flag("guessed"))
                assertFalse(answers.getValue("legacy-attempt").payload.flag("guessed"))
                assertEquals(3, db.events("course").count { it.type == "attempt_graded" })
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
