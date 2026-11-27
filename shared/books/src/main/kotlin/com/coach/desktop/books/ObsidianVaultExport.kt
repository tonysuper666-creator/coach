package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter

internal const val OBSIDIAN_MANAGED_BEGIN = "%% coach:managed begin %%"
internal const val OBSIDIAN_MANAGED_END = "%% coach:managed end %%"

internal sealed interface ObsidianExportOutcome {
    data class Done(val written: Int, val skipped: Int, val items: Int) : ObsidianExportOutcome
    data class Refused(val message: String) : ObsidianExportOutcome
}

/**
 * 每本书一个 Markdown。对比表和概念并排各自另开文件。Coach 只管分隔标记中间的那一段；标记外面是用户自己的，再导一次不动。
 * 导出批注、问答、错题、对比表、综述提纲和概念并排。高亮只留在库里搜，不把纯摘录再写进笔记。
 * 引用只来自已经落盘的选中句，不含页正文、讲解多送的原文、凭据、服务地址或设备 ID。
 */
internal fun exportObsidianVault(
    directory: Path,
    records: List<LibraryRecord>,
    books: List<LibraryBookHead>,
    now: Instant = Instant.now(),
): ObsidianExportOutcome {
    if (!Files.isDirectory(directory)) return ObsidianExportOutcome.Refused("这个文件夹不存在")
    if (directoryInsideGit(directory)) return ObsidianExportOutcome.Refused("请选 Git 仓库以外的文件夹")
    val records = records.map { it.copy(bookTitle = visibleBookTitle(it.bookTitle)) }
    val books = books.map { it.copy(title = visibleBookTitle(it.title)) }
    val ignoreCase = filesystemIgnoresCase(directory)
    val ids = libraryRecordIds
    val exportable = records.filter {
        it.kind == LibraryKind.ANNOTATION || it.kind == LibraryKind.QUESTION || it.kind == LibraryKind.MISTAKE
    }
    val byBook = exportable.groupBy { it.bookId }
    val known = books.associateBy { it.id }
    val existing = existingCoachFiles(directory, ids)
    val targets = LinkedHashMap<String, Path>()
    val usedNames = HashSet<String>()
    Files.newDirectoryStream(directory, "*.md").use { stream ->
        for (path in stream) usedNames += path.fileName.toString()
    }
    for ((bookId, path) in existing) {
        targets[bookId] = path
        usedNames += path.fileName.toString()
    }
    val heads = LinkedHashMap<String, LibraryBookHead>()
    for (bookId in byBook.keys) {
        heads[bookId] = known[bookId] ?: headFromRecords(bookId, byBook.getValue(bookId))
    }
    for (bookId in existing.keys) {
        if (bookId !in heads && bookId in known) heads[bookId] = known.getValue(bookId)
    }
    for (bookId in heads.keys.sorted()) {
        if (bookId in targets) continue
        if (bookId !in byBook) continue
        val stem = freshStem(heads.getValue(bookId).title, bookId, usedNames, ignoreCase)
        val path = directory.resolve("$stem.md")
        targets[bookId] = path
        usedNames += path.fileName.toString()
    }
    val stems = targets.mapValues { it.value.fileName.toString().removeSuffix(".md") }
    var written = 0
    var skipped = 0
    var items = 0
    for ((bookId, head) in heads) {
        val path = targets[bookId] ?: continue
        val rows = byBook[bookId].orEmpty().sortedWith(compareBy({ it.page ?: Int.MAX_VALUE }, { it.key }))
        items += rows.size
        val stem = stems.getValue(bookId)
        val others = stems.filterKeys { it != bookId }.map { (id, otherStem) -> otherStem to (heads[id]?.title ?: known[id]?.title.orEmpty()) }
        val managed = renderManaged(head, stem, rows, others)
        val previous = if (Files.isRegularFile(path)) Files.readString(path) else null
        if (previous != null && managedBody(previous) == managed) {
            skipped += 1
            continue
        }
        val text = stitchObsidian(previous, frontmatter(head, ids, now, previous), managed)
        writeAtomically(path, text)
        written += 1
    }
    val study = writeStudyNotes(directory, records, stems, usedNames, ignoreCase, ids, now)
    return ObsidianExportOutcome.Done(written + study.written, skipped + study.skipped, items + study.items)
}

