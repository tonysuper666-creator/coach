package com.coach.desktop.books

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class BookshelfPolishTest {
    @Test fun shelfAtWindowsSizesAndScrollToLastBook() {
        val titles = listOf("关于阅读与思考的一本很长很长的中文书名：从日常练习到知识的形成", "植物与四季", "阅读的方式", "纸上的世界", "观察的练习", "最后一本书")
        val books = titles.mapIndexed { index, title -> BookSummary("sample-$index", title, 120 + index, 1, 12, index == 0) }
        val book = Textbook(books[0].id, books[0].title, "md", listOf(BookPage(1, "隔离样例")), readingPage = 12, learning = true)
        for ((width, height) in listOf(1920 to 1080, 2560 to 1440)) for (dark in listOf(false, true)) {
            var selected = ""
            val actions = mutableListOf<String>()
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1.5f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        CoachTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                LibraryHome(LibraryUi(catalog = books, book = book), { actions += "upload" }, { selected = it },
                                    { actions += "read" }, { actions += "continue" }, { actions += it.name })
                            }
                        }
                    }
                }
                fun shot(name: String) {
                    val path = Path.of("build/screens/ton1668/$width-$height-${if (dark) "dark" else "light"}-$name.png")
                    Files.createDirectories(path.parent)
                    val image = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
                    assertEquals(width, image.width); assertEquals(height, image.height)
                    assertTrue(ImageIO.write(image, "png", path.toFile()))
                }
                waitForIdle()
                shot("top")
                onNodeWithTag("upload-textbook").performScrollTo().performClick()
                onNodeWithTag("home-learn").performScrollTo().performClick()
                onNodeWithTag("home-read").performScrollTo().performClick()
                onNodeWithTag("home-guided").performScrollTo().performClick()
                onNodeWithTag("home-question").performScrollTo().performClick()
                assertEquals(listOf("upload", "continue", "read", "READ_AND_LEARN", "QUESTION_FIRST"), actions)
                onNodeWithTag("book-sample-5").performScrollTo().assertIsDisplayed()
                onNodeWithTag("shelf-sheet").performTouchInput { swipeUp() }
                waitForIdle()
                shot("bottom")
                onNodeWithTag("book-sample-5").performClick()
                assertEquals("sample-5", selected)
            }
        }
    }
}
