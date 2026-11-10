package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.*

/** Mirrors the paired private reader, including standalone mounting and authorization at read time. */
internal fun Application.mainlineEssayRoutes(actor: LedgerActor, mainline: Mainline) {
    routing {
        post("/v1/courses/{course}/mainline/{week}/essay") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            val device = call.request.header("X-Coach-Device")
            val token = call.request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()
                ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (device == null || token == null || call.request.queryParameters.names().any { it.lowercase() in setOf("token", "access_token", "authorization", "code") }) {
                call.respond(HttpStatusCode.Unauthorized); return@post
            }
            try {
                if (!actor.execute { authorize(device, token) }) { call.respond(HttpStatusCode.Unauthorized); return@post }
                val channel = call.receiveChannel(); val bytes = ByteArray(32_769); var size = 0
                while (size < bytes.size) { val n = channel.readAvailable(bytes, size, bytes.size - size); if (n < 0) break; size += n }
                if (size > 32_768) { call.respond(HttpStatusCode.PayloadTooLarge); return@post }
                val identity = LedgerJson.decodeFromString<MainlineEssayIdentity>(bytes.decodeToString(0, size, throwOnInvalidSequence = true))
                val course = call.parameters["course"]!!
                val week = call.parameters["week"]?.toIntOrNull() ?: throw IllegalArgumentException()
                val document = actor.execute {
                    if (!authorize(device, token)) throw MockError(HttpStatusCode.Unauthorized, "设备访问权限已撤销。")
                    mainline.essay(this, course, week, identity)
                }
                call.respondText(LedgerJson.encodeToJsonElement(document).toString(), ContentType.Application.Json)
            } catch (e: MockError) {
                call.respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
            } catch (_: IllegalArgumentException) {
                call.respondText(json("error" to "示范读取请求格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
    }
}
