package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.CoachErrorBanner
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface CompanionPackState {
    data object Idle : CompanionPackState
    data object Loading : CompanionPackState
    data class Ready(val pack: BookCompanionPack, val expanding: Boolean = false) : CompanionPackState
    data class Failed(val message: String, val trouble: ExplainTrouble) : CompanionPackState
}

/**
 * Modular single-book companion panel inside the right drawer ("讲解" tab):
 * - 5 modules: 本章要点 / 读前自测 / 推导看图模板 / 选做练习 / 原书章末习题三级提示
 * - Real-time draft persistence into `book_chapter_progress.practice_draft_json`
 * - Enforces UX-4 (`PrimaryButton <= 1`), UX-9/10/11 (six-state primitives), UX-12/13 (plain Chinese copy)
 */
@Composable
internal fun SingleBookCompanionPanel(
    book: Textbook,
    currentPage: Int,
    currentSectionIndex: Int,
    explainer: BookExplainer,
    library: TextbookLibrary,
    draft: ChapterPracticeDraft,
    onDraftChange: (ChapterPracticeDraft) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    studyPreference: BookStudyPreference = BookStudyPreference(bookId = book.id),
    onUpdateStudyPreference: (BookStudyPreference, Boolean) -> Unit = { _, _ -> },
    onJumpPage: (Int) -> Unit = { p -> onOpenAnchor(BookCompanionAnchor(p, "", 0, 0, "")) },
    onCommitQuizAnswer: (BookLesson, Int, Practice, PracticeAnswer) -> Unit,
    onCommitPretestAnswer: ((BookLesson, Int, Practice, PracticeAnswer) -> Unit)? = null,
    onAskAboutText: (String) -> Unit,
    onOpenLogin: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit = {},
    bottomContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    currentPageOffset: Int = 0,
    visiblePageRanges: List<IntRange>? = null,
    onOpenSectionMainline: ((Int) -> Unit)? = null,
    wholeOriginalPageVisible: Boolean = false,
) {
    val colors = CoachTheme.colors
    val originalOnly = studyPreference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY
    val aiEnabled = LocalReadingAiExplain.current && !originalOnly
    val scope = rememberCoroutineScope()
    val chapterGroup = remember(book.id, book.lessons, currentSectionIndex) {
        ChapterCompanionGrouping.groupForSection(book, currentSectionIndex)
    }
    val currentSection = book.lessons.getOrNull(currentSectionIndex)
    val proseGroup = remember(book, chapterGroup) {
        chapterGroup?.let { ChapterCompanionGrouping.filterForCompanion(book, it) }
    }
    val chapterNum = remember(book.id, chapterGroup?.chapterId, currentPage) {
        chapterGroup?.let { StudyModeChapterNumbering.resolve(book, currentPage, it.firstPage).chapterNumber } ?: 0
    }
    var packState by remember(book.id, chapterGroup?.chapterId) { mutableStateOf<CompanionPackState>(CompanionPackState.Idle) }
    var fetchNonce by remember(book.id, chapterGroup?.chapterId) { mutableIntStateOf(0) }
    var modePackJson by remember(book.id, chapterGroup?.chapterId, studyPreference.mode, studyPreference.detailLevel, studyPreference.explainLang, studyPreference.inquiryQuestion) {
        mutableStateOf<String?>(null)
    }
    var modeStreamingChars by remember(book.id, chapterGroup?.chapterId, studyPreference.mode, studyPreference.detailLevel, studyPreference.explainLang, studyPreference.inquiryQuestion) {
        mutableIntStateOf(0)
    }
    var modeStreamingText by remember(book.id, chapterGroup?.chapterId, studyPreference.mode, studyPreference.detailLevel, studyPreference.explainLang, studyPreference.inquiryQuestion) {
        mutableStateOf("")
    }
    var modeLoading by remember(book.id, chapterGroup?.chapterId, studyPreference.mode, studyPreference.detailLevel, studyPreference.explainLang, studyPreference.inquiryQuestion) {
        mutableStateOf(false)
    }
    var modeError by remember(book.id, chapterGroup?.chapterId, studyPreference.mode, studyPreference.detailLevel, studyPreference.explainLang, studyPreference.inquiryQuestion) {
        mutableStateOf<String?>(null)
    }
    var modeFetchNonce by remember(book.id, chapterGroup?.chapterId, studyPreference.mode) { mutableIntStateOf(0) }
    var bookMapMeta by remember(book.id) { mutableStateOf<WholeBookMapMeta?>(null) }
    var bookMapBatchBusy by remember(book.id) { mutableStateOf(false) }
    var sectionMastery by remember(book.id, currentSection?.id, chapterGroup?.chapterId) { mutableStateOf<ConceptMasteryRecord?>(null) }
    var gradeResponsesByQuestion by remember(book.id) { mutableStateOf<Map<String, PracticeGradeDiagnoseResponse>>(emptyMap()) }
    val activeModule = CompanionModuleTab.fromWire(draft.activeModule)
    var modeSourcePages by remember(book.id, chapterGroup?.chapterId, studyPreference) { mutableStateOf(emptyList<Int>()) }
    var wholeChapter by remember(book.id, currentPage, studyPreference.mode) { mutableStateOf(false) }
    val readingExplanation = !originalOnly && (studyPreference.mode == BookStudyMode.BOOK_MAP ||
        (studyPreference.mode == BookStudyMode.READ_THROUGH && activeModule == CompanionModuleTab.KEY_POINTS))
    val samePage = chapterGroup?.firstPage == currentPage && chapterGroup.lastPage == currentPage
    val sectionOnly = studyPreference.mode == BookStudyMode.READ_THROUGH &&
        chapterGroup?.sections?.singleOrNull()?.let { currentPage in it.firstPage..it.lastPage } == true
    val screenBlocks = remember(book, currentPage, currentPageOffset, visiblePageRanges) {
        visiblePageRanges?.let { selectScreenExplainBlocks(book, currentPage, currentPageOffset, it) }
    }
    // A single import page (or a single section) can still run to many screens.
    val canChooseScreen = remember(chapterGroup, samePage, sectionOnly, screenBlocks) {
        if (screenBlocks == null) !samePage && !sectionOnly
        else !samePage || chapterGroup?.sections.orEmpty().any { lesson ->
            lesson.blocks.any { it.isBodyParagraph && it !in screenBlocks }
        }
    }
    val pageOnly = readingExplanation && !wholeChapter && canChooseScreen
    val cachedPagePack = remember(packState, pageOnly, currentPage, currentPageOffset, screenBlocks, currentSection, studyPreference.mode) {
        val pack = (packState as? CompanionPackState.Ready)?.pack
        if (!pageOnly || studyPreference.mode != BookStudyMode.READ_THROUGH || pack == null) null
        else {
            val pageText = book.page(currentPage)?.text.orEmpty()
            fun inScope(anchor: BookCompanionAnchor?): Boolean {
                if (anchor == null || anchor.pdfPage != currentPage || anchor.endUtf16 <= currentPageOffset) return false
                if (screenBlocks == null) return true
                return anchor.startUtf16 < anchor.endUtf16 && anchor.quoteAnchor.isNotBlank() &&
                    pageText.startsWith(anchor.quoteAnchor, anchor.startUtf16) &&
                    screenBlocks.any { block -> anchor.startUtf16 >= block.start && anchor.endUtf16 <= block.end &&
                        anchor.quoteAnchor.length <= block.end - anchor.startUtf16 }
            }
            val points = pack.keyPoints.filter { inScope(it.anchor) }
            if (points.isEmpty()) null else pack.copy(
                coreQuestion = currentSection?.title.orEmpty(),
                keyPoints = points,
                jargonGlosses = pack.jargonGlosses.filter { gloss ->
                    inScope(gloss.anchor) || (gloss.anchor == null && gloss.term.isNotBlank() &&
                        (screenBlocks?.any { it.text.contains(gloss.term, ignoreCase = true) }
                            ?: pageText.contains(gloss.term, ignoreCase = true)))
                },
            )
        }
    }
    val scroll = rememberScrollState()

    LaunchedEffect(book.id, modePackJson) {
        bookMapMeta = withContext(Dispatchers.IO) { StudyModeStore.loadBookMapMeta(library, book.id) }
    }

    LaunchedEffect(book.id, chapterGroup?.chapterId, chapterNum, studyPreference.mode, studyPreference.detailLevel, studyPreference.explainLang, studyPreference.inquiryQuestion, explainer.loaded, explainer.identity, aiEnabled, modeFetchNonce) {
        val group = chapterGroup ?: return@LaunchedEffect
        val fallbackAny = studyPreference.detailLevel == ExplainDetailLevel.DETAILED &&
            studyPreference.explainLang == ExplainLanguage.BILINGUAL &&
            studyPreference.inquiryQuestion.isBlank()
        val cached = withContext(Dispatchers.IO) {
            StudyModeStore.loadRawModePackJson(
                library = library,
                bookId = book.id,
                chapterNum = chapterNum,
                mode = studyPreference.mode,
                detailLevel = studyPreference.detailLevel,
                explainLang = studyPreference.explainLang,
                inquiryQuestion = studyPreference.inquiryQuestion,
                fallbackAnyVariant = fallbackAny,
            )
        }
        if (cached != null) {
            modePackJson = cached
            modeStreamingText = ""
            modeLoading = false
            modeError = null
            return@LaunchedEffect
        }
        if (studyPreference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) {
            modePackJson = null
            modeStreamingText = ""
            modeLoading = false
            modeError = null
            return@LaunchedEffect
        }
        if (studyPreference.mode == BookStudyMode.QUESTION_DRIVEN && studyPreference.inquiryQuestion.trim().length < 3) {
            modeStreamingText = ""
            modeLoading = false
            modeError = null
            return@LaunchedEffect
        }
        if (!aiEnabled || !explainer.loaded || explainer.identity == null) {
            modeLoading = false
            modeError = null
            return@LaunchedEffect
        }
        modeLoading = true
        modeStreamingChars = 0
        modeStreamingText = ""
        modeError = null
        try {
            val companionGroup = proseGroup ?: return@LaunchedEffect
            val wire = withContext(Dispatchers.IO) {
                StudyModeWireBuilder.buildModeRequestJson(library, book, companionGroup, chapterNum, studyPreference)
            }
            modeSourcePages = wire.getValue("pages").jsonArray.map { it.jsonObject.getValue("pdfPage").jsonPrimitive.int }
            val res = explainer.fetchStudyModePack(book.id, studyPreference.mode.name, wire) { chars, _, delta ->
                modeStreamingChars = maxOf(modeStreamingChars, chars)
                if (delta.isNotEmpty()) {
                    modeStreamingText = (modeStreamingText + delta).takeLast(4000)
                }
            }
            val body = res.body
            if (body != null) {
                val jsonStr = body.toString()
                withContext(Dispatchers.IO) {
                    StudyModeStore.saveModePackJson(
                        library = library,
                        bookId = book.id,
                        chapterNum = chapterNum,
                        mode = studyPreference.mode,
                        detailLevel = studyPreference.detailLevel,
                        explainLang = studyPreference.explainLang,
                        inquiryQuestion = studyPreference.inquiryQuestion,
                        packJson = jsonStr,
                    )
                }
                modePackJson = jsonStr
                modeStreamingText = ""
                modeLoading = false
                modeError = null
            } else {
                modeLoading = false
                modeError = res.error ?: "当前离线，已显示本地章节结构。"
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            modeLoading = false
            modeError = "当前离线，已显示本地章节结构。"
        }
    }

    fun triggerBookMapBatch() {
        if (bookMapBatchBusy || !aiEnabled || explainer.identity == null) return
        bookMapBatchBusy = true
        scope.launch {
            try {
                val wire = withContext(Dispatchers.IO) {
                    StudyModeWireBuilder.buildBookMapBatchRequestJson(library, book, studyPreference)
                }
                val res = explainer.fetchBookMapBatch(book.id, wire)
                val body = res.body
                if (body != null) {
                    withContext(Dispatchers.IO) {
                        StudyModeStore.saveBookMapBatchResult(
                            library = library,
                            bookId = book.id,
                            batchJson = body,
                            detailLevel = studyPreference.detailLevel,
                            explainLang = studyPreference.explainLang,
                        )
                    }
                    bookMapMeta = withContext(Dispatchers.IO) { StudyModeStore.loadBookMapMeta(library, book.id) }
                    modeFetchNonce += 1
                } else {
                    modeError = res.error ?: "整本全书地图暂无法更新。"
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                modeError = "连不上学习服务，保留本地章节地图。"
            } finally {
                bookMapBatchBusy = false
            }
        }
    }

    LaunchedEffect(book.id, currentSection?.id, chapterGroup?.chapterId, draft.pretestSubmitted.size, draft.practiceSubmitted.size) {
        sectionMastery = withContext(Dispatchers.IO) {
            val all = library.conceptMasteryForBook(book.id)
            val secId = currentSection?.id
            val chapId = chapterGroup?.chapterId
            all.firstOrNull { it.tocNodeId == secId }
                ?: all.firstOrNull { it.tocNodeId == chapId }
        }
    }

    LaunchedEffect(explainer, aiEnabled) { if (aiEnabled && !explainer.loaded) explainer.load() }

    LaunchedEffect(book.id, chapterGroup?.chapterId, explainer.loaded, explainer.identity, aiEnabled, fetchNonce, pageOnly) {
        val group = chapterGroup ?: return@LaunchedEffect
        val cached = withContext(Dispatchers.IO) { library.cachedChapterCompanion(book.id, group.chapterId) }
        val staleCaptionCache = cached != null && ChapterCompanionGrouping.hasStaleLeadingCaption(book, group, cached)
        if (cached != null && !staleCaptionCache) {
            packState = CompanionPackState.Ready(cached)
            return@LaunchedEffect
        }
        val sanitizedCached = if (cached != null && staleCaptionCache) {
            ChapterCompanionGrouping.sanitizeCachedPack(book, group, cached)
        } else null
        if (pageOnly) {
            packState = sanitizedCached?.let { CompanionPackState.Ready(it) } ?: CompanionPackState.Idle
            return@LaunchedEffect
        }
        if (aiEnabled && !explainer.loaded) {
            packState = CompanionPackState.Loading
            return@LaunchedEffect
        }
        if (!aiEnabled || explainer.identity == null) {
            if (sanitizedCached != null) {
                withContext(Dispatchers.IO) { library.saveChapterCompanion(book.id, group.chapterId, sanitizedCached) }
                packState = CompanionPackState.Ready(sanitizedCached)
            } else {
                packState = CompanionPackState.Idle
            }
            return@LaunchedEffect
        }
        packState = CompanionPackState.Loading
        suspend fun fallbackToSanitizedOrFailed(message: String, trouble: ExplainTrouble) {
            if (sanitizedCached != null) {
                withContext(Dispatchers.IO) { library.saveChapterCompanion(book.id, group.chapterId, sanitizedCached) }
                packState = CompanionPackState.Ready(sanitizedCached)
            } else {
                packState = CompanionPackState.Failed(message, trouble)
            }
        }
        try {
            val companionGroup = proseGroup ?: return@LaunchedEffect
            val firstGroup = ChapterCompanionProgressive.firstSectionGroup(companionGroup, currentSection?.id)
            val restGroup = firstGroup?.sections?.singleOrNull()?.id?.let {
                ChapterCompanionProgressive.remainingSectionGroup(companionGroup, it)
            }
            if (firstGroup != null && restGroup != null) {
                val firstWire = ChapterCompanionGrouping.buildWireRequest(book, firstGroup)
                val firstBlocks = ChapterCompanionGrouping.wireBlocks(firstGroup)
                when (val firstRes = explainer.fetchChapterCompanion(firstWire, firstBlocks)) {
                    is ChapterCompanionFetchResult.Ready -> {
                        packState = CompanionPackState.Ready(firstRes.pack, expanding = true)
                        val restWire = ChapterCompanionGrouping.buildWireRequest(book, restGroup)
                        val restBlocks = ChapterCompanionGrouping.wireBlocks(restGroup)
                        when (val restRes = explainer.fetchChapterCompanion(restWire, restBlocks)) {
                            is ChapterCompanionFetchResult.Ready -> {
                                val merged = ChapterCompanionProgressive.merge(firstRes.pack, restRes.pack)
                                withContext(Dispatchers.IO) { library.saveChapterCompanion(book.id, group.chapterId, merged) }
                                packState = CompanionPackState.Ready(merged, expanding = false)
                            }
                            is ChapterCompanionFetchResult.Failed -> {
                                packState = CompanionPackState.Ready(firstRes.pack, expanding = false)
                            }
                        }
                    }
                    is ChapterCompanionFetchResult.Failed -> {
                        fallbackToSanitizedOrFailed(firstRes.message, firstRes.trouble)
                    }
                }
            } else {
                val wire = ChapterCompanionGrouping.buildWireRequest(book, companionGroup)
                val blocks = ChapterCompanionGrouping.wireBlocks(companionGroup)
                when (val res = explainer.fetchChapterCompanion(wire, blocks)) {
                    is ChapterCompanionFetchResult.Ready -> {
                        withContext(Dispatchers.IO) { library.saveChapterCompanion(book.id, group.chapterId, res.pack) }
                        packState = CompanionPackState.Ready(res.pack, expanding = false)
                    }
                    is ChapterCompanionFetchResult.Failed -> {
                        fallbackToSanitizedOrFailed(res.message, res.trouble)
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            fallbackToSanitizedOrFailed("本章伴读暂时连不上，已有练习草稿已保留。", ExplainTrouble.Other)
        }
    }

    fun updateDraft(transform: (ChapterPracticeDraft) -> ChapterPracticeDraft) {
        val nodeId = chapterGroup?.chapterId ?: currentSection?.id ?: "p$currentPage"
        val updated = transform(draft).copy(
            bookId = book.id,
            tocNodeId = nodeId,
            page = currentPage,
            companionOpen = true,
            updatedAt = System.currentTimeMillis(),
        )
        onDraftChange(updated)
    }

    Column(modifier.fillMaxSize().testTag("single-book-companion")) {
        // In-place Study Mode bar + 5-module selector strip.
        // On wide bottom-sheet drawers (>= 520dp, e.g. 752x552 half-screen window), merge both into a single
        // compact row so companion-module-body keeps enough height and scroll hit-area instead of stacking two bars.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val mergeModeAndModules = studyPreference.mode == BookStudyMode.READ_THROUGH && maxWidth >= 520.dp
            val showKnobsInMerged = maxWidth >= 600.dp
            val pillShape = RoundedCornerShape(CoachV7.Radius.s)
            val activeBg = colors.actionPrimary.copy(alpha = 0.14f)
            val modulePills: @Composable (Modifier) -> Unit = { rowModifier ->
                Row(
                    rowModifier
                        .horizontalScroll(rememberScrollState())
                        .testTag("companion-module-strip"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (mod in CompanionModuleTab.entries) {
                        val selected = activeModule == mod
                        Text(
                            mod.shortLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) colors.actionPrimary else colors.fgSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(pillShape)
                                .background(if (selected) activeBg else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (selected) colors.actionPrimary.copy(alpha = 0.35f) else colors.borderSubtle,
                                    pillShape,
                                )
                                .clickable(role = Role.Tab) {
                                    updateDraft { it.copy(activeModule = mod.name) }
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag(mod.tag),
                        )
                    }
                }
            }

            if (mergeModeAndModules) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("companion-mode-bar"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudyModeSelectorButton(
                            preference = studyPreference,
                            onUpdatePreference = onUpdateStudyPreference,
                            compact = true,
                            tag = "companion-mode-trigger",
                        )
                        modulePills(Modifier.weight(1f))
                        if (showKnobsInMerged) {
                            CoachCardMeta(
                                "${studyPreference.detailLevel.labelZh} · ${studyPreference.explainLang.shortZh}",
                                Modifier.testTag("companion-mode-knobs-summary"),
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("companion-mode-bar"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudyModeSelectorButton(
                            preference = studyPreference,
                            onUpdatePreference = onUpdateStudyPreference,
                            compact = true,
                            tag = "companion-mode-trigger",
                        )
                        CoachCardMeta(
                            "${studyPreference.detailLevel.labelZh} · ${studyPreference.explainLang.shortZh}",
                            Modifier.testTag("companion-mode-knobs-summary"),
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.5f))

                    // 5-module selector strip shown in READ_THROUGH mode (zero PrimaryButton here to obey UX-4)
                    if (studyPreference.mode == BookStudyMode.READ_THROUGH) {
                        modulePills(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(14.dp)
                    .testTag("companion-module-body"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val summarySection = proseGroup?.sections?.firstOrNull { it.id == currentSection?.id }
                val endOffset = summarySection?.blocks?.lastOrNull()?.end?.minus(1)
                if (readingExplanation && LocalReadingSectionSummary.current && summarySection != null && endOffset != null &&
                    currentPage == summarySection.lastPage && (wholeOriginalPageVisible || currentPageOffset >= endOffset ||
                        visiblePageRanges.orEmpty().any { endOffset in it })) {
                    var dismissed by remember(book.id, summarySection.id) { mutableStateOf(false) }
                    if (!dismissed) {
                        val pack = (packState as? CompanionPackState.Ready)?.pack
                        val questions = pack?.optionalPractice.orEmpty().withIndex().filter { (_, quiz) ->
                            quiz.sectionId == summarySection.id && quiz.options.size >= 2 && quiz.correctOption in quiz.options
                        }.take(2)
                        val points = pack?.keyPoints.orEmpty().filter { it.sectionId == summarySection.id }.map { it.summary }
                        SectionEndSummaryCard(
                            section = summarySection,
                            summaryLines = points.ifEmpty { StudyCoachCourse.localSectionSummary(summarySection) },
                            practices = questions.map { (_, q) -> Practice(q.stem, q.anchor?.quoteAnchor.orEmpty(), q.correctOption, q.options) },
                            answers = questions.mapIndexedNotNull { i, (_, q) ->
                                draft.practiceSelections[q.id]?.takeIf { q.id in draft.practiceSubmitted }?.let {
                                    i to PracticeAnswer(text = it, revealed = true, understood = it == q.correctOption)
                                }
                            }.toMap(),
                            onAnswer = { i, practice, answer ->
                                val (packIndex, q) = questions[i]
                                updateDraft {
                                    it.copy(
                                        practiceSelections = if (answer.revealed) it.practiceSelections + (q.id to answer.text)
                                            else it.practiceSelections - q.id,
                                        practiceSubmitted = if (answer.revealed) it.practiceSubmitted + q.id else it.practiceSubmitted - q.id,
                                    )
                                }
                                onCommitQuizAnswer(summarySection, 100 + packIndex, practice, answer.copy(understood = answer.text == q.correctOption))
                            },
                            onOpenMainline = onOpenSectionMainline?.let { open -> { open(currentSectionIndex) } },
                            onDismiss = { dismissed = true },
                        )
                    }
                }
                if (readingExplanation) {
                    val chapter = remember(book, currentPage, currentPageOffset) {
                        BookChapterResolver.chapterForPage(book, currentPage, currentPageOffset)
                    }
                    val first = if (pageOnly) currentPage else chapterGroup?.firstPage ?: currentPage
                    val last = if (pageOnly) currentPage else chapterGroup?.lastPage ?: currentPage
                    val title = if (pageOnly) {
                        chapter?.title?.takeIf { it.isNotBlank() } ?: currentSection?.title?.takeIf { it.isNotBlank() }
                    } else chapterGroup?.chapterTitle
                    Text(explanationScope(first, last, title ?: book.title),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("companion-explanation-scope"))
                    if (canChooseScreen && chapterGroup != null) {
                        V7TextButton({ wholeChapter = !wholeChapter }, modifier = Modifier.testTag("companion-change-scope")) {
                            Text(if (wholeChapter) "只讲这一页" else "讲整章")
                        }
                    }
                    if (pageOnly) {
                        if (cachedPagePack != null) {
                            KeyPointsModuleView(
                                pack = cachedPagePack, questionLabel = "当前小节",
                                collapseBasicJargon = MasteryEngine.shouldCollapseBasicTerminology(sectionMastery),
                                onOpenAnchor = onOpenAnchor, onAskTerm = onAskAboutText,
                                onJumpPretest = { updateDraft { it.copy(activeModule = CompanionModuleTab.PRETEST.name) } },
                            )
                        } else key(book.id, currentPage, currentPageOffset, visiblePageRanges) {
                            PageExplanation(
                                book = book,
                                page = currentPage,
                                explainer = explainer,
                                enabled = aiEnabled,
                                onOpen = { block ->
                                    onOpenAnchor(BookCompanionAnchor(block.page, block.id, block.start, block.end, block.text))
                                },
                                onLogin = onOpenLogin,
                                currentPageOffset = currentPageOffset,
                                visibleRanges = visiblePageRanges,
                                library = library,
                            )
                        }
                    }
                    if (!pageOnly && modeError != null) RetrievedPageLinks(modeSourcePages, { "第 $it 页" }, onJumpPage)
                }
                if (chapterGroup == null) {
                    if (pageOnly) bottomContent?.invoke() else CoachEmptyState(
                        title = "当前页暂未归入正文小节。",
                        hint = "翻到正文第 1 章后，这里会自动呈现本章伴读与练习。",
                    )
                    return@Column
                }

                val effectiveMode = if (!studyPreference.examMode && studyPreference.mode == BookStudyMode.EXAM_FOCUS) {
                    BookStudyMode.READ_THROUGH
                } else {
                    studyPreference.mode
                }
                // Header meta line
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val headerPrefix = if (effectiveMode == BookStudyMode.READ_THROUGH) {
                        activeModule.fullTitle
                    } else {
                        effectiveMode.displayTitle(studyPreference.examMode)
                    }
                    Text(
                        "$headerPrefix · ${chapterGroup.chapterTitle}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.fgPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("companion-module-title"),
                    )
                    CoachCardMeta(
                        "第 ${chapterGroup.firstPage}–${chapterGroup.lastPage} 页 · 随时可停，进度与草稿自动保存",
                        Modifier.testTag("companion-module-meta"),
                    )
                }

                if (effectiveMode != BookStudyMode.READ_THROUGH) {
                    val readyPack = (packState as? CompanionPackState.Ready)?.pack
                    ModeDistinctCompanionSurface(
                        book = book,
                        library = library,
                        currentPage = currentPage,
                        preference = studyPreference,
                        chapterGroup = chapterGroup,
                        currentSectionIndex = currentSectionIndex,
                        pack = readyPack,
                        draft = draft,
                        onUpdateDraft = { updateDraft(it) },
                        onDiagnosticAnswer = onCommitPretestAnswer ?: onCommitQuizAnswer,
                        onUpdateInquiry = { q ->
                            val normalized = q.trim()
                            if (normalized != studyPreference.inquiryQuestion.trim()) {
                                onUpdateStudyPreference(studyPreference.copy(inquiryQuestion = normalized, userLocked = true), false)
                            } else if (normalized.length >= 3 && modePackJson == null && !modeLoading) {
                                modeFetchNonce += 1
                            }
                        },
                        onJumpPage = onJumpPage,
                        onOpenAnchor = onOpenAnchor,
                        modePackJson = modePackJson,
                        modeStreamingChars = modeStreamingChars,
                        modeStreamingText = modeStreamingText,
                        modeLoading = modeLoading,
                        modeError = modeError,
                        bookMapMeta = bookMapMeta,
                        bookMapBatchLoading = bookMapBatchBusy,
                        onRequestBookMapBatch = if (aiEnabled && explainer.identity != null) { { triggerBookMapBatch() } } else null,
                        onRetryModePack = { modeFetchNonce += 1 },
                    )
                } else {
                    val readThroughLive = remember(modePackJson) {
                        modePackJson?.let { StudyModeStore.parseReadThroughModePack(it) }
                    }
                    if (modeLoading && readThroughLive == null && packState !is CompanionPackState.Ready) {
                        val streamStatusLabel = if (modeStreamingChars > 0) "正在流式整理精读导图（已接收 $modeStreamingChars 字）…" else "正在按节整理本章伴读…"
                        val streamPreview = remember(modeStreamingText) { extractReadableStreamPreview(modeStreamingText) }
                        PaperCard(Modifier.fillMaxWidth().testTag("mode-streaming-status"), color = colors.surface2, contentPadding = PaddingValues(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                CoachLoadingDots(label = streamStatusLabel)
                                if (streamPreview.isNotBlank()) {
                                    Text(
                                        streamPreview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.fgSecondary,
                                        modifier = Modifier.testTag("mode-streaming-preview"),
                                    )
                                }
                            }
                        }
                    }
                    if (readThroughLive != null && activeModule == CompanionModuleTab.KEY_POINTS) {
                        LiveReadThroughOverviewCard(
                            pack = readThroughLive,
                            onOpenAnchor = onOpenAnchor,
                        )
                    }
                    if (!pageOnly) when (val state = packState) {
                    CompanionPackState.Loading -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CoachLoadingDots(label = "正在按节整理本章伴读…")
                        }
                    }
                    is CompanionPackState.Failed -> {
                        val pages = remember(book.id, chapterGroup) {
                            ChapterCompanionGrouping.wireBlocks(ChapterCompanionGrouping.filterForCompanion(book, chapterGroup)).distinctBy { it.page }
                        }
                        RetrievedPageLinks(pages, { "第 ${it.page} 页" }, { onJumpPage(it.page) })
                        CoachErrorBanner(
                            message = state.message,
                            retryLabel = if (state.trouble.needsPairing) "去登录" else "重试",
                            onRetry = {
                                if (state.trouble.needsPairing) onOpenLogin()
                                else fetchNonce++
                            },
                            modifier = Modifier.testTag("companion-error-banner"),
                        )
                    }
                    CompanionPackState.Idle -> {
                        when {
                            originalOnly -> CoachCardMeta("只看原文 · 可以在学法中调整讲解详略。")
                            !aiEnabled -> CoachEmptyState(
                                title = "伴读整理已在设置中关闭。",
                                hint = "打开设置里的伴读开关后，即可自动生成本章要点与练习。",
                            )
                            explainer.loaded && explainer.identity == null -> CoachEmptyState(
                                title = "登录后可使用本章伴读。",
                                hint = "本地阅读与已缓存草稿不受影响，登录后可按节生成要点与练习。",
                                actionLabel = "去连接服务",
                                onAction = onOpenLogin,
                            )
                            else -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                                CoachLoadingDots(label = "正在准备本章伴读…")
                            }
                        }
                    }
                    is CompanionPackState.Ready -> {
                        val pack = state.pack
                        when (activeModule) {
                            CompanionModuleTab.KEY_POINTS -> KeyPointsModuleView(
                                pack = pack,
                                collapseBasicJargon = MasteryEngine.shouldCollapseBasicTerminology(sectionMastery),
                                weakSectionHint = if (MasteryEngine.shouldHighlightWeakSection(sectionMastery)) {
                                    sectionMastery?.let { MasteryEngine.buildHomeFocusMeta(it, currentSection?.title, currentPage) }
                                } else null,
                                onOpenAnchor = onOpenAnchor,
                                onAskTerm = onAskAboutText,
                                onJumpPretest = { updateDraft { it.copy(activeModule = CompanionModuleTab.PRETEST.name) } },
                            )
                            CompanionModuleTab.PRETEST -> PretestModuleView(
                                pack = pack,
                                draft = draft,
                                currentSection = currentSection,
                                expanding = state.expanding,
                                onSelectOption = { qId, opt ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = qId,
                                            pretestSelections = it.pretestSelections + (qId to opt),
                                        )
                                    }
                                },
                                onToggleGuessed = { qId ->
                                    updateDraft {
                                        val cur = it.pretestGuessed[qId] == true
                                        it.copy(
                                            activeQuestionId = qId,
                                            pretestGuessed = it.pretestGuessed + (qId to !cur),
                                        )
                                    }
                                },
                                onSubmitQuestion = { idx, quiz ->
                                    val chosen = draft.pretestSelections[quiz.id].orEmpty()
                                    val guessed = draft.pretestGuessed[quiz.id] == true
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = quiz.id,
                                            pretestSubmitted = it.pretestSubmitted + quiz.id,
                                        )
                                    }
                                    val sec = book.lessons.firstOrNull { it.id == quiz.sectionId } ?: currentSection
                                    if (sec != null && chosen.isNotBlank()) {
                                        val commit = onCommitPretestAnswer ?: onCommitQuizAnswer
                                        commit(
                                            sec,
                                            idx,
                                            Practice(quiz.stem, quiz.anchor?.quoteAnchor.orEmpty(), quiz.correctOption, quiz.options, quiz.anchor?.pdfPage),
                                            PracticeAnswer(text = chosen, revealed = true, guessed = guessed),
                                        )
                                    }
                                },
                                onOpenAnchor = onOpenAnchor,
                            )
                            CompanionModuleTab.DERIVATION -> DerivationModuleView(
                                pack = pack,
                                onOpenAnchor = onOpenAnchor,
                                onAskStep = onAskAboutText,
                            )
                            CompanionModuleTab.PRACTICE -> OptionalPracticeModuleView(
                                book = book,
                                pack = pack,
                                draft = draft,
                                currentSection = currentSection,
                                explainer = explainer,
                                library = library,
                                gradeResponses = gradeResponsesByQuestion,
                                onGradeResponse = { qId, resp -> gradeResponsesByQuestion = gradeResponsesByQuestion + (qId to resp) },
                                onSelectOption = { qId, opt ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = qId,
                                            practiceSelections = it.practiceSelections + (qId to opt),
                                        )
                                    }
                                },
                                onEditText = { qId, text, cursor ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = qId,
                                            practiceTextDrafts = it.practiceTextDrafts + (qId to text),
                                            practiceCursorPositions = it.practiceCursorPositions + (qId to cursor),
                                        )
                                    }
                                },
                                onToggleGuessed = { qId ->
                                    updateDraft {
                                        val cur = it.practiceGuessed[qId] == true
                                        it.copy(
                                            activeQuestionId = qId,
                                            practiceGuessed = it.practiceGuessed + (qId to !cur),
                                        )
                                    }
                                },
                                onSubmit = { idx, quiz ->
                                    val chosen = if (quiz.options.isNotEmpty()) {
                                        draft.practiceSelections[quiz.id].orEmpty()
                                    } else {
                                        draft.practiceTextDrafts[quiz.id].orEmpty()
                                    }
                                    val guessed = draft.practiceGuessed[quiz.id] == true
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = quiz.id,
                                            practiceSubmitted = it.practiceSubmitted + quiz.id,
                                        )
                                    }
                                    val sec = book.lessons.firstOrNull { it.id == quiz.sectionId } ?: currentSection
                                    if (sec != null && chosen.isNotBlank()) {
                                        onCommitQuizAnswer(
                                            sec,
                                            100 + idx,
                                            Practice(quiz.stem, quiz.anchor?.quoteAnchor.orEmpty(), quiz.correctOption, quiz.options, quiz.anchor?.pdfPage),
                                            PracticeAnswer(text = chosen, revealed = true, understood = chosen == quiz.correctOption, guessed = guessed),
                                        )
                                    }
                                },
                                onOpenAnchor = onOpenAnchor,
                                onOpenLogin = onOpenLogin,
                                onInputFocusChanged = onInputFocusChanged,
                            )
                            CompanionModuleTab.EXERCISE_HINTS -> ExerciseHintsModuleView(
                                pack = pack,
                                draft = draft,
                                onRevealTier = { exId, nextTier ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = exId,
                                            exerciseHintLevels = it.exerciseHintLevels + (exId to nextTier.coerceIn(0, 3)),
                                        )
                                    }
                                },
                                onOpenAnchor = onOpenAnchor,
                            )
                        }
                    }
                }
                }
                bottomContent?.invoke()
            }
            if (scroll.maxValue > 0) {
                BookScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun KeyPointsModuleView(
    pack: BookCompanionPack,
    questionLabel: String = "核心问题",
    collapseBasicJargon: Boolean = false,
    weakSectionHint: String? = null,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onAskTerm: (String) -> Unit,
    onJumpPretest: () -> Unit,
) {
    val colors = CoachTheme.colors
    if (!weakSectionHint.isNullOrBlank()) {
        CoachCardMeta(
            text = weakSectionHint,
            modifier = Modifier.fillMaxWidth().testTag("companion-weak-hint"),
        )
    }
    if (pack.coreQuestion.isNotBlank()) {
        PaperCard(Modifier.fillMaxWidth().testTag("companion-core-question"), contentPadding = PaddingValues(12.dp), contentSpacing = 6.dp) {
            Text(questionLabel, style = MaterialTheme.typography.labelMedium, color = v7Pine())
            Text(pack.coreQuestion, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
        }
    }
    if (pack.keyPoints.isEmpty()) {
        CoachEmptyState(title = "本章暂无提取要点。", hint = "可直接阅读原页，或划选句子点「讲清」。")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("companion-keypoints-list")) {
            pack.keyPoints.forEachIndexed { idx, item ->
                PaperCard(Modifier.fillMaxWidth().testTag("companion-kp-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 6.dp) {
                    Text("${idx + 1}. ${item.summary}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                    item.anchor?.let { anchor ->
                        AnchorChip(anchor = anchor, onClick = { onOpenAnchor(anchor) })
                    }
                }
            }
        }
    }
    if (pack.jargonGlosses.isNotEmpty()) {
        var jargonExpanded by remember(pack.chapterId, collapseBasicJargon) { mutableStateOf(!collapseBasicJargon) }
        PaperCard(Modifier.fillMaxWidth().testTag("companion-jargon-card"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { jargonExpanded = !jargonExpanded }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag(if (jargonExpanded) "companion-jargon-header" else "companion-jargon-collapsed"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (jargonExpanded) "专业词扫障" else "专业词扫障（${pack.jargonGlosses.size} 项已掌握，点开查看 ▸）",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (jargonExpanded) v7Pine() else colors.fgSecondary,
                )
            }
            if (jargonExpanded) {
                for (g in pack.jargonGlosses) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            g.term,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.fgPrimary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 72.dp, max = 130.dp),
                        )
                        Text(g.plainMeaning, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.weight(1f))
                        V7TextButton({ onAskTerm("解释术语 ${g.term} 在本节里的准确含义。") }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text("问", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (pack.pretest.isNotEmpty()) {
        PaperButton(onClick = onJumpPretest, modifier = Modifier.fillMaxWidth().testTag("companion-go-pretest")) {
            Text("做 ${pack.pretest.size} 道读前自测")
        }
    }
}

@Composable
private fun PretestModuleView(
    pack: BookCompanionPack,
    draft: ChapterPracticeDraft,
    currentSection: BookLesson?,
    expanding: Boolean = false,
    onSelectOption: (String, String) -> Unit,
    onToggleGuessed: (String) -> Unit,
    onSubmitQuestion: (Int, BookCompanionQuiz) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.pretest.isEmpty()) {
        if (expanding) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CoachLoadingDots(label = "正在继续整理本章自测题…")
            }
            return
        }
        CoachEmptyState(title = "本章暂无读前自测题。", hint = "直接阅读原页正文，随时可切到「选做练习」。")
        return
    }
    if (expanding) {
        CoachCardMeta("第一节自测已就绪，其余小节正在后台整理…")
    }
    val activeIdx = pack.pretest.indexOfFirst { it.id == draft.activeQuestionId }.takeIf { it >= 0 }
        ?: pack.pretest.indexOfFirst { it.id !in draft.pretestSubmitted }.takeIf { it >= 0 }
        ?: 0

    Text(
        "先猜一猜不计错题惩罚，提交后标出原书对应页码供带着问题去读。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    pack.pretest.forEachIndexed { idx, quiz ->
        val selected = draft.pretestSelections[quiz.id]
        val guessed = draft.pretestGuessed[quiz.id] == true
        val submitted = quiz.id in draft.pretestSubmitted
        val isPrimaryCard = idx == activeIdx && !submitted
        PaperCard(Modifier.fillMaxWidth().testTag("pretest-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text("第 ${idx + 1} / ${pack.pretest.size} 题", style = MaterialTheme.typography.labelSmall, color = v7Pine())
            Text(quiz.stem, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            quiz.options.forEachIndexed { optIdx, opt ->
                val chosen = selected == opt
                val borderColor = when {
                    submitted && opt == quiz.correctOption -> v7Pine()
                    submitted && chosen && opt != quiz.correctOption -> v7Alert()
                    chosen -> colors.actionPrimary
                    else -> colors.borderSubtle
                }
                val shape = RoundedCornerShape(CoachV7.Radius.s)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (chosen) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                        .border(1.dp, borderColor, shape)
                        .clickable(enabled = !submitted) { onSelectOption(quiz.id, opt) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("pretest-opt-$idx-$optIdx"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(opt, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, modifier = Modifier.weight(1f))
                }
            }
            if (!submitted) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    V7TextButton(
                        onClick = { onToggleGuessed(quiz.id) },
                        modifier = Modifier.testTag("pretest-guess-$idx"),
                    ) {
                        Text(if (guessed) "✓ 这题是猜的" else "这题是猜的", color = if (guessed) colors.actionPrimary else colors.fgSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    if (isPrimaryCard) {
                        PrimaryButton(
                            onClick = { onSubmitQuestion(idx, quiz) },
                            enabled = !selected.isNullOrBlank(),
                            modifier = Modifier.testTag("pretest-submit-$idx"),
                        ) { Text("提交") }
                    } else {
                        PaperButton(
                            onClick = { onSubmitQuestion(idx, quiz) },
                            enabled = !selected.isNullOrBlank(),
                            modifier = Modifier.testTag("pretest-submit-$idx"),
                        ) { Text("提交") }
                    }
                }
            } else {
                val ok = selected == quiz.correctOption && !guessed
                Text(
                    when {
                        ok -> "回答准确。${quiz.revealHint}"
                        guessed && selected == quiz.correctOption -> "猜对了，已记入今日复习巩固。${quiz.revealHint}"
                        else -> "带着悬念去读原文。${quiz.revealHint}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) v7Pine() else colors.fgSecondary,
                    modifier = Modifier.testTag("pretest-feedback-$idx"),
                )
                if (quiz.explanation.isNotBlank()) {
                    Text(quiz.explanation, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                }
                quiz.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
            }
        }
    }
}

@Composable
private fun DerivationModuleView(
    pack: BookCompanionPack,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onAskStep: (String) -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.derivations.isEmpty()) {
        CoachEmptyState(title = "本章暂未检测到公式或图形推导。", hint = "读到含模型与坐标图的章节时，这里会展示分步模板。")
        return
    }
    pack.derivations.forEachIndexed { idx, drv ->
        PaperCard(Modifier.fillMaxWidth().testTag("derivation-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text(drv.title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
            if (drv.setup.isNotBlank()) {
                Text("设定：${drv.setup}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
            drv.steps.forEachIndexed { sIdx, step ->
                Text("${sIdx + 1}. $step", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
            }
            if (drv.graphCheck.isNotBlank()) {
                Text("看图核对：${drv.graphCheck}", style = MaterialTheme.typography.bodySmall, color = v7Pine())
            }
            if (drv.pitfall.isNotBlank()) {
                Text("常见失分点：${drv.pitfall}", style = MaterialTheme.typography.bodySmall, color = v7Alert())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                drv.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
                V7TextButton({ onAskStep("请一步步拆解「${drv.title}」的推导逻辑。") }) { Text("追问推导") }
            }
        }
    }
}

@Composable
private fun OptionalPracticeModuleView(
    book: Textbook,
    pack: BookCompanionPack,
    draft: ChapterPracticeDraft,
    currentSection: BookLesson?,
    explainer: BookExplainer,
    library: TextbookLibrary,
    gradeResponses: Map<String, PracticeGradeDiagnoseResponse>,
    onGradeResponse: (String, PracticeGradeDiagnoseResponse) -> Unit,
    onSelectOption: (String, String) -> Unit,
    onEditText: (String, String, Int) -> Unit,
    onToggleGuessed: (String) -> Unit,
    onSubmit: (Int, BookCompanionQuiz) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onOpenLogin: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    val gradingHost = LocalPracticeGradingHost.current
    var gradingInFlight by remember(book.id) { mutableStateOf<Set<String>>(emptySet()) }
    var gradeErrors by remember(book.id) { mutableStateOf<Map<String, Pair<String, ExplainTrouble>>>(emptyMap()) }

    fun launchGradeOrDiagnose(quiz: BookCompanionQuiz, answerText: String, mode: String, mcqSelected: String = "") {
        if (quiz.id in gradingInFlight) return
        val canCallServer = gradingHost != null || explainer.identity != null
        if (!canCallServer) return
        gradingInFlight = gradingInFlight + quiz.id
        gradeErrors = gradeErrors - quiz.id
        scope.launch {
            try {
                val sec = book.lessons.firstOrNull { it.id == quiz.sectionId } ?: currentSection
                val req = PracticeGradingJson.buildSingleBookRequest(
                    book = book,
                    section = sec,
                    quiz = quiz,
                    studentAnswer = answerText,
                    mode = mode,
                    mcqSelected = mcqSelected,
                )
                val result = if (gradingHost != null) {
                    gradingHost.gradeAndDiagnose(req)
                } else {
                    if (!explainer.loaded) explainer.load()
                    explainer.gradeAndDiagnosePractice(req)
                }
                when (result) {
                    is PracticeGradeDiagnoseResult.Ready -> {
                        val resp = result.response
                        onGradeResponse(quiz.id, resp)
                        withContext(Dispatchers.IO) {
                            val tocNodeId = sec?.id ?: quiz.sectionId
                            val canonicalName = sec?.title ?: quiz.sectionId
                            val targetPage = quiz.anchor?.pdfPage ?: sec?.firstPage ?: 1
                            val errCls = resp.diagnosis?.errorClass?.takeIf { it != MisconceptionErrorClass.NONE }
                            if (resp.rubricItems.isNotEmpty()) {
                                val ratio = if (resp.maxMarks > 0.0) {
                                    (resp.totalAwarded / resp.maxMarks).coerceIn(0.0, 1.0)
                                } else {
                                    resp.rubricItems.map {
                                        when (it.verdict) {
                                            "FULL" -> 1.0
                                            "PARTIAL" -> 0.5
                                            else -> 0.0
                                        }
                                    }.average()
                                }
                                library.recordMasteryEvidence(
                                    bookId = book.id,
                                    tocNodeId = tocNodeId,
                                    page = targetPage,
                                    canonicalName = canonicalName,
                                    signal = LearnerSignalType.RUBRIC_GRADE,
                                    scoreRatio = ratio,
                                    guessed = draft.practiceGuessed[quiz.id] == true,
                                    errorClass = errCls,
                                )
                            } else if (errCls != null) {
                                library.recordMasteryEvidence(
                                    bookId = book.id,
                                    tocNodeId = tocNodeId,
                                    page = targetPage,
                                    canonicalName = canonicalName,
                                    signal = LearnerSignalType.MISCONCEPTION_DIAG,
                                    errorClass = errCls,
                                )
                            }
                        }
                    }
                    is PracticeGradeDiagnoseResult.Failed -> {
                        gradeErrors = gradeErrors + (quiz.id to (result.message to result.trouble))
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                gradeErrors = gradeErrors + (quiz.id to ("逐点批改暂时不可用，你的草稿已保留。" to ExplainTrouble.Other))
            } finally {
                gradingInFlight = gradingInFlight - quiz.id
            }
        }
    }

    if (pack.optionalPractice.isEmpty()) {
        CoachEmptyState(title = "本章暂无选做练习。", hint = "可先看「本章要点」或「原书章末习题三级提示」。")
        return
    }
    val activeIdx = pack.optionalPractice.indexOfFirst { it.id == draft.activeQuestionId }.takeIf { it >= 0 }
        ?: pack.optionalPractice.indexOfFirst { it.id !in draft.practiceSubmitted }.takeIf { it >= 0 }
        ?: 0

    Text(
        "每题独立选做、随时可停，输入内容实时落盘，退出后点首页「继续」精确回到此处。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    pack.optionalPractice.forEachIndexed { idx, quiz ->
        val isMcq = quiz.options.size >= 2
        val submitted = quiz.id in draft.practiceSubmitted
        val guessed = draft.practiceGuessed[quiz.id] == true
        val isPrimaryCard = idx == activeIdx && (if (isMcq) !submitted else true)
        val chosen = if (isMcq) draft.practiceSelections[quiz.id].orEmpty() else draft.practiceTextDrafts[quiz.id].orEmpty()
        val isGrading = quiz.id in gradingInFlight
        val gradeResp = gradeResponses[quiz.id]
        val gradeErr = gradeErrors[quiz.id]

        PaperCard(Modifier.fillMaxWidth().testTag("practice-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text("选做 ${idx + 1} / ${pack.optionalPractice.size}", style = MaterialTheme.typography.labelSmall, color = v7Pine())
            Text(quiz.stem, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            if (isMcq) {
                quiz.options.forEachIndexed { optIdx, opt ->
                    val selected = chosen == opt
                    val shape = RoundedCornerShape(CoachV7.Radius.s)
                    val borderColor = when {
                        submitted && opt == quiz.correctOption -> v7Pine()
                        submitted && selected && opt != quiz.correctOption -> v7Alert()
                        selected -> colors.actionPrimary
                        else -> colors.borderSubtle
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (selected) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                            .border(1.dp, borderColor, shape)
                            .clickable(enabled = !submitted) { onSelectOption(quiz.id, opt) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("practice-opt-$idx-$optIdx"),
                    ) {
                        Text(opt, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                    }
                }
            } else {
                val savedText = draft.practiceTextDrafts[quiz.id].orEmpty()
                val savedCursor = (draft.practiceCursorPositions[quiz.id] ?: savedText.length).coerceIn(0, savedText.length)
                var tfv by remember(quiz.id) { mutableStateOf(TextFieldValue(savedText, TextRange(savedCursor))) }
                LaunchedEffect(savedText) {
                    if (tfv.text != savedText) {
                        val c = (draft.practiceCursorPositions[quiz.id] ?: savedText.length).coerceIn(0, savedText.length)
                        tfv = TextFieldValue(savedText, TextRange(c))
                    }
                }
                val shape = RoundedCornerShape(CoachV7.Radius.btn)
                // Subjective draft stays editable so the learner can revise & resubmit after inspecting rubric notes.
                BasicTextField(
                    value = tfv,
                    onValueChange = { next ->
                        tfv = next
                        onEditText(quiz.id, next.text, next.selection.end)
                    },
                    enabled = !isGrading,
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary),
                    cursorBrush = SolidColor(colors.fgPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.surface1)
                        .border(1.dp, colors.borderSubtle, shape)
                        .onFocusChanged { onInputFocusChanged(it.isFocused) }
                        .padding(10.dp)
                        .testTag("practice-draft-input-$idx"),
                )
            }
            if (!isMcq || !submitted) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    V7TextButton(
                        onClick = { onToggleGuessed(quiz.id) },
                        modifier = Modifier.testTag("practice-guess-$idx"),
                    ) {
                        Text(if (guessed) "✓ 这题是猜的" else "这题是猜的", color = if (guessed) colors.actionPrimary else colors.fgSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    val actionLabel = when {
                        isMcq -> "核对"
                        isGrading -> "批改中…"
                        else -> "提交批改"
                    }
                    val onActionClick: () -> Unit = {
                        onSubmit(idx, quiz)
                        if (!isMcq) {
                            launchGradeOrDiagnose(quiz, chosen, "GRADE_AND_DIAGNOSE")
                        } else if (chosen.isNotBlank() && chosen != quiz.correctOption) {
                            launchGradeOrDiagnose(quiz, chosen, "DIAGNOSE_ONLY", mcqSelected = chosen)
                        }
                    }
                    if (isPrimaryCard) {
                        PrimaryButton(
                            onClick = onActionClick,
                            enabled = chosen.isNotBlank() && !isGrading,
                            modifier = Modifier.testTag("practice-submit-$idx"),
                        ) { Text(actionLabel) }
                    } else {
                        PaperButton(
                            onClick = onActionClick,
                            enabled = chosen.isNotBlank() && !isGrading,
                            modifier = Modifier.testTag("practice-submit-$idx"),
                        ) { Text(actionLabel) }
                    }
                }
            }
            if (isGrading) {
                CoachLoadingDots(label = if (isMcq) "正在分析错因与生成变式…" else "正在按课程原页逐点核对…")
            }
            if (gradeErr != null) {
                CoachErrorBanner(
                    message = gradeErr.first,
                    retryLabel = if (gradeErr.second.needsPairing) "去登录" else "重试",
                    onRetry = {
                        if (gradeErr.second.needsPairing) onOpenLogin()
                        else launchGradeOrDiagnose(quiz, chosen, if (isMcq) "DIAGNOSE_ONLY" else "GRADE_AND_DIAGNOSE", mcqSelected = if (isMcq) chosen else "")
                    },
                    modifier = Modifier.testTag("practice-grade-error-$idx"),
                )
            }
            if (gradeResp != null) {
                val jumpToPage: (Int, String) -> Unit = { pg, quote ->
                    val pageObj = book.page(pg)
                    val startIdx = if (pageObj != null && quote.isNotBlank()) pageObj.text.indexOf(quote, ignoreCase = true) else -1
                    val resolvedAnchor = if (startIdx >= 0) {
                        BookCompanionAnchor(pg, "${book.id}:p$pg:$startIdx", startIdx, startIdx + quote.length, pageObj!!.text.substring(startIdx, startIdx + quote.length))
                    } else {
                        BookCompanionAnchor(pg, "${book.id}:p$pg:0", 0, 0, quote)
                    }
                    onOpenAnchor(resolvedAnchor)
                }
                if (gradeResp.rubricItems.isNotEmpty()) {
                    RubricAnnotationSheet(
                        studentDraft = chosen,
                        response = gradeResp,
                        onJumpPage = jumpToPage,
                    )
                }
                gradeResp.diagnosis?.takeIf { it.errorClass != MisconceptionErrorClass.NONE }?.let { diag ->
                    MisconceptionFeedbackCard(
                        diagnosis = diag,
                        variant = gradeResp.variant,
                        onJumpPage = jumpToPage,
                        onVariantMastered = { variant ->
                            val sec = book.lessons.firstOrNull { it.id == quiz.sectionId } ?: currentSection
                            scope.launch(Dispatchers.IO) {
                                library.recordMasteryEvidence(
                                    bookId = book.id,
                                    tocNodeId = sec?.id ?: quiz.sectionId,
                                    page = variant.pdfPage.coerceAtLeast(1),
                                    canonicalName = sec?.title ?: quiz.sectionId,
                                    signal = LearnerSignalType.POST_QUIZ_T1,
                                    scoreRatio = 1.0,
                                    guessed = false,
                                    errorClass = MisconceptionErrorClass.NONE,
                                    conceptKeyOverride = variant.conceptKey.takeIf(String::isNotBlank),
                                )
                            }
                        },
                    )
                }
            } else if (submitted && (isMcq || (!isGrading && gradeErr == null))) {
                if (isMcq || quiz.correctOption.isNotBlank()) {
                    Text("参考结论：${quiz.correctOption}", style = MaterialTheme.typography.bodySmall, color = v7Pine(), modifier = Modifier.testTag("practice-answer-$idx"))
                }
                if (quiz.explanation.isNotBlank()) {
                    Text(quiz.explanation, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                }
                quiz.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
            }
        }
    }
}

@Composable
private fun ExerciseHintsModuleView(
    pack: BookCompanionPack,
    draft: ChapterPracticeDraft,
    onRevealTier: (String, Int) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.exerciseHints.isEmpty()) {
        CoachEmptyState(title = "本章未识别到原书章末习题。", hint = "含 Exercises / 习题 节的章节会自动生成三级渐进提示。")
        return
    }
    Text(
        "按需逐级点开提示，已展开层级自动保存。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    pack.exerciseHints.forEachIndexed { idx, ex ->
        val level = (draft.exerciseHintLevels[ex.id] ?: 0).coerceIn(0, 3)
        PaperCard(Modifier.fillMaxWidth().testTag("exercise-hint-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ex.problemLabel, style = MaterialTheme.typography.labelLarge, color = v7Pine(), modifier = Modifier.weight(1f))
                ex.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
            }
            Text(ex.questionSummary, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            if (level >= 1) {
                Text(ex.hint1Direction, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, modifier = Modifier.testTag("exercise-hint1-$idx"))
            }
            if (level >= 2) {
                Text(ex.hint2Method, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, modifier = Modifier.testTag("exercise-hint2-$idx"))
            }
            if (level >= 3) {
                Text(ex.hint3Check, style = MaterialTheme.typography.bodySmall, color = v7Pine(), modifier = Modifier.testTag("exercise-hint3-$idx"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (level) {
                    0 -> PaperButton({ onRevealTier(ex.id, 1) }, modifier = Modifier.testTag("exercise-reveal-$idx")) { Text("提示 1") }
                    1 -> PaperButton({ onRevealTier(ex.id, 2) }, modifier = Modifier.testTag("exercise-reveal-$idx")) { Text("提示 2") }
                    2 -> PaperButton({ onRevealTier(ex.id, 3) }, modifier = Modifier.testTag("exercise-reveal-$idx")) { Text("核对结论") }
                    else -> V7TextButton({ onRevealTier(ex.id, 0) }, modifier = Modifier.testTag("exercise-reset-$idx")) { Text("收起提示") }
                }
            }
        }
    }
}

@Composable
private fun AnchorChip(anchor: BookCompanionAnchor, onClick: () -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    Text(
        "原文第 ${anchor.pdfPage} 页",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalV7Fonts.current.numbers,
        color = v7Pine(),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(shape)
            .background(v7Pine().copy(alpha = 0.08f))
            .border(1.dp, v7Pine().copy(alpha = 0.30f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("companion-anchor-p${anchor.pdfPage}"),
    )
}
