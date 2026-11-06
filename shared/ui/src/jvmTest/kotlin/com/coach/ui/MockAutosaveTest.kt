package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import kotlin.test.*

/** 模拟考的答案不能丢：选完立刻暂离、断线后恢复，Mac 上最后都得有这次作答。卷子是合成的。 */
@OptIn(ExperimentalTestApi::class)
class MockAutosaveTest {
    private class FakeMac : AutoCloseable {
        @Volatile var online = true
        @Volatile var stored = JsonObject(emptyMap())
        /** Saves the Mac has answered. */
        val saves = AtomicInteger()
        private val holding = AtomicBoolean()
        private val released = CountDownLatch(1)
        /** Opens once the save picked by [holdNextSave] has reached the Mac and is waiting there. */
        val held = CountDownLatch(1)
        /** The next save sits on the network, unanswered and not yet stored, until [release]. */
        fun holdNextSave() = holding.set(true)
        fun release() = released.countDown()
        private val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            // A held save must not stop the next one reaching the Mac.
            executor = workers
            createContext("/v1/courses/C/mocks/M/answers") { ex ->
                val request = Json.parseToJsonElement(ex.requestBody.bufferedReader().readText()).jsonObject
                if (holding.getAndSet(false)) { held.countDown(); released.await(10, TimeUnit.SECONDS) }
                if (online) stored = request["answers"]!!.jsonObject
                saves.incrementAndGet()
                val bytes = (if (online) """{"status":"active"}""" else """{"error":"temporarily offline"}""").toByteArray()
                ex.sendResponseHeaders(if (online) 200 else 503, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test", "synthetic", "C")
        val choice get() = (stored["Q1"] as? JsonObject)?.get("choice")?.jsonPrimitive?.intOrNull
        override fun close() { release(); http.close(); server.stop(0); workers.shutdownNow() }
    }

    // The exam never touches sync or the tutor; a bare instance stands in for them.
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(T::class.java) as T
    }

    private fun exam(answers: JsonObject) = Json.parseToJsonElement("""{
        "mockId":"M","serverNow":1000,"deadline":7201000,
        "paper":{"title":"Synthetic paper","sections":[{"title":"Section A","items":[
        {"id":"Q1","type":"mcq","marks":2,"stem":"Synthetic question?","options":["Choice one","Choice two"]}
        ]}]},"answers":$answers
    }""").jsonObject

    /** The exam page, and a stand-in for the review page it leaves to; coming back reloads what the Mac has. */
    private fun ComposeUiTest.mount(mac: FakeMac) {
        val c = CoachController("C", unused(), unused(), formatClock = { "" }, nowMillis = { 1000 }, course = mac.http)
        setContent {
            var inExam by remember { mutableStateOf(true) }
            CoachTheme(false) {
                Column {
                    if (inExam) MockExam(c, mac.http, exam(mac.stored), { inExam = false }, {})
                    else Button(onClick = { inExam = true }) { Text("Back to exam") }
                }
            }
        }
        mainClock.advanceTimeByFrame()
    }

    /** Lets an HTTP reply that arrived in real time reach the screen. */
    private fun ComposeUiTest.settle() { Thread.sleep(200); mainClock.advanceTimeByFrame() }

    private fun ComposeUiTest.assertChoiceOneSelected() =
        onNode(hasText("Choice one") and isSelected()).assertExists()

    @Test fun leavingRightAfterChoosingStillSendsTheAnswerAndKeepsIt() = FakeMac().use { mac ->
        runComposeUiTest {
            mainClock.autoAdvance = false
            mount(mac)
            onNodeWithText("Choice one").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("← 暂离").performClick()
            mainClock.advanceTimeByFrame()
            waitUntil(timeoutMillis = 5000) { mac.choice == 0 }
            onNodeWithText("Back to exam").performClick()
            mainClock.advanceTimeByFrame()
            assertChoiceOneSelected()
        }
    }

    @Test fun answerChosenOfflineSurvivesLeavingAndReachesTheMacLater() = FakeMac().use { mac ->
        mac.online = false
        runComposeUiTest {
            mainClock.autoAdvance = false
            mount(mac)
            onNodeWithText("Choice one").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("← 暂离").performClick()
            mainClock.advanceTimeByFrame()
            settle()
            onNodeWithText("Back to exam").performClick()
            mainClock.advanceTimeByFrame()
            assertChoiceOneSelected()
            mac.online = true
            mainClock.advanceTimeBy(10_000)
            waitUntil(timeoutMillis = 5000) { mac.choice == 0 }
            settle()
            onNodeWithText("答案已存到 Mac", substring = true).assertExists()
        }
    }

    @Test fun savingResumesWhenTheMacComesBackAfterRepeatedIdenticalFailures() = FakeMac().use { mac ->
        mac.online = false
        runComposeUiTest {
            mainClock.autoAdvance = false
            mount(mac)
            onNodeWithText("Choice one").performClick()
            // Several failed saves, every one with the same message.
            repeat(4) { mainClock.advanceTimeBy(9_000); settle() }
            onNodeWithText("temporarily offline").assertExists()
            mac.online = true
            mainClock.advanceTimeBy(30_000)
            waitUntil(timeoutMillis = 5000) { mac.choice == 0 }
            settle()
            onNodeWithText("答案已存到 Mac", substring = true).assertExists()
        }
    }

    @Test fun leavingTwiceWhileTheFirstSaveIsStillOnTheWayKeepsTheNewerAnswer() = FakeMac().use { mac ->
        runComposeUiTest {
            mainClock.autoAdvance = false
            mount(mac)
            mac.holdNextSave()
            onNodeWithText("Choice one").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("← 暂离").performClick()
            mainClock.advanceTimeByFrame()
            assertTrue(mac.held.await(5, TimeUnit.SECONDS), "leaving sends the first answer")
            onNodeWithText("Back to exam").performClick()
            mainClock.advanceTimeByFrame()
            assertChoiceOneSelected()
            onNodeWithText("Choice two").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("← 暂离").performClick()
            mainClock.advanceTimeByFrame()
            // The first save is still stuck on a slow network when the second leave happens; then it gets through.
            Thread.sleep(500)
            mac.release()
            waitUntil(timeoutMillis = 5000) { mac.saves.get() >= 2 }
            settle()
            assertEquals(1, mac.choice, "the Mac ends up with the answer chosen last")
            onNodeWithText("Back to exam").performClick()
            mainClock.advanceTimeByFrame()
            onNode(hasText("Choice two") and isSelected()).assertExists()
        }
    }

    @Test fun savedAnswerIsThereAfterLeavingAndComingBack() = FakeMac().use { mac ->
        runComposeUiTest {
            mainClock.autoAdvance = false
            mount(mac)
            onNodeWithText("Choice one").performClick()
            mainClock.advanceTimeBy(1500)
            waitUntil(timeoutMillis = 5000) { mac.choice == 0 }
            settle()
            onNodeWithText("← 暂离").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("Back to exam").performClick()
            mainClock.advanceTimeByFrame()
            assertChoiceOneSelected()
        }
    }
}
