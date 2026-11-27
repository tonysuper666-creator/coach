package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection

/**
 * Obsidian 库和 Coach 的批注对一次账。
 * 导出笔记靠文首的 `coach_record_id` 和条目里的 `<!-- 记录键 -->` 挂回原书、原句。
 * 没有这些回链的，按概念名挂到概念上；对不上的单独列出。
 * 只有一边改过才写入那一边。两边都改了就留着两份并记一笔冲突，不覆盖。
 * 库里删了文件只做标记，不删批注。
 * 概念名直接读表，不改 [ConceptStore]：那份文件由先合的卡负责。
 */
internal data class ObsidianConflict(
    val title: String,
    val coachText: String,
    val obsidianText: String,
)

internal data class ObsidianLooseNote(val title: String, val body: String)

internal data class ObsidianSyncView(
    val conflicts: List<ObsidianConflict> = emptyList(),
    val unmatched: List<ObsidianLooseNote> = emptyList(),
    val conceptOnly: List<ObsidianLooseNote> = emptyList(),
    val deletedKeys: Set<String> = emptySet(),
    val deletedTitles: List<String> = emptyList(),
)

internal data class ObsidianSyncReport(
    val attached: Int,
    val concepts: Int,
    val unmatched: List<String>,
    val writtenBack: Int,
    val pulled: Int,
    val conflicts: List<ObsidianConflict>,
    val removed: Int,
)

internal sealed interface ObsidianSyncOutcome {
    data class Done(val report: ObsidianSyncReport) : ObsidianSyncOutcome
    data class Refused(val message: String) : ObsidianSyncOutcome
}

internal fun obsidianSyncStatus(report: ObsidianSyncReport): String {
    val parts = ArrayList<String>()
    parts += "已挂回 ${report.attached} 条"
    if (report.pulled > 0) parts += "从 Obsidian 改入 ${report.pulled} 条"
    if (report.writtenBack > 0) parts += "写回 ${report.writtenBack} 条"
    if (report.concepts > 0) parts += "${report.concepts} 篇挂到概念上"
    if (report.unmatched.isNotEmpty()) parts += "${report.unmatched.size} 篇没有挂上"
    if (report.removed > 0) parts += "${report.removed} 篇已在 Obsidian 删除"
    if (report.conflicts.isNotEmpty()) parts += "${report.conflicts.size} 条两边都改了，两份都留着"
    return parts.joinToString("。")
}

/** 导出刚写完时记下每条批注的原文。之后同步才分得清是哪一边改的，也才认得被删掉的文件。 */
internal fun rememberObsidianExportBaseline(directory: Path, db: Connection) {
    if (!Files.isDirectory(directory) || directoryInsideGit(directory)) return
    ensureObsidianSyncTable(db)
    val vault = vaultKey(directory)
    val notes = loadLiveNotes(db)
    val books = listLibraryBooks(db).associateBy { it.id }
    for (file in markdownFiles(directory)) {
        val text = readNote(file) ?: continue
        val rel = relative(directory, file)
        val bookId = bookIdFromCoachId(frontmatterValueOf(text, "coach_record_id"))
        val title = books[bookId]?.title ?: frontmatterValueOf(text, "title") ?: file.fileName.toString().removeSuffix(".md")
        upsertLink(db, vault, rel, "", "file", title = title, deleted = false)
        if (bookId == null) continue
        for (entry in managedAnnotationLines(text)) {
            val id = annotationId(entry.key) ?: continue
            val coach = notes[noteKey(bookId, id)]?.let { notesOneLine(it.note) } ?: continue
            val obs = notesOneLine(entry.text)
            if (coach != obs) continue
            upsertLink(
                db, vault, rel, entry.key, "annotation",
                baseline = coach, hasBaseline = true, conflict = false, deleted = false,
                bookId = bookId, title = title, body = coach,
            )
        }
    }
}

