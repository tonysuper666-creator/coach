package com.coach.desktop.reader

import jdk.jfr.FlightRecorder
import jdk.jfr.Event
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import jdk.jfr.StackTrace
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Name("coach.test.DraftPut")
@StackTrace(false)
class DraftPutEvent : Event() {
    @JvmField var observer: Long = 0
    @JvmField var call: Int = 0
}

/** Test-only observer: delegates unchanged, enriches the original failure, and never logs note/quote text. */
class DraftSaveDiagnostics(private val recordingFactory: () -> Recording = { Recording() }) : TestWatcher() {
    private class Put(val id: Int, val store: Int) {
        val start: Instant = Instant.now()
        val thread: Long = Thread.currentThread().id
        @Volatile var end: Instant? = null
        @Volatile var returned: Boolean? = null
        @Volatile var thrown: Throwable? = null
    }
    private data class ObservedException(val time: Instant, val thread: Long?, val type: String, val message: String?, val stage: String?)
    private data class Span(val start: Instant, val end: Instant, val thread: Long?)
    private companion object { val observerSequence = AtomicLong() }
    private val observer = observerSequence.incrementAndGet()
    private val sequence = AtomicInteger()
    private var spans: Map<Int, Span> = emptyMap()
    private val puts = CopyOnWriteArrayList<Put>()
    private val stores = CopyOnWriteArrayList<JsonObject>()
    private val collectorErrors = CopyOnWriteArrayList<String>()
    private var recording: Recording? = null
    private var snapshot: JsonObject? = null

    override fun starting(description: Description) {
        try {
            if (!FlightRecorder.isAvailable()) { collectorErrors += "JFR unavailable"; return }
            recording = recordingFactory()
            recording!!.apply {
                enable("jdk.JavaExceptionThrow").withStackTrace()
                enable(DraftPutEvent::class.java).withThreshold(java.time.Duration.ZERO)
                start()
            }
        } catch (error: Exception) {
            collectorErrors += "${error.javaClass.name}: ${error.message}"
            closeRecording()
        }
    }

    fun observe(delegate: NoteDraftStore, directory: Path): NoteDraftStore {
        val id = stores.size
        stores += buildJsonObject {
            put("id", id); put("delegateClass", delegate.javaClass.name)
            put("scopeBlank", delegate.scope.isBlank()); put("directory", directory.toAbsolutePath().normalize().toString())
        }
        return object : NoteDraftStore by delegate {
            override fun put(draft: StoredDraft): Boolean {
                val call = Put(sequence.getAndIncrement(), id).also { puts += it }
                val span = DraftPutEvent().also { it.observer = observer; it.call = call.id; it.begin() }
                try { return delegate.put(draft).also { call.returned = it } }
                catch (error: Throwable) { call.thrown = error; throw error }
                finally { span.end(); span.commit(); call.end = Instant.now() }
            }
        }
    }

    /** Freeze before the unchanged assertion, without rendering, waiting, or reading draft files. */
    fun atAssertion(session: ReaderSession, shown: String) {
        snapshot = buildJsonObject {
            put("at", Instant.now().toString()); put("storeInjected", session.drafts != null)
            put("scopeBlank", session.drafts?.scope?.isBlank()); put("persistFailed", session.persistFailed)
            put("draftsKept", session.draftsKept()); put("noteSaving", session.noteSaving)
            put("shown", shown)
        }
    }

    override fun failed(error: Throwable, description: Description) { enrich(error, description.methodName) }
    override fun finished(description: Description) { closeRecording() }

