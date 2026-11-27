package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.coach.domain.grade.ChapterOpenSourceBlock

/**
 * Independent Ktor route block for LearningCoach 2.0-AI-2 (`TON-1801`):
 * `POST /v1/library/practice/grade-and-diagnose`
 *
 * Kept strictly separate from Q&A / retrieval routes (`2.0-AI-3`) for zero file overlap.
 */
fun Application.practiceGradeRoutes(
    actor: LedgerActor,
    friends: FriendAccess? = null,
    service: PracticeGradeAndDiagnoseService? = null,
) {
    routing {
        post("/v1/library/practice/grade-and-diagnose") {
            call.withReaderConnection {
                val activeService = service ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "逐点批改与错因诊断服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                val diagnostics = friend?.let { FriendReaderDiagnostics() }
                var requestId = ""
                try {
                    if (friend != null && friends != null) {
                        val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                        if (!allowed) {
                            throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法提交批改。")
                        }
                    }

                    val bodyBytes = call.receiveChannel().readRemaining(2L * 1024 * 1024 + 1).readByteArray()
                    if (bodyBytes.size > 2 * 1024 * 1024) {
                        throw MockError(HttpStatusCode.PayloadTooLarge, "批改请求体过大，请精简原文范围后重试。")
                    }
                    val input = LedgerJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    requestId = (input["requestId"] as? JsonPrimitive)?.content.orEmpty()

                    val parsed = PracticeGradeRequestParser.parse(input)
                    val response = activeService.process(parsed, friend = friend, device = device)
                    val body = response.toJson()

                    if (friend != null && friends != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                friends.record(friend, device, requestId, "complete", body, diagnostics)
                            } catch (_: Exception) {
                                throw FriendServiceError()
                            }
                        }
                    }
                    call.respondText(body.toString(), ContentType.Application.Json)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FriendServiceError) {
                    if (friend != null) {
                        withContext(Dispatchers.IO) {
                            runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) }
                        }
                    }
                    call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
                } catch (e: MockError) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "批改请求无效。") }.toString(),
                        ContentType.Application.Json,
                        e.status,
                    )
                } catch (e: IllegalArgumentException) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "批改请求参数格式不对。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: kotlinx.serialization.SerializationException) {
                    call.respondText(
                        buildJsonObject { put("error", "批改请求 JSON 格式不对。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: Exception) {
                    call.respondText(
                        buildJsonObject { put("error", "逐点批改暂时不可用，已保留你的草稿。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadGateway,
                    )
                }
            }
        }

        post("/v1/library/practice/chapter-open-question") {
            call.withReaderConnection {
                val activeService = service ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "这一章的开放题暂时出不了，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val friend = call.attributes.getOrNull(observedFriend)
                val device = call.attributes[observedDevice]
                val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                try {
                    if (friend != null && friends != null) {
                        val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                        if (!allowed) throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法出题。")
                    }
                    val bodyBytes = call.receiveChannel().readRemaining(2L * 1024 * 1024 + 1).readByteArray()
                    if (bodyBytes.size > 2 * 1024 * 1024) {
                        throw MockError(HttpStatusCode.PayloadTooLarge, "出题请求体过大。")
                    }
                    val input = LedgerJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    val bookId = (input["bookId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    val chapterId = (input["chapterId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    require(bookId.isNotBlank() && chapterId.isNotBlank()) { "缺少书或章节。" }
                    val blocks = (input["blocks"] as? JsonArray).orEmpty().mapNotNull { el ->
                        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        val page = (o["page"] as? JsonPrimitive)?.intOrNull ?: 0
                        val text = (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (id.isBlank() || page <= 0 || text.isBlank()) null
                        else ChapterOpenSourceBlock(id, page, text.take(900))
                    }
                    val pack = activeService.generateChapterOpen(bookId, chapterId, blocks, friend, device)
                    val body = buildJsonObject {
                        put("stem", pack.stem)
                        put("points", buildJsonArray {
                            pack.points.forEach { point ->
                                add(buildJsonObject {
                                    put("id", point.id)
                                    put("claim", point.claim)
                                    put("blockId", point.blockId)
                                    put("page", point.page)
                                    put("quoteAnchor", point.quoteAnchor)
                                    put("startUtf16", point.startUtf16)
                                    put("endUtf16", point.endUtf16)
                                })
                            }
                        })
                    }
                    call.respondText(body.toString(), ContentType.Application.Json)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MockError) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "出题请求无效。") }.toString(),
                        ContentType.Application.Json,
                        e.status,
                    )
                } catch (e: IllegalArgumentException) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "这一章暂时出不了题。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: Exception) {
                    call.respondText(
                        buildJsonObject { put("error", "这一章的开放题暂时出不了，请稍后重试。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadGateway,
                    )
                }
            }
        }
    }
}
