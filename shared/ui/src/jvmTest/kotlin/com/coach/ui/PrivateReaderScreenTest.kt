package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PrivateReaderScreenTest {
    private class FakeReader(private val stale: Boolean = false) : AutoCloseable {
        val pages = CopyOnWriteArrayList<Int>()
        val auth = CopyOnWriteArrayList<String>()
        private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(30, 40, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/C/reader") { ex ->
                auth += ex.requestHeaders.getFirst("Authorization").orEmpty()
                val path = ex.requestURI.path
                val image = path.endsWith("/image")
                val bytes = if (image) {
                    pages += path.substringBeforeLast('/').substringAfterLast('/').toInt()
                    png
                } else when {
                    path.endsWith("/state") -> """{"document":{"version":"v1"},"progress":{"version":"${if (stale) "old" else "v1"}","pdfPage":2,"stale":$stale}}""".toByteArray()
                    else -> """{"documents":[{"id":"synthetic","title":"Synthetic book","version":"v1","pages":4}]}""".toByteArray()
                }
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test-device", "synthetic-token", "C")
        override fun close() { http.close(); server.stop(0) }
    }

    @Test fun phoneRestoresSameVersionAndTurnsPages() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("第 2 页", substring = true)
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-prev").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(fake.auth.all { it == "Bearer synthetic-token" })
        }
    }

    @Test fun wideSpreadShowsTwoImagesAndStopsAtLastPage() = FakeReader().use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-spread").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-3").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-2").assertIsDisplayed()
            onNodeWithTag("private-next").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-4").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-next").assertIsNotEnabled()
        }
    }

    @Test fun staleProgressIsNotAppliedToNewVersion() = FakeReader(stale = true).use { fake ->
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PrivateReaderScreen(fake.http.reader) {} } } } }
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-book-synthetic").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-book-synthetic").performClick()
            waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("旧版本的阅读位置未自动套用，请重新定位。").assertIsDisplayed()
            assertFalse(2 in fake.pages)
        }
    }
}
