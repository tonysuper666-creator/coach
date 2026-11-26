package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.store.sha256
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * LearningCoach 2.0-W2-D Stage 2 semantic adjudicator for booklist cross-book concepts.
 * Routes through [BatchAiTransport] (primary: CodeBuddy CLI `deepseek-v4.1-flash`; overflow: Haiku Batch),
 * rejects generic/homonym false positives, merges cross-lingual/synonymous concepts, and writes <= 45-char
 * plain-Chinese difference summaries while accounting tokens in [BookTokenLedger].
 */
class BooklistSemanticAligner(
    private val transport: BatchAiTransport,
    private val ledger: BookTokenLedger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun align(input: JsonObject): JsonObject {
        val collectionTitle = (input["collectionTitle"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(120)
        val candidates = (input["candidates"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(96)
        if (candidates.isEmpty()) {
            return buildJsonObject {
                put("decisions", JsonArray(emptyList()))
                put("model", "none")
            }
        }

        val batches = candidates.chunked(8)
        val semaphore = Semaphore(4)
        val batchResults = coroutineScope {
            batches.mapIndexed { batchIdx, batch ->
                async {
                    semaphore.withPermit {
                        val systemPrompt = """
                            你是严谨的学术书单跨书概念对齐裁判。请保持思考极简，直接输出合法 JSON 对象，不输出 Markdown 代码块或多余解释。
                            规则：
                            1. 严格剔除普通高频废词（如 market, price, policy, system, study, history, 市场, 政策, 方法等）与同名异义假阳性（homonym/polysemy，上下文学科或含义不同则 keep=false）。
                            2. 合并异名同构或中英同义专业概念，给出统一 canonicalName 与 aliases。
                            3. relationType 只能取以下 5 个枚举之一：
                               CONSISTENT_DEEPENING | NOTATION_OR_BOUNDARY_DIFF | EMPIRICAL_CHALLENGE | OPPOSING_VIEW | PREREQUISITE
                            4. diffSummary 必须用平实简体中文写清两书异同或互补点，不超过 45 字，句号结尾，不暴露模型或系统实现词。
                            5. confidence 为 0.0 到 1.0；只有确实是同一专业概念且跨书关联有学习价值时才给 >= 0.85 且 keep=true。
                            输出格式：{"decisions":[{"pairId":"...","keep":true,"canonicalName":"...","aliases":["..."],"relationType":"...","diffSummary":"...","confidence":0.91,"rejectReason":""}]}
                        """.trimIndent()
                        val userPayload = buildJsonObject {
                            put("collectionTitle", collectionTitle)
                            put("candidates", JsonArray(batch))
                        }.toString()
                        val batchKey = "booklist-align-${sha256("$collectionTitle|$batchIdx|$userPayload".toByteArray(Charsets.UTF_8)).take(16)}"

                        val reply = runCatching {
                            transport.generateSection(
                                systemPrompt = systemPrompt,
                                userPrompt = userPayload,
                                task = "booklist_concept_align",
                                batchKey = batchKey,
                                maxTokens = 2_400,
                            )
                        }.getOrNull() ?: return@withPermit null
                        ledger?.record(
                            TokenLedgerEntry(
                                bookId = "booklist:${sha256(collectionTitle.toByteArray(Charsets.UTF_8)).take(12)}",
                                chapterId = "align-batch-$batchIdx",
                                sectionId = batchKey,
                                task = "booklist_concept_align",
                                routeChannel = reply.routeChannel,
                                model = reply.model,
                                inputTokens = reply.inputTokens,
                                outputTokens = reply.outputTokens,
                                cachedTokens = reply.cachedTokens,
                                costMicrodollars = TokenLedgerPricing.costMicrodollars(
                                    reply.routeChannel,
                                    reply.inputTokens,
                                    reply.outputTokens,
                                    reply.cachedTokens,
                                ),
                                ttftMs = reply.ttftMs,
                                elapsedMs = reply.elapsedMs,
                            ),
                        )
                        reply.model to parseDecisions(reply.text, batch)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val modelUsed = batchResults.firstOrNull()?.first ?: "deepseek-v4.1-flash"
        val allDecisions = batchResults.flatMap { it.second }

        return buildJsonObject {
            put("decisions", JsonArray(allDecisions))
            put("model", modelUsed)
        }
    }

    private fun parseDecisions(rawText: String, batch: List<JsonObject>): List<JsonObject> {
        val cleaned = extractJsonObject(rawText) ?: return emptyList()
        val parsed = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull() ?: return emptyList()
        val arr = parsed["decisions"] as? JsonArray ?: return emptyList()
        val allowedPairIds = batch.mapNotNull { (it["pairId"] as? JsonPrimitive)?.contentOrNull }.toSet()
        val allowedRelations = setOf(
            "CONSISTENT_DEEPENING",
            "NOTATION_OR_BOUNDARY_DIFF",
            "EMPIRICAL_CHALLENGE",
            "OPPOSING_VIEW",
            "PREREQUISITE",
        )
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pairId = (o["pairId"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
            if (pairId !in allowedPairIds) return@mapNotNull null
            val keep = (o["keep"] as? JsonPrimitive)?.booleanOrNull ?: false
            val canonical = (o["canonicalName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(80)
            val aliases = (o["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(80) }
            val relRaw = (o["relationType"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val rel = if (relRaw in allowedRelations) relRaw else "CONSISTENT_DEEPENING"
            val diff = (o["diffSummary"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(45)
            val conf = ((o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).coerceIn(0.0, 1.0)
            val rejectReason = (o["rejectReason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(60)
            buildJsonObject {
                put("pairId", pairId)
                put("keep", keep && canonical.isNotBlank() && diff.isNotBlank() && conf >= 0.85)
                put("canonicalName", canonical)
                put("aliases", buildJsonArray { aliases.forEach { add(JsonPrimitive(it)) } })
                put("relationType", rel)
                put("diffSummary", diff)
                put("confidence", conf)
                put("rejectReason", rejectReason)
            }
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }
}

fun Application.booklistAlignmentRoutes(
    @Suppress("UNUSED_PARAMETER") actor: LedgerActor,
    friends: FriendAccess? = null,
    aligner: BooklistSemanticAligner? = null,
    sayings: ConceptSayingService? = null,
) {
    routing {
        post("/v1/library/booklist/align") {
            call.withReaderConnection {
                val activeAligner = aligner ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "书单主题对齐服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                try {
                    if (friend != null && friends != null) {
                        val quota = withContext(Dispatchers.IO) { friends.status(friend, device) }
                        val callsRem = quota["callsRemaining"]?.jsonPrimitive?.longOrNull
                        val tokensRem = quota["tokensRemaining"]?.jsonPrimitive?.longOrNull
                        if ((callsRem != null && callsRem <= 0L) || (tokensRem != null && tokensRem <= 0L)) {
                            throw FriendServiceError("daily_quota")
                        }
                    }
                    val channel = call.receiveChannel()
                    val bytes = ByteArray(262_145)
                    var size = 0
                    while (size < bytes.size) {
                        val n = channel.readAvailable(bytes, size, bytes.size - size)
                        if (n < 0) break
                        size += n
                    }
                    if (size > 262_144) throw MockError(HttpStatusCode.PayloadTooLarge, "书单对齐请求过大。")
                    val input = LedgerJson.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
                    val reply = activeAligner.align(input)
                    call.respondText(reply.toString(), ContentType.Application.Json)
                } catch (e: FriendServiceError) {
                    call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
                } catch (e: MockError) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "书单对齐请求无效。") }.toString(), ContentType.Application.Json, e.status)
                } catch (_: Exception) {
                    call.respondText(
                        buildJsonObject { put("error", "书单主题对齐暂时不可用，已保留本地结果。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadGateway,
                    )
                }
            }
        }
        post("/v1/library/concepts/sayings") {
            call.withReaderConnection {
                val service = sayings ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "概念对照服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                try {
                    if (friend != null && friends != null) {
                        val quota = withContext(Dispatchers.IO) { friends.status(friend, device) }
                        val callsRem = quota["callsRemaining"]?.jsonPrimitive?.longOrNull
                        val tokensRem = quota["tokensRemaining"]?.jsonPrimitive?.longOrNull
                        if ((callsRem != null && callsRem <= 0L) || (tokensRem != null && tokensRem <= 0L)) {
                            throw FriendServiceError("daily_quota")
                        }
                    }
                    val channel = call.receiveChannel()
                    val bytes = ByteArray(262_145)
                    var size = 0
                    while (size < bytes.size) {
                        val n = channel.readAvailable(bytes, size, bytes.size - size)
                        if (n < 0) break
                        size += n
                    }
                    if (size > 262_144) throw MockError(HttpStatusCode.PayloadTooLarge, "概念对照请求过大。")
                    val input = LedgerJson.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
                    val reply = service.generate(input, friend, device)
                    call.respondText(reply.toString(), ContentType.Application.Json)
                } catch (e: FriendServiceError) {
                    call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
                } catch (e: MockError) {
                    call.respondText(buildJsonObject { put("error", e.message ?: "概念对照请求无效。") }.toString(), ContentType.Application.Json, e.status)
                } catch (e: IllegalArgumentException) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "概念对照请求无效。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: Exception) {
                    call.respondText(
                        buildJsonObject { put("error", "各书说法暂时对不上，已保留本机结果。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadGateway,
                    )
                }
            }
        }
    }
}