internal fun syncObsidianVault(directory: Path, db: Connection): ObsidianSyncOutcome {
    if (!Files.isDirectory(directory)) return ObsidianSyncOutcome.Refused("这个文件夹不存在")
    if (directoryInsideGit(directory)) return ObsidianSyncOutcome.Refused("请选 Git 仓库以外的文件夹")
    ensureObsidianSyncTable(db)
    val vault = vaultKey(directory)
    val notes = loadLiveNotes(db).toMutableMap()
    val concepts = loadConcepts(db)
    val mentions = loadMentions(db)
    val pages = loadPageTexts(db)
    val books = listLibraryBooks(db).associateBy { it.id }
    val existing = loadLinks(db, vault)
    val files = markdownFiles(directory)
    val seen = HashSet<String>()
    var attached = 0
    var conceptsAttached = 0
    val conflicts = ArrayList<ObsidianConflict>()
    val pulls = ArrayList<NoteWrite>()
    val inserts = ArrayList<NoteInsert>()
    val repairs = ArrayList<AnchorRepair>()
    val retires = ArrayList<NoteRetire>()
    val fileWrites = ArrayList<AnnotationWrite>()
    val kept = LinkedHashMap<LinkId, LinkDraft>()

    for (file in files) {
        val text = readNote(file) ?: continue
        val rel = relative(directory, file)
        seen += rel
        val coachId = frontmatterValueOf(text, "coach_record_id")
        val bookId = bookIdFromCoachId(coachId)
        val conceptFromFile = conceptIdFromCoachId(coachId)
        val title = when {
            bookId != null -> books[bookId]?.title ?: fileStem(file)
            conceptFromFile != null -> concepts.firstOrNull { it.id == conceptFromFile }?.name ?: fileStem(file)
            else -> frontmatterValueOf(text, "title") ?: firstHeading(text) ?: fileStem(file)
        }
        kept[LinkId(rel, "")] = LinkDraft("file", title = title, bookId = bookId.orEmpty(), conceptId = conceptFromFile.orEmpty())
        if (bookId != null) {
            for (entry in managedAnnotationLines(text)) {
                val id = annotationId(entry.key) ?: continue
                val row = notes[noteKey(bookId, id)] ?: continue
                val coach = notesOneLine(row.note)
                val obs = notesOneLine(entry.text)
                attached += 1
                val link = existing[LinkId(rel, entry.key)]
                val draft = reconcile(
                    coach = coach,
                    obsidian = obs,
                    link = link,
                    title = title,
                    onWriteBack = { fileWrites += AnnotationWrite(file, entry.key, coach) },
                    onPull = { pulls += NoteWrite(bookId, id, obs) },
                    onConflict = { conflicts += it },
                )
                kept[LinkId(rel, entry.key)] = draft.copy(
                    bookId = bookId,
                    title = title,
                    writeToken = if (draft.writeBack) writeToken(file, entry.key) else "",
                )
            }
            val outside = notesOneLine(userRegion(text))
            if (outside.isNotEmpty()) {
                val id = "ou" + pathHash(rel)
                val key = libraryRecordIds.entry(LibraryKind.ANNOTATION, id)
                val anchor = anchorFor(bookId, outside, notes.values, pages)
                if (anchor == null) {
                    dropStaleImport(notes, retires, pages, bookId, id)
                    kept[LinkId(rel, "unmatched")] = LinkDraft("unmatched", title = title, body = outside)
                } else {
                    absorbImported(
                        rel = rel,
                        key = key,
                        noteId = id,
                        bookId = bookId,
                        conceptId = "",
                        title = title,
                        text = outside,
                        anchor = anchor,
                        pages = pages,
                        notes = notes,
                        existing = existing,
                        kept = kept,
                        inserts = inserts,
                        repairs = repairs,
                        pulls = pulls,
                        fileWrites = fileWrites,
                        conflicts = conflicts,
                        file = file,
                        allowWriteBack = false,
                        countConcept = false,
                        onConcept = { },
                    )
                }
            }
        } else {
            val raw = if (coachId != null) userRegion(text) else bodyWithoutFrontmatter(text)
            val flat = notesOneLine(stripHeading(raw))
            val matched = if (conceptFromFile != null) {
                concepts.firstOrNull { it.id == conceptFromFile }
            } else {
                matchConcept(fileStem(file), frontmatterValueOf(text, "title"), firstHeading(text), raw, concepts)
            }
            if (matched == null) {
                if (coachId == null) {
                    kept[LinkId(rel, "unmatched")] = LinkDraft(
                        "unmatched", title = title, body = flat.ifBlank { title },
                    )
                }
            } else if (flat.isNotEmpty() || coachId == null) {
                val mention = pickMention(matched.id, raw, mentions)
                val noteText = flat.ifBlank { matched.name }
                if (mention == null || mention.bookId !in books) {
                    kept[LinkId(rel, "concept")] = LinkDraft(
                        "concept", title = matched.name, conceptId = matched.id, body = noteText,
                    )
                    if (existing[LinkId(rel, "concept")] == null) conceptsAttached += 1
                } else {
                    val id = "on" + pathHash(rel)
                    val key = libraryRecordIds.entry(LibraryKind.ANNOTATION, id)
                    val anchor = confirmOnPage(
                        mention.bookId,
                        mention.page,
                        mention.offset,
                        mention.offset + mention.quote.length,
                        mention.quote,
                        pages,
                    )
                    if (anchor == null) {
                        dropStaleImport(notes, retires, pages, mention.bookId, id)
                        kept[LinkId(rel, "unmatched")] = LinkDraft("unmatched", title = title, body = noteText)
                    } else {
                        absorbImported(
                            rel = rel,
                            key = key,
                            noteId = id,
                            bookId = mention.bookId,
                            conceptId = matched.id,
                            title = matched.name,
                            text = noteText,
                            anchor = anchor,
                            pages = pages,
                            notes = notes,
                            existing = existing,
                            kept = kept,
                            inserts = inserts,
                            repairs = repairs,
                            pulls = pulls,
                            fileWrites = fileWrites,
                            conflicts = conflicts,
                            file = file,
                            allowWriteBack = true,
                            countConcept = true,
                            onConcept = { conceptsAttached += 1 },
                        )
                    }
                }
            }
        }
    }

    val succeeded = HashSet<String>()
    for (write in fileWrites) {
        if (writeAnnotationLine(write.path, write.key, write.text)) succeeded += writeToken(write.path, write.key)
    }
    val removed = markMissing(existing, seen, kept)
    val auto = db.autoCommit
    if (auto) db.autoCommit = false
    try {
        for (insert in inserts) insertNote(db, insert)
        for (repair in repairs) repairAnchor(db, repair)
        for (retire in retires) retireNote(db, retire)
        for (pull in pulls) updateNote(db, pull)
        for ((id, draft) in kept) {
            if (draft.writeBack && draft.writeToken !in succeeded) continue
            upsertLink(
                db, vault, id.rel, id.key, draft.role,
                baseline = draft.baseline,
                hasBaseline = draft.hasBaseline,
                conflict = draft.conflict,
                coachConflict = draft.coachConflict,
                obsidianConflict = draft.obsidianConflict,
                deleted = false,
                bookId = draft.bookId,
                conceptId = draft.conceptId,
                title = draft.title,
                body = draft.body,
            )
        }
        for (id in existing.keys) {
            if (id.rel in seen && id !in kept) {
                db.prepareStatement(
                    "DELETE FROM obsidian_sync WHERE vault=? AND rel_path=? AND entry_key=?",
                ).use { sql ->
                    sql.setString(1, vault)
                    sql.setString(2, id.rel)
                    sql.setString(3, id.key)
                    sql.executeUpdate()
                }
            }
        }
        for (id in removed) {
            db.prepareStatement(
                "UPDATE obsidian_sync SET deleted=1, conflict=0 WHERE vault=? AND rel_path=? AND entry_key=?",
            ).use { sql ->
                sql.setString(1, vault)
                sql.setString(2, id.rel)
                sql.setString(3, id.key)
                sql.executeUpdate()
            }
        }
        if (auto) db.commit()
    } catch (error: Exception) {
        if (auto) db.rollback()
        throw error
    } finally {
        if (auto) db.autoCommit = true
    }
    syncLibraryCorpus(db)
    val unmatched = kept.values.filter { it.role == "unmatched" }.map { it.title }
    return ObsidianSyncOutcome.Done(
        ObsidianSyncReport(
            attached = attached,
            concepts = conceptsAttached,
            unmatched = unmatched,
            writtenBack = fileWrites.count { writeToken(it.path, it.key) in succeeded },
            pulled = pulls.size,
            conflicts = conflicts,
            removed = removed.map { it.rel }.distinct().size,
        ),
    )
}