    internal fun enrich(error: Throwable, label: String = "diagnostic-contract") {
        // Collector trouble must not replace, swallow, or change the original assertion failure.
        try {
            val directory = Path.of(System.getProperty("coach.draftDiagnostics.dir", "build/reports/draft-diagnostics"))
                .toAbsolutePath().normalize().resolve(label.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "-" + System.nanoTime())
            val events = try {
                Files.createDirectories(directory)
                collect(directory.resolve("exceptions.jfr"))
            } catch (collector: Exception) {
                collectorErrors += "${collector.javaClass.name}: ${collector.message}"
                emptyList()
            }
            fun json(event: ObservedException) = buildJsonObject {
                put("at", event.time.toString()); put("thread", event.thread); put("type", event.type)
                put("message", event.message); put("messageSource", "JFR Throwable constructor field; computed getMessage may be unavailable")
                put("stage", event.stage)
            }
            val report = buildJsonObject {
                put("snapshotBeforeAssertion", snapshot ?: JsonNull)
                put("stores", JsonArray(stores))
                put("correlationClock", "JFR put span and exception clock; wall start/end are informational")
                put("puts", JsonArray(puts.map { call ->
                    val end = call.end
                    val returned = call.returned
                    val span = spans[call.id]
                    val matching = if (span == null) emptyList() else events.filter {
                        it.thread == span.thread && it.time >= span.start && it.time <= span.end
                    }
                    val candidate = matching.lastOrNull {
                        it.type.startsWith("java.nio.file.") || it.type.startsWith("java.io.") ||
                            it.type == "java.lang.SecurityException" || it.type == "java.lang.UnsupportedOperationException"
                    }
                    buildJsonObject {
                        put("id", call.id); put("store", call.store); put("start", call.start.toString()); put("end", end?.toString())
                        put("jfrStart", span?.start?.toString()); put("jfrEnd", span?.end?.toString())
                        put("thread", call.thread); put("returned", returned); put("inFlightAtReport", end == null)
                        put("escapedType", call.thrown?.javaClass?.name); put("escapedMessage", call.thrown?.message)
                        put("failureStage", when {
                            returned == true -> "none (put returned true)"
                            call.thrown != null -> "delegate.put (exception escaped)"
                            else -> candidate?.stage ?: "unknown"
                        })
                        put("recordedExceptions", JsonArray(matching.map(::json)))
                    }
                }))
                // Never silently assign an out-of-window/thread event to a put or call missing data 'no exception'.
                put("allStoreExceptions", JsonArray(events.map(::json)))
                put("collectorErrors", JsonArray(collectorErrors.map(::JsonPrimitive)))
                put("nativeErrorCode", JsonNull); put("historicalCauseProven", false)
            }
            val file = directory.resolve("failure.json")
            val artifact = try { Files.writeString(file, report.toString()); file.toString() }
                catch (write: Exception) { "unavailable: ${write.javaClass.name}: ${write.message}" }
            error.addSuppressed(AssertionError("draft-save-diagnostics=$report\nartifact=$artifact"))
        } catch (collector: Exception) {
            error.addSuppressed(AssertionError("draft-save-diagnostics unavailable: ${collector.javaClass.name}: ${collector.message}"))
        } finally { closeRecording() }
    }

    private fun collect(file: Path): List<ObservedException> {
        val current = recording ?: return emptyList()
        if (current.state == RecordingState.RUNNING) current.stop()
        current.dump(file)
        val captured = RecordingFile.readAllEvents(file)
        spans = captured.filter { it.eventType.name == "coach.test.DraftPut" && it.getLong("observer") == observer }
            .associate { it.getInt("call") to Span(it.startTime, it.endTime, it.thread?.javaThreadId) }
        return captured.filter { it.eventType.name == "jdk.JavaExceptionThrow" }.mapNotNull { event ->
            val stack = event.stackTrace?.frames.orEmpty().map { it.method.type.name + "." + it.method.name }
            if ("com.coach.desktop.reader.FileNoteDraftStore.put" !in stack) return@mapNotNull null
            val stage = when {
                "java.nio.file.Files.move" in stack -> "atomic-replace"
                "java.nio.file.Files.writeString" in stack -> "write-temp"
                "java.nio.file.Files.createTempFile" in stack -> "create-temp"
                "java.nio.file.Files.createDirectories" in stack -> "ensure-directory"
                else -> null
            }
            ObservedException(event.startTime, event.thread?.javaThreadId, event.getClass("thrownClass").name, event.getString("message"), stage)
        }
    }
    private fun closeRecording() {
        try { recording?.close() } catch (_: Exception) { /* only a diagnostic resource */ }
        recording = null
    }
}
