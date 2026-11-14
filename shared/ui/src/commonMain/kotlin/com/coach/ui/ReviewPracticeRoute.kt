package com.coach.ui

import com.coach.client.CourseView
import com.coach.client.ReviewPracticeSession
import com.coach.client.restoreReviewPracticeSession
import com.coach.client.*
import com.coach.domain.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

/** Allowed review-specific controller port; does not modify shared navigation or the mainline controller. */
fun CoachController.restoreReviewPractice(kpId: String, view: CourseView): ReviewPracticeSession =
    restoreReviewPracticeSession(sync, courseId, kpId, tutor.agentRunId, view, nowMillis())

/** The navigation attempt must still match the scoped persisted selection before any allocation. */
@Composable
fun ReviewPracticeScreen(c: CoachController, expectedAttemptId: String? = null, onClose: () -> Unit) {
    val target = reviewPracticeTarget(c.sync, c.courseId, c.tutor.agentRunId)
        ?.takeIf { expectedAttemptId == null || it.attemptId == expectedAttemptId }
    val revision by c.sync.revision.collectAsState()
    var refresh by remember(c) { mutableStateOf(0) }
    var retry by remember(c) { mutableStateOf(0) }
    var loading by remember(c) { mutableStateOf(true) }
    var error by remember(c) { mutableStateOf<String?>(null) }
    var busy by remember(c) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun currentView(): CourseView = CourseView(c.sync.localEvents(c.courseId), c.sync.projection(c.courseId), c.tutor.agentRunId)
    LaunchedEffect(c, target, retry) {
        loading = true; error = null
        try {
            if (target != null) withContext(Dispatchers.IO) {
                val local = c.restoreReviewPractice(target.kpId, currentView())
                if (local == ReviewPracticeSession.Rejected(ReviewPracticeFailure.MISSING_ALLOCATION)) {
                    val reply = c.course?.takeReviewVariant(target)
                    if (reply?.body == null) throw IllegalStateException("Exact allocation unavailable")
                    // Never append response-shaped facts locally: only the authenticated ledger replica is accepted.
                    c.sync.syncOnce()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "这道题暂时无法开始。选中的题和原作答已保留，可连接后重试。" }
        finally { loading = false; refresh++ }
    }
    val view = remember(c, revision, refresh) { currentView() }
    val session = target?.let { c.restoreReviewPractice(it.kpId, view) }
    val ready = session as? ReviewPracticeSession.Ready
    val answerPage = target?.let { "review-answer:${it.attemptId}" }.orEmpty()
    val saved = c.sync.mainlineState(c.courseId, answerPage)
    var chosen by remember(target, refresh) { mutableStateOf(saved.str("choice").ifBlank { null }) }
    val commandId = saved.str("commandId").ifBlank { null }
    val receipt = commandId?.let(c.sync::receipt)
    val confirmed = receipt?.status in setOf("OK", "DUPLICATE") || ready?.group?.submitted == true
    val locked = confirmed || commandId != null
    fun submit() {
        val t = target ?: return
        val r = ready ?: return
        val pose = view.events.single { it.type == "question_posed" && it.payload.str("attemptId") == t.attemptId }
        val selection = chosen ?: return
        if (busy) return
        busy = true; error = null
        // Enqueue/answer lock is synchronous and transactional, before any network suspension.
        try {
            if (commandId == null) c.sync.enqueueReviewAnswer(t, pose, selection, c.nowMillis())
        } catch (_: IllegalArgumentException) { busy = false; error = "这次作答身份已经变化，原作答已保留。"; return }
        refresh++
        scope.launch {
            try { withContext(Dispatchers.IO) { c.sync.syncOnce() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The existing immutable outbox remains pending. */ }
            finally { busy = false; refresh++ }
        }
    }
    AppBackHandler(true, onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text("← 复习") }
        Text("复习这道题", style = MaterialTheme.typography.titleLarge)
        when {
            loading -> Text("正在确认选中的题…")
            ready == null -> {
                Text(error ?: "这道题的身份或可用状态暂时无法确认。原作答已保留，没有换成另一题。")
                TextButton(onClick = { retry++ }) { Text("重试这道题") }
            }
            else -> {
                QuizCardView(1, 1, ready.group.cards.single(), locked, chosen, c.math) { choice ->
                    if (!locked) {
                        chosen = choice
                        c.sync.saveMainlineState(c.courseId, json("choice" to choice), answerPage)
                    }
                }
                when {
                    confirmed -> Text("作答已确认。")
                    receipt != null -> Text("这次作答未被接受，原作答已保留。")
                    commandId != null -> {
                        Text("作答待同步，答案已保存。")
                        TextButton(onClick = { submit() }, enabled = !busy) { Text("重试同步") }
                    }
                    else -> Button(onClick = { submit() }, enabled = chosen != null && !busy,
                        modifier = Modifier.testTag("review-submit")) { Text("提交答案") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
