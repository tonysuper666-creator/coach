package com.coach.server

import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Synthetic legacy keys must work through autosave, frozen submission and replay. */
class LegacyAnalyticalReplayTest {
    @Test fun percentKeySurvivesFrozenReplay() = check("50%", "0.50")
    @Test fun punctuationKeySurvivesFrozenReplay() = check("3.14。", "3.14")
    @Test fun whitespaceKeySurvivesFrozenReplay() = check(" 3.14 ", "3.14")
    @Test fun blankToleranceUsesAnalyticalDefault() = check("3.145", "3.14", "\"\"")
    @Test fun explicitZeroOnLegacyKeyIsStrict() = check("50.0000000000000001%", "0.50", "0", 0.0)
    @Test fun decimalKeyNeverRoundTripsThroughDouble() = check("1.005000000000000000001", "1.00", "0.005", 0.0)
    @Test fun fractionKeyRemainsLoadable() = check("1/2", "0.50")

    private fun check(key: String, submitted: String, tolerance: String = "0.005", score: Double = 4.0) {
        val dir = Files.createTempDirectory("legacy-analytical-replay")
        try {
            val paper = dir.resolve("synthetic.json")
            Files.writeString(paper, """{"id":"legacy","course":"SYNTH","title":"Synthetic legacy","kind":"paper",
                "sections":[{"items":[{"id":"A","type":"analytical","stem":"Synthetic calculation","marks":4,
                "parts":[{"label":"a","prompt":"Compute","marks":4,"answer":${JsonPrimitive(key)},
                "tolerance":$tolerance,"rubric":[]}]}]}]}""")
            val mocks = MockEngine(MockLibrary(dir))
            lateinit var id: String
            lateinit var result: JsonObject
            LedgerStore(dir.resolve("db")).use { store ->
                id = mocks.start(store, "SYNTH", json("paperId" to "legacy")).str("mockId")
                val answers = json("A#a" to json("final" to submitted))
                mocks.save(store, "SYNTH", id, answers)
                Files.delete(paper) // Only the frozen session is available to submit/reopen.
                result = mocks.submit(store, "SYNTH", id, null, false)
                assertEquals(score, result.getValue("score").jsonPrimitive.double)
                val part = result.getValue("items").jsonArray.single().jsonObject
                assertEquals(submitted, part.getValue("answer").jsonObject.str("final"))
                assertEquals(score == 4.0, part.getValue("numericFinal").jsonObject.flag("correct"))
                assertEquals(key, part.str("reference"), "keep the original key for display")
            }
            LedgerStore(dir.resolve("db")).use { reopened -> assertEquals(result, mocks.get(reopened, "SYNTH", id)) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
