package com.coach.protocol

/** The HTTP client must leave time for the server to return its own timeout explanation. */
object ReaderTimeoutPolicy {
    const val DEFAULT_TOTAL_MS: Long = 180_000
    // Keep this allowance above COACH_READER_TOTAL_TIMEOUT_MS when overriding the server setting.
    const val CLIENT_REQUEST_MS: Long = DEFAULT_TOTAL_MS + 15_000
}
