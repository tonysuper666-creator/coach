package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookWorkspace
import com.coach.ui.AppTab
import com.coach.ui.CoachTheme
import com.coach.ui.DeskNavigation
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

/** Desktop windows keep the two-column desk: the rail takes its width first, the paper and margin share the rest. */
private const val WINDOWS_WINDOW_WIDTH = 1440
private const val WINDOWS_WINDOW_HEIGHT = 900

/** Windows mainline: a new learner can bring a book without a preconfigured course or Mac pairing. */
internal fun runWindowsTextbooks() {
    val directory = windowsLibraryDirectory()
    application {
        val controller = remember { TextbookController(TextbookLibrary(directory)) }
        Window(onCloseRequest = ::exitApplication, title = "Coach",
            state = rememberWindowState(width = WINDOWS_WINDOW_WIDTH.dp, height = WINDOWS_WINDOW_HEIGHT.dp)) {
            WindowsTextbookHost(controller, chooseBook = {
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

/** Legacy tokens keep their storage identity; the three visible destinations are the same projection the app uses. */
private val AppTab.hostDestination: AppTab
    get() = when (this) {
        AppTab.REVIEW -> AppTab.TODAY
        AppTab.RECORDS -> AppTab.ROUTE
        else -> this
    }

/**
 * The live Windows host: one chrome with 今天/课程/书架 and 设置 at the top right.
 * 书架 owns the whole existing textbook workspace; 今天 and 课程 have no local source of truth on this
 * machine, so they show an honest empty state instead of invented progress.
 */
@Composable
internal fun WindowsTextbookHost(controller: TextbookController, chooseBook: suspend () -> Path?) {
    var tab by rememberSaveable { mutableStateOf(AppTab.RESOURCES) }
    var settings by rememberSaveable { mutableStateOf(false) }
    CoachTheme {
        Surface(Modifier.fillMaxSize().testTag("windows-host"), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Coach", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton({ settings = true }, modifier = Modifier.testTag("open-settings")) { Text("设置") }
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val rail = maxWidth >= 700.dp
                    val navigation: @Composable () -> Unit = {
                        Box(Modifier.then(if (rail) Modifier.width(76.dp).fillMaxHeight() else Modifier.fillMaxWidth())
                            .testTag("host-navigation"), contentAlignment = if (rail) Alignment.TopCenter else Alignment.Center) {
                            DeskNavigation(tab, vertical = rail) { picked -> tab = picked.hostDestination }
                        }
                    }
                    val content: @Composable () -> Unit = {
                        when (tab.hostDestination) {
                            AppTab.TODAY -> HostEmptyState("host-today-empty", "今天还没有安排",
                                "今天会显示接着学、复习和期末倒计时。这台电脑上的入口目前只管理教材，可以先去书架。")
                            AppTab.ROUTE -> HostEmptyState("host-course-empty", "还没有课程",
                                "课程要读 Mac 上的课程和进度，Windows 入口还没接。要现在开始，先去书架上传一本教材。")
                            else -> TextbookWorkspace(controller, chooseBook)
                        }
                    }
                    if (rail) Row(Modifier.fillMaxSize()) {
                        navigation()
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                    } else Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                        HorizontalDivider()
                        navigation()
                    }
                }
            }
            if (settings) HostSettings(controller.library.directory) { settings = false }
        }
    }
}

@Composable
private fun HostEmptyState(tag: String, title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(36.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HostSettings(directory: Path, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("教材和进度保存在这台电脑，不经过 Mac。")
            Text(directory.toString(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings-library-directory"))
            Text("今天、课程和考试需要 Mac 上的课程数据；这个 Windows 入口还没接，所以那两处先空着。",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClose, modifier = Modifier.testTag("close-settings")) { Text("知道了") }
    })
}
