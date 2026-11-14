package com.coach.desktop

/** The production server and its bridge. A development or measurement run never talks to them (TON-1142; the Android card packages follow the same rule, TON-1149). */
internal val productionPorts = setOf(18791, 8791, 8790)

private val devSwitches = listOf("COACH_AUTOPLAY", "COACH_RENDER_STATE", "COACH_NOTES_VERIFY_DIR", "COACH_ACCEPTANCE_REPLAY", "COACH_AGENT_RUN_ID")
private val devProperties = listOf("coach.autoExitSeconds", "coach.capturePath", "coach.summaryCapturePath", "coach.captures", "coach.captureDir")

/** Packaged launches stay on the user's own server. Gradle runs and measurement switches do not. */
internal fun developmentLaunch(env: (String) -> String?, property: (String) -> String?): Boolean {
    fun set(name: String) = !env(name).isNullOrBlank()
    return property("jpackage.app-path") == null || devSwitches.any(::set) || devProperties.any { !property(it).isNullOrBlank() }
}

/** Shared address guard for development clients which do not own a local ledger. */
internal fun refuseProductionPort(address: String): String? {
    val port = runCatching { java.net.URI(desktopServerUrl(address)).port }.getOrNull()
        ?: return "Refusing development run: COACH_URL is not a valid URL."
    if (port in productionPorts) return "Refusing development run: COACH_URL uses production port $port; start an isolated test server on its own port."
    return null
}

/**
 * The packaged app (jpackage sets `jpackage.app-path`) is the user's real client and keeps its defaults. Any other launch — `:desktopApp:run`, or anything
 * carrying a replay/measurement switch — must name its own server, token file and ledger directory; with the defaults it would sync into the real
 * ledger and report usage to production. Returns the reason to refuse, or null when the launch may go ahead.
 */
internal fun refuseProductionDefaults(env: (String) -> String?, property: (String) -> String?): String? {
    if (!developmentLaunch(env, property)) return null
    fun set(name: String) = !env(name).isNullOrBlank()
    val missing = listOf("COACH_URL", "COACH_TOKEN_FILE", "COACH_CLIENT_DIR").filterNot(::set)
    if (missing.isNotEmpty()) return "Refusing production defaults for a development run: set ${missing.joinToString()} to an isolated test server, token file and ledger directory."
    return refuseProductionPort(env("COACH_URL")!!)
}
