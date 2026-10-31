package com.coach.server

import com.coach.store.SourcePage
import com.coach.store.tutorSourceAllowed
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The same bounded pack is used for local enrichment and for the lookup snapshot. */
data class SourcePack(val pages: List<SourcePage>, val legacy: String = "") {
    val anchors: Map<String, String> get() = pages.mapIndexed { i, p -> "S${i + 1}" to p.text }.toMap()
    fun render(): String = buildString {
        pages.forEachIndexed { i, p -> appendLine("【S${i + 1}】 ${p.docId} PDF page ${p.pdfPage}\n${p.text}\n") }
        if (legacy.isNotBlank()) append(legacy)
    }
    companion object {
        fun prepare(existing: String, hits: List<SourcePage>): SourcePack {
            val headers = Regex("(?m)^【S[0-9]+】 (.+) PDF page ([0-9]+)\\n").findAll(existing).toList()
            val previous = headers.mapIndexed { i, h -> SourcePage(h.groupValues[1],h.groupValues[2].toInt(),
                existing.substring(h.range.last+1,headers.getOrNull(i+1)?.range?.first ?: existing.length).trimEnd()) }
            val newHits = if (headers.isEmpty() && existing.isNotBlank()) hits.filterNot { it.text.isNotBlank() && existing.contains(it.text) } else hits
            val candidates = (newHits + previous).groupBy { it.key }.values.map { versions -> versions.maxBy { it.score } }
            val selected = mutableListOf<SourcePage>()
            // Count headers and locators against the character budget as well.
            var remaining = 40_000
            for (page in candidates.sortedByDescending { it.score }) {
                if (selected.size == 6) break
                val cost = page.text.length + page.docId.length + 64
                if (cost > remaining || !tutorSourceAllowed(page.docId)) continue
                selected += page
                remaining -= cost
            }
            // Legacy callers supply one opaque preparation excerpt. Multiple page markers are counted.
            val legacyPages = maxOf(1,Regex("【S[0-9]+】|<!-- PDF 第 [0-9]+ 页").findAll(existing).count())
            val legacy = if (headers.isEmpty() && selected.size + legacyPages <= 6 && existing.length <= remaining) existing else ""
            return SourcePack(selected, legacy)
        }
    }
}

/** Each paragraph is withheld until BOTH its anchors and every quoted span are checked. */
class SourceCitationGate(private val anchors: Map<String,String>) {
    private val buffer = StringBuilder()
    var checked = 0; private set
    var accepted = 0; private set
    fun push(text: String): List<String> {
        buffer.append(text)
        val out = mutableListOf<String>()
        while (true) {
            val end = buffer.indexOf("\n\n")
            if (end < 0) break
            validate(buffer.substring(0,end))?.let { out += "$it\n\n" }
            buffer.delete(0,end+2)
        }
        return out
    }
    fun finish(complete: Boolean): List<String> {
        val tail = buffer.toString(); buffer.setLength(0)
        // On interruption an unfinished paragraph is never promoted to verified content.
        return if (complete) listOfNotNull(validate(tail)) else emptyList()
    }
    private fun validate(paragraph: String): String? {
        if (paragraph.isBlank()) return null
        checked++
        val refs = Regex("【(S[0-9]+)】").findAll(paragraph).map { it.groupValues[1] }.toList()
        if (refs.isEmpty() || refs.any { it !in anchors }) return null
        if (!Regex("【S[0-9]+】[。.!！?？\\s]*$").containsMatchIn(paragraph)) return null
        var unquoted = paragraph
        for ((open, close) in listOf('“' to '”','"' to '"','「' to '」','『' to '』','«' to '»')) {
            val pattern = Regex("${Regex.escape(open.toString())}([^${Regex.escape(close.toString())}]+)${Regex.escape(close.toString())}")
            for (quote in pattern.findAll(paragraph)) {
                if (refs.none { anchors.getValue(it).contains(quote.groupValues[1]) }) return null
            }
            unquoted = unquoted.replace(pattern, "")
            if (open in unquoted || close in unquoted) return null
        }
        val singleQuotes = listOf(
            Regex("‘([^’]+)’"),
            Regex("(?<![\\p{L}\\p{N}])'([^'\\n]+)'(?![\\p{L}\\p{N}])"),
        )
        for (pattern in singleQuotes) for (quote in pattern.findAll(paragraph)) {
            if (refs.none { anchors.getValue(it).contains(quote.groupValues[1]) }) return null
        }
        if ('‘' in paragraph.replace(singleQuotes.first(), "")) return null
        if (Regex("(?<![\\p{L}\\p{N}])'").containsMatchIn(paragraph.replace(singleQuotes[1], ""))) return null
        accepted++
        return paragraph
    }
}

/**
 * No CLI gets filesystem access. It receives only text read by this isolated helper.
 * The snapshot contains vetted pages; the ledger/answer_keys and original library are never mounted.
 * Unlike the general subscription-CLI profile this profile denies home, network and all writes.
 */
class SourceReadSandbox {
    fun read(pack: SourcePack): SourcePack {
        check(System.getProperty("os.name").startsWith("Mac")) { "Source sandbox unavailable; lookup disabled" }
        val dir = Files.createTempDirectory("coach-source-").toRealPath()
        try {
            val files = pack.pages.mapIndexed { i, p -> dir.resolve("page-$i").also { Files.writeString(it,p.text) } }
            val read = files.map { file ->
                val process = ProcessBuilder(command(listOf(file),listOf(file))).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                // Drain while cat writes; UTF-8 for one bounded page cannot exceed 160K bytes.
                val bytes = process.inputStream.readNBytes(160_001)
                if (!process.waitFor(2,TimeUnit.SECONDS)) { process.destroyForcibly(); error("Source read timeout") }
                check(process.exitValue() == 0 && bytes.size <= 160_000) { "Source read denied" }
                bytes.decodeToString()
            }
            return SourcePack(pack.pages.zip(read) { p, text -> p.copy(text=text) })
        } finally {
            Files.walk(dir).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    /** Also used by the real OS denial tests, including symlink escapes. */
    fun command(allowed: List<Path>, targets: List<Path>): List<String> {
        fun quote(path: Path) = "\"" + path.toAbsolutePath().toString().replace("\\","\\\\").replace("\"","\\\"") + "\""
        val profile = """
            (version 1)
            (deny default)
            (allow process-exec (literal "/bin/cat"))
            (allow process-fork sysctl-read mach-lookup)
            (allow file-read-metadata)
            (allow file-read* (literal "/") (literal "/bin/cat") (subpath "/System/Library") (subpath "/System/Volumes/Preboot") (subpath "/usr/lib") (literal "/dev/null"))
            (allow file-read-data ${allowed.joinToString(" ") { "(literal ${quote(it.toRealPath())})" }})
        """.trimIndent()
        return listOf("/usr/bin/sandbox-exec","-p",profile,"/bin/cat") + targets.map { it.toString() }
    }
}

fun sourceLookupQuery(raw: String): String? = Regex("【需查原文[:：]([^】\\r\\n]{1,1000})】\\s*$")
    .find(raw)?.groupValues?.get(1)?.trim()?.removeSurrounding("<",">")?.takeIf { it.isNotBlank() }
