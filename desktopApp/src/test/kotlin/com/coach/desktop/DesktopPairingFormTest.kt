package com.coach.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.server.ChatService
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * Offscreen Compose input. The address and code go in through performTextInput,
 * and pairing is requested through performClick. The test does not assign the
 * gate's state and does not call DesktopPairing.pair itself.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPairingFormTest {
    @Test fun typedAddressAndCodeRejectThenPairAndReopenKeepsTheSameDevice() {
        Harness().use { service ->
            val home = Files.createTempDirectory("coach-desktop-form-")
            val settings = DesktopConnectionSettings(home, { null }, mac = false)
            val store = DesktopCredentials(settings.clientDirectory)
            val good = service.issueCode()
            val bad = if (good == "111111") "222222" else "111111"
            assertNotEquals(good, bad)
            val devicesBefore = service.deviceNames()
            var pairedDevice: String? = null
            runComposeUiTest {
                show(settings) { pairedDevice = it.credentials.deviceId }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("连接你的 Mac").fetchSemanticsNodes().isNotEmpty() }
                typeInto("Mac 地址", service.url)
                assertEquals(service.url, editable("Mac 地址").enteredText())
                onNode(hasText("配对") and hasClickAction()).assertIsNotEnabled()
                typeInto("配对码", bad)
                assertEquals("\u2022".repeat(6), editable("配对码").enteredText())
                val pair = onNode(hasText("配对") and hasClickAction())
                pair.assertIsEnabled()
                pair.performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("未能保存配对。", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("连接你的 Mac").assertExists()
                assertFalse(Files.exists(store.file))
                assertEquals(devicesBefore, service.deviceNames())

                pairedDevice = null
                show(settings) { pairedDevice = it.credentials.deviceId }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("连接你的 Mac").fetchSemanticsNodes().isNotEmpty() }
                typeInto("Mac 地址", service.url)
                assertEquals(service.url, editable("Mac 地址").enteredText())
                typeInto("配对码", good)
                assertEquals("\u2022".repeat(6), editable("配对码").enteredText())
                onNode(hasText("配对") and hasClickAction()).performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("desktop-paired").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodesWithText("连接你的 Mac").fetchSemanticsNodes().isEmpty())
            }
            val device = checkNotNull(pairedDevice)
            assertTrue(service.deviceNames().contains("Windows 电脑"))
            assertEquals(device, deviceId(store))
            val saved = Files.readAllBytes(store.file)
            pairedDevice = null
            runComposeUiTest {
                show(settings) { pairedDevice = it.credentials.deviceId }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("desktop-paired").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("desktop-paired").assertExists()
                assertTrue(onAllNodesWithText("连接你的 Mac").fetchSemanticsNodes().isEmpty())
                assertTrue(onAllNodesWithText("配对码").fetchSemanticsNodes().isEmpty())
            }
            assertEquals(device, pairedDevice)
            assertContentEquals(saved, Files.readAllBytes(store.file))
            println("FORM_INPUT=performClick+performTextInput HEAD=98497e3b FORM_RESULT=paired-reopen-kept-device")
        }
    }

    @Composable
    private fun Gate(settings: DesktopConnectionSettings, onPaired: (DesktopIdentity) -> Unit) {
        Box(Modifier.size(840.dp, 720.dp)) {
            DesktopPairingGate(settings) { identity ->
                onPaired(identity)
                Text("已进入学习", modifier = Modifier.testTag("desktop-paired"))
            }
        }
    }

    private fun ComposeUiTest.show(settings: DesktopConnectionSettings, onPaired: (DesktopIdentity) -> Unit) {
        setContent { Gate(settings, onPaired) }
    }

    private fun ComposeUiTest.editable(label: String): SemanticsNodeInteraction {
        if (onAllNodes(hasSetTextAction() and hasText(label)).fetchSemanticsNodes().size == 1) {
            return onNode(hasSetTextAction() and hasText(label))
        }
        if (onAllNodes(hasSetTextAction() and hasAnyAncestor(hasText(label)), useUnmergedTree = true).fetchSemanticsNodes().size == 1) {
            return onNode(hasSetTextAction() and hasAnyAncestor(hasText(label)), useUnmergedTree = true)
        }
        error("Compose text field '$label' was not found")
    }

    private fun ComposeUiTest.typeInto(label: String, value: String) {
        val field = editable(label)
        field.performClick()
        field.performTextInput(value)
    }

    private fun SemanticsNodeInteraction.enteredText(): String =
        checkNotNull(fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
}

private val forbiddenPorts = setOf(18791, 8791, 8790, 3790, 19540)

private class Harness : AutoCloseable {
    private val root = Files.createTempDirectory("coach-desktop-form-server-")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admin = "synthetic-form-admin-token-0123456789"
    private val actor = LedgerActor(scope, LedgerStore(root).also { it.enroll("desktop", admin) })
    private val bridgePort = freePort()
    private val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        ledgerRoutes(
            actor,
            MutableSharedFlow(extraBufferCapacity = 8),
            ChatService(
                actor,
                scope,
                bridge = BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:$bridgePort", productionKeyFile = null, testTraffic = true)),
                packsDirectory = root.resolve("packs"),
                log = {},
            ),
        )
    }.apply { start(false) }
    val port = runBlocking { engine.engine.resolvedConnectors().single().port }
    val url = "http://127.0.0.1:$port"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    init {
        check(port !in forbiddenPorts && bridgePort !in forbiddenPorts && bridgePort != port) {
            "Refusing a production or collided port"
        }
        val hello = request("GET", "/v1/hello", auth = false)
        check(hello.statusCode() == 401) { "Synthetic server did not require a token" }
    }

    fun issueCode(): String {
        val response = request("POST", "/v1/pairing-code")
        check(response.statusCode() == 200) { "pairing-code status ${response.statusCode()}" }
        return Regex(""""code"\s*:\s*"(\d{6})"""").find(response.body())?.groupValues?.get(1)
            ?: error("Synthetic server did not return a six-digit code")
    }

    fun deviceNames(): List<String> {
        val response = request("GET", "/v1/devices")
        check(response.statusCode() == 200) { "devices status ${response.statusCode()}" }
        return Regex(""""name"\s*:\s*"([^"]*)"""").findAll(response.body()).map { it.groupValues[1] }.toList()
    }

    private fun request(method: String, path: String, auth: Boolean = true): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url + path)).timeout(Duration.ofSeconds(5))
        if (auth) {
            builder.header("X-Coach-Device", "desktop")
            builder.header("Authorization", "Bearer $admin")
        }
        return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() {
        runBlocking { engine.stopSuspend(100, 500); actor.close() }
        scope.cancel()
        root.toFile().deleteRecursively()
    }
}

private fun freePort(): Int {
    val port = ServerSocket(0).use { it.localPort }
    check(port !in forbiddenPorts) { "Refusing production port $port" }
    return port
}

private fun deviceId(store: DesktopCredentials): String {
    val values = java.util.Properties()
    Files.newInputStream(store.file).use { values.load(it) }
    return checkNotNull(values.getProperty("deviceId"))
}
