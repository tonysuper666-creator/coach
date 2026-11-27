package com.coach.desktop.books

import com.coach.ui.pageturn.PageTurnStyle
import com.coach.ui.pageturn.ScrollReadPosition
import com.coach.ui.pageturn.ScrollReadState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import com.coach.ui.adaptive.Hinge
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalDeskTypeControl
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.desktop.reader.ReaderSession
import com.coach.desktop.reader.ReaderDraftScope
import com.coach.desktop.reader.NotesSection
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLException

/**
 * 出处打开后这一页要标什么。练习依据只有页 + 原句；讲解出处还带着解析器核对过的块范围，
 * 所以同一页出现两句相同原文时，标的是被点的那一块，而不是第一次字符串命中。
 */
internal data class PracticeSource(val page: Int?, val quote: String?, val warning: String?, val target: BookSourceTarget? = null)

internal data class CitedPdfLocation(val boxes: com.coach.desktop.reader.PageBoxes, val located: com.coach.desktop.reader.Located)

/** Only called after the citation's exact UTF-16 range has been checked against the imported page. */
internal fun locateCitedPdfRange(page: PdfPageText, text: String, range: IntRange): CitedPdfLocation {
    val width = page.frame.cropBox.right - page.frame.cropBox.left
    val height = page.frame.cropBox.top - page.frame.cropBox.bottom
    val sideways = page.frame.rotationClockwise in listOf(90, 270)
    val displayWidth = if (sideways) height else width
    val displayHeight = if (sideways) width else height
    val valid = page.unit.text == text && range.first >= 0 && !range.isEmpty() && range.last < text.length
    val rects = if (valid) PdfTextSelection.boxesForVerifiedRange(page.unit, text,
        com.coach.domain.reader.ReaderTextRange(range.first, range.last + 1)).map { box ->
        com.coach.desktop.reader.WordBox(box.x * displayWidth, box.y * displayHeight,
            (box.x + box.width) * displayWidth, (box.y + box.height) * displayHeight, "")
    } else emptyList()
    val located = when {
        !valid -> com.coach.desktop.reader.Located.NotFound
        page.unit.spans.isEmpty() -> com.coach.desktop.reader.Located.NoTextLayer
        rects.isEmpty() -> com.coach.desktop.reader.Located.NotFound
        else -> com.coach.desktop.reader.Located.Found(rects)
    }
    return CitedPdfLocation(com.coach.desktop.reader.PageBoxes(displayWidth, displayHeight, rects), located)
}

