package com.coach.migrate

import com.coach.store.LedgerStore
import java.nio.channels.FileChannel
import java.nio.file.*
import java.sql.Connection
import kotlin.io.path.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class FormalImportTest {
    private fun fixture(block:(ConfirmedImportTest,Connection,Path,Path)->Unit) {
        val f=ConfirmedImportTest();val backups=Files.createTempDirectory("formal-backup-test")
        try { f.withDatabase { db ->
            val data=Path.of(db.query("PRAGMA database_list").single().str("file")).parent
            data.resolve("blobs").resolve("synthetic-blob").writeText("Synthetic archived material")
            block(f,db,data,backups.resolve("backup"))
        } } finally { backups.toFile().deleteRecursively() }
    }
    private fun apply(f:ConfirmedImportTest,data:Path,backup:Path):JsonObject {
        val mapping=f.mapping();return applyConfirmed(data,backup,mapping,f.seed,f.plan(mapping),"synthetic-confirmation-sha")
    }
    @Test fun `offline import restores its backup and repeats without changing supply`() = fixture { f,db,data,backup ->
        val before=databaseState(db,"course")
        for(id in listOf("available","reserved","constructed")) {
            db.exec("INSERT INTO questions(question_id,course_id,format,stem_md,origin,fingerprint,key_state) VALUES(?,'course',?,'Synthetic native question','COACH',?,'VERIFIED')",
                id,if(id=="constructed") "SHORT_ANSWER" else "SINGLE_CHOICE",id)
            db.exec("INSERT INTO question_kp VALUES(?,'k1',1)",id)
        }
        db.exec("INSERT INTO offline_reservations VALUES('reserved','synthetic-pack',?)",Long.MAX_VALUE)
        db.exec("INSERT INTO constructed_details VALUES('constructed','hash','answer','[]','rubric','generator','reviewer')")
        val seeded=databaseState(db,"course")
        val report=apply(f,data,backup)
        assertEquals(JsonPrimitive(true),report["backup_restore_verified"])
        assertEquals(JsonPrimitive(0),report.obj("second")["records_inserted"])
        assertEquals(JsonPrimitive(0),report.obj("second")["records_revised"])
        assertEquals(JsonPrimitive(0),report.obj("second")["events_added"])
        assertEquals(JsonPrimitive(2),report.obj("isolation")["associated_questions"])
        assertNotEquals(before,seeded)
        openReadOnly(backup.resolve("coach.sqlite")).use { assertEquals(seeded,databaseState(it,"course")) }
        assertEquals(JsonPrimitive(1),report.obj("supply").obj("k1")["poolAvailable"])
        assertEquals(JsonPrimitive(1),report.obj("supply").obj("k1")["constructedSupply"])
        // SQL evidence must agree with the actual server methods, including reservation filtering.
        LedgerStore(backup.resolve("restored"),clock={report.getValue("supply_clock").jsonPrimitive.long}).use { store ->
            for((kp,counts) in report.obj("supply")) {
                assertEquals(counts.jsonObject.getValue("poolAvailable").jsonPrimitive.int,store.poolAvailable("course",kp))
                assertEquals(counts.jsonObject.getValue("constructedSupply").jsonPrimitive.int,store.constructedSupply("course")[kp] ?: 0)
            }
        }
        assertEquals(blobInventory(data),blobInventory(backup))
        assertFails { apply(f,data,backup) } // Existing recovery material is never overwritten.
    }
    @Test fun `active server lock refuses import and rollback before writing`() = fixture { f,db,data,backup ->
        val before=databaseState(db,"course")
        FileChannel.open(data.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { channel -> channel.lock().use {
            assertFails { apply(f,data,backup) }
            assertFails { rollbackConfirmed(data,backup) }
        } }
        assertFalse(backup.exists());assertEquals(before,databaseState(db,"course"))
    }
    @Test fun `rollback really restores database and blob bytes on an independent copy`() = fixture { f,_,data,backup ->
        apply(f,data,backup)
        val copy=backup.parent.resolve("rollback-copy");privateDir(copy)
        backupDatabase(data.resolve("coach.sqlite"),copy.resolve("coach.sqlite"));copyBlobs(data,copy)
        // Recovery uses the guard persisted before commit, even if final report writing was interrupted.
        backup.resolve("result.json").deleteExisting();backup.resolve("after-state.json").deleteExisting()
        val report=rollbackConfirmed(copy,backup)
        assertEquals(JsonPrimitive(true),report["database_byte_identical"])
        assertContentEquals(backup.resolve("coach.sqlite").readBytes(),copy.resolve("coach.sqlite").readBytes())
        assertEquals(blobInventory(backup),blobInventory(copy))
        assertEquals(report,rollbackConfirmed(copy,backup)) // Recovery itself is repeatable.
    }
    @Test fun `rollback refuses new native activity and damaged recovery material`() = fixture { f,db,data,backup ->
        apply(f,data,backup)
        db.exec("UPDATE questions SET stem_md='Later native edit' WHERE question_id='legacy:q0'")
        val later=databaseState(db,"course")
        assertFailsWith<IllegalArgumentException> { rollbackConfirmed(data,backup) }
        assertEquals(later,databaseState(db,"course"))
        backup.resolve("blobs/synthetic-blob").writeText("Damaged backup")
        assertFailsWith<IllegalArgumentException> { rollbackConfirmed(data,backup) }
        assertEquals(later,databaseState(db,"course"))
    }
    @Test fun `changed target blobs prevent rollback`() = fixture { f,_,data,backup ->
        apply(f,data,backup)
        data.resolve("blobs/new-native-blob").writeText("Later native material")
        assertFailsWith<IllegalArgumentException> { rollbackConfirmed(data,backup) }
        assertTrue(data.resolve("blobs/new-native-blob").exists())
    }
}
