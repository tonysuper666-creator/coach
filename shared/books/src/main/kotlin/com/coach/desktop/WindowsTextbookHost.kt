package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.exam.*
import com.coach.desktop.books.textbookFileDrop
import com.coach.desktop.books.bookWorkspaceThemeOverride
import com.coach.desktop.books.AnswerDrafts
import com.coach.desktop.books.AnswerExplain
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.BookFormats
import com.coach.desktop.books.ReadingSettings
import com.coach.desktop.books.LocalReadingFont
import com.coach.desktop.books.ReadingSettingsPanel
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
import com.coach.ui.v7.LocalDeskSelect
import com.coach.ui.v7.DeskTypeControl
import com.coach.ui.v7.LocalDeskTypeControl
import com.coach.ui.v7.V7TextButton
import coach.design.tokens.CoachV7
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    val explainer = remember(library) { BookExplainer(library = library) }
    val consent = remember(library) { ExplainConsent(library.directory) }
    return remember(explainer, consent) { answerExplainWith(explainer, consent) }
}

/**
 * The live Windows host: one chrome with 今天/课程/书架 in the rail and 设置 at its foot.
 * All three entries read the same [TextbookController]: the bookshelf owns the textbook workspace, and
 * 今天/课程 show the book that is actually on this machine and continue it in 书架. With an empty
 * library they stay an honest empty state instead of invented progress.
 */
