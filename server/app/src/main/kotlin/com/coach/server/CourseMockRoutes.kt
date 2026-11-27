package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun Application.courseMockRoutes(
    actor: LedgerActor,
    friends: FriendAccess? = null,
    service: CourseMockService? = null,
) {
    routing {
        post("/v1/library/course/mock/generate") {
            call.withReaderConnection {
                val activeService = service ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "课程模考出卷服务暂未开启，请稍后重试。") }.toString(),
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
                            throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法生成课程模考卷。")
                        }
                    }

                    val maxBytes = 2 * 1024 * 1024
                    val bodyBytes = call.receiveChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
                    if (bodyBytes.size > maxBytes) {
                        call.response.header(HttpHeaders.Connection, "close")
                        throw MockError(HttpStatusCode.PayloadTooLarge, "模考出卷请求体积超出上限，请精简后重试。")
                    }
                    val input = LedgerJson.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    requestId = (input["requestId"] as? JsonPrimitive)?.content.orEmpty()
                    val body = activeService.generate(input, friend = friend, device = device)

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
                        buildJsonObject { put("error", e.message ?: "模考出卷请求无效。") }.toString(),
                        ContentType.Application.Json,
                        e.status,
                    )
                } catch (e: IllegalArgumentException) {
                    call.respondText(
                        buildJsonObject { put("error", e.message ?: "模考出卷参数不完整。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: kotlinx.serialization.SerializationException) {
                    call.respondText(
                        buildJsonObject { put("error", "模考出卷请求 JSON 格式不对。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } catch (_: Exception) {
                    call.respondText(
                        buildJsonObject { put("error", "模考出卷暂时不可用，请稍后重试。") }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadGateway,
                    )
                }
            }
        }
    }
}
