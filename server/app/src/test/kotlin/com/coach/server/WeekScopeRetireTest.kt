package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.CourseView
import com.coach.client.LedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.LedgerStore
import com.coach.store.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * 回归（TON-1164 cross_review）：W2 设好范围后退休一个点，客户端「继续学习」必须和服务端
 * `advance_study_scope` 用同一个可用集合（active weekPoints），否则点「下一个」永远 CONFLICT/STUDY_SCOPE_MOVED。
 */
class WeekScopeRetireTest {
    private fun wire(type: String, payload: JsonObject, device: String = "phone"): WireCommand {
        val bytes = LedgerJson.encodeToString(CommandBody(type, "C", 1000, payload))
        return WireCommand(device, UUID.randomUUID().toString(), bytes, sha256(bytes.toByteArray()))
    }

    private fun LedgerStore.seedWeek() {
        append("C", "outline_revised", json("ops" to JsonArray(listOf(
            json("kpId" to "a", "title" to "【W2】A"),
            json("kpId" to "b", "title" to "【W2】B"),
            json("kpId" to "c", "title" to "【W2】C")))))
    }

    /** 服务端回放后：b 退休、a 已掌握，范围队列只剩 c。两端必须选同一个点。 */
    private fun LedgerStore.retireBAndPassA() {
        append("C", "outline_revised", json("ops" to JsonArray(listOf(json("op" to "retire", "kpId" to "b")))))
        append("C", "kp_overridden", json("kpId" to "a", "kind" to "MARK_PASSED"))
    }

    private fun assertClientAgreesWithServer(store: LedgerStore) {
        val events = store.events("C")
        val snapshot = store.snapshot("C")
        val view = CourseView(events, snapshot.projection)
        val scope = assertNotNull(snapshot.projection.studyScope)
        val serverTarget = scope.target(snapshot.projection.mastery, weekPoints(events).map { it.id }.toSet())
        assertEquals("c", serverTarget)
        assertEquals(listOf("a", "c"), view.kps.map { it.id }, "退休的 b 不应再出现在学习路线里")
        assertEquals(serverTarget, view.weekTarget?.id)
        assertEquals("c", view.continueTarget?.id)
    }