@Composable
internal fun TextbookReader(
    ui: LibraryUi, library: TextbookLibrary, support: BookLearningSupport, practiceSource: PracticeSource?,
    pageFocus: FocusRequester, onNoteSession: (ReaderSession?) -> Unit, onPage: (Int) -> Unit, onBack: () -> Unit,
    onLearn: () -> Unit, explainProvider: BookExplainer? = null, openPairing: Boolean = false,
    resumeDraft: ChapterPracticeDraft? = null,
    onPracticeDraftChange: ((ChapterPracticeDraft, Int) -> Unit)? = null,
    onCancelImport: () -> Unit = {},
    onReorganizeSections: (() -> Unit)? = null,
    onSaveSectionQuiz: ((BookLesson, Int, Practice, PracticeAnswer) -> Unit)? = null,
    onSaveSectionPretest: ((BookLesson, Int, Practice, PracticeAnswer) -> Unit)? = null,
    onOpenSectionMainline: ((Int) -> Unit)? = null,
    onEnsureUnstructuredSectioned: (() -> Unit)? = null,
    onRecordPageDwell: ((bookId: String, tocNodeId: String, page: Int, pagesTotal: Int, dwellMs: Long) -> Unit)? = null,
    onToggleChapterRead: ((bookId: String, tocNodeId: String, pagesTotal: Int, read: Boolean) -> Unit)? = null,
    onMuteConcept: ((conceptId: String) -> Unit)? = null,
    onOpenCrossBookPage: ((bookId: String, page: Int, quoteAnchor: String?, offsetUtf16: Int) -> Unit)? = null,
    hinge: Hinge? = LocalBookHinge.current,
    onExportNotes: ((Textbook, Int) -> Unit)? = null,
    courseContext: CourseReaderContext? = null,
    pendingCourseJump: BookPassage? = null,
    onConsumePendingCourseJump: () -> Unit = {},
    onSwitchCourseSource: ((String, Int) -> Unit)? = null,
    onSaveCoursePracticeDraft: ((CourseTopicPracticeDraft) -> Unit)? = null,
    onRefreshCoursePack: (() -> Unit)? = null,
    onJumpCourseCitation: ((CourseCitationRef, BookPageAnchor) -> Unit)? = null,
    collectionQaSessions: Map<String, CollectionQaSessionState> = emptyMap(),
    onUpdateCollectionQaSession: ((String, CollectionQaSessionState) -> Unit)? = null,
    onJumpCollectionCitation: ((CollectionQaCitation, BookPageAnchor) -> Unit)? = null,
    onConsumeKeepAskTabOpen: () -> Unit = {},
    onEditStructure: (suspend (Textbook, StructureEdit) -> Textbook)? = null,
    onBrowsePage: ((Int) -> Unit)? = null,
    onLoadPdfVisuals: (suspend (String, Collection<Int>) -> Unit)? = null,
    onImportReviewedBlocks: ((String) -> Unit)? = null,
) {
    val book = checkNotNull(ui.book)
    val page = book.page(ui.page)
    if (page == null) {
        LaunchedEffect(book.id, ui.page) { onPage(book.pages.first().number) }
        return
    }
    val hasOriginal = book.format == "pdf" || book.format in BookFormats.images
    val originalPdf = book.format == "pdf"
    val textOnlySource = !hasOriginal || page.ocr
    val lastPage = book.pages.maxOf { it.number }
    var textView by remember(library, book.id) {
        // A scanned cover is common in a digital PDF. Show its physical page first
        // instead of typesetting every chapter just to display that cover.
        mutableStateOf(!hasOriginal || (library.preferredTextView(book.id)
            ?: (page.ocr && !(originalPdf && originalPdfTouchEnabled) &&
                (!originalPdf || book.pages.none { !it.ocr && it.text.isNotBlank() }))))
    }
    var viewSaveRevision by remember(library, book.id) { mutableIntStateOf(0) }
    var viewSaveError by remember(library, book.id) { mutableStateOf(false) }
    val paging = remember(book.id) {
        // 章内翻到后面的重排子页再退出：同一章里回到存下的偏移，而不是章首（TON-1724）。
        val spot = if (ui.source == null) library.savedReadSpot(book.id)?.takeIf { it.first == ui.page } else null
        val bodyOffset = book.bodyStart.takeIf { ui.source == null && it.importPage == ui.page }?.offsetUtf16
        BookPagingState(BookPageAnchor(ui.page, spot?.second ?: bodyOffset ?: 0))
    }
    val pdf = remember(library, book.id) { OriginalPdfReading(ui.page, !originalPdf || library.pdfScrollMode(book.id)) }
    val pdfLayers = remember(library, book.id) { OriginalPdfTextLayers(library, book) }
    val scroll = remember(book.id) { ScrollReadState(book.pages.size, ScrollReadPosition(book.pages.indexOf(page))) }
    val pageUnrecognizedOriginal = hasOriginal && (page.ocrPending || (page.text.isBlank() && book.hasPendingOcr))
    val pdfVisible = originalPdf && (!textView || pageUnrecognizedOriginal)
    val pdfScrolling = pdfVisible && pdf.scrolling
    val previousPage = if (pdfVisible && !pdf.scrolling) pdf.adjacent(false)
        else book.pages.lastOrNull { it.number < ui.page }?.number
    val nextPage = if (pdfVisible && !pdf.scrolling) pdf.adjacent(true)
        else book.pages.firstOrNull { it.number > ui.page }?.number
    var turnStyle by remember { mutableStateOf(PageTurnStyle.Flip) }
    var readMode by remember(library, book.id) {
        mutableStateOf(if (library.textScrollMode(book.id)) BookReadMode.Scroll else BookReadMode.Paged)
    }
    LaunchedEffect(ui.page, pdfVisible) {
        val previouslyPublished = paging.publishedImportPage
        paging.followImportPage(ui.page)
        if (pdfVisible && (previouslyPublished != paging.publishedImportPage || pageUnrecognizedOriginal) && pdf.page != ui.page) {
            pdf.selection = null
            pdf.jump(ui.page)
        }
    }
    var editingJump by remember(book.id) { mutableStateOf(false) }
    var jumpSession by remember(book.id) { mutableIntStateOf(0) }
    // 不把 ui.page 放进 key：跳页过程中页码一变就会把正在敲的数字清掉。
    var jump by remember(book.id) { mutableStateOf(ui.page.toString()) }
    var retry by remember { mutableIntStateOf(0) }
    var notesOpen by remember(book.id) { mutableStateOf(false) }
    var explainOpen by remember(book.id, openPairing) { mutableStateOf(openPairing) }
    var explainingSelection by remember(book.id) { mutableStateOf<SourceBlock?>(null) }
    var passageJump by remember(book.id) { mutableStateOf<BookPassage?>(null) }
    var passageJumpTick by remember(book.id) { mutableIntStateOf(0) }
    var highlightRevision by remember(book.id) { mutableIntStateOf(0) }
    val marks = remember(library, book.id, page, highlightRevision) { library.highlights(book.id, page) }
    var savingMark by remember(book.id) { mutableStateOf(false) }
    var markError by remember(book.id, ui.page) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var textSelection by remember(book.id) { mutableStateOf<SourceBlock?>(null) }
    LaunchedEffect(ui.page, textView, explainOpen) {
        if (explainOpen || !textView) textSelection = null
    }
    val pdfChosen = pdf.selection?.let { selected ->
        val range = selected.range
        val stored = book.page(selected.page)?.text ?: return@let null
        SourceBlock("${book.id}:p${selected.page}:${range.startUtf16}", selected.page, range.startUtf16, range.endUtf16,
            false, stored.substring(range.startUtf16, range.endUtf16))
    }?.takeIf { it.text.isNotBlank() }
    val chosen = (if (textView) textSelection else pdfChosen)?.takeIf { it.text.isNotBlank() }
    val learnableSelection = chosen?.takeIf { it.text.trim().length <= 4_000 }
    val explainer = remember(library, explainProvider) { explainProvider ?: BookExplainer(library = library) }
    val explainConsent = remember(library) { ExplainConsent(library.directory) }
    val aiEnabled = LocalReadingAiExplain.current
    val isOffline = LocalCoachConnectivity.current == CoachConnectivityStatus.OFFLINE
    var studyPreference by remember(book.id, courseContext?.courseId, courseContext?.examProfile?.isExam) {
        mutableStateOf(
            StudyModeStore.loadOrResolve(
                library = library,
                bookId = book.id,
                fallbackTitle = book.title,
                fallbackPageCount = book.pages.size,
                courseExamOverride = courseContext?.examProfile?.isExam,
                courseExamDateOverride = courseContext?.examProfile?.examDate?.takeIf { it.isNotBlank() },
            ),
        )
    }
    val autoExplain = aiEnabled && studyPreference.detailLevel != ExplainDetailLevel.ORIGINAL_ONLY
    val autoGuide = LocalReadingAutoGuide.current
    val allBookBlocks = remember(book.id, book.pages, book.structure) { book.sourceBlocks() }
    val pageRole = remember(book.id, book.structure, allBookBlocks, ui.page) {
        book.structure?.pageRole(ui.page) ?: when (ExplainClassifier.analyzePage(allBookBlocks, ui.page).kind) {
            ExplainPageKind.TOC -> BookPageRole.TOC
            ExplainPageKind.COPYRIGHT -> BookPageRole.COPYRIGHT
            ExplainPageKind.BLANK -> BookPageRole.BLANK
            ExplainPageKind.HEADINGS_ONLY -> BookPageRole.FRONT_MATTER
            ExplainPageKind.BODY -> BookPageRole.BODY
        }
    }
    val navigationBook = remember(book.id, book.pages, book.structure) { book }
    val guideController = remember(navigationBook, library, explainConsent, explainer) {
        SectionGuideController(navigationBook, library, explainConsent, explainer, scope)
    }
    val readingAnchor = if (textView && !pageUnrecognizedOriginal) paging.anchor else BookPageAnchor(ui.page)
    val currentSectionIndex = remember(guideController, readingAnchor) {
        guideController.sectionIndexForAnchor(readingAnchor.importPage, readingAnchor.offsetUtf16)
    }
    val currentSection = book.lessons.getOrNull(currentSectionIndex)
    var guideExpanded by remember(book.id, currentSection?.id) { mutableStateOf(false) }
    var activeHardPoint by remember(book.id, currentSection?.id) { mutableStateOf<Pair<SectionHardPoint, SourceBlock>?>(null) }
    var inlineThread by remember(book.id) { mutableStateOf<InlineExplainThread?>(null) }
    var inlineDraft by remember(book.id) { mutableStateOf("") }
    var inlineRequest by remember(book.id) { mutableIntStateOf(0) }
    var lastInlineAction by remember(book.id) { mutableStateOf<Pair<SelectionExplainAction, String?>?>(null) }
    var editedAsk by remember(book.id) { mutableStateOf(false) }
    val draftWriter = remember(book.id, library) { AskDraftWriter(library, book.id) }
    val currentGuideState = currentSection?.let { guideController.states[it.id] }
    val currentGuide = (currentGuideState as? SectionGuideState.Ready)?.guide
    val sectionHardPoints = remember(currentSection, currentGuide, allBookBlocks) {
        val sec = currentSection ?: return@remember emptyList()
        val byId = (sec.blocks + allBookBlocks).associateBy { it.id }
        currentGuide?.hardPoints.orEmpty().mapNotNull { hp ->
            val block = byId[hp.blockId] ?: return@mapNotNull null
            hp to block
        }
    }
    LaunchedEffect(explainer, autoExplain) { if (autoExplain && !explainer.loaded) explainer.load() }
    LaunchedEffect(book.id, book.structure?.needsModelSectioning, book.hasPendingOcr, explainer.identity, autoExplain) {
        if (book.structure?.needsModelSectioning == true && !book.hasPendingOcr && explainer.identity != null && autoExplain) {
            onEnsureUnstructuredSectioned?.invoke()
        }
    }
    LaunchedEffect(guideController, ui.page, autoGuide, explainer.identity, autoExplain, currentSectionIndex) {
        if (pageRole == BookPageRole.BODY && explainer.identity != null && autoExplain && autoGuide && currentSectionIndex >= 0) {
            guideController.prefetchForPage(ui.page, autoGuide = true, sectionIndexOverride = currentSectionIndex)
        }
    }
    fun runInlineSelection(target: SourceBlock, action: SelectionExplainAction, questionText: String? = null,
        visibleContext: List<SourceBlock>? = null, retry: BookQuestionAttempt? = null) {
        val request = ++inlineRequest
        val secIdx = guideController.sectionIndexForAnchor(target.page, target.start).takeIf { it >= 0 } ?: currentSectionIndex
        val section = guideController.contextForSection(secIdx)
        val secCtx = if (visibleContext != null) section?.copy(sectionBlocks = visibleContext)
            else inlineThread?.takeIf { it.selection == target }?.context ?: section
        if (action == SelectionExplainAction.ASK && questionText == null) {
            inlineThread = (inlineThread?.takeIf { it.selection == target }
                ?: InlineExplainThread(target, secCtx)).copy(askingOpen = true, loading = false, error = null)
            return
        }
        if (!aiEnabled) {
            val existing = inlineThread?.takeIf { it.selection == target } ?: InlineExplainThread(target, secCtx)
            inlineThread = existing.copy(
                loading = false,
                error = ("已在设置中关闭伴读讲解与提问，可在设置中重新开启。" to ExplainTrouble.Other),
                fallbackBlocks = listOf(target),
                askingOpen = true,
            )
            return
        }
        lastInlineAction = action to questionText
        val askNodeId = book.lessons.getOrNull(secIdx)?.id ?: currentSection?.id ?: "p${target.page}"
        val askName = book.lessons.getOrNull(secIdx)?.title ?: currentSection?.title ?: "第 ${target.page} 页"
        scope.launch(Dispatchers.IO) {
            runCatching {
                library.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = askNodeId,
                    page = target.page,
                    canonicalName = askName,
                    signal = LearnerSignalType.SELECTION_ASK,
                )
            }
        }
        val existing = inlineThread?.takeIf { it.selection == target } ?: InlineExplainThread(target, secCtx)
        val previous = if (retry != null) existing.turns.dropLast(1) else existing.turns
        val history = previous.map { FollowUpTurn(it.label, ReadableText.withoutCitationMarks(it.explanation.text)) }
        val label = if (action == SelectionExplainAction.ASK && !questionText.isNullOrBlank()) "问：${questionText.trim()}" else action.label
        val sent = ExplainWire.selectionBlocks(target, secCtx)
        val question = questionText?.trim()?.takeIf { action == SelectionExplainAction.ASK && it.isNotEmpty() }
        var received = if (retry != null) existing.turns.lastOrNull()?.explanation?.text.orEmpty() else ""
        val pending = existing.copy(
            turns = previous,
            pendingText = received,
            retryTurn = null,
            loading = true,
            error = null,
            fallbackBlocks = emptyList(),
            askingOpen = true,
            pendingLabel = label,
        )
        val offlineMessage = if (action == SelectionExplainAction.ASK) OfflineCopy.ASK_OFFLINE else OfflineCopy.EXPLAIN_OFFLINE
        val offlineFallback = (listOf(target) + sent).distinctBy { it.page }
        inlineThread = if (isOffline) {
            existing.copy(
                turns = previous,
                pendingText = received,
                retryTurn = retry,
                loading = false,
                error = offlineMessage to ExplainTrouble.Unreachable,
                fallbackBlocks = offlineFallback,
                askingOpen = true,
                pendingLabel = null,
            )
        } else {
            pending
        }
        if (library.todayCardEnabled && action == SelectionExplainAction.ASK && !questionText.isNullOrBlank()) {
            editedAsk = true
            draftWriter.edit(BookAskDraft(book.id, target.page, questionText, pending.threadId,
                target.id, target.start, target.end, null))
        }
        scope.launch {
            if (isOffline) {
                val uploadClient = explainer.unifiedUploadClient()
                if (uploadClient != null) {
                    val live = withContext(Dispatchers.IO) {
                        runCatching { uploadClient.probeReachability() }.getOrDefault(CoachConnectivityStatus.OFFLINE)
                    }
                    if (live == CoachConnectivityStatus.OFFLINE) return@launch
                    if (inlineRequest != request || inlineThread?.selection?.id != target.id) return@launch
                    inlineThread = pending
                }
            }
            val attempt = try {
                question?.let {
                    withContext(Dispatchers.IO) {
                        library.beginBookQuestion(book.id, pending.threadId, target, secCtx?.location.orEmpty(), it, sent, retry)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (inlineRequest == request) inlineThread = existing.copy(loading = false, askingOpen = true,
                    error = "问题没能存到这台电脑，请检查可用空间后重试。" to ExplainTrouble.Other)
                return@launch
            }
            val result = try {
                if (!explainer.loaded) explainer.load()
                suspend fun ask() = explainer.explainSelection(action.kind, target, secCtx, questionText, history)
                val answer = if (attempt == null) ask() else withContext(BookAnswerProgress { partial ->
                    withContext(Dispatchers.IO) { library.saveBookQuestionProgress(attempt, partial) }
                    received = partial
                    if (inlineRequest == request && inlineThread?.selection == target) {
                        inlineThread = pending.copy(pendingText = partial)
                    }
                }) { ask() }
                if (attempt != null) withContext(Dispatchers.IO) {
                    library.finishBookQuestion(attempt, (answer as? ExplainResult.Done)?.explanation)
                }
                answer
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The committed pending row also covers a process kill before cleanup can run.
                throw e
            } catch (_: Exception) {
                ExplainResult.Refused("这次回答中断了，问题已保留，可以重试。")
            }
            if (inlineRequest != request || inlineThread?.selection?.id != target.id) return@launch
            if (result is ExplainResult.Done && questionText != null && inlineDraft.trim() == questionText) {
                inlineDraft = ""
                editedAsk = true
                if (library.todayCardEnabled) draftWriter.edit(null)
            }
            if (result is ExplainResult.Done && attempt == null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        library.saveAskTurn(
                            bookId = book.id,
                            threadId = pending.threadId,
                            selection = target,
                            sectionLocation = secCtx?.location.orEmpty(),
                            actionKind = action.kind,
                            label = label,
                            questionText = null,
                            explanation = result.explanation,
                            sentBlocks = sent,
                        )
                    }
                }
            }
            inlineThread = when (result) {
                is ExplainResult.Done -> pending.copy(
                    turns = previous + InlineExplainEntry(label, result.explanation, sent),
                    loading = false,
                    error = null,
                    askingOpen = true,
                    pendingLabel = null,
                )
                is ExplainResult.Refused -> pending.copy(
                    turns = if (attempt == null) previous else previous + InlineExplainEntry(label, Explanation(received, emptyList(), 0), sent),
                    retryTurn = attempt,
                    loading = false,
                    error = result.message to result.trouble,
                    fallbackBlocks = (listOf(target) + sent).distinctBy { it.page },
                    askingOpen = true,
                    pendingLabel = null,
                )
            }
        }
    }
    val source = ui.source
    LaunchedEffect(book.id, source, practiceSource) {
        if (source == null && practiceSource == null) return@LaunchedEffect
        val target = practiceSource?.target
        val number = target?.pdfPage ?: practiceSource?.page ?: ui.page
        val stored = book.page(number)?.text.orEmpty()
        val blocks = source?.blocks.orEmpty().filter {
            it.page == number && it.start >= 0 && it.end > it.start && it.end <= stored.length
        }
        // A lesson opens at its body evidence. Imported physical-page markers and
        // chapter titles can precede that evidence on a different laid-out page.
        val physicalMarker = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->")
        val block = blocks.firstOrNull { !it.heading && !physicalMarker.matches(it.text.trim()) }
            ?: blocks.firstOrNull { !physicalMarker.matches(it.text.trim()) }
        // Older imported lessons can consist only of the physical-page marker.
        // Keep their source page and highlight, but open the page's body context
        // rather than a metadata-only preamble sheet. Exact citations still win.
        val bodyContext = if (block == null && blocks.isNotEmpty()) bookParagraphs(stored, pdf = false)
            .paragraphs.firstOrNull { it.kind == BookParagraph.Kind.BODY &&
                !physicalMarker.matches(stored.substring(it.start, it.end).trim()) }?.start else null
        val offset = when {
            target != null && target.start >= 0 && target.end > target.start && target.end <= stored.length &&
                stored.substring(target.start, target.end) == target.quote -> target.start
            block != null -> block.start
            bodyContext != null -> bodyContext
            else -> practiceSource?.quote?.let { quote -> stored.indexOf(quote).takeIf {
                it >= 0 && stored.indexOf(quote, it + 1) < 0
            } } ?: 0
        }
        paging.anchor = BookPageAnchor(number, offset)
        if (originalPdf && number == ui.page) pdf.jump(number)
    }
    // 从答后讲解的「去原文页配对」过来、这台电脑的连接已停用：原文页直接摊开配对，不用再去找「讲解」。
    LaunchedEffect(explainer, source != null) {
        if (source == null) return@LaunchedEffect
        if (!explainer.loaded) explainer.load()
        if (explainer.revoked && explainer.identity == null) explainOpen = true
    }
    LaunchedEffect(book.id, ui.page, source) {
        if (page.ocr && source != null) textView = true
    }
    var asking by remember(book.id, ui.page) { mutableStateOf(false) }
    // A laid-out sheet can start on the previous imported page. Its bookmark
    // follows that first source, but paragraph actions must keep the source
    // explicitly selected by a page jump or citation.
    val paragraphPage = book.page(readingAnchor.importPage) ?: page

    // The page surface fetches its own raster at the current viewport size.
    val readerSource = remember(library) { com.coach.desktop.reader.LocalBooksReaderSource(library, loadPageImages = false) }
    val remote = remember(readerSource, book.id) { com.coach.desktop.reader.ReaderRemote(readerSource) }
    val session = remember(remote) {
        com.coach.desktop.reader.ReaderSession(
            com.coach.desktop.reader.ReaderFixture(book.title, emptyList(), emptyList()),
            1200,
            remote,
            ReaderDraftScope.forSource(readerSource, library.directory),
        )
    }
    DisposableEffect(session) {
        onNoteSession(session)
        onDispose { session.cancelNote(); onNoteSession(null) }
    }
    // Only explicit saves trigger this effect. Panel hide/show and draft restore never send a write.
    LaunchedEffect(session, session.noteSaveTick) {
        if (session.noteSaveTick > 0) session.commitNote()
    }

    // 翻页键只到得了拿着键盘焦点的那棵子树，外壳的 Surface 只处理 Esc，所以阅读区自己是一个焦点目标：
    // 换页、合上批注编辑区、离开页码框之后把焦点收回来，方向键马上又能用。
    // 正在输入文字时不接管：提问弹窗、页码框、批注编辑区自己要用方向键。
    val focusManager = LocalFocusManager.current
    var jumpFocused by remember { mutableStateOf(false) }
    var searchOpen by remember(book.id) { mutableStateOf(false) }
    var notesPageOpen by remember(book.id) { mutableStateOf(false) }
    var searchFocused by remember(book.id) { mutableStateOf(false) }
    var searchFocusTick by remember(book.id) { mutableIntStateOf(0) }
    var searchQuery by remember(book.id) { mutableStateOf("") }
    var searchActiveIndex by remember(book.id) { mutableIntStateOf(-1) }
    val searchFocus = remember(book.id) { FocusRequester() }
    val searchResult = remember(book.pages, searchQuery) { searchBookPages(book.pages, searchQuery) }
    LaunchedEffect(searchOpen, searchFocusTick) {
        if (searchOpen) {
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
        }
    }
    var pageFocused by remember { mutableStateOf(false) }
    var tocOpen by remember(book.id) { mutableStateOf(false) }
    val initialCompanionOpen = openPairing || courseContext?.weekLabel == CourseExamPoints.LABEL || (
        ui.source == null &&
            !(studyPreference.userLocked && studyPreference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) && (
            (resumeDraft?.companionOpen == true && resumeDraft.hasMeaningfulDraft()) ||
                (courseContext != null && courseContext.resumeDraft?.companionOpen != false) ||
                (studyPreference.userLocked &&
                    studyPreference.mode != BookStudyMode.READ_THROUGH &&
                    studyPreference.detailLevel != ExplainDetailLevel.ORIGINAL_ONLY &&
                    (resumeDraft == null || resumeDraft.companionOpen || studyPreference.updatedAt >= resumeDraft.updatedAt))
            )
        )
    val initialCompanionTab = remember(book.id, resumeDraft?.companionTab) {
        ReaderCompanionTab.entries.firstOrNull { it.name.equals(resumeDraft?.companionTab, ignoreCase = true) }
            ?: ReaderCompanionTab.EXPLAIN
    }
    var companionOpen by remember(book.id, courseContext?.topicId, openPairing) { mutableStateOf(initialCompanionOpen || ui.keepAskTabOpen) }
    var companionTab by remember(book.id) { mutableStateOf(if (ui.keepAskTabOpen) ReaderCompanionTab.ASK else initialCompanionTab) }
    LaunchedEffect(ui.keepAskTabOpen, book.id) {
        if (ui.keepAskTabOpen) {
            companionOpen = true
            companionTab = ReaderCompanionTab.ASK
            onConsumeKeepAskTabOpen()
        }
    }
    val activeAskCollection = remember(courseContext?.courseId, ui.activeBooklistId, ui.collections, book.id) {
        when {
            courseContext != null -> ActiveAskCollection(courseContext.courseId, CollectionKind.COURSE, courseContext.courseTitle)
            ui.activeBooklistId != null -> ui.collections.firstOrNull { it.record.id == ui.activeBooklistId }
                ?.let { ActiveAskCollection(it.record.id, it.record.kind, it.record.title) }
            else -> {
                val matching = ui.collections.filter { !it.record.archived && book.id in it.materialIds }
                val preferred = matching.firstOrNull { it.record.kind == CollectionKind.COURSE }
                    ?: matching.firstOrNull { it.record.kind == CollectionKind.BOOKLIST }
                preferred?.let { ActiveAskCollection(it.record.id, it.record.kind, it.record.title) }
                    ?: CollectionQaVirtualCollections.readLibraryCollection()
            }
        }
    }
    val localCollectionSessions = remember { mutableStateMapOf<String, CollectionQaSessionState>() }
    fun sessionForCollection(collectionId: String): CollectionQaSessionState =
        if (onUpdateCollectionQaSession != null) collectionQaSessions[collectionId] ?: CollectionQaSessionState()
        else localCollectionSessions[collectionId] ?: CollectionQaSessionState()
    fun updateCollectionSession(collectionId: String, next: CollectionQaSessionState) {
        if (onUpdateCollectionQaSession != null) onUpdateCollectionQaSession(collectionId, next)
        else localCollectionSessions[collectionId] = next
    }
    LaunchedEffect(book.id) {
        val (saved, restored, unfinished) = withContext(Dispatchers.IO) {
            val saved = if (library.todayCardEnabled) library.askDraft(book.id) else null
            val candidate = library.loadLatestAskThread(book.id, saved?.threadId)
            val interrupted = candidate?.let { library.lastUnfinishedQuestion(book.id, it.threadId) }
            // A process can die before the debounced draft writer attaches the new thread ID.
            val matchesSentQuestion = interrupted != null && saved?.collectionId == null &&
                saved?.text?.trim() == interrupted.question && saved.page == candidate?.selection?.page
            val thread = candidate.takeIf { saved == null || saved.threadId != null || matchesSentQuestion }
            Triple(saved, thread, interrupted.takeIf { thread != null })
        }
        if (!editedAsk && inlineThread == null) {
            val selection = saved?.selection(book) ?: restored?.selection
            if (selection != null) {
                val idx = guideController.sectionIndexForAnchor(selection.page, selection.start)
                val context = guideController.contextForSection(idx) ?: restored?.sectionLocation?.takeIf { it.isNotBlank() }
                    ?.let { ExplainSectionContext(it, null, null, emptyList()) }
                inlineThread = InlineExplainThread(selection, context,
                    turns = restored?.turns.orEmpty(), askingOpen = saved != null || unfinished != null,
                    retryTurn = unfinished,
                    error = unfinished?.let { "上次回答中断了，可以重试或继续追问。" to ExplainTrouble.Other },
                    threadId = saved?.threadId ?: restored?.threadId ?: java.util.UUID.randomUUID().toString())
            }
            if (unfinished != null) inlineDraft = unfinished.question
            if (saved != null) {
                inlineDraft = saved.text
                activeAskCollection?.let { col ->
                    val scope = if (saved.collectionId == col.id) CollectionAskScope.COLLECTION else CollectionAskScope.SECTION
                    updateCollectionSession(col.id, sessionForCollection(col.id).copy(scope = scope))
                }
            }
        }
    }
    fun changeAskDraft(text: String, collectionScope: CollectionAskScope? = null) {
        editedAsk = true
        inlineDraft = text
        if (!library.todayCardEnabled) return
        val col = activeAskCollection
        val isCollection = col != null && (collectionScope ?: sessionForCollection(col.id).scope) == CollectionAskScope.COLLECTION
        val block = if (isCollection) null else inlineThread?.selection ?: learnableSelection
        val draft = if (text.isBlank()) null else BookAskDraft(book.id, block?.page ?: ui.page, text,
            inlineThread?.threadId, block?.id, block?.start ?: 0, block?.end ?: 0, col?.id?.takeIf { isCollection })
        draftWriter.edit(draft)
    }
    fun openSelectionAction(target: SourceBlock, action: SelectionExplainAction) {
        companionOpen = true
        companionTab = ReaderCompanionTab.ASK
        runInlineSelection(target, action)
        activeAskCollection?.let { collection ->
            val session = sessionForCollection(collection.id)
            if (session.scope == CollectionAskScope.COLLECTION) {
                updateCollectionSession(collection.id, session.copy(scope = CollectionAskScope.SECTION))
                if (inlineDraft.isNotBlank()) changeAskDraft(inlineDraft, CollectionAskScope.SECTION)
            }
        }
    }
    LaunchedEffect(inlineDraft, editedAsk) {
        if (editedAsk) {
            kotlinx.coroutines.delay(200)
            withContext(Dispatchers.IO) { draftWriter.flush() }
        }
    }
    DisposableEffect(draftWriter) { onDispose { draftWriter.flush() } }
    var askInputFocused by remember(book.id) { mutableStateOf(false) }
    var practiceInputFocused by remember(book.id) { mutableStateOf(false) }
    var explicitTocNodeId by remember(book.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(readingAnchor, explicitTocNodeId) {
        val id = explicitTocNodeId ?: return@LaunchedEffect
        val chosenNode = navigationBook.tocNodes.firstOrNull { it.id == id }
        if (chosenNode == null || readingAnchor.importPage != chosenNode.startPage || readingAnchor.offsetUtf16 < chosenNode.startOffset) {
            explicitTocNodeId = null
        }
    }
    val activeTocNode = remember(navigationBook, readingAnchor, explicitTocNodeId) {
        BookBoundaries.activeTocNode(
            navigationBook.tocNodes,
            readingAnchor.importPage,
            readingAnchor.offsetUtf16,
            explicitTocNodeId,
            book.page(readingAnchor.importPage)?.text,
        )
    }
    val currentChapterGroup = remember(book.id, book.lessons, currentSectionIndex) {
        ChapterCompanionGrouping.groupForSection(book, currentSectionIndex)
    }
    val currentChapterSpan = remember(navigationBook, readingAnchor) {
        BookChapterResolver.chapterForPage(navigationBook, readingAnchor.importPage, readingAnchor.offsetUtf16)
    }
    val effectiveChapterSpan = remember(navigationBook, readingAnchor, explicitTocNodeId) {
        BookBoundaries.effectiveChapterForFooter(navigationBook, readingAnchor.importPage, readingAnchor.offsetUtf16, explicitTocNodeId)
    }
    var endOrHomeBrowseActive by remember(book.id) { mutableStateOf(false) }
    var correctedBodyPage by remember(book.id, book.correctedBodyPage) { mutableStateOf(book.correctedBodyPage) }
    val bodyStart = remember(book, correctedBodyPage) { BookBoundaries.bodyStart(book.pages, book.structure, correctedBodyPage) }
    val dwellThresholdMs = LocalChapterDwellMs.current
    var localBooklistRev by remember(book.id) { mutableIntStateOf(0) }
    var chapterReadStates by remember(book.id) { mutableStateOf<Set<String>>(emptySet()) }
    var crossBookCards by remember(book.id) { mutableStateOf<List<CrossBookCompanionCard>>(emptyList()) }
    var bookConceptNames by remember(book.id) { mutableStateOf<List<ConceptNameRef>>(emptyList()) }
    var openedConceptId by remember(book.id) { mutableStateOf<String?>(null) }
    var sayingsOpenTick by remember(book.id) { mutableIntStateOf(0) }
    var conceptSayings by remember(book.id) { mutableStateOf<ConceptSayingsBoard?>(null) }
    var checkingSayings by remember(book.id) { mutableStateOf(false) }
    var sayingsNotice by remember(book.id) { mutableStateOf<String?>(null) }
    val sayingGates = remember(book.id) { HashMap<String, Long>() }
    val readingTracker = remember(book.id, book.structure?.editRevision, dwellThresholdMs) {
        val floor = if (dwellThresholdMs < ChapterReadPolicy.MIN_DWELL_MS) maxOf(dwellThresholdMs, 40L) else 15_000L
        PageReadingTracker(defaultMedianFloorMs = floor)
    }
    val proactivityPolicy = remember(book.id) { QuietProactivityPolicy() }
    var activeWhisper by remember(book.id) { mutableStateOf<MarginWhisperCandidate?>(null) }
    var activeCheckpoint by remember(book.id) { mutableStateOf<EndOfSectionCheckpointData?>(null) }
    var lastTrackedPage by remember(book.id) { mutableStateOf<Int?>(null) }
    var lastTrackedSection by remember(book.id, book.structure?.editRevision) { mutableStateOf<BookLesson?>(null) }
    var pendingNavigationIsJump by remember(book.id) { mutableStateOf(false) }
    var pendingDwellJump by remember(book.id) { mutableStateOf(false) }
    var lastDwellTrackedPage by remember(book.id) { mutableStateOf<Int?>(null) }
    var lastDwellSpan by remember(book.id, book.structure?.editRevision) { mutableStateOf<BookChapterSpan?>(null) }
    var lastDwellVisiblePages by remember(book.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var lastDwellSampleAtMs by remember(book.id) { mutableLongStateOf(System.currentTimeMillis()) }
    val chapterDwellTracker = remember(book.id, dwellThresholdMs) {
        ChapterDwellTracker(dwellThresholdMs.coerceAtLeast(1L))
    }

    suspend fun evaluateMarginWhisperForPage(targetPage: Int, nodeId: String, rereadCountOnPage: Int) {
        if (ui.source != null || studyPreference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) return
        val quoteSnippet = book.page(targetPage)?.text?.trim()?.lineSequence()?.firstOrNull { it.length >= 8 }?.take(48).orEmpty()
        val (conflict, mastery) = withContext(Dispatchers.IO) {
            val c = runCatching { library.crossMaterialConflictForPage(book.id, targetPage, nodeId) }.getOrNull()
            val m = runCatching {
                val all = library.conceptMasteryForBook(book.id)
                all.firstOrNull { it.tocNodeId == nodeId }
                    ?: all.firstOrNull { it.tocNodeId == currentSection?.id }
            }.getOrNull()
            c to m
        }
        val effectiveReread = maxOf(rereadCountOnPage, mastery?.rereadCount ?: 0)
        val candidate = proactivityPolicy.evaluate(
            bookId = book.id,
            tocNodeId = nodeId,
            page = targetPage,
            conflictInfo = conflict,
            mastery = mastery,
            rereadCountOnPage = effectiveReread,
            cumulativeDwellMs = readingTracker.cumulativeDwellMs(targetPage),
            personalMedianDwellMs = readingTracker.personalMedianDwellMs(excludePage = targetPage),
            quoteAnchor = quoteSnippet,
        )
        if (candidate != null) {
            activeWhisper = candidate
            withContext(Dispatchers.IO) {
                runCatching { library.saveMarginWhisperAudit(candidate) }
            }
        }
    }

    // Track page entries, dismiss cooldowns, backtrack rereads, section-end checkpoints & margin whispers.
    LaunchedEffect(book.id, ui.page, ui.source) {
        if (ui.source != null) return@LaunchedEffect
        val prevPage = lastTrackedPage
        val prevSec = lastTrackedSection
        val isJump = pendingNavigationIsJump || (prevPage != null && kotlin.math.abs(ui.page - prevPage) > 2)
        pendingNavigationIsJump = false

        if (prevPage != null && prevPage != ui.page) {
            proactivityPolicy.onPageTurn()
            activeWhisper = null
            // Advancing past a section checkpoint slides right past without blocking.
            if (activeCheckpoint != null && activeCheckpoint?.page != ui.page) {
                activeCheckpoint = null
            }
            if (prevSec != null &&
                studyPreference.detailLevel != ExplainDetailLevel.ORIGINAL_ONLY &&
                studyPreference.mode != BookStudyMode.BOOK_MAP &&
                readingTracker.shouldOfferSectionCheckpoint(
                    previousSectionId = prevSec.id,
                    previousSectionLastPage = prevSec.lastPage,
                    fromPage = prevPage,
                    toPage = ui.page,
                    isJump = isJump,
                )
            ) {
                val checkpointQuestions = withContext(Dispatchers.IO) {
                    val prevIdx = book.lessons.indexOfFirst { it.id == prevSec.id }.coerceAtLeast(0)
                    val prevGroup = ChapterCompanionGrouping.groupForSection(book, prevIdx)
                    val cached = prevGroup?.let { runCatching { library.cachedChapterCompanion(book.id, it.chapterId) }.getOrNull() }
                    val fromPack = cached?.optionalPractice?.filter { it.sectionId == prevSec.id && it.options.size >= 2 }.orEmpty()
                        .ifEmpty { cached?.pretest?.filter { it.sectionId == prevSec.id && it.options.size >= 2 }.orEmpty() }
                        .ifEmpty { cached?.optionalPractice?.filter { it.options.size >= 2 }.orEmpty() }
                        .take(2)
                    fromPack.ifEmpty {
                        val snippet = prevSec.blocks.firstOrNull { !it.heading && it.text.isNotBlank() }?.text?.take(48).orEmpty()
                        listOf(
                            BookCompanionQuiz(
                                id = "cp-${prevSec.id}-1",
                                sectionId = prevSec.id,
                                stem = "刚读完「${prevSec.title}」，你现在能不看原文复述本节的核心结论吗？",
                                options = listOf("能清楚讲出核心机制", "还有一处推导不够稳"),
                                correctOption = "能清楚讲出核心机制",
                                explanation = if (snippet.isNotBlank()) "可随时切回第 ${prevSec.firstPage} 页核对原文。" else "读完后隔天复习最稳。",
                                kind = "MCQ",
                            ),
                        )
                    }
                }
                activeCheckpoint = EndOfSectionCheckpointData(
                    bookId = book.id,
                    sectionId = prevSec.id,
                    sectionTitle = prevSec.title,
                    page = ui.page,
                    questions = checkpointQuestions,
                )
            }
        }

        val secId = currentSection?.id ?: currentChapterSpan?.tocNodeId ?: "p${ui.page}"
        readingTracker.onPageEntered(ui.page, isJump = isJump, sectionId = secId)
        lastTrackedPage = ui.page
        lastTrackedSection = currentSection

        evaluateMarginWhisperForPage(ui.page, secId, readingTracker.backtrackCount(ui.page))
    }

    // 真实停留与顺序读章计时器：
    // 1. 按 (tocNodeId, page) 累计停留，跨小节/重排屏不丢失进度；
    // 2. 逐屏顺序读完一章进下一章时自动计入该章顺序浏览页；
    // 3. 目录跳读、Home/End 或进度条跳页绝不自动标已读跳过的中间章。
    // 同时持续累计页停留并检测 p+1 -> p 往返重读（累计停留 > 个人页速中位数 2.2 倍记 REREAD_BACKTRACK）。
    val dwellPage = readingAnchor.importPage
    LaunchedEffect(book.id, readingAnchor, paging.pages.size, ui.source, dwellThresholdMs, endOrHomeBrowseActive, pageRole) {
        if (ui.source != null) return@LaunchedEffect
        val span = currentChapterSpan
        val secId = currentSection?.id ?: span?.tocNodeId
        val secTitle = currentSection?.title ?: span?.title.orEmpty()
        val visiblePagesInSpan: Set<Int> = if (span == null) {
            emptySet()
        } else if (textView && !pageUnrecognizedOriginal && paging.pages.isNotEmpty()) {
            val idx = paging.index
            val laid = (0 until if (paging.spread) 2 else 1)
                .mapNotNull { paging.pages.getOrNull(idx + it) }
                .flatMap { it.sources }
                .map { it.importPage }
                .filterTo(LinkedHashSet()) { it in span.firstPage..span.lastPage }
            laid.ifEmpty { setOf(dwellPage).filterTo(LinkedHashSet()) { it in span.firstPage..span.lastPage } }
        } else {
            setOf(dwellPage).filterTo(LinkedHashSet()) { it in span.firstPage..span.lastPage }
        }
        val prevDwellPage = lastDwellTrackedPage
        val isJump = pendingDwellJump || (prevDwellPage != null && kotlin.math.abs(dwellPage - prevDwellPage) > 2)
        pendingDwellJump = false
        lastDwellTrackedPage = dwellPage

        fun commitChapterDwell(recSpan: BookChapterSpan, recPage: Int) {
            val recordedDwell = maxOf(dwellThresholdMs, ChapterReadPolicy.MIN_DWELL_MS)
            if (onRecordPageDwell != null) {
                onRecordPageDwell(book.id, recSpan.tocNodeId, recPage, recSpan.pagesTotal, recordedDwell)
            } else {
                scope.launch(Dispatchers.IO) {
                    runCatching { library.recordPageDwell(book.id, recSpan.tocNodeId, recPage, recSpan.pagesTotal, recordedDwell) }
                }
                localBooklistRev++
            }
        }

        val nowAtTransition = System.currentTimeMillis()
        if (lastDwellSpan != null && lastDwellVisiblePages.isNotEmpty()) {
            val priorElapsed = (nowAtTransition - lastDwellSampleAtMs).coerceIn(0L, 60_000L)
            if (priorElapsed > 0L) {
                for ((recSpan, recPage) in chapterDwellTracker.onTick(lastDwellSpan, lastDwellVisiblePages, priorElapsed)) {
                    commitChapterDwell(recSpan, recPage)
                }
            }
        }
        lastDwellSampleAtMs = nowAtTransition
        lastDwellSpan = span
        lastDwellVisiblePages = visiblePagesInSpan

        for ((recSpan, recPage) in chapterDwellTracker.onLocationChanged(span, visiblePagesInSpan, isJump)) {
            commitChapterDwell(recSpan, recPage)
        }

        val waitMs = dwellThresholdMs.coerceAtLeast(1L)
        val tickMs = minOf(250L, waitMs)
        var trackerElapsedMs = 0L
        var pageCommitElapsedMs = 0L
        var committedPageOnDwell = false
        while (true) {
            delay(tickMs)
            val nowMs = System.currentTimeMillis()
            val deltaMs = (nowMs - lastDwellSampleAtMs).coerceAtLeast(1L)
            lastDwellSampleAtMs = nowMs
            for ((recSpan, recPage) in chapterDwellTracker.onTick(span, visiblePagesInSpan, deltaMs)) {
                commitChapterDwell(recSpan, recPage)
            }
            pageCommitElapsedMs += deltaMs
            if (!committedPageOnDwell && pageCommitElapsedMs >= waitMs) {
                committedPageOnDwell = true
                if (!endOrHomeBrowseActive && pageRole == BookPageRole.BODY && book.readingPage != dwellPage) {
                    onPage(dwellPage)
                }
            }
            trackerElapsedMs += deltaMs
            if (span != null && secId != null && trackerElapsedMs >= waitMs) {
                val trackerDwell = trackerElapsedMs
                trackerElapsedMs = 0L
                val triggeredBacktrack = readingTracker.recordDwell(dwellPage, trackerDwell, secId)
                if (triggeredBacktrack) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            library.recordMasteryEvidence(
                                bookId = book.id,
                                tocNodeId = secId,
                                page = dwellPage,
                                canonicalName = secTitle,
                                signal = LearnerSignalType.REREAD_BACKTRACK,
                                durationMs = trackerDwell,
                            )
                        }
                    }
                    if (activeWhisper == null) {
                        evaluateMarginWhisperForPage(dwellPage, secId, readingTracker.backtrackCount(dwellPage))
                    }
                }
            }
        }
    }

    LaunchedEffect(book.id, currentChapterSpan?.tocNodeId, currentSection?.firstPage, currentSection?.lastPage, ui.activeBooklistId, ui.booklistRevision, localBooklistRev) {
        val loaded = withContext(Dispatchers.IO) {
            val states = library.chapterProgressMap(book.id).values.filter {
                ChapterReadPolicy.computeReadState(it.dwelledPages.size, it.pagesTotal) == ChapterReadState.READ
            }.mapTo(mutableSetOf()) { it.tocNodeId }
            val pageRange = currentSection?.let { it.firstPage..it.lastPage }
                ?: currentChapterSpan?.let { it.firstPage..it.lastPage }
                ?: (ui.page..ui.page)
            val cards = library.companionCrossBookCards(
                currentBookId = book.id,
                currentChapterId = currentChapterSpan?.tocNodeId,
                currentPageRange = pageRange,
                activeBooklistId = ui.activeBooklistId,
            )
            states to cards
        }
        chapterReadStates = loaded.first
        crossBookCards = loaded.second
    }
    LaunchedEffect(book.id, ui.booklistRevision, localBooklistRev) {
        bookConceptNames = withContext(Dispatchers.IO) { library.conceptNamesForBook(book.id) }
    }
    fun openOtherBooks(conceptId: String?) {
        companionOpen = true
        companionTab = ReaderCompanionTab.EXPLAIN
        pdf.selection = null
        textSelection = null
        if (conceptId == null) {
            openedConceptId = null
            conceptSayings = null
            checkingSayings = false
            sayingsNotice = ConceptSayingLogic.NO_MATCH
            return
        }
        val now = System.currentTimeMillis()
        val until = sayingGates[conceptId] ?: 0L
        if (now < until) {
            openedConceptId = conceptId
            if (!checkingSayings) sayingsNotice = ConceptSayingLogic.RETRY_LATER
            return
        }
        sayingGates[conceptId] = now + ConceptSayingLogic.RETRY_HOLD_MS
        sayingsNotice = null
        openedConceptId = conceptId
        sayingsOpenTick++
    }
    LaunchedEffect(openedConceptId, sayingsOpenTick, book.id, ui.booklistRevision, localBooklistRev, aiEnabled) {
        val id = openedConceptId
        if (id == null) {
            conceptSayings = null
            checkingSayings = false
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            val lookup = library.conceptSayingsLookup(id, book.id)
            val aiOn = library.isReadingAiExplainEnabled()
            val hold = lookup.board?.let { library.sayingHoldUntil(id, it.inputHash) }
            Triple(lookup, aiOn, hold)
        }
        val lookup = loaded.first
        val board = lookup.board
        // 与书单对齐同一条：界面开关或本机「伴读讲解与提问」任一关闭，正文都不发到远端。
        val remoteAllowed = aiEnabled && loaded.second
        val persistedHold = loaded.third
        conceptSayings = board
        val now = System.currentTimeMillis()
        if (board != null && persistedHold != null && now < persistedHold &&
            ConceptSayingLogic.mayRequestConceptSayings(board, remoteAllowed)
        ) {
            sayingGates[id] = persistedHold
            checkingSayings = false
            sayingsNotice = ConceptSayingLogic.RETRY_LATER
            return@LaunchedEffect
        }
        if (board != null && ConceptSayingLogic.mayRequestConceptSayings(board, remoteAllowed, persistedHold, now)) {
            checkingSayings = true
            sayingsNotice = null
            var unavailable = false
            val settled = settleConceptSayings(
                board,
                fetch = { explainer.alignConceptSayings(it) },
                save = { ready, parsed -> withContext(Dispatchers.IO) { library.saveConceptSayings(ready, parsed) } },
                reload = { withContext(Dispatchers.IO) { library.conceptSayings(id, book.id) } },
                delayMillis = { delay(it) },
                remoteAllowed = remoteAllowed,
                onUnavailable = { unavailable = true },
            )
            checkingSayings = false
            conceptSayings = settled
            if (unavailable) {
                val until = System.currentTimeMillis() + ConceptSayingLogic.RETRY_HOLD_MS
                sayingGates[id] = until
                withContext(Dispatchers.IO) { library.rememberSayingHold(id, board.inputHash, until) }
                sayingsNotice = ConceptSayingLogic.RETRY_LATER
            } else {
                sayingGates.remove(id)
                sayingsNotice = null
            }
        } else {
            sayingGates.remove(id)
            checkingSayings = false
            sayingsNotice = if (lookup.anchorMismatch) ConceptSayingLogic.ANCHOR_MISMATCH else null
        }
    }
    val practiceDraftNodeId = remember(book.id, currentChapterGroup?.chapterId) {
        currentChapterGroup?.chapterId ?: currentSection?.id ?: "p${ui.page}"
    }
    val exactResumeDraft = resumeDraft?.takeIf { it.bookId == book.id && it.tocNodeId == practiceDraftNodeId }
    var practiceDraft by remember(book.id, currentChapterGroup?.chapterId) {
        // 存盘的草稿放到下面后台去读：书库在等别处的写锁时，这里同步去读会把整个窗口卡住。
        val restored = exactResumeDraft
            ?: resumeDraft?.takeIf { it.bookId == book.id && it.hasMeaningfulDraft() }
            ?: ChapterPracticeDraft(bookId = book.id, tocNodeId = practiceDraftNodeId, page = ui.page)
        mutableStateOf(restored)
    }
    var practiceDraftReady by remember(book.id, currentChapterGroup?.chapterId) {
        mutableStateOf(exactResumeDraft != null)
    }
    LaunchedEffect(book.id, currentChapterGroup?.chapterId) {
        if (exactResumeDraft != null) {
            practiceDraftReady = true
            return@LaunchedEffect
        }
        val shown = practiceDraft
        val stored = withContext(Dispatchers.IO) {
            runCatching { library.loadChapterPracticeDraft(book.id, practiceDraftNodeId) }.getOrNull()
        }
        // 读回来之前人已经动过草稿，就以人手上的为准。没有存盘时也要放行，否则学法永远写不进去。
        if (stored != null && practiceDraft === shown) practiceDraft = stored
        practiceDraftReady = true
    }
    fun persistPracticeDraft(updated: ChapterPracticeDraft) {
        practiceDraft = updated
        val pagesTotal = currentChapterGroup?.pageCount ?: book.pages.size.coerceAtLeast(1)
        if (onPracticeDraftChange != null) {
            onPracticeDraftChange(updated, pagesTotal)
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { library.saveChapterPracticeDraft(updated.bookId, updated.tocNodeId, pagesTotal, updated) }
            }
        }
    }
    LaunchedEffect(companionOpen, companionTab) {
        if (!companionOpen || companionTab != ReaderCompanionTab.ASK) {
            askInputFocused = false
        }
        if (!companionOpen || companionTab != ReaderCompanionTab.EXPLAIN) {
            practiceInputFocused = false
        }
    }
    fun openReadingCompanion() {
        if (companionOpen) return
        if (textView && !pageUnrecognizedOriginal) {
            // Resolve the passage against the wide layout before opening the narrower drawer.
            // An import-page bookmark can point at a heading omitted by the typesetter.
            val slices = paging.visibleProseSlices()
            val pageNumbers = (listOf(readingAnchor.importPage) + slices.map { it.importPage }).distinct()
            for (number in pageNumbers) {
                val ranges = slices.filter { it.importPage == number }.map { it.startUtf16 until it.endUtf16 }
                val offset = if (number == readingAnchor.importPage) readingAnchor.offsetUtf16 else 0
                val blocks = selectScreenExplainBlocks(book, number, offset, ranges)
                val first = blocks.firstOrNull() ?: selectScreenExplainBlocks(book, number, 0, ranges).firstOrNull()
                if (first != null) {
                    paging.anchor = BookPageAnchor(first.page, first.start)
                    break
                }
            }
        }
        companionOpen = true
    }
    fun closeCompanion() {
        companionOpen = false
        explainOpen = false
        notesOpen = false
        askInputFocused = false
        practiceInputFocused = false
        if (companionTab == ReaderCompanionTab.ASK && inlineThread != null) {
            textSelection = null
            pdf.selection = null
        }
        inlineThread = inlineThread?.copy(askingOpen = false)
        focusManager.clearFocus()
    }
    // 仅在输入框真实聚焦或页码/批注/搜索正在输入时屏蔽翻页键，绝不用持久的 inlineThread.askingOpen 锁死键盘。
    val typing = readerTyping(asking, editingJump || jumpFocused, false, session.noting) ||
        askInputFocused || practiceInputFocused || searchOpen || searchFocused
    var pageFocusTick by remember(book.id) { mutableIntStateOf(0) }
    LaunchedEffect(book.id, ui.page, typing, pdfScrolling, companionOpen, companionTab, tocOpen, pageFocusTick) {
        if (typing || (companionOpen && companionTab == ReaderCompanionTab.ASK)) return@LaunchedEffect
        // A successful request already owns focus. Do not leave another frame's
        // request pending: it could steal focus from a newly clicked page field.
        repeat(5) {
            withFrameNanos { }
            // Mouse-up can finish moving focus to the paging button after its
            // click changed ui.page. Inspect focus once that frame has settled.
            if (pageFocused) return@LaunchedEffect
            val requested = runCatching { pageFocus.requestFocus() }.getOrDefault(false)
            if (requested) return@LaunchedEffect
        }
    }
    fun emitReaderPage(targetPage: Int, forceCommit: Boolean = false, fromEndOrHome: Boolean = false) {
        if (ui.source != null || forceCommit) {
            if (forceCommit) endOrHomeBrowseActive = false
            onPage(targetPage)
            return
        }
        if (fromEndOrHome) {
            endOrHomeBrowseActive = true
            onBrowsePage?.invoke(targetPage)
            return
        }
        val jumpDist = kotlin.math.abs(targetPage - book.readingPage)
        if (endOrHomeBrowseActive) {
            if (jumpDist <= 2) {
                endOrHomeBrowseActive = false
                onPage(targetPage)
            } else {
                onBrowsePage?.invoke(targetPage)
            }
        } else if (jumpDist > 5 && onBrowsePage != null) {
            onBrowsePage(targetPage)
        } else {
            onPage(targetPage)
        }
    }
    val publishPdfPage: (Int) -> Unit = { number ->
        val selection = pdf.selection?.takeIf { it.page == number }
        paging.anchor = BookPageAnchor(number, selection?.range?.startUtf16 ?: 0)
        if (paging.publishImportPage(number)) emitReaderPage(number)
        session.goToPage(number)
    }
    fun turnToAnchor(pageNumber: Int?, offset: Int = 0, forceCommit: Boolean = false, fromEndOrHome: Boolean = false) {
        val targetPage = pageNumber?.let(book::page)
        if (pageNumber != null && targetPage != null) {
            passageJump = null
            pdf.selection = null
            val targetUnrecognized = hasOriginal && (targetPage.ocrPending || (targetPage.text.isBlank() && book.hasPendingOcr))
            if (pdfVisible || (originalPdf && targetUnrecognized)) pdf.jump(pageNumber)
            paging.anchor = BookPageAnchor(pageNumber, offset.coerceIn(0, targetPage.text.length))
            if (paging.publishImportPage(pageNumber)) emitReaderPage(pageNumber, forceCommit = forceCommit, fromEndOrHome = fromEndOrHome)
            session.goToPage(pageNumber)
        }
    }
    val turnTo: (Int?) -> Unit = { turnToAnchor(it) }

    // Keep the event handler connected to the current neighbours after recomposition.
    // A remembered key callback must not retain a local function's old page captures.
    fun visibleImportPageRange(): IntRange {
        val visible = (0 until if (paging.spread) 2 else 1)
            .mapNotNull { paging.pages.getOrNull(paging.index + it) }
            .flatMap { it.sources }
            .map { it.importPage }
        val first = visible.minOrNull() ?: paging.anchor.importPage
        val last = visible.maxOrNull() ?: paging.anchor.importPage
        return first..last
    }
    val turnLayout by rememberUpdatedState<(ReaderTurn) -> Unit> { turn ->
        val isEndOrHome = turn == ReaderTurn.First || turn == ReaderTurn.Last
        if (isEndOrHome) {
            pendingNavigationIsJump = true
            pendingDwellJump = true
        }
        if (!textView || pageUnrecognizedOriginal) {
            when (turn) {
                ReaderTurn.Previous -> turnTo(previousPage)
                ReaderTurn.Next -> turnTo(nextPage)
                ReaderTurn.First -> turnToAnchor(book.pages.first().number, fromEndOrHome = true)
                ReaderTurn.Last -> turnToAnchor(lastPage, fromEndOrHome = true)
            }
        } else when {
            turn == ReaderTurn.Next && !paging.canNext ->
                book.pages.firstOrNull { it.number > visibleImportPageRange().last }?.number?.let(turnTo)
            turn == ReaderTurn.Previous && !paging.canPrevious ->
                book.pages.lastOrNull { it.number < visibleImportPageRange().first }?.number?.let(turnTo)
            turn == ReaderTurn.Last && book.pages.last().ocrPending ->
                turnToAnchor(book.pages.last().number, fromEndOrHome = true)
            else -> {
                if (isEndOrHome) endOrHomeBrowseActive = true
                paging.move(turn)?.let { anchor ->
                    if (paging.publishImportPage(anchor.importPage)) {
                        emitReaderPage(anchor.importPage, fromEndOrHome = isEndOrHome)
                        session.goToPage(anchor.importPage)
                    }
                }
            }
        }
    }

    val openPassage: (BookPassage) -> Unit = open@{ passage ->
        val target = book.page(passage.page) ?: return@open
        if (passage.book != book.id || passage.start !in 0 until passage.end || passage.end > target.text.length ||
            target.text.substring(passage.start, passage.end) != passage.text) return@open
        pendingNavigationIsJump = true
        pendingDwellJump = true
        pdf.selection = null
        asking = false
        explainOpen = false
        if (hasOriginal) textView = false
        passageJump = passage
        passageJumpTick++
        if (originalPdf) pdf.jump(passage.page)
        paging.anchor = BookPageAnchor(passage.page, passage.start)
        if (paging.publishImportPage(passage.page)) emitReaderPage(passage.page, forceCommit = true)
    }

    LaunchedEffect(pendingCourseJump, book.id) {
        val jump = pendingCourseJump
        if (jump != null && jump.book == book.id) {
            openPassage(jump)
            onConsumePendingCourseJump()
        }
    }
    /** 书内查找跳转：保留当前视图（原版/文字版）和读法（翻页/滚动），定位到命中页和精确 UTF-16 区间。 */
    val openSearchHit: (BookSearchHit) -> Unit = jump@{ hit ->
        val target = book.page(hit.page) ?: return@jump
        if (hit.start !in 0 until hit.end || hit.end > target.text.length ||
            target.text.substring(hit.start, hit.end) != hit.quote) return@jump
        pendingNavigationIsJump = true
        pendingDwellJump = true
        val passage = hit.toPassage(book.id)
        pdf.selection = null
        asking = false
        explainOpen = false
        passageJump = passage
        passageJumpTick++
        val targetUnrecognized = hasOriginal && (target.ocrPending || (target.text.isBlank() && book.hasPendingOcr))
        if (!textView || targetUnrecognized) {
            if (originalPdf) pdf.jump(hit.page)
        }
        paging.anchor = BookPageAnchor(hit.page, hit.start)
        if (paging.publishImportPage(hit.page)) emitReaderPage(hit.page, forceCommit = true)
        session.goToPage(hit.page)
    }
    val selectSearchHit: (Int) -> Unit = { index ->
        val hit = searchResult.hits.getOrNull(index)
        if (hit != null) {
            searchActiveIndex = index
            openSearchHit(hit)
        }
    }
    val quoteAnchor = remember(ui.page, source, practiceSource, passageJump) {
        if (passageJump?.page == ui.page) passageJump?.text
        else if (source == null) null
        else if (practiceSource != null) practiceSource.quote?.takeIf { practiceSource.page == ui.page }
        else StudyCoachCourse.sourceQuote(source, ui.page)
    }
    // 讲解出处带着解析器核对过的块范围进来。只有这一页的同一偏移仍是同一句原文时才用它；
    // 对不上就什么都不标，绝不回落到「第一次出现」或别处的句子。
    val citedRange = remember(practiceSource?.target, passageJump, ui.page, page.text) {
        passageJump?.takeIf { it.page == ui.page }?.let { return@remember it.start until it.end }
        val target = practiceSource?.target?.takeIf { it.pdfPage == ui.page } ?: return@remember null
        if (target.start >= 0 && target.end > target.start && target.end <= page.text.length &&
            page.text.substring(target.start, target.end) == target.quote) target.start until target.end else null
    }
    LaunchedEffect(pdfLayers, page.number, retry) { if (originalPdf) pdfLayers.keep(page) }
    val originalText = pdfLayers[page.number]
    val layer = originalText?.getOrNull()
    val rangeLocation = remember(layer, citedRange, page.text) {
        val range = citedRange ?: return@remember null
        val unit = layer ?: return@remember null
        val frame = unit.spans.firstOrNull()?.geometry?.frame ?: return@remember null
        locateCitedPdfRange(PdfPageText(frame, unit, emptyList()), page.text, range)
    }
    var originalSize by remember(book.id) { mutableStateOf(IntSize.Zero) }
    val image = rememberOriginalPageImage(library, book, page.number,
        hasOriginal && !pdfVisible && (!textView || source != null || pageUnrecognizedOriginal), null, retry)
    // 页图还没回来时先铺纸：这一页自己有尺寸就按它铺，量不到才借上一张成功页图的比例。
    // 图到了原地换掉，后面的按钮不往下跳；横竖混排的书也不会先铺上一页那张的纸再塌下来。
    var paperAspect by remember(book.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(image) {
        (image as? PageImage.Ready)?.let { paperAspect = it.image.width.toFloat() / it.image.height }
    }
    val imageScroll = remember(book.id, ui.page) { ScrollState(0) }
    val imageHeight = originalSize.height
    val view = (remote.page as? com.coach.desktop.reader.PageState.Ready)?.view
    val samePage = remote.current?.id == book.id && remote.current?.version == "1" &&
        view?.document == book.id && view.pdfPage == ui.page
    val boxes = if (citedRange != null) rangeLocation?.boxes
        else if (samePage) (remote.boxes as? com.coach.desktop.reader.BoxesState.Ready)?.boxes else null
    val located = if (citedRange != null) rangeLocation?.located
        else if (boxes != null && quoteAnchor != null) com.coach.desktop.reader.QuoteLocator.locate(boxes, quoteAnchor) else null
    val loadedAspect = if (pdfVisible) pdf.rasters[ui.page]?.aspect else
        (image as? PageImage.Ready)?.image?.let { it.width.toFloat() / it.height }
    val aspectOk = boxes != null && loadedAspect != null && kotlin.math.abs(loadedAspect - boxes.width / boxes.height) < 0.05
    val textTarget = practiceSource?.target
    val textTargetValid = textTarget?.let { target -> book.page(target.pdfPage)?.text?.let { stored ->
        target.start >= 0 && target.end > target.start && target.end <= stored.length &&
            stored.substring(target.start, target.end) == target.quote
    } } == true
    val sourceWarning = if (passageJump?.page == page.number && originalText != null && !textView &&
        rangeLocation?.located !is com.coach.desktop.reader.Located.Found) "无法确认原句：无法读取这一页的文字位置。"
    else if (source == null) null else when {
        textView && practiceSource?.warning != null -> practiceSource.warning
        textView && textTarget != null && !textTargetValid -> "无法确认原句：这一页的内容变了，这条出处无法定位。"
        textView && citedRange == null && !quoteAnchor.isNullOrEmpty() &&
            page.text.indexOf(quoteAnchor) != page.text.lastIndexOf(quoteAnchor) -> "无法确认原句：这一页有多处相同内容。"
        textView -> null
        practiceSource?.warning != null -> practiceSource.warning
        practiceSource?.page != null && practiceSource.page != ui.page -> "无法确认原句：本题依据在第 ${practiceSource.page} 页。"
        practiceSource?.target != null && citedRange == null -> "无法确认原句：这一页的内容变了，这条出处无法定位。"
        textOnlySource -> null
        image is PageImage.Failed || (pdfVisible && pdf.rasters[ui.page]?.failed == true) -> "无法确认原句：这一页的图像打不开。"
        citedRange != null && originalText != null && rangeLocation == null -> "无法确认原句：无法读取这一页的文字位置。"
        remote.failure != null || remote.page is com.coach.desktop.reader.PageState.Failed -> "无法确认原句：无法读取这一页的定位信息。"
        remote.current != null && (remote.current?.id != book.id || remote.current?.version != "1") -> "无法确认原句：教材版本不一致。"
        citedRange == null && samePage && remote.boxes is com.coach.desktop.reader.BoxesState.Failed -> "无法确认原句：无法读取这一页的文字位置。"
        samePage && quoteAnchor.isNullOrBlank() -> "无法确认原句：没有可定位的原文。"
        located == com.coach.desktop.reader.Located.NoTextLayer -> "无法确认原句：这一页没有可定位的文字。"
        located == com.coach.desktop.reader.Located.NotFound -> "无法确认原句：这一页没有找到对应内容。"
        located == com.coach.desktop.reader.Located.Ambiguous -> "无法确认原句：这一页有多处相同内容。"
        located is com.coach.desktop.reader.Located.Found && loadedAspect != null && !aspectOk -> "无法确认原句：页面图像与文字位置不一致。"
        else -> null
    }
    LaunchedEffect(pdfScrolling, ui.page, citedRange, passageJumpTick, located, aspectOk) {
        if (pdfScrolling && aspectOk && boxes != null && located is com.coach.desktop.reader.Located.Found) {
            val top = located.rects.minOf { it.y0 } / boxes.height
            pdf.jump(ui.page, top.toFloat(), reveal = true)
        }
    }

    LaunchedEffect(citedRange, passageJumpTick, located, aspectOk, imageHeight, imageScroll.maxValue, imageScroll.viewportSize,
        textView, explainOpen) {
        if (located is com.coach.desktop.reader.Located.Found && boxes != null &&
            aspectOk && imageHeight > 0 && imageScroll.maxValue != Int.MAX_VALUE && !textView && !explainOpen) {
            val top = located.rects.minOf { it.y0 } / boxes.height * imageHeight
            imageScroll.scrollTo((top - imageScroll.viewportSize / 3).toInt().coerceIn(0, imageScroll.maxValue))
        }
    }

    LaunchedEffect(book.id, ui.page, quoteAnchor) {
        remote.loadCatalog()
        if (quoteAnchor != null) {
            session.openRemoteAt(book.id, ui.page, quoteAnchor)
        } else {
            session.openRemote(book.id, ui.page)
        }
        // 这里只拥有原文视图；作答仍归原 controller，出处浏览不保存独立读书书签。
        if (remote.current?.id == book.id && remote.current?.version == "1" &&
            com.coach.desktop.reader.PdfPages.toPdf(session.state.position.page) == ui.page) {
            remote.show(ui.page)
        }
    }

    // 首次打开一本书时顶底栏常显；用户第一次主动收起后按书持久化，此后才开启自动隐藏。
    val hudPrefStore = remember(library.directory) { BookHudPreferenceStore(library.directory) }
    var hudDismissed by remember(book.id, hudPrefStore) { mutableStateOf(hudPrefStore.isDismissed(book.id)) }
    var hudVisible by remember(book.id, hudPrefStore, ui.source) { mutableStateOf(ui.source != null || !hudDismissed) }
    val pagedWindowsText = textView && !pageUnrecognizedOriginal && readMode == BookReadMode.Paged &&
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    var hoverHudVisible by remember(book.id, ui.source, pagedWindowsText) { mutableStateOf(false) }
    var hudRevealedByTap by remember(book.id) { mutableStateOf(false) }
    var hudPinned by remember(book.id) { mutableStateOf(false) }
    fun markHudDismissed() {
        hudDismissed = true
        hoverHudVisible = false
        hudRevealedByTap = false
        hudPinned = false
        scope.launch(Dispatchers.IO) { hudPrefStore.markDismissed(book.id) }
    }
    // 伴读在桌面并排，窄窗使用底部抽屉。
    var companionDocked by remember(book.id) { mutableStateOf(true) }
    val bookmarkStore = remember(library.directory) { BookBookmarkStore(library.directory) }
    val pdfBitmapCache = remember(library, book.id) { OriginalPdfBitmapCache() }
    var bookmarks by remember(book.id) {
        mutableStateOf(runCatching { bookmarkStore.list(book.id) }.getOrDefault(emptyList()))
    }
    LaunchedEffect(explainOpen) {
        if (explainOpen) {
            openReadingCompanion()
            companionTab = ReaderCompanionTab.EXPLAIN
        }
    }
    LaunchedEffect(notesOpen) {
        if (notesOpen) {
            companionOpen = true
            companionTab = ReaderCompanionTab.NOTES
        }
    }
    LaunchedEffect(activeHardPoint) {
        if (activeHardPoint != null) {
            companionOpen = true
            companionTab = ReaderCompanionTab.EXPLAIN
        }
    }
    var pendingNoteSelection by remember(book.id) { mutableStateOf<SourceBlock?>(null) }
    fun startNoteFromSelection(sel: SourceBlock) {
        companionOpen = true
        companionTab = ReaderCompanionTab.NOTES
        notesOpen = true
        if (samePage && view != null && view.pdfPage == sel.page) {
            if (session.noting) session.cancelNote()
            session.beginNoteForRange(sel.start, sel.end, sel.text)
            pendingNoteSelection = null
        } else {
            pendingNoteSelection = sel
            if (sel.page != ui.page) onPage(sel.page)
        }
    }
    LaunchedEffect(pendingNoteSelection, samePage, view) {
        val sel = pendingNoteSelection ?: return@LaunchedEffect
        val readyView = view ?: return@LaunchedEffect
        if (samePage && readyView.pdfPage == sel.page) {
            if (session.noting) session.cancelNote()
            session.beginNoteForRange(sel.start, sel.end, sel.text)
            pendingNoteSelection = null
        }
    }
    fun switchView(nextTextView: Boolean, originalAnchor: BookPageAnchor = paging.anchor) {
        if (nextTextView == textView) return
        val touchPdf = originalPdf && originalPdfTouchEnabled
        if (!nextTextView) {
            // 文字 -> 原页：以当前 BookPageAnchor 为唯一真源，避免来回切时回弹旧页。
            val targetPage = originalAnchor.importPage.takeIf { book.page(it) != null } ?: ui.page
            val passage = passageJump?.takeIf {
                touchPdf && it.page == targetPage && it.start == originalAnchor.offsetUtf16
            }
            if (passage != null) openPassage(passage)
            else {
                if (touchPdf) passageJump = null
                if (originalPdf) pdf.jump(targetPage)
                paging.anchor = BookPageAnchor(targetPage, originalAnchor.offsetUtf16)
                if (paging.publishImportPage(targetPage)) onPage(targetPage)
            }
        } else {
            // 原页 -> 文字：先锁死当前可视 PDF 页码（或选区所在页）到同一 BookPageAnchor。
            val targetPage = chosen?.page ?: pdf.page.takeIf { book.page(it) != null } ?: ui.page
            val targetOffset = chosen?.start
                ?: if (paging.anchor.importPage == targetPage) paging.anchor.offsetUtf16 else 0
            paging.anchor = BookPageAnchor(targetPage, targetOffset)
            if (paging.publishImportPage(targetPage)) onPage(targetPage)
            if (chosen != null && touchPdf) passageJump = BookPassage.from(book, chosen)
        }
        textView = nextTextView
        pdf.selection = null
        viewSaveError = false
        val revision = ++viewSaveRevision
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                library.persistTextView(book.id, nextTextView)
            } catch (_: SQLException) {
                if (revision == viewSaveRevision) viewSaveError = true
            }
        }
    }
    fun toggleBookmark(pageNumber: Int) {
        val note = currentSection?.title.orEmpty()
        val exists = bookmarks.any { it.page == pageNumber }
        bookmarks = if (exists) bookmarks.filterNot { it.page == pageNumber }
        else (bookmarks + BookBookmark(book.id, pageNumber, note)).sortedBy { it.page }
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (exists) bookmarkStore.remove(book.id, pageNumber)
                else bookmarkStore.add(book.id, pageNumber, note)
            }
        }
    }

    val colors = CoachTheme.colors
    val paperCanvas = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
    val highlight = if (colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl
    val density = LocalDensity.current
    val onRelogin = LocalBookRelogin.current
    var readerOriginDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val typeMenuExtra: @Composable (() -> Unit) -> Unit = { close ->
        if (originalPdf && onImportReviewedBlocks != null) {
            DropdownMenuItem(
                text = { Text("导入已核对的排版块") },
                modifier = Modifier.testTag("import-reviewed-blocks"),
                onClick = { close(); onImportReviewedBlocks(book.id) },
            )
        }
        if (pdfVisible) {
            for (scrolling in listOf(true, false)) {
                val label = if (scrolling) "适宽连续" else "双页对开"
                DropdownMenuItem(
                    text = { Text(label + if (pdf.scrolling == scrolling) " ✓" else "") },
                    modifier = Modifier.testTag(if (scrolling) "original-mode-scroll" else "original-mode-paged"),
                    onClick = {
                        if (pdf.scrolling != scrolling) {
                            pdf.switchMode()
                            library.savePdfScrollMode(book.id, scrolling)
                            publishPdfPage(pdf.page)
                        }
                        close()
                    },
                )
            }
        } else {
            for (mode in listOf(BookReadMode.Paged, BookReadMode.Scroll)) {
                val label = if (mode == BookReadMode.Scroll) "文字滚动" else "文字翻页"
                DropdownMenuItem(
                    text = { Text(label + if (readMode == mode) " ✓" else "") },
                    modifier = Modifier.testTag(if (mode == BookReadMode.Scroll) "text-mode-scroll" else "text-mode-paged"),
                    onClick = {
                        readMode = mode
                        runCatching { library.saveTextScrollMode(book.id, mode == BookReadMode.Scroll) }
                        close()
                    },
                )
            }
        }
    }

    // 单层全铺画布（容器嵌套 = 1 层）：无左轨、无 Desk 纸卡外框、无常驻顶底栏。
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(paperCanvas)
            .testTag("textbook-reader")
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (notesPageOpen) {
                    if (event.key == Key.Escape) {
                        notesPageOpen = false
                        return@onPreviewKeyEvent true
                    }
                    val turnsPage = event.key in setOf(
                        Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown,
                        Key.MoveHome, Key.MoveEnd, Key.Spacebar,
                    )
                    if (turnsPage && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                        return@onPreviewKeyEvent true
                    }
                }
                val mod = event.isCtrlPressed || event.isMetaPressed
                val typingNow = typing || askInputFocused
                when {
                    mod && !event.isAltPressed && event.key == Key.F -> {
                        searchOpen = true
                        hudVisible = true
                        searchFocusTick++
                        true
                    }
                    mod && !event.isAltPressed && event.key == Key.G -> {
                        hudVisible = true
                        editingJump = true
                        jumpSession++
                        jump = ui.page.toString()
                        true
                    }
                    mod && !event.isAltPressed && event.key == Key.D -> {
                        toggleBookmark(ui.page)
                        true
                    }
                    event.key == Key.F11 || (!typingNow && !mod && event.key == Key.F) -> {
                        if (hudVisible || hoverHudVisible || tocOpen || companionOpen || searchOpen) {
                            hudVisible = false
                            markHudDismissed()
                            tocOpen = false
                            closeCompanion()
                            searchOpen = false
                            searchFocused = false
                        } else {
                            hudVisible = true
                        }
                        true
                    }
                    !typingNow && !mod && event.key == Key.Tab -> {
                        hudVisible = !(hudVisible || hoverHudVisible)
                        if (!hudVisible) markHudDismissed()
                        true
                    }
                    !typingNow && !mod && (event.key == Key.T || event.key == Key.LeftBracket) -> {
                        tocOpen = !tocOpen
                        true
                    }
                    !typingNow && !mod && (event.key == Key.L || event.key == Key.RightBracket) -> {
                        if (companionOpen) closeCompanion() else openReadingCompanion()
                        true
                    }
                    !typingNow && !mod && event.key == Key.V && hasOriginal -> {
                        switchView(!textView)
                        true
                    }
                    event.key == Key.Escape -> {
                        when {
                            searchOpen -> { searchOpen = false; searchFocused = false; true }
                            editingJump -> { editingJump = false; focusManager.clearFocus(); true }
                            tocOpen -> { tocOpen = false; true }
                            companionOpen -> { closeCompanion(); true }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .pointerInput(book.id, hudPrefStore, ui.source, pagedWindowsText, density) {
                awaitPointerEventScope {
                    // The first line of a full-bleed sheet sits inside the toolbar's height.
                    // Use a narrow edge to open it, then allow movement across its controls.
                    val revealDepth = (if (pagedWindowsText) 12.dp else 48.dp).toPx()
                    val hideDepth = 48.dp.toPx() + 48f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type != PointerEventType.Move) continue
                        val change = ev.changes.firstOrNull() ?: continue
                        if (pagedWindowsText && change.pressed) continue
                        val y = change.position.y
                        val h = size.height.toFloat()
                        if (y <= revealDepth || y >= h - revealDepth) {
                            if (pagedWindowsText) hoverHudVisible = true else hudVisible = true
                            hudRevealedByTap = false
                        } else if (hudDismissed && !hudRevealedByTap && !hudPinned && !editingJump && !searchOpen && y > hideDepth && y < h - hideDepth) {
                            hoverHudVisible = false
                            hudVisible = false
                        }
                    }
                }
            }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                readerOriginDp = (pos.x / density.density) to (pos.y / density.density)
            },
    ) {
        val rootWidth = maxWidth
        val rootHeight = maxHeight
        val presentation = companionPresentationForWidth(rootWidth.value)
        val canDockCompanion = presentation != CompanionPresentation.BOTTOM_SHEET && hinge == null
        val isBottomSheetCompanion = presentation == CompanionPresentation.BOTTOM_SHEET && hinge == null
        val verticalSplit = bookVerticalSplit(rootWidth.value, rootHeight.value, readerOriginDp?.first ?: 0f, hinge)
        val tabletopSplit = if (verticalSplit == null) bookTabletopSplit(rootWidth.value, rootHeight.value, readerOriginDp?.second ?: 0f, hinge) else null
        val effectiveSideDocked = companionOpen && canDockCompanion && verticalSplit == null && tabletopSplit == null
        val effectiveBottomSheet = companionOpen && isBottomSheetCompanion && verticalSplit == null && tabletopSplit == null
        val effectiveSideOverlay = companionOpen && !effectiveSideDocked && !effectiveBottomSheet && verticalSplit == null && tabletopSplit == null
        // On Windows paged text, a temporary edge reveal overlays already laid-out pages.
        val reserveHudSpace = hudVisible || hudPinned || editingJump || searchOpen
        val showHud = reserveHudSpace || hoverHudVisible
        val sideDrawerWidth = companionSideWidth(rootWidth.value)
        // Leave space above the question composer for the answer and its clickable references.
        val preferredDrawerHeight = if (companionTab == ReaderCompanionTab.ASK) 340.dp else 220.dp
        val bottomDrawerHeight = maxOf(rootHeight * 0.50f, preferredDrawerHeight)
            .coerceAtMost((rootHeight - 160.dp).coerceAtLeast(220.dp))

        val currentSelectionActive by rememberUpdatedState(learnableSelection != null)
        val pageSurface: @Composable (Modifier) -> Unit = { boxModifier ->
            Box(
                boxModifier
                    .fillMaxSize()
                    .pointerInput(book.id) {
                        awaitPointerEventScope {
                            var start: androidx.compose.ui.geometry.Offset? = null
                            var downTime = 0L
                            var hadSelectionOnDown = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed && !change.previousPressed) {
                                    start = change.position
                                    downTime = change.uptimeMillis
                                    hadSelectionOnDown = currentSelectionActive
                                } else if (!change.pressed && change.previousPressed) {
                                    val p = start
                                    start = null
                                    if (p != null && !hadSelectionOnDown && !change.isConsumed &&
                                        change.uptimeMillis - downTime < 500 &&
                                        (change.position - p).getDistance() < 12f &&
                                        p.x in size.width * .3f..size.width * .7f &&
                                        p.y in size.height * .25f..size.height * .75f) {
                                        hudVisible = !(hudVisible || hoverHudVisible)
                                        if (hudVisible) hudRevealedByTap = true else markHudDismissed()
                                    }
                                }
                            }
                        }
                    }
                    .background(paperCanvas)
                    .testTag("reader-page")
                    .focusRequester(pageFocus)
                    .onFocusChanged { pageFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (notesPageOpen) {
                            if (event.key == Key.Escape) notesPageOpen = false
                            return@onPreviewKeyEvent true
                        }
                        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                        val typingNow = typing || askInputFocused
                        if (!typingNow && learnableSelection != null && event.key == Key.Enter) {
                            openSelectionAction(learnableSelection, SelectionExplainAction.EXPLAIN)
                            return@onPreviewKeyEvent true
                        }
                        if (pdfScrolling && !typingNow && event.key in setOf(
                                Key.DirectionDown, Key.DirectionUp, Key.PageDown, Key.PageUp,
                                Key.Spacebar, Key.MoveHome, Key.MoveEnd,
                            )) {
                            pdf.selection = null
                            passageJump = null
                            scope.launch {
                                val targetScroll = pdf.activeScroll ?: scroll
                                when (event.key) {
                                    Key.MoveEnd -> {
                                        endOrHomeBrowseActive = true
                                        targetScroll.scrollToEnd()
                                    }
                                    Key.MoveHome -> {
                                        endOrHomeBrowseActive = true
                                        targetScroll.scrollTo(ScrollReadPosition(0))
                                    }
                                    Key.DirectionDown, Key.DirectionUp -> targetScroll.scrollLine(event.key == Key.DirectionDown)
                                    else -> targetScroll.scrollScreen(event.key == Key.PageDown || (event.key == Key.Spacebar && !event.isShiftPressed))
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (!typingNow && event.key == Key.Spacebar) {
                            turnLayout(if (event.isShiftPressed) ReaderTurn.Previous else ReaderTurn.Next)
                            return@onPreviewKeyEvent true
                        }
                        val turn = readerPagingTurn(event.key, typingNow)
                        turn?.let(turnLayout) ?: return@onPreviewKeyEvent false
                        true
                    }
                    .focusable(),
            ) {
                if (textView && !pageUnrecognizedOriginal) {
                    val ranges = remember(book.pages, source, practiceSource, ui.page, quoteAnchor, located, aspectOk, marks, passageJump, textSelection) {
                        val result = mutableMapOf<Int, List<IntRange>>()
                        if (practiceSource == null) {
                            source?.blocks.orEmpty().groupBy { it.page }.forEach { (number, blocks) ->
                                val stored = book.page(number)?.text.orEmpty()
                                val confirmed = if (book.format != "pdf" || book.page(number)?.ocr == true) blocks
                                    else if (number == ui.page && located is com.coach.desktop.reader.Located.Found && aspectOk)
                                        blocks.filter { !it.heading && it.text == quoteAnchor }.take(1)
                                    else emptyList()
                                result[number] = confirmed.filter { it.start >= 0 && it.end > it.start &&
                                    it.end <= stored.length && stored.substring(it.start, it.end) == it.text }
                                    .map { it.start until it.end }
                            }
                        } else {
                            val target = practiceSource.target
                            val number = target?.pdfPage ?: practiceSource.page
                            val stored = number?.let { book.page(it)?.text }.orEmpty()
                            val range = if (target != null) {
                                if (target.start >= 0 && target.end > target.start && target.end <= stored.length &&
                                    stored.substring(target.start, target.end) == target.quote) target.start until target.end else null
                            } else practiceSource.quote?.takeIf { it.isNotEmpty() }?.let { quote ->
                                val at = stored.indexOf(quote)
                                if (at >= 0 && stored.indexOf(quote, at + 1) < 0) at until at + quote.length else null
                            }
                            if (number != null && range != null) result[number] = listOf(range)
                        }
                        passageJump?.let { result[it.page] = result[it.page].orEmpty() + listOf(it.start until it.end) }
                        textSelection?.let { result[it.page] = result[it.page].orEmpty() + listOf(it.start until it.end) }
                        if (marks.isNotEmpty()) result[ui.page] = result[ui.page].orEmpty() +
                            marks.map { it.startUtf16 until it.endUtf16 }
                        result
                    }
                    BookPageView(
                        book, paging, ranges, turnLayout, libraryDirectory = library.directory,
                        modifier = if (companionOpen) Modifier.align(Alignment.Center).widthIn(max = 720.dp) else Modifier.fillMaxSize(),
                        turnStyle = turnStyle, readMode = readMode, onOpenTableRow = openPassage,
                        onLoadPdfVisuals = if (originalPdf) onLoadPdfVisuals else null,
                        onOpenOriginalBlock = if (originalPdf) { item ->
                            switchView(false, BookPageAnchor(item.page, item.anchorOffset))
                        } else null,
                        onSelectText = { textSelection = it },
                        hardPoints = sectionHardPoints,
                        onHardPointClick = { hp, blk ->
                            activeHardPoint = hp to blk
                            companionOpen = true
                            companionTab = ReaderCompanionTab.EXPLAIN
                            textSelection = null
                            pdf.selection = null
                        },
                        onSpot = { anchor ->
                            if (ui.source == null && !endOrHomeBrowseActive && kotlin.math.abs(anchor.importPage - book.readingPage) <= 5) {
                                runCatching { library.saveReadSpot(book.id, anchor.importPage, anchor.offsetUtf16) }
                            }
                        },
                        onPosition = { number ->
                            if (paging.publishImportPage(number)) {
                                if (textSelection?.page != number) textSelection = null
                                emitReaderPage(number)
                                session.goToPage(number)
                            }
                        },
                        immersive = true,
                        hardPointGutter = autoGuide && autoExplain && explainer.identity != null,
                    )
                } else if (originalPdf) {
                    val citation = if (boxes != null && located is com.coach.desktop.reader.Located.Found)
                        CitedPdfLocation(boxes, located) else null
                    OriginalPdfReader(
                        book, library, pdf, scroll, pdfLayers, citation, quoteAnchor, highlightRevision,
                        turnStyle, publishPdfPage, onTurn = turnTo, immersive = true, bitmapCache = pdfBitmapCache,
                    )
                } else {
                    val paper = if (colors.dark) CoachV7.Dark.pgPaper else CoachV7.Light.pgPaper
                    val pageBoxes = if (readerBoxesAreGeometry(book.format, page.ocr)) boxes else null
                    val aspect = readerPaperAspect(pageBoxes?.width, pageBoxes?.height, paperAspect)
                    val citation = if (aspectOk && boxes != null && located is com.coach.desktop.reader.Located.Found)
                        CitedPdfLocation(boxes, located) else null
                    Box(Modifier.fillMaxSize().background(paper)) {
                        Column(Modifier.fillMaxSize().verticalScroll(imageScroll).padding(end = 12.dp).testTag("remote-pages")) {
                            OriginalBookPage(
                                "${book.title} ${book.pageLabel(ui.page)}", image, aspect, layer, page.text, null, marks,
                                citation, quoteAnchor, onResize = { originalSize = it }, onRetry = { retry++ },
                                onSelection = {},
                            )
                        }
                        ReaderScrollbar(imageScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }

        val handleCitationJump: (BookPassage) -> Unit = { passage ->
            openPassage(passage)
            // 仅窄桌面浮层模式下自动收起抽屉，以免盖住刚跳转到的出处；并排与底部半屏抽屉下保留对照。
            if (effectiveSideOverlay) closeCompanion()
        }

        val visibleScreenSlices = remember(textView, pageUnrecognizedOriginal, paging.pages,
            readingAnchor, paging.columns, paragraphPage.number) {
            if (!textView || pageUnrecognizedOriginal) null
            else paging.visibleProseSlices().filter { it.importPage == paragraphPage.number }
                .map { it.startUtf16 until it.endUtf16 }
        }
        val explainTabContent: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                if (companionOpen) {
                    activeCheckpoint?.let { cp ->
                        EndOfSectionCheckpointCard(
                            data = cp,
                            onAnswer = { quiz, chosen, isGuessed ->
                                val sec = book.lessons.firstOrNull { it.id == cp.sectionId } ?: currentSection
                                if (sec != null) {
                                    onSaveSectionQuiz?.invoke(
                                        sec,
                                        150 + cp.questions.indexOfFirst { it.id == quiz.id }.coerceAtLeast(0),
                                        Practice(quiz.stem, quiz.anchor?.quoteAnchor.orEmpty(), quiz.correctOption, quiz.options),
                                        PracticeAnswer(text = chosen, revealed = true, understood = chosen == quiz.correctOption, guessed = isGuessed),
                                    )
                                }
                            },
                            onDismiss = { activeCheckpoint = null },
                        )
                    }
                }
                val chosenHp = activeHardPoint
                val sec = currentSection
                if (chosenHp != null && sec != null) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("难点 · ${chosenHp.first.label}", style = MaterialTheme.typography.labelLarge, color = v7Pine(), modifier = Modifier.weight(1f))
                            V7TextButton({ activeHardPoint = null }) { Text("关闭") }
                        }
                        Text(chosenHp.first.explanation, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            V7TextButton({
                                val blk = chosenHp.second
                                activeHardPoint = null
                                openSelectionAction(blk, SelectionExplainAction.ASK)
                            }) { Text("继续追问") }
                            if (hasOriginal) {
                                V7TextButton({ handleCitationJump(BookPassage.from(book, chosenHp.second)) }) { Text("定位原文") }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (explainingSelection != null) {
                        BookExplainPanel(
                            book, paragraphPage.number, explainConsent, explainer,
                            selection = explainingSelection, library = library,
                            onOpenOriginal = handleCitationJump,
                        )
                    } else if (courseContext != null) {
                        CourseCompanionPanel(
                            courseContext = courseContext,
                            activeBookId = book.id,
                            activePage = paragraphPage.number,
                            onSwitchSource = { targetId, targetPage ->
                                onSwitchCourseSource?.invoke(targetId, targetPage)
                            },
                            onJumpCitation = { ref ->
                                onJumpCourseCitation?.invoke(ref, readingAnchor)
                            },
                            onSavePracticeDraft = { draft ->
                                onSaveCoursePracticeDraft?.invoke(draft)
                            },
                            onRefreshPack = {
                                onRefreshCoursePack?.invoke()
                            },
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            ReadingMethodPanel(
                                book = book,
                                chapter = currentChapterGroup,
                                draft = practiceDraft,
                                draftReady = practiceDraftReady,
                                library = library,
                                onDraftChange = { persistPracticeDraft(it) },
                                onJumpPage = { target ->
                                    pendingNavigationIsJump = true
                                    pendingDwellJump = true
                                    turnTo(target)
                                },
                                onCommitChoice = { sec, qIdx, prac, ans ->
                                    (onSaveSectionPretest ?: onSaveSectionQuiz)?.invoke(sec, qIdx, prac, ans)
                                },
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                        SingleBookCompanionPanel(
                            book = book,
                            onOpenSectionMainline = onOpenSectionMainline,
                            wholeOriginalPageVisible = !textView,
                            currentPage = paragraphPage.number,
                            currentPageOffset = readingAnchor.offsetUtf16,
                            visiblePageRanges = visibleScreenSlices,
                            currentSectionIndex = currentSectionIndex,
                            explainer = explainer,
                            library = library,
                            draft = practiceDraft,
                            studyPreference = studyPreference,
                            onUpdateStudyPreference = { nextPref, modeSwitched ->
                                studyPreference = StudyModeStore.save(library, nextPref)
                                if (nextPref.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) {
                                    activeWhisper = null
                                    activeCheckpoint = null
                                    closeCompanion()
                                } else if (modeSwitched) {
                                    companionOpen = true
                                    companionTab = ReaderCompanionTab.EXPLAIN
                                }
                            },
                            onJumpPage = { target -> pendingNavigationIsJump = true; pendingDwellJump = true; turnTo(target) },
                            onDraftChange = { persistPracticeDraft(it) },
                            onOpenAnchor = { anchor ->
                                val targetPage = book.page(anchor.pdfPage)
                                val exactPassage = if (targetPage != null &&
                                    anchor.startUtf16 in 0 until anchor.endUtf16 &&
                                    anchor.endUtf16 <= targetPage.text.length &&
                                    targetPage.text.substring(anchor.startUtf16, anchor.endUtf16) == anchor.quoteAnchor
                                ) {
                                    BookPassage(book.id, anchor.pdfPage, anchor.startUtf16, anchor.endUtf16, anchor.quoteAnchor)
                                } else null
                                if (exactPassage != null) {
                                    handleCitationJump(exactPassage)
                                } else {
                                    turnTo(anchor.pdfPage)
                                }
                            },
                            onCommitQuizAnswer = { sec, qIdx, prac, ans ->
                                onSaveSectionQuiz?.invoke(sec, qIdx, prac, ans)
                            },
                            onCommitPretestAnswer = { sec, qIdx, prac, ans ->
                                (onSaveSectionPretest ?: onSaveSectionQuiz)?.invoke(sec, qIdx, prac, ans)
                            },
                            onAskAboutText = { promptText ->
                                companionTab = ReaderCompanionTab.ASK
                                changeAskDraft(promptText)
                            },
                            onOpenLogin = {
                                if (onRelogin != null) onRelogin(if (explainer.revoked) "授权已撤销，请重新登录。" else null)
                                else companionTab = ReaderCompanionTab.EXPLAIN
                            },
                            onInputFocusChanged = { practiceInputFocused = it },
                            bottomContent = if (crossBookCards.isNotEmpty() || conceptSayings != null || checkingSayings || !sayingsNotice.isNullOrBlank()) {
                                {
                                    CrossBookCompanionSection(
                                        cards = crossBookCards,
                                        board = conceptSayings,
                                        checking = checkingSayings,
                                        notice = sayingsNotice,
                                        onOpenConcept = { conceptId -> openOtherBooks(conceptId) },
                                        onJumpToBookPage = { targetBookId, targetPage, quote, offset ->
                                            if (targetBookId == book.id) {
                                                val pageObj = book.page(targetPage)
                                                val passage = if (pageObj != null && !quote.isNullOrBlank()) {
                                                    ConceptSayingLogic.passageIfExact(book.id, targetPage, pageObj.text, offset, quote)
                                                } else null
                                                if (passage != null) handleCitationJump(passage) else turnTo(targetPage)
                                            } else if (onOpenCrossBookPage != null) {
                                                onOpenCrossBookPage(targetBookId, targetPage, quote, offset)
                                            }
                                        },
                                        onMuteConcept = { conceptId ->
                                            crossBookCards = crossBookCards.filterNot { it.conceptId == conceptId }
                                            if (openedConceptId == conceptId) {
                                                openedConceptId = null
                                                conceptSayings = null
                                                checkingSayings = false
                                                sayingsNotice = null
                                            }
                                            if (onMuteConcept != null) {
                                                onMuteConcept(conceptId)
                                            } else {
                                                scope.launch(Dispatchers.IO) {
                                                    runCatching { library.setConceptMuted(conceptId, true) }
                                                    localBooklistRev++
                                                }
                                            }
                                        },
                                    )
                                }
                            } else null,
                        )
                            }
                        }
                    }
                }
            }
        }

        val activeColSession = activeAskCollection?.let { sessionForCollection(it.id) } ?: CollectionQaSessionState()
        fun submitCollectionQuestion(rawQuestion: String) {
            val col = activeAskCollection ?: return
            val q = rawQuestion.trim()
            if (q.isEmpty() || q.length > 600) return
            val prev = sessionForCollection(col.id)
            if (!aiEnabled) {
                updateCollectionSession(
                    col.id,
                    prev.copy(
                        loading = false,
                        error = "已在设置中关闭伴读讲解与提问，可在设置中重新开启。" to ExplainTrouble.Other,
                        lastQuestion = q,
                    ),
                )
                return
            }
            if (isOffline) {
                updateCollectionSession(
                    col.id,
                    prev.copy(
                        loading = false,
                        error = OfflineCopy.COLLECTION_ASK_OFFLINE to ExplainTrouble.Unreachable,
                        fallbackPages = emptyList(),
                        lastQuestion = q,
                    ),
                )
            } else {
                updateCollectionSession(col.id, prev.copy(loading = true, error = null, fallbackPages = emptyList(), lastQuestion = q))
            }
            scope.launch {
                // The upload probe only tells us the last server check failed. The user already
                // pressed send, so the explainer still gets the question. A local or recovered
                // explainer can answer; an unreachable server keeps the offline message below.
                var keepOfflineMessage = false
                if (isOffline) {
                    val uploadClient = explainer.unifiedUploadClient()
                    if (uploadClient != null) {
                        val live = withContext(Dispatchers.IO) {
                            runCatching { uploadClient.probeReachability() }.getOrDefault(CoachConnectivityStatus.OFFLINE)
                        }
                        if (live == CoachConnectivityStatus.OFFLINE) {
                            keepOfflineMessage = true
                        } else {
                            updateCollectionSession(
                                col.id,
                                sessionForCollection(col.id).copy(
                                    loading = true,
                                    error = null,
                                    fallbackPages = emptyList(),
                                    lastQuestion = q,
                                ),
                            )
                        }
                    }
                }
                val askNodeId = currentSection?.id ?: currentChapterSpan?.tocNodeId ?: "p${ui.page}"
                val askName = currentSection?.title ?: currentChapterSpan?.title ?: "第 ${ui.page} 页"
                withContext(Dispatchers.IO) {
                    runCatching {
                        library.recordMasteryEvidence(
                            bookId = book.id,
                            tocNodeId = askNodeId,
                            page = ui.page,
                            canonicalName = askName,
                            signal = LearnerSignalType.SELECTION_ASK,
                        )
                    }
                }
                val res = CollectionQaEngine.ask(
                    library = library,
                    collectionId = col.id,
                    question = q,
                    currentBookId = book.id,
                    remoteAsk = { payload ->
                        if (!explainer.loaded) explainer.load()
                        explainer.askCollection(payload)
                    },
                )
                val latest = sessionForCollection(col.id)
                res.fold(
                    onSuccess = { turn ->
                        if (inlineDraft.trim() == q && (turn.sentences.isNotEmpty() || turn.conflictCards.isNotEmpty())) {
                            changeAskDraft("")
                        }
                        updateCollectionSession(
                            col.id,
                            latest.copy(
                                turns = latest.turns + turn,
                                loading = false,
                                error = null,
                                lastQuestion = null,
                            ),
                        )
                    },
                    onFailure = { err ->
                        val qaErr = err as? CollectionQaException
                        val unreachable = qaErr?.trouble == ExplainTrouble.Unreachable
                        val msg = when {
                            unreachable -> OfflineCopy.COLLECTION_ASK_OFFLINE
                            qaErr != null -> qaErr.message
                            keepOfflineMessage -> OfflineCopy.COLLECTION_ASK_OFFLINE
                            else -> err.message ?: "跨材料问答暂时不可用，请重试。"
                        }
                        val trouble = when {
                            unreachable || (keepOfflineMessage && qaErr == null) -> ExplainTrouble.Unreachable
                            else -> qaErr?.trouble ?: ExplainTrouble.Other
                        }
                        updateCollectionSession(
                            col.id,
                            latest.copy(
                                loading = false,
                                error = msg to trouble,
                                fallbackPages = qaErr?.fallbackPages.orEmpty(),
                            ),
                        )
                    },
                )
            }
        }
        val askTabContent: @Composable () -> Unit = {
            val isCollectionAsk = activeAskCollection != null && activeColSession.scope == CollectionAskScope.COLLECTION
            val currentPageBodyBlocks = remember(book, paragraphPage.number, readingAnchor, visibleScreenSlices) {
                selectScreenExplainBlocks(book, paragraphPage.number, readingAnchor.offsetUtf16, visibleScreenSlices)
            }
            val visibleThread = inlineThread?.takeIf { thread ->
                thread.askingOpen || visibleScreenSlices == null || visibleScreenSlices.any { range ->
                    thread.selection.page == paragraphPage.number && thread.selection.start in range
                }
            }
            CompanionAskPane(
                book = book,
                allBlocks = allBookBlocks,
                pageBlocks = currentPageBodyBlocks,
                activeSelection = learnableSelection,
                currentSection = currentSection,
                thread = visibleThread,
                draft = inlineDraft,
                compact = effectiveBottomSheet,
                onDraftChange = { changeAskDraft(it) },
                onPickPassage = { picked ->
                    val secIdx = guideController.sectionIndexForAnchor(picked.page, picked.start).takeIf { it >= 0 } ?: currentSectionIndex
                    val secCtx = guideController.contextForSection(secIdx)
                    inlineThread = InlineExplainThread(picked, secCtx, askingOpen = true)
                    if (inlineDraft.isNotBlank()) changeAskDraft(inlineDraft)
                },
                onSubmitQuestion = {
                    if (isCollectionAsk) {
                        submitCollectionQuestion(inlineDraft)
                    } else {
                        val q = inlineDraft.trim()
                        val target = visibleThread?.selection ?: learnableSelection
                            ?: currentPageBodyBlocks.firstOrNull()
                        if (target != null && BookPassageQuestion.accepts(q)) {
                            runInlineSelection(target, SelectionExplainAction.ASK, q,
                                currentPageBodyBlocks.takeIf { visibleThread == null && learnableSelection == null })
                        }
                    }
                },
                onRetry = {
                    val target = visibleThread?.selection ?: return@CompanionAskPane
                    val interrupted = visibleThread.retryTurn
                    if (interrupted != null) {
                        runInlineSelection(target, SelectionExplainAction.ASK, interrupted.question, retry = interrupted)
                        return@CompanionAskPane
                    }
                    val (act, q) = lastInlineAction ?: (SelectionExplainAction.EXPLAIN to null)
                    runInlineSelection(target, act, q)
                },
                onOpenLogin = {
                    if (onRelogin != null) onRelogin("授权已撤销，请重新登录。")
                    else companionTab = ReaderCompanionTab.EXPLAIN
                },
                onOpenCitation = { _, block -> handleCitationJump(BookPassage.from(book, block)) },
                onClearSelection = {
                    inlineThread = null
                    pdf.selection = null
                    textSelection = null
                    if (inlineDraft.isNotBlank()) changeAskDraft(inlineDraft)
                },
                onInputFocusChanged = { askInputFocused = it },
                collectionScopeLabel = activeAskCollection?.scopeToggleLabel,
                askScope = activeColSession.scope,
                onSelectAskScope = { nextScope ->
                    activeAskCollection?.let { col ->
                        updateCollectionSession(col.id, sessionForCollection(col.id).copy(scope = nextScope, error = null))
                        if (inlineDraft.isNotBlank()) changeAskDraft(inlineDraft, nextScope)
                    }
                },
                collectionTurns = activeColSession.turns,
                collectionLoading = activeColSession.loading,
                collectionError = activeColSession.error,
                collectionFallbackPages = activeColSession.fallbackPages,
                onRetryCollection = {
                    val q = activeColSession.lastQuestion ?: inlineDraft
                    submitCollectionQuestion(q)
                },
                onOpenCollectionCitation = { cit ->
                    if (cit.bookId == book.id) {
                        val pageObj = book.page(cit.page)
                        val passage = pageObj?.let {
                            SentenceCitationGate.locatePassageInPage(book.id, it, cit.quoteAnchor, cit.timestampLabel)
                        }
                        if (passage != null) openPassage(passage) else turnTo(cit.page)
                    } else if (onJumpCollectionCitation != null) {
                        onJumpCollectionCitation(cit, readingAnchor)
                    } else {
                        onOpenCrossBookPage?.invoke(cit.bookId, cit.page, cit.quoteAnchor, cit.startUtf16)
                    }
                },
            )
        }

        val notesTabContent: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).testTag("books-notes"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!session.noting && samePage && view != null) {
                        V7TextButton({
                            val sel = learnableSelection
                            if (sel != null) startNoteFromSelection(sel)
                            else session.beginNote(readingAnchor.offsetUtf16)
                        }, modifier = Modifier.testTag("books-note-new")) { Text("＋ 写批注") }
                    }
                    Spacer(Modifier.weight(1f))
                    V7TextButton({ notesPageOpen = true }, enabled = !ui.busy, modifier = Modifier.testTag("book-notes-open-tab")) {
                        Text("这本书的笔记")
                    }
                    if (onExportNotes != null) {
                        V7TextButton({ onExportNotes(book, ui.page) }, enabled = !ui.busy, modifier = Modifier.testTag("book-export-notes")) {
                            Text("导出笔记")
                        }
                    }
                }
                if (marks.isNotEmpty()) {
                    Text("本页高亮（${marks.size} 处）", style = MaterialTheme.typography.labelMedium, color = v7Pine())
                    HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
                }
                if (samePage && view != null) {
                    if (session.noteLine >= 0) {
                        Text("点选一句原文添加批注：", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                        session.pageLines().forEachIndexed { index, range ->
                            TextButton(
                                onClick = { session.chooseNoteLine(index) },
                                modifier = Modifier.fillMaxWidth().testTag("books-note-line-$index"),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (session.noteLine == index) highlight else Color.Transparent),
                            ) { Text(view.text.substring(range), modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                    NotesSection(session, remote, view, textColor = MaterialTheme.colorScheme.onSurface, keyboardHint = false)
                    if (session.noteDraft != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton({ session.requestNoteSave() }, enabled = !session.noteSaving, modifier = Modifier.testTag("books-note-save")) { Text("保存") }
                            V7TextButton({ session.cancelNote() }, modifier = Modifier.testTag("books-note-close")) { Text("取消") }
                        }
                    }
                } else {
                    Text("当前页暂无可批注文本。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                }
            }
        }

        val readerViewport: @Composable (Modifier) -> Unit = { viewportMod ->
            Column(viewportMod.padding(
                start = if (tocOpen) minOf(308.dp, (rootWidth * .85f).coerceAtLeast(240.dp)) else 0.dp,
                top = if (reserveHudSpace) 45.dp else 0.dp,
                bottom = if (reserveHudSpace && !effectiveBottomSheet) 45.dp else 0.dp,
            )) {
                activeWhisper?.let { whisper ->
                    MarginWhisperStrip(
                        whisper = whisper,
                        onExpandCompanion = {
                            openReadingCompanion()
                            companionTab = ReaderCompanionTab.EXPLAIN
                        },
                        onJumpAnchorPage = { target -> turnTo(target) },
                        onDismiss = {
                            proactivityPolicy.onUserDismiss()
                            val dismissedId = whisper.whisperId
                            activeWhisper = null
                            scope.launch(Dispatchers.IO) {
                                runCatching { library.dismissMarginWhisperAudit(dismissedId) }
                            }
                        },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    pageSurface(Modifier.fillMaxSize())
                }
                if (!companionOpen) {
                    activeCheckpoint?.let { cp ->
                        EndOfSectionCheckpointCard(
                            data = cp,
                            onAnswer = { quiz, chosen, isGuessed ->
                                val sec = book.lessons.firstOrNull { it.id == cp.sectionId } ?: currentSection
                                if (sec != null) {
                                    onSaveSectionQuiz?.invoke(
                                        sec,
                                        150 + cp.questions.indexOfFirst { it.id == quiz.id }.coerceAtLeast(0),
                                        Practice(quiz.stem, quiz.anchor?.quoteAnchor.orEmpty(), quiz.correctOption, quiz.options),
                                        PracticeAnswer(text = chosen, revealed = true, understood = chosen == quiz.correctOption, guessed = isGuessed),
                                    )
                                }
                            },
                            onDismiss = { activeCheckpoint = null },
                        )
                    }
                }
            }
        }

        // 1. 主体布局：
        // - Fold8 / Duo 半折：左书右伴读或上书下伴读（铰链零吃字）；
        // - 小窗与手机（<1000dp，含 winbox 752×552）：上书下伴读半屏抽屉，正文绝不归零；
        // - 宽屏（>=1280dp）：默认左书右伴读并排（正文零像素被遮挡）；
        // - 窄桌面（1000..1279dp）或手动切浮层：正文全铺 + 右侧浮层抽屉。
        if (verticalSplit != null) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(verticalSplit.leftDp.dp).fillMaxHeight().testTag("book-half-fold-left")) {
                    readerViewport(Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(verticalSplit.gapDp.dp).fillMaxHeight().testTag("book-half-fold-hinge"))
                Box(Modifier.weight(1f).fillMaxHeight().background(colors.surface1).testTag("book-half-fold-right")) {
                    ImmersiveCompanionDrawer(
                        visible = true,
                        docked = true,
                        canDock = false,
                        drawerWidth = rootWidth - verticalSplit.leftDp.dp - verticalSplit.gapDp.dp,
                        activeTab = companionTab,
                        onSelectTab = { companionTab = it },
                        onToggleDock = {},
                        onClose = {},
                        explainContent = explainTabContent,
                        askContent = askTabContent,
                        notesContent = notesTabContent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else if (tabletopSplit != null) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.height(tabletopSplit.topDp.dp).fillMaxWidth().testTag("book-tabletop-content")) {
                    readerViewport(Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(tabletopSplit.gapDp.dp).fillMaxWidth().testTag("book-tabletop-hinge"))
                Box(Modifier.weight(1f).fillMaxWidth().background(colors.surface1).testTag("book-tabletop-controls")) {
                    ImmersiveCompanionDrawer(
                        visible = true,
                        docked = true,
                        canDock = false,
                        bottomSheet = true,
                        bottomHeight = rootHeight - tabletopSplit.topDp.dp - tabletopSplit.gapDp.dp,
                        activeTab = companionTab,
                        onSelectTab = { companionTab = it },
                        onToggleDock = {},
                        onClose = {},
                        explainContent = explainTabContent,
                        askContent = askTabContent,
                        notesContent = notesTabContent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else if (effectiveBottomSheet) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    readerViewport(Modifier.fillMaxSize())
                }
                ImmersiveCompanionDrawer(
                    visible = true,
                    docked = true,
                    canDock = false,
                    bottomSheet = true,
                    bottomHeight = bottomDrawerHeight,
                    activeTab = companionTab,
                    onSelectTab = { companionTab = it },
                    onToggleDock = {},
                    onClose = { closeCompanion() },
                    explainContent = explainTabContent,
                    askContent = askTabContent,
                    notesContent = notesTabContent,
                )
            }
        } else if (effectiveSideDocked) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    readerViewport(Modifier.fillMaxSize())
                }
                ImmersiveCompanionDrawer(
                    visible = true,
                    docked = true,
                    canDock = false,
                    drawerWidth = sideDrawerWidth,
                    activeTab = companionTab,
                    onSelectTab = { companionTab = it },
                    onToggleDock = { companionDocked = false },
                    onClose = { closeCompanion() },
                    explainContent = explainTabContent,
                    askContent = askTabContent,
                    notesContent = notesTabContent,
                    modifier = Modifier.padding(top = if (showHud) 45.dp else 0.dp),
                )
            }
        } else {
            readerViewport(Modifier.fillMaxSize())
        }

        val pagedBodyStart = if (readMode.isWindowsPaged()) book.bodyStart else null
        val (currentSubPage, currentSubPageCount) = remember(
            textView, pageUnrecognizedOriginal, paging.pages, paging.anchor, paging.columns, pagedBodyStart,
        ) {
            if (!textView || pageUnrecognizedOriginal) 1 to 1
            else paging.pages.importPageScreenPosition(paging.anchor, paging.columns, pagedBodyStart)
        }
        val currentSubPageFraction = if (currentSubPageCount > 1) {
            (currentSubPage - 1).toFloat() / currentSubPageCount.toFloat()
        } else 0f
        val currentChapterRemainingScreens = remember(
            textView, pageUnrecognizedOriginal, navigationBook, effectiveChapterSpan, paging.pages, paging.anchor, paging.columns,
        ) {
            if (!textView || pageUnrecognizedOriginal || paging.pages.isEmpty() || effectiveChapterSpan == null) 0
            else {
                val activeIdx = paging.pages.pageContaining(paging.anchor)
                BookBoundaries.remainingChapterScreens(
                    book = navigationBook,
                    chapter = effectiveChapterSpan,
                    laidPages = paging.pages,
                    activeSheetIndex = activeIdx,
                    visibleColumns = paging.columns,
                )
            }
        }

        // 2. 底边 2dp 静默进度细线（HUD 隐藏且非底抽屉时可见，占面积 <0.2%）。
        if (!showHud && !effectiveBottomSheet) {
            ImmersiveProgressHairline(
                currentPage = readingAnchor.importPage,
                lastPage = lastPage,
                subPageFraction = currentSubPageFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 3. 选中文字浮出操作胶囊（高亮 / 讲讲 / 举个例子 / 问… / 笔记）。
        if (learnableSelection != null) {
            val selectedPdf = pdf.selection
            val highlighted = if (!textView && selectedPdf != null) {
                remember(library, book.id, selectedPdf, highlightRevision) {
                    val selectedPage = checkNotNull(book.page(selectedPdf.page))
                    selectedPdf.range in library.highlights(book.id, selectedPage)
                }
            } else false
            ImmersiveSelectionPill(
                showHighlight = !textView && selectedPdf != null,
                highlighted = highlighted,
                highlightBusy = savingMark,
                onHighlight = {
                    val sel = selectedPdf ?: return@ImmersiveSelectionPill
                    val passage = BookPassage.from(book, learnableSelection)
                    savingMark = true
                    markError = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { library.setHighlight(passage, !highlighted) }
                            highlightRevision++
                            paging.anchor = BookPageAnchor(learnableSelection.page, learnableSelection.start)
                            if (pdf.selection == sel) pdf.selection = null
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { markError = true }
                        finally { savingMark = false }
                    }
                },
                onAction = { action -> openSelectionAction(learnableSelection, action) },
                onNote = {
                    startNoteFromSelection(learnableSelection)
                },
                onDismiss = { pdf.selection = null; textSelection = null },
                onCompare = if (ConceptSayingLogic.canAskOtherBooks(learnableSelection.text)) {
                    { openOtherBooks(ConceptSayingLogic.matchConcept(learnableSelection.text, bookConceptNames)) }
                } else null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (showHud) 54.dp else 14.dp),
            )
        }

        // 4. 浮动书内查找面板（不挤压正文）。
        if (searchOpen) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 16.dp)
                    .widthIn(max = minOf(420.dp, maxWidth - 32.dp)),
            ) {
                BookSearchPanel(
                    book = book,
                    query = searchQuery,
                    result = searchResult,
                    activeIndex = searchActiveIndex,
                    focusRequester = searchFocus,
                    onQueryChange = { next -> searchQuery = next; searchActiveIndex = -1 },
                    onFocusChange = { searchFocused = it },
                    onPick = selectSearchHit,
                    onPrevious = {
                        val total = searchResult.hits.size
                        if (total > 0) selectSearchHit(if (searchActiveIndex <= 0) total - 1 else searchActiveIndex - 1)
                    },
                    onNext = {
                        val total = searchResult.hits.size
                        if (total > 0) selectSearchHit(if (searchActiveIndex !in 0 until total - 1) 0 else searchActiveIndex + 1)
                    },
                    onClose = { searchOpen = false; searchFocused = false },
                )
            }
        }

        // 5. 左侧浮动抽屉（目录 + 书签）。
        ImmersiveTocDrawer(
            visible = tocOpen,
            availableWidth = maxWidth,
            book = book,
            currentPage = ui.page,
            currentAnchor = readingAnchor,
            currentChapterId = effectiveChapterSpan?.tocNodeId,
            bodyStart = bodyStart,
            bodyStartCorrected = correctedBodyPage != null,
            bookmarks = bookmarks,
            busy = ui.busy || book.indexState != "READY" || book.hasPendingOcr,
            onJumpPage = { target ->
                pendingNavigationIsJump = true
                pendingDwellJump = true
                endOrHomeBrowseActive = false
                turnTo(target)
                tocOpen = false
            },
            onJumpToc = { target ->
                pendingNavigationIsJump = true
                pendingDwellJump = true
                endOrHomeBrowseActive = false
                explicitTocNodeId = target.id
                val cleanOffset = book.page(target.startPage)?.let {
                    BookBoundaries.snapToCleanBodyOffset(it.text, target.startOffset)
                } ?: target.startOffset
                turnToAnchor(target.startPage, cleanOffset)
                tocOpen = false
            },
            onEditChapter = if (onEditStructure == null) null else { edit -> onEditStructure(book, edit) },
            onSaveBodyStart = { corrected ->
                val updated = if (onEditStructure != null) onEditStructure(book, StructureEdit.BodyStart(corrected))
                    else withContext(Dispatchers.IO) {
                        library.saveBodyStart(book.id, corrected)
                        library.book(book.id)
                    }
                correctedBodyPage = corrected
                val start = updated.bodyStart
                pendingNavigationIsJump = true
                pendingDwellJump = true
                turnToAnchor(start.importPage, start.offsetUtf16, forceCommit = true)
            },
            onToggleBookmark = { toggleBookmark(it) },
            onReorganizeSections = onReorganizeSections,
            onClose = { tocOpen = false },
            readChapterIds = chapterReadStates,
            activeTocNodeId = explicitTocNodeId ?: activeTocNode?.id,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    top = if (showHud) 45.dp else 0.dp,
                    bottom = when {
                        effectiveBottomSheet -> bottomDrawerHeight
                        showHud -> 45.dp
                        else -> 0.dp
                    },
                ),
        )

        // 6. 右侧浮层伴读抽屉（窄桌面 1000..1279dp 或宽屏手动切换浮层时渲染）。
        if (effectiveSideOverlay) {
            ImmersiveCompanionDrawer(
                visible = true,
                docked = false,
                canDock = canDockCompanion,
                drawerWidth = sideDrawerWidth,
                activeTab = companionTab,
                onSelectTab = { companionTab = it },
                onToggleDock = { companionDocked = true },
                onClose = { closeCompanion() },
                explainContent = explainTabContent,
                askContent = askTabContent,
                notesContent = notesTabContent,
                modifier = Modifier.align(Alignment.CenterEnd).padding(top = if (showHud) 45.dp else 0.dp),
            )
        }

        // 7. 顶部浮动工具栏（44dp，默认隐藏，悬停/Tab 呼出）。
        val frontMatterTitle = remember(navigationBook, readingAnchor, activeTocNode, textView,
            pageUnrecognizedOriginal, paging.pages, paging.columns) {
            if (activeTocNode != null) return@remember null
            // An image-only cover may share a spread with the opening notice on the right.
            val headerAnchor = if (textView && !pageUnrecognizedOriginal && book.page(readingAnchor.importPage)?.text.isNullOrBlank()) {
                val first = paging.index
                (first until first + paging.columns).asSequence()
                    .flatMap { paging.pages.getOrNull(it)?.sources.orEmpty().asSequence() }
                    .firstOrNull { it.endUtf16 > it.startUtf16 }
                    ?.let { BookPageAnchor(it.importPage, it.startUtf16) } ?: readingAnchor
            } else readingAnchor
            BookBoundaries.unlistedFrontMatterTitle(navigationBook, headerAnchor)
        }
        ImmersiveTopHud(
            visible = showHud,
            availableWidth = maxWidth,
            bookTitle = book.title,
            sectionTitle = if (source != null) "出处 · ${source.title}" else
                activeTocNode?.let { BookBoundaries.headerTitleForNode(navigationBook, it) }
                    ?: frontMatterTitle ?: effectiveChapterSpan?.title ?: currentSection?.title,
            backLabel = when {
                ui.canReturnToCitationOrigin -> "返回引用前的位置"
                source != null && ui.review != null -> "返回复习"
                source != null -> "返回学习"
                else -> "书架"
            },
            hasOriginal = hasOriginal,
            textView = textView,
            tocOpen = tocOpen,
            companionOpen = companionOpen,
            searchOpen = searchOpen,
            bookmarked = bookmarks.any { it.page == ui.page },
            pinnedHud = hudPinned,
            busy = ui.busy,
            showLearnAction = false,
            learnLabel = "",
            onBack = onBack,
            onToggleToc = { tocOpen = !tocOpen },
            onSelectView = { switchView(it) },
            onToggleSearch = {
                if (searchOpen) { searchOpen = false; searchFocused = false }
                else { searchOpen = true; searchFocusTick++ }
            },
            onToggleBookmark = { toggleBookmark(ui.page) },
            onToggleCompanion = {
                if (companionOpen) closeCompanion() else openReadingCompanion()
            },
            onTogglePinHud = {
                hudPinned = !hudPinned
                if (hudPinned) hudVisible = true
            },
            onLearn = onLearn,
            onOpenBookNotes = { notesPageOpen = true },
            typeControl = {
                val control = LocalDeskTypeControl.current
                if (control != null) control(typeMenuExtra)
            },
            modeControl = if (source == null) {
                {
                    StudyModeSelectorButton(
                        preference = studyPreference,
                        noun = "伴读",
                        onUpdatePreference = { nextPref, modeSwitched ->
                            studyPreference = StudyModeStore.save(library, nextPref)
                            if (nextPref.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) {
                                activeWhisper = null
                                activeCheckpoint = null
                                closeCompanion()
                            } else if (modeSwitched) {
                                openReadingCompanion()
                                companionTab = ReaderCompanionTab.EXPLAIN
                            }
                        },
                        compact = true,
                        tag = "immersive-mode-trigger",
                    )
                }
            } else null,
            onMoreMenuDismiss = { if (searchOpen) searchFocusTick++ else pageFocusTick++ },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 8. 底部浮动进度与跳页栏（44dp，默认隐藏，悬停/Tab/Ctrl+G 呼出）。
        val visibleImportRange = visibleImportPageRange()
        val canPrev = if (textView && !pageUnrecognizedOriginal) paging.canPrevious || book.pages.any { it.number < visibleImportRange.first }
            else !ui.busy && previousPage != null
        val canNext = if (textView && !pageUnrecognizedOriginal) paging.canNext || book.pages.any { it.number > visibleImportRange.last }
            else !ui.busy && nextPage != null
        ImmersiveBottomScrubber(
            visible = showHud && !effectiveBottomSheet,
            availableWidth = maxWidth,
            currentPage = readingAnchor.importPage,
            lastPage = lastPage,
            pageStatus = { preview ->
                val isCurrent = preview == readingAnchor.importPage
                val chapter = if (isCurrent) effectiveChapterSpan
                    else BookBoundaries.effectiveChapterForFooter(navigationBook, preview)
                val remScreens = if (isCurrent) {
                    currentChapterRemainingScreens
                } else if (textView && !pageUnrecognizedOriginal && paging.pages.isNotEmpty() && chapter != null && preview == chapter.lastPage) {
                    val previewIdx = paging.pages.pageContaining(BookPageAnchor(preview, 0))
                    BookBoundaries.remainingChapterScreens(navigationBook, chapter, paging.pages, previewIdx, paging.columns)
                } else 0
                BookBoundaries.pageStatus(
                    page = preview,
                    lastPage = lastPage,
                    chapter = chapter,
                    subPage = if (isCurrent) currentSubPage else 1,
                    subPageCount = if (isCurrent) currentSubPageCount else 1,
                    chapterRemainingScreens = remScreens,
                )
            },
            canPrev = canPrev,
            canNext = canNext,
            editingJump = editingJump,
            jumpSession = jumpSession,
            jumpText = jump,
            onJumpTextChange = { jump = it },
            onStartEditJump = {
                editingJump = true
                jumpSession++
                jump = readingAnchor.importPage.toString()
            },
            onCancelEditJump = { editingJump = false; jumpFocused = false; focusManager.clearFocus(); pageFocusTick++ },
            onJumpFocusChange = { jumpFocused = it },
            onSubmitJump = { target ->
                if (book.page(target) != null) {
                    pendingNavigationIsJump = true
                    pendingDwellJump = true
                    endOrHomeBrowseActive = false
                    turnTo(target)
                    editingJump = false
                    jumpFocused = false
                    focusManager.clearFocus()
                    pageFocusTick++
                }
            },
            onPrev = { turnLayout(ReaderTurn.Previous) },
            onNext = { turnLayout(ReaderTurn.Next) },
            onScrubToPage = { target ->
                pendingNavigationIsJump = true
                pendingDwellJump = true
                endOrHomeBrowseActive = false
                turnTo(target)
            },
            subPageFraction = currentSubPageFraction,
            modifier = Modifier.align(Alignment.BottomStart).then(if (effectiveSideDocked) Modifier.width(rootWidth - sideDrawerWidth) else Modifier.fillMaxWidth()),
        )

        // 9. 轻提示（后台导入进度 / 出处警告），仅异常或进行中时浮在顶栏下方。
        val noticeText = sourceWarning
            ?: (if (viewSaveError) "视图设置暂未保存，下次切换将重试。" else null)
            ?: (if (markError) "高亮暂未保存，请重试。" else null)
            ?: (if (chosen != null && learnableSelection == null) "选段超过 4000 字，请缩窄范围后再问。" else null)
        if (noticeText != null) {
            Text(
                noticeText,
                style = MaterialTheme.typography.labelMedium,
                color = v7Alert(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (showHud) 52.dp else 12.dp)
                    .clip(RoundedCornerShape(CoachV7.Radius.btn))
                    .background(colors.surface1.copy(alpha = 0.95f))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.btn))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("source-anchor-status"),
            )
        }

        if (notesPageOpen) {
            BookNotesPageOverlay(
                bookId = book.id,
                bookTitle = book.title,
                library = library,
                lessonFirstPages = {
                    book.lessons.associate { lesson -> lesson.id to lesson.firstPage }
                },
                onBack = { notesPageOpen = false },
                onJump = { target ->
                    notesPageOpen = false
                    if (book.page(target) != null) {
                        pendingNavigationIsJump = true
                        pendingDwellJump = true
                        endOrHomeBrowseActive = false
                        turnTo(target)
                    }
                },
            )
        }
    }
}

