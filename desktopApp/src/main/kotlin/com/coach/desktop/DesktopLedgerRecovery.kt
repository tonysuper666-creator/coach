package com.coach.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.HttpLedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import com.coach.desktop.books.CoachConnectivityRegistry
import com.coach.desktop.books.CoachConnectivityStatus
import com.coach.desktop.books.LocalConnectivityPollMs
import com.coach.domain.LedgerJson
import com.coach.protocol.CommandBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID

internal data class ExistingLedgerRecoveryInfo(
    val courses: List<String>,
    val hasUnackedOutbox: Boolean,
)

/**
 * Inspects an existing client `ledger.sqlite` without creating it so Windows bookshelf sessions
 * can drain queued offline commands automatically once connectivity returns.
 */
internal fun inspectExistingLedgerQueue(ledgerPath: Path): ExistingLedgerRecoveryInfo? {
    if (!Files.isRegularFile(ledgerPath)) return null
    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:${ledgerPath.toAbsolutePath()}").use { conn ->
            val tables = existingTableNames(conn)
            if ("outbox" !in tables) return null
            val courses = linkedSetOf<String>()
            var hasUnackedOutbox = false
            conn.createStatement().use { st ->
                st.executeQuery("SELECT payload_bytes FROM outbox WHERE ack_seq IS NULL").use { rs ->
                    while (rs.next()) {
                        hasUnackedOutbox = true
                        val payload = rs.getString(1)
                        val body = runCatching { LedgerJson.decodeFromString<CommandBody>(payload) }.getOrNull()
                        body?.courseId?.takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
                if ("replica" in tables) {
                    st.executeQuery("SELECT DISTINCT course_id FROM replica").use { rs ->
                        while (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
                if ("projection_cache" in tables) {
                    st.executeQuery("SELECT DISTINCT course_id FROM projection_cache").use { rs ->
                        while (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
                if ("offline_packs" in tables) {
                    st.executeQuery("SELECT DISTINCT course_id FROM offline_packs").use { rs ->
                        while (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
                if ("sync_meta" in tables) {
                    st.executeQuery("SELECT key FROM sync_meta WHERE key LIKE 'cursor:%'").use { rs ->
                        while (rs.next()) rs.getString(1).removePrefix("cursor:").takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
            }
            if (!hasUnackedOutbox && courses.isEmpty()) null
            else ExistingLedgerRecoveryInfo(courses.toList(), hasUnackedOutbox)
        }
    }.getOrNull()
}

private fun existingTableNames(conn: Connection): Set<String> {
    val result = hashSetOf<String>()
    conn.createStatement().use { st ->
        st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
            while (rs.next()) result += rs.getString(1)
        }
    }
    return result
}

/**
 * Owns background [SyncEngine] recovery when a Windows bookshelf identity has an existing
 * `ledger.sqlite` on disk, draining queued learning commands without requiring a restart.
 */
internal class DesktopLedgerQueueRecovery(
    private val settings: DesktopConnectionSettings,
    private val identity: DesktopIdentity,
    private val pollMs: Long = 2_000L,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var driver: JdbcSqliteDriver? = null
    private var transport: HttpLedgerTransport? = null
    private var sync: SyncEngine? = null
    private var syncJob: Job? = null

    fun start() {
        val ledgerPath = settings.clientDirectory.resolve("ledger.sqlite")
        scope.launch {
            val info = awaitExistingLedger(ledgerPath) ?: return@launch
            val sqliteDriver = JdbcSqliteDriver(
                "jdbc:sqlite:${ledgerPath.toAbsolutePath()}",
                Properties(),
                ClientDatabase.Schema,
            )
            val ledgerTransport = HttpLedgerTransport(
                identity.serverUrl,
                identity.credentials.deviceId,
                identity.credentials.token,
            )
            val engine = SyncEngine(
                db = ClientDatabase(sqliteDriver),
                deviceId = identity.credentials.deviceId,
                transport = ledgerTransport,
                courses = info.courses,
                newId = { UUID.randomUUID().toString() },
                hash = { bytes ->
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                },
                agentRunId = settings.agentRunId,
            )
            synchronized(this@DesktopLedgerQueueRecovery) {
                if (!scope.isActive) {
                    runCatching { ledgerTransport.close() }
                    runCatching { sqliteDriver.close() }
                    return@launch
                }
                driver = sqliteDriver
                transport = ledgerTransport
                sync = engine
                syncJob = engine.start(scope)
            }
            scope.launch {
                CoachConnectivityRegistry.flowFor(identity.serverUrl).collectLatest { status ->
                    if (status == CoachConnectivityStatus.ONLINE) {
                        runCatching { engine.syncOnce() }
                    }
                }
            }
        }
    }

    private suspend fun awaitExistingLedger(ledgerPath: Path): ExistingLedgerRecoveryInfo? {
        val checkIntervalMs = pollMs.coerceIn(100L, 2_000L)
        while (scope.isActive) {
            val found = withContext(Dispatchers.IO) { inspectExistingLedgerQueue(ledgerPath) }
            if (found != null) return found
            delay(checkIntervalMs)
        }
        return null
    }

    override fun close() {
        val currentTransport: HttpLedgerTransport?
        val currentDriver: JdbcSqliteDriver?
        synchronized(this) {
            currentTransport = transport
            currentDriver = driver
            sync = null
            syncJob = null
        }
        scope.cancel()
        runCatching { currentTransport?.close() }
        runBlocking { scope.coroutineContext[Job]?.join() }
        runCatching { currentDriver?.close() }
    }
}

@Composable
internal fun RememberDesktopLedgerRecovery(
    settings: DesktopConnectionSettings,
    identity: DesktopIdentity,
) {
    val pollMs = LocalConnectivityPollMs.current
    val recovery = remember(settings, identity, pollMs) {
        DesktopLedgerQueueRecovery(settings, identity, pollMs)
    }
    DisposableEffect(recovery) {
        recovery.start()
        onDispose { recovery.close() }
    }
}
