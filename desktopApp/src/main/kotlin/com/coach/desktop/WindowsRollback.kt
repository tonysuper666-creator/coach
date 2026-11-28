package com.coach.desktop

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager

internal const val ROLLBACK_NO_PACKAGE = "这台电脑上没有留下上一版的安装包，没有更换版本。可以继续使用现在这个版本。"
internal const val ROLLBACK_NO_SNAPSHOT = "没有留下升级前的学习记录，没有更换版本。可以继续使用现在这个版本。"
internal const val ROLLBACK_FAILED = "没能回到上一版，还在当前版本。可以继续使用。"

/** msiexec has been handed to a helper that outlives this process. The library stays as it is. */
internal const val ROLLBACK_HANDOFF = -1

/**
 * One-step rollback.
 *
 * The snapshot is the library as it was the moment this version was entered, taken before the new
 * schema migration. Books, highlights, notes, questions and mistakes added afterwards are parked.
 * A row that already existed is parked too when its columns differ. The park file is merged the
 * next time this version, or a newer one, starts. The rolled-back build does not merge it.
 * When both sides changed the same row, the edit made after the rollback wins, and the other
 * copy is kept in `rollback_copies`. Only the immediately previous installer is kept.
 */
internal object WindowsRollback {
    private const val PENDING_V2 = "pending-v2.sqlite"
    private const val PENDING_FOR = "pending-for.txt"

    private val parked = listOf(
        ParkedTable("books", listOf("id")),
        ParkedTable("pages", listOf("book", "page")),
        ParkedTable("book_highlights", listOf("book", "page", "start", "end")),
        ParkedTable("book_notes", listOf("book", "id")),
        ParkedTable("book_reviews", listOf("book", "lesson", "mode")),
        ParkedTable("book_ask_turns", listOf("id")),
    )

    fun prepareLaunch(libraryDir: Path, version: String) {
        val rollback = libraryDir.resolve(".rollback")
        val marker = rollback.resolve("snapshot-for.txt")
        val pending = rollback.resolve("pending.sqlite")
        val pendingV2 = rollback.resolve(PENDING_V2)
        if (Files.isRegularFile(marker) && Files.readString(marker).trim() == version &&
            !Files.isRegularFile(pending) && !Files.isRegularFile(pendingV2)
        ) return
        val live = libraryDir.resolve("library.sqlite")
        if (!Files.isRegularFile(live)) return
        if (!roomFor(libraryDir, live)) return
        capturePair(libraryDir)
        Files.writeString(marker, version + "\n")
    }

    /** Replace the snapshot with the database this process is about to leave, tagged for the arriving version. */
    fun captureBeforeInstall(libraryDir: Path, arrivingVersion: String) {
        val live = libraryDir.resolve("library.sqlite")
        if (Files.isRegularFile(live) && !roomFor(libraryDir, live)) throw UpdateFailed(UpdateFailure.SPACE, UPDATE_SPACE)
        if (Files.isRegularFile(live)) capturePair(libraryDir)
        else Files.createDirectories(libraryDir.resolve(".rollback"))
        Files.writeString(libraryDir.resolve(".rollback").resolve("snapshot-for.txt"), arrivingVersion + "\n")
    }

    fun mergeParked(libraryDir: Path, version: String = "") {
        val rollback = libraryDir.resolve(".rollback")
        val files = listOf(rollback.resolve(PENDING_V2), rollback.resolve("pending.sqlite"))
            .filter { Files.isRegularFile(it) }
        val live = libraryDir.resolve("library.sqlite")
        if (files.isEmpty() || !Files.isRegularFile(live)) return
        val pendingFor = rollback.resolve(PENDING_FOR)
        if (Files.isRegularFile(pendingFor) && version.isNotEmpty()) {
            val fromNumber = versionNumber(Files.readString(pendingFor).trim())
            val nowNumber = versionNumber(version)
            if (fromNumber != null && nowNumber != null && nowNumber < fromNumber) return
        }
        for (pending in files) mergeUserRows(live, pending)
        capturePair(libraryDir)
        files.forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(pendingFor)
    }

