package com.coach.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Who may call each endpoint the clients use (TON-1943).
 *
 * [SIGNED_IN]: any paired or Sable account. The handler keeps a friend inside their own store (`FriendAccess`),
 * sends model calls through the friend's Bridge route (`friendId`/`friendDevice`) so they land in that friend's
 * usage, and never opens the owner's catalog, ledger, source files or AI-call table.
 *
 * [OWNER]: only the owner's own computer. A signed-in friend gets `403 {"code":"owner_only"}` instead of an empty 404,
 * so the client can explain or hide the entry. Anonymous callers on the public listener still get an empty 404.
 *
 * Every endpoint path that appears in client source must match one row here; `PublicApiTableTest` fails otherwise.
 */
internal enum class ApiAccess { SIGNED_IN, OWNER }

internal class ApiRoute(
    val method: HttpMethod?,
    val template: String,
    val access: ApiAccess,
    val why: String,
    shape: Regex? = null,
    val needsLearningSync: Boolean = false,
    val ownerMessage: String? = null,
) {
    private val regex = shape ?: templateRegex(template)
    fun matches(method: HttpMethod, path: String) = (this.method == null || this.method == method) && regex.matches(path)

    private companion object {
        fun templateRegex(template: String): Regex {
            val rest = template.endsWith("/**")
            val body = template.removeSuffix("/**").split('/').joinToString("/") { segment ->
                if (segment.startsWith('{') && segment.endsWith('}')) "[^/]+" else Regex.escape(segment)
            }
            return Regex(if (rest) "$body(/.*)?" else body)
        }
    }
}

internal object PublicApiTable {
    private val post = HttpMethod.Post
    private val get = HttpMethod.Get
    private val delete = HttpMethod.Delete
    private const val SHELF_BOOK = "sb_[0-9a-f]{24}"

    private fun open(method: HttpMethod, template: String, why: String, shape: Regex? = null, sync: Boolean = false) =
        ApiRoute(method, template, ApiAccess.SIGNED_IN, why, shape, sync)
    private fun owner(template: String, why: String, message: String? = null) =
        ApiRoute(null, template, ApiAccess.OWNER, why, ownerMessage = message)

