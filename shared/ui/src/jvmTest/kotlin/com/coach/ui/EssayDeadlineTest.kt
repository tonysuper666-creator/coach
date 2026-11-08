package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * 限时论述到点交卷不看练习页在不在屏幕上：写到一半回复习页，停过截止和宽限，Mac 仍在截止那一刻收到这篇，且只收到一次。
 * 真的本机队列（内存 SQLite 上的 SyncEngine）+ 一台在线的假 Mac；时间是虚拟的，25 分钟不用真等。
 */
@OptIn(ExperimentalTestApi::class)
class EssayDeadlineTest {
    /** 在线的 Mac：照单全收，记下每条命令在虚拟时钟上几点送到。 */
    private class FakeMac(private val now: () -> Long) : LedgerTransport {
        val received = CopyOnWriteArrayList<Pair<Long, CommandBody>>()
        private var seq = 0L
        private fun hello() = Hello("e1", seq, 0, seq)
        override suspend fun hello(epoch: String?) = hello()
        override suspend fun snapshot(course: String) = Snapshot(hello(), course, emptyList(), Projection())
        override suspend fun events(course: String, after: Long) = EventsPage("e1", emptyList(), after)
        override suspend fun command(command: WireCommand): Receipt {
            received += now() to LedgerJson.decodeFromString<CommandBody>(command.payloadBytes)
            return Receipt(command.commandId, "OK", listOf(++seq), epoch = "e1", backedUpThroughSeq = 0)
        }
        override suspend fun watch(onLedger: suspend () -> Unit) {}
        fun submits() = received.filter { it.second.type == "submit_answer" }
    }

    private class Rig {
        val scheduler = TestCoroutineScheduler()
        private val dispatcher = StandardTestDispatcher(scheduler)
        val now = { 1_800_000_000_000L + scheduler.currentTime }
        val mac = FakeMac(now)
        val sync = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { driver ->
            ClientDatabase.Schema.create(driver)
            SyncEngine(ClientDatabase(driver), "phone", mac, listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
        }
        val c = CoachController("C", sync, unused(), formatClock = { "" }, nowMillis = now,
            essayClock = CoroutineScope(SupervisorJob() + dispatcher), io = dispatcher)
        val limit = Essay.TIME_LIMIT_SEC * 1000L
        val session = EssaySession("essay-deadline", json("stem" to "Explain why a synthetic toy market can unravel.", "posedAt" to now()), now() + limit)
        fun queuedSubmits() = sync.retainedCommands().count { LedgerJson.decodeFromString<CommandBody>(it.payloadBytes).type == "submit_answer" }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> unused(): T {
            val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return (f.get(null) as sun.misc.Unsafe).allocateInstance(T::class.java) as T
        }
    }

    private fun ComposeUiTest.advance(rig: Rig, millis: Long) = runOnIdle { rig.scheduler.advanceTimeBy(millis); rig.scheduler.runCurrent() }

    @Test fun backOnReviewPastDeadlineAndGraceStillHandsInOnTimeExactlyOnce() = runComposeUiTest {
        val rig = Rig()
        val draft = "A synthetic draft typed on the essay page before going back to review."
        var essayOpen by mutableStateOf(true)
        rig.c.startEssay(rig.session)
        setContent { CoachTheme(false) {
            if (essayOpen) EssayScreen(rig.c, CourseView(emptyList(), Projection()), onBack = { essayOpen = false }) else Text("复习页")
        } }
        onNodeWithTag("essay-text").performTextInput(draft)
        advance(rig, 5 * 60_000L)
        assertTrue(rig.mac.submits().isEmpty(), "Nothing is handed in before the deadline")

        onNodeWithText("← 复习").performClick()
        onNodeWithText("复习页").assertExists()
        // 一直停在复习页，越过截止和 Mac 的宽限再多一分钟。
        advance(rig, rig.limit - 5 * 60_000L + Essay.GRACE_SEC * 1000L + 60_000L)

        val (arrivedAt, body) = rig.mac.submits().single()
        assertEquals(draft, body.payload.str("answer"), "The draft typed before leaving is what gets graded")
        val late = arrivedAt - rig.session.deadline
        assertTrue(late in 0..1_000, "Reached the Mac ${late}ms after the deadline; the Mac counts it as timed only within ${Essay.GRACE_SEC}s")
        assertTrue(rig.session.handedIn)

        // 回到练习页：直接是交卷后的等待批改页，不能再写，也不会再交一次。
        runOnIdle { essayOpen = true }
        onNodeWithText("再写一篇").assertExists()
        onNodeWithTag("essay-text").assertDoesNotExist()
        advance(rig, 10 * 60_000L)
        assertEquals(1, rig.mac.submits().size)
        assertEquals(1, rig.queuedSubmits())
        println("ESSAY_DEADLINE away=review limitSec=${Essay.TIME_LIMIT_SEC} graceSec=${Essay.GRACE_SEC} waitedPastDeadlineSec=${Essay.GRACE_SEC + 60 + 600} " +
            "arrivedAfterDeadlineMs=$late submits=${rig.mac.submits().size} outbox=${rig.queuedSubmits()} answerMatchesDraft=${body.payload.str("answer") == draft}")
    }

    @Test fun tappingHandInThenLettingTheDeadlinePassSubmitsOnce() = runComposeUiTest {
        val rig = Rig()
        rig.c.startEssay(rig.session)
        setContent { CoachTheme(false) { EssayScreen(rig.c, CourseView(emptyList(), Projection()), onBack = {}) } }
        onNodeWithTag("essay-text").performTextInput("A synthetic answer handed in early by tapping the button.")
        advance(rig, 60_000L)
        onNodeWithText("交卷").performClick()
        advance(rig, rig.limit + Essay.GRACE_SEC * 1000L)
        val (arrivedAt, _) = rig.mac.submits().single()
        assertEquals(rig.session.deadline - rig.limit + 60_000L, arrivedAt)
        assertEquals(1, rig.queuedSubmits())
    }
}
