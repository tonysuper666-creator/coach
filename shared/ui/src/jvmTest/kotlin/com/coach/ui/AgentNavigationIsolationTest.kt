package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.flag
import com.coach.domain.json
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AgentNavigationIsolationTest {
    private fun scenario(learnerOpen: Boolean) {
        val file = Files.createTempFile("synthetic-agent-navigation", ".sqlite").toFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        ClientDatabase.Schema.create(driver)
        val sync = SyncEngine(ClientDatabase(driver), "synthetic", noNavigationNetwork, listOf("A", "B"), { "id" }, { "hash" })
        val learner = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ 1L }))
        val agent = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ 1L }), agentRunId = "run-A")
        val learnerController = CoachController("A", sync, learner, formatClock = { "12:00" })
        val agentController = CoachController("A", sync, agent, formatClock = { "12:00" })
        val active = json("open" to learnerOpen, "week" to 3, "targetKp" to "learner-kp", "future" to "keep")
        val question = json("item" to "learner-question", "choice" to 2, "pending" to json("id" to "learner-pending"))
        val otherCourse = json("open" to true, "targetKp" to "other-course-kp")
        sync.saveMainlineState("A", active)
        sync.saveMainlineState("A", question, "week:3")
        sync.saveMainlineState("B", otherCourse)
        val agentPage = "navigation:agent:5:run-A"
        if (!learnerOpen) {
            // An existing agent task must not open the learner's legacy flag.
            val stack = AppNavigation(listOf(AppEntry(), AppEntry(AppPage.MAINLINE, id = "agent-task", week = 8))).encode()
            sync.saveMainlineState("A", json("stack" to stack), agentPage)
        }
        try {
            runComposeUiTest {
                var isAgent by mutableStateOf(false)
                setContent { key(isAgent) { CoachApp(if (isAgent) agentController else learnerController, dark = false) } }
                waitForIdle()
                val learnerStack = sync.mainlineState("A", "navigation")
                val before = AppNavigation.restore(learnerStack.getValue("stack").jsonPrimitive.content)!!
                assertEquals(if (learnerOpen) AppPage.MAINLINE else AppPage.HOME, before.current.page)
                assertEquals(active, sync.mainlineState("A"))
                runOnIdle { isAgent = true }
                waitForIdle()
                val agentNav = AppNavigation.restore(sync.mainlineState("A", agentPage).getValue("stack").jsonPrimitive.content)!!
                assertEquals(if (learnerOpen) AppPage.HOME else AppPage.MAINLINE, agentNav.current.page,
                    "An agent without a stack must not inherit the learner's legacy open flag")
                if (!learnerOpen) assertEquals("agent-task", agentNav.current.id)
                assertEquals(active, sync.mainlineState("A"), "Agent navigation must never change learner open or task identity")
                onNodeWithTag("open-settings").performClick()
                onNodeWithTag("navigation-back").performClick()
                waitForIdle()
                assertEquals(active, sync.mainlineState("A"))
                assertEquals(learnerStack, sync.mainlineState("A", "navigation"))
                assertEquals(question, sync.mainlineState("A", "week:3"))
                assertEquals(otherCourse, sync.mainlineState("B"))
                runOnIdle { isAgent = false }
                waitForIdle()
                assertEquals(before.current.page, AppNavigation.restore(sync.mainlineState("A", "navigation").getValue("stack").jsonPrimitive.content)!!.current.page)
                assertEquals(active, sync.mainlineState("A"))
            }
        } finally {
            learner.close(); agent.close(); driver.close()
        }
        val reopened = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        try {
            val disk = SyncEngine(ClientDatabase(reopened), "synthetic", noNavigationNetwork, listOf("A", "B"), { "id" }, { "hash" })
            assertEquals(active, disk.mainlineState("A"))
            assertEquals(question, disk.mainlineState("A", "week:3"))
            assertEquals(otherCourse, disk.mainlineState("B"))
            assertEquals(learnerOpen, disk.mainlineState("A").flag("open"))
            assertNotNull(disk.mainlineState("A", agentPage)["stack"])
        } finally { reopened.close(); file.delete() }
    }

    @Test fun agentWithoutStackDoesNotInheritLearnerOpen() = scenario(true)
    @Test fun agentMainlineDoesNotOpenClosedLearner() = scenario(false)
}
