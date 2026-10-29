package com.coach.domain

import com.coach.domain.math.MathDelimiters
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class DelimiterContractTest {
    @Test fun sharedLanguageIndependentCases() {
        val cases = Json.parseToJsonElement(File(System.getProperty("coach.repo"), "tools/mathsvc/delims/cases.json").readText()).jsonObject.getValue("cases").jsonArray
        for (raw in cases) {
            val c = raw.jsonObject
            val result = MathDelimiters.tokenize(c.getValue("input").jsonPrimitive.content, c.getValue("source").jsonPrimitive.content == "model")
            val normalized = buildJsonArray {
                result.segments.forEach { segment -> add(buildJsonObject {
                    if (segment.text != null) put("text", segment.text) else { put("math", segment.math!!); put("display", segment.display) }
                }) }
            }
            assertEquals(c["expect"], normalized, c.getValue("name").jsonPrimitive.content)
            assertEquals(c["unresolved"]?.jsonPrimitive?.int ?: 0, result.unresolved, c.getValue("name").jsonPrimitive.content)
        }
        println("DELIMITERS ${cases.size} shared JSON cases passed")
    }
}
