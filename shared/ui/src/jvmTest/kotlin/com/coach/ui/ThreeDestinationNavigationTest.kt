package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.ui.adaptive.WindowSpec
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalDeskWindow
import kotlinx.serialization.json.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ThreeDestinationNavigationTest {
    private fun app(dark: Boolean, legacy: Boolean = false) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val now = 1_794_571_200_000L
        val event = Event(1, "SYN-EVENT", "SYN", "outline_revised", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("ops" to JsonArray(listOf(json("kpId" to "SYN-KP", "title" to "【W3】合成示例：从问题开始")))))
        db.ledgerQueries.putEvent(1, "SYN", LedgerJson.encodeToString(Event.serializer(), event))
        val exam = Event(2, "SYN-EXAM", "SYN", "exam_set", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("examDay" to now / 86_400_000 + 32))
        db.ledgerQueries.putEvent(2, "SYN", LedgerJson.encodeToString(Event.serializer(), exam))
        val sync = SyncEngine(db, "synthetic", noNavigationNetwork, listOf("SYN"), { "id" }, { "hash" })
        if (legacy) {
            val fixtures = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/navigation/expert-v2-legacy-cases.json")).readText()).jsonObject
            val old = fixtures.getValue("cases").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "built-records-mock" }
                .jsonObject.getValue("old_input").jsonPrimitive.content
            sync.saveMainlineState("SYN", json("stack" to old), "navigation")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val controller = CoachController("SYN", sync, tutor, formatClock = { "12:00" }, nowMillis = { now })
        try {
            runDesktopComposeUiTest(1440, 900) {
                setContent { CoachApp(controller, dark = dark, settings = { Text("合成提醒设置") }) }
                if (legacy) {
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("模拟考要连接 Mac。").fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle()
                    val stored = sync.mainlineState("SYN", "navigation").getValue("stack").jsonPrimitive.content
                    assertEquals(2, Json.parseToJsonElement(stored).jsonObject.getValue("version").jsonPrimitive.int)
                    val task = assertNotNull(AppNavigation.restore(stored)).current
                    assertEquals("SYN-MOCK-1", task.id)
                    assertEquals(AppTab.RECORDS, task.tab)
                    onNodeWithText("← 记录").performClick()
                    onNodeWithContentDescription("导航：课程").assertIsSelected()
                    onNodeWithContentDescription("导航：今天").performClick()
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("today-continue").fetchSemanticsNodes().isNotEmpty() }
                fun nav() {
                    onAllNodesWithTag("app-navigation").assertCountEquals(1)
                    listOf("今天", "课程", "书架").forEach { onNodeWithContentDescription("导航：$it").assertIsDisplayed() }
                    listOf("路线", "复习", "记录", "资料").forEach { onNodeWithContentDescription("导航：$it").assertDoesNotExist() }
                }
                fun capture(page: String) {
                    val file = File("build/screens/navigation-$page-${if (dark) "dark" else "light"}.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                }
                nav()
                capture("today")
                // Two real clicks from Today to the existing mock task (one from Course).
                onNodeWithContentDescription("导航：课程").performClick()
                onNodeWithTag("course-mock").assertIsDisplayed().performClick()
                onNodeWithText("模拟考要连接 Mac。").assertIsDisplayed()
                onAllNodesWithTag("app-navigation").assertCountEquals(0)
                assertEquals(AppPage.MOCK, AppNavigation.restore(sync.mainlineState("SYN", "navigation").getValue("stack").jsonPrimitive.content)!!.current.page)
                onNodeWithText("← 课程").performClick()
                nav()
                capture("course")
                onNodeWithTag("course-essay").performClick()
                onNodeWithText("论述限时练习").assertIsDisplayed()
                assertEquals(AppPage.ESSAY, AppNavigation.restore(sync.mainlineState("SYN", "navigation").getValue("stack").jsonPrimitive.content)!!.current.page)
                onNodeWithText("暂离 · 课程").performClick()
                nav()
                onNodeWithTag("course-records").performClick()
                waitForIdle()
                assertEquals(AppTab.RECORDS, AppNavigation.restore(sync.mainlineState("SYN", "navigation").getValue("stack").jsonPrimitive.content)!!.current.tab)
                nav()
                onNodeWithTag("navigation-back").performClick()
                onNodeWithTag("course-mock").assertIsDisplayed()
                val settings = onNodeWithTag("open-settings").fetchSemanticsNode().boundsInRoot
                assertTrue(settings.center.x > 1200, "Settings is in the top right")
                assertTrue(settings.center.y < 100, "Settings stays in the top bar")
                onNodeWithTag("open-settings").performClick()
                onNodeWithText("合成提醒设置").assertIsDisplayed()
                onNodeWithTag("navigation-back").performClick()
                onNodeWithContentDescription("导航：书架").performClick()
                nav()
                onNodeWithTag("library-title").assertTextEquals("书架").assertIsDisplayed()
                capture("library")
            }
        } finally { tutor.close(); driver.close() }
    }

    @Test fun windowsSizedSharedAppLightRestoresLegacyTaskAndHasThreeEntriesAndTwoClickExams() = app(false, legacy = true)
    @Test fun windowsSizedSharedAppDarkHasThreeEntriesAndTwoClickExams() = app(true)

    @Test fun managedDesktopDeskHasOnlyPaperAndAsideWithDirectoryInDrawerAcrossWindowSizes() {
        runDesktopComposeUiTest(1600, 1000) {
            var width by mutableStateOf(1500)
            setContent {
                CoachTheme(false) {
                    CompositionLocalProvider(LocalAppManagedNavigation provides true, LocalDeskWindow provides WindowSpec(width.toFloat(), 900f)) {
                        Box(Modifier.size(width.dp, 900.dp)) {
                            Desk("合成题目", directory = { Text("合成周目录") }, margin = { Text("合成出处") }) { Text("合成正文") }
                        }
                    }
                }
            }
            for (size in listOf(1500, 1100, 900, 1500)) {
                runOnIdle { width = size }
                waitForIdle()
                onNodeWithTag("v7-paper").assertIsDisplayed()
                onNodeWithTag("v7-margin").assertIsDisplayed()
                onNodeWithTag("v7-directory").assertDoesNotExist()
                onNodeWithText("合成周目录").assertDoesNotExist()
            }
            onNodeWithTag("v7-directory-toggle").performClick()
            onNodeWithTag("v7-directory-drawer").assertIsDisplayed()
            onNodeWithText("合成周目录").assertIsDisplayed()
        }
    }

    @Test fun standaloneDeskRetainsTaskAcrossWindowChangesAndKeepsDirectoryInDrawer() {
        runDesktopComposeUiTest(1600, 1000) {
            var width by mutableStateOf(1500)
            setContent {
                CoachTheme(false) {
                    Box(Modifier.size(width.dp, 900.dp)) {
                        Desk("合成题目", directory = { Text("合成周目录") }, margin = { Text("合成出处") }) {
                            var selected by remember { mutableStateOf(false) }
                            Button(onClick = { selected = true }) { Text(if (selected) "已选择 B" else "选择 B") }
                        }
                    }
                }
            }
            onNodeWithText("选择 B").performClick()
            for (size in listOf(1500, 1100, 900, 411, 1500)) {
                runOnIdle { width = size }
                waitForIdle()
                onNodeWithText("已选择 B").assertIsDisplayed()
                onNodeWithTag("v7-paper").assertIsDisplayed()
                onNodeWithTag("v7-directory").assertDoesNotExist()
                onNodeWithTag("v7-directory-toggle").assertIsDisplayed()
                if (size >= 700) onNodeWithTag("v7-margin").assertIsDisplayed()
                else onNodeWithTag("v7-margin").assertDoesNotExist()
                if (size == 1500 || size == 411) {
                    val file = File("build/screens/standalone-desk-$size.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(onNodeWithTag("v7-desk").captureToImage().toAwtImage(), "png", file)
                }
            }
            onNodeWithTag("v7-directory-toggle").performClick()
            onNodeWithText("合成周目录").assertIsDisplayed()
            onNodeWithText("收起目录").performClick()
            onNodeWithText("已选择 B").assertIsDisplayed()
        }
    }
}
