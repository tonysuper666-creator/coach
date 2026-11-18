package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import java.sql.DriverManager

internal enum class ReadingSize(val label: String, val scale: Float) {
    SMALL("小", .9f), MEDIUM("中", 1f), LARGE("大", 1.15f)
}

internal enum class PaperColor(val label: String, val dark: Boolean?) {
    SYSTEM("跟随系统", null), LIGHT("浅", false), DARK("深", true)
}

/** Global to the local library, including when a development launch uses an isolated library. */
internal class ReadingSettings(directory: Path) {
    private val url = "jdbc:sqlite:${directory.resolve("library.sqlite")}"
    var size by mutableStateOf(ReadingSize.MEDIUM)
        private set
    var paper by mutableStateOf(PaperColor.SYSTEM)
        private set

    init {
        DriverManager.getConnection(url).use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE IF NOT EXISTS reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                sql.executeQuery("SELECT name, value FROM reading_settings").use { rows ->
                    while (rows.next()) {
                        val value = rows.getString("value")
                        when (rows.getString("name")) {
                            "size" -> size = ReadingSize.entries.firstOrNull { it.name == value } ?: ReadingSize.MEDIUM
                            "paper" -> paper = PaperColor.entries.firstOrNull { it.name == value } ?: PaperColor.SYSTEM
                        }
                    }
                }
            }
        }
    }

    fun chooseSize(value: ReadingSize) {
        save("size", value.name)
        size = value
    }

    fun choosePaper(value: PaperColor) {
        save("paper", value.name)
        paper = value
    }

    private fun save(name: String, value: String) {
        DriverManager.getConnection(url).use { db ->
            db.prepareStatement("INSERT OR REPLACE INTO reading_settings(name,value) VALUES(?,?)").use { sql ->
                sql.setString(1, name)
                sql.setString(2, value)
                sql.executeUpdate()
            }
        }
    }
}