private data class StudyWrite(val written: Int, val skipped: Int, val items: Int)

private fun writeStudyNotes(
    directory: Path,
    records: List<LibraryRecord>,
    bookStems: Map<String, String>,
    usedNames: MutableSet<String>,
    ignoreCase: Boolean,
    ids: LibraryRecordIdScheme,
    now: Instant,
): StudyWrite {
    val existing = existingRecordFiles(directory, ids)
    var written = 0
    var skipped = 0
    var items = 0
    val comparisons = LinkedHashMap<String, MutableList<LibraryRecord>>()
    val concepts = LinkedHashMap<String, MutableList<LibraryRecord>>()
    for (record in records) {
        val detail = libraryDetailValues(record.detail)
        when (record.kind) {
            LibraryKind.COMPARISON, LibraryKind.OUTLINE -> {
                val digest = detail[LibraryStudyFields.GROUP]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
                comparisons.getOrPut(digest) { ArrayList() }.add(record)
            }
            LibraryKind.SAYING -> {
                val conceptId = detail[LibraryStudyFields.CONCEPT_ID]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
                concepts.getOrPut(conceptId) { ArrayList() }.add(record)
            }
            else -> Unit
        }
    }
    for (digest in comparisons.keys.sorted()) {
        val rows = comparisons.getValue(digest)
        items += rows.size
        val recordId = "${ids.name}:comparison:$digest"
        if (writeManagedNote(directory, existing, usedNames, ignoreCase, recordId, "文献对比", renderComparisonFile(rows, bookStems), ids, now)) {
            written += 1
        } else {
            skipped += 1
        }
    }
    for (conceptId in concepts.keys.sorted()) {
        val rows = concepts.getValue(conceptId)
        items += rows.size
        val name = rows.firstNotNullOfOrNull { libraryDetailValues(it.detail)[LibraryStudyFields.CONCEPT]?.firstOrNull()?.ifBlank { null } }
            ?: "这个概念"
        val recordId = "${ids.name}:concept:$conceptId"
        if (writeManagedNote(directory, existing, usedNames, ignoreCase, recordId, name, renderConceptFile(name, rows, bookStems), ids, now)) {
            written += 1
        } else {
            skipped += 1
        }
    }
    return StudyWrite(written, skipped, items)
}

private fun writeManagedNote(
    directory: Path,
    existing: MutableMap<String, Path>,
    usedNames: MutableSet<String>,
    ignoreCase: Boolean,
    recordId: String,
    title: String,
    managed: String,
    ids: LibraryRecordIdScheme,
    now: Instant,
): Boolean {
    val path = existing[recordId] ?: run {
        val suffix = recordId.substringAfterLast(':').take(8).ifBlank { "2" }
        val stem = freshStem(title, suffix, usedNames, ignoreCase)
        val created = directory.resolve("$stem.md")
        existing[recordId] = created
        usedNames += created.fileName.toString()
        created
    }
    val previous = if (Files.isRegularFile(path)) Files.readString(path) else null
    if (previous != null && managedBody(previous) == managed) return false
    val text = stitchObsidian(previous, studyFrontmatter(title, recordId, ids, now, previous), managed)
    writeAtomically(path, text)
    return true
}

