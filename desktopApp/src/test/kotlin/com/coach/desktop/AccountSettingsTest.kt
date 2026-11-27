package com.coach.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.ui.CoachTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AccountSettingsTest {
    @Test fun accountAndDataFitNarrowWindowInBothThemes() {
        val home = Files.createTempDirectory("coach-settings-ui")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/session") { request ->
            val body = """{"nickname":"示例读者","method":"Sable","maskedEmail":"r***@example.org"}""".toByteArray()
            request.sendResponseHeaders(200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val identity = DesktopIdentity("http://127.0.0.1:${server.address.port}", PairedCredentials("demo", "a".repeat(43)))
            val profile = LearningProfile(home, null)
            val output = Files.createDirectories(Path.of("build/screens/account-settings"))
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                var switching: Boolean? = null
                runSkikoComposeUiTest(size = Size(680f, 760f), density = Density(1f)) {
                    var enhanced by mutableStateOf(false)
                    setContent {
                        CoachTheme(dark) {
                            var result by remember { mutableStateOf("") }
                            var reports by remember { mutableStateOf(true) }
                            CompositionLocalProvider(
                                LocalAccountSettings provides if (!enhanced) null else {
                                    { AccountSection(identity, home) { switching = it } }
                                },
                                LocalDataSettings provides if (!enhanced) null else {
                                    { DataSection(profile, remember { SyncDisplay(home) }, result,
                                        { result = "已经是最新版本" }, reports, { reports = it }) }
                                },
                                LocalSettingsDirectoryOverride provides Path.of(System.getProperty("user.home"), "Coach", "books"),
                            ) { HostSettings(directory = home, onClose = {}) }
                        }
                    }
                    waitForIdle()
                    assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", output.resolve("$theme-before.png").toFile()))
                    runOnIdle { enhanced = true }
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("示例读者").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("r***@example.org").assertIsDisplayed()
                    fun capture(name: String) {
                        waitForIdle()
                        assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", output.resolve("$theme-$name.png").toFile()))
                    }
                    capture("account")
                    onNodeWithText("切换账号").performClick()
                    runOnIdle { assertEquals(true, switching) }
                    onNodeWithText("退出登录").performClick()
                    runOnIdle { assertEquals(false, switching) }
                    onNodeWithText("检查更新").performScrollTo().performClick()
                    onNodeWithText("已经是最新版本").performScrollTo().assertIsDisplayed()
                    onNodeWithText("打开文件夹").performScrollTo().assertIsDisplayed()
                    val reports = onNodeWithContentDescription("问题上报提醒").performScrollTo()
                    reports.assertIsOn().performClick()
                    reports.assertIsOff()
                    capture("data")
                }
            }
        } finally {
            server.stop(0)
            home.toFile().deleteRecursively()
        }
    }
}
