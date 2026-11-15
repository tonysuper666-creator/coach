package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class LocalCodexBookTransportTest {
    private val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"

    private fun fake(f: OwnedBookConsentTest.Fixture, mode: String, timeout: Long = 5000): LocalCodexBookTransport {
        val script = f.root.resolve("fake-codex.py")
        Files.writeString(script, """
            import json, os, subprocess, sys, time
            from pathlib import Path
            mode, root = sys.argv[1:3]
            root = Path(root)
            args = sys.argv[3:]
            if args == ['login', 'status']:
                print('Logged in using ' + ('an API key' if mode == 'apikey' else 'ChatGPT'))
                sys.exit(0)
            root.joinpath('argv.json').write_text(json.dumps(args))
            root.joinpath('cwd').write_text(os.getcwd())
            assert list(Path.cwd().iterdir()) == []
            raw = sys.stdin.read()
            assert 'Synthetic A' not in raw and 'book' not in raw and str(root) not in raw
            if mode == 'model':
                print('The model is not supported', file=sys.stderr)
                sys.exit(3)
            if mode in ('timeout', 'cancel'):
                child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
                root.joinpath('pids').write_text(str(os.getpid()) + ',' + str(child.pid))
                time.sleep(120)
            Path(args[args.index('-o') + 1]).write_text(json.dumps({'answer': 'Light supplies energy for making stored food.', 'citations': [0]}))
        """.trimIndent())
        return LocalCodexBookTransport(Path.of(python), timeoutMillis = timeout,
            commandPrefix = listOf(python, script.toString(), mode, f.root.toString()))
    }

    @Test fun exactArgvEmptyWorkingDirectoryTemporaryOutputAndSubscriptionLogin() = runBlocking {
        val f = OwnedBookConsentTest.Fixture()
        val support = ConsentedBookLearningSupport(f.library, f.store, fake(f, "success"))
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        assertIs<BookLearningReply.Text>(support.explain(f.explain()))
        val argv = Files.readString(f.root.resolve("argv.json"))
        for (flag in listOf("exec", "gpt-6.1-sol", "model_reasoning_effort=low", "--ephemeral", "--skip-git-repo-check", "read-only", "-o"))
            assertTrue(argv.contains(flag), flag)
        assertFalse(Files.exists(Path.of(Files.readString(f.root.resolve("cwd")))))
        val api = ConsentedBookLearningSupport(f.library, f.store, fake(f, "apikey"))
        assertEquals(LocalCodexProblem.LOGIN.message, assertIs<BookLearningReply.Unavailable>(api.explain(f.explain())).message)
        val model = ConsentedBookLearningSupport(f.library, f.store, fake(f, "model"))
        assertEquals(LocalCodexProblem.MODEL.message, assertIs<BookLearningReply.Unavailable>(model.explain(f.explain())).message)
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
