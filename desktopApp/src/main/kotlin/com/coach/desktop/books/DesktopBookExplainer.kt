package com.coach.desktop.books

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.client.CourseHttp
import com.coach.client.MockReply
import com.coach.client.UnifiedServerCapability
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val hasBuiltInAddress: Boolean get() = settings.hasBuiltInFriendUrl()

    /** 别处（选段提问、答后讲解）先碰到了 401：讲解面板也回到配对，不再显示「已连上」。 */
    fun markRevoked() {
        identity?.let {
            stopped += it.key()
            runCatching { pairing.revoke(it) }
        }
        identity = null; revoked = true
    }

    open suspend fun load() {
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
            if (!authorized) { markRevoked(); loaded = true; return }
            identity = if (stale) null else saved
            revoked = stale || (saved == null && wasRevoked)
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
        val blocks = context?.sectionBlocks?.filterNot { it.heading }
            ?.takeIf { list -> list.isNotEmpty() && BookLessonWire.fits(list) && list.any { chosen.text.trim() in it.text || it.id == chosen.id } }
            ?: listOf(chosen)
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

    suspend fun ask(chosen: SourceBlock, question: String, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult =
        send(ExplainWire.question(requestId, chosen, question), listOf(chosen), requestId)

    open suspend fun fetchChapterCompanion(
        requestJson: JsonObject,
        blocks: List<SourceBlock> = emptyList(),
    ): ChapterCompanionFetchResult {
        val paired = identity ?: return if (revoked) {
            ChapterCompanionFetchResult.Failed(ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            ChapterCompanionFetchResult.Failed(ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val snapshot = if (blocks.isNotEmpty()) {
                withContext(Dispatchers.IO) { runCatching { imports?.snapshot(blocks) }.getOrNull() }
                    ?: return ChapterCompanionFetchResult.Failed("请先打开个人导入的书并允许讲解。")
            } else null
            val wire = if (snapshot != null) JsonObject(requestJson + ("localSource" to snapshot)) else requestJson
            val reply = http.companionChapter(wire)
            if (snapshot != null && withContext(Dispatchers.IO) { runCatching { imports?.snapshot(blocks) }.getOrNull() } != snapshot) {
                return ChapterCompanionFetchResult.Failed("保存的原文已变化，请重新打开这本书后重试。")
            }
            val body = reply.body
            val isRevoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && http.libraryAuthStatus() == 401)
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
            ChapterCompanionFetchResult.Failed("连不上学习服务，本地阅读与草稿已保留。")
        } finally {
            http.close()
        }
    }

    open suspend fun courseAlign(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.courseAlign(input) }

    open suspend fun courseTopicPack(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.courseTopicPack(input) }

    open suspend fun courseVision(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.courseVision(input) }

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

    open suspend fun askCollection(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.askCollection(input) }

    open suspend fun generateCourseMock(input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.generateCourseMock(input) }

    open suspend fun fetchStudyModePack(
        bookId: String,
        mode: String,
        input: JsonObject,
        onProgress: (chars: Int, firstTokenMs: Long?, delta: String) -> Unit = { _, _, _ -> },
    ): CourseServerCallResult = callCourseEndpoint { http -> http.streamStudyModePack(bookId, mode, input, onProgress) }

    open suspend fun fetchBookMapBatch(bookId: String, input: JsonObject): CourseServerCallResult =
        callCourseEndpoint { http -> http.fetchBookMapBatch(bookId, input) }

    private suspend fun callCourseEndpoint(
        block: suspend (CourseHttp) -> MockReply,
    ): CourseServerCallResult {
        val paired = identity ?: return if (revoked) {
            CourseServerCallResult(null, ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            CourseServerCallResult(null, ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val reply = block(http)
            val body = reply.body
            val isRevoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && http.libraryAuthStatus() == 401)
            if (identity != paired) return CourseServerCallResult(null, "连接已更换，请重试。")
            val trouble = if (isRevoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> {
                    markRevoked()
                    CourseServerCallResult(null, ExplainTrouble.revoked(sableLogin), trouble)
                }
                body == null -> CourseServerCallResult(null, ExplainTrouble.message(trouble, reply.error), trouble)
                else -> CourseServerCallResult(body, null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CourseServerCallResult(null, "连不上学习服务，已保留本地数据与草稿。")
        } finally {
            http.close()
        }
    }

    open suspend fun alignBooklistConcepts(requestJson: JsonObject): JsonObject? {
        val paired = identity ?: return null
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val reply = http.alignBooklistConcepts(requestJson)
            if (reply.statusCode == 401) {
                markRevoked()
                null
            } else reply.body
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            http.close()
        }
    }

    private suspend fun sendGuide(input: JsonObject, context: List<SourceBlock>, requestId: String): SectionGuideResult {
        val (body, refused) = sendRaw(input, context, requestId)
        if (refused != null) return SectionGuideResult.Refused(refused.message, refused.trouble)
        return ExplainWire.parseGuide(checkNotNull(body), context)
    }

    private suspend fun send(input: JsonObject, context: List<SourceBlock>, requestId: String): ExplainResult {
        val (body, refused) = sendRaw(input, context, requestId)
        if (refused != null) return refused
        return ExplainWire.parse(checkNotNull(body), context)
    }

    private suspend fun sendRaw(input: JsonObject, context: List<SourceBlock>, requestId: String): Pair<JsonObject?, ExplainResult.Refused?> {
        val paired = identity ?: return null to if (revoked) {
            ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), ExplainTrouble.Revoked)
        } else {
            ExplainResult.Refused(ExplainTrouble.UNPAIRED, ExplainTrouble.Unpaired)
        }
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val snapshot = withContext(Dispatchers.IO) { imports?.snapshot(context) }
            val kind = input["kind"]?.jsonPrimitive?.contentOrNull
            val sectionScoped = kind == "lesson" || kind == "guide" || kind == "structure" || input["sectionContext"] != null
            val wire = if (sectionScoped) {
                if (snapshot == null) return null to ExplainResult.Refused("请先打开个人导入的书并允许讲解。")
                try { BookLessonWire.withSource(input, snapshot) }
                catch (_: IllegalArgumentException) { return null to ExplainResult.Refused(BookLessonWire.TOO_LONG) }
            } else if (snapshot == null) input else JsonObject(input + ("localSource" to snapshot))
            val reply = http.explainLibrary(wire)
            if (snapshot != null && withContext(Dispatchers.IO) { imports!!.snapshot(context) } != snapshot)
                return null to ExplainResult.Refused("保存的原文已变化，请重新打开这本书后重试。")
            val body = reply.body
            // A 403 can mean either a revoked device or an unauthorized passage. Check only
            // the original server with the original identity; never resend the book text.
            val revoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && http.libraryAuthStatus() == 401)
            // A late response must not invalidate a pairing made while the request was in flight.
            if (identity != paired) return null to ExplainResult.Refused("连接已更换，请重新选择这段后再试。")
            val trouble = if (revoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> { markRevoked(); null to ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), trouble) }
                body == null -> null to ExplainResult.Refused(ExplainTrouble.message(trouble, reply.error), trouble)
                else -> body to null
            }
        } catch (cancelled: CancellationException) {
            runCatching { http.cancelLibraryExplain(requestId) }
            throw cancelled
        } catch (_: Exception) {
            null to ExplainResult.Refused("保存的原文或发送许可无法核对，请重新打开这本书后重试。")
        } finally { http.close() }
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
                val http = CourseHttp(pinned.serverUrl, pinned.credentials.deviceId, pinned.credentials.token, "library")
                return try {
                    val cap = http.unifiedBooksCapability(pinned.credentials.role)
                    ensurePinned()
                    when (cap) {
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
                } finally {
                    http.close()
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
                val http = CourseHttp(pinned.serverUrl, pinned.credentials.deviceId, pinned.credentials.token, "library")
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
                val http = CourseHttp(pinned.serverUrl, pinned.credentials.deviceId, pinned.credentials.token, "library")
                return try {
                    val reply = http.unifiedBookStatus(shelfBookId)
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

            override suspend fun deleteBook(shelfBookId: String): Boolean {
                ensurePinned()
                val http = CourseHttp(pinned.serverUrl, pinned.credentials.deviceId, pinned.credentials.token, "library")
                return try {
                    val reply = http.deleteUnifiedBook(shelfBookId)
                    ensurePinned()
                    when {
                        reply.statusCode == 401 -> {
                            markRevoked()
                            false
                        }
                        reply.statusCode == 404 -> true
                        else -> reply.body != null
                    }
                } finally {
                    http.close()
                }
            }
        }

        override suspend fun activeAccountKey(): String? = currentIdentity()?.let(::fallbackAccountKey)

        override suspend fun openSession(): UnifiedBookRemoteSession? = currentIdentity()?.let(::pinnedSession)

        override suspend fun checkCapability(): UnifiedBookCapability =
            openSession()?.checkCapability() ?: UnifiedBookCapability.Unauthorized

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

        override suspend fun deleteBook(shelfBookId: String): Boolean =
            openSession()?.deleteBook(shelfBookId) ?: false
    }
}
