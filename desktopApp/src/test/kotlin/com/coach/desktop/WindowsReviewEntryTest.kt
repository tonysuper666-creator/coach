package com.coach.desktop

import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actual desktop session/host with owned loopback HTTP and on-disk SQLite. No learner corpus. */
@OptIn(ExperimentalTestApi::class)
class WindowsReviewEntryTest {
    private class Fixture(ready: Boolean = true) : AutoCloseable {
        val root = Files.createTempDirectory("synthetic-windows-review-")
        val token = "synthetic-windows-review-token-32-characters"
        var clock = 1_794_052_800_000L - 86_400_000
        val offline = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("server").also { Files.createDirectories(it) }) { clock }
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            intercept(ApplicationCallPipeline.Call) {
                if (offline.get()) { call.respond(HttpStatusCode.ServiceUnavailable); finish() }
            }
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
        }
        val base: String
        val settings: DesktopConnectionSettings
        init {
            runBlocking { actor.execute {
                enroll("windows-learner", token)
                append("C", "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic review")))))
                val options = listOf(Option("o1", "Synthetic one"), Option("o2", "Synthetic two"))
                registerQuestion("original", "C", "", "SINGLE_CHOICE", "Synthetic original", "VERIFIED", "o1", options, listOf("K"))
                poseChoiceGroup("C", "original-group", listOf(json("attemptId" to "original-attempt", "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1, "mainline" to true, "posedAt" to clock)))
                append("C", "answer_submitted", json("attemptId" to "original-attempt", "answer" to "A"))
                append("C", "attempt_graded", json("attemptId" to "original-attempt", "score" to 0, "max" to 1, "status" to "EXACT"))
                if (ready) {
                    // Opposite insertion order exposes arbitrary KP allocation.
                    registerQuestion("q-z", "C", "", "SINGLE_CHOICE", "Synthetic arbitrary fallback", "VERIFIED", "o1", options, listOf("K"))
                    registerQuestion("q-a", "C", "", "SINGLE_CHOICE", "Synthetic exact Windows card", "VERIFIED", "o1", options, listOf("K"))
                }
            } }
            clock += 86_400_000
            server.start(false)
            base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            val tokenFile = root.resolve("token").also { Files.writeString(it, token) }
            val env = mapOf("COACH_URL" to base, "COACH_TOKEN_FILE" to tokenFile.toString(),
                "COACH_DEVICE_ID" to "windows-learner", "COACH_CLIENT_DIR" to root.resolve("client").toString())
            settings = DesktopConnectionSettings(root, env::get, mac = false, property = { null })
        }
        fun open(connect: Boolean = true): DesktopLearningSession {
            val session = DesktopLearningSession(settings, assertNotNull(DesktopPairing(settings).load()), "C")
            if (connect) runBlocking { session.controller.sync.syncOnce() }
            return session
        }
        fun facts() = runBlocking { actor.execute { events("C") } }
        fun reopenNativeWindow() {
            val output = File("build/windows-review-native.log")
            val captures = File("build/screens").absoluteFile.also { it.mkdirs() }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val java = File(System.getProperty("java.home"), "bin/${if (windows) "javaw.exe" else "java"}")
            val paths = generateSequence(WindowsReviewEntryTest::class.java.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
                .map { File(it.toURI()).path }.toList()
            val classpath = (paths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
            val builder = ProcessBuilder(java.path, "-Duser.home=$root", "-Dcoach.autoExitSeconds=6",
                "-Dcoach.captures=windows-review-native@3", "-Dcoach.captureDir=$captures",
                "-cp", classpath, "com.coach.desktop.MainKt")
                .redirectOutput(output).redirectError(File("build/windows-review-native.stderr.log"))
            builder.environment().apply {
                for (key in keys.filter { it.startsWith("COACH_") }.toList()) remove(key)
                putAll(mapOf("COACH_MODE" to "coach", "COACH_COURSE" to "C", "COACH_URL" to base,
                    "COACH_TOKEN_FILE" to root.resolve("token").toString(), "COACH_DEVICE_ID" to "windows-learner",
                    "COACH_CLIENT_DIR" to root.resolve("client").toString()))
            }
            val process = builder.start()
            val exit = process.waitFor() // The owned runCoach window exits via its existing six-second hook.
            println("NATIVE_MAIN argv=MainKt COACH_COURSE=C isolatedLoopback=true autoExit=6 pid=${process.pid()} exit=$exit")
            assertEquals(0, exit, File("build/windows-review-native.stderr.log").readText())
            assertTrue(output.readText().contains("COACH captured windows-review-native"))
            assertTrue(File(captures, "windows-review-native.png").length() > 0)
        }
        override fun close() { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
    }

    private fun ComposeUiTest.openReview() {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("复习").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("复习") and hasClickAction()).performClick()
    }

    @Test fun configuredWindowsEntryAllocatesSubmitsAcknowledgesAndReopensExactIdentity() = Fixture().use { f ->
        var attempt = ""
        var command = ""
        var navigation = ""
        f.open().use { session ->
            val c = session.controller
            runComposeUiTest {
                setContent { DesktopCourseContent(session) }
                openReview()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("已备 1 道变式", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("开始", substring = false).onFirst().performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) {
                    attempt = reviewCursorAttempt(c.sync, "C").orEmpty()
                    attempt.isNotEmpty() && onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Synthetic exact Windows card").assertIsDisplayed()
                onNodeWithText("Synthetic arbitrary fallback").assertDoesNotExist()
                val target = assertNotNull(reviewPracticeTarget(c.sync, "C"))
                assertEquals("windows-learner", target.deviceId)
                assertNull(target.agentRunId)
                assertEquals("q-a", target.question.id)
                onNodeWithTag("study-option-$attempt-A").performClick()
                onNodeWithTag("review-submit").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("作答已确认。").fetchSemanticsNodes().isNotEmpty() }
                command = c.sync.mainlineState("C", "review-answer:$attempt").str("commandId")
                assertEquals("OK", c.sync.receipt(command)?.status)
                navigation = c.sync.mainlineState("C", "navigation").str("stack")
            }
            assertTrue(command.isNotBlank())
            println(json("phase" to "before-restart", "course" to "C", "device" to c.sync.deviceId,
                "question" to "q-a", "attempt" to attempt, "command" to command, "ack" to c.sync.receipt(command)?.status,
                "navigation" to navigation))
        }
        f.offline.set(true)
        f.open(connect = false).use { reopened ->
            val c = reopened.controller
            runComposeUiTest {
                // Default host restores its persisted stack; no initial review/practice shortcut.
                setContent { DesktopCourseContent(reopened) }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Synthetic exact Windows card").assertIsDisplayed()
                onNodeWithText("作答已确认。").assertIsDisplayed()
                onNodeWithTag("review-submit").assertDoesNotExist()
            }
            assertEquals(attempt, reviewCursorAttempt(c.sync, "C"))
            assertEquals(command, c.sync.mainlineState("C", "review-answer:$attempt").str("commandId"))
            assertEquals(navigation, c.sync.mainlineState("C", "navigation").str("stack"))
            println(json("phase" to "after-offline-restart", "question" to reviewPracticeTarget(c.sync, "C")?.question?.id,
                "attempt" to reviewCursorAttempt(c.sync, "C"), "command" to command, "ack" to c.sync.receipt(command)?.status))
        }
        val facts = f.facts()
        assertEquals("q-a", facts.single { it.type == "question_posed" && it.payload.str("attemptId") == attempt }.payload.str("questionId"))
        assertEquals(1, facts.count { it.type == "answer_submitted" && it.payload.str("attemptId") == attempt })
        assertEquals(1, facts.count { it.type == "attempt_graded" && it.payload.str("attemptId") == attempt })
        assertEquals("A", facts.single { it.type == "answer_submitted" && it.payload.str("attemptId") == "original-attempt" }.payload.str("answer"))
        f.reopenNativeWindow()
        assertEquals(facts, f.facts(), "Offline native reopen must not mint or duplicate facts")
        f.open(connect = false).use { afterNative ->
            assertEquals(attempt, reviewCursorAttempt(afterNative.controller.sync, "C"))
            assertEquals(command, afterNative.controller.sync.mainlineState("C", "review-answer:$attempt").str("commandId"))
            assertEquals("OK", afterNative.controller.sync.receipt(command)?.status)
            println(json("phase" to "after-native-main", "attempt" to attempt, "command" to command, "ack" to "OK"))
        }
    }

    @Test fun defaultEntryWithoutPreparedVariantRefusesWithoutMintingFacts() = Fixture(ready = false).use { f ->
        f.open().use { session ->
            val before = f.facts()
            runComposeUiTest {
                setContent { DesktopCourseContent(session) }
                openReview()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("待生成变式", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("开始", substring = false).onFirst().performScrollTo().performClick()
                onNodeWithText("这项复习还没有可用的已核变式，尚未开始；原作答已保留。").assertIsDisplayed()
                onNodeWithText("提交答案").assertDoesNotExist()
                onNodeWithText("这道题的练习入口尚未接通", substring = true).assertDoesNotExist()
            }
            assertNull(reviewPracticeTarget(session.controller.sync, "C"))
            assertEquals(before, f.facts())
        }
    }

    @Test fun windowsDispatchKeepsLocalBooksWithoutInventingARemoteCourse() {
        assertTrue(desktopUsesTextbooks("Windows 11", null, null))
        assertTrue(desktopUsesTextbooks("Windows 11", " ", " "))
        assertFalse(desktopUsesTextbooks("Windows 11", "C", null))
        assertFalse(desktopUsesTextbooks("Windows 11", null, "C"))
        assertFalse(desktopUsesTextbooks("Mac OS X", null, null))
        assertEquals("C", resolveCourse(" C ", "other"))
    }

    @Test fun staleNavigationAttemptCannotAllocateTheNewlySelectedTarget() = Fixture().use { f ->
        f.open().use { session ->
            val c = session.controller
            val view = CourseView(c.sync.localEvents("C"), c.sync.projection("C"))
            val candidates = runBlocking { assertNotNull(c.course).reviewVariantCandidates("K") }.orEmpty()
            val board = reviewBoard("C", view, view.logicalToday(c.nowMillis()),
                com.coach.domain.reviewqueue.VariantCandidatePort { _, _, _ -> candidates })
            val item = board.items.first { it.variantAttemptId != null }
            val target = assertNotNull(prepareReviewPractice(c.sync, "C", null, item, board.day))
            c.sync.saveMainlineState("C", json("stack" to """[{"page":"HOME","tab":"REVIEW"},{"page":"REVIEW_PRACTICE","tab":"REVIEW","id":"stale-attempt"}]"""), "navigation")
            val before = f.facts()
            runComposeUiTest {
                setContent { DesktopCourseContent(session) }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("这道题的身份或可用状态暂时无法确认", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Synthetic exact Windows card").assertDoesNotExist()
                onNodeWithTag("review-submit").assertDoesNotExist()
            }
            assertEquals(target, reviewPracticeTarget(c.sync, "C"))
            assertEquals(before, f.facts(), "A stale route must not reserve the current selection")
        }
    }
}
