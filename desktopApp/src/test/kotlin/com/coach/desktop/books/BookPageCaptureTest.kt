package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Same self-authored fixture on main and the implementation, at physical desktop sizes. */
@OptIn(ExperimentalTestApi::class)
class BookPageCaptureTest {
    @Test fun lightAndDarkDesktopAndNarrowReader() {
        val previous = System.getProperty("coach.windows.theme")
        try {
            for (dark in listOf(false, true)) for ((width, height) in listOf(1920 to 1080, 2560 to 1440, 1280 to 800)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val root = Files.createTempDirectory("coach-page-capture-")
                try {
                    val file = root.resolve("阅读与理解.md")
                    Files.writeString(file, "# 第三章 阅读与理解\n\n" + (1..36).joinToString("\n\n") {
                        "读书是一种缓慢而主动的工作。第 $it 段把新知识接回已有经验，检查前后两句之间的关系。" +
                            "阅读时停下来提出问题，再回到原文确认答案。".repeat(4)
                    } + "\n\n# 第四章 回到原文\n\n" + "The reader returns to the source and checks the evidence. ".repeat(8))
                    val library = TextbookLibrary(root.resolve("library"))
                    val imported = library.importBook(file)
                    library.save(imported.copy(promptSeen = true))
                    val controller = TextbookController(library)
                    runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1.5f)) {
                        setContent { WindowsTextbookHost(controller) { null } }
                        waitUntil(timeoutMillis = 20_000) { controller.state.value.book != null && !controller.state.value.busy }
                        onNodeWithTag("home-read").performClick()
                        waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.prompt == null }
                        waitForIdle()
                        onNodeWithTag("reader-text").assertExists()
                        val output = Path.of("build/screens/pagination", "${if (dark) "dark" else "light"}-${width}x$height.png")
                        Files.createDirectories(output.parent)
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output.toFile())
                    }
                } finally { root.toFile().deleteRecursively() }
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
        }
    }
}
