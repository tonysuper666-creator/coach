package com.coach.desktop.books

/** A lookup request only. A paired client cannot approve content, mappings, keys or attempts. */
internal data class BookSourceRegistrationRequest(
    val course: String, val book: Textbook, val lesson: BookLesson, val practice: Practice,
)

internal fun interface BookSourceRegistrationPort {
    suspend fun prepare(request: BookSourceRegistrationRequest): BookPracticeSession?
}

internal val UnconnectedBookSourceRegistration = BookSourceRegistrationPort { null }
