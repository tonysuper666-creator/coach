package com.coach.desktop

import com.coach.desktop.books.BookSourceRegistrationPort

/** Reuse the authenticated pose path; this never installs an owner mapping or trusts a client receipt. */
internal fun pairedBookSourceRegistration(connection: WindowsBookPracticeConnection) = BookSourceRegistrationPort { request ->
    connection.selectCourse(request.book.id, request.course)
    connection.prepare(request.book, request.lesson, request.practice)
}
