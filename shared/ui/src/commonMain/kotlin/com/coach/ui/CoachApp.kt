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
)

/** 公式 SVG 缓存。缺失的公式显示源码 chip，同时请求 [fetch]；取到后重组。 */
class MathAssets(private val fetch: (suspend (tex: String, display: Boolean) -> MathAsset?)? = null) {
    val assets = mutableStateMapOf<String, MathAsset>()
    private val asked = HashSet<String>()
    suspend fun request(key: String) {
        val loader = fetch ?: return
        if (key in assets || !asked.add(key)) return
        val display = key.substringBefore(':') == "true"
        val tex = key.substringAfter(':')
        // 请求方的 LaunchedEffect 会因别的公式到货而重启；已发出的请求必须跑完，否则这个公式永远停在源码 chip。
        withContext(NonCancellable) { loader(tex, display)?.let { assets[key] = it } }
    }
}

/** 公式从服务端 `/v1/math` 取（手机没有 Node）；取不到的公式显示源码 chip。 */
fun MathAssets(http: com.coach.client.MathHttp) = MathAssets { tex, display ->
    http.render(tex, display)?.let { MathAsset(tex, display, it.sha, it.svg) }
}

fun threadIdFor(kpId: String) = "kp:$kpId"

/** 两栏布局的起点宽度：Fold8 内屏（约 960dp 宽）走两栏，外屏（约 624dp 宽）走单栏。 */
val WIDE_LAYOUT_MIN_WIDTH = 840.dp

@Composable
fun CoachApp(c: CoachController, initialKp: String? = null, initialAutoStart: Boolean = false, dark: Boolean? = null) {
    val ui by c.tutor.model.state.collectAsState()
    val revision by c.sync.revision.collectAsState()
    val view by produceState<CourseView?>(null, revision) {
        value = withContext(Dispatchers.Default) { CourseView(c.sync.localEvents(c.courseId), c.sync.projection(c.courseId)) }
    }
    var open by remember { mutableStateOf(initialKp?.let { it to initialAutoStart }) }
    CoachTheme(dark ?: isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                OfflineBanner(ui, c)
                val current = view
                val target = open?.let { (id, _) -> current?.kp(id) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                        // 宽屏：左边今天/知识点，右边学习线程；点「继续」直接在右栏开讲。
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(380.dp).fillMaxHeight()) {
                                TodayScreen(c, current, ui, selectedKpId = target?.id) { id, start -> open = id to start }
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (target != null && current != null) {
                                    key(target.id) { StudyScreen(c, target, current, ui, autoStart = open?.second == true, onBack = null) }
                                } else CenterMessage("选一个知识点，或点「继续」。")
                            }
                        }
                    } else if (target != null && current != null) {
                        StudyScreen(c, target, current, ui, autoStart = open?.second == true, onBack = { open = null })
                    } else {
                        TodayScreen(c, current, ui, selectedKpId = null) { id, start -> open = id to start }
                    }
                }
            }
        }
    }
}

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

@Composable
internal fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = .16f), shape = MaterialTheme.shapes.small) {
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
