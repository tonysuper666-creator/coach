package com.coach.server

import com.coach.core.LedgerActor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun Route.courseRoutes(
    actor: LedgerActor,
    library: ReaderLibrary? = null,
    friends: FriendAccess? = null,
    alignPipeline: CourseAlignmentPipeline? = null,
    topicPipeline: CourseTopicSynthesisPipeline? = null,
    visionService: CourseVisionService? = null,
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    post("/v1/library/course/align") {
        call.withReaderConnection {
            val friend = call.attributes.getOrNull(observedFriend)
            val device = call.attributes[observedDevice]
            val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
            val diagnostics = friend?.let { FriendReaderDiagnostics() }
            var requestId = ""
            try {
                val pipeline = alignPipeline ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "课程自动对齐服务暂未开启，将使用本地规则对齐。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val bodyBytes = call.receiveChannel().readRemaining(2L * 1024 * 1024 + 1).readByteArray()
                if (bodyBytes.size > 2 * 1024 * 1024) {
                    throw MockError(HttpStatusCode.PayloadTooLarge, "课程材料摘要过大，请精简后再试。")
                }
                val input = json.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                requestId = (input["requestId"] as? JsonPrimitive)?.content.orEmpty()
                if (friend != null) {
                    val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                    if (!allowed) throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法对齐课程。")
                }
                val body = pipeline.align(input)
                if (friend != null) withContext(Dispatchers.IO) {
                    try { friends!!.record(friend, device, requestId, "complete", body, diagnostics) }
                    catch (_: Exception) { throw FriendServiceError() }
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FriendServiceError) {
                if (friend != null) withContext(Dispatchers.IO) {
                    runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) }
                }
                call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
            } catch (e: MockError) {
                call.respondText(buildJsonObject { put("error", e.message) }.toString(), ContentType.Application.Json, e.status)
            } catch (e: Exception) {
                call.respondText(
                    buildJsonObject { put("error", e.message ?: "课程自动对齐暂时失败，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
        }
    }

    post("/v1/library/course/topic-pack") {
        call.withReaderConnection {
            val friend = call.attributes.getOrNull(observedFriend)
            val device = call.attributes[observedDevice]
            val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
            val diagnostics = friend?.let { FriendReaderDiagnostics() }
            var requestId = ""
            try {
                val pipeline = topicPipeline ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "专题合成服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val bodyBytes = call.receiveChannel().readRemaining(4L * 1024 * 1024 + 1).readByteArray()
                if (bodyBytes.size > 4 * 1024 * 1024) {
                    throw MockError(HttpStatusCode.PayloadTooLarge, "本专题上传的原文片段过大，请缩小单次范围。")
                }
                val input = json.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                requestId = (input["requestId"] as? JsonPrimitive)?.content.orEmpty()
                val bookIds = ((input["blocks"] as? JsonArray) ?: JsonArray(emptyList()))
                    .mapNotNull { (it as? JsonObject)?.get("bookId")?.let { p -> (p as? JsonPrimitive)?.content } }
                    .distinct()
                val current: suspend () -> Boolean = {
                    withContext(Dispatchers.IO) {
                        if (friend != null) {
                            actor.execute { authorize(device, bearer) && friendName(device) == friend }
                        } else {
                            bookIds.none { id -> runCatching { library?.reservesVersion(id) == true }.getOrDefault(false) }
                        }
                    }
                }
                if (!current()) {
                    throw MockError(HttpStatusCode.Conflict, "材料或设备授权已失效，无法生成专题包。")
                }
                val cacheScope = friend?.let { "friend:${friendId(it)}" } ?: "owner:$device"
                val bypassCache = (input["bypassCache"] as? JsonPrimitive)?.booleanOrNull == true
                val body = pipeline.generateTopicPack(
                    input = input,
                    cacheScope = cacheScope,
                    bypassCache = bypassCache,
                    currentCheck = current,
                )
                if (friend != null) withContext(Dispatchers.IO) {
                    try { friends!!.record(friend, device, requestId, "complete", body, diagnostics) }
                    catch (_: Exception) { throw FriendServiceError() }
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FriendServiceError) {
                if (friend != null) withContext(Dispatchers.IO) {
                    runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) }
                }
                call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
            } catch (e: MockError) {
                call.respondText(buildJsonObject { put("error", e.message) }.toString(), ContentType.Application.Json, e.status)
            } catch (e: Exception) {
                call.respondText(
                    buildJsonObject { put("error", e.message ?: "专题包生成失败，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
        }
    }

    post("/v1/library/course/vision") {
        call.withReaderConnection {
            val friend = call.attributes.getOrNull(observedFriend)
            val device = call.attributes[observedDevice]
            val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
            val diagnostics = friend?.let { FriendReaderDiagnostics() }
            var requestId = ""
            try {
                val service = visionService ?: return@withReaderConnection call.respondText(
                    buildJsonObject { put("error", "视觉识别服务暂未开启，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                val bodyBytes = call.receiveChannel().readRemaining(24L * 1024 * 1024 + 1).readByteArray()
                if (bodyBytes.size > 24 * 1024 * 1024) {
                    throw MockError(HttpStatusCode.PayloadTooLarge, "单次视觉识别的图片总大小超过限制，请分批识别。")
                }
                val input = json.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                requestId = (input["requestId"] as? JsonPrimitive)?.content.orEmpty()
                if (friend != null) {
                    val allowed = actor.execute { authorize(device, bearer) && friendName(device) == friend }
                    if (!allowed) throw MockError(HttpStatusCode.Conflict, "设备授权已失效，无法发起视觉识别。")
                }
                val body = service.recognizeBatch(input)
                if (friend != null) withContext(Dispatchers.IO) {
                    try { friends!!.record(friend, device, requestId, "complete", body, diagnostics) }
                    catch (_: Exception) { throw FriendServiceError() }
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FriendServiceError) {
                if (friend != null) withContext(Dispatchers.IO) {
                    runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) }
                }
                call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
            } catch (e: MockError) {
                call.respondText(buildJsonObject { put("error", e.message) }.toString(), ContentType.Application.Json, e.status)
            } catch (e: Exception) {
                call.respondText(
                    buildJsonObject { put("error", e.message ?: "手写识别失败，请稍后重试。") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
        }
    }
}