private fun renderComparisonFile(rows: List<LibraryRecord>, stems: Map<String, String>): String = buildString {
    appendLine("这一段由 Coach 维护。写在这一段外面的内容，下次导出还会留着。")
    appendLine()
    appendLine("| 文献 | 主张 | 方法 / 证据 | 关键概念 | 与其他几篇的关系 |")
    appendLine("| --- | --- | --- | --- | --- |")
    val cells = rows.filter { it.kind == LibraryKind.COMPARISON }
    val byRow = cells.groupBy { detailInt(it, LibraryStudyFields.ROW) }.toSortedMap()
    for ((_, rowCells) in byRow) {
        val label = libraryDetailValues(rowCells.first().detail)[LibraryStudyFields.PAPER]?.firstOrNull().orEmpty()
        fun join(slot: String): String {
            val parts = rowCells
                .filter { libraryDetailValues(it.detail)[LibraryStudyFields.SLOT]?.firstOrNull() == slot }
                .sortedBy { it.key }
                .map { mdCell(cellExportText(it)) }
            return parts.joinToString("；").ifBlank { COMPARISON_PENDING }
        }
        appendLine("| ${mdCell(label)} | ${join("主张")} | ${join("方法 / 证据")} | ${join("关键概念")} | ${join("关系")} |")
    }
    appendLine()
    appendLine("## 出处")
    appendLine()
    for (cell in cells.sortedWith(compareBy({ detailInt(it, LibraryStudyFields.ROW) }, { it.key }))) {
        val detail = libraryDetailValues(cell.detail)
        val slot = detail[LibraryStudyFields.SLOT]?.firstOrNull().orEmpty()
        val paper = detail[LibraryStudyFields.PAPER]?.firstOrNull().orEmpty()
        appendLine("- $slot · $paper：${locationPhrase(cell, stems)}")
    }
    appendLine()
    appendLine("# 综述提纲")
    appendLine()
    val outlines = rows.filter { it.kind == LibraryKind.OUTLINE }
    if (outlines.isEmpty()) {
        appendLine("没有可用论点，没有硬补。")
        return@buildString
    }
    val points = outlines.groupBy { detailInt(it, LibraryStudyFields.POINT) }.toSortedMap()
    for ((index, sides) in points) {
        val thesis = sides.firstOrNull { it.primary.isNotBlank() }?.primary.orEmpty()
        appendLine("## ${index + 1}. $thesis")
        appendLine()
        fun sideLines(role: String) {
            val matched = sides.filter { libraryDetailValues(it.detail)[LibraryStudyFields.ROLE]?.firstOrNull() == role }
            if (matched.isEmpty()) {
                appendLine("- $role：没有列出")
                return
            }
            for (side in matched.sortedBy { it.bookTitle }) {
                val paper = libraryDetailValues(side.detail)[LibraryStudyFields.PAPER]?.firstOrNull().orEmpty().ifBlank { side.bookTitle }
                appendLine("- $role：$paper（${locationPhrase(side, stems)}）")
            }
        }
        sideLines("支持")
        sideLines("反对")
        appendLine()
    }
}.trimEnd()

private fun renderConceptFile(name: String, rows: List<LibraryRecord>, stems: Map<String, String>): String = buildString {
    appendLine("这一段由 Coach 维护。写在这一段外面的内容，下次导出还会留着。")
    appendLine()
    appendLine("# $name")
    appendLine()
    for (row in rows.sortedWith(compareBy({ it.bookTitle }, { it.key }))) {
        val detail = libraryDetailValues(row.detail)
        val stance = detail[LibraryStudyFields.STANCE]?.firstOrNull().orEmpty()
        val cite = detail[LibraryStudyFields.CITE]?.firstOrNull().orEmpty().ifBlank { COMPARISON_PENDING }
        val stem = stems[row.bookId]
        val book = if (stem != null) wikiLink(stem, row.bookTitle) else row.bookTitle
        appendLine("## ${row.bookTitle}")
        appendLine()
        appendLine("- 书：$book")
        if (stance.isNotBlank()) appendLine("- 立场：$stance")
        appendLine("- 说法：${row.primary}")
        appendLine("- 位置：$cite")
        if (row.quote.isNotBlank()) appendLine("- 原句：「${row.quote}」")
        appendLine()
    }
}.trimEnd()

