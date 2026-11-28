package com.coach.desktop.books

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal val LocalReadingAutoGuide = compositionLocalOf { true }
internal val LocalReadingSectionSummary = compositionLocalOf { true }
internal val LocalReadingAiExplain = compositionLocalOf { true }
internal val LocalAllowBookExplain = compositionLocalOf<((String) -> Unit)?> { null }
internal val LocalOpenTasteQuiz = compositionLocalOf<(() -> Unit)?> { null }

internal enum class ReadingSize(val label: String, val scale: Float) {
    SMALL("小", .9f), MEDIUM("中", 1f), LARGE("大", 1.15f)
}

internal enum class PaperColor(val label: String, val dark: Boolean?) {
    SYSTEM("跟随系统", null), LIGHT("浅", false), DARK("深", true)
}

internal enum class ReadingFont(val label: String) {
    SONG("宋体"), KAI("楷体")
}

internal enum class AutoGuideOption(val label: String, val enabled: Boolean) {
    ON("开", true), OFF("关", false)
}

/** Global to the local library, including when a development launch uses an isolated library. */
internal class ReadingSettings(directory: Path, private val busyTimeoutMs: Int = DEFAULT_BUSY_TIMEOUT_MS) {
    private val dirKey = directory.toAbsolutePath().normalize().toString()
    private val url = "jdbc:sqlite:${directory.resolve("library.sqlite")}"
    var size by mutableStateOf(ReadingSize.MEDIUM)
        private set
    var font by mutableStateOf(ReadingFont.SONG)
        private set
    var paper by mutableStateOf(PaperColor.SYSTEM)
        private set
    var autoGuide by mutableStateOf(true)
        private set
    var sectionSummary by mutableStateOf(true)
        private set
    var aiExplain by mutableStateOf(true)
        private set
    var proactiveWhispers by mutableStateOf(true)
        private set
    var endOfSectionCheckpoint by mutableStateOf(true)
        private set

    init {
        Files.createDirectories(directory)
        withBusyRetry { db ->
            db.createStatement().use { sql ->
                val exists = sql.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='reading_settings'"
                ).use { it.next() }
                if (!exists) {
                    sql.execute("CREATE TABLE IF NOT EXISTS reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                }
                sql.executeQuery("SELECT name, value FROM reading_settings").use { rows ->
                    while (rows.next()) {
                        val value = rows.getString("value")
                        when (rows.getString("name")) {
                            "size" -> size = ReadingSize.entries.firstOrNull { it.name == value } ?: ReadingSize.MEDIUM
                            "font" -> font = ReadingFont.entries.firstOrNull { it.name == value } ?: ReadingFont.SONG
                            "paper" -> paper = PaperColor.entries.firstOrNull { it.name == value } ?: PaperColor.SYSTEM
                            "autoGuide" -> autoGuide = value != "false"
                            "sectionSummary" -> sectionSummary = value != "false"
                            "aiExplain" -> aiExplain = value != "false"
                            "proactiveWhispers" -> proactiveWhispers = value != "false"
                            "endOfSectionCheckpoint" -> endOfSectionCheckpoint = value != "false"
                        }
                    }
                }
            }
        }
        registerInstance(dirKey, this)
    }

    private fun database(): Connection = DriverManager.getConnection(url).apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=$busyTimeoutMs") }
    }

    private fun <T> withBusyRetry(attempts: Int = BUSY_RETRY_ATTEMPTS, block: (Connection) -> T): T {
        var remaining = attempts
        while (true) {
            try {
                return database().use(block)
            } catch (error: SQLException) {
                if ((error.errorCode and 0xff) != SQLITE_BUSY || --remaining <= 0) throw error
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

    fun chooseFont(value: ReadingFont) {
        save("font", value.name)
        font = value
    }

    fun chooseAutoGuide(enabled: Boolean) {
        save("autoGuide", enabled.toString())
        autoGuide = enabled
    }

    fun chooseSectionSummary(enabled: Boolean) {
        save("sectionSummary", enabled.toString())
        sectionSummary = enabled
    }

    fun storedAiExplainEnabled(): Boolean = withBusyRetry { db ->
        db.prepareStatement("SELECT value FROM reading_settings WHERE name='aiExplain'").use { sql ->
            sql.executeQuery().use { rows -> !rows.next() || rows.getString(1) == "true" }
        }
    }

    fun chooseAiExplain(enabled: Boolean) {
        save("aiExplain", enabled.toString())
        aiExplain = enabled
        syncForDirectory(dirKey) { it.aiExplain = enabled }
    }

    fun chooseProactiveWhispers(enabled: Boolean) {
        save("proactiveWhispers", enabled.toString())
        syncForDirectory(dirKey) { it.proactiveWhispers = enabled }
    }

    fun chooseEndOfSectionCheckpoint(enabled: Boolean) {
        save("endOfSectionCheckpoint", enabled.toString())
        syncForDirectory(dirKey) { it.endOfSectionCheckpoint = enabled }
    }

    @Synchronized
    private fun save(name: String, value: String) {
        withBusyRetry { db ->
            db.prepareStatement("INSERT OR REPLACE INTO reading_settings(name,value) VALUES(?,?)").use { sql ->
                sql.setString(1, name)
                sql.setString(2, value)
                sql.executeUpdate()
            }
        }
    }

    companion object {
        internal const val DEFAULT_BUSY_TIMEOUT_MS = 5_000
        private const val BUSY_RETRY_ATTEMPTS = 2
        private const val SQLITE_BUSY = 5
        private val liveInstances = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ReadingSettings>>>()

        private fun registerInstance(dirKey: String, instance: ReadingSettings) {
            val list = liveInstances.computeIfAbsent(dirKey) { CopyOnWriteArrayList() }
            list.removeIf { it.get() == null }
            list.add(WeakReference(instance))
        }

        private fun syncForDirectory(dirKey: String, update: (ReadingSettings) -> Unit) {
            val list = liveInstances[dirKey] ?: return
            list.removeIf { ref ->
                val target = ref.get()
                if (target == null) true
                else {
                    update(target)
                    false
                }
            }
        }
    }
}
