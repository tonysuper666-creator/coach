package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * First open stops once the person has seen an explanation.
 * The marker is a local flag only — no book text.
 */
internal object FirstOpenStore {
    private const val FILE = "first-open-done"

    fun isDone(directory: Path): Boolean = Files.isRegularFile(directory.resolve(FILE))

    fun markDone(directory: Path) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(FILE), "done\n")
    }
}

internal object FirstOpenCopy {
    const val TITLE = "放进一本书，马上做第一道题。"
    const val HINT = "开头几章整理好就能出题，大约 1 分钟，不用等整本。"
    const val BUTTON = "放进一本书"
    const val IMPORTING = "正在读进这本书，大约不到一分钟。"
    const val MAKING = "正在用开头几章出第一道题，大约 1 分钟。整本书还在整理，可以稍后读。"
    const val OFFLINE = "现在没联网。书可以先放进来；出题和讲解要联网，连上后点再试一次。"
    const val OFFLINE_HAS_BOOK = "现在没联网。书已经在这台电脑上，连上以后再出第一道题。"
    const val BUSY = "服务这会儿正忙。大约一分钟后再试，书还在。"
    const val IMPORT_NEXT = "换一份文件再放一次。"
    const val RETRY = "再试一次"
    const val AGAIN = "再放一次"
    const val READ = "先翻开这本书"
    const val SUBMIT = "看讲解"
    const val CONTINUE = "开始读这本书"
    const val ANSWER_HINT = "选一个答案。做完就能看到讲解。"
    const val NO_QUESTION = "开头这几章还没出成题。可以再试一次，或先翻开这本书。"
    const val GENERIC_FAIL = "这次没出成题。可以再试一次。"
    const val ALLOW_EXPLAIN = "允许这本书讲解"
    const val ALLOW_EXPLAIN_HINT = "这本书还没开启讲解许可。允许后只发送开头几小节用来出题。"
}

internal enum class FirstOpenBlock { NONE, OFFLINE, BUSY, IMPORT, OTHER }

internal object FirstOpenPlanner {
    /** Enough of the opening for one question. A 600-page book does not have to finish first. */
    const val OPENING_CHARS = 3500

    fun openingGroup(book: Textbook): ChapterSectionGroup? {
        if (book.lessons.isEmpty()) return null
        val bodyPage = runCatching { book.bodyStart.importPage }.getOrDefault(1)
        val hasProse = { lesson: BookLesson -> lesson.blocks.any { !it.heading && it.text.isNotBlank() } }
        val inBody = book.lessons.indices.filter { i ->
            val lesson = book.lessons[i]
            lesson.blocks.isNotEmpty() &&
                lesson.lastPage >= bodyPage &&
                !ChapterCompanionGrouping.isExerciseSection(lesson.title) &&
                hasProse(lesson)
        }
        // Front matter that runs into the body page belongs to no chapter; try the next section instead of
        // sending the preface as the opening.
        val candidates = (inBody.sortedBy { book.lessons[it].firstPage < bodyPage } +
            book.lessons.indices.filter { hasProse(book.lessons[it]) }).distinct()
        for (index in candidates) {
            val group = ChapterCompanionGrouping.groupForSection(book, index) ?: continue
            val chosenLesson = book.lessons[index]
            val first = ChapterCompanionProgressive.firstSectionGroup(group, chosenLesson.id)
                ?.takeIf { g -> g.sections.any(hasProse) }
                ?: group.copy(
                    firstPage = chosenLesson.firstPage,
                    lastPage = chosenLesson.lastPage,
                    sections = listOf(chosenLesson),
                )
            trim(first, OPENING_CHARS)?.let { return it }
        }
        // No section sits inside a chapter (a book without an outline): ask about the first prose section alone.
        for (index in candidates) {
            val chosenLesson = book.lessons[index]
            val alone = ChapterSectionGroup(
                chapterId = chosenLesson.id,
                chapterTitle = chosenLesson.title,
                sections = listOf(chosenLesson),
                firstPage = chosenLesson.firstPage,
                lastPage = chosenLesson.lastPage,
            )
            trim(alone, OPENING_CHARS)?.let { return it }
        }
        return null
    }

