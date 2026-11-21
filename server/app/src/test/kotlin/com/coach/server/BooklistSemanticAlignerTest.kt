package com.coach.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BooklistSemanticAlignerTest {

    @Test fun alignerParsesDeepSeekDecisionsFiltersHomonymsAndRecordsLedger() = runBlocking {
        val dir = Files.createTempDirectory("coach-booklist-aligner-")
        try {
            val ledger = BookTokenLedger(dir)
            val transport = object : BatchAiTransport {
                override suspend fun generateSection(
                    systemPrompt: String,
                    userPrompt: String,
                    task: String,
                    batchKey: String,
                    maxTokens: Int,
                ): BatchTransportReply {
                    assertEquals("booklist_concept_align", task)
                    return BatchTransportReply(
                        text = """
                            {
                              "decisions": [
                                {
                                  "pairId": "pair-1",
                                  "keep": true,
                                  "canonicalName": "逆向选择",
                                  "aliases": ["Adverse Selection"],
                                  "relationType": "EMPIRICAL_CHALLENGE",
                                  "diffSummary": "前书推导理论死亡螺旋，本书用实证指出有利选择反例。",
                                  "confidence": 0.93,
                                  "rejectReason": ""
                                },
                                {
                                  "pairId": "pair-2",
                                  "keep": false,
                                  "canonicalName": "bank",
                                  "aliases": [],
                                  "relationType": "CONSISTENT_DEEPENING",
                                  "diffSummary": "",
                                  "confidence": 0.12,
                                  "rejectReason": "homonym_river_vs_finance"
                                }
                              ]
                            }
                        """.trimIndent(),
                        routeChannel = CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH,
                        model = "deepseek-v4.1-flash",
                        inputTokens = 620,
                        outputTokens = 180,
                        ttftMs = 310L,
                        elapsedMs = 740L,
                    )
                }
            }

            val aligner = BooklistSemanticAligner(transport, ledger)
            val input = buildJsonObject {
                put("collectionTitle", "信息与激励")
                put("candidates", buildJsonArray {
                    add(buildJsonObject { put("pairId", "pair-1") })
                    add(buildJsonObject { put("pairId", "pair-2") })
                })
            }

            val result = aligner.align(input)
            val decisions = result["decisions"]!!.jsonArray
            assertEquals(2, decisions.size)
            val first = decisions[0].jsonObject
            assertTrue(first["keep"]!!.jsonPrimitive.boolean)
            assertEquals("逆向选择", first["canonicalName"]!!.jsonPrimitive.content)
            assertEquals("EMPIRICAL_CHALLENGE", first["relationType"]!!.jsonPrimitive.content)
            assertTrue(first["diffSummary"]!!.jsonPrimitive.content.length <= 45)

            val second = decisions[1].jsonObject
            assertFalse(second["keep"]!!.jsonPrimitive.boolean)
            assertEquals("homonym_river_vs_finance", second["rejectReason"]!!.jsonPrimitive.content)

            val entries = ledger.entriesForBook("booklist:${com.coach.store.sha256("信息与激励".toByteArray(Charsets.UTF_8)).take(12)}")
            assertEquals(1, entries.size)
            assertEquals("deepseek-v4.1-flash", entries.single().model)
            assertEquals(0L, entries.single().costMicrodollars)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
