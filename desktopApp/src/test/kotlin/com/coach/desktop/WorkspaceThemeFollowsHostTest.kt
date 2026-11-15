package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import coach.design.tokens.CoachV7
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1605: 书架外壳和正文必须落在同一个 V7 主题上。
 *
 * 用真实宿主 [WindowsTextbookHost] 离屏渲染，只翻转两件外部事实：系统的明暗，和
 * `coach.windows.theme` 的显式覆盖。两处读数都在像素上：外壳的案头色和书架正文 Surface
 * 左上角的案头色（浅 #E8E2D6 / 深 #0E0D0B）。一明一暗时两者不相等。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class WorkspaceThemeFollowsHostTest {
    private val lightDesk = CoachV7.Light.desk
    private val darkDesk = CoachV7.Dark.desk

    /** 外壳浅、系统深：正文必须跟外壳浅，而不是跟系统深。 */
    @Test fun hostLightKeepsTheWorkspaceLightWhenTheSystemIsDark() {
        val shot = render("host-light-system-dark", systemDark = true, override = "light")
        assertEquals(lightDesk, shot.host, "宿主自己的案头已是浅色")
        assertEquals(shot.host, shot.workspace, "书架正文与外壳同一个主题")
        assertEquals(lightDesk, shot.workspace)
    }

    /** 外壳深、系统浅：反向组合同样跟外壳。 */
    @Test fun hostDarkKeepsTheWorkspaceDarkWhenTheSystemIsLight() {
        val shot = render("host-dark-system-light", systemDark = false, override = "dark")
        assertEquals(darkDesk, shot.host, "宿主自己的案头已是深色")
        assertEquals(shot.host, shot.workspace, "书架正文与外壳同一个主题")
        assertEquals(darkDesk, shot.workspace)
    }

    /** 没写 coach.windows.theme 时宿主跟系统走，正文也跟同一个选择走。 */
    @Test fun withoutAnExplicitHostThemeTheWorkspaceFollowsTheSystem() {
        for (systemDark in listOf(true, false)) {
            val shot = render("system-${if (systemDark) "dark" else "light"}", systemDark = systemDark, override = null)
            assertEquals(shot.host, shot.workspace, "系统${if (systemDark) "深" else "浅"}时正文与外壳同色")
        }
    }

    private data class Shot(val host: Color, val workspace: Color)

    /** Offscreen only: no window, no real desktop theme switch, no existing user library. */
    private fun render(name: String, systemDark: Boolean, override: String?): Shot {
        val root = Files.createTempDirectory("coach-workspace-theme-")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val previous = System.getProperty("coach.windows.theme")
        if (override == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", override)
        try {
            var shot = Shot(Color.Unspecified, Color.Unspecified)
            runSkikoComposeUiTest(size = Size(1600f, 1000f), density = Density(2f)) {
                setContent {
                    CompositionLocalProvider(
                        LocalSystemTheme provides if (systemDark) SystemTheme.Dark else SystemTheme.Light) {
                        WindowsTextbookHost(controller) { null }
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("windows-textbooks").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                val hostImage = onNodeWithTag("windows-host").captureToImage()
                val workspaceImage = onNodeWithTag("windows-textbooks").captureToImage()
                shot = Shot(hostImage.toPixelMap()[5, 5], workspaceImage.toPixelMap()[2, 2])
                val output = File("build/screens/ton1605").apply { mkdirs() }
                ImageIO.write(workspaceImage.toAwtImage(), "png", File(output, "$name.png"))
            }
            println("TON-1605 $name system=${if (systemDark) "dark" else "light"} override=$override " +
                "host=${shot.host.toHex()} workspace=${shot.workspace.toHex()}")
            return shot
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme")
            else System.setProperty("coach.windows.theme", previous)
        }
    }

    private fun Color.toHex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