    fun questionOf(pack: BookCompanionPack): BookCompanionQuiz? =
        pack.pretest.firstOrNull { it.stem.isNotBlank() && it.options.size >= 2 }
            ?: pack.optionalPractice.firstOrNull { it.stem.isNotBlank() && it.options.size >= 2 }

    fun blockFor(trouble: ExplainTrouble?, connectivity: CoachConnectivityStatus): FirstOpenBlock = when {
        connectivity == CoachConnectivityStatus.OFFLINE || trouble == ExplainTrouble.Unreachable -> FirstOpenBlock.OFFLINE
        trouble == ExplainTrouble.Busy -> FirstOpenBlock.BUSY
        else -> FirstOpenBlock.OTHER
    }

    fun statusText(block: FirstOpenBlock, serverMessage: String?, bookInHand: Boolean): String = when (block) {
        FirstOpenBlock.OFFLINE -> if (bookInHand) FirstOpenCopy.OFFLINE_HAS_BOOK else FirstOpenCopy.OFFLINE
        FirstOpenBlock.BUSY -> FirstOpenCopy.BUSY
        FirstOpenBlock.IMPORT -> {
            val said = serverMessage?.trim().orEmpty().ifBlank { "这本书没放进来。" }
            if (FirstOpenCopy.IMPORT_NEXT in said) said else "$said ${FirstOpenCopy.IMPORT_NEXT}"
        }
        FirstOpenBlock.OTHER -> {
            val said = serverMessage?.trim().orEmpty()
            if (said.isBlank() || said.contains("Exception") || said.contains("HTTP")) FirstOpenCopy.GENERIC_FAIL else said
        }
        FirstOpenBlock.NONE -> ""
    }

    private fun trim(group: ChapterSectionGroup, maxChars: Int): ChapterSectionGroup? {
        val section = group.sections.firstOrNull() ?: return null
        var used = 0
        val kept = mutableListOf<SourceBlock>()
        for (block in section.blocks) {
            if (block.heading) {
                kept += block
                continue
            }
            if (block.text.isBlank()) continue
            if (used >= maxChars && kept.any { !it.heading }) break
            kept += block
            used += block.text.length
        }
        if (kept.none { !it.heading && it.text.isNotBlank() }) return null
        val trimmed = section.copy(blocks = kept)
        return group.copy(
            sections = listOf(trimmed),
            firstPage = trimmed.firstPage,
            lastPage = trimmed.lastPage,
        )
    }
}

@Composable
internal fun FirstOpenInvite(
    ui: LibraryUi,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offline = LocalCoachConnectivity.current == CoachConnectivityStatus.OFFLINE
    val busy = ui.busy || ui.importing != null || ui.indexing != null
    val password = BookImportMessages.asksForPassword(ui.error)
    val failed = !ui.error.isNullOrBlank() && !password && !busy
    val status = when {
        busy -> FirstOpenCopy.IMPORTING
        failed -> FirstOpenPlanner.statusText(FirstOpenBlock.IMPORT, ui.error, bookInHand = false)
        password -> ui.error.orEmpty()
        offline -> FirstOpenCopy.OFFLINE
        else -> null
    }
    val button = if (failed) FirstOpenCopy.AGAIN else FirstOpenCopy.BUTTON
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 28.dp)
            .testTag("home-empty-hub"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoachEmptyState(title = FirstOpenCopy.TITLE, hint = FirstOpenCopy.HINT)
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed || password) v7Alert() else CoachTheme.colors.fgSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("first-open-status"),
            )
        }
        if (!password) {
            PrimaryButton(
                onClick = onImport,
                enabled = !busy,
                modifier = Modifier.testTag("upload-textbook"),
            ) { Text(button) }
        }
    }
}

internal sealed interface TasteStep {
    data object Loading : TasteStep
    data object NeedConsent : TasteStep
    data class Hold(val block: FirstOpenBlock, val message: String) : TasteStep
    data class Ask(val quiz: BookCompanionQuiz) : TasteStep
    data class Told(val quiz: BookCompanionQuiz, val picked: String) : TasteStep
}