internal fun loadObsidianSyncView(db: Connection): ObsidianSyncView {
    if (!BookSchemaMigration.tableExists(db, "obsidian_sync")) return ObsidianSyncView()
    val conflicts = ArrayList<ObsidianConflict>()
    val unmatched = ArrayList<ObsidianLooseNote>()
    val conceptOnly = ArrayList<ObsidianLooseNote>()
    val deletedKeys = HashSet<String>()
    val deletedTitles = ArrayList<String>()
    db.prepareStatement(
        "SELECT entry_key, role, conflict, conflict_coach, conflict_obsidian, deleted, title, body FROM obsidian_sync",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val key = rows.getString(1).orEmpty()
                val role = rows.getString(2).orEmpty()
                val conflict = rows.getInt(3) != 0
                val deleted = rows.getInt(6) != 0
                val title = rows.getString(7).orEmpty()
                val body = rows.getString(8).orEmpty()
                if (deleted && key.isNotEmpty()) deletedKeys += key
                if (deleted && role == "file" && title.isNotBlank()) deletedTitles += title
                if (!deleted && conflict) {
                    conflicts += ObsidianConflict(title.ifBlank { "这条批注" }, rows.getString(4).orEmpty(), rows.getString(5).orEmpty())
                }
                if (!deleted && role == "unmatched") unmatched += ObsidianLooseNote(title.ifBlank { "未命名笔记" }, body)
                if (!deleted && role == "concept") conceptOnly += ObsidianLooseNote(title.ifBlank { "这个概念" }, body)
            }
        }
    }
    return ObsidianSyncView(conflicts, unmatched, conceptOnly, deletedKeys, deletedTitles.distinct())
}

private data class LiveNote(
    val bookId: String,
    val id: String,
    val note: String,
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val version: String,
    val textSha256: String,
)

private data class ConceptRef(val id: String, val name: String, val aliases: List<String>)

private data class MentionRef(val conceptId: String, val bookId: String, val page: Int, val offset: Int, val quote: String, val mentionId: String)

