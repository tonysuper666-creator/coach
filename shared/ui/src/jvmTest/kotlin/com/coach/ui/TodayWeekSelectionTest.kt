package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TodayWeekSelectionTest {
    @Test fun midtermWeekIsVisibleButCannotBeSelected() = runDesktopComposeUiTest(width = 390, height = 800) {
        val f = Fixture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(f.controller, scope)
        val outline = Event(1, "outline", "C", "outline_revised", recordedAt = 1, occurredAt = 1, deviceId = "d", actor = "LEARNER", payload = json(
            "week7" to json("knowledgePoints" to 0, "reason" to "Midterm Exam"),
            "ops" to JsonArray(listOf(json("op" to "add", "kpId" to "kp6", "title" to "【W6】甲"), json("op" to "add", "kpId" to "kp8", "title" to "【W8】乙")))))
        var current by mutableStateOf(CourseView(listOf(outline), project(listOf(outline))))
        val exemption = "第 7 周 · 期中周（无新课，豁免）"
        try {
            setContent { CoachTheme(false) { TodayScreen(f.controller, current, TutorUiState(connected = true), null, {}, { _, _ -> }, actions) } }
            onNodeWithText(exemption).assertIsDisplayed().assertIsNotEnabled().performClick()
            assertTrue(f.transport.calls.isEmpty())
            runOnIdle { current = CourseView(current.events, current.projection.copy(studyScope = StudyScope("s", 6, listOf("kp6"), cursor = 1))) }
            onNodeWithText("第 6 周 · 换周 ▾").performClick()
            onNodeWithText(exemption).assertIsDisplayed().assertIsNotEnabled()
            onNodeWithText("第 8 周 · 1 个待学").performClick()
            waitUntil(timeoutMillis = 5000) { synchronized(f.transport.calls) { f.transport.calls.isNotEmpty() } }
            val body = LedgerJson.decodeFromString<CommandBody>(synchronized(f.transport.calls) { f.transport.calls.single() }.payloadBytes)
            assertEquals(8, body.payload.num("week").toInt())
            waitUntil(timeoutMillis = 5000) { !actions.busy }
        } finally { scope.cancel(); f.close() }
    }
    private fun view(weeks: Boolean = true, scoped: Boolean = false): CourseView {
        val titles = if (weeks) listOf("【W1】甲", "【W2】乙") else listOf("无周次")
        val event = Event(1, "outline", "C", "outline_revised", recordedAt = 1, occurredAt = 1, deviceId = "d", actor = "LEARNER", payload = json(
            "ops" to JsonArray(titles.mapIndexed { i, title -> json("op" to "add", "kpId" to "kp$i", "title" to title) })))
        val events = listOf(event)
        val projection = project(events).let { if (scoped) it.copy(studyScope = StudyScope("scope", 1, listOf("kp0"))) else it }
        return CourseView(events, projection)
    }

    private class Transport : LedgerTransport {
        val calls = mutableListOf<WireCommand>()
        var status = "OK"
        var target: String? = "kp1"
        var gate: CompletableDeferred<Unit>? = null
        val hello = Hello("epoch", 0, 0, 0)
        override suspend fun hello(epoch: String?) = hello
        override suspend fun snapshot(course: String) = Snapshot(hello, course, emptyList(), project(emptyList()))
        override suspend fun events(course: String, after: Long) = EventsPage("epoch", emptyList(), after)
        override suspend fun command(command: WireCommand): Receipt {
            synchronized(calls) { calls.add(command) }
            gate?.await()
            return Receipt(command.commandId, status, result = json("target" to target.orEmpty()), error = "rejected", epoch = "epoch", backedUpThroughSeq = 0)
        }
        override suspend fun watch(onLedger: suspend () -> Unit) {}
    }

    private class Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ClientDatabase.Schema.create(it) }
        val transport = Transport()
        var ids = 0
        val sync = SyncEngine(ClientDatabase(driver), "d", transport, listOf("C"), { "id${++ids}" }, { "hash" })
        val tutor = TutorClient("http://127.0.0.1:12345", "d", "token", TutorModel({ 0 }))
        val controller = CoachController("C", sync, tutor, formatClock = { "" }, nowMillis = { 1000 })
        fun close() { tutor.close(); driver.close() }
    }

    @Test fun singleTapWaitsForReceiptAndIgnoresRepeatedSelection() = runDesktopComposeUiTest(width = 390, height = 800) {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>(); f.transport.gate = gate
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(f.controller, scope)
        val opened = mutableListOf<String?>(); actions.onOpen = { opened.add(it) }
        try {
            setContent { CoachTheme(false) { TodayScreen(f.controller, view(), TutorUiState(connected = true), null, {}, { _, _ -> }, actions) } }
            onNodeWithText("第 1 周 · 1 个待学").assertIsDisplayed()
            onNodeWithText("第 2 周 · 1 个待学").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5000) { synchronized(f.transport.calls) { f.transport.calls.size == 1 } }
            onNodeWithText("第 1 周 · 1 个待学").assertIsNotEnabled()
            runOnIdle { actions.select(1); assertTrue(opened.isEmpty()) }
            gate.complete(Unit)
            waitUntil(timeoutMillis = 5000) { opened.isNotEmpty() }
            assertEquals(listOf<String?>("kp1"), opened.toList())
            val command = synchronized(f.transport.calls) { f.transport.calls.single() }
            val body = LedgerJson.decodeFromString<CommandBody>(command.payloadBytes)
            assertEquals("set_study_scope", body.type)
            assertEquals(2, body.payload.num("week").toInt())
        } finally { scope.cancel(); f.close() }
    }

    @Test fun offlineFailureReconnectAndEmptyTargetStayOnToday() = runDesktopComposeUiTest(width = 390, height = 800) {
        val f = Fixture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(f.controller, scope)
        val opened = mutableListOf<String?>(); actions.onOpen = { opened.add(it) }
        var connected by mutableStateOf(false)
        var current by mutableStateOf(view())
        try {
            setContent { CoachTheme(false) { TodayScreen(f.controller, current, TutorUiState(connected = connected), null, {}, { _, _ -> }, actions) } }
            onNodeWithText("连接 Mac 后可选择周次。").assertIsDisplayed()
            onNodeWithText("第 2 周 · 1 个待学").assertIsNotEnabled()
            assertTrue(f.transport.calls.isEmpty())
            runOnIdle { connected = true; f.transport.status = "REJECTED" }
            onNodeWithText("第 2 周 · 1 个待学").performClick()
            waitUntil(timeoutMillis = 5000) { actions.error != null }
            assertTrue(opened.isEmpty())
            onNodeWithText("未能更新学习范围，请连接 Mac 后重试；若已在别的设备推进，以最新进度为准。").assertIsDisplayed()
            runOnIdle { connected = false }
            runBlocking { f.sync.syncOnce() }
            runOnIdle { connected = true; assertTrue(opened.isEmpty()) }
            runOnIdle {
                f.transport.status = "OK"; f.transport.target = null
                current = CourseView(current.events, current.projection.copy(mastery = mapOf("kp1" to Mastery(passed = true))))
            }
            onNodeWithText("第 2 周 · 0 个待学").performClick()
            waitUntil(timeoutMillis = 5000) { opened.isNotEmpty() }
            assertEquals(listOf<String?>(null), opened.toList())
        } finally { scope.cancel(); f.close() }
    }

    @Test fun scopedPickerAndEmptyWeekListKeepTheirExistingExits() = runDesktopComposeUiTest(width = 390, height = 800) {
        val f = Fixture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(f.controller, scope)
        var current by mutableStateOf(view(scoped = true))
        try {
            setContent { CoachTheme(false) { TodayScreen(f.controller, current, TutorUiState(connected = true), null, {}, { _, _ -> }, actions) } }
            onNodeWithText("开始下一个").assertIsDisplayed()
            onNodeWithText("甲").assertIsDisplayed()
            onNodeWithText("第 1 周 · 换周 ▾").performClick()
            onNodeWithText("第 2 周 · 1 个待学").assertIsDisplayed()
            onNodeWithText("全部课程").assertIsDisplayed()
            runOnIdle { current = view(weeks = false) }
            onNodeWithText("暂无可选周次。").assertIsDisplayed()
        } finally { scope.cancel(); f.close() }
    }

    @Test fun finishedWeekStillOffersReviewAndNextWeek() = runDesktopComposeUiTest(width = 390, height = 800) {
        val f = Fixture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(f.controller, scope)
        var reviewed = 0; actions.onReview = { reviewed = it }
        val base = view(scoped = true)
        val finished = CourseView(base.events, base.projection.copy(studyScope = StudyScope("scope", 1, listOf("kp0"), cursor = 1)))
        try {
            setContent { CoachTheme(false) { TodayScreen(f.controller, finished, TutorUiState(connected = true), null, {}, { _, _ -> }, actions) } }
            onNodeWithText("再练这一周错题").assertIsDisplayed().performClick()
            assertEquals(1, reviewed)
            onNodeWithText("换下一周").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5000) { synchronized(f.transport.calls) { f.transport.calls.isNotEmpty() } }
            val body = LedgerJson.decodeFromString<CommandBody>(synchronized(f.transport.calls) { f.transport.calls.single() }.payloadBytes)
            assertEquals(2, body.payload.num("week").toInt())
            waitUntil(timeoutMillis = 5000) { !actions.busy }
        } finally { scope.cancel(); f.close() }
    }
}
