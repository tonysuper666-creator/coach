package com.coach.desktop.books

import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CourseSourceMigrationTest {
    private fun Connection.count(query: String) = createStatement().use { sql ->
        sql.executeQuery(query).use { rows -> check(rows.next()); rows.getInt(1) }
    }

    @Test
    fun olderAndCurrentProfilesRetainEverySourceAndOnlySyncLiveLinks() {
        for ((version, journalInstalled) in listOf(5 to false, 9 to false, 9 to true)) {
            val dir = Files.createTempDirectory("course-source-v$version")
            try {
                val old = TextbookLibrary(dir)
                old.withCourseDatabase { db ->
                    db.createStatement().use { sql ->
                        // The pre-fix writer used SQLite's default foreign_keys=OFF.
                        sql.execute("PRAGMA foreign_keys=OFF")
                        sql.execute("DROP TABLE course_topic_sources")
                        sql.execute("""CREATE TABLE course_topic_sources(
                            topic_id TEXT NOT NULL REFERENCES course_topics(topic_id) ON DELETE CASCADE,
                            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                            toc_node_id TEXT, start_page INTEGER NOT NULL, end_page INTEGER NOT NULL,
                            source_role TEXT NOT NULL, user_locked INTEGER NOT NULL DEFAULT 0,
                            scope_note TEXT NOT NULL DEFAULT '', PRIMARY KEY(topic_id,book_id,start_page,end_page))""")
                        sql.execute("INSERT INTO books(id,title,format,touched) VALUES('b','Synthetic','txt',1)")
                        sql.execute("INSERT INTO collections(id,kind,title,created_at,updated_at) VALUES('c','COURSE','Course',1,1)")
                        sql.execute("""INSERT INTO course_topics(topic_id,collection_id,week_label,ord,title,updated_at)
                            VALUES('live','c','Week 1',1,'Topic',1)""")
                        if (journalInstalled) LearningJournal.installProfile(old)
                        // Same range and payload on the replacement topic: relinking would collide.
                        sql.execute("""INSERT INTO course_topic_sources VALUES
                            ('old','b','toc',3,7,'PRIMARY',0,'range'),
                            ('live','b','toc',3,7,'PRIMARY',0,'range'),
                            ('live','missing-book',NULL,1,2,'PRIMARY',-1,'manual exclusion')""")
                        sql.execute("PRAGMA user_version=$version")
                    }
                    assertEquals(2, db.count("SELECT count(*) FROM pragma_foreign_key_check"))
                }
                repeat(2) {
                    val reopened = TextbookLibrary(dir)
                    LearningJournal.installProfile(reopened)
                    reopened.withCourseDatabase { db ->
                        assertEquals(0, db.count("SELECT count(*) FROM pragma_foreign_key_check"))
                        assertEquals(3, db.count("SELECT count(*) FROM course_topic_sources"))
                        assertEquals(1, db.count("SELECT count(*) FROM course_topics"))
                        assertEquals(1, db.count("""SELECT count(*) FROM course_topic_sources WHERE retired_topic_id='old'
                            AND retired_book_id='b' AND toc_node_id='toc' AND start_page=3 AND end_page=7 AND scope_note='range'"""))
                        assertEquals(1, db.count("""SELECT count(*) FROM course_topic_sources WHERE retired_topic_id='live'
                            AND retired_book_id='missing-book' AND user_locked=-1 AND scope_note='manual exclusion'"""))
                        if (journalInstalled) {
                            assertEquals(3, db.count("""SELECT count(*) FROM learning_sync_outbox
                                WHERE kind='topic_source' AND after_json IS NOT NULL AND before_json IS NULL"""))
                            assertEquals(2, db.count("""SELECT count(*) FROM learning_sync_outbox WHERE kind='topic_source'
                                AND after_json IS NULL AND json_extract(before_json,'$.topic_id') IS NOT NULL"""))
                        } else {
                            assertEquals(1, db.count("SELECT count(*) FROM learning_sync_outbox WHERE kind='topic_source' AND baseline=1"))
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun realignmentCascadesThroughTheLibraryWriter() {
        val dir = Files.createTempDirectory("course-source-realign")
        try {
            val library = TextbookLibrary(dir)
            library.withCourseDatabase { db ->
                db.createStatement().use { sql ->
                    sql.execute("INSERT INTO books(id,title,format,touched) VALUES('b','Synthetic','txt',1)")
                    sql.execute("INSERT INTO collections(id,kind,title,created_at,updated_at) VALUES('c','COURSE','Course',1,1)")
                    sql.execute("""INSERT INTO course_topics(topic_id,collection_id,week_label,ord,title,updated_at)
                        VALUES('t','c','Week 1',1,'Topic',1)""")
                    sql.execute("""INSERT INTO course_topic_sources(topic_id,book_id,start_page,end_page,source_role)
                        VALUES('t','b',1,2,'PRIMARY')""")
                }
                CourseStore.applyAlignmentRespectingLocks(db, "c", CourseAlignmentPlan(topics = emptyList(), includeExamPoints = false))
                assertEquals(0, db.count("SELECT count(*) FROM course_topics"))
                assertEquals(0, db.count("SELECT count(*) FROM course_topic_sources"))
                assertFailsWith<java.sql.SQLException> {
                    db.createStatement().use { it.execute("""INSERT INTO course_topic_sources(topic_id,book_id,start_page,end_page,source_role)
                        VALUES('missing','b',1,2,'PRIMARY')""") }
                }
                assertTrue(db.autoCommit)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