private data class LinkId(val rel: String, val key: String)

private data class LinkRow(val hasBaseline: Boolean, val baseline: String, val bookId: String)

private data class ManagedLine(val key: String, val text: String)

private data class NoteWrite(val bookId: String, val id: String, val note: String)

private data class NoteInsert(
    val bookId: String,
    val id: String,
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    val pageSha: String,
)

private data class AnchorRepair(
    val bookId: String,
    val id: String,
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val pageSha: String,
)

private data class NoteRetire(val bookId: String, val id: String)

private data class AnnotationWrite(val path: Path, val key: String, val text: String)

private data class LinkDraft(
    val role: String,
    val baseline: String = "",
    val hasBaseline: Boolean = false,
    val conflict: Boolean = false,
    val coachConflict: String = "",
    val obsidianConflict: String = "",
    val bookId: String = "",
    val conceptId: String = "",
    val title: String = "",
    val body: String = "",
    val writeBack: Boolean = false,
    val writeToken: String = "",
    val countConcept: Boolean = false,
)

private data class Anchor(
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val pageSha: String,
)

private fun absorbImported(
    rel: String,
    key: String,
    noteId: String,
    bookId: String,
    conceptId: String,
    title: String,
    text: String,
    anchor: Anchor,
    pages: Map<Pair<String, Int>, String>,
    notes: MutableMap<String, LiveNote>,
    existing: Map<LinkId, LinkRow>,
    kept: MutableMap<LinkId, LinkDraft>,
    inserts: MutableList<NoteInsert>,
    repairs: MutableList<AnchorRepair>,
    pulls: MutableList<NoteWrite>,
    fileWrites: MutableList<AnnotationWrite>,
    conflicts: MutableList<ObsidianConflict>,
    file: Path,
    allowWriteBack: Boolean,
    countConcept: Boolean,
    onConcept: () -> Unit,
) {
    val page = anchor.page
    val start = anchor.start
    val end = anchor.end
    val quote = anchor.quote
    val id = LinkId(rel, key)
    val link = existing[id]
    val current = notes[noteKey(bookId, noteId)]
    if (current == null && link == null) {
        inserts += NoteInsert(bookId, noteId, page, start, end, quote, text, anchor.pageSha)
        notes[noteKey(bookId, noteId)] = LiveNote(bookId, noteId, text, page, start, end, quote, "1", anchor.pageSha)
        kept[id] = LinkDraft(
            "imported", baseline = text, hasBaseline = true, bookId = bookId, conceptId = conceptId,
            title = title, body = text, countConcept = countConcept,
        )
        if (countConcept) onConcept()
        return
    }
    if (current != null && readerWouldMarkStale(current, pages)) {
        repairs += AnchorRepair(bookId, noteId, page, start, end, quote, anchor.pageSha)
        notes[noteKey(bookId, noteId)] = current.copy(
            page = page,
            start = start,
            end = end,
            quote = quote,
            version = "1",
            textSha256 = anchor.pageSha,
        )
    }
    val coach = notesOneLine(current?.note ?: link?.baseline.orEmpty())
    val token = writeToken(file, "")
    val draft = reconcile(
        coach = coach,
        obsidian = text,
        link = link,
        title = title,
        allowWriteBack = allowWriteBack,
        onWriteBack = { fileWrites += AnnotationWrite(file, "", coach) },
        onPull = { pulls += NoteWrite(bookId, noteId, text) },
        onConflict = { conflicts += it },
    )
    kept[id] = draft.copy(
        bookId = bookId,
        conceptId = conceptId,
        title = title,
        writeToken = if (draft.writeBack) token else "",
    )
}

private fun reconcile(
    coach: String,
    obsidian: String,
    link: LinkRow?,
    title: String,
    allowWriteBack: Boolean = true,
    onWriteBack: () -> Unit,
    onPull: () -> Unit,
    onConflict: (ObsidianConflict) -> Unit,
): LinkDraft {
    if (coach == obsidian) {
        return LinkDraft("annotation", baseline = coach, hasBaseline = true, title = title, body = coach)
    }
    val baseline = link?.takeIf { it.hasBaseline }?.baseline
    if (baseline != null && obsidian == baseline && coach != baseline) {
        if (!allowWriteBack) {
            onConflict(ObsidianConflict(title.ifBlank { "这条批注" }, coach, obsidian))
            return LinkDraft(
                "annotation",
                baseline = baseline,
                hasBaseline = true,
                conflict = true,
                coachConflict = coach,
                obsidianConflict = obsidian,
                title = title,
                body = obsidian,
            )
        }
        onWriteBack()
        return LinkDraft("annotation", baseline = coach, hasBaseline = true, title = title, body = coach, writeBack = true)
    }
    if (baseline != null && coach == baseline && obsidian != baseline) {
        onPull()
        return LinkDraft("annotation", baseline = obsidian, hasBaseline = true, title = title, body = obsidian)
    }
    onConflict(ObsidianConflict(title.ifBlank { "这条批注" }, coach, obsidian))
    return LinkDraft(
        "annotation",
        baseline = baseline.orEmpty(),
        hasBaseline = baseline != null,
        conflict = true,
        coachConflict = coach,
        obsidianConflict = obsidian,
        title = title,
        body = obsidian,
    )
}

