package com.coach.migrate

import com.coach.store.LedgerStore
import com.coach.domain.json
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Test

class OutlineSafetyTest {
    private val fixture = OutlineAdditionTest()
    private fun fixture(block: (Path, Path, ByteArray) -> Unit) = fixture.isolated { root ->
        val data = root.resolve("data"); fixture.seed(data)
        exec(data, "CREATE TABLE synthetic_chat(body TEXT)")
        exec(data, "INSERT INTO synthetic_chat VALUES('original')")
        data.resolve("blobs/extra").writeText("Synthetic unreferenced blob")
        block(root, data, fixture.payload().toString().toByteArray())
    }
    private fun preflight(data: Path, bytes: ByteArray) = offlineData(data) { outlinePreflight(data, bytes) }
    private fun exec(data: Path, sql: String) = DriverManager.getConnection("jdbc:sqlite:${data.resolve("coach.sqlite")}").use {
        it.createStatement().use { st -> st.execute(sql) }
    }

    @Test fun `preflight is read only and apply retains full rows and blobs on repeat`() = fixture { root, data, bytes ->
        val before = outlineState(data)
        val expect = preflight(data, bytes)
        assertEquals(before, outlineState(data))
        val result = applyOutline(data, bytes, expect, root.resolve("backup"))
        assertEquals("installed", result.str("result"))
        assertEquals(2, result.obj("validation").getValue("activePoints").jsonPrimitive.int)
        assertEquals(1, result.obj("validation").obj("weeks").getValue("8").jsonPrimitive.int)
        assertEquals(before, outlineState(root.resolve("backup")))
        assertEquals(expect, parseFile(root.resolve("backup/before.json")))
        val installed = outlineState(data)
        val repeat = applyOutline(data, bytes, preflight(data, bytes), root.resolve("backup-repeat"))
        assertEquals("unchanged", repeat.str("result"))
        assertEquals(installed, outlineState(data))
    }

    @Test fun `changed input events identity non event rows and blobs reject before any database write`() = fixture { root, data, bytes ->
        val expect = preflight(data, bytes)
        val changedBytes = (bytes.toString(Charsets.UTF_8) + " ").toByteArray()
        assertFailsWith<IllegalArgumentException> { applyOutline(data, changedBytes, expect, root.resolve("input")) }
        assertFalse(root.resolve("input").exists())
        val changes: List<() -> Unit> = listOf(
            { LedgerStore(data).use { it.append("other", "note", json("text" to "synthetic")) } },
            { exec(data, "UPDATE ledger_meta SET epoch='changed'") },
            { exec(data, "UPDATE synthetic_chat SET body='new'") },
            { data.resolve("blobs/extra").writeText("Changed synthetic blob") }
        )
        var expected = expect
        for ((n, mutate) in changes.withIndex()) {
            mutate()
            val state = outlineState(data)
            assertFailsWith<IllegalArgumentException> { applyOutline(data, bytes, expected, root.resolve("rejected-$n")) }
            assertEquals(state, outlineState(data))
            assertFalse(root.resolve("rejected-$n").exists())
            expected = preflight(data, bytes)
        }
    }

    @Test fun `existing backup and failed backup leave target untouched`() = fixture { root, data, bytes ->
        val before = outlineState(data); val expect = preflight(data, bytes)
        root.resolve("exists").createDirectory()
        root.resolve("exists/sentinel").writeText("keep")
        assertFails { applyOutline(data, bytes, expect, root.resolve("exists")) }
        assertEquals("keep", root.resolve("exists/sentinel").readText())
        // Directory creation fails before the writer or any migration can open the target.
        root.resolve("file-parent").writeText("not a directory")
        assertFails { applyOutline(data, bytes, expect, root.resolve("file-parent/backup")) }
        assertEquals(before, outlineState(data))
    }

    @Test fun `transaction failure is atomic and retries from a fresh preflight`() = fixture { root, data, bytes ->
        exec(data, "CREATE TRIGGER fail_outline BEFORE INSERT ON events WHEN NEW.type='outline_revised' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        val before = outlineState(data)
        assertFails { applyOutline(data, bytes, preflight(data, bytes), root.resolve("failed")) }
        assertEquals(before, outlineState(data))
        exec(data, "DROP TRIGGER fail_outline")
        assertEquals("installed", applyOutline(data, bytes, preflight(data, bytes), root.resolve("retry")).str("result"))
    }

    @Test fun `offline verification failure restores backup before same lock is released`() = fixture { root, data, bytes ->
        val before = outlineState(data)
        assertFailsWith<IllegalStateException> {
            applyOutline(data, bytes, preflight(data, bytes), root.resolve("backup")) {
                FileChannel.open(data.resolve("server.lock"), CREATE, WRITE).use { channel ->
                    assertFailsWith<java.nio.channels.OverlappingFileLockException> { channel.tryLock() }
                }
                error("synthetic offline verifier failure")
            }
        }
        assertEquals(before, outlineState(data))
        assertEquals(JsonPrimitive(true), parseFile(root.resolve("backup/failure.json")).jsonObject["restored"])
        assertEquals(fileHash(root.resolve("backup/coach.sqlite")), fileHash(data.resolve("coach.sqlite")))
    }

    @Test fun `later event non event or blob writes refuse destructive rollback even without a new event`() {
        for (kind in listOf("event", "chat", "blob")) fixture { root, data, bytes ->
            val expect = preflight(data, bytes)
            val failure = assertFailsWith<IllegalStateException> {
                applyOutline(data, bytes, expect, root.resolve("backup")) {
                    when (kind) {
                        "event" -> LedgerStore(data).use { it.append("other", "note", json("text" to "keep")) }
                        "chat" -> exec(data, "UPDATE synthetic_chat SET body='keep'")
                        "blob" -> data.resolve("blobs/extra").writeText("keep later write")
                    }
                    error("synthetic failed verification")
                }
            }
            assertTrue(failure.message!!.contains("keep service stopped"))
            assertFalse(root.resolve("backup/failure.json").exists())
            openReadOnly(data.resolve("coach.sqlite")).use { db ->
                assertEquals(2, db.query("SELECT kp_id FROM outline_kp WHERE course_id='C'").size)
                when (kind) {
                    "event" -> assertTrue(db.query("SELECT seq FROM events WHERE course_id='other'").isNotEmpty())
                    "chat" -> assertEquals("keep", db.query("SELECT body FROM synthetic_chat").single().str("body"))
                    "blob" -> assertEquals("keep later write", data.resolve("blobs/extra").readText())
                }
            }
        }
    }

    @Test fun `there is no unguarded apply or post restart file rollback command`() = fixture { root, data, bytes ->
        val input = root.resolve("input.json"); input.writeBytes(bytes)
        val before = outlineState(data)
        val args = listOf("--input", input.toString(), "--database", data.toString())
        assertFailsWith<IllegalArgumentException> { outlineAdditionCommand(args) }
        assertFailsWith<IllegalArgumentException> { outlineAdditionCommand(args + listOf("--rollback", root.resolve("backup").toString())) }
        assertEquals(before, outlineState(data))
    }
}