@Composable
internal fun WindowsTextbookHost(controller: TextbookController, drafts: AnswerDrafts = rememberAnswerDrafts(controller),
    exams: LocalExamController = rememberLocalExamController(controller.library),
    answerExplain: AnswerExplain = rememberAnswerExplain(controller.library),
    learningSupport: com.coach.desktop.books.BookLearningSupport? = null, onPersonalCourse: (() -> Unit)? = null,
    releasePickedBook: (Path) -> Unit = {}, chooseBook: suspend () -> Path?) {
    var tab by rememberSaveable { mutableStateOf(AppTab.RESOURCES) }
    // 今天 / 课程 open their book inside 书架's workspace; the rail keeps pointing where the reader came from
    // until they pick another entry or the workspace is back on the shelf.
    var origin by rememberSaveable { mutableStateOf<AppTab?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val ui by controller.state.collectAsState()
    val examUi by exams.state.collectAsState()
    val scope = rememberCoroutineScope()
    val book = ui.book
    LaunchedEffect(ui.prompt, book?.id) {
        if (ui.prompt == com.coach.desktop.books.BookPrompt.IMPORTED) {
            origin = null
            settings = false
            tab = AppTab.RESOURCES
        }
    }
    // On the shelf with nothing being opened over it; a start prompt still belongs to the book being opened.
    val onShelf = book == null || (ui.screen == BookScreen.HOME && ui.prompt == null)
    LaunchedEffect(book?.id) { book?.let { exams.load(it) } }
    LaunchedEffect(onShelf) { if (onShelf) origin = null }
    // The library is read once for the whole host, so 今天 and 课程 show existing books before 书架 is opened.
    LaunchedEffect(controller) {
        val current = controller.state.value
        if (current.catalog.isEmpty() && current.book == null && current.prompt == null) controller.load()
        while (true) {
            delay(60_000)
            controller.refreshReviews()
        }
    }
    fun openInWorkspace(action: suspend () -> Unit) {
        origin = tab.hostDestination
        tab = AppTab.RESOURCES
        scope.launch {
            action()
            // Nothing opened (a draft could not be saved, say): the rail goes back to 书架 with the shelf.
            if (controller.state.value.let { it.book == null || (it.screen == BookScreen.HOME && it.prompt == null) }) origin = null
        }
    }
    fun select(picked: AppTab) { origin = null; tab = picked.hostDestination }
    val destination = tab.hostDestination
    // Reading a book is the shelf's own page, wherever it was opened from (TON-1698); learning and its
    // source detour keep pointing at the entry they came from.
    val reading = ui.screen == BookScreen.READER && ui.source == null
    val highlighted = if (destination == AppTab.RESOURCES && !onShelf && !reading) origin ?: destination else destination
    // Reader, learning and reviews draw their own V7 desk bar; the host bar would be a second "Coach" row above it.
    val deskPage = destination == AppTab.RESOURCES && book != null && ui.screen != BookScreen.HOME
    val systemDark = isSystemInDarkTheme()
    val dark = remember { bookWorkspaceThemeOverride() }
    val preferences = remember(controller.library) { ReadingSettings(controller.library.directory) }
    CoachTheme(dark ?: preferences.paper.dark ?: systemDark) {
        val colors = com.coach.ui.theme.CoachTheme.colors
        Surface(Modifier.fillMaxSize().textbookFileDrop { files ->
            scope.launch {
                try { if (drafts.flush() && exams.flush()) controller.importDroppedBooks(files) }
                finally { files.forEach(releasePickedBook) }
            }
        }.testTag("windows-host"), color = colors.bgCanvas) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val rail = maxWidth >= 700.dp
                val settingsButton: @Composable () -> Unit = {
                    V7TextButton({ settings = true }, modifier = Modifier.testTag("open-settings"), ringInside = true) {
                        Text("设置", style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                    }
                }
                val navigation: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.testTag("host-navigation").semantics { stateDescription = hostCrumb(highlighted) }, contentAlignment = if (rail) Alignment.TopCenter else Alignment.Center) {
                        DeskNavigation(highlighted, vertical = rail) { picked -> select(picked) }
                    }
                }
                val content: @Composable () -> Unit = {
                    when (destination) {
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
                        else -> TextbookWorkspace(controller, drafts, loadOnMount = false, answerExplain = answerExplain, support = learningSupport, releasePickedBook = releasePickedBook, chooseBook = chooseBook)
                    }
                }
                val deskSelect = remember { { picked: AppTab -> select(picked) } }
                val typeControl: DeskTypeControl? = if (destination == AppTab.RESOURCES && ui.screen == BookScreen.READER && book != null) {
                    val defaultTextView = remember(book.id) { book.page(ui.page)?.ocr == true }
                    val panel: DeskTypeControl = { extra ->
                        ReadingSettingsPanel(preferences, isOriginal = {
                            (book.format == "pdf" || book.format in BookFormats.images) &&
                                !(controller.library.preferredTextView(book.id) ?: defaultTextView)
                        }, extra = extra)
                    }
                    panel
                } else null
                CompositionLocalProvider(LocalDeskSelect provides deskSelect, LocalDeskTypeControl provides typeControl,
                    LocalReadingFont provides preferences.font) {
                    // No rules between bar, rail and page: V7 separates them with the desk colour and the paper alone.
                    if (rail) Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(76.dp).fillMaxHeight().padding(top = HostBarHeight + 8.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            navigation(Modifier.fillMaxWidth())
                            Spacer(Modifier.weight(1f))
                            settingsButton()
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (!deskPage) HostBar(hostCrumb(destination)) { select(AppTab.TODAY) }
                            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                        }
                    } else Column(Modifier.fillMaxSize()) {
                        if (!deskPage) HostBar(hostCrumb(destination)) { select(AppTab.TODAY) }
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                        Row(Modifier.fillMaxWidth().background(colors.bgCanvas), verticalAlignment = Alignment.CenterVertically) {
                            navigation(Modifier.weight(1f))
                            settingsButton()
                        }
                    }
                }
            }
            if (settings) HostSettings(controller.library.directory, onPersonalCourse) { settings = false }
        }
    }
}

private val HostBarHeight = 48.dp

private fun hostCrumb(destination: AppTab): String = when (destination) {
    AppTab.TODAY -> "今天"
    AppTab.ROUTE -> "课程"
    else -> "书架"
}

/** The same 48dp bar `Desk` draws: a small sans "Coach" at the left and the place in the middle, no rule below. */
@Composable
private fun HostBar(crumb: String, onHome: () -> Unit) {
    val colors = com.coach.ui.theme.CoachTheme.colors
    Box(Modifier.fillMaxWidth().height(HostBarHeight).padding(horizontal = 16.dp).testTag("host-bar")) {
        Text("Coach", Modifier.align(Alignment.CenterStart).clickable(onClick = onHome),
            style = MaterialTheme.typography.labelLarge, fontWeight = CoachV7.Weight.uiStrong, color = colors.fgPrimary)
        Text(crumb, Modifier.align(Alignment.Center).testTag("host-crumb"), style = MaterialTheme.typography.labelLarge,
            fontWeight = CoachV7.Weight.uiStrong, color = colors.fgPrimary)
    }
}
