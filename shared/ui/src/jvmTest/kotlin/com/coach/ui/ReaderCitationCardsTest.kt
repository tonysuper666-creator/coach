package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.coach.domain.*
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReaderCitationCardsTest {
    @Test fun longOriginalKeepsReturnReachableAtPhoneAndDesktopWindowSizes() {
        for ((width, height) in listOf(360 to 640, 900 to 700)) runDesktopComposeUiTest(width, height) {
            setContent { MaterialTheme { Surface { Column {
                Text("Synthetic reading state")
                ReaderCitationCards(JsonArray(listOf(json("anchor" to "S1B1", "docId" to "synthetic", "pdfPage" to 1,
                    "mode" to "block", "text" to "Synthetic long paragraph. ".repeat(500)))), { _, _ -> null })
            } } } }
            onNodeWithTag("reader-source-S1B1").performClick()
            onNodeWithTag("reader-source-content").assertIsDisplayed()
            onNodeWithText("返回追问").assertIsDisplayed().performClick()
            onNodeWithText("Synthetic reading state").assertIsDisplayed()
        }
    }

    @Test fun actualClickDrawsBlockAndFallbackWithoutNetworkAndReturnsToTheSameQuestion() = runComposeUiTest {
        val samples = CopyOnWriteArrayList<Pair<String, Long>>()
        var requests = 0
        val sources = JsonArray(listOf(
            json("anchor" to "S1B1", "docId" to "synthetic", "pdfPage" to 2, "mode" to "block", "text" to "Synthetic pears grow here."),
            json("anchor" to "S2", "docId" to "synthetic", "pdfPage" to 3, "mode" to "page", "text" to "Synthetic whole page.")))
        setContent {
            MaterialTheme { Column {
                Text("Synthetic question remains")
                ReaderCitationCards(sources, loadPage = { _, _ -> requests++; error("inline snapshots must not fetch") },
                    onCardDrawn = { mode, ms -> samples += mode to ms })
            } }
        }
        for ((id, text) in listOf("S1B1" to "Synthetic pears grow here.", "S2" to "Synthetic whole page.")) {
            onNodeWithTag("reader-source-$id").performClick()
            onNodeWithTag("reader-source-content").assertTextEquals(text).assertIsDisplayed()
            val out = File("build/screens/reader-citation-$id.png").also { it.parentFile.mkdirs() }
            ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", out)
            if (id == "S2") onNodeWithText("这一段的位置无法确认，显示已核对的整页。").assertIsDisplayed()
            waitUntil { samples.size == if (id == "S2") 2 else 1 }
            onNodeWithText("返回追问").performClick()
            onNodeWithTag("reader-source-content").assertDoesNotExist()
            onNodeWithText("Synthetic question remains").assertIsDisplayed()
        }
        assertEquals(0, requests)
        assertEquals(listOf("block", "page"), samples.map { it.first })
        assertTrue(samples.all { it.second >= 0 })
        println("CITATION_SYNTHETIC_CLICK samples=${samples.size} modes=${samples.map { it.first }} elapsed_ms=${samples.map { it.second }}")
    }

    @Test fun legacyPageFetchAndMissingResourceAreSeparateFromBlockHits() = runComposeUiTest {
        val samples = CopyOnWriteArrayList<Pair<String, Long>>()
        var requests = 0
        setContent { MaterialTheme { Column {
            ReaderCitationCards(JsonArray(listOf(json("anchor" to "S1", "docId" to "legacy", "pdfPage" to 1),
                json("anchor" to "S2", "docId" to "missing", "pdfPage" to 2))), loadPage = { doc, _ ->
                requests++; if (doc == "legacy") "Synthetic legacy page." else null
            }, onCardDrawn = { mode, ms -> samples += mode to ms })
        } } }
        onNodeWithTag("reader-source-S1").performClick()
        onNodeWithTag("reader-source-content").assertTextEquals("Synthetic legacy page.").assertIsDisplayed()
        onNodeWithTag("reader-source-content").captureToImage()
        waitUntil { samples.size == 1 }
        onNodeWithText("返回追问").performClick()
        onNodeWithTag("reader-source-S2").performClick()
        onNodeWithTag("reader-source-missing").assertIsDisplayed()
        onNodeWithTag("reader-source-content").assertDoesNotExist()
        onNodeWithTag("reader-source-missing").captureToImage()
        waitUntil { samples.size == 2 }
        onNodeWithText("返回追问").performClick()
        onNodeWithTag("reader-source-S1").assertIsDisplayed()
        assertEquals(2, requests)
        assertEquals(listOf("page", "missing"), samples.map { it.first })
        println("CITATION_SYNTHETIC_LEGACY modes=${samples.map { it.first }} elapsed_ms=${samples.map { it.second }}")
    }
}
