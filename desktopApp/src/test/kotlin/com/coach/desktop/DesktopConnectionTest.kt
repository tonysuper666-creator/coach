package com.coach.desktop

import com.coach.client.PairedCredentials
import java.nio.file.Files
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class DesktopConnectionTest {
    private fun home() = Files.createTempDirectory("coach-desktop-test-").toRealPath()
    private val paired get() = PairedCredentials("paired-computer", "a".repeat(43))

    @Test fun cleanWindowsUsesLocalMacConfigurationWithoutEnvironment() {
        val home = home()
        val config = home.resolve("Coach/config/local.properties")
        Files.createDirectories(config.parent)
        Files.writeString(config, "coach.tailHost=100.64.0.2\ncoach.port=19876\ncoach.course=synthetic\n")
        val settings = DesktopConnectionSettings(home, { null }, mac = false)
        assertEquals("http://100.64.0.2:19876", settings.serverUrl())
        assertNull(DesktopPairing(settings).load())
        assertFalse(Files.exists(home.resolve("Coach/client/ledger.sqlite")))
        settings.saveAddress("https://mac.example:444")
        assertEquals("https://mac.example:444", DesktopConnectionSettings(home, { null }, false).serverUrl())
        val configValues = java.util.Properties().apply { Files.newInputStream(config).use { load(it) } }
        assertEquals("synthetic", configValues.getProperty("coach.course"))
        assertEquals("100.64.0.2", configValues.getProperty("coach.tailHost"))
    }

    @Test fun selectedAddressLeavesUtf8SharedConfigurationUnchanged() {
        for (mac in listOf(false, true)) {
            val home = home()
            val config = home.resolve("Coach/config/local.properties")
            Files.createDirectories(config.parent)
            val original = "# 本机教材配置，保留此注释\r\ncoach.textbookDir=/书/教材\r\n" +
                "coach.referenceUrl=http://textbook.example:8080\r\ncoach.tailHost=100.64.0.2\r\ncoach.port=19876\r\n"
            Files.writeString(config, original)
            val settings = DesktopConnectionSettings(home, { null }, mac)
            settings.saveAddress("https://selected-mac.example:444")
            assertContentEquals(original.toByteArray(Charsets.UTF_8), Files.readAllBytes(config))
            val lines = Files.readAllLines(config, Charsets.UTF_8)
            assertEquals("# 本机教材配置，保留此注释", lines.first())
            val values = lines.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("/书/教材", values["coach.textbookDir"])
            assertEquals("http://textbook.example:8080", values["coach.referenceUrl"])
            val reopened = DesktopConnectionSettings(home, { null }, mac)
            assertEquals("https://selected-mac.example:444", reopened.serverUrl())
        }
    }

    @Test fun missingWindowsConfigurationReturnsToConnectionAndNeverUsesAServerToken() {
        val home = home()
        val token = home.resolve("Coach/data/device-token")
        Files.createDirectories(token.parent)
        Files.writeString(token, "another-device-must-not-be-used")
        val settings = DesktopConnectionSettings(home, { null }, mac = false)
        assertNull(settings.serverUrl())
        assertNull(settings.nativeCredentials("http://127.0.0.1:18791"))
        assertNull(DesktopPairing(settings).load())
        settings.saveAddress("http://selected-mac.example:18791")
        assertFalse(Files.exists(home.resolve("Coach/config/local.properties")))
        assertEquals("http://selected-mac.example:18791", DesktopConnectionSettings(home, { null }, false).serverUrl())
    }

    @Test fun nativeMacKeepsItsLoopbackIdentityEvenWithTailHostConfigured() {
        val home = home()
        val token = home.resolve("Coach/data/device-token")
        Files.createDirectories(token.parent)
        Files.writeString(token, "local-server-token")
        val config = home.resolve("Coach/config/local.properties")
        Files.createDirectories(config.parent)
        Files.writeString(config, "coach.tailHost=100.64.0.2\n")
        val settings = DesktopConnectionSettings(home, { null }, mac = true)
        val identity = assertNotNull(DesktopPairing(settings).load())
        assertEquals("http://127.0.0.1:18791", identity.serverUrl)
        assertEquals("desktop", identity.credentials.deviceId)
        assertNull(settings.nativeCredentials("http://100.64.0.2:18791"))
        assertNull(settings.nativeCredentials("http://127.0.0.1:19876"))
        assertNull(settings.nativeCredentials("https://127.0.0.1:18791"))
        DesktopCredentials(settings.clientDirectory).clear()
        assertTrue(Files.exists(token))
    }

    @Test fun explicitIsolatedDevelopmentCredentialsStillWork() {
        val home = home()
        val token = home.resolve("test-token")
        Files.writeString(token, "synthetic-token")
        val env = mapOf("COACH_URL" to "http://127.0.0.1:19876", "COACH_TOKEN_FILE" to token.toString(), "COACH_DEVICE_ID" to "isolated-desktop")
        val settings = DesktopConnectionSettings(home, env::get, false)
        val identity = assertNotNull(DesktopPairing(settings).load())
        assertEquals("isolated-desktop", identity.credentials.deviceId)
        settings.saveAddress(identity.serverUrl)
        assertFalse(Files.exists(home.resolve("Coach/config/local.properties")))
    }

    @Test fun credentialsArePrivateAddressBoundAndSurviveReopening() {
        val directory = home().resolve("client")
        val store = DesktopCredentials(directory)
        store.saveAddress("http://mac.example:18791")
        store.save("http://mac.example:18791/", paired)
        val reopened = DesktopCredentials(directory)
        assertEquals(paired.deviceId, assertNotNull(reopened.load("http://MAC.example:18791")).deviceId)
        assertNull(reopened.load("http://other.example:18791"))
        assertNull(reopened.load("http://mac.example:18792"))
        val posix = Files.getFileAttributeView(store.file, PosixFileAttributeView::class.java)
        if (posix != null) assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions())
        else {
            val entries = Files.getFileAttributeView(store.file, AclFileAttributeView::class.java).acl
            assertEquals(1, entries.size)
            assertEquals(AclEntryType.ALLOW, entries.single().type())
            assertEquals(store.file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name")), entries.single().principal())
        }
        store.clear()
        assertNull(reopened.load("http://mac.example:18791"))
        assertEquals("http://mac.example:18791", reopened.savedAddress())
    }

    @Test fun invalidCredentialPathFailsWithoutOverwritingIt() {
        val store = DesktopCredentials(home().resolve("client"))
        Files.createDirectories(store.file)
        assertFails { store.save("http://mac.example:18791", paired) }
        assertTrue(Files.isDirectory(store.file))
        assertFails { store.load("http://mac.example:18791") }
    }

    @Test fun addressesRejectCredentialsQueriesPathsAndInvalidPorts() {
        assertEquals("http://mac.example:18791", desktopServerUrl("mac.example"))
        assertEquals("https://mac.example:443", desktopServerUrl("https://mac.example"))
        for (address in listOf("http://user:password@mac.example:18791", "http://mac.example:18791?token=secret", "http://mac.example:18791/path", "http://mac.example:0", "http://mac.example:99999")) {
            assertFails { desktopServerUrl(address) }
        }
    }
}
