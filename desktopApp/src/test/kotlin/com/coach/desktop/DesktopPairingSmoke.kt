package com.coach.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.LedgerJson
import com.coach.domain.str
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import java.net.ServerSocket
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses the same settings, pairing, persistence and revocation code as the installed UI. */
object DesktopPairingSmoke {
    private fun fingerprint(identity: DesktopIdentity): String = MessageDigest.getInstance("SHA-256")
        .digest("${identity.serverUrl}\u0000${identity.credentials.deviceId}\u0000${identity.credentials.token}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        if (args.first() == "reopen") {
            check(listOf("COACH_URL", "COACH_TOKEN_FILE", "COACH_DEVICE_ID").all { System.getenv(it) == null })
            val settings = DesktopConnectionSettings(Path.of(args[1]), { null }, mac = false)
            val pairing = DesktopPairing(settings)
            val identity = checkNotNull(pairing.load())
            check(fingerprint(identity) == args[2]) { "Pairing changed across client process restart" }
            check(pairing.stillAuthorized(identity))
            println("REOPEN_AUTHORIZED")
            return@runBlocking
        }
        val root = Path.of(args.single())
        val work = Files.createDirectories(root.resolve("desktopApp/build/pairing-smoke/${UUID.randomUUID()}"))
        val clientHome = Files.createDirectories(work.resolve("client-home"))
        val serverHome = Files.createDirectories(work.resolve("server-home"))
        val tokenFile = work.resolve("server-data/device-token")
        val port = ServerSocket(0).use { it.localPort }
        check(port !in productionPorts)
        val bridgePort = ServerSocket(0).use { it.localPort }
        check(bridgePort !in productionPorts && bridgePort != port)
        val url = "http://127.0.0.1:$port"
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        var server: Process? = null
        val checks = mutableListOf<String>()
        fun passed(name: String) { checks += name; println("PASS $name") }
        fun request(method: String, path: String, credentials: PairedCredentials? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(url + path)).timeout(Duration.ofSeconds(5))
            if (credentials != null) builder.header("Authorization", "Bearer ${credentials.token}").header("X-Coach-Device", credentials.deviceId)
            return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        }
        fun stopServer() {
            server?.let { child ->
                child.destroy()
                if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); check(child.waitFor(10, TimeUnit.SECONDS)) }
            }
            server = null
        }
        suspend fun startServer() {
            val builder = ProcessBuilder(java, "-Duser.home=$serverHome", "-cp", root.resolve("server/app/build/install/app/lib").toString() + File.separator + "*", "com.coach.server.MainKt")
            builder.environment().apply {
                keys.filter { it.startsWith("COACH_") }.toList().forEach(::remove)
                put("COACH_DATA_DIR", work.resolve("server-data").toString())
                put("COACH_TOKEN_FILE", tokenFile.toString())
                put("COACH_DEVICE_ID", "smoke-native-desktop")
                put("COACH_PORT", port.toString())
                put("COACH_TAIL_HOST", "")
                put("COACH_BRIDGE_URL", "http://127.0.0.1:$bridgePort")
                put("COACH_TRAFFIC", "test")
                put("COACH_MOCK_DIR", work.resolve("empty-materials").toString())
                put("COACH_GENERATED_DIR", work.resolve("generated").toString())
            }
            server = builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("server.log").toFile())).start()
            withTimeout(30_000) {
                while (true) {
                    check(checkNotNull(server).isAlive) { "Isolated server exited; see private smoke log" }
                    if (runCatching { request("GET", "/v1/hello").statusCode() == 401 }.getOrDefault(false)) break
                    delay(200)
                }
            }
        }
        try {
            val config = clientHome.resolve("Coach/config/local.properties")
            Files.createDirectories(config.parent)
            val originalConfig = "# 本机教材配置，保留此注释\r\ncoach.textbookDir=/书/教材\r\n" +
                "coach.referenceUrl=http://textbook.example:8080\r\ncoach.tailHost=127.0.0.1\r\ncoach.port=$port\r\ncoach.course=synthetic\r\n"
            Files.writeString(config, originalConfig)
            val settings = DesktopConnectionSettings(clientHome, { null }, mac = false)
            var pairing = DesktopPairing(settings)
            check(pairing.load() == null && settings.serverUrl() == url)
            check(!Files.exists(settings.clientDirectory.resolve("ledger.sqlite")))
            passed("clean-windows-config-without-environment-or-ledger")
            startServer()
            val admin = PairedCredentials("smoke-native-desktop", Files.readString(tokenFile).trim())
            fun code(): String {
                val response = request("POST", "/v1/pairing-code", admin)
                check(response.statusCode() == 200)
                return LedgerJson.parseToJsonElement(response.body()).jsonObject.str("code")
            }
            val firstCode = code()
            val identity = pairing.pair(checkNotNull(settings.serverUrl()), firstCode, "Synthetic Windows computer")
            check(identity.credentials.deviceId != admin.deviceId && identity.credentials.token != admin.token)
            check(originalConfig.toByteArray(Charsets.UTF_8).contentEquals(Files.readAllBytes(config)))
            val configLines = Files.readAllLines(config, Charsets.UTF_8)
            check(configLines.first() == "# 本机教材配置，保留此注释")
            val configValues = configLines.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            check(configValues["coach.textbookDir"] == "/书/教材")
            check(configValues["coach.referenceUrl"] == "http://textbook.example:8080")
            passed("shared-utf8-values-comments-and-line-endings-unchanged-after-real-pairing")
            val other = PairingClient(url)
            try {
                check(runCatching { other.pair(firstCode, "reused-code") }.isFailure)
                val second = other.pair(code(), "another-synthetic-device")
                check(second.deviceId != identity.credentials.deviceId && second.token != identity.credentials.token)
            } finally { other.close() }
            passed("real-single-use-pairing-independent-device-identities")
            check(request("POST", "/v1/pairing-code", identity.credentials).statusCode() == 403)
            passed("paired-computer-cannot-manage-other-devices")

            val transport = HttpLedgerTransport(url, identity.credentials.deviceId, identity.credentials.token)
            try {
                Files.createDirectories(settings.clientDirectory)
                val driver = JdbcSqliteDriver("jdbc:sqlite:${settings.clientDirectory.resolve("ledger.sqlite")}", Properties(), ClientDatabase.Schema)
                try {
                    val sync = SyncEngine(ClientDatabase(driver), identity.credentials.deviceId, transport, listOf("synthetic"),
                        { UUID.randomUUID().toString() }, { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
                    sync.syncOnce()
                    check(sync.connection.value == "online")
                    check(transport.snapshot("synthetic").courseId == "synthetic")
                } finally { driver.close() }
                val opened = CompletableDeferred<Unit>()
                val watch = launch { transport.watch(onOpen = { opened.complete(Unit) }, onLedger = {}) }
                try { withTimeout(10_000) { opened.await() } } finally { watch.cancelAndJoin() }
            } finally { transport.close() }
            passed("real-http-ledger-sync-and-websocket-handshake")
            val store = DesktopCredentials(settings.clientDirectory)
            val before = Files.readAllBytes(store.file)
            val hash = fingerprint(identity)
            val reopen = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), DesktopPairingSmoke::class.java.name, "reopen", clientHome.toString(), hash)
            reopen.environment().keys.filter { it.startsWith("COACH_") }.toList().forEach { reopen.environment().remove(it) }
            val childLog = work.resolve("reopen.log")
            val child = reopen.redirectErrorStream(true).redirectOutput(childLog.toFile()).start()
            try { check(child.waitFor(30, TimeUnit.SECONDS) && child.exitValue() == 0) { "Client process restart failed; see private smoke log" } }
            finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) } }
            check(Files.readString(childLog).contains("REOPEN_AUTHORIZED"))
            passed("fresh-client-process-reuses-persisted-identity-without-environment")

            stopServer()
            pairing = DesktopPairing(DesktopConnectionSettings(clientHome, { null }, mac = false))
            val offline = checkNotNull(pairing.load())
            check(pairing.stillAuthorized(offline))
            check(before.contentEquals(Files.readAllBytes(store.file)))
            passed("offline-preserves-credential-bytes-and-identity")
            startServer()
            check(pairing.stillAuthorized(offline))
            passed("server-restart-retains-paired-device")
            check(store.load("http://127.0.0.1:${if (port == 65535) port - 1 else port + 1}") == null)
            passed("server-address-change-never-forwards-saved-credentials")
            check(request("DELETE", "/v1/devices/${identity.credentials.deviceId}", admin).statusCode() == 204)
            check(request("GET", "/v1/hello", identity.credentials).statusCode() == 401)
            withTimeout(10_000) { pairing.awaitRevocation(offline) }
            check(pairing.load() == null && !Files.exists(store.file))
            check(DesktopConnectionSettings(clientHome, { null }, false).serverUrl() == url)
            check(Files.exists(tokenFile))
            passed("real-revocation-401-returns-to-pairing-without-touching-native-token")
            val repaired = pairing.pair(url, code(), "Repaired synthetic computer")
            check(repaired.credentials.deviceId != identity.credentials.deviceId)
            check(pairing.stillAuthorized(repaired))
            check(originalConfig.toByteArray(Charsets.UTF_8).contentEquals(Files.readAllBytes(config)))
            passed("normal-repairing-obtains-a-new-independent-identity")
            val receipt = checks.joinToString("\n", prefix = "Desktop pairing smoke: ${checks.size} passed\n", postfix = "\nidentitySha256=$hash\n")
            val report = root.resolve("desktopApp/build/reports/pairing-smoke.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, receipt)
            println("PAIRING_SMOKE_PASSED=${checks.size} identitySha256=$hash")
        } finally { stopServer() }
    }
}
