package com.coach.server

import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class SyntheticMainlineTest {
    @Test fun producerPackPassesRealWeeklyFeedAndAnswerWithoutProductionOrLedgerApproval() {
        val raw = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-v1.json")!!.readText()).jsonObject
        val runtime = SyntheticLadderRuntime.parse(raw)
        val dir = Files.createTempDirectory("synthetic-mainline")
        try {
            LedgerStore(Files.createDirectories(dir.resolve("db"))).use { store ->
                val library = MockLibrary(Files.createDirectories(dir.resolve("papers")))
                assertFailsWith<MockError> { Mainline(library).week(store, "SYNTHETIC", 1, learning = true) }
                val mainline = Mainline(library, syntheticRuntime = runtime)
                val before = store.events("SYNTHETIC")
                val feed = mainline.week(store, "SYNTHETIC", 1, learning = true)
                val items = feed["items"]!!.jsonArray
                assertEquals(1, items.size)
                val item = items.single().jsonObject
                assertEquals("SYN-Q1@v2", item.str("id"))
                for (key in listOf("answer", "model_answer", "rubric", "essay")) assertFalse(key in item, key)
                assertFalse(feed.toString().contains("Two objects form a pair."))
                val metadata = LedgerJson.decodeFromJsonElement<LadderQuestion>(item["ladder"]!!)
                assertEquals(item.str("id"), QuestionLadder.next(listOf(metadata), LadderSession()).question!!.id)
                val wire = json("itemId" to metadata.id, "choice" to 1, "learning" to true, "submissionId" to "SYN-submit")
                val result = mainline.answer(store, "SYNTHETIC", 1, wire)
                assertEquals(1L, result.num("answer")); assertFalse(result.flag("independentCorrect"))
                assertFalse(result.flag("mastery_evidence")); assertTrue(result.flag("syntheticOnly"))
                assertEquals("UNREVIEWED", result.str("keyState"))
                assertEquals(before, store.events("SYNTHETIC"))
                assertEquals(result, mainline.answer(store, "SYNTHETIC", 1, wire))
                assertFailsWith<MockError> { mainline.answer(store, "SYNTHETIC", 1, JsonObject(wire + ("itemId" to JsonPrimitive("SYN-Q1@v1")))) }
                assertFailsWith<MockError> { mainline.answer(store, "SYNTHETIC", 1, JsonObject(wire + ("itemId" to JsonPrimitive("SYN-Q2@v1")))) }
                assertFailsWith<MockError> { mainline.answer(store, "SYNTHETIC", 1, JsonObject(wire - "submissionId")) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
