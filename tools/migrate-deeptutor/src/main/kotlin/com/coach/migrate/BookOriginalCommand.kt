package com.coach.migrate

import com.coach.domain.BookPracticeMapping
import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText

/** Uses the existing local owner/offline boundary, not a new learner permission or upload route. */
fun bookOriginalCommand(command: String, args: List<String>) {
    val register = command == "register-book-original"
    val allowed = setOf("--database", "--course", "--sha256") +
        if (register) setOf("--pdf", "--canonical-sha256", "--mappings") else emptySet()
    require(args.size % 2 == 0) { "Book original options require values" }
    val pairs = args.chunked(2)
    require(pairs.all { it[0] in allowed } && pairs.map { it[0] }.toSet() == allowed && pairs.size == allowed.size) {
        "Supply each required option exactly once: ${allowed.joinToString()}"
    }
    val options = pairs.associate { it[0] to it[1] }
    val database = Path.of(options.getValue("--database")).toRealPath()
    requirePrivateDestination(database, listOf(resolvedDestination(Path.of(System.getProperty("user.home"), "DeepTutor"))))
    require(Files.isRegularFile(database.resolve("coach.sqlite"))) { "Use an existing seeded offline database" }
    val course = options.getValue("--course")
    val original = options.getValue("--sha256")
    require(course.isNotBlank() && original.matches(Regex("[a-f0-9]{64}"))) { "Course and exact SHA256 are required" }
    // Enforce the same private destination policy as seed-content. Snapshot.kt remains its owner’s domain.
    privateDir(database)
    FileChannel.open(database.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        val lock = channel.tryLock() ?: error("Coach server is running; use an offline directory or stop it first")
        lock.use {
            LedgerStore(database).use { store ->
                if (register) {
                    val pdf = Path.of(options.getValue("--pdf")).toRealPath()
                    require(Files.isRegularFile(pdf) && Files.size(pdf) in 1..(128L * 1024 * 1024)) { "Use an actual PDF of at most 128 MiB" }
                    val mappings = LedgerJson.decodeFromString<List<BookPracticeMapping>>(Path.of(options.getValue("--mappings")).readText())
                    val changed = store.registerBookOriginal(course, original, options.getValue("--canonical-sha256"), Files.readAllBytes(pdf), mappings)
                    println("register-book-original ${if (changed) "installed" else "unchanged"}: course=$course original=$original mappings=${mappings.size} learningRecords=0")
                } else {
                    store.withdrawBookOriginal(course, original)
                    println("withdraw-book-original: course=$course original=$original learningRecords=0")
                }
            }
        }
    }
}
