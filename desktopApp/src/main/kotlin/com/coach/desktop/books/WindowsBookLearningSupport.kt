package com.coach.desktop.books

/** The caller supplies both transport and authorization; the default denies all private-book output. */
internal fun windowsBookLearningSupport(
    library: TextbookLibrary,
    transport: OwnedBookCliTransport,
    policy: OwnedBookOutboundPolicy = DenyOwnedBookOutbound,
): BookLearningSupport = OwnedBookLearningProvider(library, OwnedBookCliExecutor(transport, policy))