    /**
     * Starts the previous installer without touching the live library.
     * The library is replaced only after msiexec returns 0 or 3010.
     * An empty string means the caller should exit: either the previous version is installed
     * and the snapshot is already restored, or a helper will do both after this process ends.
     * Any other string is a status to show while staying on this version, with the library unchanged.
     */
    fun rollback(
        libraryDir: Path,
        updatesDir: Path,
        freeBytes: () -> Long = { usableSpace(libraryDir) },
        acquirePrevious: (Path) -> Pair<Path, String>? = { null },
        install: (Path, Path, Path) -> Int = ::handoffRollbackInstall,
    ): String {
        val snapshot = libraryDir.resolve(".rollback").resolve("snapshot.sqlite")
        if (!Files.isRegularFile(snapshot)) return ROLLBACK_NO_SNAPSHOT
        val live = libraryDir.resolve("library.sqlite")
        if (!Files.isRegularFile(live)) return ROLLBACK_NO_SNAPSHOT
        val exams = libraryDir.resolve("exams.sqlite")
        val bytes = Files.size(live) + (if (Files.isRegularFile(exams)) Files.size(exams) else 0L)
        if (freeBytes() < bytes * 3 + 16L * 1024 * 1024) return UPDATE_SPACE
        val acquired = try {
            ensurePrevious(updatesDir, acquirePrevious)
        } catch (error: UpdateFailed) {
            return error.message ?: UPDATE_FAILED
        } catch (_: Exception) {
            return ROLLBACK_FAILED
        }
        if (acquired != null) return acquired
        val previous = updatesDir.resolve("previous.msi")
        if (!Files.isRegularFile(previous)) return ROLLBACK_NO_PACKAGE
        val safety = libraryDir.resolve(".rollback").resolve("safety")
        val pending = libraryDir.resolve(".rollback").resolve(PENDING_V2)
        val pendingFor = libraryDir.resolve(".rollback").resolve(PENDING_FOR)
        val current = updatesDir.resolve("current.msi")
        var restored = false
        return try {
            backupPair(libraryDir, safety)
            parkNewRows(live, snapshot, pending)
            val leaving = updatesDir.resolve("current.json")
            if (Files.isRegularFile(leaving)) {
                Files.writeString(pendingFor, Files.readString(leaving).trim() + "\n")
            }
            when (val code = install(previous, current, libraryDir)) {
                0, 3010 -> {
                    restoreSnapshot(libraryDir)
                    restored = true
                    ""
                }
                ROLLBACK_HANDOFF -> ""
                else -> {
                    Files.deleteIfExists(pending)
                    Files.deleteIfExists(pendingFor)
                    ROLLBACK_FAILED
                }
            }
        } catch (_: Exception) {
            if (restored) undo(libraryDir, safety)
            Files.deleteIfExists(pending)
            Files.deleteIfExists(pendingFor)
            ROLLBACK_FAILED
        }
    }

    /** Downloads nothing itself. [acquirePrevious] returns an already verified installer, or null. */
    private fun ensurePrevious(updatesDir: Path, acquirePrevious: (Path) -> Pair<Path, String>?): String? {
        if (Files.isRegularFile(updatesDir.resolve("previous.msi"))) return null
        val staged = acquirePrevious(updatesDir) ?: return ROLLBACK_NO_PACKAGE
        if (!Files.isRegularFile(staged.first)) return ROLLBACK_NO_PACKAGE
        acceptPrevious(updatesDir, staged.first, staged.second)
        return null
    }

    /** Shown once on the next launch when the helper could not finish the rollback. */
    fun consumeRollbackFailure(libraryDir: Path): String? {
        val marker = libraryDir.resolve(".rollback").resolve("failed.txt")
        if (!Files.isRegularFile(marker)) return null
        Files.deleteIfExists(marker)
        return ROLLBACK_FAILED
    }