private fun cellExportText(record: LibraryRecord): String {
    val pending = libraryDetailValues(record.detail)[LibraryStudyFields.CITE]?.any { COMPARISON_PENDING in it } == true
    val text = record.primary.ifBlank { COMPARISON_PENDING }
    return when {
        !pending -> text
        text == COMPARISON_PENDING -> COMPARISON_PENDING
        else -> "$text（$COMPARISON_PENDING）"
    }
}

private fun locationPhrase(record: LibraryRecord, stems: Map<String, String>): String {
    val cite = libraryDetailValues(record.detail)[LibraryStudyFields.CITE]?.firstOrNull().orEmpty().ifBlank { COMPARISON_PENDING }
    val stem = stems[record.bookId]
    val book = if (stem != null) wikiLink(stem, record.bookTitle) else record.bookTitle
    val quote = if (record.quote.isBlank()) "" else "「${record.quote}」"
    return listOf(book, cite, quote).filter { it.isNotBlank() }.joinToString(" ")
}

private fun detailInt(record: LibraryRecord, key: String): Int =
    libraryDetailValues(record.detail)[key]?.firstOrNull()?.toIntOrNull() ?: 0

private fun mdCell(text: String): String = text.replace("|", "\\|")

private fun studyFrontmatter(
    title: String,
    recordId: String,
    ids: LibraryRecordIdScheme,
    now: Instant,
    previous: String?,
): String = buildString {
    appendLine("---")
    appendLine("title: ${yamlQuote(title)}")
    appendLine("author: ${yamlQuote("")}")
    appendLine("exported_at: ${yamlQuote(DateTimeFormatter.ISO_INSTANT.format(now))}")
    appendLine("coach_record_id: ${yamlQuote(recordId)}")
    appendLine("coach_id_scheme: ${yamlQuote(ids.name)}")
    val extra = userFrontmatter(previous)
    if (extra.isNotEmpty()) appendLine(extra)
    appendLine("---")
}

private fun existingRecordFiles(directory: Path, ids: LibraryRecordIdScheme): MutableMap<String, Path> {
    val out = LinkedHashMap<String, Path>()
    Files.newDirectoryStream(directory, "*.md").use { stream ->
        for (path in stream) {
            val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
            if (frontmatterValue(text, "coach_id_scheme") != ids.name) continue
            val recordId = frontmatterValue(text, "coach_record_id") ?: continue
            out.putIfAbsent(recordId, path)
        }
    }
    return out
}

internal fun directoryInsideGit(path: Path): Boolean =
    generateSequence(path.toAbsolutePath()) { it.parent }.any { Files.exists(it.resolve(".git")) }

/** 这个目录上，大小写不同的文件名会不会指到同一个文件。 */
internal fun filesystemIgnoresCase(directory: Path): Boolean {
    val name = "CoachCase" + java.lang.Long.toUnsignedString(System.nanoTime(), 36)
    val probe = directory.resolve(name)
    return try {
        Files.writeString(probe, "")
        Files.exists(directory.resolve(name.lowercase()))
    } catch (_: Exception) {
        false
    } finally {
        runCatching { Files.deleteIfExists(probe) }
    }
}

internal fun obsidianFileStem(title: String): String {
    val cleaned = buildString {
        for (ch in title.trim()) {
            if (ch.code < 32 || ch in "\\/:*?\"<>|#[]") append(' ') else append(ch)
        }
    }.trim().replace(Regex(" +"), " ").ifBlank { "这本书" }
    return cleaned.take(80).trim().ifBlank { "这本书" }
}

internal fun wikiLink(stem: String, title: String): String {
    if (title.isEmpty() || title.any { it == '[' || it == ']' || it == '|' || it == '\n' }) return "[[$stem]]"
    return if (stem == title) "[[$title]]" else "[[$stem|$title]]"
}

