package com.coach.desktop

import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.desktop.books.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Path
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
        fun reopenNativeWindow(configured: Boolean = true, library: Path? = null) {
            val captureName = if (configured) "windows-review-native" else "combined-books-native"
            val output = File("build/$captureName.log")
            val errors = File("build/$captureName.stderr.log")
            val captures = File("build/screens").absoluteFile.also { it.mkdirs() }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val java = File(System.getProperty("java.home"), "bin/${if (windows) "javaw.exe" else "java"}")
            val paths = generateSequence(WindowsReviewEntryTest::class.java.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
                .map { File(it.toURI()).path }.toList()
            val classpath = (paths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
            val builder = ProcessBuilder(java.path, "-Duser.home=$root", "-Dcoach.autoExitSeconds=6",
                "-Dcoach.captures=$captureName@3", "-Dcoach.captureDir=$captures",
                "-cp", classpath, "com.coach.desktop.MainKt")
                .redirectOutput(output).redirectError(errors)
            builder.environment().apply {
                for (key in keys.filter { it.startsWith("COACH_") }.toList()) remove(key)
                putAll(mapOf("COACH_MODE" to "coach", "COACH_URL" to base,
                    "COACH_TOKEN_FILE" to root.resolve("token").toString(), "COACH_DEVICE_ID" to "windows-learner",
                    "COACH_CLIENT_DIR" to root.resolve("client").toString()))
                if (configured) put("COACH_COURSE", "C")
                else put("COACH_LIBRARY_DIR", requireNotNull(library).toString())
            }
            val process = builder.start()
            val exit = process.waitFor() // The owned runCoach window exits via its existing six-second hook.
            println("NATIVE_MAIN argv=MainKt COACH_COURSE=${if (configured) "C" else "<none>"} isolatedLoopback=true autoExit=6 pid=${process.pid()} exit=$exit")
            val stdout = output.readText()
            val nativeLog = stdout + "\n" + errors.readText()
            assertEquals(0, exit, nativeLog)
            // Skiko can catch a native fault, switch renderers, capture a frame and still exit 0.
            val renderFailures = nativeLog.lineSequence().filter {
                "[SKIKO] error:" in it || "org.jetbrains.skiko.RenderException" in it ||
                    "EXCEPTION_ACCESS_VIOLATION" in it
            }.toList()
            assertTrue(renderFailures.isEmpty(), "Native rendering failed despite exit 0:\n${renderFailures.joinToString("\n")}\n" +
                "For an active Windows console session use: tools/dev/heavy --local python3 tools/dev/winbox.py --interactive ./gradlew :desktopApp:test --tests 'com.coach.desktop.WindowsReviewEntryTest'")
            assertTrue(stdout.contains("COACH captured $captureName"))
            assertTrue(File(captures, "$captureName.png").length() > 0)
        }
        override fun close() { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
    }

    private fun ComposeUiTest.openReview() {
        // 188 removes the review navigation tab. The same queue is reached from Today.
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("到期复习").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("到期复习").performScrollTo().performClick()
    }

    @Test fun combinedMainlineKeepsTextbookAndExactReviewSeparateAcrossMigrationAndAgentRestart() = Fixture().use { f ->
        val libraryDir = f.root.resolve("library")
        val source = f.root.resolve("synthetic-orbits.md")
        Files.writeString(source, """
            # Synthetic astronomy

            Orbit is the path of an object around another body. An orbit can be circular or elliptical.

            Gravity is the attraction between bodies with mass. Gravity keeps an orbiting body near its star.
        """.trimIndent())
        val library = TextbookLibrary(libraryDir)
        var localBook = ""
        var localLesson = ""
        var localAnswer = ""
        val beforeBook = f.facts()
        assertTrue(desktopUsesTextbooks("Windows 11", null, null))
        runComposeUiTest {
            setContent { WindowsTextbooksContent(libraryDir) { source } }
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("upload-textbook") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("enable-learning") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("enable-learning").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("choice-0").fetchSemanticsNodes().isNotEmpty() }
            val book = library.book(library.catalog().single().id)
            val lesson = book.lessons[book.lessonIndex]
            val question = assertNotNull(StudyCoachCourse.practice(book, lesson, book.questionMode))
            localBook = book.id; localLesson = lesson.id; localAnswer = question.answer
            onNodeWithTag("choice-${question.options.indexOf(question.answer)}").performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("与原文一致。").fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(localAnswer, library.answer(localBook, localLesson, QuestionMode.CHOICE).text)
        f.reopenNativeWindow(configured = false, library = libraryDir)
        assertEquals(beforeBook, f.facts(), "Local textbook study cannot mint remote course facts")

        var attempt = ""
        var command = ""
        val run = "combined-agent"
        val agentPage = "navigation:agent:${run.length}:$run"
        val agentSentinel = json("stack" to """[{"tab":"TODAY","future":"agent-only"}]""")
        val otherCourse = json("stack" to """[{"tab":"RESOURCES","future":"other-course"}]""")
        assertFalse(desktopUsesTextbooks("Windows 11", "C", null))
        f.open().use { session ->
            val c = session.controller
            c.sync.saveMainlineState("C", agentSentinel, agentPage)
            c.sync.saveMainlineState("C2", otherCourse, "navigation")
            runComposeUiTest {
                setContent { DesktopCourseContent(session) }
                waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("课程") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("课程") and hasClickAction()).performClick()
                onNode(hasText("书架") and hasClickAction()).performClick()
                onNode(hasText("今天") and hasClickAction()).performClick()
                onNodeWithText("到期复习").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("已备 1 道变式", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("开始", substring = false).onFirst().performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) {
                    attempt = reviewCursorAttempt(c.sync, "C").orEmpty()
                    attempt.isNotEmpty() && onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty()
                }
                val target = assertNotNull(reviewPracticeTarget(c.sync, "C"))
                assertEquals("q-a", target.question.id)
                assertEquals("C", target.courseId); assertEquals("windows-learner", target.deviceId); assertNull(target.agentRunId)
                onNodeWithTag("study-option-$attempt-A").performClick()
                onNodeWithTag("review-submit").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("作答已确认。").fetchSemanticsNodes().isNotEmpty() }
            }
            command = c.sync.mainlineState("C", "review-answer:$attempt").str("commandId")
            assertEquals("OK", c.sync.receipt(command)?.status)
            assertEquals(agentSentinel, c.sync.mainlineState("C", agentPage))
            assertEquals(otherCourse, c.sync.mainlineState("C2", "navigation"))
            val stack = Json.parseToJsonElement(c.sync.mainlineState("C", "navigation").str("stack")).jsonObject
            assertEquals(2, stack.getValue("version").jsonPrimitive.int)
            assertEquals("REVIEW_PRACTICE", stack.getValue("entries").jsonArray.last().jsonObject.str("page"))
            // The 188 codec must retain future envelope fields when restoring 177's exact task.
            c.sync.saveMainlineState("C", json("stack" to JsonObject(stack + json("futureRelay" to "keep" )).toString()), "navigation")
            println(json("phase" to "combined-before-restart", "course" to "C", "device" to c.sync.deviceId,
                "question" to "q-a", "originalAttempt" to "original-attempt", "originalAnswer" to "A", "attempt" to attempt,
                "command" to command, "ack" to "OK", "agentKey" to agentPage, "book" to localBook, "lesson" to localLesson))
        }

        val agentEnv = mapOf("COACH_URL" to f.base, "COACH_TOKEN_FILE" to f.root.resolve("token").toString(),
            "COACH_DEVICE_ID" to "windows-learner", "COACH_CLIENT_DIR" to f.root.resolve("client").toString(),
            "COACH_ACCEPTANCE_REPLAY" to "1", "COACH_AGENT_RUN_ID" to run)
        val settings = DesktopConnectionSettings(f.root, agentEnv::get, mac = false, property = { null })
        DesktopLearningSession(settings, assertNotNull(DesktopPairing(settings).load()), "C").use { agent ->
            runBlocking { agent.controller.sync.syncOnce() }
            runComposeUiTest {
                setContent { DesktopCourseContent(agent) }
                waitUntil(timeoutMillis = 15_000) { agent.controller.sync.mainlineState("C", agentPage).str("stack").isNotBlank() }
                onNodeWithText("Synthetic exact Windows card").assertDoesNotExist()
            }
            assertNull(reviewPracticeTarget(agent.controller.sync, "C", run))
            assertEquals(run, agent.controller.tutor.agentRunId)
        }
        val settled = f.facts()
        f.offline.set(true)
        f.open(connect = false).use { reopened ->
            val c = reopened.controller
            runComposeUiTest {
                setContent { DesktopCourseContent(reopened) }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Synthetic exact Windows card").assertIsDisplayed()
                onNodeWithText("作答已确认。").assertIsDisplayed()
                onNodeWithTag("review-submit").assertDoesNotExist()
            }
            assertEquals(attempt, reviewCursorAttempt(c.sync, "C"))
            assertEquals(command, c.sync.mainlineState("C", "review-answer:$attempt").str("commandId"))
            assertEquals("OK", c.sync.receipt(command)?.status)
            assertEquals(agentSentinel, c.sync.mainlineState("C", agentPage))
            assertEquals(otherCourse, c.sync.mainlineState("C2", "navigation"))
            assertEquals("keep", Json.parseToJsonElement(c.sync.mainlineState("C", "navigation").str("stack")).jsonObject.str("futureRelay"))
            println(json("phase" to "combined-after-restart", "question" to reviewPracticeTarget(c.sync, "C")?.question?.id,
                "attempt" to attempt, "command" to command, "ack" to c.sync.receipt(command)?.status, "agentKey" to agentPage))
        }
        f.reopenNativeWindow()
        assertEquals(settled, f.facts())
        assertEquals(localAnswer, library.answer(localBook, localLesson, QuestionMode.CHOICE).text)
        val facts = f.facts()
        assertEquals("A", facts.single { it.type == "answer_submitted" && it.payload.str("attemptId") == "original-attempt" }.payload.str("answer"))
        for (type in listOf("question_posed", "answer_submitted", "attempt_graded"))
            assertEquals(1, facts.count { it.type == type && it.payload.str("attemptId") == attempt }, type)
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
