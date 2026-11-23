package com.coach.desktop.books

import java.security.MessageDigest
import java.sql.Connection
import java.sql.Types
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

internal data class CourseMaterialLinkSpec(
    val bookId: String,
    val role: CourseMaterialRole,
    val weekNum: Int?,
    val userLocked: Boolean,
    val ordinal: Int,
)

internal data class CourseTopicLinkPlan(
    val bookId: String,
    val tocNodeId: String? = null,
    val startPage: Int,
    val endPage: Int,
    val role: CourseMaterialRole,
    val scopeNote: String = "",
)

internal data class CourseTopicPlan(
    val topicId: String? = null,
    val weekLabel: String,
    val weekNum: Int?,
    val ord: Int,
    val title: String,
    val examStatus: CourseTopicStatus,
    val examWeight: Double = 1.0,
    val sources: List<CourseTopicLinkPlan>,
)

internal data class CourseAlignmentPlan(
    val examProfile: CourseExamProfile? = null,
    val materialUpdates: List<CourseMaterialLinkSpec> = emptyList(),
    val topics: List<CourseTopicPlan> = emptyList(),
    val spoilerSanitizedCount: Int = 0,
)

internal data class AddCourseMaterialsResult(
    val addedBookIds: List<String>,
    val rejectedPastExamTitles: List<String>,
)

/**
 * Durable SQLite store for course collections, materials, auto-aligned topics,
 * source links, quality-gated topic packs, and continuous practice/anchor drafts.
 *
 * Locking invariants:
 * - `collection_materials.user_locked = 1`: re-alignment never overwrites manual week/role.
 * - `course_topics.user_locked = 1`: re-alignment never overwrites manual title or exam_status.
 * - `course_topic_sources.user_locked = 1`: preserved manual source link.
 * - `course_topic_sources.user_locked = -1`: tombstone; auto-alignment never re-attaches this book to the topic.
 */
internal object CourseStore {
    private val weekLabelNumberRegex = Regex("""(?:第\s*(\d{1,2})\s*周|(?i)\b(?:week|wk|w)\s*(\d{1,2})\b)""")