private fun markMissing(existing: Map<LinkId, LinkRow>, seen: Set<String>, kept: Map<LinkId, LinkDraft>): List<LinkId> {
    return existing.keys.filter { it.rel !in seen && LinkId(it.rel, it.key) !in kept }.distinct()
}

private fun anchorFor(
    bookId: String,
    text: String,
    notes: Collection<LiveNote>,
    pages: Map<Pair<String, Int>, String>,
): Anchor? {
    val quoted = notes.filter { it.bookId == bookId && it.quote.isNotBlank() && text.contains(it.quote) }
    for (pick in quoted) {
        val confirmed = confirmOnPage(bookId, pick.page, pick.start, pick.end, pick.quote, pages)
        if (confirmed != null) return confirmed
    }
    return null
}

// 阅读器认 version "1"、整页文字的哈希，以及页内截出的文字等于引文。对不上就不写位置。
private fun confirmOnPage(
    bookId: String,
    page: Int,
    start: Int,
    end: Int,
    quote: String,
    pages: Map<Pair<String, Int>, String>,
): Anchor? {
    if (quote.isEmpty() || start !in 0 until end) return null
    val text = pages[bookId to page] ?: return null
    if (end > text.length || text.substring(start, end) != quote) return null
    return Anchor(page, start, end, quote, sha256(text))
}

private fun readerWouldMarkStale(note: LiveNote, pages: Map<Pair<String, Int>, String>): Boolean {
    val text = pages[note.bookId to note.page] ?: return true
    if (note.version != "1" || note.textSha256 != sha256(text)) return true
    if (note.start !in 0 until note.end || note.end > text.length) return true
    return text.substring(note.start, note.end) != note.quote
}

private fun dropStaleImport(
    notes: MutableMap<String, LiveNote>,
    retires: MutableList<NoteRetire>,
    pages: Map<Pair<String, Int>, String>,
    bookId: String,
    noteId: String,
) {
    val note = notes[noteKey(bookId, noteId)] ?: return
    if (!readerWouldMarkStale(note, pages)) return
    retires += NoteRetire(bookId, noteId)
    notes.remove(noteKey(bookId, noteId))
}

private fun matchConcept(
    stem: String,
    frontTitle: String?,
    heading: String?,
    body: String,
    concepts: List<ConceptRef>,
): ConceptRef? {
    val named = concepts.filter { it.name.length >= 2 }
    fun exact(raw: String?): List<ConceptRef> {
        val text = normalizeName(raw ?: return emptyList())
        if (text.isEmpty()) return emptyList()
        return named.filter { normalizeName(it.name) == text || it.aliases.any { alias -> normalizeName(alias) == text } }
    }
    for (candidate in listOf(stem, frontTitle, heading)) {
        val hits = exact(candidate)
        if (hits.size == 1) return hits.single()
        if (hits.size > 1) return null
    }
    val hits = named.filter { concept ->
        body.contains(concept.name, ignoreCase = true) ||
            concept.aliases.any { it.length >= 2 && body.contains(it, ignoreCase = true) }
    }
    if (hits.isEmpty()) return null
    val longest = hits.maxOf { it.name.length }
    val top = hits.filter { it.name.length == longest }
    return if (top.size == 1) top.single() else null
}

private fun pickMention(conceptId: String, body: String, mentions: List<MentionRef>): MentionRef? {
    val mine = mentions.filter { it.conceptId == conceptId }
    val quoted = mine.filter { it.quote.isNotBlank() && body.contains(it.quote) }
    val pool = if (quoted.isNotEmpty()) quoted else mine
    return pool.minWithOrNull(compareBy({ it.page }, { it.mentionId }))
}

private fun normalizeName(raw: String): String = raw.trim().lowercase().replace(Regex("\\s+"), " ")

private fun loadLiveNotes(db: Connection): Map<String, LiveNote> {
    if (!BookSchemaMigration.tableExists(db, "book_notes")) return emptyMap()
    val out = HashMap<String, LiveNote>()
    db.prepareStatement(
        "SELECT book, id, note, page, start, end, quote, version, text_sha256 FROM book_notes WHERE deleted=0",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val note = LiveNote(
                    bookId = rows.getString(1),
                    id = rows.getString(2),
                    note = rows.getString(3).orEmpty(),
                    page = rows.getInt(4),
                    start = rows.getInt(5),
                    end = rows.getInt(6),
                    quote = rows.getString(7).orEmpty(),
                    version = rows.getString(8).orEmpty(),
                    textSha256 = rows.getString(9).orEmpty(),
                )
                out[noteKey(note.bookId, note.id)] = note
            }
        }
    }
    return out
}

