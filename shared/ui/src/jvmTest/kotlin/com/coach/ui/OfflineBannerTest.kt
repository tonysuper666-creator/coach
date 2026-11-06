package com.coach.ui

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import androidx.compose.ui.test.*
import com.coach.client.LedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.client.db.ClientDatabase
import kotlin.test.*

/**
 * TON-1241 D4：整屏 [CoachApp] 上的「Mac 未连接」横幅，由模型的连接状态驱动（与 [TutorClient] 每次握手/断开时一样）。
 * 只用 main 上就有的 API，所以同一份测试在 main 上能编译、能跑出闪烁。
 */
@OptIn(ExperimentalTestApi::class)
class OfflineBannerTest {
    /** 一行都没有的本地库：首页走「还没同步」，横幅照常挂在最上面。 */
    private object EmptyDriver : SqlDriver {
        private object Empty : SqlCursor {
            override fun next() = QueryResult.Value(false)
            override fun getString(index: Int): String? = null
            override fun getLong(index: Int): Long? = null
            override fun getBytes(index: Int): ByteArray? = null
            override fun getDouble(index: Int): Double? = null
            override fun getBoolean(index: Int): Boolean? = null
        }
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?) = mapper(Empty)
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) = QueryResult.Value(0L)
        override fun newTransaction(): QueryResult<Transacter.Transaction> = error("read-only")
        override fun currentTransaction(): Transacter.Transaction? = null
        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun notifyListeners(vararg queryKeys: String) = Unit
        override fun close() = Unit
    }

    private object NoTransport : LedgerTransport {
        override suspend fun hello(epoch: String?) = error("not started")
        override suspend fun snapshot(course: String) = error("not started")
        override suspend fun events(course: String, after: Long) = error("not started")
        override suspend fun command(command: com.coach.protocol.WireCommand) = error("not started")
        override suspend fun watch(onLedger: suspend () -> Unit) = error("not started")
    }

    private val model = TutorModel({ 0L })
    private val tutor = TutorClient("http://127.0.0.1:9", "dev", "tok", model)
    private val sync = SyncEngine(ClientDatabase(EmptyDriver), "dev", NoTransport, listOf("C"), { "id" }, { "h" })

    private fun ComposeUiTest.mount() {
        mainClock.autoAdvance = false
        setContent { CoachApp(CoachController(courseId = "C", sync = sync, tutor = tutor, formatClock = { "10:00" })) }
    }

    private fun ComposeUiTest.bannerShown() = onAllNodesWithText("Mac 未连接", substring = true).fetchSemanticsNodes().isNotEmpty()

    @AfterTest fun closeClient() = tutor.close()

    @Test fun coldStartHandshakeDoesNotFlashBanner() = runComposeUiTest {
        mount()
        mainClock.advanceTimeBy(400)
        assertFalse(bannerShown(), "第一次握手还没完成就亮了红横幅")
        model.onConnection(true)
        mainClock.advanceTimeBy(5_000)
        assertFalse(bannerShown())
    }

    @Test fun reconnectStormKeepsBannerSteady() = runComposeUiTest {
        model.onConnection(true)
        mount()
        mainClock.advanceTimeBy(100)
        var flips = 0
        var last = bannerShown()
        // 连上 300ms、断开 250ms，循环 20 次：与 06:43 的日志同一节奏。
        repeat(20) {
            model.onConnection(false); mainClock.advanceTimeBy(250)
            bannerShown().let { if (it != last) { flips++; last = it } }
            model.onConnection(true); mainClock.advanceTimeBy(300)
            bannerShown().let { if (it != last) { flips++; last = it } }
        }
        assertTrue(flips <= 1, "风暴里横幅出现/消失了 $flips 次")
        model.onConnection(false); mainClock.advanceTimeBy(1_000)
        assertTrue(bannerShown(), "真断开一秒内要亮横幅")
        model.onConnection(true); mainClock.advanceTimeBy(2_100)
        assertFalse(bannerShown(), "连接稳定后横幅应收起")
    }

    @Test fun macThatNeverAnswersStillShowsBanner() = runComposeUiTest {
        mount()
        mainClock.advanceTimeBy(3_100)
        assertTrue(bannerShown())
    }
}
