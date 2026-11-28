package com.coach.desktop.regression

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.CoachConnectivityRegistry
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.incident.IncidentLog
import com.coach.desktop.incident.IncidentPromptLayer
import com.coach.desktop.incident.IncidentStore
import com.coach.desktop.incident.PendingOffer
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
            // A copy that keeps running while another copy of the app starts and closes.
            "hold" -> { println("ready offer=${IncidentLog.currentOffer() != null}"); System.out.flush(); Thread.sleep(120_000) }
            // Windows ending the process at logoff or shutdown, or Ctrl+C: the JVM runs its shutdown hooks.
            "hooks" -> System.exit(3)
            // The app closed itself: the same call runCoachApplication makes after the window loop returns.
            "close" -> {
                println("offer=${IncidentLog.currentOffer() != null}")
                System.out.flush()
                IncidentLog.markStartedSessionClean()
                System.exit(0)
            }
        }
    }
}

/**
 * TON-1941：打包版带课程，走 runCoach 的课程路由，原先这条路既不写会话标记也没有提示位，
 * 强杀后重开停在首页、没有「发送 / 不发」。另外原先靠 JVM 关闭钩子写 clean，系统结束任务时钩子也会跑。
 */
@OptIn(ExperimentalTestApi::class)
class R1941IncidentExitMarkerTest {
    private fun startChild(dir: Path, mode: String): Process {
        val classpath = (generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList() +
            System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
        val args = dir.resolveSibling("${dir.fileName}.args")
        Files.writeString(args, "-cp\n\"" + classpath.replace("\\", "/") + "\"\n")
        return ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=true", "@$args", R1941SessionChild::class.java.name, dir.toString(), mode)
            .redirectErrorStream(true).start()
    }

    private fun runChild(dir: Path, mode: String): String {
        val child = startChild(dir, mode)
        try {
            var output = ""
            if (mode == "kill") {
                val line = child.inputStream.bufferedReader().readLine()
                assertEquals("ready", line)
                assertTrue(Files.readString(dir.resolve("session.txt")).startsWith("open\n"))
                child.destroyForcibly()
            } else {
                output = child.inputStream.bufferedReader().readText()
            }
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child $mode did not end")
            if (mode == "hooks") assertEquals(3, child.exitValue())
            if (mode == "close") assertEquals(0, child.exitValue(), output)
            return output
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
            Files.deleteIfExists(dir.resolveSibling("${dir.fileName}.args"))
        }
    }

    /**
     * QA 退回复现：同一目录先开 A 并保持运行，再开 B，B 不能提示；B 正常关闭后强杀 A，下次打开必须提示。
     * 原先 session.txt 只有一条记录：B 开时把仍在运行的 A 当成异常退出，B 关时又把 A 的记录盖成 clean。
     */
    @Test fun secondCopyNeitherFlagsTheRunningFirstNorHidesItsLaterKill() {
        val root = Files.createTempDirectory("coach-r1941-two-")
        val dir = Files.createDirectories(root.resolve("shared"))
        val holder = startChild(dir, "hold")
        try {
            assertEquals("ready offer=false", holder.inputStream.bufferedReader().readLine())
            val second = runChild(dir, "close")
            assertTrue(second.contains("offer=false"), "the second copy must not report the running first one: $second")
            val marker = Files.readString(dir.resolve("session.txt"))
            assertTrue(marker.startsWith("open\n") && marker.contains("\n${holder.pid()}\n"), "the first copy's entry must stay: $marker")
            assertTrue(dir.toFile().listFiles().orEmpty().none { it.name.contains("unclean_exit") })

            holder.destroyForcibly()
            assertTrue(holder.waitFor(60, TimeUnit.SECONDS))
            val offer = IncidentStore(dir).beginSession()
            assertNotNull(offer, "killing the first copy after the second closed must offer the prompt")
            assertTrue(offer.text.contains("kind: unclean_exit"))
        } finally {
            if (holder.isAlive) holder.destroyForcibly().waitFor()
            root.toFile().deleteRecursively()
        }
    }

    /** The same rules in every order, with process liveness under the test's control. */
    @Test fun eachCopyOnlyClosesItsOwnEntry() {
        val dir = Files.createTempDirectory("coach-r1941-order-")
        try {
            val running = mutableSetOf<Long>()
            fun open(pid: Long): Pair<IncidentStore, PendingOffer?> {
                val store = IncidentStore(dir, pid = { pid }, processStart = { pid * 10 }, alive = { p, s -> p in running && s == p * 10 })
                val offer = store.beginSession()
                running += pid
                return store to offer
            }
            fun IncidentStore.close(pid: Long) { markClean(); running -= pid }

            // A runs, B opens and closes, then A is killed: the next start offers.
            val (a, first) = open(1); assertNull(first)
            val (b, second) = open(2); assertNull(second, "A is still running")
            b.close(2)
            running -= 1
            val (c, third) = open(3); assertNotNull(third, "A was killed")
            c.close(3)
            val (d, fourth) = open(4); assertNull(fourth, "every copy before this one has been handled")
            d.close(4)
            assertTrue(Files.readString(dir.resolve("session.txt")).startsWith("clean\n"))

            // A runs, B opens and is killed, then A closes normally: the next start still offers for B.
            val (e, fifth) = open(5); assertNull(fifth)
            val (_, sixth) = open(6); assertNull(sixth)
            running -= 6
            e.close(5)
            assertTrue(Files.readString(dir.resolve("session.txt")).contains("\n6\n"), "B's open entry must survive A's close")
            val (f, seventh) = open(7); assertNotNull(seventh, "B was killed")
            f.close(7)

            // A pid now used by a process that started later is not the copy that wrote the entry.
            val (_, eighth) = open(8); assertNull(eighth)
            val reused = IncidentStore(dir, pid = { 9 }, processStart = { 90 }, alive = { p, s -> p == 8L && s == 81L })
            assertNotNull(reused.beginSession(), "pid 8 belongs to another process now")
            assertTrue(a.incidentFiles().isNotEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * QA 第二次退回复现：A、B 同开，强杀 B，下次打开对 B 的提示点「不发」，再强杀 A。
     * 原先 B 已处理的记录晚于 A 的启动时间，被当成 A 的记录，A 的异常退出不再新建记录，提示为空。
     */
    @Test fun aLaterKillStillOffersAfterAnEarlierCopysRecordWasDeclined() {
        val dir = Files.createTempDirectory("coach-r1941-declined-")
        try {
            val running = mutableSetOf<Long>()
            fun open(pid: Long): Pair<IncidentStore, PendingOffer?> {
                val store = IncidentStore(dir, pid = { pid }, processStart = { pid * 10 }, alive = { p, s -> p in running && s == p * 10 })
                val offer = store.beginSession()
                running += pid
                return store to offer
            }

            val (_, first) = open(1); assertNull(first)
            val (_, second) = open(2); assertNull(second, "A is still running")
            running -= 2
            val (c, forB) = open(3); assertNotNull(forB, "B was killed")
            c.decline(forB)
            c.markClean(); running -= 3
            running -= 1
            val (_, forA) = open(4)
            assertNotNull(forA, "A was killed after B's prompt was declined")
            assertTrue(forA.files.none { it in forB.files }, "the declined record must not come back: ${forA.files}")
            assertTrue(forA.text.contains("kind: unclean_exit"))
        } finally {
            dir.toFile().deleteRecursively()
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
                // 动作收在徽标后面：先点徽标，再看 查看 / 发送 / 不发。
                onNodeWithTag("incident-exit-prompt").performClick()
                onNodeWithTag("incident-decline").assertIsDisplayed()
                // TON-1985：徽标缩在顶栏角落里（原来压在顶栏下面占一整行）。
                val bar = onNodeWithTag("host-bar").fetchSemanticsNode().boundsInRoot
                val prompt = onNodeWithTag("incident-exit-prompt").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    prompt.top >= bar.top && prompt.bottom <= bar.bottom && prompt.width < bar.width / 2,
                    "prompt=$prompt bar=$bar",
                )
            }
            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                // Owner content has no prompt host; the layer still shows the prompt.
                setContent { IncidentPromptLayer(identity = null, darkTheme = true) { Text("owner course") } }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().size == 1 }
                onNodeWithTag("incident-exit-prompt").performClick()
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
