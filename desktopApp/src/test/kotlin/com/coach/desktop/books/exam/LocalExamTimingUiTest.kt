package com.coach.desktop.books.exam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Offscreen Compose only. These captures are not native Windows/new-PID evidence. */
@OptIn(ExperimentalTestApi::class)
class LocalExamTimingUiTest {
    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/local-timer")
        Files.createDirectories(dir)
        assertTrue(ImageIO.write(onNodeWithTag("timer-root").captureToImage().toAwtImage(), "png", dir.resolve("$name.png").toFile()))
    }

    private fun record(f: TimedExamFixture, controller: LocalExamController, name: String) {
        val dir = Path.of("build/evidence/local-timer")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.json"), json("controlledNow" to f.now.get(),
            "view" to controller.state.value.attempt).toString())
        Files.copy(f.store().databasePath, dir.resolve("$name.sqlite"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test fun authoredSelectorNumericCountdownEssayAndRecreatedControllerUseSameDeadline() {
        val f = TimedExamFixture()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller = LocalExamController(f.library, scope, f.now::get)
        runBlocking { controller.load(f.book) }
        lateinit var id: String
        try {
            runSkikoComposeUiTest(size = Size(900f, 720f), density = Density(1f)) {
                setContent {
                    MaterialTheme {
                        val ui by controller.state.collectAsState()
                        Surface(Modifier.fillMaxSize().testTag("timer-root")) {
                            if (ui.attempt == null) Column(Modifier.verticalScroll(rememberScrollState())) { LocalExamsSummary(controller) }
                            else LocalExamPane(controller)
                        }
                    }
                }
                onNodeWithTag("local-timed-0").performScrollTo().performClick()
                onNodeWithTag("local-minutes-0").performScrollTo().performTextReplacement("0")
                onNodeWithTag("local-paper-start-0").assertIsNotEnabled()
                onNodeWithTag("local-minutes-0").performTextReplacement("1")
                onNodeWithTag("local-paper-start-0").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.attempt != null && !controller.state.value.busy }
                id = controller.state.value.attempt!!.str("mockId")
                onNodeWithTag("local-countdown").assertTextEquals("剩余 01:00")
                capture("01-timed-start-offscreen")
                record(f, controller, "ui-01-start")
                onNodeWithTag("local-text-essay").performScrollTo().performTextReplacement("受控计时论述 😀")
                waitUntil(timeoutMillis = 20_000) { !controller.state.value.saving && f.store().get(f.book.id, id)["answers"]!!.jsonObject["essay"]?.jsonObject?.str("text") == "受控计时论述 😀" }
                f.now.set(1_015_500)
                waitUntil(timeoutMillis = 20_000) { controller.state.value.nowMillis == 1_015_500L }
                onNodeWithTag("local-countdown").performScrollTo().assertTextEquals("剩余 00:45")
                capture("02-counting-offscreen")
                record(f, controller, "ui-02-counting")
            }
            runBlocking { assertTrue(controller.flush()) }
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            controller = LocalExamController(f.library, scope, f.now::get)
            runBlocking { controller.load(f.book); controller.open(id) }
            runSkikoComposeUiTest(size = Size(900f, 720f), density = Density(1f)) {
                setContent { MaterialTheme { Surface(Modifier.fillMaxSize().testTag("timer-root")) { LocalExamPane(controller) } } }
                onNodeWithTag("local-countdown").assertTextEquals("剩余 00:45")
                assertEquals(1_000_000, controller.state.value.attempt!!.num("startedAt"))
                assertEquals(1_060_000, controller.state.value.attempt!!.num("deadline"))
                onNodeWithTag("local-text-essay").performScrollTo().assertTextContains("受控计时论述 😀")
                onNodeWithTag("local-countdown").performScrollTo()
                capture("03-recovered-offscreen")
                record(f, controller, "ui-03-recovered")
                onNodeWithTag("local-submit").performScrollTo().performClick()
                onNodeWithTag("local-confirm-submit").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.attempt?.str("status") == "submitted" }
                onNodeWithText("总分待评").assertExists()
                capture("04-result-offscreen")
                assertEquals(1_015_500, f.store().get(f.book.id, id).num("submittedAt"))
                record(f, controller, "ui-04-result")
            }
        } finally { scope.cancel() }
    }
}
