package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.ui.adaptive.LayoutRules
import com.coach.ui.adaptive.NavStyle
import com.coach.ui.adaptive.WindowSpec
import com.coach.client.KpRow
import com.coach.client.SyncEngine
import com.coach.client.TutorClient
import com.coach.client.TutorUiState
import com.coach.ui.markdown.MathAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 界面依赖的一切外部能力，由各端（桌面/安卓）装配。 */
class CoachController(
    val courseId: String,
    val sync: SyncEngine,
    val tutor: TutorClient,
    /** 某个知识点的备课包（教材页文字，≤40K 字）；没有就返回空串。 */
    val packFor: (kpId: String) -> String = { "" },
    /** epoch 毫秒 → "hh:mm"。 */
    val formatClock: (Long) -> String,
    val math: MathAssets = MathAssets(),
    /** 当前时刻（epoch 毫秒），决定「今天」的复习到期与考试倒计时；测试可注入。 */
    val nowMillis: () -> Long = { @OptIn(kotlin.time.ExperimentalTime::class) kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** 只读的课程查询（原题评分标准、教材原文、构答题库存）；没有（比如测试）就是 null，相关界面如实说「取不到」。 */
    val course: com.coach.client.CourseHttp? = null,
) {
    // Keep arrival anchors as long as the tutor model, including a trip back to Today.
    internal val quizMessageAnchors = HashMap<String, MutableMap<String, Int>>()
    /** 看过（或预取过）的教材页，整个会话里留着：学习页的「看原文」多半不用再等 Mac。 */
    internal val sourcePages = HashMap<PageKey, com.coach.client.SourcePage>()
}

/** 公式 SVG 缓存。缺失的公式显示源码 chip，同时请求 [fetch]；取到后重组。 */
class MathAssets(private val fetch: (suspend (tex: String, display: Boolean) -> MathAsset?)? = null) {
    val assets = mutableStateMapOf<String, MathAsset>()
    /** Formulas the server could not draw (bad TeX, service down). Distinct from "not fetched yet": the reader gets an explanation, not a spinner-less chip. */
    val failed = mutableStateMapOf<String, Boolean>()
    private val asked = HashSet<String>()
    suspend fun request(key: String) {
        val loader = fetch ?: return
        if (key in assets || !asked.add(key)) return
        val display = key.substringBefore(':') == "true"
        val tex = key.substringAfter(':')
        // 请求方的 LaunchedEffect 会因别的公式到货而重启；已发出的请求必须跑完，否则这个公式永远停在源码 chip。
        withContext(NonCancellable) {
            val asset = runCatching { loader(tex, display) }.getOrNull()
            // Parse the SVG on a worker before the formula becomes visible, so drawing it later does not parse inside a frame.
            if (asset != null) { com.coach.ui.math.MathSvgDocument.prewarm(listOf(asset.sha to asset.svg)); assets[key] = asset } else failed[key] = true
        }
    }
}

/** 公式从服务端 `/v1/math` 取（手机没有 Node）；取不到的公式显示源码 chip。 */
fun MathAssets(http: com.coach.client.MathHttp) = MathAssets { tex, display ->
    http.render(tex, display)?.let { MathAsset(tex, display, it.sha, it.svg) }
}

fun threadIdFor(kpId: String) = "kp:$kpId"

/** 两栏布局的起点宽度（Material 的 medium/expanded 分界附近）：Fold8 内屏走两栏，外屏走单栏。 */
val WIDE_LAYOUT_MIN_WIDTH = 640.dp

/** 「看原文」左右并排（左讲解、右原文）的起点宽度：每栏至少约 420dp。Pad 横屏够，Fold8 内屏不够，那里原文盖在讲解上面。 */
val SOURCE_SPLIT_MIN_WIDTH = 840.dp

/** 底栏/侧轨的五个入口。「学习」不是入口：它是从「继续」或某个知识点打开的线程。 */
enum class AppTab(val label: String, val glyph: String) {
    TODAY("今天", "◉"), ROUTE("路线", "☰"), REVIEW("复习", "↻"), RECORDS("记录", "✓"), RESOURCES("资料", "❖"),
}

/** [initialSource] 同样只用于验收：打开知识点后直接展开「看原文」（桌面截图用）。[initialDraft] 只用于验收：把一句话预填进输入框（`adb` 敲不了中文），不会自动发送。 */
@Composable
fun CoachApp(c: CoachController, initialKp: String? = null, initialAutoStart: Boolean = false, dark: Boolean? = null, initialDraft: String? = null, initialTab: AppTab = AppTab.TODAY, initialSource: Boolean = false) {
    val ui by c.tutor.model.state.collectAsState()
    val revision by c.sync.revision.collectAsState()
    val view by produceState<CourseView?>(null, revision) {
        value = withContext(Dispatchers.Default) {
            val events = c.sync.localEvents(c.courseId)
            if (events.isEmpty() && revision == 0L) null else CourseView(events, c.sync.projection(c.courseId))
        }
    }
    var open by remember { mutableStateOf(initialKp?.let { it to initialAutoStart }) }
    var tab by remember { mutableStateOf(initialTab) }
    var source by remember { mutableStateOf<SourceJump?>(null) }
    val openKp: (String, Boolean) -> Unit = { id, start -> c.tutor.telemetry?.record("tap", "open_kp"); source = null; open = id to start }
    val openReview: () -> Unit = { c.tutor.telemetry?.record("tap", "review"); tab = AppTab.REVIEW; open = null; source = null }
    val select: (AppTab) -> Unit = { tab = it; open = null; source = null }
    val focus = LocalFocusManager.current
    // 打开的知识点有教材锚点：先把那一页取到本机，「看原文」点下去时通常已经在手里。
    val anchorOfOpen = open?.let { (id, _) -> view?.kp(id) }?.takeIf { it.anchorDoc.isNotBlank() }
    LaunchedEffect(anchorOfOpen?.id, ui.connected) {
        if (anchorOfOpen != null && ui.connected) runCatching { prefetchSourcePage(c, anchorOfOpen.anchorDoc, anchorOfOpen.anchorPdfPage) }
    }
    // Closed telemetry vocabulary (TON-1140): only the screens it already names are reported.
    LaunchedEffect(open, tab, source) {
        val screen = when { open != null -> "study"; source != null -> null; tab == AppTab.TODAY -> "today"; tab == AppTab.REVIEW -> "review"; else -> null }
        if (screen != null) c.tutor.telemetry?.record("screen", screen)
    }

    CoachTheme(dark ?: isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                OfflineBanner(ui, c)
                val current = view
                val target = open?.let { (id, _) -> current?.kp(id) }
                // The panes are movable: when the window changes shape (fold/unfold, Pad rotation) a pane that moves between the
                // single-column and two-column layouts keeps its composition: reading position, draft, chosen answers, running stream.
                val studyPane = remember(c) { movableContentOf<StudyPaneArgs> { args ->
                    key(args.kp.id) { StudyScreen(c, args.kp, args.view, args.ui, args.autoStart, args.onBack, initialDraft, args.onOpenReference) }
                } }
                val openReference: (KpRow) -> Unit = { kp -> if (kp.anchorDoc.isNotBlank()) { focus.clearFocus(); source = SourceJump(kp.anchorDoc, kp.anchorPdfPage) } }
                var sourceShown by remember { mutableStateOf(false) }
                LaunchedEffect(target?.id) { if (initialSource && !sourceShown && target != null) { sourceShown = true; openReference(target) } }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val layout = LayoutRules.compute(WindowSpec(maxWidth.value, maxHeight.value))
                    val bottomBar = layout.nav == NavStyle.BottomBar
                    val contentWidth = maxWidth - layout.navWidthDp.dp
                    val wide = contentWidth >= WIDE_LAYOUT_MIN_WIDTH
                    LaunchedEffect(wide) { c.tutor.telemetry?.record("screen", if (wide) "wide" else "compact") }
                    // Records and Resources use the whole width (Records has its own list | detail); the other tabs pair a list with the study thread.
                    val paired = tab == AppTab.TODAY || tab == AppTab.ROUTE || tab == AppTab.REVIEW
                    val body: @Composable () -> Unit = {
                        val tabContent: @Composable () -> Unit = {
                            when (tab) {
                                AppTab.TODAY -> TodayScreen(c, current, ui, selectedKpId = if (wide) target?.id else null, onOpenReview = openReview, onOpen = openKp)
                                AppTab.ROUTE -> RouteScreen(c, current, if (wide) target?.id else null, openKp)
                                AppTab.REVIEW -> if (current != null) ReviewScreen(c, current, onOpenKp = openKp, onBack = null) else CenterMessage("正在加载课程…")
                                AppTab.RECORDS -> RecordsScreen(c, current, wide) { id -> openKp(id, false) }
                                AppTab.RESOURCES -> ResourcesScreen(c, null, null)
                            }
                        }
                        val reading = source
                        // Pad 横屏：左讲解、右原文并排（列表栏让出位置）；窄一点的窗口原文盖在讲解上面，讲解一直留在下面，返回后进度原样。
                        val split = reading != null && wide && paired && target != null && current != null && contentWidth >= SOURCE_SPLIT_MIN_WIDTH
                        val paneArgs = target?.let { t -> current?.let { v -> StudyPaneArgs(t, v, ui, open?.second == true, if (wide && paired) null else ({ open = null }), openReference) } }
                        if (split) SourceSplit(
                            study = { studyPane(paneArgs!!) },
                            source = { ResourcesScreen(c, reading, onBack = { source = null }, backLabel = "✕ 关闭原文") },
                        ) else SourceOverlay(
                            covered = reading != null,
                            base = {
                                when {
                                    wide && paired -> Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.width((contentWidth * 0.38f).coerceIn(300.dp, 400.dp)).fillMaxHeight()) { tabContent() }
                                        VerticalDivider()
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            if (paneArgs != null) studyPane(paneArgs) else CenterMessage("选一个知识点，或点「继续」。")
                                        }
                                    }
                                    paneArgs != null -> studyPane(paneArgs)
                                    else -> tabContent()
                                }
                            },
                            overlay = { ResourcesScreen(c, reading, onBack = { source = null }) },
                        )
                    }
                    // Wide windows keep the nav on the side; the single-column phone layout hides it while a thread or page is open.
                    val navVisible = !(bottomBar && (open != null || source != null))
                    if (bottomBar) Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
                        if (navVisible) AppNavBar(tab, select)
                    } else Row(Modifier.fillMaxSize()) {
                        AppNavRail(tab, layout.nav == NavStyle.IconRail, select)
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) { body() }
                    }
                }
            }
        }
    }
}