    @Test fun retiredPointLeavesTheWeekQueueAndTheClientTargetAdvancesAfterRestart() {
        val dir = Files.createTempDirectory("coach-week-retire-")
        try {
            var db = LedgerStore(dir)
            db.seedWeek()
            assertEquals("a", db.command(wire("set_study_scope", json("week" to 2))).result.str("target"))
            db.retireBAndPassA()

            assertClientAgreesWithServer(db)

            db.close(); db = LedgerStore(dir)
            assertClientAgreesWithServer(db)

            val scope = assertNotNull(db.snapshot("C").projection.studyScope)
            val clientTarget = assertNotNull(CourseView(db.events("C"), db.snapshot("C").projection).weekTarget).id
            val accepted = db.command(wire("advance_study_scope", json("scopeId" to scope.id, "kpId" to clientTarget)))
            assertEquals("OK", accepted.status, "客户端选出的下一项必须被服务端接受（原缺陷返回 CONFLICT/STUDY_SCOPE_MOVED）")
            assertEquals("", accepted.result.str("target"), "c 是本周最后一个待学点，推进后本周队列为空")
            db.close()
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun syncedClientPicksTheSameNextPointAsTheServerAndSurvivesRestart(): Unit = runBlocking {
        val root = Files.createTempDirectory("coach-week-retire-sync-")
        val store = LedgerStore(root)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val client = SyncEngine(ClientDatabase(driver), "phone", Direct(store), listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
            store.seedWeek()
            client.syncOnce()
            val select = client.enqueue(CommandBody("set_study_scope", "C", 1000, json("week" to 2)))
            client.syncOnce()
            assertEquals("a", client.receipt(select.commandId)!!.result.str("target"))
            store.retireBAndPassA()
            client.syncOnce()

            val projection = client.projection("C")
            val view = CourseView(client.localEvents("C"), projection)
            assertEquals(listOf("a", "c"), view.kps.map { it.id })
            assertEquals("c", view.weekTarget?.id)
            assertEquals("c", view.continueTarget?.id)
            val advance = client.enqueue(CommandBody("advance_study_scope", "C", 1000,
                json("scopeId" to assertNotNull(projection.studyScope).id, "kpId" to assertNotNull(view.weekTarget).id)))
            client.syncOnce()
            assertEquals("OK", client.receipt(advance.commandId)!!.status)

            // 重启回放：同一个客户端库新建引擎，投影与账本一致，队列已随推进清空。
            val restarted = SyncEngine(ClientDatabase(driver), "phone", Direct(store), listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
            restarted.syncOnce()
            assertEquals(store.snapshot("C").projection.bytes(), restarted.projection("C").bytes())
            assertNull(CourseView(restarted.localEvents("C"), restarted.projection("C")).weekTarget)
        } finally {
            driver.close(); store.close(); root.toFile().deleteRecursively()
        }
    }

    @Test fun versionFiveCacheWithoutStudyScopeIsRebuiltFromTheLedger(): Unit = runBlocking {
        val root = Files.createTempDirectory("coach-week-retire-v5-")
        val store = LedgerStore(root)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val db = ClientDatabase(driver)
            val client = SyncEngine(db, "phone", Direct(store), listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
            store.seedWeek()
            client.syncOnce()
            client.enqueue(CommandBody("set_study_scope", "C", 1000, json("week" to 2)))
            client.syncOnce()
            store.retireBAndPassA()
            client.syncOnce()
            val current = client.projection("C")

            // 旧客户端留下的 v5 缓存：同 cursor、同 epoch，但投影里没有 studyScope。
            val seq = db.ledgerQueries.replicaSeq("C").executeAsOne()
            db.ledgerQueries.putProjection("C", 5L, seq, assertNotNull(client.epoch),
                LedgerJson.encodeToString(current.copy(version = 5, studyScope = null)))

            val rebuilt = client.projection("C")
            val scope = assertNotNull(rebuilt.studyScope, "v5 缓存必须作废，从账本重放出周学习范围")
            assertEquals(2, scope.week)
            assertEquals("c", CourseView(client.localEvents("C"), rebuilt).weekTarget?.id)
        } finally {
            driver.close(); store.close(); root.toFile().deleteRecursively()
        }
    }

    @Test fun restoredPointAndOldCandidateCacheStayConsistentAcrossSyncAndRestart(): Unit = runBlocking {
        val root = Files.createTempDirectory("coach-week-restore-v6-")
        var store = LedgerStore(root)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val db = ClientDatabase(driver)
            fun engine() = SyncEngine(db, "phone", Direct(store), listOf("C"), { UUID.randomUUID().toString() }, ::sha256)
            val client = engine()
            store.seedWeek()
            client.syncOnce()
            client.enqueue(CommandBody("set_study_scope", "C", 1000, json("week" to 2)))
            client.syncOnce()
            store.append("C", "kp_overridden", json("kpId" to "a", "kind" to "MARK_PASSED"))
            store.append("C", "kp_overridden", json("kpId" to "c", "kind" to "MARK_PASSED"))
            val evidence = store.snapshot("C").projection.mastery.getValue("c")
            store.append("C", "outline_revised", json("ops" to JsonArray(listOf(json("op" to "retire", "kpId" to "b")))))
            store.append("C", "outline_revised", json("ops" to JsonArray(listOf(
                json("op" to "setPrereq", "kpId" to "b", "prereqs" to emptyList<String>()),
                json("op" to "move", "kpId" to "b", "index" to 1)))))
            client.syncOnce()
            var view = CourseView(client.localEvents("C"), client.projection("C"))
            assertNull(view.kp("b"), "修改前置和顺序不能使退休点恢复")
            assertTrue(weekPoints(client.localEvents("C")).none { it.id == "b" })
            assertNull(view.weekTarget)

            // 不重复发送标题；恢复必须保留旧标题及已掌握的证据。
            store.append("C", "outline_revised", json("ops" to JsonArray(listOf(json("op" to "add", "kpId" to "b")))))
            client.syncOnce()
            val projection = client.projection("C")
            view = CourseView(client.localEvents("C"), projection)
            assertEquals("b", view.weekTarget?.id)
            assertEquals("b", view.continueTarget?.id)
            assertEquals("【W2】B", view.kp("b")?.title)
            assertEquals("【W2】B", weekPoints(client.localEvents("C")).first { it.id == "b" }.title)
            assertEquals(evidence, projection.mastery["c"])
            assertEquals(store.snapshot("C").projection.bytes(), projection.bytes())

            // 旧 PR59 的 v6 缓存可以带有效周范围，却使用旧恢复语义；必须丢弃。
            val seq = db.ledgerQueries.replicaSeq("C").executeAsOne()
            val stale = projection.copy(version = 6, position = projection.position.copy(continueTarget = "c"))
            store.close()
            java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { sql ->
                sql.prepareStatement("UPDATE projection_cache SET version=6, blob=? WHERE name='all' AND course_id='C'").use { statement ->
                    statement.setBytes(1, LedgerJson.encodeToString(stale).toByteArray(Charsets.UTF_8))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            store = LedgerStore(root)
            assertEquals(projection.bytes(), store.snapshot("C").projection.bytes(), "服务端重启必须重放旧 v6 缓存")
            db.ledgerQueries.putProjection("C", 6L, seq, assertNotNull(client.epoch), LedgerJson.encodeToString(stale))
            val restarted = engine()
            assertEquals(store.snapshot("C").projection.bytes(), restarted.projection("C").bytes())
            restarted.syncOnce()
            assertEquals(store.snapshot("C").projection.bytes(), restarted.projection("C").bytes())
            assertEquals("b", CourseView(restarted.localEvents("C"), restarted.projection("C")).weekTarget?.id)
            val scope = assertNotNull(restarted.projection("C").studyScope)
            assertEquals("OK", store.command(wire("advance_study_scope", json("scopeId" to scope.id, "kpId" to "b"))).status)
        } finally {
            driver.close(); store.close(); root.toFile().deleteRecursively()
        }
    }

    private class Direct(var store: LedgerStore) : LedgerTransport {
        override suspend fun hello(epoch: String?) = store.hello()
        override suspend fun snapshot(course: String) = store.snapshot(course)
        override suspend fun events(course: String, after: Long) = store.page(course, after, 500)
        override suspend fun command(command: WireCommand) = store.command(command)
        override suspend fun watch(onLedger: suspend () -> Unit) {}
    }
}
