package com.coach.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

internal expect fun coachClientVersion(): String?

private val clientVersion = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}")

/** Sent only when the desktop build has a safe version. Tests that leave it unset keep their headers. */
internal fun coachVersionOrNull(): String? = coachClientVersion()?.trim()?.takeIf(clientVersion::matches)

internal fun HttpRequestBuilder.coachVersionHeader() {
    coachVersionOrNull()?.let { header("X-Coach-Version", it) }
}
