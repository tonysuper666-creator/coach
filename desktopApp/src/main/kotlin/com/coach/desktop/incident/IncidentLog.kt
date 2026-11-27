package com.coach.desktop.incident

import com.coach.desktop.books.IncidentHooks
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide incident log. Writes stay on this machine until the user sends them. */
internal object IncidentLog {
    private val storeLock = Any()
    private var store: IncidentStore? = null
    private var sessionStore: IncidentStore? = null
    private val crashHandlerInstalled = AtomicBoolean(false)
    @Volatile var suppressCleanExit = false
    @Volatile private var offer: PendingOffer? = null

    fun directory(): Path = current().root

    fun startSession(home: Path, environment: (String) -> String? = System::getenv) {
        val root = incidentDirectory(home, environment)
        val active = synchronized(storeLock) {
            val existing = store
            if (existing != null && existing.root == root) existing
            else IncidentStore(root).also { store = it }
        }
        IncidentHooks.install(
            onImportFailure = { active.recordImportFailure(it) },
            onSyncFailure = { active.recordSyncFailure(it) },
        )
        offer = active.beginSession()
        synchronized(storeLock) {
            sessionStore = active
        }
        if (shutdownHook.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread({
                markStartedSessionClean()
            }, "incident-clean-exit"))
        }
    }

    internal fun markStartedSessionClean() {
        if (suppressCleanExit) return
        val started = synchronized(storeLock) { sessionStore } ?: return
        runCatching { started.markClean() }
    }

    internal fun resetForTest() {
        synchronized(storeLock) {
            store = null
            sessionStore = null
            offer = null
            suppressCleanExit = false
        }
    }

    fun currentOffer(): PendingOffer? = offer

    fun note(operation: String) = current().note(operation)

    fun recordImportFailure(detail: String) = current().recordImportFailure(detail)

    fun recordSyncFailure(detail: String) = current().recordSyncFailure(detail)

    fun record(kind: String, operation: String, detail: String? = null, error: Throwable? = null) =
        current().record(kind, operation, detail, error)

    fun decline(pending: PendingOffer) {
        current().decline(pending)
        if (offer === pending) offer = null
    }

    fun markSent(pending: PendingOffer) {
        current().markSent(pending)
        if (offer === pending) offer = null
    }

    fun clear() = current().clear()

    fun openFolder() {
        val dir = current().root
        java.nio.file.Files.createDirectories(dir)
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) desktop.open(dir.toFile())
    }

    fun installCrashHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == "incident-clean-exit") return@setDefaultUncaughtExceptionHandler
            try {
                suppressCleanExit = System.getenv("COACH_INCIDENT_TEST") == "crash" || thread.name.startsWith("AWT-EventQueue")
                current().record("crash", "uncaught", error.message, error)
            } catch (_: Throwable) {
                // A failure while recording must not hide the original crash.
            }
            if (suppressCleanExit) Runtime.getRuntime().halt(70)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Test switch: write a redacted crash record, then stop the process without a clean mark. */
    fun crashForTest() {
        Thread({
            throw RuntimeException(com.coach.domain.IncidentRedactor.syntheticBlob())
        }, "incident-test-crash").start()
    }

    private fun current(): IncidentStore = synchronized(storeLock) {
        store ?: IncidentStore(incidentDirectory(Path.of(System.getProperty("user.home")), System::getenv)).also { store = it }
    }

    private val shutdownHook = AtomicBoolean(false)
}

internal fun incidentDirectory(home: Path, environment: (String) -> String?): Path {
    val override = environment("COACH_INCIDENT_DIR")?.takeIf { it.isNotBlank() }
    return if (override != null) Path.of(override).toAbsolutePath().normalize()
    else home.resolve("Coach").resolve("incidents").toAbsolutePath().normalize()
}
