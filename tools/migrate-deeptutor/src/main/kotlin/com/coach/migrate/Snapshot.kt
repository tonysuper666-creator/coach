package com.coach.migrate

import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.*
import java.security.MessageDigest
import kotlin.io.path.*
import kotlinx.serialization.json.*

fun privateDir(p: Path): Path {
    Files.createDirectories(p)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"))
    return p
}
fun safeFiles(root: Path): List<Path> {
    if (!root.exists()) return emptyList()
    val result=mutableListOf<Path>()
    Files.walkFileTree(root, object:SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir:Path,attrs:java.nio.file.attribute.BasicFileAttributes):FileVisitResult =
            if(dir.fileName.toString() in setOf("sealed","答案") || dir.fileName.toString().startsWith(".env")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        override fun visitFile(file:Path,attrs:java.nio.file.attribute.BasicFileAttributes):FileVisitResult {
            if(attrs.isRegularFile && !file.fileName.toString().startsWith(".env")) result.add(file)
            return FileVisitResult.CONTINUE
        }
    })
    return result.sorted()
}
fun copyFile(from: Path, to: Path) {
    require(from.toAbsolutePath().normalize()==from.toRealPath()) { "Refusing symbolic link or aliased ancestor" }
    privateDir(to.parent)
    Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES)
    Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("rw-------"))
}
fun stat(p: Path): String = if (!p.exists()) "absent" else {
    val a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    "${a.size()}:${a.lastModifiedTime()}"
}
fun inventory(root: Path): String = Files.walk(root).use { s ->
    s.sorted().map { "${root.relativize(it)}\t${stat(it)}" }.toList().joinToString("\n", postfix="\n")
}
fun idle(log: Path, now: Instant = Instant.now()) {
    require(log.isRegularFile()) { "Bridge activity log missing; cannot establish idle window" }
    val last = Files.newBufferedReader(log).useLines { it.lastOrNull { line -> line.isNotBlank() } }
        ?: error("Bridge activity log empty; cannot establish idle window")
    val time = OffsetDateTime.parse(last.substringBefore(' ')).toInstant()
    require(!time.isAfter(now) && Duration.between(time, now).seconds >= 600 &&
        Duration.between(Files.getLastModifiedTime(log).toInstant(), now).seconds >= 600) {
        "Bridge active within ten minutes; retry in a later idle window"
    }
}
fun snapshot(source: Path, staging: Path, weights: Path): Path {
    val log = source.resolve("data/user/runtime/bridge.log")
    idle(log)
    val target = privateDir(staging.resolve(Instant.now().toString().replace(':','-')))
    try {
        val before = inventory(source.resolve("data"))
        fun ls(): String {
            val process = ProcessBuilder("ls", "-laR", source.resolve("data").toString()).start()
            val output = process.inputStream.bufferedReader().readText()
            require(process.waitFor() == 0) { "ls evidence failed" }
            return output
        }
        val lsBefore = ls()
        val dbs = listOf("data/user/workspace/learning/mastery/mastery.sqlite3", "data/user/chat_history.db")
        val dbFiles = dbs.flatMap { listOf(it, "$it-wal") }
        val fixed = listOf("data/user/workspace/courses/courses.json", "data/learning_journal/journal.json",
            "data/digests/questions/ECNM10119-期中练习题.json", "tools/patch_backups/gate_types_before.json")
        val notebook = source.resolve("data/user/workspace/notebook")
        val library = source.resolve("library/ECNM10119-健康经济学")
        val files = (dbFiles.filter { source.resolve(it).exists() } + fixed +
            safeFiles(notebook).map { source.relativize(it).toString() } +
            safeFiles(library.resolve("教材")).filter { it.extension == "md" }.map { source.relativize(it).toString() }).sorted()
        require(dbs.all { source.resolve(it).isRegularFile() }) { "Required database missing" }
        val stamps = (files + dbFiles).distinct().associateWith { stat(source.resolve(it)) }
        files.forEach { copyFile(source.resolve(it), target.resolve(it)) }
        copyFile(weights, target.resolve("exam-weights.md"))
        val materials = safeFiles(library.resolve("资料")).map { library.relativize(it).toString() }
        target.resolve("materials.json").writeText(JsonArray(materials.map(::JsonPrimitive)).toString())
        idle(log)
        require(stamps.all { (p,v) -> stat(source.resolve(p)) == v }) { "Source changed while copying; discard and retry" }
        val after = inventory(source.resolve("data"))
        require(before == after) { "DeepTutor data inventory changed; discard and retry" }
        target.resolve("data-before.stat").writeText(before)
        target.resolve("data-after.stat").writeText(after)
        val lsAfter=ls()
        require(lsBefore==lsAfter) { "DeepTutor ls evidence changed; discard and retry" }
        target.resolve("data-before.ls").writeText(lsBefore)
        target.resolve("data-after.ls").writeText(lsAfter)
        target.resolve("copy-stats.json").writeText(canonical(buildJsonObject {
            put("before", JsonObject(stamps.mapValues { JsonPrimitive(it.value) }))
            put("after", JsonObject(stamps.mapValues { JsonPrimitive(stat(source.resolve(it.key))) }))
            put("data_unchanged", true)
        }))
        dbs.forEach { readDatabase(target.resolve(it)) }
        require(before==inventory(source.resolve("data"))) { "DeepTutor data changed during integrity checking; discard and retry" }
        return target
    } catch (e: Exception) {
        target.toFile().deleteRecursively()
        throw e
    }
}
fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
fun canonical(e: JsonElement): String {
    fun sorted(v: JsonElement): JsonElement = when(v) {
        is JsonObject -> JsonObject(v.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(v.map(::sorted))
        else -> v
    }
    return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), sorted(e)) + "\n"
}