/**
 * 进阅读页后底部轻提示开胃题栏：紧贴在阅读区下方（独立占位不遮挡正文与插图），
 * 提供「来一道」「不再提示」「×」三个入口。
 */
@Composable
internal fun FirstTasteStrip(
    onTry: () -> Unit,
    onDismiss: () -> Unit,
    onNeverShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val pillShape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface1)
            .testTag("first-taste-strip"),
    ) {
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "想先试一道开胃题吗？",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "来一道",
                style = MaterialTheme.typography.labelLarge,
                color = colors.actionPrimary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(pillShape)
                    .clickable(role = Role.Button, onClick = onTry)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("first-taste-try"),
            )
            Text(
                "不再提示",
                style = MaterialTheme.typography.labelMedium,
                color = colors.fgSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(pillShape)
                    .clickable(role = Role.Button, onClick = onNeverShow)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("first-taste-never"),
            )
            Text(
                "×",
                style = MaterialTheme.typography.labelLarge,
                color = colors.fgSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(pillShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("first-taste-dismiss"),
            )
        }
    }
}

/**
 * The first question once it is on screen, kept outside the panel so a connectivity flip or the
 * panel being re-mounted never throws away the question the reader is answering.
 */
@Stable
internal class FirstTasteMemory {
    private var bookId: String? = null
    var step by mutableStateOf<TasteStep>(TasteStep.Loading)
    var picked by mutableStateOf<String?>(null)

    fun bind(id: String) {
        if (bookId == id) return
        bookId = id
        step = TasteStep.Loading
        picked = null
    }
}

