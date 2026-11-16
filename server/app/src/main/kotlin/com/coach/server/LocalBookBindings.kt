package com.coach.server

import com.coach.domain.LedgerJson
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Operator-owned registrations, separate from the PDF viewer catalog. No HTTP write route.
 * Each entry binds original bytes AND the desktop's saved pagination to an existing ledger source.
 * It cannot create source visibility, course membership or learned knowledge-point anchors.
 */
internal fun localBookBindings(root: Path?, version: String, page: Int): List<ReaderDocument> {
    if (root == null) return emptyList()
    val realRoot = root.toRealPath()
    val catalog = realRoot.resolve("local-books.json")
    if (!Files.exists(catalog)) return emptyList()
    require(Files.size(catalog) <= 1_048_576)
    val raw = LedgerJson.parseToJsonElement(Files.readString(catalog)).jsonObject
    require(raw["schema"]?.jsonPrimitive?.intOrNull == 1)
    val entries = raw.getValue("books").jsonArray
    require(entries.size <= 500)
    fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    fun privateFile(name: String): Path {
        val relative = Path.of(name)
        require(!relative.isAbsolute)
        return realRoot.resolve(relative).normalize().toRealPath().also {
            require(it.startsWith(realRoot) && Files.isRegularFile(it))
        }
    }
    return entries.map { it.jsonObject }.filter { it.string("sha256") == version }.map { entry ->
        val id = entry.string("id")
        val course = entry.string("course")
        val title = entry.string("title")
        val source = entry.string("explainSourceId")
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(id) && course.length in 1..128 && title.length in 1..300 &&
            Regex("[A-Za-z0-9._:-]{1,80}").matches(source))
        val snapshotVersion = entry.string("pagesSha256")
        require(Regex("[0-9a-f]{64}").matches(snapshotVersion))
        val file = privateFile(entry.string("file"))
        require(Files.size(file) in 1..268_435_456)
        val snapshot = privateFile(entry.string("pagesFile"))
        require(Files.size(snapshot) <= 33_554_432)
        val bytes = Files.readAllBytes(snapshot)
        require(ReaderLibrary.digest(bytes) == snapshotVersion)
        val saved = LedgerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(saved["schema"]?.jsonPrimitive?.intOrNull == 1 && saved.string("book") == version)
        val pages = saved.getValue("pages").jsonArray.map { it.jsonObject }
        require(pages.size in 1..100_000)
        val numbers = pages.map { it.getValue("number").jsonPrimitive.int }
        require(numbers.all { it in 1..100_000 } && numbers.distinct().size == numbers.size)
        val selected = pages.singleOrNull { it.getValue("number").jsonPrimitive.int == page }
        // A page absent from the immutable import snapshot cannot acquire an alias.
        require(selected != null)
        ReaderDocument(id, course, title, file, version, numbers.max(), null, "reading", null,
            explainSourceId = source, savedPage = selected.string("text"), pageSnapshotVersion = snapshotVersion)
    }
}
