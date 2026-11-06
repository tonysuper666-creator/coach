package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.serialization.json.*
import kotlin.test.*

/** 模拟考的答案不能丢：选完立刻暂离、断线后恢复，Mac 上最后都得有这次作答。卷子是合成的。 */
@OptIn(ExperimentalTestApi::class)
class MockAutosaveTest {
    private class FakeMac : AutoCloseable {
        @Volatile var online = true
        @Volatile var stored = JsonObject(emptyMap())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/C/mocks/M/answers") { ex ->
                val request = Json.parseToJsonElement(ex.requestBody.bufferedReader().readText()).jsonObject
                if (online) stored = request["answers"]!!.jsonObject
                val bytes = (if (online) """{"status":"active"}""" else """{"error":"temporarily offline"}""").toByteArray()
                ex.sendResponseHeaders(if (online) 200 else 503, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test", "synthetic", "C")
        val choice get() = (stored["Q1"] as? JsonObject)?.get("choice")?.jsonPrimitive?.intOrNull
        override fun close() { http.close(); server.stop(0) }
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
