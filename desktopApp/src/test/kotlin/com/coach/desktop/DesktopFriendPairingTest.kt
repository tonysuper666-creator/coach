package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.CourseHttp
import com.coach.client.ServerReachability
import com.coach.desktop.books.CoachConnectivityRegistry
import com.coach.desktop.books.CoachConnectivityStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopFriendPairingTest {
    @Test fun pairingReopenOfflineAndRevocationNeverProbeTheOwnerLedger() = runBlocking {
        val root = Files.createTempDirectory("friend-pairing-").toRealPath()
        val paths = CopyOnWriteArrayList<String>()
        var status = 200
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            exchange.requestBody.readAllBytes()
            val pair = exchange.requestURI.path == "/v1/pair"
            val body = (if (pair) """{"deviceId":"friend-pc","token":"${"a".repeat(43)}","role":"friend"}"""
                else """{"online":true,"calls":0}""").toByteArray()
            exchange.sendResponseHeaders(if (pair) 200 else status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        val url = "http://127.0.0.1:${http.address.port}"
        val settings = DesktopConnectionSettings(root, { null }, mac = false,
            property = { if (it == "jpackage.app-path") "/synthetic/Coach.exe" else null })
        try {
            val pairing = DesktopPairing(settings)
            val identity = pairing.pair(url, "123456", "Synthetic PC")
            assertEquals("friend", identity.credentials.role)
            val reopened = assertNotNull(DesktopPairing(settings).load())
            assertEquals("friend", reopened.credentials.role)
            assertEquals(identity.credentials.deviceId, reopened.credentials.deviceId)
            status = 503
            CoachConnectivityRegistry.resetForTests()
            assertTrue(pairing.stillAuthorized(reopened)) // An API outage is not revocation.
            assertEquals(CoachConnectivityStatus.ONLINE, CoachConnectivityRegistry.statusFor(reopened.serverUrl))
            assertFalse(CoachConnectivityRegistry.isOffline(reopened.serverUrl))
            assertFailsWith<IllegalArgumentException> { DesktopLearningSession(settings, reopened, "owner-course") }
            status = 401
            assertFalse(pairing.stillAuthorized(reopened))
            assertNull(pairing.load())
            assertEquals(listOf("/v1/pair", "/v1/public/status", "/v1/public/status", "/v1/public/status"), paths.toList())
            assertEquals("https://synthetic.example:443", desktopServerUrl("https://synthetic.example"))
        } finally { http.stop(0); root.toFile().deleteRecursively() }
    }

    @Test fun rawIdentityKeepsFriendRoleAndNon401HttpDoesNotMarkOffline() = runBlocking {
        val root = Files.createTempDirectory("friend-role-").toRealPath()
        val paths = CopyOnWriteArrayList<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            exchange.requestBody.readAllBytes()
            val code = if (exchange.requestURI.path == "/v1/public/status") 503 else 404
            val body = """{"online":false}""".toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        val url = desktopServerUrl("http://127.0.0.1:${http.address.port}")
        val clientDir = root.resolve("client")
        val pairingDir = clientDir.resolve("pairing")
        Files.createDirectories(pairingDir)
        val token = "a".repeat(43)
        fun writeIdentity(roleLine: String?) {
            val text = buildString {
                append("server=").append(url).append('\n')
                append("deviceId=friend-pc\n")
                append("token=").append(token).append('\n')
                if (roleLine != null) append(roleLine).append('\n')
            }
            Files.writeString(pairingDir.resolve("credentials.properties"), text, StandardCharsets.UTF_8)
        }
        val settings = DesktopConnectionSettings(root, { key ->
            when (key) {
                "COACH_URL" -> url
                "COACH_CLIENT_DIR" -> clientDir.toString()
                else -> null
            }
        }, mac = false, property = { if (it == "jpackage.app-path") "/synthetic/Coach.exe" else null })
        try {
            writeIdentity("role=friend")
            val friend = assertNotNull(DesktopPairing(settings).load())
            assertEquals("friend", friend.credentials.role)
            CoachConnectivityRegistry.resetForTests()
            paths.clear()
            assertTrue(DesktopPairing(settings).stillAuthorized(friend))
            assertEquals(CoachConnectivityStatus.ONLINE, CoachConnectivityRegistry.statusFor(friend.serverUrl))
            assertFalse(CoachConnectivityRegistry.isOffline(friend.serverUrl))
            assertEquals(listOf("/v1/public/status"), paths.toList())
            val course = CourseHttp(url, friend.credentials.deviceId, friend.credentials.token, "library", 1_500L)
            try {
                assertEquals(ServerReachability.ONLINE, course.probeReachability("friend"))
            } finally { course.close() }
            assertNotNull(DesktopPairing(settings).load())

            writeIdentity(null)
            val owner = assertNotNull(DesktopPairing(settings).load())
            assertEquals("owner", owner.credentials.role)
            CoachConnectivityRegistry.resetForTests()
            paths.clear()
            assertTrue(DesktopPairing(settings).stillAuthorized(owner))
            assertEquals(CoachConnectivityStatus.ONLINE, CoachConnectivityRegistry.statusFor(owner.serverUrl))
            assertFalse(CoachConnectivityRegistry.isOffline(owner.serverUrl))
            assertEquals(listOf("/v1/hello"), paths.toList())
            val ownerProbe = CourseHttp(url, owner.credentials.deviceId, owner.credentials.token, "library", 1_500L)
            try {
                assertEquals(ServerReachability.ONLINE, ownerProbe.probeReachability("owner"))
            } finally { ownerProbe.close() }
            val kept = assertNotNull(DesktopPairing(settings).load())
            assertEquals("owner", kept.credentials.role)
            assertEquals(token, kept.credentials.token)
        } finally { http.stop(0); root.toFile().deleteRecursively() }
    }

    @Test fun friendRouteShowsLocalBooksInBothThemes() {
        val old = System.getProperty("coach.windows.theme")
        try {
            for (theme in listOf("light", "dark")) {
                val root = Files.createTempDirectory("friend-books-")
                System.setProperty("coach.windows.theme", theme)
                try {
                    for (route in listOf("before", "friend")) runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
                        // Baseline is the existing local bookshelf; the new friend route reuses it.
                        setContent { if (route == "before") WindowsTextbooksContent(root) { null } else FriendCourseContent(root) { null } }
                        onNodeWithTag("upload-textbook").assertIsDisplayed()
                        onNodeWithTag("windows-host").assertIsDisplayed()
                        val output = Path.of("build/screens/friends").toFile().also { it.mkdirs() }.resolve("$route-$theme.png")
                        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", output))
                    }
                } finally { root.toFile().deleteRecursively() }
            }
        } finally {
            if (old == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", old)
        }
    }
}
