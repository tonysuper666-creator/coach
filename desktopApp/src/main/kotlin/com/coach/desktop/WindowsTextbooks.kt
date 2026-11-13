package com.coach.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path

internal fun windowsLibraryDirectory(
    environment: (String) -> String? = System::getenv,
    property: (String) -> String? = System::getProperty,
    home: Path = Path.of(System.getProperty("user.home")),
): Path {
    val explicit = environment("COACH_LIBRARY_DIR")?.takeIf(String::isNotBlank)
    require(explicit != null || !developmentLaunch(environment, property)) {
        "Development runs require an isolated COACH_LIBRARY_DIR for the Windows textbook library."
    }
    val target = explicit?.let(Path::of)?.toAbsolutePath()?.normalize() ?: home.resolve("Coach/books")
    require(!developmentLaunch(environment, property) || target != home.resolve("Coach/books").toAbsolutePath().normalize()) {
        "Development runs must not open the learner's textbook library."
    }
    val learnerLibrary = home.resolve("Coach/books")
    require(!developmentLaunch(environment, property) || !Files.exists(target) || !Files.exists(learnerLibrary) || !Files.isSameFile(target, learnerLibrary)) {
        "Development runs must not open an alias of the learner's textbook library."
    }
    return target
}

/** Windows mainline: a new learner can bring a book without a preconfigured course or Mac pairing. */
internal fun runWindowsTextbooks() {
    val directory = windowsLibraryDirectory()
    application {
        val controller = remember { TextbookController(TextbookLibrary(directory)) }
        Window(onCloseRequest = ::exitApplication, title = "Coach", state = rememberWindowState(width = 1100.dp, height = 800.dp)) {
            TextbookWorkspace(controller, chooseBook = {
                withContext(Dispatchers.Swing) {
                    val dialog = FileDialog(window, "上传教材 · PDF / EPUB / Markdown / 文本", FileDialog.LOAD)
                    try {
                        dialog.isVisible = true
                        dialog.file?.let { Path.of(dialog.directory, it) }
                    } finally { dialog.dispose() }
                }
            })
            LaunchedEffect(window) { VerifyHooks.captures(window) }
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { delay(it * 1000); exitApplication() }
            }
        }
    }
}
