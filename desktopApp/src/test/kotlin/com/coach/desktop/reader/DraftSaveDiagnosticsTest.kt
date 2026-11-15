package com.coach.desktop.reader

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DraftSaveDiagnosticsTest {
    @org.junit.Rule @JvmField val diagnostics = DraftSaveDiagnostics()
    private val scope = "diagnostic:contract"
    private fun draft() = StoredDraft(DraftIdentity(scope, "a", "v1", 1, 0, 1, "0".repeat(64)), "id", "A", "do-not-log-my-note", 0)
    private fun report(error: Throwable): JsonObject {
        val info = error.suppressed.single().message!!.substringAfter("draft-save-diagnostics=").substringBefore("\nartifact=")
        assertFalse(info.contains("do-not-log-my-note"))
        return Json.parseToJsonElement(info).jsonObject
    }

    @Test fun aCaughtFilesystemFailureIsCorrelatedWithoutReplacingTheAssertion() {
        val blocker = Files.writeString(Files.createTempDirectory("diagnostic-contract-").resolve("not-a-directory"), "synthetic")
        val store = diagnostics.observe(FileNoteDraftStore(blocker, scope), blocker)
        assertFalse(store.put(draft()))
        val session = ReaderSession(ReaderFixture("synthetic", emptyList(), emptyList()), 1100, drafts = store)
        diagnostics.atAssertion(session, "synthetic visible failure")
        val original = AssertionError("original strict assertion")
        diagnostics.enrich(original)
        assertEquals("original strict assertion", original.message)
        val info = report(original)
        assertTrue(info["snapshotBeforeAssertion"]!!.jsonObject["storeInjected"]!!.jsonPrimitive.boolean)
        assertFalse(info["snapshotBeforeAssertion"]!!.jsonObject["scopeBlank"]!!.jsonPrimitive.boolean)
        val call = info["puts"]!!.jsonArray.single().jsonObject
        assertFalse(call["returned"]!!.jsonPrimitive.boolean)
        assertEquals("ensure-directory", call["failureStage"]!!.jsonPrimitive.content)
        assertTrue(call["recordedExceptions"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "java.nio.file.FileAlreadyExistsException" })
        assertEquals("synthetic", Files.readString(blocker))
    }

    @Test fun anEscapingExceptionAndItsMessageAreRethrownUnchanged() {
        val failure = java.io.IOException("synthetic opaque failure")
        val delegate = object : NoteDraftStore {
            override val scope = this@DraftSaveDiagnosticsTest.scope
            override fun put(draft: StoredDraft): Boolean = throw failure
            override fun get(identity: DraftIdentity): StoredDraft? = null
            override fun listFor(doc: String, version: String, pdfPage: Int) = emptyList<StoredDraft>()
            override fun remove(identity: DraftIdentity) {}
        }
        val store = diagnostics.observe(delegate, Files.createTempDirectory("diagnostic-throw-"))
        assertSame(failure, assertFailsWith<java.io.IOException> { store.put(draft()) })
        val original = AssertionError("kept assertion")
        diagnostics.enrich(original)
        val call = report(original)["puts"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, call["returned"])
        assertEquals("java.io.IOException", call["escapedType"]!!.jsonPrimitive.content)
        assertEquals("synthetic opaque failure", call["escapedMessage"]!!.jsonPrimitive.content)
        assertEquals("kept assertion", original.message)
    }

    @Test fun failedCollectorStillAttachesPutDataThroughTheJUnitRule() {
        val observer = DraftSaveDiagnostics { throw IllegalStateException("synthetic recorder unavailable") }
        val delegate = object : NoteDraftStore {
            override val scope = this@DraftSaveDiagnosticsTest.scope
            override fun put(draft: StoredDraft) = false
            override fun get(identity: DraftIdentity): StoredDraft? = null
            override fun listFor(doc: String, version: String, pdfPage: Int) = emptyList<StoredDraft>()
            override fun remove(identity: DraftIdentity) {}
        }
        val original = AssertionError("original rule assertion")
        val statement = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                observer.observe(delegate, Files.createTempDirectory("diagnostic-no-recorder-")).put(draft())
                throw original
            }
        }
        val description = org.junit.runner.Description.createTestDescription(javaClass, "synthetic-rule-failure")
        assertSame(original, assertFailsWith<AssertionError> { observer.apply(statement, description).evaluate() })
        val info = report(original)
        assertFalse(info["puts"]!!.jsonArray.single().jsonObject["returned"]!!.jsonPrimitive.boolean)
        assertEquals("unknown", info["puts"]!!.jsonArray.single().jsonObject["failureStage"]!!.jsonPrimitive.content)
        assertTrue(info["collectorErrors"]!!.jsonArray.any { it.jsonPrimitive.content.contains("synthetic recorder unavailable") })
        assertEquals("original rule assertion", original.message)
    }
}
