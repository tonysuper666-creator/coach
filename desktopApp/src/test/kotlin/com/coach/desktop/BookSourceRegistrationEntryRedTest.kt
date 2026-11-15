package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.syntheticTextbook
import java.nio.file.Files
import kotlin.test.Test

/** Uses only the PR251 public surface too; old red must be the missing production form. */
@OptIn(ExperimentalTestApi::class)
class BookSourceRegistrationEntryRedTest {
    @Test fun ordinaryImportedPdfOffersAnExplicitSourceRegistrationForm() {
        val root = Files.createTempDirectory("book-source-form-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            library.importBook(syntheticTextbook(root.resolve("source")))
            val settings = DesktopConnectionSettings(root, environment = {
                when (it) { "COACH_URL" -> "http://127.0.0.1:1"; "COACH_CLIENT_DIR" -> root.resolve("client").toString(); else -> null }
            }, mac = false, property = { null })
            runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
                setContent { WindowsTextbooksContent(library.directory, settings) { null } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("open-settings").performClick()
                onNodeWithTag("book-source-confirm").assertExists()
                onNodeWithTag("book-source-register").assertExists().assertIsNotEnabled()
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