@Composable
internal fun FirstTastePanel(
    book: Textbook,
    explainer: BookExplainer,
    connectivity: CoachConnectivityStatus,
    onReadBook: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    memory: FirstTasteMemory = remember { FirstTasteMemory() },
    compact: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    library: TextbookLibrary? = null,
    onAllowExplain: (() -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    val pollMs = LocalConnectivityPollMs.current
    val currentBook by rememberUpdatedState(book)
    val aiEnabled = LocalReadingAiExplain.current
    val hostAllowExplain = LocalAllowBookExplain.current
    val effectiveAllowExplain: (() -> Unit)? = onAllowExplain ?: hostAllowExplain?.let { cb -> { cb(book.id) } }
    var localAllowed by remember(book.id) { mutableStateOf(false) }
    val hasConsent = aiEnabled && (library == null || localAllowed || ExplainConsent(library.directory).allowed(book.id))
    var fetchTrigger by remember(book.id) { mutableIntStateOf(0) }
    var manualClicks by remember(book.id) { mutableIntStateOf(0) }
    var handledManualClicks by remember(book.id) { mutableIntStateOf(0) }
    var recoveredOnline by remember(book.id) { mutableStateOf(false) }
    var waitingBookReady by remember(book.id) { mutableStateOf(false) }
    memory.bind(book.id)

    // If Tier 0 PDF import had not yet extracted body paragraphs when FirstTastePanel mounted,
    // automatically proceed as soon as Tier 1 / OCR updates the book in memory.
    LaunchedEffect(book.id, book.indexState, book.hasPendingOcr, book.lessons.size) {
        if (waitingBookReady) {
            waitingBookReady = false
            fetchTrigger += 1
        }
    }

    // Automatic resume when host connectivity recovers from OFFLINE -> ONLINE.
    // Never touches Ask or Told once the first question has already loaded.
    LaunchedEffect(book.id, connectivity) {
        if (connectivity == CoachConnectivityStatus.ONLINE &&
            (memory.step as? TasteStep.Hold)?.block == FirstOpenBlock.OFFLINE
        ) {
            recoveredOnline = true
            fetchTrigger += 1
        }
    }

    // Background probe while held on a network failure so standalone panels or requests that failed
    // before host connectivity flipped also recover without requiring a manual click.
    val heldOffline = (memory.step as? TasteStep.Hold)?.block == FirstOpenBlock.OFFLINE
    LaunchedEffect(book.id, heldOffline, connectivity, pollMs) {
        if (!heldOffline) return@LaunchedEffect
        val probeClient = explainer.unifiedUploadClient()
        while (true) {
            delay(pollMs)
            if (connectivity == CoachConnectivityStatus.OFFLINE) {
                if (!explainer.loaded && explainer.identity == null) continue
                val live = if (probeClient != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { probeClient.probeReachability() }.getOrDefault(CoachConnectivityStatus.OFFLINE)
                    }
                } else CoachConnectivityStatus.OFFLINE
                if (live == CoachConnectivityStatus.ONLINE) {
                    recoveredOnline = true
                    fetchTrigger += 1
                    break
                }
            } else {
                val live = if (probeClient != null && (explainer.loaded || explainer.identity != null)) {
                    withContext(Dispatchers.IO) {
                        runCatching { probeClient.probeReachability() }.getOrDefault(CoachConnectivityStatus.ONLINE)
                    }
                } else CoachConnectivityStatus.ONLINE
                if (live == CoachConnectivityStatus.ONLINE) {
                    recoveredOnline = true
                    fetchTrigger += 1
                    break
                }
            }
        }
    }

    LaunchedEffect(book.id, fetchTrigger, hasConsent) {
        val isManual = manualClicks > handledManualClicks
        handledManualClicks = manualClicks
        if (memory.step is TasteStep.Ask || memory.step is TasteStep.Told) return@LaunchedEffect
        if (!hasConsent) {
            memory.step = TasteStep.NeedConsent
            return@LaunchedEffect
        }
        if (connectivity == CoachConnectivityStatus.OFFLINE && !isManual && !recoveredOnline) {
            memory.step = TasteStep.Hold(
                FirstOpenBlock.OFFLINE,
                FirstOpenPlanner.statusText(FirstOpenBlock.OFFLINE, null, bookInHand = true),
            )
            return@LaunchedEffect
        }
        recoveredOnline = false
        memory.step = TasteStep.Loading
        memory.picked = null
        val snapshotBook = currentBook
        val group = withContext(Dispatchers.IO) { runCatching { FirstOpenPlanner.openingGroup(snapshotBook) }.getOrNull() }
        if (group == null) {
            if (snapshotBook.indexState != "READY" || snapshotBook.hasPendingOcr) {
                waitingBookReady = true
                return@LaunchedEffect
            }
            memory.step = TasteStep.Hold(FirstOpenBlock.OTHER, FirstOpenCopy.NO_QUESTION)
            return@LaunchedEffect
        }
        waitingBookReady = false
        if (!explainer.loaded) {
            explainer.load()
        }
        val wire = ChapterCompanionGrouping.buildWireRequest(snapshotBook, group)
        val blocks = ChapterCompanionGrouping.wireBlocks(group)
        when (val result = explainer.fetchChapterCompanion(wire, blocks)) {
            is ChapterCompanionFetchResult.Ready -> {
                val quiz = FirstOpenPlanner.questionOf(result.pack)
                memory.picked = null
                memory.step = if (quiz == null) TasteStep.Hold(FirstOpenBlock.OTHER, FirstOpenCopy.NO_QUESTION)
                else TasteStep.Ask(quiz)
            }
            is ChapterCompanionFetchResult.Failed -> {
                if (result.trouble == ExplainTrouble.ConsentRequired) {
                    memory.step = TasteStep.NeedConsent
                } else {
                    val latestConnectivity = CoachConnectivityRegistry.statusFor(explainer.identity?.serverUrl.orEmpty())
                        .takeIf { it != CoachConnectivityStatus.UNKNOWN } ?: connectivity
                    val block = FirstOpenPlanner.blockFor(result.trouble, latestConnectivity)
                    memory.step = TasteStep.Hold(block, FirstOpenPlanner.statusText(block, result.message, bookInHand = true))
                }
            }
        }
    }
    val body: @Composable () -> Unit = {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary, modifier = Modifier.weight(1f))
                V7TextButton(onDismiss ?: onReadBook, modifier = Modifier.testTag("first-taste-close")) { Text("×") }
            }
        } else {
            Text(book.title, style = MaterialTheme.typography.titleLarge, color = colors.fgPrimary, textAlign = TextAlign.Center)
        }
        when (val current = memory.step) {
            TasteStep.Loading -> {
                Text(FirstOpenCopy.MAKING, color = colors.fgSecondary, textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
            }
            TasteStep.NeedConsent -> {
                Text(FirstOpenCopy.ALLOW_EXPLAIN_HINT, color = colors.fgSecondary, textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
                PrimaryButton(
                    onClick = {
                        localAllowed = true
                        effectiveAllowExplain?.invoke()
                        if (effectiveAllowExplain == null) library?.let { ExplainConsent(it.directory).allow(book.id) }
                        manualClicks += 1
                        fetchTrigger += 1
                    },
                    modifier = Modifier.testTag("first-taste-allow-explain"),
                ) { Text(FirstOpenCopy.ALLOW_EXPLAIN) }
                V7TextButton(onDismiss ?: onReadBook, modifier = Modifier.testTag("first-taste-read")) { Text(FirstOpenCopy.READ) }
            }
            is TasteStep.Hold -> {
                Text(current.message, color = v7Alert(), textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
                PrimaryButton(
                    onClick = {
                        manualClicks += 1
                        fetchTrigger += 1
                    },
                    modifier = Modifier.testTag("first-taste-primary"),
                ) {
                    Text(FirstOpenCopy.RETRY)
                }
                V7TextButton(onDismiss ?: onReadBook, modifier = Modifier.testTag("first-taste-read")) { Text(FirstOpenCopy.READ) }
            }
            is TasteStep.Ask -> {
                Text(FirstOpenCopy.ANSWER_HINT, color = colors.fgSecondary, textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
                QuestionCard(current.quiz, memory.picked) { memory.picked = it }
                PrimaryButton(
                    onClick = { memory.picked?.let { memory.step = TasteStep.Told(current.quiz, it) } },
                    enabled = memory.picked != null,
                    modifier = Modifier.testTag("first-taste-primary"),
                ) { Text(FirstOpenCopy.SUBMIT) }
            }
            is TasteStep.Told -> {
                val right = current.picked == current.quiz.correctOption
                Text(
                    if (right) "对了。" else "这题选的是另一项。",
                    color = colors.fgPrimary,
                    modifier = Modifier.testTag("first-taste-verdict"),
                )
                val explanation = current.quiz.explanation.ifBlank { "正确项是：${current.quiz.correctOption}" }
                Text(explanation, color = colors.fgPrimary, modifier = Modifier.testTag("first-taste-explanation"))
                PrimaryButton(onClick = onDone, modifier = Modifier.testTag("first-taste-primary")) {
                    Text(FirstOpenCopy.CONTINUE)
                }
            }
        }
    }
    if (compact) {
        PaperCard(
            modifier = modifier
                .widthIn(max = 460.dp)
                .heightIn(max = 460.dp)
                .testTag("first-taste"),
            contentPadding = PaddingValues(16.dp),
            contentSpacing = 12.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                body()
            }
        }
    } else {
        Column(
            modifier
                .fillMaxSize()
                .background(colors.bgCanvas)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 36.dp)
                .testTag("first-taste"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            body()
        }
    }
}

@Composable
private fun QuestionCard(quiz: BookCompanionQuiz, picked: String?, onPick: (String) -> Unit) {
    val colors = CoachTheme.colors
    PaperCard(Modifier.fillMaxWidth().testTag("first-taste-question"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Text(quiz.stem, style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary, modifier = Modifier.testTag("first-taste-stem"))
        for ((index, option) in quiz.options.withIndex()) {
            val selected = option == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPick(option) },
                    )
                    .testTag("first-taste-option-$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected) "● $option" else "○ $option",
                    color = if (selected) colors.fgPrimary else colors.fgSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