private fun loadPageTexts(db: Connection): Map<Pair<String, Int>, String> {
    if (!BookSchemaMigration.tableExists(db, "pages")) return emptyMap()
    val out = HashMap<Pair<String, Int>, String>()
    db.prepareStatement("SELECT book, page, text FROM pages").use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                out[rows.getString(1) to rows.getInt(2)] = rows.getString(3).orEmpty()
            }
        }
    }
    return out
}

private fun loadConcepts(db: Connection): List<ConceptRef> {
    if (!BookSchemaMigration.tableExists(db, "concept_entities")) return emptyList()
    return db.prepareStatement(
        "SELECT concept_id, canonical_name, aliases_json FROM concept_entities WHERE muted=0",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val name = rows.getString(2).orEmpty().trim()
                    if (name.isEmpty()) continue
                    add(ConceptRef(rows.getString(1), name, decodeAliasList(rows.getString(3))))
                }
            }
        }
    }
}

private fun loadMentions(db: Connection): List<MentionRef> {
    if (!BookSchemaMigration.tableExists(db, "concept_mentions")) return emptyList()
    return db.prepareStatement(
        "SELECT concept_id, book_id, page, offset_utf16, quote_anchor, mention_id FROM concept_mentions",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        MentionRef(
                            conceptId = rows.getString(1),
                            bookId = rows.getString(2),
                            page = rows.getInt(3),
                            offset = rows.getInt(4),
                            quote = rows.getString(5).orEmpty(),
                            mentionId = rows.getString(6),
                        ),
                    )
                }
            }
        }
    }
}

private fun loadLinks(db: Connection, vault: String): Map<LinkId, LinkRow> {
    val out = HashMap<LinkId, LinkRow>()
    db.prepareStatement(
        "SELECT rel_path, entry_key, has_baseline, baseline_text, book_id FROM obsidian_sync WHERE vault=?",
    ).use { sql ->
        sql.setString(1, vault)
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                out[LinkId(rows.getString(1), rows.getString(2).orEmpty())] = LinkRow(
                    hasBaseline = rows.getInt(3) != 0,
                    baseline = rows.getString(4).orEmpty(),
                    bookId = rows.getString(5).orEmpty(),
                )
            }
        }
    }
    return out
}

private fun decodeAliasList(raw: String?): List<String> {
    if (raw.isNullOrBlank() || raw == "[]") return emptyList()
    return Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(raw).map { match ->
        match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }.map { it.trim() }.filter { it.isNotEmpty() }.toList()
}

private fun ensureObsidianSyncTable(db: Connection) {
    db.createStatement().use {
        it.execute(
            """CREATE TABLE IF NOT EXISTS obsidian_sync(
                vault TEXT NOT NULL,
                rel_path TEXT NOT NULL,
                entry_key TEXT NOT NULL,
                role TEXT NOT NULL,
                baseline_text TEXT NOT NULL DEFAULT '',
                has_baseline INTEGER NOT NULL DEFAULT 0,
                conflict INTEGER NOT NULL DEFAULT 0,
                conflict_coach TEXT NOT NULL DEFAULT '',
                conflict_obsidian TEXT NOT NULL DEFAULT '',
                deleted INTEGER NOT NULL DEFAULT 0,
                book_id TEXT NOT NULL DEFAULT '',
                concept_id TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (vault, rel_path, entry_key)
            )""",
        )
    }
}

private fun upsertLink(
    db: Connection,
    vault: String,
    rel: String,
    key: String,
    role: String,
    baseline: String = "",
    hasBaseline: Boolean = false,
    conflict: Boolean = false,
    coachConflict: String = "",
    obsidianConflict: String = "",
    deleted: Boolean = false,
    bookId: String = "",
    conceptId: String = "",
    title: String = "",
    body: String = "",
) {
    db.prepareStatement(
        """INSERT INTO obsidian_sync(
            vault, rel_path, entry_key, role, baseline_text, has_baseline, conflict,
            conflict_coach, conflict_obsidian, deleted, book_id, concept_id, title, body
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(vault, rel_path, entry_key) DO UPDATE SET
            role=excluded.role,
            baseline_text=excluded.baseline_text,
            has_baseline=excluded.has_baseline,
            conflict=excluded.conflict,
            conflict_coach=excluded.conflict_coach,
            conflict_obsidian=excluded.conflict_obsidian,
            deleted=excluded.deleted,
            book_id=excluded.book_id,
            concept_id=excluded.concept_id,
            title=excluded.title,
            body=excluded.body""",
    ).use { sql ->
        sql.setString(1, vault)
        sql.setString(2, rel)
        sql.setString(3, key)
        sql.setString(4, role)
        sql.setString(5, baseline)
        sql.setInt(6, if (hasBaseline) 1 else 0)
        sql.setInt(7, if (conflict) 1 else 0)
        sql.setString(8, coachConflict)
        sql.setString(9, obsidianConflict)
        sql.setInt(10, if (deleted) 1 else 0)
        sql.setString(11, bookId)
        sql.setString(12, conceptId)
        sql.setString(13, title)
        sql.setString(14, body)
        sql.executeUpdate()
    }
}

