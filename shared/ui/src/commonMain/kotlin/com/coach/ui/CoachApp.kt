package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
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
) {
    // Keep arrival anchors as long as the tutor model, including a trip back to Today.
    internal val quizMessageAnchors = HashMap<String, MutableMap<String, Int>>()
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
        withContext(NonCancellable) { val asset = runCatching { loader(tex, display) }.getOrNull(); if (asset != null) assets[key] = asset else failed[key] = true }
    }
}

/** 公式从服务端 `/v1/math` 取（手机没有 Node）；取不到的公式显示源码 chip。 */
fun MathAssets(http: com.coach.client.MathHttp) = MathAssets { tex, display ->
    http.render(tex, display)?.let { MathAsset(tex, display, it.sha, it.svg) }
}

fun threadIdFor(kpId: String) = "kp:$kpId"

/** 两栏布局的起点宽度（Material 的 medium/expanded 分界附近）：Fold8 内屏走两栏，外屏走单栏。 */
val WIDE_LAYOUT_MIN_WIDTH = 640.dp

@Composable
/** [initialDraft] 只用于验收：把一句话预填进输入框（`adb` 敲不了中文），不会自动发送。 */
fun CoachApp(c: CoachController, initialKp: String? = null, initialAutoStart: Boolean = false, dark: Boolean? = null, initialDraft: String? = null) {
    val ui by c.tutor.model.state.collectAsState()
    val revision by c.sync.revision.collectAsState()
    val view by produceState<CourseView?>(null, revision) {
        value = withContext(Dispatchers.Default) {
            val events = c.sync.localEvents(c.courseId)
            if (events.isEmpty() && revision == 0L) null else CourseView(events, c.sync.projection(c.courseId))
        }
    }
    var open by remember { mutableStateOf(initialKp?.let { it to initialAutoStart }) }
    var reviewOpen by remember { mutableStateOf(false) }
    val openKp: (String, Boolean) -> Unit = { id, start -> reviewOpen = false; open = id to start }
    val openReview: () -> Unit = { reviewOpen = true; open = null }
    CoachTheme(dark ?: isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                OfflineBanner(ui, c)
                val current = view
                val target = open?.let { (id, _) -> current?.kp(id) }
                val studyPane = remember(c) { movableContentOf<StudyPaneArgs> { args ->
                    key(args.kp.id) { StudyScreen(c, args.kp, args.view, args.ui, args.autoStart, args.onBack, initialDraft) }
                } }
                val reviewPane = remember(c) { movableContentOf<ReviewPaneArgs> { args ->
                    ReviewScreen(c, args.view, openKp, args.onBack)
                } }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val totalWidth = maxWidth
                    if (totalWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                        // 宽屏：左边今天/知识点，右边学习线程；点「继续」直接在右栏开讲。
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width((totalWidth * 0.38f).coerceIn(300.dp, 400.dp)).fillMaxHeight()) {
                                TodayScreen(c, current, ui, selectedKpId = target?.id, onOpenReview = openReview, onOpen = openKp)
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (reviewOpen && current != null) {
                                    reviewPane(ReviewPaneArgs(current, null))
                                } else if (target != null && current != null) {
                                    studyPane(StudyPaneArgs(target, current, ui, open?.second == true, null))
                                } else CenterMessage("选一个知识点，或点「继续」。")
                            }
                        }
                    } else if (reviewOpen && current != null) {
                        reviewPane(ReviewPaneArgs(current, { reviewOpen = false }))
                    } else if (target != null && current != null) {
                        studyPane(StudyPaneArgs(target, current, ui, open?.second == true, { open = null }))
                    } else {
                        TodayScreen(c, current, ui, selectedKpId = null, onOpenReview = openReview, onOpen = openKp)
                    }
                }
            }
        }
    }
}

private data class ReviewPaneArgs(val view: CourseView, val onBack: (() -> Unit)?)

private data class StudyPaneArgs(val kp: KpRow, val view: CourseView, val ui: TutorUiState, val autoStart: Boolean, val onBack: (() -> Unit)?)

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