private fun headFromRecords(bookId: String, rows: List<LibraryRecord>): LibraryBookHead =
    LibraryBookHead(bookId, rows.first().bookTitle, rows.first().author)

private fun freshStem(title: String, bookId: String, used: Set<String>, ignoreCase: Boolean): String {
    fun taken(stem: String): Boolean = used.any { it.equals("$stem.md", ignoreCase = ignoreCase) }
    val base = obsidianFileStem(title)
    if (!taken(base)) return base
    val suffix = bookId.take(8).ifBlank { "2" }
    var stem = "$base $suffix"
    var n = 2
    while (taken(stem)) {
        stem = "$base $suffix $n"
        n += 1
    }
    return stem
}

private fun existingCoachFiles(directory: Path, ids: LibraryRecordIdScheme): Map<String, Path> {
    val out = LinkedHashMap<String, Path>()
    Files.newDirectoryStream(directory, "*.md").use { stream ->
        for (path in stream) {
            val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
            val scheme = frontmatterValue(text, "coach_id_scheme") ?: continue
            if (scheme != ids.name) continue
            val recordId = frontmatterValue(text, "coach_record_id") ?: continue
            val bookId = bookIdFromRecord(ids, recordId) ?: continue
            out.putIfAbsent(bookId, path)
        }
    }
    return out
}

private fun bookIdFromRecord(ids: LibraryRecordIdScheme, recordId: String): String? {
    val prefix = "${ids.name}:book:"
    if (!recordId.startsWith(prefix)) return null
    return recordId.removePrefix(prefix).trim().ifBlank { null }
}

private fun renderManaged(
    head: LibraryBookHead,
    stem: String,
    rows: List<LibraryRecord>,
    others: List<Pair<String, String>>,
): String = buildString {
    appendLine("这一段由 Coach 维护。写在这一段外面的内容，下次导出还会留着。")
    appendLine()
    val link = wikiLink(stem, head.title)
    for (kind in listOf(LibraryKind.ANNOTATION, LibraryKind.QUESTION, LibraryKind.MISTAKE)) {
        val entries = rows.filter { it.kind == kind }
        appendLine("## ${kind.label}")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("这一类还没有。")
            appendLine()
            continue
        }
        var lastPage: Int? = Int.MIN_VALUE
        for (entry in entries) {
            if (entry.page != lastPage) {
                appendLine("### ${notesPageLabel(entry.page)}")
                appendLine()
                lastPage = entry.page
            }
            appendLine("- 书：$link")
            for (line in entry.detail.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val label = line.substring(0, tab)
                val text = linkBookMentions(line.substring(tab + 1), others)
                appendLine("- $label：$text")
            }
            appendLine("<!-- ${entry.key} -->")
            appendLine()
        }
    }
}.trimEnd()

internal fun linkBookMentions(text: String, others: List<Pair<String, String>>): String {
    val sorted = others
        .filter { (_, title) -> title.length >= 2 && title.none { it == '[' || it == ']' || it == '|' || it == '\n' } }
        .sortedByDescending { it.second.length }
    if (sorted.isEmpty()) return text
    val out = StringBuilder()
    var index = 0
    while (index < text.length) {
        if (text.startsWith("[[", index)) {
            val end = text.indexOf("]]", index + 2)
            if (end < 0) {
                out.append(text.substring(index))
                break
            }
            out.append(text.substring(index, end + 2))
            index = end + 2
            continue
        }
        val matched = sorted.firstOrNull { (_, title) -> text.startsWith(title, index) }
        if (matched != null) {
            out.append(wikiLink(matched.first, matched.second))
            index += matched.second.length
        } else {
            out.append(text[index])
            index += 1
        }
    }
    return out.toString()
}

private val ownedFrontmatterKeys = setOf("title", "author", "exported_at", "coach_record_id", "coach_id_scheme")

