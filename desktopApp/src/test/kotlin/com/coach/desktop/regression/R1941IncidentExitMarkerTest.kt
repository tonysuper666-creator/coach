package com.coach.desktop.regression

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.CoachConnectivityRegistry
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.incident.IncidentLog
import com.coach.desktop.incident.IncidentPromptLayer
import com.coach.desktop.incident.IncidentStore
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starts a real session in its own JVM, then ends the way the parent asks. */
internal object R1941SessionChild {
    @JvmStatic fun main(args: Array<String>) {
        val dir = args[0]
        IncidentLog.startSession(Path.of(dir)) { if (it == "COACH_INCIDENT_DIR") dir else null }
        when (args[1]) {
            // Hard kill / power cut: the parent ends this process with TerminateProcess.
            "kill" -> { println("ready"); System.out.flush(); Thread.sleep(120_000) }
            // Windows ending the process at logoff or shutdown, or Ctrl+C: the JVM runs its shutdown hooks.
            "hooks" -> System.exit(3)
            // The app closed itself: the same call runCoachApplication makes after the window loop returns.
            "close" -> { IncidentLog.markStartedSessionClean(); System.exit(0) }
        }
    }
}

/**
 * TON-1941：打包版带课程，走 runCoach 的课程路由，原先这条路既不写会话标记也没有提示位，
 * 强杀后重开停在首页、没有「发送 / 不发」。另外原先靠 JVM 关闭钩子写 clean，系统结束任务时钩子也会跑。
 */
@OptIn(ExperimentalTestApi::class)
class R1941IncidentExitMarkerTest {
    private fun runChild(dir: Path, mode: String) {
        val classpath = (generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList() +
            System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
        val args = dir.resolveSibling("${dir.fileName}.args")
        Files.writeString(args, "-cp\n\"" + classpath.replace("\\", "/") + "\"\n")
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=true", "@$args", R1941SessionChild::class.java.name, dir.toString(), mode)
            .redirectErrorStream(true).start()
        try {
            if (mode == "kill") {
                val line = child.inputStream.bufferedReader().readLine()
                assertEquals("ready", line)
                assertTrue(Files.readString(dir.resolve("session.txt")).startsWith("open\n"))
                child.destroyForcibly()
            }
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child $mode did not end")
            if (mode == "hooks") assertEquals(3, child.exitValue())
            if (mode == "close") assertEquals(0, child.exitValue(), child.inputStream.bufferedReader().readText())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
            Files.deleteIfExists(args)
        }
    }

    @Test fun onlyTheAppsOwnCloseSkipsThePromptOnTheNextStart() {
        val root = Files.createTempDirectory("coach-r1941-exit-")
        try {
            for (mode in listOf("kill", "hooks")) {
                val dir = Files.createDirectories(root.resolve(mode))
                runChild(dir, mode)
                val offer = IncidentStore(dir).beginSession()
                assertNotNull(offer, "$mode must leave the session open")
                assertTrue(offer.text.contains("kind: unclean_exit"), mode)
            }
            val dir = Files.createDirectories(root.resolve("close"))
            runChild(dir, "close")
            assertTrue(Files.readString(dir.resolve("session.txt")).startsWith("clean\n"))
            assertNull(IncidentStore(dir).beginSession(), "a normal close must not offer the prompt")
            assertFalse(Files.exists(dir.resolve("session.txt.part")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun courseRouteStartsTheSessionAndClosesThroughTheMarkingWrapper() {
        val source = locateDesktopMain().resolve("com/coach/desktop/CoachDesktop.kt").readText()
        val body = source.substringAfter("fun runCoach() {").substringBefore("\n}\n")
        val start = body.indexOf("IncidentLog.startSession(settings.home)")
        val app = body.indexOf("runCoachApplication {")
        assertTrue(start >= 0 && app > start, "runCoach must start the incident session before its window loop")
        assertFalse(Regex("""(?<![A-Za-z])application\s*\{""").containsMatchIn(body), "runCoach must not bypass runCoachApplication")
        assertTrue(body.contains("IncidentPromptLayer("), "runCoach must give the home page a place for the prompt")
        val textbooks = locateDesktopMain().resolve("com/coach/desktop/WindowsTextbooks.kt").readText()
            .substringAfter("internal fun runWindowsTextbooks() {").substringBefore("\n}\n")
        assertTrue(textbooks.contains("runCoachApplication {"))
        assertFalse(Regex("""(?<![A-Za-z])application\s*\{""").containsMatchIn(textbooks))
        val log = locateDesktopMain().resolve("com/coach/desktop/incident/IncidentLog.kt").readText()
        assertFalse(log.contains("addShutdownHook"), "a shutdown hook would mark Windows-ended sessions clean")
    }

    @Test fun promptLayerShowsTheOfferOnTheCourseRouteHomeAndWithoutAHost() {
        CoachConnectivityRegistry.resetForTests()
        val root = Files.createTempDirectory("coach-r1941-layer-").toRealPath()
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val incidentDir = Files.createDirectories(root.resolve("incidents"))
            assertNull(IncidentStore(incidentDir, pid = { 999_998L }).beginSession())
            IncidentLog.startSession(home) { if (it == "COACH_INCIDENT_DIR") incidentDir.toString() else null }
            assertNotNull(IncidentLog.currentOffer())

            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            Files.writeString(root.resolve("sample.md"), "# Chapter 1: Synthetic\n\nA synthetic line for the shelf.\n")
            library.importBook(root.resolve("sample.md"))
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                // Friend installs with a packaged course render the textbook home inside the layer.
                setContent { IncidentPromptLayer(identity = null, darkTheme = false) { WindowsTextbookHost(controller) { null } } }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("host-bar").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().size == 1
                }
                onNodeWithTag("incident-decline").assertIsDisplayed()
                val bar = onNodeWithTag("host-bar").fetchSemanticsNode().boundsInRoot
                val prompt = onNodeWithTag("incident-exit-prompt").fetchSemanticsNode().boundsInRoot
                assertTrue(prompt.top >= bar.bottom - 1f, "prompt=$prompt bar=$bar")
            }
            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                // Owner content has no prompt host; the layer still shows the prompt.
                setContent { IncidentPromptLayer(identity = null, darkTheme = true) { Text("owner course") } }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().size == 1 }
                onNodeWithTag("incident-send").assertIsDisplayed()
            }
        } finally {
            IncidentLog.resetForTest()
            root.toFile().deleteRecursively()
        }
    }

    private fun locateDesktopMain(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main/kotlin"), File(dir, "desktopApp/src/main/kotlin"))
                .firstOrNull { File(it, "com/coach/desktop/CoachDesktop.kt").isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("desktopApp/src/main/kotlin not found")
    }
}
