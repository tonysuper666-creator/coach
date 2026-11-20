package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.client.CourseHttp
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Pairing with the Mac server and asking it to explain a block of a book that lives on this computer.
 * Open only so a screenshot test can stand in for the Mac without a network; the app uses this class as is.
 */
internal open class BookExplainer(private val settings: DesktopConnectionSettings = DesktopConnectionSettings(),
    library: TextbookLibrary? = null) {
    private val imports = library?.let(::ImportedBookWire)
    private val pairing = DesktopPairing(settings)
    var identity by mutableStateOf<DesktopIdentity?>(null)
        protected set
    var loaded by mutableStateOf(false)
        protected set
    var defaultAddress by mutableStateOf("")
        protected set
    /** 服务端说这台电脑的连接已停用（401）；重新配对成功前，配对表单上先说清这一句。 */
    var revoked by mutableStateOf(false)
        protected set
    /** 配对表单上要不要放「用 Sable 账号登录」：跟着构建时的决定走，默认只给配对码。 */
    val sableLogin: Boolean get() = settings.sableLoginShown()

    /** 别处（选段提问、答后讲解）先碰到了 401：讲解面板也回到配对，不再显示「已连上」。 */
    fun markRevoked() {
        identity?.let { stopped += it.key() }
        identity = null; revoked = true
    }

    open suspend fun load() {
        val result = withContext(Dispatchers.IO) { runCatching { settings.serverUrl() to pairing.load() } }
        result.getOrNull()?.let { (address, saved) ->
            defaultAddress = address.orEmpty()
            // 配对文件 401 之后仍在磁盘上；同一份凭据在这个进程里停用过，就别再当成「已连上」。
            val stale = saved != null && saved.key() in stopped
            val authorized = saved == null || saved.credentials.role != "friend" || withContext(Dispatchers.IO) {
                pairing.stillAuthorized(saved)
            }
            if (!authorized) { markRevoked(); loaded = true; return }
            identity = if (stale) null else saved
            if (stale) revoked = true else if (saved != null) revoked = false
        }
        loaded = true
    }

    suspend fun sable(open: suspend (String, String) -> Unit) {
        identity = pairing.sable(open)
        revoked = false
    }

    open suspend fun pair(address: String, code: String): Boolean = try {
        identity = withContext(Dispatchers.IO) { pairing.pair(address, code, "Windows 电脑") }
        revoked = false
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private companion object {
        /** 原文页、答后讲解、选段提问各有一个 explainer；谁先碰到 401，其他的读到同一份凭据时也算停用。 */
        val stopped: MutableSet<Triple<String, String, String>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun DesktopIdentity.key() = Triple(serverUrl, credentials.deviceId, credentials.token)
    }

    open suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        val context = ExplainWire.context(blocks, chosen)
        return send(ExplainWire.request(requestId, chosen, context), context, requestId)
    }

    open suspend fun explainLesson(blocks: List<SourceBlock>, bookPages: Int,
        requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        if (!BookLessonWire.fits(blocks)) return ExplainResult.Refused(BookLessonWire.TOO_LONG)
        return send(BookLessonWire.request(requestId, blocks, bookPages), blocks, requestId)
    }

    suspend fun ask(chosen: SourceBlock, question: String, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult =
        send(ExplainWire.question(requestId, chosen, question), listOf(chosen), requestId)

    private suspend fun send(input: JsonObject, context: List<SourceBlock>, requestId: String): ExplainResult {
        val paired = identity ?: return if (revoked) {
            ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            ExplainResult.Refused(ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val snapshot = withContext(Dispatchers.IO) { imports?.snapshot(context) }
            val lesson = input["kind"]?.jsonPrimitive?.contentOrNull == "lesson"
            val wire = if (lesson) {
                if (snapshot == null) return ExplainResult.Refused("请先打开个人导入的书并允许讲解。")
                try { BookLessonWire.withSource(input, snapshot) }
                catch (_: IllegalArgumentException) { return ExplainResult.Refused(BookLessonWire.TOO_LONG) }
            } else if (snapshot == null) input else JsonObject(input + ("localSource" to snapshot))
            val reply = http.explainLibrary(wire)
            if (snapshot != null && withContext(Dispatchers.IO) { imports!!.snapshot(context) } != snapshot)
                return ExplainResult.Refused("保存的原文已变化，请重新打开这本书后重试。")
            val body = reply.body
            // A 403 can mean either a revoked device or an unauthorized passage. Check only
            // the original server with the original identity; never resend the book text.
            val revoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && http.libraryAuthStatus() == 401)
            // A late response must not invalidate a pairing made while the request was in flight.
            if (identity != paired) return ExplainResult.Refused("连接已更换，请重新选择这段后再试。")
            val trouble = if (revoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> { markRevoked(); ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), trouble) }
                body == null -> ExplainResult.Refused(ExplainTrouble.message(trouble, reply.error), trouble)
                else -> ExplainWire.parse(body, context)
            }
        } catch (cancelled: CancellationException) {
            runCatching { http.cancelLibraryExplain(requestId) }
            throw cancelled
        } catch (_: Exception) {
            ExplainResult.Refused("保存的原文或发送许可无法核对，请重新打开这本书后重试。")
        } finally { http.close() }
    }
}
