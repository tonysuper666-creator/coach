package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class LocalCodexBookTransportTest {
    private fun fake(f: OwnedBookConsentTest.Fixture, mode: String, timeout: Long = 5000): LocalCodexBookTransport {
        return SyntheticIsolatedCodex(f, mode).transport(timeout)
    }

    @Test fun exactArgvEmptyWorkingDirectoryTemporaryOutputAndSubscriptionLogin() = runBlocking {
        val f = OwnedBookConsentTest.Fixture()
        val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, "success"))
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        assertIs<BookLearningReply.Text>(support.explain(f.explain()))
        val argv = Files.readString(f.root.resolve("argv.json"))
        for (flag in listOf("exec", "gpt-6.1-sol", "model_reasoning_effort=low", "--ephemeral", "--skip-git-repo-check", "read-only", "--ignore-user-config", "--json", "-o", "web_search=disabled", "features.multi_agent_v2.disable_native_tools=true") +
            listOf("shell_tool", "unified_exec", "apps", "plugins", "browser_use", "computer_use", "in_app_browser", "image_generation", "view_image", "multi_agent").map { "features.$it=false" })
            assertTrue(argv.contains(flag), flag)
        assertFalse(Files.exists(Path.of(Files.readString(f.root.resolve("cwd")))))
        val api = ConsentedBookLearningSupport(f.library, f.store, fake(f, "apikey"))
        assertEquals(LocalCodexProblem.LOGIN.message, assertIs<BookLearningReply.Unavailable>(api.explain(f.explain())).message)
        val model = ConsentedBookLearningSupport(f.library, f.store, fake(f, "model"))
        assertEquals(LocalCodexProblem.MODEL.message, assertIs<BookLearningReply.Unavailable>(model.explain(f.explain())).message)
    }

    @Test fun loginAndExecDoNotInheritRustLogFiltering() = runBlocking {
        val f = OwnedBookConsentTest.Fixture()
        val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, "environment"))
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        assertIs<BookLearningReply.Text>(support.explain(f.explain()))
        for (phase in listOf("login", "exec")) {
            assertEquals("{\"has_RUST_LOG\": false}", Files.readString(f.root.resolve("$phase-environment.json")), phase)
        }
        println("SYNTHETIC_ENV parent_rust_log_off=${System.getenv("RUST_LOG") == "off"} login_has_rust_log=false exec_has_rust_log=false")
    }

    @Test fun toolUnknownMalformedAndIncompleteEventsNeverPublishTheOtherwiseValidAnswer() = runBlocking {
        for (mode in listOf("tool-command_execution", "tool-mcp_tool_call", "tool-web_search", "tool-future_tool",
            "future-event", "late-tool", "empty", "malformed", "truncated")) {
            val f = OwnedBookConsentTest.Fixture()
            val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, mode))
            support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
            assertEquals(LocalCodexProblem.FAILED.message,
                assertIs<BookLearningReply.Unavailable>(support.explain(f.explain()), mode).message, mode)
            assertFalse(Files.exists(Path.of(Files.readString(f.root.resolve("cwd")))))
        }
    }

    @Test fun stderrOnlyToolTracesNeverPublishEvenWithCleanJsonEventsAndValidAnswer() = runBlocking {
        for (mode in listOf("stderr-collab", "stderr-router", "stderr-late-collab", "stderr-split-router", "stderr-benign")) {
            val f = OwnedBookConsentTest.Fixture()
            val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, mode))
            support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
            val reply = support.explain(f.explain())
            if (mode == "stderr-benign") assertIs<BookLearningReply.Text>(reply)
            else assertEquals(LocalCodexProblem.FAILED.message, assertIs<BookLearningReply.Unavailable>(reply, mode).message, mode)
            assertFalse(Files.exists(Path.of(Files.readString(f.root.resolve("cwd")))))
        }
    }

    @Test fun revokeDoesNotBlockItsCallingThreadWhileStubbornProcessIsReaped() = runBlocking {
        val f = OwnedBookConsentTest.Fixture()
        val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, "stubborn", 30_000))
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        val request = async { support.explain(f.explain()) }
        withTimeout(8000) { while (!Files.exists(f.root.resolve("pids"))) delay(10) }
        val handles = Files.readString(f.root.resolve("pids")).split(',').mapNotNull { ProcessHandle.of(it.toLong()).orElse(null) }
        try {
            val measured = CompletableDeferred<Pair<Long, List<Job>>>()
            val caller = Thread {
                try {
                    val start = System.nanoTime()
                    val jobs = f.store.revoke(f.a.id) // Same locked cancellation used by support.revoke.
                    measured.complete((System.nanoTime() - start) / 1_000_000 to jobs)
                } catch (error: Throwable) { measured.completeExceptionally(error) }
            }
            caller.start()
            val (millis, jobs) = withTimeout(8000) { measured.await() }
            caller.join()
            jobs.joinAll()
            assertNotNull(assertIs<BookLearningReply.Unavailable>(request.await()).needsConsent)
            println("SYNTHETIC_REVOKE caller_block_ms=$millis")
            assertTrue(millis <= 50, "revoke blocked caller for ${millis}ms")
            assertTrue(handles.none { it.isAlive })
        } finally { handles.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }; request.cancelAndJoin() }
    }

    @Test fun parentExitWithInheritedOutputDoesNotDelayTimeoutAndTrackedChildIsReaped() = runBlocking {
        val f = OwnedBookConsentTest.Fixture()
        val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, "parentexit", 1500))
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        val start = System.nanoTime()
        val request = async { support.explain(f.explain()) }
        withTimeout(8000) { while (!Files.exists(f.root.resolve("pids"))) delay(10) }
        val handles = Files.readString(f.root.resolve("pids")).split(',').mapNotNull { ProcessHandle.of(it.toLong()).orElse(null) }
        try {
            // The AppContainer helper closes its Job on parent exit, so inherited pipes close
            // immediately. An exit without events is a protocol failure rather than a timeout.
            assertEquals(LocalCodexProblem.FAILED.message, assertIs<BookLearningReply.Unavailable>(request.await()).message)
            val millis = (System.nanoTime() - start) / 1_000_000
            println("SYNTHETIC_PARENT_EXIT elapsed_ms=$millis survivors=${handles.count { it.isAlive }}")
            assertTrue(millis < 2500, "inherited pipe delayed timeout: ${millis}ms")
            assertTrue(handles.none { it.isAlive })
        } finally { handles.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }; request.cancelAndJoin() }
    }

    @Test fun timeoutAndRevokeReapTheActualAdapterProcessTreeBeforeReturning() = runBlocking {
        for (mode in listOf("timeout", "cancel")) {
            val f = OwnedBookConsentTest.Fixture()
            val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, mode, if (mode == "timeout") 1500 else 30_000))
            support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
            val reply = async { support.explain(f.explain()) }
            withTimeout(8000) { while (!Files.exists(f.root.resolve("pids"))) delay(20) }
            val handles = Files.readString(f.root.resolve("pids")).split(',').mapNotNull { ProcessHandle.of(it.toLong()).orElse(null) }
            try {
                if (mode == "cancel") support.revoke(f.a.id)
                val result = assertIs<BookLearningReply.Unavailable>(reply.await())
                if (mode == "timeout") assertEquals(LocalCodexProblem.TIMEOUT.message, result.message)
                else assertNotNull(result.needsConsent)
                // Unix zombies are dead; Windows handles become absent after collection.
                handles.forEach { handle ->
                    val status = Path.of("/proc/${handle.pid()}/stat")
                    val zombie = Files.exists(status) && Files.readString(status).substringAfterLast(") ").startsWith("Z")
                    assertTrue(!handle.isAlive || zombie, "process ${handle.pid()} survived $mode")
                }
                assertFalse(Files.exists(Path.of(Files.readString(f.root.resolve("cwd")))))
            } finally { handles.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }; reply.cancelAndJoin() }
        }
    }

    @Test fun discoveryFindsNativeNpmBinaryWithoutLaunchingItsShellWrapper() {
        val root = Files.createTempDirectory("synthetic-codex-discovery-")
        val executable = root.resolve("node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, "synthetic executable")
        executable.toFile().setExecutable(true)
        assertEquals(executable, discoverLocalCodex(root.toString(), windows = true))
        assertNull(discoverLocalCodex(root.resolve("absent").toString(), windows = true))
    }
}
