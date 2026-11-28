package com.coach.server

import com.coach.core.LedgerActor
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.*

internal val learningSyncActive = io.ktor.util.AttributeKey<Boolean>("learning-sync-active")

internal fun Application.learningSyncRoutes(
    actor: LedgerActor,
    books: UnifiedBookPipeline,
    allowOwner: Boolean = false,
) {
    attributes.put(learningSyncActive, true)
    val store = LearningSyncStore(books.accountsDirPath, books.learningBackup(), allowOwner = allowOwner)
    suspend fun ApplicationCall.withAccount(block: (UnifiedBookScope, String) -> JsonObject) {
        val device = attributes[observedDevice]
        val friend = attributes.getOrNull(observedFriend)
        val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ").orEmpty()
        val scope = UnifiedBookScope.fromCall(device, friend)
        val allowed = scope.isSableAccount || (allowOwner && friend == null)
        if (!allowed) { respond(HttpStatusCode.Forbidden); return }
        try {
            // Serialize the authorization check with device revocation. The request cannot supply a storage path.
            val result = books.withActiveAccount(scope) {
                actor.execute {
                    if (authorize(device, token) && friendName(device) == friend) block(scope, device) else null
                }
            }
            response.header(HttpHeaders.CacheControl,"no-store")
            if (result == null) respond(HttpStatusCode.Unauthorized)
            else respondText(result.toString(),ContentType.Application.Json)
        } catch (_: LearningRecoveryRequired) {
            respond(HttpStatusCode.ServiceUnavailable)
        } catch (_: LearningConflict) {
            respond(HttpStatusCode.Conflict)
        } catch (_: LearningRecordMissing) {
            respond(HttpStatusCode.NotFound)
        } catch (_: LearningSnapshotExpired) {
            respond(HttpStatusCode.Gone)
        } catch (e: UnifiedBookError) {
            respond(e.status)
        } catch (_: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest)
        } catch (_: NoSuchElementException) {
            respond(HttpStatusCode.BadRequest)
        }
    }
    routing {
        get("/learning-sync/snapshot") {
            val params = call.request.queryParameters
            call.withAccount { scope, _ -> store.snapshot(scope,requireNotNull(params["epoch"]),params["snapshotId"],
                requireNotNull(params["after"]?.toLongOrNull()),params["chunks"]=="1") }
        }
        get("/learning-sync/chunk") {
            val params = call.request.queryParameters
            call.withAccount { scope, _ -> store.part(scope,requireNotNull(params["epoch"]),requireNotNull(params["transportEpoch"]),
                params["snapshotId"],requireNotNull(params["seq"]?.toLongOrNull()),requireNotNull(params["offset"]?.toIntOrNull())) }
        }
        get("/learning-sync/profile") {
            call.withAccount { scope, _ -> store.profile(scope) }
        }
        get("/learning-sync/record") {
            call.withAccount { scope, _ -> store.lookup(scope,
                requireNotNull(call.request.queryParameters["kind"]), requireNotNull(call.request.queryParameters["id"])) }
        }
        get("/learning-sync/changes") {
            val cursor = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            val epoch = call.request.queryParameters["epoch"]
            call.withAccount { scope, device -> store.changes(scope, epoch, cursor,device,requireNotNull(call.request.queryParameters["stream"]),
                call.request.queryParameters["chunks"]=="1") }
        }
        post("/learning-sync/push") {
            val channel = call.receiveChannel()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = channel.readAvailable(chunk,0,chunk.size)
                if (n < 0) break
                if (n > 4 * 1024 * 1024 - buffer.size()) return@post call.respond(HttpStatusCode.PayloadTooLarge)
                buffer.write(chunk,0,n)
            }
            call.withAccount { scope, device ->
                store.push(scope, device, Json.parseToJsonElement(buffer.toString(Charsets.UTF_8)).jsonObject)
            }
        }
    }
}