    val routes: List<ApiRoute> = listOf(
        // Sign-in and status.
        open(post, "/v1/public/sable", "Sable token exchange; creates only this account's device credential"),
        open(post, "/v1/pair", "pairing code exchange; public pairing never issues owner credentials"),
        open(get, "/v1/public/status", "this account's own quota from friends.json and the friend ledger"),
        open(get, "/v1/session", "display information for the authenticated device's session"),
        open(delete, "/v1/session", "revokes only the authenticated non-admin device"),
        open(post, "/v1/incidents", "redacted crash report; no owner data is read"),
        open(delete, "/v1/account", "deletes only this account's data (FriendAccess.deleteAccountData)"),
        // Reading and companion.
        open(post, "/v1/library/explain", "friend imports and friend Bridge route; audit in the friend directory"),
        open(post, "/v1/library/explain/cancel", "cancels only this device's request"),
        open(delete, "/v1/library/personal-imports/{bookId}", "resets only the authenticated device's personal import snapshots for this book in its own or friend store"),
        open(post, "/v1/library/companion/chapter", "friend Bridge route via FriendScopedBatchTransport; no owner ledger row"),
        open(post, "/v1/library/collection/ask", "friend quota check, friend Bridge route, friend audit"),
        // 2.2+ study features.
        open(post, "/v1/library/practice/grade-and-diagnose", "friend never enters a batch; friend route, friend audit"),
        open(post, "/v1/library/practice/chapter-open-question", "FriendScopedBatchTransport; no owner ledger row"),
        open(post, "/v1/library/course/align", "READER_SELECTION on the friend route; no owner ledger row"),
        open(post, "/v1/library/course/topic-pack", "cache scoped to friend:<id>; friend route"),
        open(post, "/v1/library/course/vision", "friend model on the friend route; friend audit"),
        open(post, "/v1/library/course/mock/generate", "friend planner and FriendScopedBatchTransport; no owner ledger row"),
        open(post, "/v1/library/booklist/align", "FriendScopedBatchTransport; no owner ledger row"),
        open(post, "/v1/library/concepts/sayings", "friendId/friendDevice on the Bridge request"),
        open(post, "/api/books/{id}/modes/{mode}", "study modes on the friend route; cache keyed by content"),
        open(post, "/api/books/{id}/book-map/batch", "book map on the friend route"),
        // Books stored per account (UnifiedBookScope).
        open(post, "/v1/books/upload", "stored under this account's scope"),
        open(get, "/v1/books", "lists only this account's books"),
        open(get, "/v1/books/{id}/status", "own book only", Regex("/v1/books/$SHELF_BOOK/status")),
        open(get, "/v1/books/{id}/original", "own book only", Regex("/v1/books/$SHELF_BOOK/original")),
        open(get, "/v1/books/{id}/artifact", "own book only", Regex("/v1/books/$SHELF_BOOK/artifact")),
        open(delete, "/v1/books/{id}", "own book only", Regex("/v1/books/$SHELF_BOOK")),
        // Learning sync, only while the server has it switched on.
        open(get, "/learning-sync/profile", "this account's sync store", sync = true),
        open(get, "/learning-sync/changes", "this account's sync store", sync = true),
        open(get, "/learning-sync/record", "this account's sync store", sync = true),
        open(get, "/learning-sync/snapshot", "this account's sync store", sync = true),
        open(get, "/learning-sync/chunk", "this account's sync store", sync = true),
        open(post, "/learning-sync/push", "this account's sync store", sync = true),

        // Owner only: these read or write the owner's course ledger, catalog, devices or AI-call table.
        owner("/v1/courses/{course}/**", "owner's course ledger: today, mainline, mocks, events, reader, sources"),
        owner("/v1/hello", "owner ledger handshake"),
        owner("/v1/commands", "writes the owner ledger"),
        owner("/v1/ws", "owner ledger and tutor socket"),
        owner("/offline-pack", "owner question bank"),
        owner("/v1/usage", "owner usage observation"),
        owner("/v1/prewarm", "owner lesson preparation"),
        owner("/v1/math", "owner listener only"),
        owner("/v1/library/companion/ledger", "site-wide AI-call ledger"),
        owner("/v1/library/course/exam-point-drill", "batch Haiku generation without a friend route", "考点出题只在自己的电脑上做。"),
        // Administration (devices, pairing codes, Sable users, book migration) is not called by any client and is
        // deliberately absent: friends keep getting an empty 404 there.
    )

    fun find(method: HttpMethod, path: String): ApiRoute? = routes.firstOrNull { it.matches(method, path) }
}

internal enum class PublicApiGate { OPEN, OWNER_ONLY, UNKNOWN }

internal fun publicApiGate(call: ApplicationCall): PublicApiGate {
    val path = call.request.path()
    if (path.contains("..") || path.contains('%')) return PublicApiGate.UNKNOWN
    val route = PublicApiTable.find(call.request.httpMethod, path) ?: return PublicApiGate.UNKNOWN
    return when (route.access) {
        ApiAccess.OWNER -> PublicApiGate.OWNER_ONLY
        ApiAccess.SIGNED_IN ->
            if (!route.needsLearningSync || call.application.attributes.getOrNull(learningSyncActive) == true) PublicApiGate.OPEN
            else PublicApiGate.UNKNOWN
    }
}

internal fun friendPath(call: ApplicationCall): Boolean = publicApiGate(call) == PublicApiGate.OPEN

internal const val OWNER_ONLY_MESSAGE = "这个功能只在自己的电脑上用。"

internal suspend fun ApplicationCall.respondOwnerOnly() {
    val message = PublicApiTable.find(request.httpMethod, request.path())?.ownerMessage ?: OWNER_ONLY_MESSAGE
    respondText(
        buildJsonObject { put("code", "owner_only"); put("error", message) }.toString(),
        ContentType.Application.Json,
        HttpStatusCode.Forbidden,
    )
}
