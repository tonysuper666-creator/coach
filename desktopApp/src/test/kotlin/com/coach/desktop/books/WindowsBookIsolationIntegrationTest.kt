package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class WindowsBookIsolationIntegrationTest {
    private suspend fun support(native: SyntheticIsolatedCodex, transport: LocalCodexBookTransport = native.transport()): ConsentedBookLearningSupport {
        val fixture = native.fixture
        return ConsentedBookLearningSupport(fixture.library, fixture.store, transport).also {
            it.grant(it.consentOffer(fixture.a.id), OwnedBookAction.entries.toSet())
        }
    }
    @Test fun helperReceiptRequiresVerifiedContainerChildAndCleanup() {
        val good = """
            COACH_ISO_SID S-1-15-2-1-2-3-4-5-6-7
            COACH_ISO_VERIFIED pid=123 appcontainer=1
            COACH_ISO_RESUMED pid=123
            COACH_ISO_CHILD_EXIT 0
            COACH_ISO_ACL_REVOKED 3
            COACH_ISO_PROFILE_DELETED hr=0x0
        """.trimIndent()
        assertTrue(validIsolationReceipt(good))
        for (line in good.lines()) assertFalse(validIsolationReceipt(good.lines().filter { it != line }.joinToString("\n")))
        assertFalse(validIsolationReceipt(good + "\nCOACH_ISO_REFUSED code=128 reason=cleanup_failed"))
        assertFalse(validIsolationReceipt(good.replace("CHILD_EXIT 0", "CHILD_EXIT 1")))
        assertFalse(validIsolationReceipt(good.replace("RESUMED pid=123", "RESUMED pid=124")))
        assertFalse(validIsolationReceipt(good.replace("hr=0x0", "hr=0x01")))
    }

    @Test fun absentIsolationNeverStartsTheGivenExecutable() = runBlocking {
        val f = OwnedBookConsentTest.Fixture()
        val called = mutableListOf<Int>()
        val transport = LocalCodexBookTransport(Path.of("synthetic-must-not-run"), receipt = { _, status -> called.add(status) })
        val support = ConsentedBookLearningSupport(f.library, f.store, transport)
        support.grant(support.consentOffer(f.a.id), OwnedBookAction.entries.toSet())
        assertIs<BookLearningReply.Unavailable>(support.explain(f.explain()))
        assertTrue(called.isEmpty())
    }

    @Test fun syntheticWindowsRequestUsesContainerAndDeniesNeighbor() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val fixture = OwnedBookConsentTest.Fixture()
        val root = fixture.root
        val sentinel = Files.writeString(root.resolve("SYNTHETIC-neighbor.txt"), "SENTINEL_PRIVATE_SYNTHETIC")
        val authDir = Files.createDirectory(root.resolve("synthetic-auth"))
        val auth = Files.writeString(authDir.resolve("auth.json"), """{"synthetic":"original"}""")
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("desktopApp/windows-iso/CoachIso.cs")) }
        val csc = Path.of(System.getenv("SystemRoot"), "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
        fun compile(output: Path, source: Path) {
            val p = ProcessBuilder(csc.toString(), "/nologo", "/out:$output", source.toString()).redirectErrorStream(true).start()
            val diagnostic = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), diagnostic)
        }
        val helper = root.resolve("coach-iso.exe")
        val committer = root.resolve("coach-auth.exe")
        compile(helper, repo.resolve("desktopApp/windows-iso/CoachIso.cs"))
        compile(committer, repo.resolve("desktopApp/windows-auth/CoachAuthCommit.cs"))
        val source = root.resolve("SyntheticAdapter.cs")
        val sentinelLiteral = sentinel.toString().replace("\"", "\"\"")
        Files.writeString(source, """
            using System;
            using System.IO;
            class SyntheticAdapter {
              static int Main(string[] args) {
                if (args.Length == 2 && args[0] == "login" && args[1] == "status") {
                  Console.WriteLine("Logged in using ChatGPT"); return 0;
                }
                string prompt = Console.In.ReadToEnd();
                if (!prompt.Contains("Photosynthesis")) return 3;
                try { File.ReadAllText(@"$sentinelLiteral"); return 4; }
                catch (UnauthorizedAccessException) { }
                catch (IOException) { }
                int output = Array.IndexOf(args, "-o");
                if (output < 0) return 5;
                File.WriteAllText(args[output+1], "{\"answer\":\"Light supplies energy for making stored food. SYNTHETIC_NEIGHBOR_DENIED\",\"citations\":[0]}");
                Console.WriteLine("{\"type\":\"thread.started\"}");
                Console.WriteLine("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\"}}");
                Console.WriteLine("{\"type\":\"turn.completed\"}");
                return 0;
              }
            }
        """.trimIndent())
        val fake = root.resolve("synthetic-codex.exe")
        compile(fake, source)
        val receipts = mutableListOf<Pair<List<String>, Int>>()
        val identities = mutableListOf<WindowsBookIsolationReceipt>()
        val isolation = WindowsBookIsolation(helper, committer, auth)
        val transport = LocalCodexBookTransport(fake, isolation = isolation, receipt = { argv, exit -> receipts.add(argv to exit) },
            isolationReceipt = { identities.add(it); println("SYNTHETIC_HELPER $it") })
        val support = ConsentedBookLearningSupport(fixture.library, fixture.store, transport)
        support.grant(support.consentOffer(fixture.a.id), OwnedBookAction.entries.toSet())
        val result = support.explain(fixture.explain())
        val reply = assertIs<BookLearningReply.Text>(result, "synthetic result=$result helper exits=${receipts.map { it.second }}")
        assertTrue(reply.toString().contains("SYNTHETIC_NEIGHBOR_DENIED"))
        assertFalse(reply.toString().contains("SENTINEL_PRIVATE_SYNTHETIC"))
        assertEquals(2, receipts.size)
        assertEquals(2, identities.size)
        assertTrue(identities.all { it.verified && it.cleaned && it.childPid != null && it.containerSid?.startsWith("S-1-15-2-") == true })
        assertTrue(identities.none { ProcessHandle.of(it.childPid!!).orElse(null)?.isAlive == true })
        assertTrue(receipts.all { (argv, exit) -> exit == 0 && argv.first() == helper.toAbsolutePath().toString() && "--allow-read" in argv })
        assertEquals("""{"synthetic":"original"}""", Files.readString(auth))
        println("SYNTHETIC_ISOLATED phases=login,exec exits=0,0 appcontainer=verified neighbor=denied auth=unchanged")
        Files.writeString(auth, "{")
        receipts.clear()
        assertIs<BookLearningReply.Unavailable>(support.explain(fixture.explain()))
        assertTrue(receipts.isEmpty())
        assertEquals("{", Files.readString(auth))
        println("SYNTHETIC_INVALID_AUTH helper_starts=0 original=unchanged")
    }

    @Test fun aRefreshedSyntheticAuthIsCommittedThroughTheSameTransport() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "refresh")
        assertIs<BookLearningReply.Text>(support(native).explain(native.fixture.explain()))
        assertEquals("""{"SYNTHETIC":"REFRESHED_NOT_A_CREDENTIAL"}""", Files.readString(native.auth))
        native.clean()
        println("SYNTHETIC_TRANSPORT_REFRESH committed=1 run_homes=0")
    }

    @Test fun aConcurrentOriginalUpdateAfterExecIsPreservedAndAnswerDiscarded() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "refresh")
        val newer = """{"SYNTHETIC":"NEWER_FROM_ANOTHER_PROCESS"}"""
        native.beforePhaseReceipt = { if (it.phase == "exec" && it.exit == 0) Files.writeString(native.auth, newer) }
        assertIs<BookLearningReply.Unavailable>(support(native).explain(native.fixture.explain()))
        assertEquals(newer, Files.readString(native.auth))
        assertEquals(2, native.receipts.size)
        native.clean()
        println("SYNTHETIC_TRANSPORT_CAS source_changed=1 newer_preserved=true displayed_answers=0")
    }

    @Test fun twoCompletedTransportRunsCannotOverwriteEachOthersRefresh() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "refresh")
        val ready = java.util.concurrent.CountDownLatch(2)
        native.beforePhaseReceipt = {
            if (it.phase == "exec" && it.exit == 0) {
                ready.countDown()
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS), "Both runs must finish execution before either commits")
            }
        }
        val support = support(native)
        val replies = withTimeout(15_000) {
            listOf(async { support.explain(native.fixture.explain()) }, async { support.explain(native.fixture.explain()) }).awaitAll()
        }
        assertEquals(1, replies.count { it is BookLearningReply.Text })
        assertEquals(1, replies.count { it is BookLearningReply.Unavailable })
        assertEquals("""{"SYNTHETIC":"REFRESHED_NOT_A_CREDENTIAL"}""", Files.readString(native.auth))
        assertEquals(4, native.receipts.size)
        native.clean()
        println("SYNTHETIC_TRANSPORT_TWO_COMPLETIONS committed_answers=1 rejected_answers=1 run_roots=0")
    }

    @Test fun brokenRefreshedJsonCannotOverwriteTheOriginalOrPublishAnAnswer() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "broken-auth")
        val result = assertIs<BookLearningReply.Unavailable>(support(native).explain(native.fixture.explain()))
        assertEquals(LocalCodexProblem.AUTH_REFRESH.message, result.message)
        assertTrue(Files.readAllBytes(native.auth).contentEquals(native.original))
        native.clean()
        println("SYNTHETIC_TRANSPORT_BAD_REFRESH original_preserved=true displayed_answers=0")
    }

    @Test fun rejectedWritebackRequestsReloginWithoutRetryingTheAdapter() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "refresh")
        val source = Files.writeString(native.root.resolve("SYNTHETIC-FailedCommit.cs"),
            "class SyntheticFailedCommit { static int Main(string[] args) { return 4; } }")
        val helper = SyntheticIsolatedCodex.compile(native.root.resolve("SYNTHETIC-failed-commit.exe"), listOf(source))
        val result = assertIs<BookLearningReply.Unavailable>(support(native, native.transport(commitHelper = helper)).explain(native.fixture.explain()))
        assertEquals(LocalCodexProblem.AUTH_REFRESH.message, result.message)
        assertEquals(2, native.argv.size)
        assertTrue(Files.readAllBytes(native.auth).contentEquals(native.original))
        native.clean()
        println("SYNTHETIC_TRANSPORT_REFRESH_FAILURE relogin_required=true adapter_retries=0 original_preserved=true")
    }

    @Test fun unknownWritebackOutcomeIsTerminalAndTheNextExplicitRequestRereadsAuth() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "refresh")
        val source = Files.writeString(native.root.resolve("SYNTHETIC-UncertainCommit.cs"), """
            using System.IO;
            class SyntheticUncertainCommit {
              static int Main(string[] args) {
                File.WriteAllText(args[0], File.ReadAllText(args[1]));
                return 5;
              }
            }
        """.trimIndent())
        val helper = SyntheticIsolatedCodex.compile(native.root.resolve("SYNTHETIC-uncertain-commit.exe"), listOf(source))
        val support = support(native, native.transport(commitHelper = helper))
        val first = assertIs<BookLearningReply.Unavailable>(support.explain(native.fixture.explain()))
        assertEquals(LocalCodexProblem.AUTH_REFRESH.message, first.message)
        assertEquals(2, native.argv.size, "Unknown outcome must not retry the adapter")
        assertEquals("""{"SYNTHETIC":"REFRESHED_NOT_A_CREDENTIAL"}""", Files.readString(native.auth))
        // The new request must snapshot the updated original; its identical refresh is then
        // UNCHANGED. Reusing the stale snapshot would call the uncertain helper and fail again.
        assertIs<BookLearningReply.Text>(support.explain(native.fixture.explain()))
        assertEquals(4, native.argv.size)
        native.clean()
        println("SYNTHETIC_TRANSPORT_UNKNOWN first_answers=0 automatic_retries=0 next_request_rereads_original=true")
    }

    @Test fun revokeDuringTheTransactionRollsBackAndCannotPublishTheCompletedAnswer() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "refresh")
        Files.writeString(native.auth.parent.resolve("SYNTHETIC-pause-closed"), "SYNTHETIC_BARRIER")
        val support = support(native, native.transport(commitHelper = SyntheticIsolatedCodex.commitHarness))
        val request = async { support.explain(native.fixture.explain()) }
        try {
            withTimeout(10_000) { while (!Files.exists(native.auth.parent.resolve("SYNTHETIC-ready"))) delay(5) }
            support.revoke(native.fixture.a.id)
            assertNotNull(assertIs<BookLearningReply.Unavailable>(request.await()).needsConsent)
            assertTrue(Files.readAllBytes(native.auth).contentEquals(native.original))
            val completed = native.receipts.size
            assertIs<BookLearningReply.Unavailable>(support.explain(native.fixture.explain()))
            assertEquals(completed, native.receipts.size, "Revoked request must not start another helper")
            native.clean()
            println("SYNTHETIC_TRANSPORT_REVOKE_CAS original_preserved=true displayed_answers=0 subsequent_starts=0")
        } finally {
            Files.writeString(native.auth.parent.resolve("SYNTHETIC-release"), "RELEASE")
            request.cancelAndJoin()
        }
    }

    @Test fun revokeDuringAChildRunLetsTheHelperFinishItsOwnCleanup() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "cancel")
        val support = support(native)
        val request = async { support.explain(native.fixture.explain()) }
        try {
            withTimeout(8000) { while (!Files.exists(native.root.resolve("pids"))) delay(5) }
            val handles = Files.readString(native.root.resolve("pids")).split(',').mapNotNull { ProcessHandle.of(it.toLong()).orElse(null) }
            support.revoke(native.fixture.a.id)
            assertNotNull(assertIs<BookLearningReply.Unavailable>(request.await()).needsConsent)
            assertTrue(handles.none { it.isAlive })
            assertEquals(2, native.receipts.size)
            assertTrue(native.receipts.all { it.cleaned && it.verified })
            native.clean()
            println("SYNTHETIC_TRANSPORT_REVOKE child_survivors=0 helper_cleanup=true displayed_answers=0")
        } finally { request.cancelAndJoin() }
    }

    @Test fun rejectedHelperPartialAnswerNeverTriggersABareAdapterFallback() = runBlocking {
        for (exit in listOf(0, 128)) {
            val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "success")
            val source = native.root.resolve("SYNTHETIC-RejectedLauncher.cs")
            Files.writeString(source, """
                using System;
                using System.IO;
                class RejectedLauncher {
                  static int Main(string[] args) {
                    string output = args[Array.IndexOf(args, "--stdout")+1];
                    string work = args[Array.IndexOf(args, "--cwd")+1];
                    File.WriteAllText(output, "Logged in using ChatGPT");
                    File.WriteAllText(Path.Combine(work, "answer.txt"), "PARTIAL_SYNTHETIC_ANSWER");
                    Console.Error.WriteLine("COACH_ISO_VERIFIED pid=1 appcontainer=1");
                    Console.Error.WriteLine("COACH_ISO_CHILD_EXIT 0");
                    Console.Error.WriteLine("COACH_ISO_ACL_REVOKED 3");
                    Console.Error.WriteLine("COACH_ISO_PROFILE_DELETED hr=0x0");
                    Console.Error.WriteLine("COACH_ISO_REFUSED code=128 reason=synthetic_failure");
                    return $exit;
                  }
                }
            """.trimIndent())
            val helper = SyntheticIsolatedCodex.compile(native.root.resolve("SYNTHETIC-rejected-launcher.exe"), listOf(source))
            assertIs<BookLearningReply.Unavailable>(support(native, native.transport(isolationHelper = helper)).explain(native.fixture.explain()))
            assertFalse(Files.exists(native.root.resolve("adapter-started")))
            assertEquals(1, native.argv.size)
            assertTrue(native.argv.all { it.first.first() == helper.toAbsolutePath().toString() })
            assertTrue(Files.readAllBytes(native.auth).contentEquals(native.original))
            native.clean()
        }
        println("SYNTHETIC_TRANSPORT_REFUSAL partial_answers=0 bare_adapter_starts=0")
    }

    @Test fun runCleanupUnlinksAJunctionWithoutDeletingItsSyntheticVictim() = runBlocking {
        val native = SyntheticIsolatedCodex(OwnedBookConsentTest.Fixture(), "success")
        val victim = Files.createDirectory(native.root.resolve("SYNTHETIC-victim"))
        val keep = Files.writeString(victim.resolve("KEEP_SYNTHETIC.txt"), "SYNTHETIC_KEEP")
        native.prepare = { run ->
            val link = run.work.resolve("SYNTHETIC-link")
            val process = ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32/cmd.exe").toString(), "/d", "/c", "mklink", "/J", link.toString(), victim.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        }
        assertIs<BookLearningReply.Unavailable>(support(native).explain(native.fixture.explain()))
        assertEquals("SYNTHETIC_KEEP", Files.readString(keep))
        native.clean()
        println("SYNTHETIC_TRANSPORT_JUNCTION victim_preserved=true run_roots=0")
    }
}
