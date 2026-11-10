package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.*
import com.coach.domain.*
import com.coach.ui.v7.WeekPage
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test

/** Actual week page, synthetic ledger, no service or production date writes. */
@OptIn(ExperimentalTestApi::class)
class WeekCountdownTest {
    private fun checkWidth(width: Int, managed: Boolean = false) {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val events = listOf(Event(1, "outline", "SYN", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1】合成要点"))))),
            Event(2, "exam", "SYN", "exam_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("examDay" to 20802)))
        val view = CourseView(events, project(events))
        SyntheticLocalLedger().use { ledger ->
            val tutor = TutorClient("http://127.0.0.1:18959", "synthetic", "synthetic", TutorModel({ now }))
            try {
                val controller = CoachController("SYN", ledger.sync, tutor, formatClock = { "" }, nowMillis = { now })
                for (dark in listOf(false, true)) {
                    runSkikoComposeUiTest(size = Size(width.toFloat(), 800f), density = Density(1f, 1.15f)) {
                        setContent { CompositionLocalProvider(LocalAppManagedNavigation provides managed) { CoachTheme(dark) { Surface {
                            WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 1)
                        } } } }
                        onAllNodesWithText("距期末 35 天").assertCountEquals(1)
                        onNodeWithText("距期末 35 天").assertIsDisplayed()
                        onNodeWithTag("v7-focus").performClick()
                        onAllNodesWithText("距期末 35 天").assertCountEquals(1)
                        onNodeWithText("距期末 35 天").assertIsDisplayed()
                        onNodeWithTag("v7-focus").performClick()
                        val out = File("build/screens/week-countdown").also { it.mkdirs() }
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$width-${if (managed) "managed" else "standalone"}-${if (dark) "dark" else "light"}.png"))
                    }
                }
            } finally { tutor.close() }
        }
    }

    @Test fun narrowPhoneShowsCountdown() = checkWidth(320)
    @Test fun phoneShowsCountdown() = checkWidth(411)
    @Test fun unfoldedMediumWindowShowsCountdown() = checkWidth(704)
    @Test fun desktopKeepsOneCountdown() = checkWidth(1224)
    @Test fun managedNarrowPhoneShowsCountdown() = checkWidth(320, managed = true)
    @Test fun managedPhoneShowsCountdown() = checkWidth(411, managed = true)
    @Test fun managedUnfoldedMediumWindowShowsCountdown() = checkWidth(704, managed = true)
    @Test fun managedDesktopKeepsOneCountdown() = checkWidth(1224, managed = true)
}
