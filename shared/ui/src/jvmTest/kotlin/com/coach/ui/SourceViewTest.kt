package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** 「看原文」：看完返回，讲解页的状态原样；资料页不等章节列表就显示锚点页；取不到时如实说。 */
@OptIn(ExperimentalTestApi::class)
class SourceViewTest {
    /** Stand-in for the study pane: a counter that only survives if the pane stays composed (draft, chosen option, running stream). */
    private class Harness {
        var reading by mutableStateOf(false)
        var split by mutableStateOf(false)
        var progress by mutableStateOf(0)
        var composed = 0 // times the pane entered the composition (a restart or a drop-and-recreate counts)
    }

    private fun ComposeUiTest.mount(h: Harness) = setContent {
        val pane = remember { movableContentOf {
            var typed by remember { mutableStateOf(0) }
            DisposableEffect(Unit) { h.composed++; onDispose { } }
            androidx.compose.foundation.layout.Column {
                Text("typed=$typed", Modifier.testTag("pane"))
                androidx.compose.material3.Button(onClick = { typed++; h.progress++ }, modifier = Modifier.testTag("type")) { Text("type") }
            }
        } }
        Box(Modifier.size(900.dp, 600.dp)) {
            if (h.split) SourceSplit(study = { pane() }, source = { Text("原文", Modifier.testTag("source")) })
            else SourceOverlay(covered = h.reading, base = { pane() }, overlay = { Text("原文", Modifier.testTag("source")) })
        }
    }

    @Test fun overlayKeepsTheStudyPaneComposedAndHidesItFromReaders() = runComposeUiTest {
        val h = Harness(); mount(h); waitForIdle()
        repeat(3) { onNodeWithTag("type").performClick() }
        onNodeWithTag("pane").assertTextEquals("typed=3")
        runOnIdle { h.reading = true }; waitForIdle()
        onNodeWithTag("source").assertExists()
        onNodeWithTag("pane").assertDoesNotExist() // cleared semantics: invisible to readers, still composed
        runOnIdle { h.reading = false }; waitForIdle()
        onNodeWithTag("source").assertDoesNotExist()
        onNodeWithTag("pane").assertTextEquals("typed=3")
        assertEquals(1, h.composed, "the pane never restarted")
    }

    @Test fun splitAndBackKeepProgressAcrossLayoutChanges() = runComposeUiTest {
        val h = Harness(); mount(h); waitForIdle()
        repeat(2) { onNodeWithTag("type").performClick() }
        runOnIdle { h.reading = true }; waitForIdle()
        runOnIdle { h.split = true }; waitForIdle() // window widens to Pad landscape while reading
        onNodeWithTag("source").assertExists(); onNodeWithTag("pane").assertTextEquals("typed=2")
        onNodeWithTag("type").performClick()
        runOnIdle { h.split = false; h.reading = false }; waitForIdle()
        onNodeWithTag("pane").assertTextEquals("typed=3")
        assertEquals(1, h.composed)
    }

    // --- resources page against a real HTTP server: page first, list optional, honest failure ---

    private class Fake(val listWorks: Boolean = true, val pageWorks: Boolean = true) {
        val pageHits = AtomicInteger(); val listHits = AtomicInteger()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/C/sources") { ex ->
                val path = ex.requestURI.path
                val (code, body) = when {
                    path.endsWith("/sources") -> { listHits.incrementAndGet(); if (listWorks) 200 to """{"docs":[{"docId":"textbook:2","title":"第二章.md","firstPdfPage":30,"lastPdfPage":60,"firstBookPage":1,"lastBookPage":31}]}""" else 503 to "{}" }
                    path.contains("/pages/") -> { pageHits.incrementAndGet(); val n = path.substringAfterLast('/').toInt(); if (pageWorks) 200 to """{"docId":"textbook:2","pdfPage":$n,"bookPage":${n - 29},"text":"这是第 $n 页的正文。"}""" else 503 to "{}" }
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray(); ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http get() = CourseHttp("http://127.0.0.1:${server.address.port}", "dev", "tok", "C")
        fun stop() = server.stop(0)
    }

    private fun controller(http: CourseHttp?) = CoachController(
        courseId = "C", sync = unused(), tutor = unused(), formatClock = { "" }, course = http,
    )

    /** These screens never touch the sync engine or the tutor client; allocate without running a constructor. */
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(T::class.java) as T
    }

    private fun ComposeUiTest.showResources(fake: Fake?, jump: SourceJump?): CoachController {
        val c = controller(fake?.http)
        setContent { CoachTheme(false) { ResourcesScreen(c, jump, onBack = {}) } }
        return c
    }

    @Test fun jumpShowsTheAnchorPageEvenWhenTheChapterListFails() {
        val fake = Fake(listWorks = false)
        try {
            runComposeUiTest {
                showResources(fake, SourceJump("textbook:2", 41))
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("这是第 41 页的正文。", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("source-page-label").assertTextContains("PDF 第 41 页", substring = true)
                onNodeWithTag("source-page-label").assertTextContains("书页 p.12", substring = true)
            }
        } finally { fake.stop() }
    }

    @Test fun prefetchedPageNeedsNoSecondRequestAndShowsAtOnce() {
        val fake = Fake()
        try {
            runComposeUiTest {
                val c = controller(fake.http)
                kotlinx.coroutines.runBlocking { prefetchSourcePage(c, "textbook:2", 41) }
                assertEquals(1, fake.pageHits.get())
                setContent { CoachTheme(false) { ResourcesScreen(c, SourceJump("textbook:2", 41), onBack = {}) } }
                waitForIdle()
                onNodeWithText("这是第 41 页的正文。", substring = true).assertExists() // no wait: it was already on the device
                assertEquals(1, fake.pageHits.get())
            }
        } finally { fake.stop() }
    }

    @Test fun unreachableMacSaysSoInsteadOfShowingAPlaceholder() {
        val fake = Fake(listWorks = false, pageWorks = false)
        try {
            runComposeUiTest {
                showResources(fake, SourceJump("textbook:2", 41))
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("这一页取不到", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("重试").assertExists()
                // The header still names the requested page in this state, so a device check must not take it as proof the text arrived.
                onNodeWithTag("source-page-label").assertTextContains("PDF 第 41 页", substring = true)
                onNodeWithText("这是第", substring = true).assertDoesNotExist()
            }
        } finally { fake.stop() }
    }

    @Test fun versionWithoutTheResourceClientSaysSo() = runComposeUiTest {
        showResources(null, SourceJump("textbook:2", 41))
        waitForIdle()
        onNodeWithText("这个版本没有接入资料浏览。").assertExists()
    }
}