private fun insertNote(db: Connection, insert: NoteInsert) {
    db.prepareStatement(
        """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,0)""",
    ).use { sql ->
        sql.setString(1, insert.bookId)
        sql.setString(2, insert.id)
        sql.setString(3, "1")
        sql.setInt(4, insert.page)
        sql.setInt(5, insert.start)
        sql.setInt(6, insert.end)
        sql.setString(7, insert.quote)
        sql.setString(8, insert.note)
        sql.setString(9, insert.pageSha)
        sql.setLong(10, 1)
        sql.setString(11, "obsidian-sync")
        sql.executeUpdate()
    }
}

private fun repairAnchor(db: Connection, repair: AnchorRepair) {
    db.prepareStatement(
        """UPDATE book_notes SET version=?, page=?, start=?, "end"=?, quote=?, text_sha256=?,
           revision=revision+1, last_mutation_id=?
           WHERE book=? AND id=? AND deleted=0""",
    ).use { sql ->
        sql.setString(1, "1")
        sql.setInt(2, repair.page)
        sql.setInt(3, repair.start)
        sql.setInt(4, repair.end)
        sql.setString(5, repair.quote)
        sql.setString(6, repair.pageSha)
        sql.setString(7, "obsidian-sync")
        sql.setString(8, repair.bookId)
        sql.setString(9, repair.id)
        sql.executeUpdate()
    }
}

private fun retireNote(db: Connection, retire: NoteRetire) {
    db.prepareStatement(
        """UPDATE book_notes SET deleted=1, revision=revision+1, last_mutation_id=?
           WHERE book=? AND id=? AND deleted=0""",
    ).use { sql ->
        sql.setString(1, "obsidian-sync")
        sql.setString(2, retire.bookId)
        sql.setString(3, retire.id)
        sql.executeUpdate()
    }
}

private fun updateNote(db: Connection, write: NoteWrite) {
    db.prepareStatement(
        """UPDATE book_notes SET note=?, revision=revision+1, last_mutation_id=?
           WHERE book=? AND id=? AND deleted=0""",
    ).use { sql ->
        sql.setString(1, write.note)
        sql.setString(2, "obsidian-sync")
        sql.setString(3, write.bookId)
        sql.setString(4, write.id)
        sql.executeUpdate()
    }
}

private fun writeAnnotationLine(path: Path, key: String, text: String): Boolean {
    val original = readNote(path) ?: return false
    val updated = if (key.isEmpty()) replaceWholeBody(original, text) else replaceManagedNote(original, key, text)
    if (updated == null || updated == original) return updated != null
    writeAtomically(path, restoreNewlines(original, updated))
    return true
}

private fun replaceWholeBody(original: String, text: String): String? {
    val normalized = original.removePrefix("\uFEFF").replace("\r\n", "\n")
    val matter = if (normalized.startsWith("---\n")) {
        val end = normalized.indexOf("\n---\n", 4)
        if (end < 0) "" else normalized.substring(0, end + 5)
    } else ""
    val body = if (matter.isEmpty()) text else matter + "\n" + text + "\n"
    return if (original.startsWith("\uFEFF")) "\uFEFF$body" else body
}

private fun replaceManagedNote(original: String, key: String, text: String): String? {
    val bom = original.startsWith("\uFEFF")
    val normalized = original.removePrefix("\uFEFF").replace("\r\n", "\n")
    val managedStart = normalized.indexOf(OBSIDIAN_MANAGED_BEGIN)
    val managedEnd = if (managedStart < 0) -1 else normalized.indexOf(OBSIDIAN_MANAGED_END, managedStart)
    if (managedStart < 0 || managedEnd < 0) return null
    val regionStart = managedStart + OBSIDIAN_MANAGED_BEGIN.length
    val managed = normalized.substring(regionStart, managedEnd)
    val marker = "<!-- $key -->"
    val at = managed.indexOf(marker)
    if (at < 0) return null
    val previous = managed.lastIndexOf("-->", at - 1)
    val blockStart = if (previous < 0) 0 else previous + 3
    val block = managed.substring(blockStart, at)
    val line = block.lineSequence().firstOrNull { it.trimStart().startsWith("- 我写的：") } ?: return null
    val replacedBlock = block.replaceFirst(line, "- 我写的：$text")
    val updatedManaged = managed.substring(0, blockStart) + replacedBlock + managed.substring(at)
    val updated = normalized.substring(0, regionStart) + updatedManaged + normalized.substring(managedEnd)
    return if (bom) "\uFEFF$updated" else updated
}

