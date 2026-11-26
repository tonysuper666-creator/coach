package com.coach.client

internal actual fun coachClientVersion(): String? =
    System.getProperty("coach.app.version")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
