package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.books.CoachConnectivityRegistry
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.incident.IncidentLog
import com.coach.desktop.incident.IncidentStore
import com.coach.ui.CoachTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TON-1985 第 2 项：异常退出只在顶栏角落留一个徽标，点开才有 查看 / 发送 / 不发 / 不再提示。
 * 「不再提示」写进本机设置后徽标不再出现，但问题记录本身留着；入口退回「设置 → 数据与关于 → 问题记录」，
 * 那里还能把提醒重新打开，也能看/清本地记录。任何时候都不替用户发出去。
 */
@OptIn(ExperimentalTestApi::class)
class HomeIncidentNeverAskTest {
    @Test
    fun neverAskHidesTheBadgeForGoodButKeepsTheRecordAndSendsNothing() {
        CoachConnectivityRegistry.resetForTests()
        val root = Files.createTempDirectory("coach-ton1985-never-ask-").toRealPath()
        val uploaded = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.use { it.readBytes() }
                val path = ex.requestURI.path.orEmpty()
                val (code, body) = when (path) {
                    "/v1/public/status" -> 200 to "{}"
                    "/v1/incidents" -> {
                        uploaded += path
                        204 to ""
                    }
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, if (code == 204) -1L else bytes.size.toLong())
                if (code != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val incidentDir = Files.createDirectories(root.resolve("incidents"))
            // 上一次被强杀留下的记录。
            val prior = IncidentStore(incidentDir, pid = { 1_234_567L })
            assertNull(prior.beginSession())
            prior.recordImportFailure("synthetic prior failure")
            IncidentLog.startSession(home) { key ->
                if (key == "COACH_INCIDENT_DIR") incidentDir.toString() else null
            }
            assertNotNull(IncidentLog.currentOffer())

            val serverUrl = "http://127.0.0.1:${server.address.port}"
            val settings = DesktopConnectionSettings(
                home = home,
                environment = { null },
                mac = false,
                property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
            )
            val creds = DesktopCredentials(settings.clientDirectory)
            creds.saveAddress(serverUrl)
            creds.save(serverUrl, PairedCredentials("ton1985-never-ask", "t".repeat(43), "friend"))

            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            Files.writeString(root.resolve("sample.md"), "# 第一章\n\n合成的一行正文，用来看书架。\n")
            library.importBook(root.resolve("sample.md"))
            val controller = TextbookController(library)

            val offers = ReportOffersState(settings.clientDirectory)
            val marker = settings.clientDirectory.resolve("disable-report-offers")
            assertFalse(Files.exists(marker), "默认要问，标记不存在")
            assertTrue(offers.enabled)

            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                setContent {
                    val state = remember { offers }
                    WindowsTextbookGate(
                        settings,
                        darkTheme = false,
                        offerReports = state.enabled,
                        onDisableOffers = { state.set(false) },
                    ) {
                        WindowsTextbookHost(controller) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isNotEmpty()
                }

                // 动作藏在徽标后面：页面上不占位置。
                assertTrue(onAllNodesWithTag("incident-never-ask").fetchSemanticsNodes().isEmpty())
                onNodeWithTag("incident-exit-prompt").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-never-ask").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-never-ask").performClick()

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(Files.exists(marker), "「不再提示」要落到本机设置里")
                assertFalse(offers.enabled)
                assertNotNull(IncidentLog.currentOffer(), "只是不再问，记录没被删，也没被发出去")
                assertTrue(prior.incidentFiles().isNotEmpty(), "本地问题记录还在")
                assertTrue(uploaded.isEmpty(), "「不再提示」不是「替我发出去」")
            }
        } finally {
            server.stop(0)
            CoachConnectivityRegistry.resetForTests()
            IncidentLog.resetForTest()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun settingsKeepsThePassiveEntryAfterNeverAskAndCanTurnTheBadgeBackOn() {
        val root = Files.createTempDirectory("coach-ton1985-passive-").toRealPath()
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val settings = DesktopConnectionSettings(home = home, environment = { null }, mac = false)
            val marker = settings.clientDirectory.resolve("disable-report-offers")
            // 已经点过「不再提示」。
            ReportOffersState(settings.clientDirectory).set(false)
            assertTrue(Files.exists(marker))

            var openedFolder = 0
            var cleared = 0
            val profile = LearningProfile(home, null)
            val offers = ReportOffersState(settings.clientDirectory)

            runSkikoComposeUiTest(size = Size(680f, 900f), density = Density(1f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(
                            LocalIncidentFolderActions provides IncidentFolderActions(
                                openFolder = { openedFolder++ },
                                clearRecords = { cleared++ },
                            ),
                            LocalDataSettings provides {
                                DataSection(
                                    profile,
                                    remember { SyncDisplay(home) },
                                    "",
                                    {},
                                    offers.enabled,
                                    { offers.set(it) },
                                )
                            },
                            LocalSettingsDirectoryOverride provides Path.of(System.getProperty("user.home"), "Coach", "books"),
                        ) { HostSettings(directory = home, onClose = {}) }
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("settings-page").fetchSemanticsNodes().isNotEmpty()
                }

                // 徽标不再出现，但被动入口还在：设置里能看记录、能清记录。
                val reports = onNodeWithContentDescription("问题上报提醒").performScrollTo()
                reports.assertIsOff()
                onNodeWithTag("settings-incident-open").performScrollTo().assertIsDisplayed()
                onNodeWithTag("settings-incident-clear").performScrollTo().assertIsDisplayed()
                onNodeWithTag("settings-incident-open").performClick()
                runOnIdle { assertTrue(openedFolder == 1) }

                // 想重新被问就在同一个开关上打开，标记一并删掉。
                onNodeWithContentDescription("问题上报提醒").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { !Files.exists(marker) }
                runOnIdle { assertTrue(offers.enabled) }
                onNodeWithContentDescription("问题上报提醒").assertIsOn()
                runOnIdle { assertTrue(cleared == 0, "没有确认就不清记录") }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
