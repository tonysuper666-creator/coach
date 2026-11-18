package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.awt.Color
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookVisualCaptureTest {
    @Test fun importedFigureAndCaptionOnDesktopPaper() {
        val root = Files.createTempDirectory("coach-figure-paper-")
        val previous = System.getProperty("coach.windows.theme")
        try {
            val caption = "图 1　同一株幼苗在六周内的高度"
            val file = root.resolve("观察札记.html")
            val image = BufferedImage(340, 124, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(176, 179, 162)
                for (y in 20..100 step 20) g.drawLine(10, y, 330, y)
                g.stroke = BasicStroke(2.5f)
                g.color = Color(66, 108, 78)
                val x = intArrayOf(17, 77, 137, 197, 257, 317)
                val y = intArrayOf(99, 92, 77, 58, 33, 14)
                g.drawPolyline(x, y, x.size)
                x.indices.forEach { g.fillOval(x[it] - 3, y[it] - 3, 7, 7) }
            } finally { g.dispose() }
            ImageIO.write(image, "png", root.resolve("growth.png").toFile())
            val paragraph = "每周的读数都来自同一株幼苗。把标记的位置连起来，我们就能看到叶尖在不同时段的变化。"
            Files.writeString(file, "<article><p>每周三早晨，记录一次叶尖的高度。</p><img src='growth.png' alt='$caption'>" +
                (1..30).joinToString("") { "<p>$paragraph</p>" } + "</article>")
            for (dark in listOf(false, true)) for ((width, height) in listOf(1920 to 1080, 2560 to 1440, 1280 to 800)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val library = TextbookLibrary(root.resolve("library-$dark-$width"))
                val imported = library.importBook(file)
                assertEquals(caption, imported.pages.flatMap { it.illustrations }.single().caption)
                library.save(imported.copy(promptSeen = true))
                val controller = TextbookController(library)
                runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1.5f)) {
                    setContent { WindowsTextbookHost(controller) { null } }
                    waitUntil(timeoutMillis = 20_000) { controller.state.value.book != null && !controller.state.value.busy }
                    onNodeWithTag("home-read").performClick()
                    waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER }
                    waitForIdle()
                    onNodeWithTag("reader-text").assertExists()
                    val item = imported.pages.flatMap { it.illustrations }.single()
                    val blockTag = "book-block-${item.page}-${item.order}"
                    if (onAllNodesWithTag(blockTag).fetchSemanticsNodes().isEmpty()) {
                        onNodeWithTag("next-page").assertIsEnabled().performClick()
                        waitForIdle()
                    }
                    onNodeWithTag(blockTag).assertIsDisplayed()
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription(caption, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(caption).assertIsDisplayed()
                    waitForIdle()
                    val mode = if (onAllNodesWithTag("book-sheet-1").fetchSemanticsNodes().isEmpty()) "single" else "spread"
                    val output = Path.of("build/screens/page-blocks", "${if (dark) "dark" else "light"}-${width}x$height-$mode.png")
                    Files.createDirectories(output.parent)
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output.toFile())
                }
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
            root.toFile().deleteRecursively()
        }
    }
}
