package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.exam.*
import com.coach.desktop.books.AnswerDrafts
import com.coach.desktop.books.AnswerExplain
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookWorkspace
import com.coach.desktop.books.answerExplainWith
import com.coach.desktop.books.rememberAnswerDrafts
import com.coach.ui.AppTab
import com.coach.ui.CoachTheme
import com.coach.ui.DeskNavigation
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Legacy tokens keep their storage identity; the three visible destinations are the same projection the app uses. */
private val AppTab.hostDestination: AppTab
    get() = when (this) {
        AppTab.REVIEW -> AppTab.TODAY
        AppTab.RECORDS -> AppTab.ROUTE
        else -> this
    }

/**
 * Answering in 做题学 is the first place this window sends book text anywhere: the same pairing, the same
 * per-book consent file and the same explain call the 原文页 already uses, created once for the whole window
 * so a book allowed on one screen is allowed on the next.
 */
@Composable
internal fun rememberAnswerExplain(library: TextbookLibrary): AnswerExplain {
    val explainer = remember(library) { BookExplainer() }
    val consent = remember(library) { ExplainConsent(library.directory) }
    return remember(explainer, consent) { answerExplainWith(explainer, consent) }
}

/**
 * The live Windows host: one chrome with 今天/课程/书架 and 设置 at the top right.
 * All three entries read the same [TextbookController]: the bookshelf owns the textbook workspace, and
 * 今天/课程 show the book that is actually on this machine and continue it in 书架. With an empty
 * library they stay an honest empty state instead of invented progress.
 */
@Composable
internal fun WindowsTextbookHost(controller: TextbookController, drafts: AnswerDrafts = rememberAnswerDrafts(controller),
    exams: LocalExamController = rememberLocalExamController(controller.library),
    answerExplain: AnswerExplain = rememberAnswerExplain(controller.library), chooseBook: suspend () -> Path?) {
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
                            else -> TextbookWorkspace(controller, drafts, loadOnMount = false, answerExplain = answerExplain, chooseBook = chooseBook)
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