    /**
     * Starts a helper and returns immediately. The app must exit so msiexec can replace its files.
     * The helper waits for that exit, runs the previous package, and only then swaps the library.
     * A failed install leaves the library in place and, if the product directory is gone, installs current.msi.
     */
    fun handoffRollbackInstall(previous: Path, current: Path, libraryDir: Path): Int {
        val root = System.getenv("SystemRoot") ?: return Int.MIN_VALUE
        val powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val cmd = Path.of(root, "System32", "cmd.exe")
        if (!Files.isRegularFile(powershell) || !Files.isRegularFile(cmd)) return Int.MIN_VALUE
        val updatesDir = previous.parent
        Files.createDirectories(updatesDir)
        val script = updatesDir.resolve("rollback-helper.ps1")
        Files.writeString(
            script,
            helperScript(
                previous = previous,
                current = current,
                libraryDir = libraryDir,
                result = updatesDir.resolve("rollback-result.txt"),
                log = updatesDir.resolve("rollback-install.log"),
            ),
        )
        ProcessBuilder(
            cmd.toString(),
            "/c",
            "start \"rollback\" /min \"$powershell\" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File \"$script\"",
        ).start()
        return ROLLBACK_HANDOFF
    }

    private fun helperScript(previous: Path, current: Path, libraryDir: Path, result: Path, log: Path): String {
        fun q(path: Path) = "'" + path.toAbsolutePath().toString().replace("'", "''") + "'"
        return """
            ${'$'}ErrorActionPreference = 'Continue'
            ${'$'}previous = ${q(previous)}
            ${'$'}current = ${q(current)}
            ${'$'}library = ${q(libraryDir)}
            ${'$'}result = ${q(result)}
            ${'$'}log = ${q(log)}
            function Wait-App {
              ${'$'}deadline = (Get-Date).AddMinutes(2)
              do {
                if (-not (Get-Process -Name 'LearningCoach' -ErrorAction SilentlyContinue)) { return }
                Start-Sleep -Milliseconds 400
              } while ((Get-Date) -lt ${'$'}deadline)
            }
            function Note([string]${'$'}text) { Add-Content -LiteralPath ${'$'}result -Value ${'$'}text }
            Wait-App
            if (-not (Test-Path -LiteralPath ${'$'}current)) {
              ${'$'}cached = ${'$'}null
              Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\UserData' -Recurse -ErrorAction SilentlyContinue |
                Where-Object { ${'$'}_.PSChildName -eq 'InstallProperties' } |
                ForEach-Object {
                  ${'$'}props = Get-ItemProperty ${'$'}_.PSPath -ErrorAction SilentlyContinue
                  if (${'$'}props.DisplayName -eq 'LearningCoach' -and ${'$'}props.LocalPackage -and (Test-Path -LiteralPath ${'$'}props.LocalPackage)) {
                    ${'$'}cached = ${'$'}props.LocalPackage
                  }
                }
              if (${'$'}cached) { Copy-Item -LiteralPath ${'$'}cached -Destination ${'$'}current -Force }
            }
            ${'$'}installer = Join-Path ${'$'}env:SystemRoot 'System32\msiexec.exe'
            ${'$'}proc = Start-Process -FilePath ${'$'}installer -ArgumentList @('/i', ${'$'}previous, '/passive', '/norestart', '/l*v', ${'$'}log) -Wait -PassThru
            ${'$'}code = ${'$'}proc.ExitCode
            'EXIT=' + ${'$'}code | Set-Content -LiteralPath ${'$'}result
            ${'$'}rollback = Join-Path ${'$'}library '.rollback'
            ${'$'}pending = Join-Path ${'$'}rollback 'pending.sqlite'
            ${'$'}pendingV2 = Join-Path ${'$'}rollback 'pending-v2.sqlite'
            ${'$'}pendingFor = Join-Path ${'$'}rollback 'pending-for.txt'
            ${'$'}exe = Join-Path ${'$'}env:LOCALAPPDATA 'LearningCoach\LearningCoach.exe'
            if (${'$'}code -eq 0 -or ${'$'}code -eq 3010) {
              function Swap-Db([string]${'$'}source, [string]${'$'}target) {
                ${'$'}staging = ${'$'}target + '.rollback-new'
                Copy-Item -LiteralPath ${'$'}source -Destination ${'$'}staging -Force
                Remove-Item -LiteralPath ${'$'}target -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath (${'$'}target + '-wal') -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath (${'$'}target + '-shm') -Force -ErrorAction SilentlyContinue
                Move-Item -LiteralPath ${'$'}staging -Destination ${'$'}target -Force
              }
              Swap-Db (Join-Path ${'$'}rollback 'snapshot.sqlite') (Join-Path ${'$'}library 'library.sqlite')
              ${'$'}exams = (Get-Content -LiteralPath (Join-Path ${'$'}rollback 'exams.txt') -Raw -ErrorAction SilentlyContinue)
              if (${'$'}exams -match 'present') {
                Swap-Db (Join-Path ${'$'}rollback 'snapshot-exams.sqlite') (Join-Path ${'$'}library 'exams.sqlite')
              } elseif (${'$'}exams -match 'absent') {
                ${'$'}liveExams = Join-Path ${'$'}library 'exams.sqlite'
                Remove-Item -LiteralPath ${'$'}liveExams -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath (${'$'}liveExams + '-wal') -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath (${'$'}liveExams + '-shm') -Force -ErrorAction SilentlyContinue
              }
              Note 'SWAPPED=yes'
              exit 0
            }
            Remove-Item -LiteralPath ${'$'}pending -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath ${'$'}pendingV2 -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath ${'$'}pendingFor -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath ${'$'}exe) -and (Test-Path -LiteralPath ${'$'}current)) {
              Start-Process -FilePath ${'$'}installer -ArgumentList @('/i', ${'$'}current, '/passive', '/norestart') -Wait | Out-Null
              Note 'REINSTALLED=current'
            }
            Set-Content -LiteralPath (Join-Path ${'$'}rollback 'failed.txt') -Value 'failed' -Encoding Ascii
            Note 'SWAPPED=no'
            exit ${'$'}code
        """.trimIndent()
    }

