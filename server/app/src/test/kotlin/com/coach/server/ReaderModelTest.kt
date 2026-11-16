package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.scope.VisibleScope
import com.coach.store.LedgerStore
import com.coach.store.SourcePage
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonArray
import kotlin.test.*

class ReaderModelTest {
    private val isolated = mapOf("COACH_TRAFFIC" to "test", "COACH_READER_MODEL" to BridgeClient.CODEX,
        "COACH_BRIDGE_URL" to "http://127.0.0.1:41001")

    @Test fun defaultsStayUnchangedAndOverridesRequireAnExplicitPrivateTestBridge() {
        assertEquals(BridgeClient.CLAUDE, ReaderModel.environment(emptyMap()))
        assertEquals(BridgeClient.CODEX, ReaderModel.environment(isolated))
        for (env in listOf(isolated - "COACH_TRAFFIC", isolated - "COACH_BRIDGE_URL",
            isolated + ("COACH_TRAFFIC" to "user"), isolated + ("COACH_MANAGE_SERVICES" to "1"),
            isolated + ("COACH_READER_MODEL" to "unknown")) +
            listOf("http://127.0.0.1:8790", "http://localhost:8791", "http://127.0.0.1:18791",
                "http://100.88.12.89:41001", "https://127.0.0.1:41001", "http://127.0.0.1:41001/?key=x")
                .map { isolated + ("COACH_BRIDGE_URL" to it) }) {
            assertFailsWith<IllegalArgumentException> { ReaderModel.environment(env) }
        }
    }

    @Test fun explicitCodexSelectionAcceptsHonestCitationsAndStillRejectsVendorMismatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, LedgerStore(Files.createTempDirectory("reader-model-synthetic-")))
        StubTutor().use { stub ->
            try {
                val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)),
                    readSources = { pack, _ -> pack }, model = ReaderModel.environment(isolated))
                val pages = listOf(SourcePage("synthetic-book", 4, "Synthetic pears grow in the orchard."))
                val payload = """{"paragraphs":[{"text":"合成示例说明梨生长在果园。","anchors":["S1"]}]}"""
                suspend fun explain(id: String) = reader.explain("d", "c", json("requestId" to id, "kind" to "explain"),
                    "Synthetic pears", pages, VisibleScope.learner())
                stub.stream(1, payload, StubTutor.terminal("complete").replace(BridgeClient.CLAUDE, BridgeClient.CODEX), StubTutor.DONE)
                assertEquals(1, explain("aa").getValue("sources").jsonArray.size)
                assertTrue(stub.requestBodies.single().contains(BridgeClient.CODEX))
                stub.stream(1, payload, StubTutor.terminal("complete"), StubTutor.DONE)
                assertTrue(explain("ab").getValue("sources").jsonArray.isEmpty())
                assertTrue(actor.execute { events("c") }.isEmpty())
            } finally { actor.close(); scope.cancel() }
        }
    }
}