    fun ensureReady(db: Connection) {
        if (!BookSchemaMigration.tableExists(db, "course_topics") || !BookSchemaMigration.tableExists(db, "course_topic_sources")) {
            db.createStatement().use { sql ->
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS collections(
                        id TEXT PRIMARY KEY,
                        kind TEXT NOT NULL CHECK(kind IN ('SINGLE_BOOK','COURSE','BOOKLIST','CUSTOM')),
                        title TEXT NOT NULL,
                        metadata_json TEXT NOT NULL DEFAULT '{}',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        exam_date TEXT,
                        last_active_anchor_json TEXT,
                        source_hash TEXT,
                        user_locked INTEGER NOT NULL DEFAULT 0,
                        archived INTEGER NOT NULL DEFAULT 0
                    )""",
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS collection_materials(
                        collection_id TEXT NOT NULL,
                        material_id TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'PRIMARY',
                        ordinal INTEGER NOT NULL DEFAULT 0,
                        added_at INTEGER NOT NULL,
                        metadata_json TEXT NOT NULL DEFAULT '{}',
                        week_num INTEGER,
                        user_locked INTEGER NOT NULL DEFAULT 0,
                        authority_rank INTEGER NOT NULL DEFAULT 2,
                        source_hash TEXT,
                        PRIMARY KEY(collection_id, material_id)
                    )""",
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS course_topics(
                        topic_id TEXT PRIMARY KEY,
                        collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                        week_label TEXT NOT NULL,
                        ord INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        exam_status TEXT NOT NULL DEFAULT 'EXAMINABLE' CHECK(exam_status IN ('EXAMINABLE','EXCLUDED','PENDING_MATERIALS')),
                        user_locked INTEGER NOT NULL DEFAULT 0,
                        exam_weight REAL NOT NULL DEFAULT 1.0,
                        pack_payload_json TEXT,
                        pack_sources_hash TEXT,
                        practice_draft_json TEXT NOT NULL DEFAULT '{}',
                        mastery_state TEXT NOT NULL DEFAULT 'UNSEEN',
                        updated_at INTEGER NOT NULL
                    )""",
                )
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS course_topic_sources(
                        topic_id TEXT NOT NULL REFERENCES course_topics(topic_id) ON DELETE CASCADE,
                        book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                        toc_node_id TEXT,
                        start_page INTEGER NOT NULL,
                        end_page INTEGER NOT NULL,
                        source_role TEXT NOT NULL,
                        user_locked INTEGER NOT NULL DEFAULT 0,
                        scope_note TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(topic_id, book_id, start_page, end_page)
                    )""",
                )
            }
        }
        CourseVisionCacheStore.ensureSchema(db)
    }

    fun extractWeekNum(weekLabel: String): Int? {
        val m = weekLabelNumberRegex.find(weekLabel) ?: return null
        return (1..2).firstNotNullOfOrNull { idx -> m.groupValues.getOrNull(idx)?.toIntOrNull() }?.takeIf { it in 1..20 }
    }

    fun formatWeekLabel(weekNum: Int?): String =
        if (weekNum != null && weekNum in 1..20) "第 $weekNum 周" else "通用专题"

    fun createCourse(
        db: Connection,
        title: String,
        examProfile: CourseExamProfile = CourseExamProfile(),
        materialBookIds: List<String> = emptyList(),
        existingId: String? = null,
    ): Pair<String, List<String>> {
        ensureReady(db)
        val cleanTitle = title.trim().ifBlank { "未命名课程" }
        val courseId = existingId?.takeIf { it.isNotBlank() } ?: "course-${UUID.randomUUID().toString().take(12)}"
        val now = System.currentTimeMillis()
        val sortOrder = nextCollectionSortOrder(db)
        db.prepareStatement(
            """INSERT INTO collections(id, kind, title, metadata_json, created_at, updated_at, sort_order, exam_date, last_active_anchor_json, source_hash, user_locked, archived)
               VALUES(?, 'COURSE', ?, ?, ?, ?, ?, ?, NULL, NULL, 0, 0)
               ON CONFLICT(id) DO UPDATE SET
                   title=excluded.title,
                   metadata_json=excluded.metadata_json,
                   exam_date=excluded.exam_date,
                   updated_at=excluded.updated_at""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.setString(2, cleanTitle)
            sql.setString(3, CourseJson.encodeExamProfile(examProfile))
            sql.setLong(4, now)
            sql.setLong(5, now)
            sql.setInt(6, sortOrder)
            sql.setString(7, examProfile.examDate.takeIf { it.isNotBlank() })
            sql.executeUpdate()
        }
        val addRes = addMaterials(db, courseId, materialBookIds)
        return courseId to addRes.rejectedPastExamTitles
    }

    fun addMaterials(
        db: Connection,
        courseId: String,
        bookIds: List<String>,
    ): AddCourseMaterialsResult {
        ensureReady(db)
        if (bookIds.isEmpty()) return AddCourseMaterialsResult(emptyList(), emptyList())
        val added = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val now = System.currentTimeMillis()
        var nextOrd = db.prepareStatement("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM collection_materials WHERE collection_id=?").use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }
        for (bookId in bookIds.distinct()) {
            val meta = loadBookMeta(db, bookId) ?: continue
            if (CourseClientGates.isPastExamMaterial(meta.title, meta.sourceFilename)) {
                rejected += meta.title
                continue
            }
            val alreadyLinked = db.prepareStatement("SELECT user_locked FROM collection_materials WHERE collection_id=? AND material_id=?").use { sql ->
                sql.setString(1, courseId)
                sql.setString(2, bookId)
                sql.executeQuery().use { if (it.next()) it.getInt(1) else null }
            }
            if (alreadyLinked != null) {
                added += bookId
                continue
            }
            val role = CourseMaterialRole.infer(
                title = meta.title,
                format = meta.format,
                previewText = meta.previewText,
                pageCount = meta.pageCount,
                sourceFilename = meta.sourceFilename,
            )
            val weekNum = CourseMaterialRole.inferWeekNumber(meta.title, meta.sourceFilename, meta.previewText)
            val contentHash = loadBookContentFingerprint(db, bookId)
            db.prepareStatement(
                """INSERT INTO collection_materials(collection_id, material_id, role, ordinal, added_at, metadata_json, week_num, user_locked, authority_rank, source_hash)
                   VALUES(?,?,?,?,?,'{}',?,0,?,?)""",
            ).use { sql ->
                sql.setString(1, courseId)
                sql.setString(2, bookId)
                sql.setString(3, role.wire)
                sql.setInt(4, nextOrd++)
                sql.setLong(5, now)
                if (weekNum != null) sql.setInt(6, weekNum) else sql.setNull(6, Types.INTEGER)
                sql.setInt(7, role.authorityRank)
                sql.setString(8, contentHash)
                sql.executeUpdate()
            }
            added += bookId
        }
        refreshCollectionSourceHash(db, courseId, now)
        return AddCourseMaterialsResult(added, rejected)
    }

    /**
     * In-place material week/role edit from `⋯ -> 查看本课材料清单`.
     * Sets `user_locked = 1` on both `collection_materials` and the newly placed `course_topic_sources` link,
     * removes old auto/manual links in the course for that book, and refreshes affected `pack_sources_hash` values.
     */
    fun updateMaterialAssignment(
        db: Connection,
        courseId: String,
        bookId: String,
        newWeekNum: Int?,
        newRole: CourseMaterialRole,
    ) {
        ensureReady(db)
        val cleanWeek = newWeekNum?.takeIf { it in 1..20 }
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            db.prepareStatement(
                """UPDATE collection_materials
                   SET role=?, authority_rank=?, week_num=?, user_locked=1
                   WHERE collection_id=? AND material_id=?""",
            ).use { sql ->
                sql.setString(1, newRole.wire)
                sql.setInt(2, newRole.authorityRank)
                if (cleanWeek != null) sql.setInt(3, cleanWeek) else sql.setNull(3, Types.INTEGER)
                sql.setString(4, courseId)
                sql.setString(5, bookId)
                sql.executeUpdate()
            }
            // Find existing topic(s) inside this course that linked this book so we can refresh their hashes
            val oldTopicIds = db.prepareStatement(
                """SELECT DISTINCT cs.topic_id
                   FROM course_topic_sources cs
                   JOIN course_topics ct ON ct.topic_id = cs.topic_id
                   WHERE ct.collection_id=? AND cs.book_id=? AND cs.user_locked >= 0""",
            ).use { sql ->
                sql.setString(1, courseId)
                sql.setString(2, bookId)
                sql.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            db.prepareStatement(
                """DELETE FROM course_topic_sources
                   WHERE book_id=? AND user_locked >= 0
                     AND topic_id IN (SELECT topic_id FROM course_topics WHERE collection_id=?)""",
            ).use { sql ->
                sql.setString(1, bookId)
                sql.setString(2, courseId)
                sql.executeUpdate()
            }
            val meta = loadBookMeta(db, bookId)
            val pageCount = meta?.pageCount?.coerceAtLeast(1) ?: 1
            val targetTopicIds = mutableListOf<String>()
            if (cleanWeek != null) {
                val weekLabel = formatWeekLabel(cleanWeek)
                val existingTarget = db.prepareStatement(
                    """SELECT topic_id FROM course_topics
                       WHERE collection_id=? AND exam_status <> 'EXCLUDED' AND week_label=?
                       ORDER BY ord LIMIT 1""",
                ).use { sql ->
                    sql.setString(1, courseId)
                    sql.setString(2, weekLabel)
                    sql.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
                val targetId = existingTarget ?: insertCustomWeekTopic(
                    db = db,
                    courseId = courseId,
                    weekNum = cleanWeek,
                    title = meta?.title ?: weekLabel,
                    now = now,
                )
                val tombstoned = isSourceTombstoned(db, targetId, bookId)
                if (!tombstoned) {
                    db.prepareStatement(
                        """INSERT INTO course_topic_sources(topic_id, book_id, toc_node_id, start_page, end_page, source_role, user_locked, scope_note)
                           VALUES(?,?,NULL,1,?,?,1,?)
                           ON CONFLICT(topic_id, book_id, start_page, end_page) DO UPDATE SET
                               source_role=excluded.source_role,
                               user_locked=1,
                               scope_note=excluded.scope_note""",
                    ).use { sql ->
                        sql.setString(1, targetId)
                        sql.setString(2, bookId)
                        sql.setInt(3, pageCount)
                        sql.setString(4, newRole.wire)
                        sql.setString(5, "手动分配至$weekLabel")
                        sql.executeUpdate()
                    }
                    // If target topic was PENDING_MATERIALS and now has material, promote to EXAMINABLE unless locked
                    db.prepareStatement(
                        """UPDATE course_topics
                           SET exam_status='EXAMINABLE', updated_at=?
                           WHERE topic_id=? AND exam_status='PENDING_MATERIALS' AND user_locked=0""",
                    ).use { sql ->
                        sql.setLong(1, now)
                        sql.setString(2, targetId)
                        sql.executeUpdate()
                    }
                }
                targetTopicIds += targetId
            }
            for (tid in (oldTopicIds + targetTopicIds).distinct()) {
                recomputeTopicPackHash(db, courseId, tid, now)
            }
            refreshCollectionSourceHash(db, courseId, now)
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    /**
     * Non-destructive removal from course: leaves `books` and `pages` untouched in the user's library.
     */
    fun removeMaterialFromCourse(db: Connection, courseId: String, bookId: String) {
        ensureReady(db)
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            val affectedTopicIds = db.prepareStatement(
                """SELECT DISTINCT cs.topic_id
                   FROM course_topic_sources cs
                   JOIN course_topics ct ON ct.topic_id = cs.topic_id
                   WHERE ct.collection_id=? AND cs.book_id=?""",
            ).use { sql ->
                sql.setString(1, courseId)
                sql.setString(2, bookId)
                sql.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            db.prepareStatement(
                """DELETE FROM course_topic_sources
                   WHERE book_id=? AND topic_id IN (SELECT topic_id FROM course_topics WHERE collection_id=?)""",
            ).use { sql ->
                sql.setString(1, bookId)
                sql.setString(2, courseId)
                sql.executeUpdate()
            }
            db.prepareStatement("DELETE FROM collection_materials WHERE collection_id=? AND material_id=?").use { sql ->
                sql.setString(1, courseId)
                sql.setString(2, bookId)
                sql.executeUpdate()
            }
            for (tid in affectedTopicIds) {
                recomputeTopicPackHash(db, courseId, tid, now)
            }
            refreshCollectionSourceHash(db, courseId, now)
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    fun unlinkTopicSource(db: Connection, courseId: String, topicId: String, bookId: String) {
        ensureReady(db)
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            val updated = db.prepareStatement(
                "UPDATE course_topic_sources SET user_locked=-1 WHERE topic_id=? AND book_id=?",
            ).use { sql ->
                sql.setString(1, topicId)
                sql.setString(2, bookId)
                sql.executeUpdate()
            }
            if (updated == 0) {
                db.prepareStatement(
                    """INSERT OR IGNORE INTO course_topic_sources(topic_id, book_id, toc_node_id, start_page, end_page, source_role, user_locked, scope_note)
                       VALUES(?,?,NULL,0,0,'USER_NOTES',-1,'已手动移除')""",
                ).use { sql ->
                    sql.setString(1, topicId)
                    sql.setString(2, bookId)
                    sql.executeUpdate()
                }
            }
            recomputeTopicPackHash(db, courseId, topicId, now)
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    fun renameTopic(db: Connection, topicId: String, newTitle: String) {
        ensureReady(db)
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        db.prepareStatement("UPDATE course_topics SET title=?, user_locked=1, updated_at=? WHERE topic_id=?").use { sql ->
            sql.setString(1, trimmed)
            sql.setLong(2, System.currentTimeMillis())
            sql.setString(3, topicId)
            sql.executeUpdate()
        }
    }

    fun setTopicExamStatus(db: Connection, topicId: String, status: CourseTopicStatus) {
        ensureReady(db)
        db.prepareStatement("UPDATE course_topics SET exam_status=?, user_locked=1, updated_at=? WHERE topic_id=?").use { sql ->
            sql.setString(1, status.wire)
            sql.setLong(2, System.currentTimeMillis())
            sql.setString(3, topicId)
            sql.executeUpdate()
        }
    }

    fun updateExamProfile(db: Connection, courseId: String, profile: CourseExamProfile) {
        ensureReady(db)
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            db.prepareStatement(
                "UPDATE collections SET metadata_json=?, exam_date=?, user_locked=1, updated_at=? WHERE id=?",
            ).use { sql ->
                sql.setString(1, CourseJson.encodeExamProfile(profile))
                sql.setString(2, profile.examDate.takeIf { it.isNotBlank() })
                sql.setLong(3, now)
                sql.setString(4, courseId)
                sql.executeUpdate()
            }
            val topicIds = db.prepareStatement("SELECT topic_id FROM course_topics WHERE collection_id=?").use { sql ->
                sql.setString(1, courseId)
                sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            for (tid in topicIds) {
                recomputeTopicPackHash(db, courseId, tid, now)
            }
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    /**
     * Applies auto-alignment output (local or remote Sonnet planner) while strictly preserving
     * all manual locks (`collection_materials.user_locked = 1`, `course_topics.user_locked = 1`,
     * `course_topic_sources.user_locked = 1`, and tombstones `-1`).
     */
    fun applyAlignmentRespectingLocks(db: Connection, courseId: String, plan: CourseAlignmentPlan) {
        ensureReady(db)
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            if (plan.examProfile != null) {
                val colLocked = db.prepareStatement("SELECT user_locked FROM collections WHERE id=?").use { sql ->
                    sql.setString(1, courseId)
                    sql.executeQuery().use { if (it.next()) it.getInt(1) == 1 else false }
                }
                if (!colLocked) {
                    db.prepareStatement("UPDATE collections SET metadata_json=?, exam_date=?, updated_at=? WHERE id=?").use { sql ->
                        sql.setString(1, CourseJson.encodeExamProfile(plan.examProfile))
                        sql.setString(2, plan.examProfile.examDate.takeIf { it.isNotBlank() })
                        sql.setLong(3, now)
                        sql.setString(4, courseId)
                        sql.executeUpdate()
                    }
                }
            }

            // Update unlocked material weeks/roles; skip locked materials cleanly
            for (mat in plan.materialUpdates) {
                db.prepareStatement(
                    """UPDATE collection_materials
                       SET role=?, authority_rank=?, week_num=?
                       WHERE collection_id=? AND material_id=? AND user_locked=0""",
                ).use { sql ->
                    sql.setString(1, mat.role.wire)
                    sql.setInt(2, mat.role.authorityRank)
                    if (mat.weekNum != null) sql.setInt(3, mat.weekNum) else sql.setNull(3, Types.INTEGER)
                    sql.setString(4, courseId)
                    sql.setString(5, mat.bookId)
                    sql.executeUpdate()
                }
            }

            // Read authoritative current material locks (after unlocked updates)
            val lockedMaterialWeekByBookId = db.prepareStatement(
                "SELECT material_id, week_num, role FROM collection_materials WHERE collection_id=? AND user_locked=1",
            ).use { sql ->
                sql.setString(1, courseId)
                sql.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            put(
                                rows.getString(1),
                                Pair(
                                    rows.getObject(2)?.let { (it as Number).toInt() },
                                    CourseMaterialRole.fromWire(rows.getString(3)),
                                ),
                            )
                        }
                    }
                }
            }

            val existingTopics = db.prepareStatement(
                "SELECT topic_id, week_label, title, exam_status, user_locked FROM course_topics WHERE collection_id=?",
            ).use { sql ->
                sql.setString(1, courseId)
                sql.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                ExistingTopicRow(
                                    topicId = rows.getString(1),
                                    weekLabel = rows.getString(2),
                                    title = rows.getString(3),
                                    examStatus = CourseTopicStatus.fromWire(rows.getString(4)),
                                    userLocked = rows.getInt(5) == 1,
                                ),
                            )
                        }
                    }
                }
            }
            val touchedTopicIds = mutableSetOf<String>()

            for (incoming in plan.topics) {
                val matched = existingTopics.firstOrNull { ex ->
                    (incoming.topicId != null && ex.topicId == incoming.topicId) ||
                        (ex.weekLabel == incoming.weekLabel && normalizeMatch(ex.title) == normalizeMatch(incoming.title)) ||
                        (incoming.weekNum != null && extractWeekNum(ex.weekLabel) == incoming.weekNum &&
                            (ex.examStatus != CourseTopicStatus.EXCLUDED && incoming.examStatus != CourseTopicStatus.EXCLUDED))
                }
                val topicId = matched?.topicId ?: incoming.topicId?.takeIf { it.isNotBlank() }
                    ?: "topic-${UUID.randomUUID().toString().take(12)}"
                touchedTopicIds += topicId

                if (matched == null) {
                    db.prepareStatement(
                        """INSERT INTO course_topics(topic_id, collection_id, week_label, ord, title, exam_status, user_locked, exam_weight, pack_payload_json, pack_sources_hash, practice_draft_json, mastery_state, updated_at)
                           VALUES(?,?,?,?,?,?,0,?,NULL,NULL,'{}','UNSEEN',?)""",
                    ).use { sql ->
                        sql.setString(1, topicId)
                        sql.setString(2, courseId)
                        sql.setString(3, incoming.weekLabel)
                        sql.setInt(4, incoming.ord)
                        sql.setString(5, incoming.title)
                        sql.setString(6, incoming.examStatus.wire)
                        sql.setDouble(7, incoming.examWeight)
                        sql.setLong(8, now)
                        sql.executeUpdate()
                    }
                } else {
                    if (matched.userLocked) {
                        db.prepareStatement(
                            "UPDATE course_topics SET ord=?, exam_weight=?, updated_at=? WHERE topic_id=?",
                        ).use { sql ->
                            sql.setInt(1, incoming.ord)
                            sql.setDouble(2, incoming.examWeight)
                            sql.setLong(3, now)
                            sql.setString(4, topicId)
                            sql.executeUpdate()
                        }
                    } else {
                        db.prepareStatement(
                            """UPDATE course_topics
                               SET week_label=?, ord=?, title=?, exam_status=?, exam_weight=?, updated_at=?
                               WHERE topic_id=?""",
                        ).use { sql ->
                            sql.setString(1, incoming.weekLabel)
                            sql.setInt(2, incoming.ord)
                            sql.setString(3, incoming.title)
                            sql.setString(4, incoming.examStatus.wire)
                            sql.setDouble(5, incoming.examWeight)
                            sql.setLong(6, now)
                            sql.setString(7, topicId)
                            sql.executeUpdate()
                        }
                    }
                }

                // Clear only auto-generated sources (`user_locked = 0`) on this topic before inserting fresh ones
                db.prepareStatement("DELETE FROM course_topic_sources WHERE topic_id=? AND user_locked=0").use { sql ->
                    sql.setString(1, topicId)
                    sql.executeUpdate()
                }
                val tombstonedBooks = db.prepareStatement(
                    "SELECT DISTINCT book_id FROM course_topic_sources WHERE topic_id=? AND user_locked=-1",
                ).use { sql ->
                    sql.setString(1, topicId)
                    sql.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                }

                for (src in incoming.sources) {
                    if (src.bookId in tombstonedBooks) continue
                    val lockedAssignment = lockedMaterialWeekByBookId[src.bookId]
                    if (lockedAssignment != null && lockedAssignment.first != null && incoming.weekNum != null &&
                        lockedAssignment.first != incoming.weekNum
                    ) {
                        // Respect user-locked week: never re-link this book onto a mismatched week topic
                        continue
                    }
                    val effectiveRole = lockedAssignment?.second ?: src.role
                    val existingLock = db.prepareStatement(
                        "SELECT user_locked FROM course_topic_sources WHERE topic_id=? AND book_id=? AND start_page=? AND end_page=?",
                    ).use { sql ->
                        sql.setString(1, topicId)
                        sql.setString(2, src.bookId)
                        sql.setInt(3, src.startPage)
                        sql.setInt(4, src.endPage)
                        sql.executeQuery().use { if (it.next()) it.getInt(1) else null }
                    }
                    if (existingLock != null) continue
                    db.prepareStatement(
                        """INSERT INTO course_topic_sources(topic_id, book_id, toc_node_id, start_page, end_page, source_role, user_locked, scope_note)
                           VALUES(?,?,?,?,?,?,0,?)""",
                    ).use { sql ->
                        sql.setString(1, topicId)
                        sql.setString(2, src.bookId)
                        if (src.tocNodeId != null) sql.setString(3, src.tocNodeId) else sql.setNull(3, Types.VARCHAR)
                        sql.setInt(4, src.startPage)
                        sql.setInt(5, src.endPage)
                        sql.setString(6, effectiveRole.wire)
                        sql.setString(7, src.scopeNote)
                        sql.executeUpdate()
                    }
                }
            }

            // Preserve any user-locked topics not explicitly mentioned in plan, and clean up stale unlocked topics with zero sources
            for (ex in existingTopics) {
                if (ex.topicId !in touchedTopicIds && !ex.userLocked) {
                    val hasLockedSources = db.prepareStatement(
                        "SELECT 1 FROM course_topic_sources WHERE topic_id=? AND user_locked<>0 LIMIT 1",
                    ).use { sql ->
                        sql.setString(1, ex.topicId)
                        sql.executeQuery().use { it.next() }
                    }
                    if (!hasLockedSources) {
                        db.prepareStatement("DELETE FROM course_topics WHERE topic_id=?").use { sql ->
                            sql.setString(1, ex.topicId)
                            sql.executeUpdate()
                        }
                    } else {
                        touchedTopicIds += ex.topicId
                    }
                } else {
                    touchedTopicIds += ex.topicId
                }
            }

            // Ensure every user-locked material is actually linked onto its locked week topic
            for ((bookId, lockInfo) in lockedMaterialWeekByBookId) {
                val lockedWeek = lockInfo.first ?: continue
                val role = lockInfo.second
                val weekLabel = formatWeekLabel(lockedWeek)
                val targetTopicId = db.prepareStatement(
                    """SELECT topic_id FROM course_topics
                       WHERE collection_id=? AND exam_status <> 'EXCLUDED' AND week_label=?
                       ORDER BY ord LIMIT 1""",
                ).use { sql ->
                    sql.setString(1, courseId)
                    sql.setString(2, weekLabel)
                    sql.executeQuery().use { if (it.next()) it.getString(1) else null }
                } ?: insertCustomWeekTopic(
                    db = db,
                    courseId = courseId,
                    weekNum = lockedWeek,
                    title = loadBookMeta(db, bookId)?.title ?: weekLabel,
                    now = now,
                ).also { touchedTopicIds += it }

                if (!isSourceTombstoned(db, targetTopicId, bookId)) {
                    val hasLinkOnTarget = db.prepareStatement(
                        "SELECT 1 FROM course_topic_sources WHERE topic_id=? AND book_id=? AND user_locked>=0 LIMIT 1",
                    ).use { sql ->
                        sql.setString(1, targetTopicId)
                        sql.setString(2, bookId)
                        sql.executeQuery().use { it.next() }
                    }
                    if (!hasLinkOnTarget) {
                        val pageCount = loadBookMeta(db, bookId)?.pageCount?.coerceAtLeast(1) ?: 1
                        db.prepareStatement(
                            """INSERT OR IGNORE INTO course_topic_sources(topic_id, book_id, toc_node_id, start_page, end_page, source_role, user_locked, scope_note)
                               VALUES(?,?,NULL,1,?,?,1,?)""",
                        ).use { sql ->
                            sql.setString(1, targetTopicId)
                            sql.setString(2, bookId)
                            sql.setInt(3, pageCount)
                            sql.setString(4, role.wire)
                            sql.setString(5, "锁定至$weekLabel")
                            sql.executeUpdate()
                        }
                    }
                }
            }

            for (tid in touchedTopicIds) {
                recomputeTopicPackHash(db, courseId, tid, now)
            }
            refreshCollectionSourceHash(db, courseId, now)
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    fun saveTopicPack(db: Connection, topicId: String, pack: CourseTopicPack, sourcesHash: String) {
        ensureReady(db)
        val stamped = pack.copy(generatedFromHash = sourcesHash)
        db.prepareStatement(
            "UPDATE course_topics SET pack_payload_json=?, pack_sources_hash=?, updated_at=? WHERE topic_id=?",
        ).use { sql ->
            sql.setString(1, CourseJson.encodeTopicPack(stamped))
            sql.setString(2, sourcesHash)
            sql.setLong(3, System.currentTimeMillis())
            sql.setString(4, topicId)
            sql.executeUpdate()
        }
    }

    fun savePracticeDraft(db: Connection, courseId: String, draft: CourseTopicPracticeDraft) {
        ensureReady(db)
        val now = System.currentTimeMillis()
        val stamped = draft.copy(updatedAt = now)
        db.prepareStatement(
            "UPDATE course_topics SET practice_draft_json=?, updated_at=? WHERE topic_id=?",
        ).use { sql ->
            sql.setString(1, CourseJson.encodePracticeDraft(stamped))
            sql.setLong(2, now)
            sql.setString(3, stamped.topicId)
            sql.executeUpdate()
        }
        val anchor = CourseActiveAnchor(
            courseId = courseId,
            topicId = stamped.topicId,
            bookId = stamped.activeSourceBookId,
            page = stamped.activePage.coerceAtLeast(1),
            companionOpen = stamped.companionOpen,
            updatedAt = now,
        )
        db.prepareStatement("UPDATE collections SET last_active_anchor_json=?, updated_at=? WHERE id=?").use { sql ->
            sql.setString(1, CourseJson.encodeActiveAnchor(anchor))
            sql.setLong(2, now)
            sql.setString(3, courseId)
            sql.executeUpdate()
        }
    }

    fun saveActiveAnchor(db: Connection, anchor: CourseActiveAnchor) {
        ensureReady(db)
        val now = System.currentTimeMillis()
        db.prepareStatement("UPDATE collections SET last_active_anchor_json=?, updated_at=? WHERE id=?").use { sql ->
            sql.setString(1, CourseJson.encodeActiveAnchor(anchor.copy(updatedAt = now)))
            sql.setLong(2, now)
            sql.setString(3, anchor.courseId)
            sql.executeUpdate()
        }
    }

    fun loadCourseDetail(
        db: Connection,
        courseId: String,
        clock: Clock = Clock.systemDefaultZone(),
    ): CourseDetailSnapshot {
        ensureReady(db)
        val col = db.prepareStatement(
            "SELECT title, metadata_json, exam_date, last_active_anchor_json, updated_at FROM collections WHERE id=?",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                check(rows.next()) { "找不到课程记录（$courseId）。" }
                Quad(
                    rows.getString(1),
                    CourseJson.decodeExamProfile(rows.getString(2), rows.getString(3)),
                    CourseJson.decodeActiveAnchor(rows.getString(4)),
                    rows.getLong(5),
                )
            }
        }
        val materials = db.prepareStatement(
            """SELECT cm.material_id, b.title, b.format, cm.role, cm.authority_rank, cm.week_num, cm.user_locked,
                      cm.added_at,
                      (SELECT COUNT(*) FROM pages p WHERE p.book=b.id),
                      (SELECT COUNT(*) FROM pages p WHERE p.book=b.id AND p.ocr_pending=1)
               FROM collection_materials cm
               JOIN books b ON b.id = cm.material_id
               WHERE cm.collection_id=?
               ORDER BY COALESCE(cm.week_num, 999), cm.authority_rank, cm.ordinal, cm.added_at""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val fmt = rows.getString(3)
                        val pageCount = rows.getInt(9)
                        val pendingOcr = rows.getInt(10)
                        val role = CourseMaterialRole.fromWire(rows.getString(4))
                        add(
                            CourseMaterialEntry(
                                bookId = rows.getString(1),
                                title = rows.getString(2),
                                format = fmt,
                                role = role,
                                authorityRank = role.authorityRank,
                                weekNum = rows.getObject(6)?.let { (it as Number).toInt() },
                                userLocked = rows.getInt(7) == 1,
                                pageCount = pageCount,
                                addedAt = rows.getLong(8),
                                ocrPendingCount = pendingOcr,
                                needsVision = fmt == "pdf" && pendingOcr > 0 && pageCount <= MAX_HANDWRITTEN_VISION_PAGES,
                            ),
                        )
                    }
                }
            }
        }.sortedWith(compareBy<CourseMaterialEntry>({ it.weekNum ?: 999 }, { it.role.displayOrder }, { it.title }))

        val sourcesByTopic = db.prepareStatement(
            """SELECT cs.topic_id, cs.book_id, b.title, b.format, cs.source_role, cs.toc_node_id,
                      cs.start_page, cs.end_page, cs.user_locked, cs.scope_note
               FROM course_topic_sources cs
               JOIN course_topics ct ON ct.topic_id = cs.topic_id
               JOIN books b ON b.id = cs.book_id
               WHERE ct.collection_id=? AND cs.user_locked >= 0""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                val map = LinkedHashMap<String, MutableList<CourseTopicSourceLink>>()
                while (rows.next()) {
                    val role = CourseMaterialRole.fromWire(rows.getString(5))
                    val link = CourseTopicSourceLink(
                        topicId = rows.getString(1),
                        bookId = rows.getString(2),
                        bookTitle = rows.getString(3),
                        bookFormat = rows.getString(4),
                        role = role,
                        authorityRank = role.authorityRank,
                        tocNodeId = rows.getString(6),
                        startPage = rows.getInt(7).coerceAtLeast(1),
                        endPage = rows.getInt(8).coerceAtLeast(1),
                        userLocked = rows.getInt(9),
                        scopeNote = rows.getString(10),
                    )
                    map.getOrPut(link.topicId) { mutableListOf() }.add(link)
                }
                map.mapValues { (_, list) ->
                    list.sortedWith(compareBy<CourseTopicSourceLink>({ it.role.displayOrder }, { it.startPage }, { it.bookTitle }))
                }
            }
        }

        val allTopics = db.prepareStatement(
            """SELECT topic_id, week_label, ord, title, exam_status, user_locked, exam_weight,
                      pack_payload_json, pack_sources_hash, practice_draft_json, mastery_state, updated_at
               FROM course_topics
               WHERE collection_id=?
               ORDER BY ord, week_label, topic_id""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val tid = rows.getString(1)
                        val weekLabel = rows.getString(2)
                        val status = CourseTopicStatus.fromWire(rows.getString(5))
                        val packHash = rows.getString(9)
                        val decodedPack = CourseJson.decodeTopicPack(rows.getString(8))
                            ?.takeIf { packHash.isNullOrBlank() || it.generatedFromHash.isBlank() || it.generatedFromHash == packHash }
                        val draft = CourseJson.decodePracticeDraft(rows.getString(10), tid)
                        add(
                            CourseTopicEntry(
                                topicId = tid,
                                collectionId = courseId,
                                weekLabel = weekLabel,
                                weekNum = extractWeekNum(weekLabel),
                                ord = rows.getInt(3),
                                title = rows.getString(4),
                                examStatus = status,
                                userLocked = rows.getInt(6) == 1,
                                examWeight = rows.getDouble(7),
                                sources = sourcesByTopic[tid].orEmpty(),
                                pack = decodedPack,
                                packSourcesHash = packHash,
                                practiceDraft = draft,
                                masteryState = rows.getString(11),
                                updatedAt = rows.getLong(12),
                            ),
                        )
                    }
                }
            }
        }.let { CourseExamMode.normalizeTopics(it, materials) }
            .sortedWith(compareBy<CourseTopicEntry>({ it.weekNum ?: 999 }, { it.ord }, { it.title }))

        var sanitizedNotes = 0
        for (m in materials.filter { it.role == CourseMaterialRole.USER_NOTES }) {
            sanitizedNotes += countSanitizedNotes(db, m.bookId)
        }

        val snapshot = CourseDetailSnapshot(
            courseId = courseId,
            title = col.first,
            examProfile = col.second,
            materials = materials,
            examinableTopics = allTopics.filter { it.examStatus == CourseTopicStatus.EXAMINABLE },
            pendingTopics = allTopics.filter { it.examStatus == CourseTopicStatus.PENDING_MATERIALS },
            excludedTopics = allTopics.filter { it.examStatus == CourseTopicStatus.EXCLUDED },
            activeAnchor = col.third,
            spoilerSanitizedCount = sanitizedNotes,
            alignedAt = col.fourth,
        )
        val plan = ExamCountdownPlanner.planForCourse(
            db = db,
            detail = snapshot,
            today = LocalDate.now(clock),
            nowMs = clock.millis(),
        )
        return snapshot.copy(countdownPlan = plan)
    }

    fun loadPagesForBook(db: Connection, bookId: String, maxPages: Int = 500): List<BookPage> =
        db.prepareStatement(
            "SELECT page, text, ocr, ocr_pending FROM pages WHERE book=? ORDER BY page LIMIT ?",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, maxPages)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            BookPage(
                                number = rows.getInt(1),
                                text = rows.getString(2),
                                ocr = rows.getInt(3) == 1,
                                ocrPending = rows.getInt(4) == 1,
                            ),
                        )
                    }
                }
            }
        }

    fun recomputeTopicSourcesHash(db: Connection, courseId: String, topicId: String): String {
        val profile = db.prepareStatement("SELECT metadata_json, exam_date FROM collections WHERE id=?").use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use {
                if (it.next()) CourseJson.decodeExamProfile(it.getString(1), it.getString(2))
                else CourseExamProfile()
            }
        }
        val parts = db.prepareStatement(
            """SELECT cs.book_id, cs.start_page, cs.end_page, cs.source_role, cs.user_locked, COALESCE(cm.source_hash, '')
               FROM course_topic_sources cs
               LEFT JOIN collection_materials cm ON cm.collection_id=? AND cm.material_id=cs.book_id
               WHERE cs.topic_id=? AND cs.user_locked >= 0
               ORDER BY cs.book_id, cs.start_page, cs.end_page""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.setString(2, topicId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            "${rows.getString(1)}:${rows.getInt(2)}-${rows.getInt(3)}:${rows.getString(4)}:${rows.getInt(5)}:${rows.getString(6)}",
                        )
                    }
                }
            }
        }
        val raw = parts.joinToString("|") + "||" + CourseJson.encodeExamProfile(profile.forContentHash())
        return sha256(raw)
    }

    private fun recomputeTopicPackHash(db: Connection, courseId: String, topicId: String, now: Long) {
        val newHash = recomputeTopicSourcesHash(db, courseId, topicId)
        val prevHash = db.prepareStatement("SELECT pack_sources_hash FROM course_topics WHERE topic_id=?").use { sql ->
            sql.setString(1, topicId)
            sql.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        if (prevHash != null && prevHash != newHash) {
            // Invalidate cached summary while keeping practice_draft_json untouched
            db.prepareStatement(
                "UPDATE course_topics SET pack_sources_hash=?, pack_payload_json=NULL, updated_at=? WHERE topic_id=?",
            ).use { sql ->
                sql.setString(1, newHash)
                sql.setLong(2, now)
                sql.setString(3, topicId)
                sql.executeUpdate()
            }
        } else {
            db.prepareStatement(
                "UPDATE course_topics SET pack_sources_hash=?, updated_at=? WHERE topic_id=?",
            ).use { sql ->
                sql.setString(1, newHash)
                sql.setLong(2, now)
                sql.setString(3, topicId)
                sql.executeUpdate()
            }
        }
    }

    private fun refreshCollectionSourceHash(db: Connection, courseId: String, now: Long) {
        val parts = db.prepareStatement(
            """SELECT material_id, role, COALESCE(week_num, -1), user_locked, COALESCE(source_hash, '')
               FROM collection_materials
               WHERE collection_id=?
               ORDER BY material_id""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add("${rows.getString(1)}:${rows.getString(2)}:${rows.getInt(3)}:${rows.getInt(4)}:${rows.getString(5)}")
                    }
                }
            }
        }
        val hash = sha256(parts.joinToString("|"))
        db.prepareStatement("UPDATE collections SET source_hash=?, updated_at=? WHERE id=?").use { sql ->
            sql.setString(1, hash)
            sql.setLong(2, now)
            sql.setString(3, courseId)
            sql.executeUpdate()
        }
    }

    private fun insertCustomWeekTopic(
        db: Connection,
        courseId: String,
        weekNum: Int,
        title: String,
        now: Long,
    ): String {
        val topicId = "topic-${UUID.randomUUID().toString().take(12)}"
        db.prepareStatement(
            """INSERT INTO course_topics(topic_id, collection_id, week_label, ord, title, exam_status, user_locked, exam_weight, pack_payload_json, pack_sources_hash, practice_draft_json, mastery_state, updated_at)
               VALUES(?,?,?,?,?,'EXAMINABLE',0,1.0,NULL,NULL,'{}','UNSEEN',?)""",
        ).use { sql ->
            sql.setString(1, topicId)
            sql.setString(2, courseId)
            sql.setString(3, formatWeekLabel(weekNum))
            sql.setInt(4, weekNum * 10)
            sql.setString(5, title.take(120))
            sql.setLong(6, now)
            sql.executeUpdate()
        }
        return topicId
    }

    private fun isSourceTombstoned(db: Connection, topicId: String, bookId: String): Boolean =
        db.prepareStatement(
            "SELECT 1 FROM course_topic_sources WHERE topic_id=? AND book_id=? AND user_locked=-1 LIMIT 1",
        ).use { sql ->
            sql.setString(1, topicId)
            sql.setString(2, bookId)
            sql.executeQuery().use { it.next() }
        }

    private fun loadBookMeta(db: Connection, bookId: String): BookMetaRow? {
        val hasSourceFilename = BookSchemaMigration.tableColumns(db, "books").contains("source_filename")
        val query = if (hasSourceFilename) {
            "SELECT title, format, source_filename FROM books WHERE id=?"
        } else {
            "SELECT title, format, NULL FROM books WHERE id=?"
        }
        val basic = db.prepareStatement(query).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) return null
                Triple(rows.getString(1), rows.getString(2), rows.getString(3))
            }
        }
        val pageRows = db.prepareStatement(
            "SELECT page, text FROM pages WHERE book=? ORDER BY page",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                var count = 0
                val preview = StringBuilder()
                while (rows.next()) {
                    count++
                    if (count <= 3 && preview.length < 1800) {
                        preview.append(rows.getString(2)).append('\n')
                    }
                }
                count to preview.toString()
            }
        }
        return BookMetaRow(
            bookId = bookId,
            title = basic.first,
            format = basic.second,
            sourceFilename = basic.third,
            pageCount = pageRows.first.coerceAtLeast(1),
            previewText = pageRows.second,
        )
    }

    private fun loadBookContentFingerprint(db: Connection, bookId: String): String {
        val sample = db.prepareStatement(
            "SELECT page, SUBSTR(text, 1, 240) FROM pages WHERE book=? ORDER BY page LIMIT 12",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildString {
                    while (rows.next()) {
                        append(rows.getInt(1)).append(':').append(rows.getString(2)).append('\n')
                    }
                }
            }
        }
        return sha256("$bookId\n$sample")
    }

    private fun countSanitizedNotes(db: Connection, bookId: String): Int {
        var total = 0
        db.prepareStatement("SELECT text FROM pages WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                while (rows.next()) {
                    total += CourseClientGates.sanitizeUserNote(rows.getString(1)).redactedCount
                }
            }
        }
        return total
    }

    private fun nextCollectionSortOrder(db: Connection): Int =
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM collections").use { rows ->
                if (rows.next()) rows.getInt(1) else 1
            }
        }

    private fun normalizeMatch(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{Punct}]+"), "")

    internal fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class ExistingTopicRow(
        val topicId: String,
        val weekLabel: String,
        val title: String,
        val examStatus: CourseTopicStatus,
        val userLocked: Boolean,
    )

    private data class BookMetaRow(
        val bookId: String,
        val title: String,
        val format: String,
        val sourceFilename: String?,
        val pageCount: Int,
        val previewText: String,
    )

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
