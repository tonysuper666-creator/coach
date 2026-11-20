package com.coach.client

import com.coach.domain.LedgerJson
import com.coach.domain.num
import com.coach.domain.str
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject

class SableLoginError(message: String) : Exception(message)

const val SABLE_ORIGIN = "https://chat.tonysuper666.com"
private const val MAX_POLL_BACKOFF_MS = 15_000L

internal suspend fun sableLogin(
    client: HttpClient, friendUrl: String, open: suspend (uri: String, code: String) -> Unit,
    issuer: String = SABLE_ORIGIN,
    pause: suspend (Long) -> Unit = ::delay,
): PairedCredentials {
    validateCoachBaseUrl(friendUrl)
    require(issuer == SABLE_ORIGIN || (Url(issuer).host == "127.0.0.1" && Url(issuer).protocol == URLProtocol.HTTP))
    val server = Url(friendUrl)
    require(server.port != 18791) { "Invalid friend service port" }
    require(server.protocol == URLProtocol.HTTPS || server.host == "127.0.0.1" || server.host == "localhost")
    val failure = "暂时连不上登录服务，请稍后再试。"
    val expired = "授权码已过期，请重新登录。"
    val response = client.submitForm("$issuer/api/device/code", parametersOf("client_id", "learningcoach")) { header("x-hermes-web", "1") }
    if (response.status != HttpStatusCode.OK) throw SableLoginError(failure)
    val grant = LedgerJson.parseToJsonElement(response.bodyAsText()).jsonObject
    val uri = Url(grant.str("verification_uri_complete"))
    val origin = Url(issuer)
    require(uri.protocol == origin.protocol && uri.host == origin.host && uri.port == origin.port &&
        uri.user == null && uri.password == null && uri.encodedPath == "/link")
    val code = grant.str("user_code")
    require(code.matches(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")))
    val form = parameters {
        append("client_id", "learningcoach")
        append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        append("device_code", grant.str("device_code").also { require(it.isNotBlank()) })
    }
    val lifetime = grant.num("expires_in")
    require(lifetime in 1..600)
    var baseInterval = grant.num("interval", 5).also { require(it in 1..600) } * 1000L
    var nextDelay = baseInterval
    val token = withTimeoutOrNull(lifetime * 1000L) {
        open(uri.toString(), code)
        var access: String? = null
        while (access == null) {
            pause(nextDelay)
            val (status, raw) = try {
                val reply = client.submitForm("$issuer/api/device/token", form) { header("x-hermes-web", "1") }
                reply.status to reply.bodyAsText()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                nextDelay = minOf(nextDelay * 2, maxOf(baseInterval, MAX_POLL_BACKOFF_MS))
                continue
            }
            nextDelay = baseInterval
            val body = LedgerJson.parseToJsonElement(raw).jsonObject
            if (status == HttpStatusCode.OK) {
                access = body.str("access_token").also { require(it.isNotBlank()) }
            } else when (body.str("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    baseInterval += 5_000L
                    nextDelay = baseInterval
                }
                "access_denied" -> throw SableLoginError("你已拒绝授权。")
                "expired_token" -> throw SableLoginError(expired)
                else -> throw SableLoginError(failure)
            }
        }
        access
    } ?: throw SableLoginError(expired)
    val exchanged = client.post("${friendUrl.trimEnd('/')}/v1/public/sable") { bearerAuth(token) }
    if (exchanged.status == HttpStatusCode.Unauthorized) throw SableLoginError("此账号的授权已失效或已停用。")
    if (exchanged.status != HttpStatusCode.OK) throw SableLoginError(failure)
    val body = LedgerJson.parseToJsonElement(exchanged.bodyAsText()).jsonObject
    require(body.str("role") == "friend" && body.str("token").length == 43 && body.str("deviceId").isNotBlank())
    return PairedCredentials(body.str("deviceId"), body.str("token"), "friend")
}
