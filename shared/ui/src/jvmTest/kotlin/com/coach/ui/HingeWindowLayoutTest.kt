package com.coach.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.ui.adaptive.Hinge
import com.coach.ui.adaptive.HingeAxis
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * 折痕落在窗口里的三个姿态（Fold8 内屏 704x933dp，铰链 352dp）：
 * 书本半折左右分栏、桌面半折上下分区、外屏无折痕单栏。
 * 每个断言都是读者能看见的东西：导航和正文各在哪一页、有没有一条字压在折痕上。
 */
@OptIn(ExperimentalTestApi::class)
class HingeWindowLayoutTest {
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

    private fun ComposeUiTest.awaitToday() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("today-continue").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    @Test fun bookHalfFoldKeepsNavigationLeftOfTheHingeAndThePageRightOfIt() = runDesktopComposeUiTest(704, 933) {
        setContent { CoachApp(controller(), dark = false, hinge = Hinge(352f)) }
        awaitToday()
        // 还是一个导航（不是底部栏），三种入口都在。
        onAllNodesWithTag("app-navigation").assertCountEquals(1)
        listOf("今天", "课程", "书架").forEach { onNodeWithContentDescription("导航：$it").assertIsDisplayed() }
        val nav = onNodeWithTag("hinge-nav").getUnclippedBoundsInRoot()
        val page = onNodeWithTag("hinge-page").getUnclippedBoundsInRoot()
        assertTrue(nav.right.value <= 352f, "导航页不能越过折痕：${nav.right}")
        assertTrue(page.left.value >= 352f, "内容页要从折痕之后开始：${page.left}")
    }

    @Test fun tabletopHalfFoldKeepsContentAboveTheHingeAndControlsBelow() = runDesktopComposeUiTest(933, 704) {
        setContent { CoachApp(controller(), dark = false, hinge = Hinge(352f, axis = HingeAxis.Horizontal)) }
        awaitToday()
        val content = onNodeWithTag("tabletop-content").getUnclippedBoundsInRoot()
        val controls = onNodeWithTag("tabletop-controls").getUnclippedBoundsInRoot()
        assertTrue(content.bottom.value <= 352f, "上半屏要停在折痕上：${content.bottom}")
        assertTrue(controls.top.value >= 352f, "下半屏从折痕之后开始：${controls.top}")
        onAllNodesWithTag("app-navigation").assertCountEquals(1)
        listOf("今天", "课程", "书架").forEach { onNodeWithContentDescription("导航：$it").assertIsDisplayed() }
    }

    @Test fun bookHalfFoldOnAStudyPageKeepsOneHeaderAndSettingsOnTheNavPage() = runDesktopComposeUiTest(704, 933) {
        setContent { CoachApp(controller(), initialKp = "SYN-KP", dark = false, hinge = Hinge(352f)) }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("v7-desk").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        // 讲解页的案头栏就是这一屏唯一的标题行，外壳不再叠一行。
        onAllNodesWithTag("navigation-back").assertCountEquals(0)
        onAllNodesWithTag("app-navigation").assertCountEquals(1)
        val settings = onNodeWithTag("open-settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(settings.right.value <= 352f, "设置在左边导航页：${settings.right}")
    }

    @Test fun tabletopOnAStudyPageKeepsSettingsBelowTheHinge() = runDesktopComposeUiTest(933, 704) {
        setContent { CoachApp(controller(), initialKp = "SYN-KP", dark = false, hinge = Hinge(352f, axis = HingeAxis.Horizontal)) }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("v7-desk").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onAllNodesWithTag("navigation-back").assertCountEquals(0)
        val settings = onNodeWithTag("open-settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(settings.top.value >= 352f, "设置在下半屏：${settings.top}")
    }

    @Test fun coverScreenStaysSingleColumnWithTheBottomBar() = runDesktopComposeUiTest(475, 751) {
        setContent { CoachApp(controller(), dark = false) }
        awaitToday()
        onAllNodesWithTag("app-navigation").assertCountEquals(1)
        // 外屏没有真折痕：不分栏，页面上只有一条底栏，没有第二条「返回/设置」标题行抢高度。
        onAllNodesWithTag("hinge-nav").assertCountEquals(0)
        onAllNodesWithTag("hinge-page").assertCountEquals(0)
    }
}