/** Pad 横屏：左讲解、右原文并排。讲解窗格是同一个 movable 内容，从单栏/两栏布局挪过来时保留它的组合状态。 */
@Composable
internal fun SourceSplit(study: @Composable () -> Unit, source: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { study() }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { source() }
    }
}

/**
 * 原文盖在页面上面（手机、Fold8 内屏）：下面的页面 [base] 一直留在组合里，输入、选项、滚动位置原样；
 * 盖住时它不接收点击，也不对读屏可见。
 */
@Composable
internal fun SourceOverlay(covered: Boolean, base: @Composable () -> Unit, overlay: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)) { base() }
        if (covered) Surface(Modifier.fillMaxSize().pointerInput(Unit) {}) { overlay() }
    }
}

@Composable
private fun AppNavBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        AppTab.entries.forEach { t ->
            NavigationBarItem(selected = t == selected, onClick = { onSelect(t) }, icon = { Text(t.glyph, style = MaterialTheme.typography.titleMedium) }, label = { Text(t.label) })
        }
    }
}

@Composable
private fun AppNavRail(selected: AppTab, compact: Boolean, onSelect: (AppTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxHeight()) {
        Spacer(Modifier.weight(1f))
        AppTab.entries.forEach { t ->
            NavigationRailItem(selected = t == selected, onClick = { onSelect(t) }, icon = { Text(t.glyph, style = MaterialTheme.typography.titleMedium) }, label = if (compact) null else ({ Text(t.label) }), alwaysShowLabel = !compact)
        }
        Spacer(Modifier.weight(1f))
    }
}

private data class StudyPaneArgs(val kp: KpRow, val view: CourseView, val ui: TutorUiState, val autoStart: Boolean, val onBack: (() -> Unit)?, val onOpenReference: ((KpRow) -> Unit)?)

@Composable
private fun OfflineBanner(ui: TutorUiState, c: CoachController) {
    if (ui.connected) return
    val last = ui.lastSeenAtMs
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (last == null) "Mac 未连接" else "Mac 未连接（最后同步 ${c.formatClock(last)}）",
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 标签底色 = 字色的这么多分之几：太深会把字色压成低对比，由 StudyChromeTest 守住 ≥4.5。 */
internal const val CHIP_TINT = .12f

@Composable
internal fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = CHIP_TINT), shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** 整屏居中的一句说明（未配对、缺课程号这类进不了主界面的情况）。 */
@Composable
fun CoachNotice(text: String) = Surface(Modifier.fillMaxSize()) { CenterMessage(text) }

@Composable
internal fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
