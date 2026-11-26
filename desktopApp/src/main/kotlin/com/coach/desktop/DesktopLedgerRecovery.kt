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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * Inspects an existing client `ledger.sqlite` without creating it. Only unacknowledged commands
 * written by [deviceId] count: rows another pairing left behind stay queued and untouched.
 * Returns null when this pairing has nothing to recover.
 */
internal fun inspectExistingLedgerQueue(ledgerPath: Path, deviceId: String): ExistingLedgerRecoveryInfo? {
    if (!Files.isRegularFile(ledgerPath)) return null
    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:${ledgerPath.toAbsolutePath()}").use { conn ->
            val tables = existingTableNames(conn)
            if ("outbox" !in tables) return null
            val courses = linkedSetOf<String>()
            var hasUnackedOutbox = false
            conn.prepareStatement("SELECT payload_bytes FROM outbox WHERE ack_seq IS NULL AND device_id=?").use { st ->
                st.setString(1, deviceId)
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        hasUnackedOutbox = true
                        val payload = rs.getString(1)
                        val body = runCatching { LedgerJson.decodeFromString<CommandBody>(payload) }.getOrNull()
                        body?.courseId?.takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
            }
            if (!hasUnackedOutbox) return null
            conn.createStatement().use { st ->
                if ("sync_meta" in tables) {
                    st.executeQuery("SELECT key FROM sync_meta WHERE key LIKE 'cursor:%'").use { rs ->
                        while (rs.next()) rs.getString(1).removePrefix("cursor:").takeIf { it.isNotBlank() }?.let(courses::add)
                    }
                }
            }
            ExistingLedgerRecoveryInfo(courses.toList(), hasUnackedOutbox)
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
 * Drains this pairing's queued learning commands from an existing `ledger.sqlite` while the
 * Windows bookshelf is open, without a restart. Scope (TON-1888):
 * - only an owner pairing has a ledger on the server; friend (Sable) identities never touch it;
 * - only commands written by the current device are uploaded; other pairings' rows stay queued;
 * - it uploads and stops; live ledger sync stays with the course session.
 */
internal class DesktopLedgerQueueRecovery(
    private val settings: DesktopConnectionSettings,
    private val identity: DesktopIdentity,
    private val pollMs: Long = 2_000L,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var driver: JdbcSqliteDriver? = null
    private var transport: HttpLedgerTransport? = null

    fun start() {
        if (identity.credentials.role != "owner") return
        val ledgerPath = settings.clientDirectory.resolve("ledger.sqlite")
        val deviceId = identity.credentials.deviceId
        val interval = pollMs.coerceIn(100L, 2_000L)
        scope.launch {
            var engine: SyncEngine? = null
            var engineCourses: List<String> = emptyList()
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch {
                CoachConnectivityRegistry.flowFor(identity.serverUrl).collectLatest { status ->
                    if (status == CoachConnectivityStatus.ONLINE) wake.trySend(Unit)
                }
            }
            while (isActive) {
                val info = inspectExistingLedgerQueue(ledgerPath, deviceId)
                if (info != null) {
                    if (engine == null || info.courses != engineCourses) {
                        engine = openEngine(ledgerPath, info.courses) ?: return@launch
                        engineCourses = info.courses
                    }
                    try { engine.syncOnce() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                withTimeoutOrNull(interval) { wake.receive() }
            }
        }
    }

    private fun openEngine(ledgerPath: Path, courses: List<String>): SyncEngine? = synchronized(this) {
        if (!scope.isActive) return null
        val sqliteDriver = driver ?: JdbcSqliteDriver(
            "jdbc:sqlite:${ledgerPath.toAbsolutePath()}",
            Properties(),
            ClientDatabase.Schema,
        ).also { driver = it }
        val ledgerTransport = transport ?: HttpLedgerTransport(
            identity.serverUrl,
            identity.credentials.deviceId,
            identity.credentials.token,
        ).also { transport = it }
        SyncEngine(
            db = ClientDatabase(sqliteDriver),
            deviceId = identity.credentials.deviceId,
            transport = ledgerTransport,
            courses = courses,
            newId = { UUID.randomUUID().toString() },
            hash = { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            },
            agentRunId = settings.agentRunId,
            uploadOwnCommandsOnly = true,
        )
    }

    override fun close() {
        val currentTransport: HttpLedgerTransport?
        val currentDriver: JdbcSqliteDriver?
        synchronized(this) {
            currentTransport = transport
            currentDriver = driver
            scope.cancel()
        }
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
