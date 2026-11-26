package com.coach.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.LedgerTransport
import com.coach.client.PairedCredentials
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import com.coach.domain.LedgerJson
import com.coach.domain.Projection
import com.coach.domain.json
import com.coach.protocol.CommandBody
import com.coach.protocol.EventsPage
import com.coach.protocol.Hello
import com.coach.protocol.Receipt
import com.coach.protocol.Snapshot
import com.coach.protocol.WireCommand
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TON-1888 scope: bookshelf recovery drains only the current pairing's queue.
 * The server mirrors device checks: commands of another device get 403, friends have no ledger (404).
 */
class DesktopLedgerRecoveryTest {
    private class LedgerServer(private val ownerDevice: String) : AutoCloseable {
        val requests = AtomicInteger(0)
        val received = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", ::handle)
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            requests.incrementAndGet()
            val body = exchange.requestBody.use { it.readBytes() }.decodeToString()
            val device = exchange.requestHeaders.getFirst("X-Coach-Device")
            val hello = Hello(epoch = "e1", lastSeq = 0, backedUpThroughSeq = 0, lastSeqBefore = 0)
            val path = exchange.requestURI.path
            val (status, resp) = when {
                device != ownerDevice -> 404 to "{}"
                path == "/v1/hello" -> 200 to LedgerJson.encodeToString(hello)
                path.endsWith("/snapshot") -> 200 to LedgerJson.encodeToString(
                    Snapshot(hello = hello, courseId = "C1", events = emptyList(), projection = Projection(lastSeq = 0)),
                )
                path.endsWith("/events") -> 200 to """{"epoch":"e1","events":[],"throughSeq":0,"hasMore":false}"""
                path == "/v1/commands" -> {
                    val cmd = LedgerJson.decodeFromString<WireCommand>(body)
                    received += cmd.commandId
                    if (cmd.deviceId != device) 403 to "{}"
                    else 200 to LedgerJson.encodeToString(
                        Receipt(commandId = cmd.commandId, status = "APPLIED", eventSeqs = listOf(1L), epoch = "e1", backedUpThroughSeq = 0L),
                    )
                }
                else -> 404 to "{}"
            }
            val bytes = resp.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() = server.stop(0)
    }

    private object NoTransport : LedgerTransport {
        override suspend fun hello(epoch: String?): Hello = error("offline")
        override suspend fun snapshot(course: String): Snapshot = error("offline")
        override suspend fun events(course: String, after: Long): EventsPage = error("offline")
        override suspend fun command(command: WireCommand): Receipt = error("offline")
        override suspend fun watch(onLedger: suspend () -> Unit) = error("offline")
    }

    /** Queues one command per device, as a ledger left behind by an earlier pairing would hold. */
    private fun seedLedger(ledger: Path, vararg devices: String): Map<String, String> {
        Files.createDirectories(ledger.parent)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${ledger.toAbsolutePath()}", Properties(), ClientDatabase.Schema)
        try {
            val db = ClientDatabase(driver)
            return devices.associateWith { device ->
                SyncEngine(db, device, NoTransport, listOf("C1"), { UUID.randomUUID().toString() },
                    { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } })
                    .enqueue(CommandBody("mark_read", "C1", 1_700_000_000_000L, json("week" to 1))).commandId
            }
        } finally {
            driver.close()
        }
    }

    private fun outboxRow(ledger: Path, commandId: String): Pair<Long?, String?> =
        DriverManager.getConnection("jdbc:sqlite:${ledger.toAbsolutePath()}").use { conn ->
            conn.prepareStatement("SELECT ack_seq, receipt_status FROM outbox WHERE command_id=?").use { st ->
                st.setString(1, commandId)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    (rs.getLong(1).takeUnless { rs.wasNull() }) to rs.getString(2)
                }
            }
        }

    private fun settingsIn(root: Path) = DesktopConnectionSettings(
        home = root,
        environment = { null },
        mac = false,
        property = { null },
    )

    @Test
    fun ownerRecoveryUploadsOnlyItsOwnQueueAndKeepsOtherPairingRowsQueued() {
        val root = Files.createTempDirectory("coach-ledger-recovery-").toRealPath()
        try {
            LedgerServer(ownerDevice = "new-pc").use { server ->
                val settings = settingsIn(root)
                val ledger = settings.clientDirectory.resolve("ledger.sqlite")
                val ids = seedLedger(ledger, "old-pc", "new-pc")
                val identity = DesktopIdentity(server.url, PairedCredentials("new-pc", "t".repeat(43), "owner"))
                DesktopLedgerQueueRecovery(settings, identity, pollMs = 100L).use { recovery ->
                    recovery.start()
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline && outboxRow(ledger, ids.getValue("new-pc")).first == null) Thread.sleep(100)
                    Thread.sleep(500)
                }
                assertEquals(1L to "APPLIED", outboxRow(ledger, ids.getValue("new-pc")))
                assertEquals(null to null, outboxRow(ledger, ids.getValue("old-pc")), "the other pairing's record stays queued")
                assertEquals(listOf(ids.getValue("new-pc")), server.received.distinct())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun friendBookshelfNeverTouchesTheOwnerLedger() {
        val root = Files.createTempDirectory("coach-ledger-recovery-friend-").toRealPath()
        try {
            LedgerServer(ownerDevice = "owner-pc").use { server ->
                val settings = settingsIn(root)
                val ledger = settings.clientDirectory.resolve("ledger.sqlite")
                val ids = seedLedger(ledger, "owner-pc", "friend-pc")
                val identity = DesktopIdentity(server.url, PairedCredentials("friend-pc", "t".repeat(43), "friend"))
                DesktopLedgerQueueRecovery(settings, identity, pollMs = 100L).use { recovery ->
                    recovery.start()
                    Thread.sleep(2_000)
                }
                assertEquals(0, server.requests.get(), "a friend identity makes no ledger requests")
                ids.values.forEach { assertNull(outboxRow(ledger, it).first) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