/** 键盘翻页的去向。 */
internal enum class ReaderTurn { Previous, Next, First, Last }

/**
 * 阅读区认的翻页键：←/PageUp 上一页，→/PageDown 下一页，Home 首页，End 末页。
 * 正在输入文字时一个都不接——方向键和 Home/End 归光标用。
 */
internal fun readerPagingTurn(key: Key, typing: Boolean): ReaderTurn? {
    if (typing) return null
    return when (key) {
        Key.DirectionLeft, Key.PageUp -> ReaderTurn.Previous
        Key.DirectionRight, Key.PageDown -> ReaderTurn.Next
        Key.MoveHome -> ReaderTurn.First
        Key.MoveEnd -> ReaderTurn.Last
        else -> null
    }
}

/** 焦点是否落在会吃掉方向键的文字输入里：选段提问、页码框、打开着的批注编辑区。 */
internal fun readerTyping(asking: Boolean, jumpFocused: Boolean, notesOpen: Boolean, noting: Boolean): Boolean =
    asking || jumpFocused || (notesOpen && noting)

/**
 * 页图还没到时的纸面宽高比：先用这一页自己的几何（文字的框），这一页还没量到才借同一本书
 * 上一张成功页图的比例，都没有就按 A4（1:√2）铺。三种都是纸，图到了只换内容不换框。
 * 顺序不能反：上一页的比例是另一张纸，横竖混排的书照它铺，图一到就会塌掉一截。
 */
