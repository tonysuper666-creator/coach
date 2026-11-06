package com.coach.migrate

import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import kotlinx.serialization.json.jsonObject
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import kotlin.io.path.*

/** Explicit offline destination; no production default and no source-document imports. */
fun outlineAdditionCommand(args: List<String>) {
    val pairs = args.chunked(2)
    require(args.size == 4 && pairs.all { it.size == 2 && it[0] in setOf("--input", "--database") } &&
        pairs.map { it[0] }.toSet().size == 2) { "Usage: add-outline --input FILE --database OFFLINE_DIR" }
    val options = pairs.associate { it[0] to it[1] }
    val input = Path.of(options.getValue("--input")).toRealPath()
    val database = Path.of(options.getValue("--database")).toRealPath()
    val home = Path.of(System.getProperty("user.home"))
    requirePrivateDestination(database, listOf(resolvedDestination(home.resolve("DeepTutor"))))
    require(database.resolve("coach.sqlite").isRegularFile()) { "Append requires an existing database" }
    val payload = LedgerJson.parseToJsonElement(input.readText()).jsonObject
    FileChannel.open(database.resolve("server.lock"), CREATE, WRITE).use { channel ->
        val lock = try { channel.tryLock() } catch(_: OverlappingFileLockException) { null }
        check(lock != null) { "Coach server owns this directory; use an offline copy" }
        lock.use {
            LedgerStore(database).use { store ->
                val changed = store.addOutline(payload)
                println("add-outline ${if(changed) "installed" else "unchanged"}")
            }
        }
    }
}
