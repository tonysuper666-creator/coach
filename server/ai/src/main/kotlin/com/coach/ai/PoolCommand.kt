package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

/** Input file: {course,kp,objective,excerpt}. Original material remains outside the repository. */
fun main(args: Array<String>): Unit = runBlocking {
    require(args.size in 3..4) { "refillPool: DATA_DIRECTORY INPUT_JSON TARGET [MAX_BATCHES=4] (stop server first)" }
    val data=Path.of(args[0]); val input=JsonText.objectOf(Files.readString(Path.of(args[1])))
    val target=args[2].toInt(); val maxBatches=args.getOrNull(3)?.toInt() ?: 4
    require(target > 0 && maxBatches > 0)
    Files.createDirectories(data)
    FileChannel.open(data.resolve("server.lock"),CREATE,WRITE).use { channel ->
        val lock=channel.tryLock() ?: error("Coach server owns this data directory; use its PoolWorker")
        lock.use {
            val actor=LedgerActor(this,LedgerStore(data))
            try {
                val client=BridgeClient(BridgeConfig(requestTimeout=java.time.Duration.ofSeconds(90)))
                val worker=PoolWorker(actor,client,beforeCall={
                    // Owner removed local call-count/rate thresholds on 2026-10-31.
                    // Keep only the explicit examination stop window; upstream 429 remains a real failure.
                    val now=java.time.Instant.now()
                    check(now < java.time.Instant.parse("2026-11-02T00:00:00Z") ||
                        now >= java.time.Instant.parse("2026-11-02T15:00:00Z")) { "All live calls paused during examination" }
                    check(client.health() != null) { "Coach bridge is unavailable" }
                },requestType="dev_live")
                val course=input.str("course"); val kp=input.str("kp")
                val before=actor.execute { supplyCallCount() }
                val start=System.nanoTime()
                var batches=0
                while (actor.execute { poolAvailable(course,kp) } < target && batches < maxBatches) {
                    worker.ensure(course,kp,input.str("objective"),input.str("excerpt"),target)
                    // A generation plus its independently claimed review. Existing review resumes directly.
                    while(true) {
                        val step=worker.runOne(course,kp) ?: break
                        println("job=${step.jobId} kind=${step.kind} state=${step.state} inserted=${step.inserted}")
                        if(step.state in setOf("failed","stale")) break
                    }
                    batches++
                }
                val available=actor.execute { poolAvailable(course,kp) }
                println("available=$available target=$target batches=$batches physical_calls=${actor.execute { supplyCallCount() }-before} wall_ms=${(System.nanoTime()-start)/1_000_000}")
                check(available >= target) { "Target not reached; verified content retained, no fabricated questions" }
            } finally { actor.close() }
        }
    }
}
