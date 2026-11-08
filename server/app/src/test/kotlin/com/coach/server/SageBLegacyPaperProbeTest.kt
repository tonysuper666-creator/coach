// Place at: server/app/src/test/kotlin/com/coach/server/SageBLegacyPaperProbeTest.kt
package com.coach.server

import kotlinx.serialization.json.*
import kotlin.test.*

/** Keys that origin/main accepted via NumericValue.parse but are no longer BigDecimal literals. */
class SageBLegacyPaperProbeTest {
    private fun paper(answer: String, tolerance: String? = null) = Json.parseToJsonElement("""{"id":"p","title":"t","course":"SYNTH","kind":"paper",
      "sections":[{"items":[{"id":"A","type":"analytical","stem":"s","marks":4,"parts":[
      {"label":"a","prompt":"p","marks":4,"answer":${JsonPrimitive(answer)},${tolerance?.let { "\"tolerance\":$it," }.orEmpty()}"rubric":[]}]}]}]}""").jsonObject

    // origin/main: NumericValue.parse("50%") = 0.5 -> server-numeric part loads.
    // PR: toBigDecimalOrNull() = null -> require("细则不足分值且没有数值答案") throws; MockEngine.result() on a
    // frozen session of this paper (history/report) would throw too.
    @Test fun percentKeyStillLoads() { assertTrue(runCatching { MockPaper(paper("50%")).units }.isSuccess) }
    @Test fun keyWithTrailingPunctuationStillLoads() { assertTrue(runCatching { MockPaper(paper("3.14。")).units }.isSuccess) }
    @Test fun keyWithSurroundingSpaceStillLoads() { assertTrue(runCatching { MockPaper(paper(" 3.14 ")).units }.isSuccess) }

    // origin/main: tolerance doubleOrNull -> 0.0 fallback; PR: BigDecimal("") throws at paper load.
    @Test fun emptyStringToleranceStillLoads() { assertTrue(runCatching { MockPaper(paper("3.14", "\"\"")).units }.isSuccess) }
}
