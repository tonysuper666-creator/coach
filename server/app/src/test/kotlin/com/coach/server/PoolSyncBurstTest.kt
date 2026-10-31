package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PoolSyncBurstTest {
    @Test fun pushAfterRestoreReplacesTheEpochEvenWithoutPendingAnswers() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val epoch=java.util.concurrent.atomic.AtomicReference("before-restore")
        val ready=CompletableDeferred<suspend () -> Unit>()
        val snapshots=java.util.Collections.synchronizedList(mutableListOf<String>())
        val transport=object: LedgerTransport {
            override suspend fun hello(previous: String?)=Hello(epoch.get(),0,0,0)
            override suspend fun snapshot(course: String): Snapshot {
                val current=hello(null);snapshots.add(current.epoch)
                return Snapshot(current,course,emptyList(),Projection())
            }
            override suspend fun events(course: String,after: Long)=EventsPage(epoch.get(),emptyList(),0)
            override suspend fun command(command: WireCommand): Receipt=error("No pending learner answer")
            override suspend fun watch(onLedger: suspend () -> Unit) { ready.complete(onLedger);awaitCancellation() }
        }
        val client=SyncEngine(ClientDatabase(driver),"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256)
        var loop: Job?=null
        try {
            client.syncOnce();loop=client.start(scope)
            val notify=withTimeout(2_000) { ready.await() }
            withTimeout(2_000) { while(client.revision.value<2) delay(10) }
            epoch.set("after-restore");notify()
            // Complete from the push, before the two-second recovery poll can mask a bug.
            withTimeout(1_000) { while(client.epoch!="after-restore") delay(10) }
            assertEquals("after-restore",snapshots.last())
            assertTrue(client.localEvents("course").isEmpty())
        } finally { loop?.cancelAndJoin();scope.cancel();driver.close() }
    }

    @Test fun quizHintsDoNotBlockTheSocketAndFactsArrivingDuringCatchupAreNotLost() = runBlocking {
        val dir=Files.createTempDirectory("pool-sync-burst")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actor=LedgerActor(scope,LedgerStore(dir))
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val pause=AtomicBoolean(false)
        val reads=AtomicInteger()
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val ready=CompletableDeferred<suspend () -> Unit>()
        var loop: Job?=null
        val transport=object: LedgerTransport {
            override suspend fun hello(epoch: String?)=actor.execute { hello() }
            override suspend fun snapshot(course: String)=actor.execute { snapshot(course) }
            override suspend fun command(command: WireCommand): Receipt=error("No learner commands in this replay")
            override suspend fun events(course: String,after: Long): EventsPage {
                reads.incrementAndGet()
                val page=actor.execute {
                    val facts=events(course,after)
                    EventsPage(hello().epoch,facts,facts.lastOrNull()?.seq ?: after)
                }
                if(pause.compareAndSet(true,false)) { entered.complete(Unit);release.await() }
                return page
            }
            override suspend fun watch(onLedger: suspend () -> Unit) { ready.complete(onLedger);awaitCancellation() }
        }
        try {
            actor.execute { append("course","kp_opened",json("kpId" to "first","title" to "First")) }
            val client=SyncEngine(ClientDatabase(driver),"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256)
            client.syncOnce()
            pause.set(true)
            loop=client.start(scope)
            withTimeout(2_000) { entered.await() }
            val notify=withTimeout(2_000) { ready.await() }
            val latest=actor.execute { append("course","kp_opened",json("kpId" to "next","title" to "Next")) }
            // The WS reader must accept the whole burst even while an HTTP read is stalled.
            withTimeout(1_000) { repeat(50) { notify() } }
            release.complete(Unit)
            withTimeout(2_000) { while(client.localEvents("course").lastOrNull()?.id!=latest.id) delay(10) }
            assertEquals(listOf("first","next"),client.localEvents("course").map { it.payload.str("kpId") })
            println("POOL_SYNC_BURST hints=50 httpEventReads=${reads.get()} newestFactVisible=true")
        } finally {
            release.complete(Unit);loop?.cancelAndJoin();actor.close();scope.cancel();driver.close();dir.toFile().deleteRecursively()
        }
    }
}
