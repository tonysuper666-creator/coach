package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.coach.desktop.books.exam.*
import com.coach.desktop.books.AnswerDrafts
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookWorkspace
import com.coach.desktop.books.rememberAnswerDrafts
import com.coach.ui.AppTab
import com.coach.ui.CoachTheme
import com.coach.ui.DeskNavigation
import com.coach.ui.v7.PrimaryButton
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
        Window(onCloseRequest = closeGuard::requestClose, title = "Coach",
            state = rememberWindowState(
                width = initialBounds.width.dp, height = initialBounds.height.dp,
                position = WindowPosition.Absolute(initialBounds.x.dp, initialBounds.y.dp),
            )) {
            WindowsTextbookHost(controller, drafts, exams, chooseBook = {
                withContext(Dispatchers.Swing) {
                    val dialog = FileDialog(window, "上传教材 · PDF / EPUB / Markdown / 文本", FileDialog.LOAD)
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

/** Legacy tokens keep their storage identity; the three visible destinations are the same projection the app uses. */
private val AppTab.hostDestination: AppTab
    get() = when (this) {
        AppTab.REVIEW -> AppTab.TODAY
        AppTab.RECORDS -> AppTab.ROUTE
        else -> this
    }

/**
 * The live Windows host: one chrome with 今天/课程/书架 and 设置 at the top right.
 * All three entries read the same [TextbookController]: the bookshelf owns the textbook workspace, and
 * 今天/课程 show the book that is actually on this machine and continue it in 书架. With an empty
 * library they stay an honest empty state instead of invented progress.
 */
@Composable
internal fun WindowsTextbookHost(controller: TextbookController, drafts: AnswerDrafts = rememberAnswerDrafts(controller),
    exams: LocalExamController = rememberLocalExamController(controller.library), chooseBook: suspend () -> Path?) {
    var tab by rememberSaveable { mutableStateOf(AppTab.RESOURCES) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val ui by controller.state.collectAsState()
    val examUi by exams.state.collectAsState()
    val scope = rememberCoroutineScope()
    val book = ui.book
    LaunchedEffect(book?.id) { book?.let { exams.load(it) } }
    // The library is read once for the whole host, so 今天 and 课程 show existing books before 书架 is opened.
    LaunchedEffect(controller) {
        val current = controller.state.value
        if (current.catalog.isEmpty() && current.book == null && current.prompt == null) controller.load()
    }
    fun openInWorkspace(action: suspend () -> Unit) { tab = AppTab.RESOURCES; scope.launch { action() } }
    val systemDark = isSystemInDarkTheme()
    val dark = remember { windowsThemeOverride(System.getProperty("coach.windows.theme")) }
    CoachTheme(dark ?: systemDark) {
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
                            AppTab.TODAY -> if (book == null) HostEmptyState("host-today-empty", "今天还没有要接着读的书",
                                "今天会显示书架上读到一半的教材。书架现在是空的，先去上传一本。")
                            else HostTodayBook(book, busy = ui.busy, reviews = ui.reviews.size,
                                onReview = { openInWorkspace { if (drafts.flush()) controller.showReviews() } },
                                onRead = { openInWorkspace { controller.read() } },
                                onLearn = { openInWorkspace { drafts.flush(); controller.learn() } })
                            AppTab.ROUTE -> if (book == null) HostEmptyState("host-course-empty", "还没有课程",
                                "课程按书架上的教材分节。书架现在是空的，先去上传一本，再回来按节学。")
                            else if (examUi.book?.id == book.id && examUi.attempt != null) LocalExamPane(exams)
                            else HostCourseBook(book, busy = ui.busy,
                                onLearn = { openInWorkspace { drafts.flush(); controller.learn() } },
                                onRead = { openInWorkspace { controller.read() } },
                                exams = {
                                    if (examUi.book?.id == book.id) LocalExamsSummary(exams)
                                    else {
                                        examUi.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        Text("正在读取本书练习…")
                                    }
                                })
                            else -> TextbookWorkspace(controller, drafts, loadOnMount = false, chooseBook = chooseBook)
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

/** 今天: the book on this machine, at the page and lesson 书架 left off. */
@Composable
private fun HostTodayBook(book: Textbook, busy: Boolean, reviews: Int, onReview: () -> Unit, onRead: () -> Unit, onLearn: () -> Unit) {
    HostPanel("host-today-book", "今天接着读", "《${book.title}》${book.progressLine()}", busy, onRead,
        "host-today-read", "接着读 · 第 ${book.readingPage} 页", onLearn, "host-today-learn",
        if (book.learning) "继续学 · 第 ${book.lessonIndex + 1} / ${book.lessons.size} 节" else "开始学这本书",
        extra = {
            if (reviews > 0) PrimaryButton(onReview, enabled = !busy, modifier = Modifier.testTag("host-today-review")) {
                Text("复习 $reviews 题")
            }
        })
}

/** 课程: the same book split into lessons; no remote course or knowledge map is invented here. */
@Composable
private fun HostCourseBook(book: Textbook, busy: Boolean, onLearn: () -> Unit, onRead: () -> Unit,
    exams: @Composable () -> Unit) {
    if (book.lessons.isEmpty()) HostPanel("host-course-book", "这门课还开不了",
        "《${book.title}》没有可提取的文字，按节学需要带文字层的教材。可以先在书架当一本书读。", busy, onRead,
        "host-course-read", "读这本书的原文", null, null, null, extra = exams)
    else HostPanel("host-course-book", book.title,
        "这门课就是书架上的这本教材，共 ${book.lessons.size} 节，${book.progressLine()}。", busy, onLearn,
        "host-course-learn", if (book.learning) "继续第 ${book.lessonIndex + 1} 节" else "开始这门课",
        onRead, "host-course-read", "接着读 · 第 ${book.readingPage} 页", extra = exams)
}

@Composable
private fun HostPanel(tag: String, title: String, body: String, busy: Boolean, onPrimary: () -> Unit,
    primaryTag: String, primaryLabel: String, onSecondary: (() -> Unit)?, secondaryTag: String?, secondaryLabel: String?,
    extra: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(36.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(onPrimary, enabled = !busy, modifier = Modifier.testTag(primaryTag)) { Text(primaryLabel) }
                if (onSecondary != null && secondaryTag != null && secondaryLabel != null) OutlinedButton(onSecondary,
                    enabled = !busy, modifier = Modifier.testTag(secondaryTag)) { Text(secondaryLabel) }
            }
            extra()
        }
    }
}

private fun Textbook.progressLine(): String =
    "读到第 $readingPage 页" + if (learning) "，学习停在第 ${lessonIndex + 1} / ${lessons.size} 节" else "，还没开始按节学"

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
            Text("课程里可做本地教材参考练习；Mac 上的课程数据仍独立。",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClose, modifier = Modifier.testTag("close-settings")) { Text("知道了") }
    })
}

/** Production host content with an owned library for offscreen tests. */
@Composable
internal fun WindowsTextbooksContent(directory: Path, chooseBook: suspend () -> Path?) {
    val controller = remember(directory) { TextbookController(TextbookLibrary(directory)) }
    WindowsTextbookHost(controller, chooseBook = chooseBook)
}
