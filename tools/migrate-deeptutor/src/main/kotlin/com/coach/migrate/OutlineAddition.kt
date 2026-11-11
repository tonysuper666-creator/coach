package com.coach.migrate

import com.coach.domain.Event
import com.coach.domain.LedgerJson
import com.coach.domain.weekPoints
import com.coach.store.LedgerStore
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files
import java.sql.Connection
import kotlin.io.path.*

private fun quoted(name: String) = "\"${name.replace("\"", "\"\"")}\""

/** Exact typed rows for EVERY table, including SQLite sequences; caches alone are disposable.
 * Preview comparison normalizes only the new event's random ID, never historical facts. */
internal fun outlineState(data: Path, normalizeAfter: Long? = null): JsonObject =
    openReadOnly(data.resolve("coach.sqlite")).use { db ->
        buildJsonObject {
            put("tables", buildJsonObject {
                for (table in db.query("SELECT name FROM sqlite_master WHERE type='table' AND name<>'projection_cache' ORDER BY name")) {
                    val name = table.str("name")
                    val hashes = db.createStatement().use { st -> st.executeQuery("SELECT * FROM ${quoted(name)}").use { rs ->
                        buildList {
                            while (rs.next()) {
                                val normalize = name == "events" && normalizeAfter != null && rs.getLong("seq") > normalizeAfter
                                val row = buildJsonArray {
                                    for (i in 1..rs.metaData.columnCount) {
                                        var value = rs.getObject(i)
                                        if (normalize) when (rs.metaData.getColumnName(i)) {
                                            "id" -> value = "preview-event"
                                            "event_json" -> value = canonical(JsonObject(parseText(value.toString()).jsonObject +
                                                ("id" to JsonPrimitive("preview-event"))))
                                        }
                                        add(buildJsonObject {
                                            put("type", when (value) { null -> "null"; is ByteArray -> "blob"; is Number -> "number"; else -> "text" })
                                            put("value", when (value) {
                                                null -> JsonNull
                                                is ByteArray -> JsonPrimitive(java.util.Base64.getEncoder().encodeToString(value))
                                                is Number -> JsonPrimitive(value)
                                                else -> JsonPrimitive(value.toString())
                                            })
                                        })
                                    }
                                }
                                add(sha(canonical(row)))
                            }
                        }.sorted()
                    } }
                    put(name, JsonArray(hashes.map(::JsonPrimitive)))
                }
            })
            put("schema", JsonArray(db.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name")))
            put("userVersion", JsonArray(db.query("PRAGMA user_version")))
            put("applicationId", JsonArray(db.query("PRAGMA application_id")))
            put("blobs", blobInventory(data))
        }
    }

private fun activeIds(db: Connection, course: String) = db.query(
    "SELECT kp_id FROM outline_kp WHERE course_id=? AND retired=0 ORDER BY kp_id", course).map { it.str("kp_id") }

private fun eventPrefix(db: Connection, through: Long): String = sha(canonical(JsonArray(
    db.query("SELECT * FROM events WHERE seq<=? ORDER BY seq", through))))

/** Call only with server.lock held. It cannot migrate or otherwise write the database. */
internal fun outlinePreflight(data: Path, input: ByteArray): JsonObject {
    val payload = parseText(input.toString(Charsets.UTF_8)).jsonObject
    val course = payload.obj("course").str("id")
    val state = outlineState(data)
    return openReadOnly(data.resolve("coach.sqlite")).use { db ->
        integrity(db); validateBlobs(db, data)
        val seq = db.query("SELECT coalesce(max(seq),0) AS n FROM events").single().getValue("n")
        val ids = activeIds(db, course)
        buildJsonObject {
            put("inputSha256", com.coach.store.sha256(input)); put("course", course)
            put("activeIdsSha256", sha(canonical(JsonArray(ids.map(::JsonPrimitive))))); put("activePoints", ids.size)
            put("maxSeq", seq); put("eventsSha256", eventPrefix(db, seq.jsonPrimitive.long))
            put("epoch", db.query("SELECT epoch FROM ledger_meta").single().getValue("epoch"))
            put("stateSha256", sha(canonical(state)))
        }
    }
}

private fun validateOutline(data: Path, payload: JsonObject, before: JsonObject, oldIds: Set<String>, changed: Boolean): JsonObject =
    openReadOnly(data.resolve("coach.sqlite")).use { db ->
        integrity(db)
        val course = before.str("course")
        val seq = before.getValue("maxSeq").jsonPrimitive.long
        check(eventPrefix(db, seq) == before.str("eventsSha256")) { "Historical events changed" }
        check(db.query("SELECT epoch FROM ledger_meta").single().getValue("epoch") == before["epoch"]) { "Epoch changed" }
        val points = payload.array("knowledgePoints").map { it.jsonObject }
        val expected = oldIds + points.map { it.str("id") }
        check(activeIds(db, course).toSet() == expected) { "Active ID set differs" }
        val added = db.query("SELECT * FROM events WHERE seq>? ORDER BY seq", seq)
        check(added.size == if (changed) 1 else 0) { "Unexpected event count" }
        if (changed) check(added.single().let { it.str("course_id") == course && it.str("type") == "outline_revised" &&
            it.str("actor") == "MIGRATION" && it.str("idem_key").startsWith("add-outline:") }) { "Unexpected import event" }
        val events = db.query("SELECT event_json FROM events WHERE course_id=? ORDER BY seq", course)
            .map { LedgerJson.decodeFromString<Event>(it.str("event_json")) }
        val replay = weekPoints(events)
        check(replay.map { it.id }.toSet() == expected) { "Replay ID set differs" }
        for (p in points) check(replay.single { it.id == p.str("id") }.weeks == setOf(p.getValue("week").jsonPrimitive.int)) {
            "Replay week differs from input"
        }
        buildJsonObject {
            put("activePoints", expected.size); put("eventsAdded", added.size); put("integrity", "ok")
            put("weeks", buildJsonObject {
                for (week in (replay.flatMap { it.weeks } + points.map { it.getValue("week").jsonPrimitive.int }).distinct().sorted())
                    put(week.toString(), replay.count { week in it.weeks })
            })
        }
    }

/** No public rollback command: this capability exists only inside the uninterrupted offline lock.
 * The full receipt is compared again immediately before replacement, including non-event tables/blobs. */
private fun rollbackOutline(data: Path, backup: Path, beforeState: JsonObject, writtenState: JsonObject, savedHash: String) {
    check(outlineState(data) == writtenState) { "Target changed after import; refusing to discard subsequent activity. Keep service stopped; consult Architect" }
    val saved = backup.resolve("coach.sqlite")
    check(fileHash(saved) == savedHash && outlineState(backup) == beforeState) { "Backup changed; refusing rollback" }
    replaceOfflineDatabase(data, saved, ".outline-rollback.sqlite")
    check(outlineState(data) == beforeState)
    openReadOnly(data.resolve("coach.sqlite")).use(::integrity)
}

private fun preserveOutline(before: JsonObject, after: JsonObject, backup: Path, restored: Path, course: String) {
    val tables = before.obj("tables")
    for ((name, rows) in tables) when (name) {
        "events", "outline_kp", "course_modules" -> check(after.obj("tables").getValue(name).jsonArray.containsAll(rows.jsonArray)) {
            "Existing rows changed in $name"
        }
        "course_content", "sqlite_sequence" -> Unit
        else -> check(after.obj("tables")[name] == rows) { "Unrelated table changed: $name" }
    }
    openReadOnly(backup.resolve("coach.sqlite")).use { old -> openReadOnly(restored.resolve("coach.sqlite")).use { new ->
        if ("course_content" in tables) check(old.query("SELECT * FROM course_content WHERE course_id<>? ORDER BY course_id", course) ==
            new.query("SELECT * FROM course_content WHERE course_id<>? ORDER BY course_id", course)) { "Other course metadata changed" }
        if ("sqlite_sequence" in tables) check(old.query("SELECT * FROM sqlite_sequence WHERE name<>'events' ORDER BY name") ==
            new.query("SELECT * FROM sqlite_sequence WHERE name<>'events' ORDER BY name")) { "Unrelated sequence changed" }
    } }
    check(before["blobs"] == after["blobs"]) { "Blobs changed" }
}

internal fun applyOutline(data: Path, input: ByteArray, expect: JsonObject, backup: Path,
                          afterWrite: () -> Unit = {}): JsonObject = offlineData(data) {
    val before = outlinePreflight(data, input)
    require(before == expect) { "Preflight changed; refusing import without any database write" }
    val payload = parseText(input.toString(Charsets.UTF_8)).jsonObject
    val course = before.str("course")
    val seq = before.getValue("maxSeq").jsonPrimitive.long
    val oldIds = openReadOnly(data.resolve("coach.sqlite")).use { activeIds(it, course).toSet() }
    val beforeState = outlineState(data)
    freshDirectory(backup)
    backupDatabase(data.resolve("coach.sqlite"), backup.resolve("coach.sqlite"))
    copyBlobs(data, backup)
    val savedHash = fileHash(backup.resolve("coach.sqlite"))
    check(outlineState(backup) == beforeState) { "Backup differs from target" }
    openReadOnly(backup.resolve("coach.sqlite")).use(::integrity)
    // A real restore into a new directory is validated before opening the target LedgerStore.
    val restored = backup.resolve("restored"); freshDirectory(restored)
    Files.copy(backup.resolve("coach.sqlite"), restored.resolve("coach.sqlite")); copyBlobs(backup, restored)
    check(outlineState(restored) == beforeState)
    openReadOnly(restored.resolve("coach.sqlite")).use(::integrity)
    val clock = System.currentTimeMillis()
    val expectedChange = LedgerStore(restored) { clock }.use { it.addOutline(payload) }
    validateOutline(restored, payload, before, oldIds, expectedChange)
    preserveOutline(beforeState, outlineState(restored), backup, restored, course)
    val expectedState = outlineState(restored, seq)
    check(outlinePreflight(data, input) == before) { "Target changed during backup and restore verification" }
    backup.resolve("before.json").writeText(canonical(before))
    backup.resolve("before-state.json").writeText(canonical(beforeState))
    backup.resolve("backup.json").writeText(canonical(buildJsonObject {
        put("databaseSha256", savedHash); put("stateSha256", sha(canonical(beforeState))); put("restoreVerified", true)
    }))
    var writtenState: JsonObject? = null
    try {
        val changed = LedgerStore(data) { clock }.use { it.addOutline(payload) }
        // Capture before validation, so even a failing verifier cannot authorize discarding a later write.
        writtenState = outlineState(data)
        backup.resolve("written-state.json").writeText(canonical(writtenState))
        afterWrite()
        check(changed == expectedChange && outlineState(data, seq) == expectedState) { "Import differs from restored preview" }
        val validation = validateOutline(data, payload, before, oldIds, changed)
        val receipt = buildJsonObject {
            put("result", if (changed) "installed" else "unchanged"); put("before", before)
            put("after", outlinePreflight(data, input)); put("validation", validation)
            put("writtenStateSha256", sha(canonical(writtenState))); put("backupDatabaseSha256", savedHash)
            put("restoreVerified", true); put("rollbackWindow", "same invocation, before releasing server.lock only")
        }
        backup.resolve("receipt.json").writeText(canonical(receipt))
        receipt
    } catch (failure: Exception) {
        try {
            val guard = writtenState ?: outlineState(data)
            rollbackOutline(data, backup, beforeState, guard, savedHash)
            backup.resolve("failure.json").writeText(canonical(buildJsonObject { put("restored", true); put("stateSha256", sha(canonical(beforeState))) }))
        } catch (rollbackFailure: Exception) {
            failure.addSuppressed(rollbackFailure)
            throw IllegalStateException("Offline rollback refused or failed; keep service stopped and consult Architect", failure)
        }
        throw failure
    }
}

/** Explicit destination and reviewed, frozen preflight. No unguarded or post-restart rollback mode. */
fun outlineAdditionCommand(args: List<String>) {
    val preflight = "--preflight" in args
    val pairs = args.filterNot { it == "--preflight" }.chunked(2)
    val required = if (preflight) setOf("--input", "--database") else setOf("--input", "--database", "--expect", "--backup")
    require(args.count { it == "--preflight" } <= 1 && pairs.all { it.size == 2 && it[0] in required } &&
        pairs.map { it[0] }.toSet() == required && pairs.size == required.size) {
        "Usage: add-outline --input FILE --database OFFLINE_DIR [--preflight | --expect PREFLIGHT.json --backup NEW_DIR]"
    }
    val options = pairs.associate { it[0] to it[1] }
    val input = Path.of(options.getValue("--input")).toRealPath()
    val data = Path.of(options.getValue("--database")).toRealPath()
    val protected = listOf(resolvedDestination(Path.of(System.getProperty("user.home")).resolve("DeepTutor")))
    requirePrivateDestination(data, protected)
    require(!input.startsWith(data)) { "Input and database must be separate" }
    val bytes = input.readBytes() // Hash and apply the same bytes, even if the file is replaced later.
    if (preflight) {
        println(canonical(offlineData(data) { outlinePreflight(data, bytes) })); return
    }
    val expectedFile = Path.of(options.getValue("--expect")).toRealPath()
    val backup = resolvedDestination(Path.of(options.getValue("--backup")))
    requirePrivateDestination(backup, protected)
    require(!backup.startsWith(data) && !data.startsWith(backup) && !input.startsWith(backup) && !expectedFile.startsWith(backup)) {
        "Backup, inputs and target must be separate"
    }
    println(canonical(applyOutline(data, bytes, parseFile(expectedFile).jsonObject, backup)))
}
