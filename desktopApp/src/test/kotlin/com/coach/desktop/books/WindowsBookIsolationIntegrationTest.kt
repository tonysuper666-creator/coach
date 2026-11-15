package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WindowsBookIsolationIntegrationTest {
    @Test fun helperReceiptRequiresVerifiedContainerChildAndCleanup() {
        val good = """
            COACH_ISO_VERIFIED pid=123 appcontainer=1
            COACH_ISO_CHILD_EXIT 0
            COACH_ISO_ACL_REVOKED 3
            COACH_ISO_PROFILE_DELETED hr=0x0
        """.trimIndent()
        assertTrue(validIsolationReceipt(good))
        for (line in good.lines()) assertFalse(validIsolationReceipt(good.lines().filter { it != line }.joinToString("\n")))
        assertFalse(validIsolationReceipt(good + "\nCOACH_ISO_REFUSED code=128 reason=cleanup_failed"))
        assertFalse(validIsolationReceipt(good.replace("CHILD_EXIT 0", "CHILD_EXIT 1")))
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
        val isolation = WindowsBookIsolation(helper, committer, auth)
        val transport = LocalCodexBookTransport(fake, isolation = isolation, receipt = { argv, exit -> receipts.add(argv to exit) })
        val support = ConsentedBookLearningSupport(fixture.library, fixture.store, transport)
        support.grant(support.consentOffer(fixture.a.id), OwnedBookAction.entries.toSet())
        val result = support.explain(fixture.explain())
        val reply = assertIs<BookLearningReply.Text>(result, "synthetic result=$result helper exits=${receipts.map { it.second }}")
        assertTrue(reply.toString().contains("SYNTHETIC_NEIGHBOR_DENIED"))
        assertFalse(reply.toString().contains("SENTINEL_PRIVATE_SYNTHETIC"))
        assertEquals(2, receipts.size)
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
}