private fun restoreNewlines(original: String, updated: String): String {
    if ("\r\n" !in original) return updated
    return updated.replace("\r\n", "\n").replace("\n", "\r\n")
}

private fun writeAtomically(path: Path, text: String) {
    val tmp = path.resolveSibling(path.fileName.toString() + ".coach-tmp")
    Files.writeString(tmp, text)
    try {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun markdownFiles(directory: Path): List<Path> {
    return Files.walk(directory).use { stream ->
        stream.filter { path ->
            Files.isRegularFile(path) &&
                path.fileName.toString().endsWith(".md", ignoreCase = true) &&
                directory.relativize(path).none { part -> part.toString() in SKIPPED_DIRS }
        }.toList()
    }
}

private val SKIPPED_DIRS = setOf(".obsidian", ".git", ".trash")

private fun readNote(path: Path): String? {
    val size = runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE)
    if (size > 512_000) return null
    return runCatching { Files.readString(path) }.getOrNull()
}

private fun relative(directory: Path, path: Path): String =
    directory.relativize(path).joinToString("/") { it.toString() }

private fun fileStem(path: Path): String = path.fileName.toString().removeSuffix(".md").removeSuffix(".MD")

private fun vaultKey(directory: Path): String = directory.toAbsolutePath().normalize().toString()

private fun writeToken(path: Path, key: String): String = "$path|$key"

private fun noteKey(bookId: String, id: String): String = "$bookId\u0000$id"

private fun annotationId(key: String): String? {
    val prefix = libraryRecordIds.entry(LibraryKind.ANNOTATION, "")
    if (!key.startsWith(prefix)) return null
    return key.removePrefix(prefix).ifBlank { null }
}

private fun bookIdFromCoachId(recordId: String?): String? {
    if (recordId == null) return null
    val prefix = libraryRecordIds.book("")
    if (!recordId.startsWith(prefix)) return null
    return recordId.removePrefix(prefix).trim().ifBlank { null }
}

private fun conceptIdFromCoachId(recordId: String?): String? {
    if (recordId == null) return null
    val prefix = "${libraryRecordIds.name}:concept:"
    if (!recordId.startsWith(prefix)) return null
    return recordId.removePrefix(prefix).trim().ifBlank { null }
}

private fun pathHash(rel: String): String = sha256(rel).take(12)

private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun managedAnnotationLines(text: String): List<ManagedLine> {
    val managed = managedRegion(text) ?: return emptyList()
    val out = ArrayList<ManagedLine>()
    var previous = 0
    for (match in COMMENT.findAll(managed)) {
        val block = managed.substring(previous, match.range.first)
        val line = block.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("- 我写的：") }
        if (line != null) out += ManagedLine(match.groupValues[1].trim(), line.removePrefix("- 我写的：").trim())
        previous = match.range.last + 1
    }
    return out
}

private val COMMENT = Regex("<!--\\s*([^>]+?)\\s*-->")

private fun managedRegion(text: String): String? {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    val begin = normalized.indexOf(OBSIDIAN_MANAGED_BEGIN)
    if (begin < 0) return null
    val start = begin + OBSIDIAN_MANAGED_BEGIN.length
    val end = normalized.indexOf(OBSIDIAN_MANAGED_END, start)
    if (end < 0) return null
    return normalized.substring(start, end)
}

private fun userRegion(text: String): String {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    val body = bodyWithoutFrontmatter(normalized)
    val begin = body.indexOf(OBSIDIAN_MANAGED_BEGIN)
    val end = if (begin < 0) -1 else body.indexOf(OBSIDIAN_MANAGED_END, begin + OBSIDIAN_MANAGED_BEGIN.length)
    if (begin < 0 || end < 0) return ""
    val before = body.substring(0, begin).trim()
    val after = body.substring(end + OBSIDIAN_MANAGED_END.length).trim()
    return listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n")
}

private fun bodyWithoutFrontmatter(text: String): String {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return normalized
    val end = normalized.indexOf("\n---\n", 4)
    if (end < 0) return normalized
    return normalized.substring(end + 5)
}

private fun stripHeading(raw: String): String =
    raw.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

private fun firstHeading(text: String): String? {
    val line = bodyWithoutFrontmatter(text).lineSequence().firstOrNull { it.trimStart().startsWith("# ") } ?: return null
    return line.trim().removePrefix("#").trim().ifBlank { null }
}

private fun frontmatterValueOf(text: String, key: String): String? {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return null
    val end = normalized.indexOf("\n---\n", 4)
    if (end < 0) return null
    val prefix = "$key:"
    val line = normalized.substring(4, end).lineSequence().firstOrNull { it.trimStart().startsWith(prefix) } ?: return null
    return unquote(line.substringAfter(':').trim())
}

private fun unquote(raw: String): String {
    if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        return raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return raw
}
