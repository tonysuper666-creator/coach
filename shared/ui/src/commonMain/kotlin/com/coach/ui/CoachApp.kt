package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import com.coach.ui.v7.paperShadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.coach.domain.flag
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coach.client.CourseView
import com.coach.ui.adaptive.LayoutRules
import com.coach.ui.adaptive.NavStyle
import com.coach.ui.adaptive.WindowSpec
import com.coach.client.KpRow
import com.coach.client.SyncEngine
import com.coach.client.TutorClient
import com.coach.client.TutorUiState
import com.coach.domain.json
import com.coach.protocol.CommandBody
import com.coach.ui.markdown.MathAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** 论述限时练习的时限（秒）。默认 25 分钟；验收演示可以配短。 */
    val essayLimitSec: Int = com.coach.domain.Essay.TIME_LIMIT_SEC,
    /** 论述的截止计时和交卷送出，不随页面走。必须跑在界面线程上：交卷和打字改的是同一份草稿。测试可注入虚拟时钟。 */
    private val essayClock: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // Keep arrival anchors as long as the tutor model, including a trip back to Today.
    internal val quizMessageAnchors = HashMap<String, MutableMap<String, Int>>()
    /** 看过（或预取过）的教材页，整个会话里留着：学习页的「看原文」多半不用再等 Mac。 */
    internal val sourcePages = HashMap<PageKey, com.coach.client.SourcePage>()
    internal var readerFocus by mutableStateOf(false)
    /** 期末面板点「修复 / 开讲 / 开练」留下的一次性启动（kpId → 是否出题）：学习页等历史加载完取走、发一次，重连不会再发。 */
    internal val startRequests = mutableStateMapOf<String, Boolean>()
    /** 模拟考里还没存到 Mac 的作答（按 mockId）：离页、断线都留着，回来续考先用它，存上了才删。 */
    internal val mockDrafts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, kotlinx.serialization.json.JsonObject>>(
        sync.mainlineState(courseId, "mock-drafts").mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }.toMap())
    internal fun persistMockDrafts() { sync.saveMainlineState(courseId, JsonObject(mockDrafts.value), "mock-drafts") }
    /** 模拟考的保存和续考时读卷都排这一把锁：同一时刻只有一个请求在路上，旧答案不会后到覆盖新答案，续考读到的也是最后存上的。 */
    internal val mockSaving = kotlinx.coroutines.sync.Mutex()
    /** 每场考试最后发出的保存序号，只增不减（锁内读写）。 */
    internal val mockSeq = HashMap<String, Long>()
    internal val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    /** 正在写的那篇论述。放在这里：离开练习页再回来，倒计时照走，草稿还在。 */
    internal var essay by mutableStateOf<EssaySession?>(null)
        private set

    init {
        val saved = sync.mainlineState(courseId, "essay-draft")
        val posed = saved["posed"] as? JsonObject
        val attempt = (saved["attempt"] as? JsonPrimitive)?.content.orEmpty()
        val deadline = (saved["deadline"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (posed != null && attempt.isNotEmpty() && deadline != null) startEssay(EssaySession(attempt, posed, deadline).also {
            it.text = (saved["text"] as? JsonPrimitive)?.content.orEmpty()
            it.handedIn = saved.flag("handedIn")
        })
    }

    internal fun persistEssay() {
        val s = essay
        sync.saveMainlineState(courseId, if (s == null) com.coach.domain.json() else com.coach.domain.json(
            "attempt" to s.attemptId, "posed" to s.posed, "deadline" to s.deadline, "text" to s.text, "handedIn" to s.handedIn), "essay-draft")
    }

    /** 题目到手，开始计时。截止由这里盯着，不看练习页在不在屏幕上：回复习页、切到别的页，到点照样交卷。 */
    internal fun startEssay(s: EssaySession) {
        essay = s
        persistEssay()
        essayClock.launch {
            while (essay === s && !s.handedIn) {
                val left = s.deadline - nowMillis()
                if (left <= 0) { handInEssay(s); break }
                // 每秒对一次墙钟：手机休眠时 delay 不走，醒来后一秒内补交。
                delay(left.coerceAtMost(1000))
            }
        }
    }

    /** 交卷：只交一次。草稿就此定稿，命令先落进本机队列再送 Mac，断网也不丢。 */
    internal fun handInEssay(s: EssaySession) {
        if (s.handedIn || essay !== s) return
        if (s.text.isBlank()) { essay = null; persistEssay(); return }
        tutor.telemetry?.record("tap", "essay_submit")
        val cmd = sync.enqueue(CommandBody("submit_answer", courseId, nowMillis(),
            json("attemptId" to s.attemptId, "answer" to s.text, "posedSnapshot" to s.posed, "threadId" to "essay:${s.attemptId}")))
        s.handedIn = true
        persistEssay()
        essayClock.launch {
            try {
                withContext(io) { sync.syncOnce() }
                val receipt = sync.receipt(cmd.commandId)
                if (receipt == null) s.note = "Mac 未连接：已交卷，连上后自动送去批改。"
                else if (receipt.status !in setOf("OK", "DUPLICATE")) s.note = "Mac 没有收下这篇（${receipt.status}）。原文还在下面，可以复制保存。"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { s.note = "Mac 未连接：已交卷，连上后自动送去批改。" }
        }
    }

    internal fun closeEssay() { essay = null; persistEssay() }
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
@kotlinx.serialization.Serializable
enum class AppTab(val label: String, val glyph: String? = null) {
    TODAY("今天", "◉"), ROUTE("路线", "☰"), REVIEW("复习", "↻"), RECORDS("记录", "✓"), RESOURCES("资料"),
}

/** [initialSource] 同样只用于验收：打开知识点后直接展开「看原文」（桌面截图用）。[initialDraft] 只用于验收：把一句话预填进输入框（`adb` 敲不了中文），不会自动发送。 */
@Composable
fun CoachApp(c: CoachController, initialKp: String? = null, initialAutoStart: Boolean = false, dark: Boolean? = null, initialDraft: String? = null, initialTab: AppTab = AppTab.TODAY, initialSource: Boolean = false, settings: @Composable () -> Unit = {}, courseActions: @Composable (CourseView) -> Unit = {}) {
    val ui by c.tutor.model.state.collectAsState()
    val revision by c.sync.revision.collectAsState()
    val view by produceState<CourseView?>(null, c, revision) {
        var traced = emptyList<String>()
        value = withContext(Dispatchers.Default) {
            val events = c.sync.localEvents(c.courseId)
            val active = com.coach.client.PoolTrace.active()
            if (active.isNotEmpty()) traced = events.filter { it.type == "question_posed" && it.threadId in active }.mapNotNull { it.threadId }.distinct()
            traced.forEach { com.coach.client.PoolTrace.mark(it, "view_events_read") }
            val built = if (events.isEmpty() && revision == 0L) null else CourseView(events, c.sync.projection(c.courseId))
            traced.forEach { com.coach.client.PoolTrace.mark(it, "view_built") }
            built
        }
        traced.forEach { com.coach.client.PoolTrace.mark(it, "view_applied") }
    }
    val navigation = androidx.compose.runtime.saveable.rememberSaveable(c.courseId, saver = AppNavigation.saver) {
        val saved = c.sync.mainlineState(c.courseId, "navigation")["stack"]?.let { (it as? JsonPrimitive)?.content }
        if (initialKp == null && initialTab == AppTab.TODAY && saved != null) AppNavigation.restore(saved) ?: AppNavigation(listOf(AppEntry(tab = initialTab)))
        else if (initialKp == null && initialTab == AppTab.TODAY && c.sync.mainlineState(c.courseId).flag("open"))
            AppNavigation(listOf(AppEntry(tab = AppTab.REVIEW), AppEntry(AppPage.MAINLINE, AppTab.REVIEW, label = "选择题主线")))
        else AppNavigation(listOf(AppEntry(tab = initialTab)) + listOfNotNull(initialKp?.let {
            AppEntry(AppPage.STUDY, initialTab, it, initialAutoStart, label = it)
        }))
    }
    val entry = navigation.current
    LaunchedEffect(entry.page, entry.tab) {
        c.tutor.telemetry?.record("screen", when (entry.page) {
            AppPage.HOME -> entry.tab.name.lowercase()
            AppPage.STUDY -> "study"
            else -> entry.page.name.lowercase()
        })
    }
    val mainline = navigation.entries.any { it.page == AppPage.MAINLINE }
    SideEffect {
        c.sync.saveMainlineState(c.courseId, com.coach.domain.json("stack" to navigation.encode()), "navigation")
        c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) + ("open" to JsonPrimitive(mainline))))
    }
    val dispatcher = remember { AppBackDispatcher() }
    PlatformBackHandler(dispatcher.enabled) { dispatcher.back() }
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val weekActions = remember(c) { WeekActions(c, scope) }
    val focus = LocalFocusManager.current
    val select: (AppTab) -> Unit = navigation::select
    fun openStudy(id: String, start: Boolean, review: Boolean = false) {
        c.tutor.telemetry?.record("tap", "open_kp")
        val label = view?.kpAny(id)?.title?.let { splitTitle(it).second } ?: id
        val destination = AppEntry(AppPage.STUDY, id = id, autoStart = start, review = review, label = label)
        if (entry.page == AppPage.STUDY) navigation.replace(destination) else navigation.push(destination)
    }
    fun startLearning(target: LearningTarget) {
        if (target.legacy || target.week == null) { openStudy(target.kp.id, true); return }
        val saved = c.sync.mainlineState(c.courseId)
        c.sync.saveMainlineState(c.courseId, JsonObject(saved + mapOf("week" to JsonPrimitive(target.week), "targetKp" to JsonPrimitive(target.kp.id),
            "entry" to JsonPrimitive(target.entry))))
        navigation.push(AppEntry(AppPage.MAINLINE, id = "continue", week = target.week, label = "第 ${target.week} 周"))
    }
    val openKp: (String, Boolean) -> Unit = { id, start ->
        val current = view
        if (start && current != null) {
            val common = c.learningTarget(current)?.takeIf { it.kp.id == id }
            val explicit = current.kp(id)?.let { kp ->
                val week = current.weeks.firstOrNull { n -> current.weekKps(n).any { it.id == id } }
                LearningTarget(kp, week, false, legacy = week == null)
            }
            (common ?: explicit)?.let { weekActions.start(current, it) }
        }
        else openStudy(id, false)
    }
    val openReview: () -> Unit = { navigation.push(AppEntry(tab = AppTab.REVIEW, label = "复习")) }
    fun openTask(page: AppPage, label: String, id: String = "") {
        if (navigation.current.page == AppPage.MAINLINE) {
            (c.sync.mainlineState(c.courseId)["week"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { navigation.rename("第 $it 周") }
        }
        navigation.push(AppEntry(page, id = id, label = label))
    }
    val openEssay: () -> Unit = { openTask(AppPage.ESSAY, "论述限时练习") }
    val studyEntry = navigation.entries.lastOrNull { it.page == AppPage.STUDY }
    // 已打开的点即使被退休也要继续显示：名称、教材页和阅读内容不能凭空消失。
    val target = studyEntry?.let { view?.kpAny(it.id) }
    LaunchedEffect(target?.id, ui.connected) {
        if (target != null && ui.connected && target.anchorDoc.isNotBlank()) runCatching { prefetchSourcePage(c, target.anchorDoc, target.anchorPdfPage) }
    }
    val openReference: (KpRow) -> Unit = { kp ->
        if (kp.anchorDoc.isNotBlank()) {
            focus.clearFocus()
            navigation.rename(splitTitle(kp.title).second)
            navigation.push(AppEntry(AppPage.SOURCE, id = kp.anchorDoc, pdfPage = kp.anchorPdfPage, label = "原文"))
        }
    }
    var sourceShown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(target?.id) { if (initialSource && !sourceShown && target != null) { sourceShown = true; openReference(target) } }
    SideEffect {
        weekActions.onStart = ::startLearning
        weekActions.onBrowse = { n ->
            if (n == 0) navigation.select(AppTab.ROUTE)
            else {
                val destination = AppEntry(AppPage.WEEK, week = n, label = "第 $n 周")
                if (navigation.current.page == AppPage.WEEK) navigation.replace(destination) else navigation.push(destination)
            }
        }
        weekActions.onOpen = { id -> if (id != null) openStudy(id, true) else navigation.back() }
        weekActions.onReview = { week -> navigation.push(AppEntry(AppPage.WEEK_REVIEW, week = week, label = "第 $week 周错题")) }
    }
    var hideTaskChrome by remember { mutableStateOf(false) }
    val updateChrome = remember { { hidden: Boolean -> hideTaskChrome = hidden } }
    CoachTheme(dark ?: isSystemInDarkTheme()) {
        CompositionLocalProvider(LocalAppBack provides dispatcher, LocalAppManagedNavigation provides true, LocalTaskChrome provides updateChrome,
            com.coach.ui.v7.LocalDeskSelect provides select) {
            AppBackHandler(navigation.canGoBack, priority = -100) { navigation.back() }
            AppBackHandler(entry.page == AppPage.SOURCE, priority = 250) { navigation.back() }
            Surface(Modifier.fillMaxSize().onPreviewKeyEvent {
                it.key == Key.Escape && it.type == KeyEventType.KeyUp && dispatcher.back()
            }) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    OfflineBanner(ui, c)
                    view?.let { courseActions(it) }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val keyboard = with(LocalDensity.current) {
                            (WindowInsets.safeDrawing.getBottom(this) - WindowInsets.systemBars.union(WindowInsets.displayCutout).getBottom(this)).coerceAtLeast(0).toDp()
                        }
                        val window = WindowSpec(maxWidth.value, (maxHeight + keyboard).value)
                        val layout = LayoutRules.navigation(window)
                        val current = view
                        val task = entry.page in listOf(AppPage.MOCK, AppPage.ESSAY)
                        val showNav = !task && !hideTaskChrome
                        val render: @Composable (AppEntry) -> Unit = { page ->
                            holder.SaveableStateProvider("${c.courseId}:${page.page}:${page.tab}:${page.id}:${page.review}:${page.week}") {
                                when (page.page) {
                                    AppPage.HOME -> when (page.tab) {
                                        AppTab.TODAY -> TodayScreen(c, current, ui, selectedKpId = null, onOpenReview = openReview, onOpen = openKp, weekActions = weekActions)
                                        AppTab.ROUTE -> RouteScreen(c, current, null, openKp, weekActions.onReview, weekActions::select)
                                        AppTab.REVIEW -> if (current != null) ReviewScreen(c, current, { id, start -> openStudy(id, start, true) }, if (navigation.canGoBack) ({ navigation.back() }) else null,
                                            onOpenMock = { navigation.push(AppEntry(AppPage.MOCK, label = "模拟考")) }, onOpenEssay = openEssay,
                                            onOpenMainline = { week, direct ->
                                                val decision = current.let { c.learningTarget(it) }
                                                if (week != null) c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) + mapOf("week" to JsonPrimitive(week), "entry" to JsonPrimitive(if (!direct) "notes" else decision?.entry ?: "quiz"))))
                                                navigation.push(AppEntry(AppPage.MAINLINE, id = if (week == null) "catalog" else "", week = week, label = "选择题主线"))
                                            }, reminderSettings = settings) else CenterMessage("正在加载课程…")
                                        AppTab.RECORDS -> RecordsScreen(c, current, layout.nav != NavStyle.BottomBar, onOpenMock = { id -> navigation.push(AppEntry(AppPage.MOCK, id = id, label = "模拟考")) }) { id -> openStudy(id, false) }
                                        AppTab.RESOURCES -> ResourcesScreen(c, null, null)
                                    }
                                    AppPage.WEEK -> if (current != null) com.coach.ui.v7.WeekPage(c, current, ui, weekActions, openKp, page.week!!) else CenterMessage("正在加载课程…")
                                    AppPage.STUDY -> current?.kpAny(page.id)?.let { kp ->
                                        StudyScreen(c, kp, current, ui, page.autoStart, { navigation.back() }, initialDraft, openReference,
                                            if (page.review) null else weekActions, page.review, backLabel = "← ${navigation.entries.getOrNull(navigation.entries.indexOf(page) - 1)?.label ?: page.tab.label}")
                                    } ?: CenterMessage("正在加载课程…")
                                    AppPage.MAINLINE -> if (current != null) MainlineScreen(c, current, { navigation.back() },
                                        { openTask(AppPage.MOCK, "模拟考") }, openEssay, weekActions.onReview, directEntry = page.id == "continue", parentLabel = navigation.parentLabel,
                                        initialWeek = page.week, browseOnly = page.id == "catalog", onBrowseWeek = weekActions::select,
                                        onStartWeek = { n -> (c.learningTarget(current, n) ?: current.weekKps(n).firstOrNull()?.let { LearningTarget(it, n, false) })?.let { weekActions.start(current, it) } })
                                    AppPage.MOCK -> if (current != null) MockScreen(c, current, { navigation.back() }, page.id.ifEmpty { null }, weekActions.onReview, backLabel = "← ${navigation.parentLabel}")
                                    AppPage.ESSAY -> if (current != null) EssayScreen(c, current, { navigation.back() }, backLabel = "暂离 · ${navigation.parentLabel}")
                                    AppPage.WEEK_REVIEW -> if (current != null) WeekMistakesScreen(c, current, page.week!!, "← ${navigation.parentLabel}") { navigation.back() }
                                    AppPage.SOURCE -> Unit
                                }
                            }
                        }
                        val content by rememberUpdatedState<@Composable () -> Unit> {
                            CompositionLocalProvider(com.coach.ui.v7.LocalDeskWindow provides window) {
                                val reading = entry.page == AppPage.SOURCE
                                SourceOverlay(reading,
                                    base = { render(if (reading) navigation.entries[navigation.entries.lastIndex - 1] else entry) },
                                    overlay = { holder.SaveableStateProvider("${c.courseId}:$entry") { ResourcesScreen(c, SourceJump(entry.id, entry.pdfPage), { navigation.back() }, "← ${navigation.parentLabel}") } })
                            }
                        }
                        // One movable page and one chrome owner, including a rail/bottom-bar transition.
                        val page = remember { movableContentOf { content() } }
                        if (layout.nav == NavStyle.BottomBar) Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
                            if (showNav) AppNavBar(entry.tab, select)
                        } else Row(Modifier.fillMaxSize()) {
                            if (showNav) { AppNavRail(entry.tab, layout.nav == NavStyle.IconRail, select); VerticalDivider() }
                            Box(Modifier.weight(1f).fillMaxHeight()) { page() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 系统返回键的关闭顺序：原文 → 周复习 → 学习线程。应用里由 [AppNavigation] 的返回栈按同一顺序出栈；
 * 这里保留分层规则本身，供 [AppBackTest] 钉住顺序。
 */
@Composable
internal fun AppBackHandler(sourceOpen: Boolean, reviewOpen: Boolean, studyOpen: Boolean,
                            closeSource: () -> Unit, closeReview: () -> Unit, closeStudy: () -> Unit,
                            register: @Composable (Boolean, () -> Unit) -> Unit) {
    register(sourceOpen || reviewOpen || studyOpen) {
        when {
            sourceOpen -> closeSource()
            reviewOpen -> closeReview()
            studyOpen -> closeStudy()
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

/** Shared by the actual app and JVM captures, including the narrow-screen bottom navigation. */
@Composable
fun DeskNavigation(selected: AppTab, vertical: Boolean, onSelect: (AppTab) -> Unit) {
    val tabs = AppTab.entries
    val c = com.coach.ui.theme.CoachTheme.colors
    val item: @Composable (AppTab) -> Unit = { tab ->
        val active = selected == tab
        Column(Modifier.width(64.dp).then(if (active) Modifier.paperShadow("card", c.dark) else Modifier)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(if (active) c.surface1 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onSelect(tab) }.padding(vertical = if (active) 6.dp else if (vertical) 8.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.coach.ui.v7.DeskNavIcon(tab, Modifier.size(22.dp), if (active) c.fgPrimary else c.fgSecondary)
            Text(tab.label, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 22.2.sp), color = if (active) c.fgPrimary else c.fgSecondary)
        }
    }
    if (vertical) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { tabs.forEach { item(it) } }
    else Row(Modifier.fillMaxWidth().height(68.dp).background(c.bgCanvas), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) { tabs.forEach { item(it) } }
}

@Composable
private fun AppNavBar(selected: AppTab, onSelect: (AppTab) -> Unit, v7: Boolean = false) {
    val ink = com.coach.ui.theme.CoachTheme.colors
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = if (v7) 0.dp else 3.dp,
        modifier = (if (v7) Modifier.height(coach.design.tokens.V7Windows.Outer.footH) else Modifier).testTag("app-navigation"),
        windowInsets = if (v7) WindowInsets(0.dp) else NavigationBarDefaults.windowInsets) {
        AppTab.entries.forEach { t ->
            NavigationBarItem(selected = t == selected, onClick = { onSelect(t) }, modifier = Modifier.semantics { contentDescription = "导航：${t.label}" }, colors = if (v7) NavigationBarItemDefaults.colors(selectedIconColor = ink.accent, selectedTextColor = ink.fgPrimary, indicatorColor = ink.surface2, unselectedIconColor = ink.fgSecondary, unselectedTextColor = ink.fgSecondary) else NavigationBarItemDefaults.colors(), icon = { AppNavIcon(t) }, label = { Text(t.label) })
        }
    }
}

@Composable
internal fun AppNavRail(selected: AppTab, compact: Boolean, onSelect: (AppTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxHeight().testTag("app-navigation")) {
        Spacer(Modifier.weight(1f))
        AppTab.entries.forEach { t ->
            NavigationRailItem(selected = t == selected, onClick = { onSelect(t) }, modifier = Modifier.semantics { contentDescription = "导航：${t.label}" },
                // 窄侧轨只画图标：读屏读名称，不读「◉」「☰」这类字形。
                icon = { Box(if (compact) Modifier.clearAndSetSemantics { contentDescription = t.label } else Modifier) { AppNavIcon(t) } },
                label = if (compact) null else ({ Text(t.label) }), alwaysShowLabel = !compact)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AppNavIcon(tab: AppTab) {
    val glyph = tab.glyph
    if (glyph == null) {
        com.coach.ui.v7.DeskNavIcon(tab, Modifier.size(22.dp), LocalContentColor.current)
    } else {
        Text(glyph, style = MaterialTheme.typography.titleMedium)
    }
}

private data class StudyPaneArgs(val kp: KpRow, val view: CourseView, val ui: TutorUiState, val autoStart: Boolean, val onBack: (() -> Unit)?, val onOpenReference: ((KpRow) -> Unit)?, val reviewMode: Boolean, val backLabel: String)

/**
 * 横幅跟的是「站稳了」的连接状态：断线持续 [DISCONNECT_GRACE_MS] 才出现，恢复后稳定 [RECONNECT_SETTLE_MS] 才收起，
 * 连上即被踢的重连里横幅常亮而不是一闪一闪；冷启动给 [STARTUP_GRACE_MS] 去完成第一次握手。
 */
@Composable
internal fun steadyConnected(connected: Boolean): Boolean {
    var steady by remember { mutableStateOf(true) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        kotlinx.coroutines.delay(when {
            connected -> if (settled) RECONNECT_SETTLE_MS else 0
            settled -> DISCONNECT_GRACE_MS
            else -> STARTUP_GRACE_MS
        })
        steady = connected
        settled = true
    }
    return steady
}

internal const val DISCONNECT_GRACE_MS = 700L
internal const val RECONNECT_SETTLE_MS = 2_000L
internal const val STARTUP_GRACE_MS = 3_000L

@Composable
private fun OfflineBanner(ui: TutorUiState, c: CoachController) {
    if (steadyConnected(ui.connected)) return
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

/** V7 status labels use plain ink on the current paper/desk; StudyChromeTest checks both actual surfaces. */
internal const val CHIP_TINT = 0f

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
