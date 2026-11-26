package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/** Publish the Windows library and exam snapshots together after both pass integrity checks. */
internal object LearningMigrationBackup {
    fun prepare(directory: Path) {
        val ready = directory.resolve("before-learning-sync")
        if (Files.isDirectory(ready)) return
        val sources = listOf("library.sqlite", "exams.sqlite").map(directory::resolve).filter(Files::isRegularFile)
        if (sources.isEmpty()) return
        val stage = Files.createTempDirectory(directory, ".before-learning-sync-")
        try {
            for (source in sources) {
                val target = stage.resolve(source.fileName)
                DriverManager.getConnection("jdbc:sqlite:$source").use { db ->
                    db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
                    db.prepareStatement("VACUUM INTO ?").use { copy ->
                        copy.setString(1, target.toString())
                        copy.execute()
                    }
                }
                DriverManager.getConnection("jdbc:sqlite:${target.toUri()}?mode=ro").use { copy ->
                    copy.createStatement().use { sql ->
                        sql.executeQuery("PRAGMA integrity_check").use { rows ->
                            check(rows.next() && rows.getString(1) == "ok" && !rows.next())
                        }
                    }
                }
            }
            Files.move(stage, ready, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            sources.forEach { Files.deleteIfExists(stage.resolve(it.fileName)) }
            Files.deleteIfExists(stage)
        }
    }
}
