package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import kotlin.test.Test

/** Runs unchanged on PR239/242: the ordinary entry offers no optional paired connection. */
@OptIn(ExperimentalTestApi::class)
class WindowsPracticeEntryRedTest {
    @Test fun ordinaryBookEntryOffersPairingWithoutBlockingLocalBooks() {
        val root = Files.createTempDirectory("windows-practice-entry-")
        try {
            runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
                setContent { WindowsTextbooksContent(root.resolve("library")) { null } }
                onNodeWithTag("windows-host").assertExists()
                onNodeWithTag("open-settings").performClick()
                onNodeWithTag("book-practice-connect").assertExists()
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
