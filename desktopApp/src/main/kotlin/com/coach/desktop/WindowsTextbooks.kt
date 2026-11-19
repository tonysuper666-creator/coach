package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.coach.desktop.books.exam.*
import com.coach.desktop.books.AnswerDrafts
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.rememberAnswerDrafts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** An optional per-process override; without it the existing system preference remains authoritative. */
internal fun windowsThemeOverride(value: String?): Boolean? = when {
    value.equals("dark", ignoreCase = true) -> true
    value.equals("light", ignoreCase = true) -> false
    else -> null
}

/** Windows mainline: a new learner can bring a book without a preconfigured course or Mac pairing. */
internal fun runWindowsTextbooks() {
    val directory = windowsLibraryDirectory()
    val initialBounds = windowsInitialWindowBounds()
    application {
        val controller = remember { TextbookController(TextbookLibrary(directory)) }
        val drafts = rememberAnswerDrafts(controller)
        val exams = rememberLocalExamController(controller.library)
        val scope = rememberCoroutineScope()
        val closeGuard = remember(drafts, exams, scope) {
            TextbookCloseGuard({ drafts.flush() && exams.flush() }, scope, ::exitApplication)
        }
        Window(onCloseRequest = closeGuard::requestClose, title = "LearningCoach", icon = AppIcon.window(),
            state = rememberWindowState(
                width = initialBounds.width.dp, height = initialBounds.height.dp,
                position = WindowPosition.Absolute(initialBounds.x.dp, initialBounds.y.dp),
            )) {
            LaunchedEffect(window) { window.applyWindowsReadingMinimum() }
            WindowsTextbookHost(controller, drafts, exams, chooseBook = {
                withContext(Dispatchers.Swing) {
                    val dialog = FileDialog(window, "上传教材 · ${com.coach.desktop.books.BookFormats.description}", FileDialog.LOAD)
                    try {
                        dialog.isVisible = true
                        dialog.file?.let { Path.of(dialog.directory, it) }
                    } finally { dialog.dispose() }
                }
            })
            TextbookCloseWarning(closeGuard)
            LaunchedEffect(window) { VerifyHooks.captures(window) }
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { delay(it * 1000); closeGuard.requestClose() }
            }
        }
    }
}

internal class TextbookCloseGuard(
    private val flushDrafts: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val close: () -> Unit,
) {
    constructor(drafts: AnswerDrafts, scope: CoroutineScope, close: () -> Unit) : this(drafts::flush, scope, close)

    var failed by mutableStateOf(false)
        private set
    private var closing = false

    fun requestClose() {
        if (closing) return
        closing = true
        failed = false
        scope.launch {
            try {
                if (flushDrafts()) close() else failed = true
            } finally { closing = false }
        }
    }

    fun dismiss() { failed = false }
}

@Composable
internal fun TextbookCloseWarning(guard: TextbookCloseGuard) {
    if (guard.failed) AlertDialog(
        onDismissRequest = guard::dismiss,
        title = { Text("草稿还没有保存") },
        text = { Text("窗口会保持打开，草稿仍在。请排除保存问题后重试。") },
        confirmButton = { TextButton(guard::requestClose, Modifier.testTag("retry-close")) { Text("重试保存并关闭") } },
        dismissButton = { TextButton(guard::dismiss) { Text("继续编辑") } },
    )
}

/** Production host content with an owned library for offscreen tests. */
@Composable
internal fun WindowsTextbooksContent(directory: Path, chooseBook: suspend () -> Path?) {
    val controller = remember(directory) { TextbookController(TextbookLibrary(directory)) }
    WindowsTextbookHost(controller, chooseBook = chooseBook)
}
