package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
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
}

internal enum class FirstOpenBlock { NONE, OFFLINE, BUSY, IMPORT, OTHER }

internal object FirstOpenPlanner {
    /** Enough of the opening for one question. A 600-page book does not have to finish first. */
    const val OPENING_CHARS = 3500

    fun openingGroup(book: Textbook): ChapterSectionGroup? {
        if (book.lessons.isEmpty()) return null
        val bodyPage = runCatching { book.bodyStart.importPage }.getOrDefault(1)
        val index = book.lessons.indexOfFirst { lesson ->
            lesson.blocks.isNotEmpty() &&
                lesson.lastPage >= bodyPage &&
                !ChapterCompanionGrouping.isExerciseSection(lesson.title) &&
                lesson.blocks.any { !it.heading && it.text.isNotBlank() }
        }.let { found ->
            if (found >= 0) found
            else book.lessons.indexOfFirst { it.blocks.any { block -> !block.heading && block.text.isNotBlank() } }
        }
        if (index < 0) return null
        val group = ChapterCompanionGrouping.groupForSection(book, index) ?: return null
        val first = ChapterCompanionProgressive.firstSectionGroup(group, null) ?: group
        return trim(first, OPENING_CHARS)
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

private sealed interface TasteStep {
    data object Loading : TasteStep
    data class Hold(val block: FirstOpenBlock, val message: String) : TasteStep
    data class Ask(val quiz: BookCompanionQuiz) : TasteStep
    data class Told(val quiz: BookCompanionQuiz, val picked: String) : TasteStep
}

@Composable
internal fun FirstTastePanel(
    book: Textbook,
    explainer: BookExplainer,
    connectivity: CoachConnectivityStatus,
    onReadBook: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    var attempt by remember(book.id) { mutableIntStateOf(0) }
    var step by remember(book.id) { mutableStateOf<TasteStep>(TasteStep.Loading) }
    var picked by remember(book.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id, attempt, connectivity) {
        if (connectivity == CoachConnectivityStatus.OFFLINE) {
            step = TasteStep.Hold(
                FirstOpenBlock.OFFLINE,
                FirstOpenPlanner.statusText(FirstOpenBlock.OFFLINE, null, bookInHand = true),
            )
            return@LaunchedEffect
        }
        step = TasteStep.Loading
        picked = null
        val group = withContext(Dispatchers.IO) { runCatching { FirstOpenPlanner.openingGroup(book) }.getOrNull() }
        if (group == null) {
            step = TasteStep.Hold(FirstOpenBlock.OTHER, FirstOpenCopy.NO_QUESTION)
            return@LaunchedEffect
        }
        val wire = ChapterCompanionGrouping.buildWireRequest(book, group)
        val blocks = ChapterCompanionGrouping.wireBlocks(group)
        when (val result = explainer.fetchChapterCompanion(wire, blocks)) {
            is ChapterCompanionFetchResult.Ready -> {
                val quiz = FirstOpenPlanner.questionOf(result.pack)
                step = if (quiz == null) TasteStep.Hold(FirstOpenBlock.OTHER, FirstOpenCopy.NO_QUESTION)
                else TasteStep.Ask(quiz)
            }
            is ChapterCompanionFetchResult.Failed -> {
                val block = FirstOpenPlanner.blockFor(result.trouble, connectivity)
                step = TasteStep.Hold(block, FirstOpenPlanner.statusText(block, result.message, bookInHand = true))
            }
        }
    }
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
        Text(book.title, style = MaterialTheme.typography.titleLarge, color = colors.fgPrimary, textAlign = TextAlign.Center)
        when (val current = step) {
            TasteStep.Loading -> {
                Text(FirstOpenCopy.MAKING, color = colors.fgSecondary, textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
            }
            is TasteStep.Hold -> {
                Text(current.message, color = v7Alert(), textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
                PrimaryButton(onClick = { attempt += 1 }, modifier = Modifier.testTag("first-taste-primary")) {
                    Text(FirstOpenCopy.RETRY)
                }
                V7TextButton(onReadBook, modifier = Modifier.testTag("first-taste-read")) { Text(FirstOpenCopy.READ) }
            }
            is TasteStep.Ask -> {
                Text(FirstOpenCopy.ANSWER_HINT, color = colors.fgSecondary, textAlign = TextAlign.Center, modifier = Modifier.testTag("first-taste-status"))
                QuestionCard(current.quiz, picked) { picked = it }
                PrimaryButton(
                    onClick = { picked?.let { step = TasteStep.Told(current.quiz, it) } },
                    enabled = picked != null,
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
