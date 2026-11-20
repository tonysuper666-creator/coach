package com.coach.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.Desk
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * 外屏（Fold8 合上）是这块应用最窄的窗口：1248px 约 475dp，再窄的机器只到 320dp 级。
 * 这里盯两件读者能看见的事：案头栏的按钮不叠在一起；整屏只有一条标题行，设置落在底栏。
 */
@OptIn(ExperimentalTestApi::class)
class NarrowCoverLayoutTest {
    private fun controller(): CoachController {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val now = 1_794_571_200_000L
        val outline = Event(1, "SYN-EVENT", "SYN", "outline_revised", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("ops" to JsonArray(listOf(json("kpId" to "SYN-KP", "title" to "【W3】合成示例：从问题开始")))))
        db.ledgerQueries.putEvent(1, "SYN", LedgerJson.encodeToString(Event.serializer(), outline))
        val exam = Event(2, "SYN-EXAM", "SYN", "exam_set", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("examDay" to now / 86_400_000 + 32))
        db.ledgerQueries.putEvent(2, "SYN", LedgerJson.encodeToString(Event.serializer(), exam))
        val sync = SyncEngine(db, "synthetic", noNavigationNetwork, listOf("SYN"), { "id" }, { "hash" })
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        return CoachController("SYN", sync, tutor, formatClock = { "12:00" }, nowMillis = { now })
    }

    private fun ComposeUiTest.waitTag(tag: String) =
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun deskHeaderFitsTheNarrowestCover() = runDesktopComposeUiTest(320, 700) {
        setContent {
            CoachTheme(dark = false) {
                Desk(crumb = "第 3 周 · 数据流", onBack = {}, backLabel = "← 第 3 周", directory = { Text("目录") }) { Text("正文") }
            }
        }
        waitForIdle()
        val directory = onNodeWithTag("v7-directory-toggle").getUnclippedBoundsInRoot()
        val focus = onNodeWithTag("v7-focus").getUnclippedBoundsInRoot()
        assertTrue(directory.right.value <= focus.left.value, "窄屏上「目录」和右侧按钮不能叠在一起：${directory.right} / ${focus.left}")
        val paper = onNodeWithTag("v7-paper").getUnclippedBoundsInRoot()
        assertTrue(paper.right.value - paper.left.value <= 320f, "纸面不能超出窗口")
        onNodeWithTag("v7-directory-toggle").assertIsDisplayed()
        onNodeWithTag("v7-focus").assertIsDisplayed()
    }

    @Test fun coverStudyPageHasOneHeaderAndSettingsInTheBottomRow() = runDesktopComposeUiTest(475, 751) {
        setContent { CoachApp(controller(), initialKp = "SYN-KP", dark = false) }
        waitTag("v7-desk")
        // 讲解页自带 V7 案头栏；外壳不再压第二行标题。
        onAllNodesWithTag("navigation-back").assertCountEquals(0)
        onAllNodesWithTag("app-navigation").assertCountEquals(1)
        listOf("今天", "课程", "书架").forEach { onNodeWithContentDescription("导航：$it").assertIsDisplayed() }
        // 设置挪到底栏右侧，仍然够得着。
        val settings = onNodeWithTag("open-settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        val mid = (settings.top.value + settings.bottom.value) / 2f
        assertTrue(mid > 600f, "设置应该在底栏里：$mid")
    }

    @Test fun narrowestCoverKeepsTheSettingsReachable() = runDesktopComposeUiTest(320, 700) {
        setContent { CoachApp(controller(), initialKp = "SYN-KP", dark = false) }
        waitTag("v7-desk")
        onAllNodesWithTag("navigation-back").assertCountEquals(0)
        onNodeWithTag("open-settings").assertIsDisplayed()
        onNodeWithTag("v7-directory-toggle").assertIsDisplayed()
    }
}