internal fun readerPaperAspect(boxesWidth: Double?, boxesHeight: Double?, remembered: Float?): Float =
    boxesWidth?.let { w -> boxesHeight?.takeIf { it > 0 }?.let { h -> (w / h).toFloat() } }
        ?: remembered
        ?: (1f / 1.4142f)

/**
 * 这一页的「几何」是不是真量出来的：只有 pdf 的非 OCR 页给的是本页 cropBox，
 * 图片书和 OCR 页那份 1200x1600 是按经验补的常数，照它铺纸会和真图差一截。
 */
internal fun readerBoxesAreGeometry(format: String, ocr: Boolean): Boolean = format == "pdf" && !ocr

/** 一行 Markdown 标题：[marks] 是井号连同后面的空白，[title] 是标题文字；都是原文里的字符下标。 */
internal data class MarkdownHeading(val level: Int, val marks: IntRange, val title: IntRange)

private val markdownHeadingLine = Regex("(?m)^(#{1,6})[ \\t]+(\\S.*?)[ \\t\\r]*$")

internal fun markdownHeadings(text: String): List<MarkdownHeading> = markdownHeadingLine.findAll(text).mapNotNull { m ->
    if (BookBoundaries.isBogusMarkdownHeadingLine(m.value)) return@mapNotNull null
    val hashes = m.groups[1]!!.range
    val title = m.groups[2]!!.range
    MarkdownHeading(hashes.last - hashes.first + 1, hashes.first until title.first, title)
}.toList()

