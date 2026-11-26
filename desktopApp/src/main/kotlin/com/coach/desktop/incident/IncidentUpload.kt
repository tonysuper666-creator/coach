package com.coach.desktop.incident

import com.coach.desktop.DesktopIdentity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal enum class IncidentUploadResult { Sent, NeedLogin, TooBig, Limited, NotSent }

/** Sends the exact preview text. Called only after the user clicks 发送. */
internal object IncidentUpload {
    fun send(identity: DesktopIdentity, body: String): IncidentUploadResult {
        val request = HttpRequest.newBuilder(URI.create(identity.serverUrl.trimEnd('/') + "/v1/incidents"))
            .timeout(Duration.ofSeconds(20))
            .header("X-Coach-Device", identity.credentials.deviceId)
            .header("Authorization", "Bearer ${identity.credentials.token}")
            .header("Content-Type", "text/plain; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        return try {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            when (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
                204, 200 -> IncidentUploadResult.Sent
                401, 403 -> IncidentUploadResult.NeedLogin
                413 -> IncidentUploadResult.TooBig
                429 -> IncidentUploadResult.Limited
                else -> IncidentUploadResult.NotSent
            }
        } catch (_: Exception) {
            IncidentUploadResult.NotSent
        }
    }
}