private fun frontmatter(head: LibraryBookHead, ids: LibraryRecordIdScheme, now: Instant, previous: String?): String = buildString {
    appendLine("---")
    appendLine("title: ${yamlQuote(head.title)}")
    appendLine("author: ${yamlQuote(head.author)}")
    appendLine("exported_at: ${yamlQuote(DateTimeFormatter.ISO_INSTANT.format(now))}")
    appendLine("coach_record_id: ${yamlQuote(ids.book(head.id))}")
    appendLine("coach_id_scheme: ${yamlQuote(ids.name)}")
    val extra = userFrontmatter(previous)
    if (extra.isNotEmpty()) {
        appendLine(extra)
    }
    appendLine("---")
}

private fun userFrontmatter(previous: String?): String {
    if (previous == null) return ""
    val normalized = previous.removePrefix("\uFEFF").replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return ""
    val end = normalized.indexOf("\n---\n", 4)
    if (end < 0) return ""
    val kept = ArrayList<String>()
    var skip = false
    for (line in normalized.substring(4, end).lines()) {
        if (line.isEmpty()) {
            if (!skip) kept += line
            continue
        }
        if (line.first() != ' ' && line.first() != '\t') {
            skip = line.substringBefore(':').trim() in ownedFrontmatterKeys
        }
        if (!skip) kept += line
    }
    return kept.joinToString("\n").trim('\n')
}

private fun yamlQuote(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
    return "\"$escaped\""
}

private fun stitchObsidian(previous: String?, matter: String, managed: String): String {
    val split = if (previous == null) null else splitUserRegions(previous)
    val before = split?.before.orEmpty()
    val after = split?.after.orEmpty()
    return buildString {
        append(matter)
        append('\n')
        if (before.isNotEmpty()) {
            append(before)
            if (!before.endsWith("\n")) append('\n')
        }
        appendLine(OBSIDIAN_MANAGED_BEGIN)
        appendLine(managed)
        appendLine(OBSIDIAN_MANAGED_END)
        if (after.isNotEmpty()) {
            if (!after.startsWith("\n")) append('\n')
            append(after)
            if (!after.endsWith("\n")) append('\n')
        }
    }
}

private data class UserRegions(val before: String, val after: String)

private fun splitUserRegions(text: String): UserRegions {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    val body = bodyAfterFrontmatter(normalized)
    val begin = body.indexOf(OBSIDIAN_MANAGED_BEGIN)
    val end = if (begin < 0) -1 else body.indexOf(OBSIDIAN_MANAGED_END, begin + OBSIDIAN_MANAGED_BEGIN.length)
    if (begin < 0 || end < 0) return UserRegions(body.trim('\n'), "")
    val before = body.substring(0, begin).trim('\n')
    val after = body.substring(end + OBSIDIAN_MANAGED_END.length).trim('\n')
    return UserRegions(before, after)
}

private fun managedBody(text: String): String? {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    val body = bodyAfterFrontmatter(normalized)
    val begin = body.indexOf(OBSIDIAN_MANAGED_BEGIN)
    if (begin < 0) return null
    val start = begin + OBSIDIAN_MANAGED_BEGIN.length
    val end = body.indexOf(OBSIDIAN_MANAGED_END, start)
    if (end < 0) return null
    return body.substring(start, end).trim()
}

private fun bodyAfterFrontmatter(text: String): String {
    if (!text.startsWith("---\n")) return text
    val end = text.indexOf("\n---\n", 4)
    if (end < 0) return text
    return text.substring(end + 5)
}

private fun frontmatterValue(text: String, key: String): String? {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return null
    val end = normalized.indexOf("\n---\n", 4)
    if (end < 0) return null
    val block = normalized.substring(4, end)
    val prefix = "$key:"
    val line = block.lineSequence().firstOrNull { it.trimStart().startsWith(prefix) } ?: return null
    return unquoteYaml(line.substringAfter(':').trim())
}

private fun unquoteYaml(raw: String): String {
    if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        return raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return raw
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