/**
 * 阅读区的滚动条，直接用共享的 `v7/V7Scrollbar`：固定 12dp 的槽，槽里 4dp 细条悬停加粗到 8dp，
 * 加粗不挪正文。内容不满一屏时整条不占位，跟清单 W5 说的一致。
 */
@Composable
private fun ReaderScrollbar(scroll: ScrollState, modifier: Modifier = Modifier) {
    if (scroll.maxValue <= 0) return
    BookScrollbar(scroll, modifier)
}

/** 待办的阅读器焦点请求：发起时的书与出处。 */
internal class ReaderFocusTicket(val bookId: String?, val source: BookLesson?)

/** 焦点只能还给发起请求时的那本书、那个出处的阅读屏；退出、切书、换出处、弹窗出现后旧请求一律作废。 */
internal fun readerFocusStillOwned(startBookId: String?, startSource: BookLesson?, current: LibraryUi): Boolean =
    current.screen == BookScreen.READER && current.source != null && current.prompt == null &&
        current.book?.id == startBookId && current.source == startSource

/**
 * 翻页之后等阅读器丢掉焦点（最多 [timeoutMs]），再核对发起时的书和出处仍是当前的，才发焦点票。
 * 抽成独立函数是为了能用受控的延迟和状态变化单独验证"旧请求晚到"的各种情形。
 */
internal suspend fun awaitReaderFocusRestore(
    startBookId: String?, startSource: BookLesson?,
    hasFocus: () -> Boolean, current: () -> LibraryUi,
    timeoutMs: Long = 1_000, issue: (ReaderFocusTicket) -> Unit,
) {
    val lostFocus = withTimeoutOrNull(timeoutMs) { snapshotFlow { !hasFocus() }.first { it } } == true
    if (lostFocus && !hasFocus() && readerFocusStillOwned(startBookId, startSource, current())) issue(ReaderFocusTicket(startBookId, startSource))
}

/**
 * 消费焦点票：票和界面状态可能在同一个 UI 回合里一起变，所以真正请求焦点前读的是"最新"的界面状态，
 * 而不是效果启动时捕获的那一份；票上的书和出处不再是当前阅读屏时不请求。
 */
@Composable
internal fun ReaderFocusTicketEffect(ticket: ReaderFocusTicket?, ui: LibraryUi, requestFocus: () -> Unit) {
    val latestUi by rememberUpdatedState(ui)
    LaunchedEffect(ticket) {
        val t = ticket ?: return@LaunchedEffect
        if (readerFocusStillOwned(t.bookId, t.source, latestUi)) requestFocus()
    }
}
