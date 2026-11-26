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
 * 每本书一个 Markdown。Coach 只管分隔标记中间的那一段；标记外面是用户自己的，再导一次不动。
 * 导出批注、问答、错题。高亮只留在库里搜，不把纯摘录再写进笔记。
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
    val ids = libraryRecordIds
    val exportable = records.filter { it.kind != LibraryKind.HIGHLIGHT }
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
        val stem = freshStem(heads.getValue(bookId).title, bookId, usedNames)
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
        val text = stitchObsidian(previous, frontmatter(head, ids, now), managed)
        writeAtomically(path, text)
        written += 1
    }
    return ObsidianExportOutcome.Done(written, skipped, items)
}

internal fun directoryInsideGit(path: Path): Boolean =
    generateSequence(path.toAbsolutePath()) { it.parent }.any { Files.exists(it.resolve(".git")) }

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

private fun freshStem(title: String, bookId: String, used: Set<String>): String {
    val base = obsidianFileStem(title)
    if ("$base.md" !in used) return base
    val suffix = bookId.take(6).ifBlank { "2" }
    var stem = "$base $suffix"
    var n = 2
    while ("$stem.md" in used) {
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

private fun frontmatter(head: LibraryBookHead, ids: LibraryRecordIdScheme, now: Instant): String = buildString {
    appendLine("---")
    appendLine("title: ${yamlQuote(head.title)}")
    appendLine("author: ${yamlQuote(head.author)}")
    appendLine("exported_at: ${yamlQuote(DateTimeFormatter.ISO_INSTANT.format(now))}")
    appendLine("coach_record_id: ${yamlQuote(ids.book(head.id))}")
    appendLine("coach_id_scheme: ${yamlQuote(ids.name)}")
    appendLine("---")
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