    /** After msiexec has been started: the running version's installer becomes the one-step previous. */
    fun retainInstallers(updatesDir: Path, staged: Path, newVersion: String, runningVersion: String) {
        Files.createDirectories(updatesDir)
        val current = updatesDir.resolve("current.msi")
        val previous = updatesDir.resolve("previous.msi")
        if (Files.isRegularFile(current)) {
            Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(updatesDir.resolve("previous.json"), runningVersion + "\n")
        }
        Files.copy(staged, current, StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(updatesDir.resolve("current.json"), newVersion + "\n")
    }

    fun acceptPrevious(updatesDir: Path, staged: Path, version: String) {
        Files.createDirectories(updatesDir)
        val previous = updatesDir.resolve("previous.msi")
        if (Files.isRegularFile(previous)) return
        Files.copy(staged, previous, StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(updatesDir.resolve("previous.json"), version + "\n")
    }

    internal fun mergeUserRows(live: Path, pending: Path) = sqlite(live) { db ->
        db.createStatement().use { it.execute("ATTACH DATABASE '${sqlPath(pending)}' AS parked") }
        try {
            ensureCopies(db)
            for (table in parked) {
                if (!tableExists(db, "main", table.name) || !tableExists(db, "parked", table.name)) continue
                val shared = columns(db, "main", table.name).filter { it in columns(db, "parked", table.name) }
                if (!table.key.all { it in shared }) continue
                val liveAlias = table.key.joinToString(" AND ") { "live.$it = parked.$it" }
                val insertCols = shared.joinToString(",")
                val selectCols = shared.joinToString(",") { "parked.$it" }
                db.createStatement().use {
                    it.executeUpdate(
                        """INSERT INTO ${table.name} ($insertCols)
                           SELECT $selectCols FROM parked.${table.name} AS parked
                           WHERE NOT EXISTS (SELECT 1 FROM ${table.name} AS live WHERE $liveAlias)""",
                    )
                }
                if (tableExists(db, "parked", baseName(table))) reconcileExisting(db, table, shared)
            }
        } finally {
            db.createStatement().use { it.execute("DETACH DATABASE parked") }
        }
    }

    internal fun parkNewRows(live: Path, snapshot: Path, pending: Path): Int {
        Files.deleteIfExists(pending)
        Files.createDirectories(pending.parent)
        var parkedRows = 0
        sqlite(live) { db ->
            db.createStatement().use { it.execute("ATTACH DATABASE '${sqlPath(snapshot)}' AS snap") }
            db.createStatement().use { it.execute("ATTACH DATABASE '${sqlPath(pending)}' AS park") }
            try {
                for (table in parked) {
                    if (!tableExists(db, "main", table.name)) continue
                    db.createStatement().use {
                        it.execute("CREATE TABLE park.${table.name} AS SELECT * FROM main.${table.name} WHERE 0")
                    }
                    val inSnapshot = tableExists(db, "snap", table.name) &&
                        table.key.all { it in columns(db, "snap", table.name) }
                    val shared = if (!inSnapshot) emptyList() else {
                        columns(db, "main", table.name).filter { it in columns(db, "snap", table.name) }
                    }
                    val match = table.key.joinToString(" AND ") { "s.$it = n.$it" }
                    val differs = shared.joinToString(" OR ") { "n.$it IS NOT s.$it" }
                    val where = if (!inSnapshot) "" else {
                        " WHERE NOT EXISTS (SELECT 1 FROM snap.${table.name} AS s WHERE $match)" +
                            " OR EXISTS (SELECT 1 FROM snap.${table.name} AS s WHERE $match AND ($differs))"
                    }
                    parkedRows += db.createStatement().use {
                        it.executeUpdate("INSERT INTO park.${table.name} SELECT n.* FROM main.${table.name} AS n$where")
                    }
                    if (!inSnapshot) continue
                    db.createStatement().use {
                        it.execute("CREATE TABLE park.${baseName(table)} AS SELECT * FROM snap.${table.name} WHERE 0")
                        it.executeUpdate(
                            """INSERT INTO park.${baseName(table)}
                               SELECT s.* FROM snap.${table.name} AS s
                               WHERE EXISTS (
                                 SELECT 1 FROM main.${table.name} AS n
                                 WHERE $match AND ($differs)
                               )""",
                        )
                    }
                }
            } finally {
                db.createStatement().use {
                    it.execute("DETACH DATABASE snap")
                    it.execute("DETACH DATABASE park")
                }
            }
        }
        return parkedRows
    }

    private fun reconcileExisting(db: Connection, table: ParkedTable, shared: List<String>) {
        val baseColumns = columns(db, "parked", baseName(table))
        val comparable = shared.filter { it in baseColumns }
        if (comparable.isEmpty() || !table.key.all { it in comparable }) return
        val keySelect = table.key.joinToString(", ") { "p.$it" }
        val exists = table.key.joinToString(" AND ") { "l.$it = p.$it" }
        val keys = db.createStatement().use { sql ->
            sql.executeQuery(
                """SELECT $keySelect FROM parked.${table.name} AS p
                   WHERE EXISTS (SELECT 1 FROM main.${table.name} AS l WHERE $exists)""",
            ).use { cursor ->
                buildList {
                    while (cursor.next()) add(List(table.key.size) { cursor.getString(it + 1) })
                }
            }
        }
        for (key in keys) {
            val current = row(db, "main", table.name, table.key, key, shared) ?: continue
            val parkedRow = row(db, "parked", table.name, table.key, key, shared) ?: continue
            if (current == parkedRow) continue
            val base = row(db, "parked", baseName(table), table.key, key, comparable)
            val sameBase = base != null && comparable.all { current[it] == base[it] }
            if (sameBase) updateRow(db, table, shared, key, parkedRow)
            else keepCopy(db, table.name, key, parkedRow)
        }
    }

    private fun row(
        db: Connection,
        schema: String,
        table: String,
        key: List<String>,
        keyValues: List<String>,
        wanted: List<String>,
    ): Map<String, String?>? {
        val where = key.joinToString(" AND ") { "$it = ?" }
        db.prepareStatement("SELECT ${wanted.joinToString(",")} FROM $schema.$table WHERE $where").use { sql ->
            keyValues.forEachIndexed { index, value -> sql.setString(index + 1, value) }
            sql.executeQuery().use { cursor ->
                if (!cursor.next()) return null
                return wanted.associateWith { column ->
                    val index = wanted.indexOf(column) + 1
                    if (cursor.getObject(index) == null) null else cursor.getString(index)
                }
            }
        }
    }

    private fun updateRow(
        db: Connection,
        table: ParkedTable,
        shared: List<String>,
        key: List<String>,
        values: Map<String, String?>,
    ) {
        val assignments = shared.joinToString(",") { "$it = ?" }
        val where = table.key.joinToString(" AND ") { "$it = ?" }
        db.prepareStatement("UPDATE ${table.name} SET $assignments WHERE $where").use { sql ->
            shared.forEachIndexed { index, column -> bind(sql, index + 1, values[column]) }
            key.forEachIndexed { index, value -> sql.setString(shared.size + index + 1, value) }
            sql.executeUpdate()
        }
    }

    private fun keepCopy(db: Connection, table: String, key: List<String>, values: Map<String, String?>) {
        val payload = buildJsonObject {
            for ((column, value) in values) {
                if (value == null) put(column, JsonNull) else put(column, value)
            }
        }.toString()
        db.prepareStatement(
            """INSERT OR IGNORE INTO rollback_copies(source_table, row_key, payload, kept_at) VALUES (?,?,?,?)""",
        ).use { sql ->
            sql.setString(1, table)
            sql.setString(2, key.joinToString("\u001f"))
            sql.setString(3, payload)
            sql.setLong(4, System.currentTimeMillis())
            sql.executeUpdate()
        }
    }

    private fun ensureCopies(db: Connection) {
        db.createStatement().use {
            it.execute(
                """CREATE TABLE IF NOT EXISTS rollback_copies (
                    copy_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_table TEXT NOT NULL,
                    row_key TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    kept_at INTEGER NOT NULL,
                    UNIQUE(source_table, row_key, payload)
                )""",
            )
        }
    }

    private fun bind(sql: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value == null) sql.setNull(index, java.sql.Types.VARCHAR) else sql.setString(index, value)
    }

    private fun baseName(table: ParkedTable) = "base_${table.name}"

    private fun versionNumber(version: String): Long? {
        if (!version.matches(Regex("""[1-9][0-9]{0,2}\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})"""))) return null
        val (major, minor, patch) = version.split('.').map(String::toLong)
        return if (major <= 255 && minor <= 255 && patch <= 65535) (major * 256 + minor) * 65536 + patch else null
    }

    private fun roomFor(directory: Path, database: Path): Boolean {
        val exams = directory.resolve("exams.sqlite")
        val bytes = Files.size(database) + (if (Files.isRegularFile(exams)) Files.size(exams) else 0L)
        return usableSpace(directory) >= bytes * 3 + 16L * 1024 * 1024
    }

    private fun capturePair(libraryDir: Path) {
        val rollback = libraryDir.resolve(".rollback")
        Files.createDirectories(rollback)
        vacuumInto(libraryDir.resolve("library.sqlite"), rollback.resolve("snapshot.sqlite"))
        val exams = libraryDir.resolve("exams.sqlite")
        if (Files.isRegularFile(exams)) {
            vacuumInto(exams, rollback.resolve("snapshot-exams.sqlite"))
            Files.writeString(rollback.resolve("exams.txt"), "present\n")
        } else {
            Files.deleteIfExists(rollback.resolve("snapshot-exams.sqlite"))
            Files.writeString(rollback.resolve("exams.txt"), "absent\n")
        }
    }

    private fun backupPair(libraryDir: Path, safety: Path) {
        if (Files.isDirectory(safety)) safety.toFile().deleteRecursively()
        Files.createDirectories(safety)
        vacuumInto(libraryDir.resolve("library.sqlite"), safety.resolve("library.sqlite"))
        val exams = libraryDir.resolve("exams.sqlite")
        if (Files.isRegularFile(exams)) vacuumInto(exams, safety.resolve("exams.sqlite"))
        else Files.writeString(safety.resolve("exams-absent"), "absent\n")
    }

    private fun restoreSnapshot(libraryDir: Path) {
        val rollback = libraryDir.resolve(".rollback")
        swapIn(rollback.resolve("snapshot.sqlite"), libraryDir.resolve("library.sqlite"))
        when (Files.readString(rollback.resolve("exams.txt")).trim()) {
            "present" -> swapIn(rollback.resolve("snapshot-exams.sqlite"), libraryDir.resolve("exams.sqlite"))
            "absent" -> deleteDatabase(libraryDir.resolve("exams.sqlite"))
        }
    }

    private fun undo(libraryDir: Path, safety: Path) {
        val saved = safety.resolve("library.sqlite")
        if (Files.isRegularFile(saved)) swapIn(saved, libraryDir.resolve("library.sqlite"))
        val exams = safety.resolve("exams.sqlite")
        if (Files.isRegularFile(exams)) swapIn(exams, libraryDir.resolve("exams.sqlite"))
        else if (Files.isRegularFile(safety.resolve("exams-absent"))) deleteDatabase(libraryDir.resolve("exams.sqlite"))
    }

    private fun swapIn(source: Path, target: Path) {
        deleteDatabase(target)
        Files.copy(source, target)
    }

    private fun deleteDatabase(target: Path) {
        Files.deleteIfExists(target)
        Files.deleteIfExists(Path.of("$target-wal"))
        Files.deleteIfExists(Path.of("$target-shm"))
    }

    private fun vacuumInto(source: Path, target: Path) {
        Files.deleteIfExists(target)
        Files.createDirectories(target.parent)
        sqlite(source) { db ->
            db.prepareStatement("VACUUM INTO ?").use { copy ->
                copy.setString(1, target.toAbsolutePath().toString())
                copy.execute()
            }
        }
        sqlite(target) { copy ->
            copy.createStatement().use { sql ->
                sql.executeQuery("PRAGMA integrity_check").use { rows ->
                    check(rows.next() && rows.getString(1) == "ok" && !rows.next())
                }
            }
        }
    }

    private fun sqlite(path: Path, block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            block(db)
        }
    }

    private fun tableExists(db: Connection, schema: String, name: String): Boolean =
        db.prepareStatement("SELECT 1 FROM $schema.sqlite_master WHERE type='table' AND name=?").use { sql ->
            sql.setString(1, name)
            sql.executeQuery().use { it.next() }
        }

    private fun columns(db: Connection, schema: String, table: String): List<String> =
        db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA $schema.table_info($table)").use { rows ->
                buildList {
                    while (rows.next()) {
                        val name = rows.getString("name")
                        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                        add(name)
                    }
                }
            }
        }

    private fun sqlPath(path: Path) = path.toAbsolutePath().toString().replace("'", "''")
}

private data class ParkedTable(val name: String, val key: List<String>)
