package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.client.withReaderRetry
import com.coach.client.withBookMapBatchPoll
import com.coach.client.withBusyRetry
import com.coach.client.CourseHttp
import com.coach.client.MockReply
import com.coach.client.ServerReachability
import com.coach.client.UnifiedServerCapability
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.coach.protocol.ReaderTransientReply
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Pairing with the Mac server and asking it to explain a block of a book that lives on this computer.
 * Open only so a screenshot test can stand in for the Mac without a network; the app uses this class as is.
 */
internal open class BookExplainer(private val settings: DesktopConnectionSettings = DesktopConnectionSettings(),
    private val library: TextbookLibrary? = null) {
    private val imports = library?.let(::ImportedBookWire)
    private val pairing = DesktopPairing(settings)
    private val loadMutex = Mutex()
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
    val hasBuiltInAddress: Boolean get() = settings.hasBuiltInFriendUrl()

    /** 别处（选段提问、答后讲解）先碰到了 401：讲解面板也回到配对，不再显示「已连上」。 */
    fun markRevoked() {
        identity?.let {
            stopped += it.key()
            runCatching { pairing.revoke(it) }
        }
        identity = null; revoked = true
    }

    open suspend fun load() = loadMutex.withLock {
        if (loaded) return@withLock
        val result = withContext(Dispatchers.IO) {
            runCatching { Triple(settings.serverUrl(), pairing.load(), runCatching { pairing.wasRevoked() }.getOrDefault(false)) }
        }
        result.getOrNull()?.let { (address, saved, wasRevoked) ->
            defaultAddress = address.orEmpty()
            // 配对文件 401 之后会被清理并留下撤销标记；同一份凭据停用过，就别再当成「已连上」。
            val stale = saved != null && saved.key() in stopped
            val authorized = saved == null || saved.credentials.role != "friend" || withContext(Dispatchers.IO) {
                pairing.stillAuthorized(saved)
            }
            if (!authorized) { markRevoked(); loaded = true; return@withLock }
            identity = if (stale) null else saved
            revoked = stale || (saved == null && wasRevoked)
        }
        loaded = true
    }

    suspend fun sable(open: suspend (String, String) -> Unit) {
        val signedIn = pairing.sable(open)
        identity = signedIn
        revoked = false
        CoachConnectivityRegistry.markOnline(signedIn.serverUrl)
    }

    open suspend fun pair(address: String, code: String): Boolean = try {
        val signedIn = withContext(Dispatchers.IO) { pairing.pair(address, code, "Windows 电脑") }
        identity = signedIn
        revoked = false
        CoachConnectivityRegistry.markOnline(signedIn.serverUrl)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun newHttp(
        paired: DesktopIdentity,
        connectTimeoutMs: Long = CoachConnectivityRegistry.probePolicy.attemptTimeoutMs,
    ): CourseHttp =
        CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library", connectTimeoutMs)

    private fun isReachableReply(reply: MockReply): Boolean =
        reply.statusCode != null && reply.code != "offline"

    private fun recordReplyConnectivity(serverUrl: String, reply: MockReply) {
        if (!isReachableReply(reply)) {
            CoachConnectivityRegistry.markOffline(serverUrl)
        } else {
            CoachConnectivityRegistry.markOnline(serverUrl)
        }
    }

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

    open suspend fun explainSectionGuide(
        context: ExplainSectionContext,
        bookPages: Int,
        requestId: String = UUID.randomUUID().toString().replace("-", ""),
    ): SectionGuideResult {
        val body = context.sectionBlocks.filterNot { it.heading }
        if (!BookLessonWire.fits(body)) return SectionGuideResult.Refused(BookLessonWire.TOO_LONG)
        val wire = ExplainWire.sectionGuideRequest(requestId, body, bookPages, context)
        return sendGuide(wire, body, requestId)
    }

    open suspend fun explainSelection(
        kind: String,
        chosen: SourceBlock,
        context: ExplainSectionContext? = null,
        question: String? = null,
        history: List<FollowUpTurn> = emptyList(),
        requestId: String = UUID.randomUUID().toString().replace("-", ""),
    ): ExplainResult {
        if (kind == "ask" && !question.isNullOrBlank() && kotlinx.coroutines.currentCoroutineContext()[BookAnswerProgress] == null) {
            val bookId = chosen.id.substringBefore(":p").takeIf { it.isNotBlank() && it != chosen.id }
            if (bookId != null && library != null) {
                withContext(Dispatchers.IO) { runCatching { library.saveLastQuestion(bookId, question) } }
            }
        }
        val blocks = ExplainWire.selectionBlocks(chosen, context)
        val wire = ExplainWire.selectionRequest(requestId, kind, chosen, blocks, context, question, history)
        return send(wire, blocks, requestId)
    }

    open suspend fun structureUnstructuredBook(
        candidates: List<SourceBlock>,
        bookPages: Int,
        requestId: String = UUID.randomUUID().toString().replace("-", ""),
    ): ModelSectionResult {
        if (!BookLessonWire.fits(candidates)) return ModelSectionResult.Failed(BookLessonWire.TOO_LONG)
        val wire = ExplainWire.bookStructureRequest(requestId, candidates, bookPages)
        val (body, refused) = sendRaw(wire, candidates, requestId)
        if (refused != null) return ModelSectionResult.Failed(refused.message)
        return ExplainWire.parseStructure(checkNotNull(body), candidates)
    }

    suspend fun ask(chosen: SourceBlock, question: String, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        val bookId = chosen.id.substringBefore(":p").takeIf { it.isNotBlank() && it != chosen.id }
        if (bookId != null && library != null && question.isNotBlank()) {
            withContext(Dispatchers.IO) { runCatching { library.saveLastQuestion(bookId, question) } }
        }
        return send(ExplainWire.question(requestId, chosen, question), listOf(chosen), requestId)
    }

    open suspend fun fetchChapterCompanion(
        requestJson: JsonObject,
        blocks: List<SourceBlock> = emptyList(),
    ): ChapterCompanionFetchResult {
        val paired = identity ?: return if (revoked) {
            ChapterCompanionFetchResult.Failed(ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            ChapterCompanionFetchResult.Failed(ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        return try {
            val snapshot = if (blocks.isNotEmpty()) {
                withContext(Dispatchers.IO) { runCatching { imports?.snapshot(blocks) }.getOrNull() }
                    ?: return ChapterCompanionFetchResult.Failed("请先打开个人导入的书并允许讲解。")
            } else null
            val wire = if (snapshot != null) JsonObject(requestJson + ("localSource" to snapshot)) else requestJson
            suspend fun stillCurrent(): Boolean =
                identity == paired && (snapshot == null || withContext(Dispatchers.IO) { runCatching { imports?.snapshot(blocks) }.getOrNull() } == snapshot)
            val reply = retryBeforeOffline(
                serverUrl = paired.serverUrl,
                stillCurrent = ::stillCurrent,
                isReachableResult = ::isReachableReply,
            ) { timeoutMs ->
                val http = newHttp(paired, timeoutMs)
                try {
                    http.companionChapter(wire)
                } finally {
                    http.close()
                }
            }
            if (snapshot != null && withContext(Dispatchers.IO) { runCatching { imports?.snapshot(blocks) }.getOrNull() } != snapshot) {
                return ChapterCompanionFetchResult.Failed("保存的原文已变化，请重新打开这本书后重试。")
            }
            val body = reply.body
            val isRevoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && checkAuthStatus(paired) == 401)
            if (identity != paired) return ChapterCompanionFetchResult.Failed("连接已更换，请重试。")
            val trouble = if (isRevoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> {
                    markRevoked()
                    ChapterCompanionFetchResult.Failed(ExplainTrouble.revoked(sableLogin), trouble)
                }
                body == null -> ChapterCompanionFetchResult.Failed(ExplainTrouble.message(trouble, reply.error), trouble)
                else -> {
                    val parsed = runCatching { CompanionJson.decodePack(body) }.getOrNull()
                    if (parsed != null) ChapterCompanionFetchResult.Ready(parsed)
                    else ChapterCompanionFetchResult.Failed("章节伴读返回格式无法解析，请稍后重试。")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CoachConnectivityRegistry.markOffline(paired.serverUrl)
            ChapterCompanionFetchResult.Failed(OfflineCopy.EXPLAIN_OFFLINE, ExplainTrouble.Unreachable)
        }
    }

    private suspend fun checkAuthStatus(paired: DesktopIdentity): Int? {
        val http = newHttp(paired)
        return try {
            http.libraryAuthStatus()
        } finally {
            http.close()
        }
    }

    open suspend fun courseAlign(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.courseAlign(input) }

    open suspend fun courseTopicPack(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.courseTopicPack(input) }

    open suspend fun examPointDrill(input: JsonObject): CourseServerCallResult {
        val paired = identity
        return callCourseEndpoint { http -> withBusyRetry(stillCurrent = { identity == paired }) { http.examPointDrill(input) } }
    }

    open suspend fun courseVision(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.courseVision(input) }

    open suspend fun generateChapterOpenQuestion(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.generateChapterOpenQuestion(input) }

    open suspend fun gradeAndDiagnosePractice(input: JsonObject): PracticeGradeDiagnoseResult {
        val res = callCourseEndpoint { http -> http.gradeAndDiagnosePractice(input) }
        val body = res.body ?: return PracticeGradeDiagnoseResult.Failed(
            res.error ?: "逐点批改暂时不可用，你的草稿已保留。",
            res.trouble ?: ExplainTrouble.Other,
        )
        val parsed = runCatching { PracticeGradingJson.decodeResponse(body) }.getOrNull()
        return if (parsed != null) PracticeGradeDiagnoseResult.Ready(parsed)
        else PracticeGradeDiagnoseResult.Failed("批改结果格式无法解析，请稍后重试。")
    }

    open suspend fun askCollection(input: JsonObject): CourseServerCallResult {
        val paired = identity
        return callCourseEndpoint { http -> withReaderRetry({ identity == paired }) { http.askCollection(input) } }
    }

    open suspend fun generateCourseMock(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.generateCourseMock(input) }

    open suspend fun fetchStudyModePack(
        bookId: String,
        mode: String,
        input: JsonObject,
        /** Shown while the server is briefly full and the request waits to retry by itself (TON-740). */
        onBusy: (String) -> Unit = {},
        onProgress: (chars: Int, firstTokenMs: Long?, delta: String) -> Unit = { _, _, _ -> },
    ): CourseServerCallResult {
        var streamedAny = false
        return callCourseEndpoint(canRetry = { !streamedAny }) { http ->
            withBusyRetry(stillCurrent = { !streamedAny }, onBusy = onBusy) {
                http.streamStudyModePack(bookId, mode, input) { chars, firstTokenMs, delta ->
                    if (chars > 0 || delta.isNotEmpty()) streamedAny = true
                    onProgress(chars, firstTokenMs, delta)
                }
            }
        }
    }

    open suspend fun fetchBookMapBatch(bookId: String, input: JsonObject, onBusy: (String) -> Unit = {}): CourseServerCallResult =
        callCourseEndpoint { http -> withBookMapBatchPoll(onProgress = onBusy) { http.fetchBookMapBatch(bookId, input) } }

    private suspend fun callCourseEndpoint(
        canRetry: () -> Boolean = { true },
        block: suspend (CourseHttp) -> MockReply,
    ): CourseServerCallResult {
        val paired = identity ?: return if (revoked) {
            CourseServerCallResult(null, ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            CourseServerCallResult(null, ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        return try {
            val reply = retryBeforeOffline(
                serverUrl = paired.serverUrl,
                stillCurrent = { identity == paired && canRetry() },
                isReachableResult = { r -> !canRetry() || isReachableReply(r) },
            ) { timeoutMs ->
                val http = newHttp(paired, timeoutMs)
                try {
                    block(http)
                } finally {
                    http.close()
                }
            }
            val body = reply.body
            val isRevoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && checkAuthStatus(paired) == 401)
            if (identity != paired) return CourseServerCallResult(null, "连接已更换，请重试。")
            val trouble = if (isRevoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> {
                    markRevoked()
                    CourseServerCallResult(null, ExplainTrouble.revoked(sableLogin), trouble)
                }
                body == null -> CourseServerCallResult(null, ExplainTrouble.message(trouble, reply.error), trouble, reply.code)
                else -> CourseServerCallResult(body, null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CoachConnectivityRegistry.markOffline(paired.serverUrl)
            CourseServerCallResult(null, OfflineCopy.EXPLAIN_OFFLINE, ExplainTrouble.Unreachable)
        }
    }

    open suspend fun alignConceptSayings(requestJson: JsonObject): JsonObject? {
        val paired = identity ?: return null
        return try {
            val reply = retryBeforeOffline(
                serverUrl = paired.serverUrl,
                stillCurrent = { identity == paired },
                isReachableResult = ::isReachableReply,
            ) { timeoutMs ->
                val http = newHttp(paired, timeoutMs)
                try {
                    http.alignConceptSayings(requestJson)
                } finally {
                    http.close()
                }
            }
            if (reply.statusCode == 401) {
                markRevoked()
                null
            } else reply.body
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CoachConnectivityRegistry.markOffline(paired.serverUrl)
            null
        }
    }

    open suspend fun alignBooklistConcepts(requestJson: JsonObject): JsonObject? {
        val paired = identity ?: return null
        return try {
            val reply = retryBeforeOffline(
                serverUrl = paired.serverUrl,
                stillCurrent = { identity == paired },
                isReachableResult = ::isReachableReply,
            ) { timeoutMs ->
                val http = newHttp(paired, timeoutMs)
                try {
                    http.alignBooklistConcepts(requestJson)
                } finally {
                    http.close()
                }
            }
            if (reply.statusCode == 401) {
                markRevoked()
                null
            } else reply.body
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CoachConnectivityRegistry.markOffline(paired.serverUrl)
            null
        }
    }

    private suspend fun sendGuide(input: JsonObject, context: List<SourceBlock>, requestId: String): SectionGuideResult {
        val (body, refused) = sendRaw(input, context, requestId)
        if (refused != null) return SectionGuideResult.Refused(refused.message, refused.trouble)
        return ExplainWire.parseGuide(checkNotNull(body), context)
    }

    private suspend fun send(input: JsonObject, context: List<SourceBlock>, requestId: String): ExplainResult =
        retryTransientOnce(requestId) { id ->
            val wire = if (id == requestId) input else JsonObject(input + ("requestId" to JsonPrimitive(id)))
            val (body, refused) = sendRaw(wire, context, id)
            refused ?: ExplainWire.parse(checkNotNull(body), context)
        }

    private suspend fun sendRaw(input: JsonObject, context: List<SourceBlock>, requestId: String): Pair<JsonObject?, ExplainResult.Refused?> {
        val paired = identity ?: return null to if (revoked) {
            ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            ExplainResult.Refused(ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        return try {
            val snapshot = withContext(Dispatchers.IO) { imports?.snapshot(context) }
            val kind = input["kind"]?.jsonPrimitive?.contentOrNull
            val sectionScoped = kind == "lesson" || kind == "guide" || kind == "structure" || input["sectionContext"] != null
            val wire = if (sectionScoped) {
                if (snapshot == null) return null to ExplainResult.Refused("请先打开个人导入的书并允许讲解。")
                try { BookLessonWire.withSource(input, snapshot) }
                catch (_: IllegalArgumentException) { return null to ExplainResult.Refused(BookLessonWire.TOO_LONG) }
            } else if (snapshot == null) input else JsonObject(input + ("localSource" to snapshot))
            suspend fun stillCurrent() = identity == paired && withContext(Dispatchers.IO) { imports?.snapshot(context) } == snapshot
            val progress = kotlinx.coroutines.currentCoroutineContext()[BookAnswerProgress]?.takeIf { kind == "ask" }
            var verifiedStream = false
            val reply = retryBeforeOffline(
                serverUrl = paired.serverUrl,
                stillCurrent = { !verifiedStream && stillCurrent() },
                isReachableResult = { r -> verifiedStream || isReachableReply(r) },
            ) { timeoutMs ->
                val http = newHttp(paired, timeoutMs)
                try {
                    withReaderRetry({ stillCurrent() }) {
                        if (progress == null) http.explainLibrary(wire) else http.explainLibrary(wire) { partial ->
                            if (!verifiedStream) {
                                check(stillCurrent())
                                verifiedStream = true
                            } else {
                                check(identity == paired && withContext(Dispatchers.IO) { imports?.pageSnapshot(context) } == snapshot)
                            }
                            val checked = ExplainWire.parse(partial, context) as? ExplainResult.Done ?: error("Invalid answer sources")
                            progress.received(checked.explanation.text)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // TON-1963: this coroutine is already cancelled, so a plain suspend call would throw before
                    // anything left the machine and the service would keep the abandoned answer running.
                    withContext(NonCancellable) { withTimeoutOrNull(CANCEL_NOTICE_MS) { http.cancelLibraryExplain(requestId) } }
                    throw cancelled
                } finally {
                    http.close()
                }
            }
            if (snapshot != null && withContext(Dispatchers.IO) { imports!!.snapshot(context) } != snapshot)
                return null to ExplainResult.Refused("保存的原文已变化，请重新打开这本书后重试。")
            val body = reply.body
            // A 403 can mean either a revoked device or an unauthorized passage. Check only
            // the original server with the original identity; never resend the book text.
            val revoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && checkAuthStatus(paired) == 401)
            // A late response must not invalidate a pairing made while the request was in flight.
            if (identity != paired) return null to ExplainResult.Refused("连接已更换，请重新选择这段后再试。")
            val trouble = if (revoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> { markRevoked(); null to ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), trouble) }
                body == null -> null to ExplainResult.Refused(ExplainTrouble.message(trouble, reply.error), trouble)
                else -> body to null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null to ExplainResult.Refused("保存的原文或发送许可无法核对，请重新打开这本书后重试。")
        }
    }

    /** Unified server book intake client backed by an immutable per-batch identity snapshot (TON-1823 PR A). */
    open fun unifiedUploadClient(): UnifiedBookRemoteClient? = object : UnifiedBookRemoteClient {
        private fun fallbackAccountKey(paired: DesktopIdentity): String {
            val raw = "${paired.serverUrl}\u0000${paired.credentials.role}\u0000${paired.credentials.deviceId}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return "local-" + digest.joinToString("") { "%02x".format(it) }
        }

        private suspend fun currentIdentity(): DesktopIdentity? {
            if (!loaded) load()
            return identity
        }

        private fun isStillPinned(pinned: DesktopIdentity): Boolean {
            val current = identity ?: return false
            return current.serverUrl == pinned.serverUrl &&
                current.credentials.deviceId == pinned.credentials.deviceId &&
                current.credentials.token == pinned.credentials.token &&
                current.credentials.role == pinned.credentials.role
        }

        private fun pinnedSession(pinned: DesktopIdentity): UnifiedBookRemoteSession = object : UnifiedBookRemoteSession {
            override val sessionId: String = fallbackAccountKey(pinned)
            override val fallbackAccountKey: String = sessionId

            private fun ensurePinned() {
                if (!isStillPinned(pinned)) throw CancellationException("Upload identity changed")
            }

            override suspend fun checkCapability(): UnifiedBookCapability {
                ensurePinned()
                val cap = retryBeforeOffline(
                    serverUrl = pinned.serverUrl,
                    stillCurrent = { isStillPinned(pinned) },
                    isReachableResult = { it !is UnifiedServerCapability.Offline },
                ) { timeoutMs ->
                    val http = newHttp(pinned, timeoutMs)
                    try {
                        http.unifiedBooksCapability(pinned.credentials.role, timeoutMs)
                    } finally {
                        http.close()
                    }
                }
                ensurePinned()
                return when (cap) {
                    is UnifiedServerCapability.Supported -> UnifiedBookCapability.Supported(
                        cap.accountScope ?: fallbackAccountKey,
                    )
                    is UnifiedServerCapability.Unsupported -> UnifiedBookCapability.Unsupported(
                        cap.accountScope ?: fallbackAccountKey,
                    )
                    UnifiedServerCapability.Unauthorized -> {
                        markRevoked()
                        UnifiedBookCapability.Unauthorized
                    }
                    UnifiedServerCapability.Offline -> UnifiedBookCapability.Offline
                }
            }

            override suspend fun uploadBook(
                file: Path,
                format: String,
                title: String,
                sha256: String,
                onProgress: (Int) -> Unit,
            ): UnifiedBookUploadOutcome {
                ensurePinned()
                val totalBytes = runCatching { Files.size(file) }.getOrNull()
                    ?: return UnifiedBookUploadOutcome.Failed("本地原文件无法读取，请重新导入", retryable = false)
                if (totalBytes <= 0L) {
                    return UnifiedBookUploadOutcome.Failed("这份教材是空文件，无法上传", retryable = false)
                }
                val http = newHttp(pinned)
                return try {
                    val reply = http.uploadUnifiedBook(totalBytes, format, title, sha256) { writeChunk ->
                        withContext(Dispatchers.IO) {
                            var sent = 0L
                            var lastPct = -1
                            Files.newInputStream(file).use { input ->
                                val buf = ByteArray(65_536)
                                while (true) {
                                    ensurePinned()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    writeChunk(buf, 0, n)
                                    sent += n
                                    val pct = ((sent * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    if (pct != lastPct && (pct == 100 || pct - lastPct >= 5)) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                    recordReplyConnectivity(pinned.serverUrl, reply)
                    ensurePinned()
                    val body = reply.body
                    when {
                        reply.statusCode == 401 -> {
                            markRevoked()
                            UnifiedBookUploadOutcome.Unauthorized
                        }
                        reply.statusCode == 404 || reply.code == "unsupported" -> UnifiedBookUploadOutcome.UnsupportedServer
                        reply.code == "offline" -> UnifiedBookUploadOutcome.Offline
                        body != null -> {
                            val shelfBookId = body["shelfBookId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val serverSha = body["sha256"]?.jsonPrimitive?.contentOrNull ?: sha256
                            val job = body["job"] as? JsonObject
                            val jobState = BookUploadQueueStore.parseServerJobState(
                                job?.get("state")?.jsonPrimitive?.contentOrNull ?: "queued",
                            )
                            val pct = job?.get("progressPct")?.jsonPrimitive?.intOrNull ?: 0
                            val stageLabel = job?.get("stageLabel")?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (shelfBookId.isBlank()) {
                                UnifiedBookUploadOutcome.Failed("服务端返回格式异常，请稍后重试")
                            } else {
                                UnifiedBookUploadOutcome.Accepted(shelfBookId, serverSha, jobState, pct, stageLabel)
                            }
                        }
                        else -> UnifiedBookUploadOutcome.Failed(reply.error ?: "上传失败，请稍后重试", reply.retryable)
                    }
                } finally {
                    http.close()
                }
            }

            override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome {
                ensurePinned()
                val http = newHttp(pinned)
                return try {
                    val reply = http.unifiedBookStatus(shelfBookId)
                    recordReplyConnectivity(pinned.serverUrl, reply)
                    ensurePinned()
                    val body = reply.body
                    when {
                        reply.statusCode == 401 -> {
                            markRevoked()
                            UnifiedBookStatusOutcome.Unauthorized
                        }
                        reply.statusCode == 404 -> UnifiedBookStatusOutcome.NotFound
                        reply.code == "offline" -> UnifiedBookStatusOutcome.Offline
                        body != null -> {
                            val job = body["job"] as? JsonObject
                            val jobState = BookUploadQueueStore.parseServerJobState(
                                job?.get("state")?.jsonPrimitive?.contentOrNull ?: "queued",
                            )
                            val pct = job?.get("progressPct")?.jsonPrimitive?.intOrNull ?: 0
                            val stageLabel = job?.get("stageLabel")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val err = job?.get("error")?.jsonPrimitive?.contentOrNull
                            UnifiedBookStatusOutcome.Ready(jobState, pct, stageLabel, err)
                        }
                        else -> UnifiedBookStatusOutcome.Offline
                    }
                } finally {
                    http.close()
                }
            }

            override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
                ensurePinned()
                val http = newHttp(pinned)
                return try {
                    val reply = http.deleteUnifiedBook(shelfBookId)
                    recordReplyConnectivity(pinned.serverUrl, reply)
                    ensurePinned()
                    when {
                        reply.statusCode == 401 -> {
                            markRevoked()
                            UnifiedBookDeleteOutcome.Unauthorized
                        }
                        reply.code == "not_found" -> UnifiedBookDeleteOutcome.NotFound
                        reply.statusCode == 404 || reply.code == "unsupported" -> UnifiedBookDeleteOutcome.UnsupportedServer
                        reply.code == "offline" -> UnifiedBookDeleteOutcome.Offline
                        reply.statusCode in 200..299 -> UnifiedBookDeleteOutcome.Deleted
                        else -> UnifiedBookDeleteOutcome.Failed(reply.error ?: "解绑失败", reply.retryable)
                    }
                } finally {
                    http.close()
                }
            }

            override suspend fun listRemoteBooks(): UnifiedBookListOutcome {
                ensurePinned()
                val http = CourseHttp(pinned.serverUrl, pinned.credentials.deviceId, pinned.credentials.token, "library")
                return try {
                    val reply = http.listUnifiedBooks()
                    ensurePinned()
                    val body = reply.body
                    when {
                        reply.statusCode == 401 -> {
                            markRevoked()
                            UnifiedBookListOutcome.Unauthorized
                        }
                        reply.statusCode == 404 -> UnifiedBookListOutcome.UnsupportedServer
                        reply.code == "offline" || body == null -> UnifiedBookListOutcome.Offline
                        else -> {
                            val raw = body["books"] as? JsonArray ?: return UnifiedBookListOutcome.Ready(emptyList())
                            val books = raw.mapNotNull { el ->
                                val obj = el as? JsonObject ?: return@mapNotNull null
                                val shelfBookId = obj["shelfBookId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val sha = obj["sha256"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val format = obj["format"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L
                                val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
                                val updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                                val job = obj["job"] as? JsonObject
                                val jobState = BookUploadQueueStore.parseServerJobState(
                                    job?.get("state")?.jsonPrimitive?.contentOrNull ?: "queued",
                                )
                                val progressPct = job?.get("progressPct")?.jsonPrimitive?.intOrNull ?: 0
                                val stageLabel = job?.get("stageLabel")?.jsonPrimitive?.contentOrNull.orEmpty()
                                val errorMessage = job?.get("error")?.jsonPrimitive?.contentOrNull
                                val processCount = job?.get("processCount")?.jsonPrimitive?.intOrNull ?: 0
                                RemoteShelfBook(
                                    shelfBookId = shelfBookId,
                                    sha256 = sha,
                                    title = title,
                                    format = format,
                                    sizeBytes = sizeBytes,
                                    createdAt = createdAt,
                                    updatedAt = updatedAt,
                                    jobState = jobState,
                                    progressPct = progressPct,
                                    stageLabel = stageLabel,
                                    errorMessage = errorMessage,
                                    processCount = processCount,
                                )
                            }
                            UnifiedBookListOutcome.Ready(books)
                        }
                    }
                } finally {
                    http.close()
                }
            }

            override suspend fun downloadBookOriginal(
                shelfBookId: String,
                destination: Path,
                expectedSha256: String,
                onProgress: (Int) -> Unit,
            ): UnifiedBookDownloadOutcome {
                ensurePinned()
                val http = CourseHttp(pinned.serverUrl, pinned.credentials.deviceId, pinned.credentials.token, "library")
                return try {
                    var written = 0L
                    var serverHeaderSha: String? = null
                    val digest = MessageDigest.getInstance("SHA-256")
                    var lastPct = -1
                    withContext(Dispatchers.IO) {
                        destination.parent?.let { Files.createDirectories(it) }
                    }
                    val reply = Files.newOutputStream(destination).use { out ->
                        http.downloadUnifiedBookOriginal(shelfBookId) { buf, offset, len, serverSha, contentLength ->
                            ensurePinned()
                            if (serverHeaderSha == null) serverHeaderSha = serverSha
                            withContext(Dispatchers.IO) {
                                digest.update(buf, offset, len)
                                out.write(buf, offset, len)
                            }
                            written += len
                            if (contentLength != null && contentLength > 0L) {
                                val pct = ((written * 100L) / contentLength).toInt().coerceIn(0, 100)
                                if (pct != lastPct && (pct == 100 || pct - lastPct >= 5)) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                    ensurePinned()
                    val actualSha = if (written > 0L) digest.digest().joinToString("") { "%02x".format(it) } else null
                    when {
                        reply.statusCode == 401 -> {
                            runCatching { Files.deleteIfExists(destination) }
                            markRevoked()
                            UnifiedBookDownloadOutcome.Unauthorized
                        }
                        reply.statusCode == 404 && reply.code == "not_found" -> {
                            runCatching { Files.deleteIfExists(destination) }
                            UnifiedBookDownloadOutcome.NotFound
                        }
                        reply.statusCode == 404 -> {
                            runCatching { Files.deleteIfExists(destination) }
                            UnifiedBookDownloadOutcome.UnsupportedServer
                        }
                        reply.code == "offline" -> {
                            runCatching { Files.deleteIfExists(destination) }
                            UnifiedBookDownloadOutcome.Offline
                        }
                        reply.statusCode in 200..299 && actualSha != null -> {
                            val expected = expectedSha256.trim().lowercase()
                            if (written <= 0L || actualSha != expected || (serverHeaderSha != null && serverHeaderSha != actualSha)) {
                                runCatching { Files.deleteIfExists(destination) }
                                UnifiedBookDownloadOutcome.Failed("原文件校验不一致，请重试。")
                            } else {
                                onProgress(100)
                                UnifiedBookDownloadOutcome.Downloaded(actualSha, written)
                            }
                        }
                        else -> {
                            runCatching { Files.deleteIfExists(destination) }
                            UnifiedBookDownloadOutcome.Failed(reply.error ?: "下载原文件失败，请重试。")
                        }
                    }
                } catch (cancel: CancellationException) {
                    runCatching { Files.deleteIfExists(destination) }
                    throw cancel
                } catch (_: Exception) {
                    runCatching { Files.deleteIfExists(destination) }
                    UnifiedBookDownloadOutcome.Offline
                } finally {
                    http.close()
                }
            }
        }

        override suspend fun activeAccountKey(): String? = currentIdentity()?.let(::fallbackAccountKey)

        override suspend fun openSession(): UnifiedBookRemoteSession? = currentIdentity()?.let(::pinnedSession)

        override suspend fun checkCapability(): UnifiedBookCapability =
            openSession()?.checkCapability() ?: UnifiedBookCapability.Unauthorized

        override fun connectivityFlow(): StateFlow<CoachConnectivityStatus>? =
            identity?.serverUrl?.let { CoachConnectivityRegistry.flowFor(it) }

        override suspend fun probeReachability(): CoachConnectivityStatus {
            val paired = currentIdentity() ?: return CoachConnectivityStatus.UNKNOWN
            val reachability = retryBeforeOffline(
                serverUrl = paired.serverUrl,
                stillCurrent = { isStillPinned(paired) },
                isReachableResult = { it != ServerReachability.OFFLINE },
            ) { timeoutMs ->
                val http = CourseHttp(
                    paired.serverUrl,
                    paired.credentials.deviceId,
                    paired.credentials.token,
                    "library",
                    timeoutMs,
                )
                try {
                    http.probeReachability(paired.credentials.role, timeoutMs)
                } finally {
                    http.close()
                }
            }
            return when (reachability) {
                ServerReachability.ONLINE -> CoachConnectivityStatus.ONLINE
                ServerReachability.UNAUTHORIZED -> {
                    markRevoked()
                    CoachConnectivityStatus.ONLINE
                }
                ServerReachability.OFFLINE -> CoachConnectivityStatus.OFFLINE
            }
        }

        override suspend fun uploadBook(
            file: Path,
            format: String,
            title: String,
            sha256: String,
            onProgress: (Int) -> Unit,
        ): UnifiedBookUploadOutcome =
            openSession()?.uploadBook(file, format, title, sha256, onProgress) ?: UnifiedBookUploadOutcome.Unauthorized

        override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome =
            openSession()?.fetchStatus(shelfBookId) ?: UnifiedBookStatusOutcome.Unauthorized

        override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome =
            openSession()?.deleteBook(shelfBookId) ?: UnifiedBookDeleteOutcome.Unauthorized

        override suspend fun listRemoteBooks(): UnifiedBookListOutcome =
            openSession()?.listRemoteBooks() ?: UnifiedBookListOutcome.Unauthorized

        override suspend fun downloadBookOriginal(
            shelfBookId: String,
            destination: Path,
            expectedSha256: String,
            onProgress: (Int) -> Unit,
        ): UnifiedBookDownloadOutcome =
            openSession()?.downloadBookOriginal(shelfBookId, destination, expectedSha256, onProgress)
                ?: UnifiedBookDownloadOutcome.Unauthorized
    }
}

/** How long a cancelled explain waits to tell the service to stop; the cancel itself never waits for the answer. */
private const val CANCEL_NOTICE_MS = 3_000L

/**
 * TON-1963: the service answers HTTP 200 with no sources and one of [ReaderTransientReply.retryable] when a single
 * model attempt broke off (no first token, the stream stalled, the bridge failed, the JSON was unreadable, or the
 * service was full). The same question usually succeeds on a fresh attempt, so ask once more under a new request id
 * before showing the refusal. [ReaderTransientReply.INSUFFICIENT] (the model returned no paragraphs, usually within
 * a second or two) can repeat on a supported passage, so it may be asked up to two more times. Every other result,
 * including the total-time limit, is returned as it is. At most three attempts in all, each under its own id.
 */
internal suspend fun retryTransientOnce(
    requestId: String,
    busyPauseMs: Long = 1_500,
    newRequestId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    attempt: suspend (requestId: String) -> ExplainResult,
): ExplainResult {
    var result = attempt(requestId)
    var retries = 0
    while (true) {
        val refusal = (result as? ExplainResult.Refused)?.message?.trim() ?: return result
        val again = when {
            refusal == ReaderTransientReply.INSUFFICIENT -> retries < INSUFFICIENT_RETRIES
            ReaderTransientReply.isRetryable(refusal) -> retries < 1
            else -> false
        }
        if (!again) return result
        if (refusal == ReaderTransientReply.BUSY) delay(busyPauseMs)
        retries++
        result = attempt(newRequestId())
    }
}

/** Extra tries for an empty answer; the wfs p11 explain refused twice running before it answered. */
private const val INSUFFICIENT_RETRIES = 2
