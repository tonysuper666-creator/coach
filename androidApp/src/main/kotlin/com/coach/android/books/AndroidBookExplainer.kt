package com.coach.desktop.books

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.android.BuildConfig
import com.coach.android.CoachCredentialStore
import com.coach.android.KeystoreCredentials
import com.coach.client.CourseHttp
import com.coach.client.DeviceAuthorizationException
import com.coach.client.HttpLedgerTransport
import com.coach.client.PairedCredentials
import com.coach.client.PairingClient
import com.coach.client.SABLE_ORIGIN
import com.coach.client.SableLoginError
import com.coach.client.validateCoachBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor

/** Deliberately not a data class: never include serverUrl, deviceId, or token in toString(). */
internal class AndroidBookIdentity(val serverUrl: String, val credentials: PairedCredentials) {
    override fun equals(other: Any?): Boolean = other is AndroidBookIdentity &&
        serverUrl == other.serverUrl &&
        credentials.deviceId == other.credentials.deviceId &&
        credentials.token == other.credentials.token &&
        credentials.role == other.credentials.role

    override fun hashCode(): Int {
        var result = serverUrl.hashCode()
        result = 31 * result + credentials.deviceId.hashCode()
        result = 31 * result + credentials.token.hashCode()
        result = 31 * result + credentials.role.hashCode()
        return result
    }

    override fun toString(): String = "AndroidBookIdentity(role=${credentials.role})"
}

internal object AndroidBookConnection {
    private const val PREFS_NAME = "coach-book-connection"
    private const val KEY_MODE = "mode"
    private const val KEY_ADDRESS = "address"
    private const val MODE_FRIEND = "friend"
    private const val MODE_CUSTOM = "custom"

    private data class Route(
        val context: Context,
        val url: String,
        val store: CoachCredentialStore,
        val friendUrl: String,
        val sableIssuer: String,
        val credentialFactory: (Context, String, String) -> CoachCredentialStore,
    )

    @Volatile private var route: Route? = null

    fun bind(
        context: Context,
        url: String,
        store: CoachCredentialStore,
        friendUrl: String = BuildConfig.COACH_FRIEND_URL,
        sableIssuer: String = SABLE_ORIGIN,
        credentialFactory: (Context, String, String) -> CoachCredentialStore = ::KeystoreCredentials,
    ) {
        route = Route(
            context = context.applicationContext,
            url = normalizeOptionalOrigin(url),
            store = store,
            friendUrl = normalizeOptionalOrigin(friendUrl),
            sableIssuer = sableIssuer,
            credentialFactory = credentialFactory,
        )
    }

    internal fun resetForTest() {
        BookExplainer.clearStoppedForTest()
        route = null
    }

    internal fun normalizeOptionalOrigin(origin: String): String {
        val trimmed = origin.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return runCatching { normalizeUnchecked(trimmed) }.getOrDefault("")
    }

    fun configuredFriendUrl(): String =
        route?.friendUrl ?: normalizeOptionalOrigin(BuildConfig.COACH_FRIEND_URL)

    fun hasBuiltInAddress(): Boolean =
        route?.let { it.friendUrl.isNotEmpty() || it.url.isNotEmpty() } == true

    private fun fallbackPairAddress(): String =
        route?.let { it.friendUrl.ifEmpty { it.url } }.orEmpty()

    private fun normalizeUnchecked(address: String): String {
        val trimmed = address.trim().trimEnd('/')
        val hasScheme = trimmed.contains("://")
        val uri = URI(if (hasScheme) trimmed else "http://$trimmed")
        require(!uri.host.isNullOrBlank()) { "Invalid Coach server address" }
        val selected = if (!hasScheme && uri.scheme == "http" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawPath.orEmpty().isBlank() && uri.rawQuery == null && uri.rawFragment == null) {
            URI("http", null, uri.host.lowercase(), 18791, null, null, null).toString()
        } else {
            URI(uri.scheme?.lowercase(), uri.rawUserInfo, uri.host.lowercase(), uri.port, uri.rawPath, uri.rawQuery, uri.rawFragment).toString()
        }
        validateCoachBaseUrl(selected)
        return selected
    }

    private fun normalize(address: String): String {
        val selected = normalizeUnchecked(address)
        val active = requireNotNull(route)
        if (active.context.packageName != "com.coach.android") {
            val allowed = listOf(active.url, active.friendUrl).filter(String::isNotEmpty).map(::URI)
            require(URI(selected) in allowed) { "Use this build's isolated Coach server" }
        }
        return selected
    }

    private fun store(address: String): CoachCredentialStore {
        val active = requireNotNull(route)
        val clean = address.trim().trimEnd('/')
        if (active.friendUrl.isNotEmpty() && clean == active.friendUrl) {
            return active.credentialFactory(active.context, active.friendUrl, "paired-friend")
        }
        if (active.url.isNotEmpty() && clean == active.url) return active.store
        val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return active.credentialFactory(active.context, address, "paired-book-$key")
    }

    fun load(address: String): Pair<AndroidBookIdentity?, Boolean> = runCatching {
        val normalized = normalize(address)
        val (credentials, revoked) = store(normalized).bookPairing()
        credentials?.let { AndroidBookIdentity(normalized, it) } to revoked
    }.getOrDefault(null to false)

    fun load(): Pair<AndroidBookIdentity?, Boolean> {
        val active = route ?: return null to false
        val prefs = active.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, null)
        val customAddress = prefs.getString(KEY_ADDRESS, null)
        if (mode == MODE_CUSTOM && !customAddress.isNullOrBlank()) {
            return load(customAddress)
        }
        if (active.friendUrl.isNotEmpty()) {
            val friend = load(active.friendUrl)
            if (friend.first != null || friend.second || mode == MODE_FRIEND) return friend
        }
        if (mode == null && !customAddress.isNullOrBlank()) {
            return load(customAddress)
        }
        if (active.url.isNotEmpty()) {
            return load(active.url)
        }
        return null to false
    }

    private fun saveFriend(url: String, credentials: PairedCredentials) {
        val active = requireNotNull(route)
        store(url).save(credentials)
        syncFriendSaved(url, credentials, active.context)
    }

    internal fun syncFriendSaved(url: String, credentials: PairedCredentials, contextOverride: Context? = null) {
        val ctx = contextOverride ?: route?.context ?: return
        check(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, MODE_FRIEND)
            .remove(KEY_ADDRESS)
            .commit())
        BookExplainer.clearStopped(url, credentials)
    }

    private fun saveCustom(url: String, credentials: PairedCredentials) {
        val active = requireNotNull(route)
        store(url).save(credentials)
        check(active.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, MODE_CUSTOM)
            .putString(KEY_ADDRESS, url)
            .commit())
        BookExplainer.clearStopped(url, credentials)
    }

    fun revoke(identity: AndroidBookIdentity) {
        BookExplainer.markStopped(identity)
        store(identity.serverUrl).revokeIfCurrent(identity.credentials)
    }

    internal fun markFriendRevoked(url: String, credentials: PairedCredentials) {
        if (url.isBlank()) return
        val normalized = normalizeOptionalOrigin(url).ifEmpty { url }
        revoke(AndroidBookIdentity(normalized, credentials))
    }

    /** Offline is not revocation. Only an authenticated endpoint's explicit 401 retires a pairing. */
    suspend fun stillAuthorized(identity: AndroidBookIdentity, startup: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (identity.credentials.role == "friend") {
            val client = PairingClient(identity.serverUrl)
            try {
                client.friendAuthorized(identity.credentials, startup).also { authorized ->
                    if (!authorized) runCatching { revoke(identity) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            } finally {
                client.close()
            }
        } else {
            val transport = HttpLedgerTransport(identity.serverUrl, identity.credentials.deviceId, identity.credentials.token)
            try {
                transport.hello(null)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DeviceAuthorizationException) {
                runCatching { revoke(identity) }
                false
            } catch (_: Exception) {
                true
            } finally {
                transport.close()
            }
        }
    }

    suspend fun pair(address: String, code: String): AndroidBookIdentity = withContext(Dispatchers.IO) {
        val active = requireNotNull(route)
        val rawTarget = address.trim().ifBlank { fallbackPairAddress() }
        val normalized = normalize(rawTarget)
        val deviceName = Build.MODEL.orEmpty().trim().take(80).ifBlank { "安卓" }
        val client = PairingClient(normalized)
        try {
            val credentials = client.pair(code, deviceName)
            if (credentials.role == "friend") check(client.friendAuthorized(credentials))
            else {
                val transport = HttpLedgerTransport(normalized, credentials.deviceId, credentials.token)
                try { transport.hello(null) } finally { transport.close() }
            }
            if (active.friendUrl.isNotEmpty() && normalized == active.friendUrl) {
                saveFriend(normalized, credentials)
            } else {
                saveCustom(normalized, credentials)
            }
            AndroidBookIdentity(normalized, credentials)
        } finally { client.close() }
    }

    /** 与安卓登录门同一个朋友服务地址；没配地址的安装包照旧只能用配对码。 */
    suspend fun sable(open: suspend (String, String) -> Unit): AndroidBookIdentity {
        val active = requireNotNull(route) { "请先打开应用后再登录。" }
        if (active.friendUrl.isEmpty()) throw SableLoginError("此安装包尚未配置 Coach 服务地址。")
        val callbackDispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Main.immediate
        return withContext(Dispatchers.IO) {
            val client = PairingClient(active.friendUrl)
            try {
                val credentials = client.sable({ uri, code ->
                    withContext(callbackDispatcher) { open(uri, code) }
                }, active.sableIssuer)
                saveFriend(active.friendUrl, credentials)
                AndroidBookIdentity(active.friendUrl, credentials)
            } finally { client.close() }
        }
    }
}

internal class BookExplainer(library: TextbookLibrary? = null) {
    private val imports = library?.let(::ImportedBookWire)
    var identity by mutableStateOf<AndroidBookIdentity?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    /** 安卓始终不在文本框预填朋友服务地址，避免私密地址出现在界面与截图中。 */
    var defaultAddress by mutableStateOf("")
        private set
    var revoked by mutableStateOf(false)
        private set
    /** 安卓的配对页一直放着「用 Sable 账号登录」，讲解面板跟它一致。 */
    val sableLogin: Boolean get() = true
    val hasBuiltInAddress: Boolean get() = AndroidBookConnection.hasBuiltInAddress()

    fun markRevoked() {
        identity?.let { revokedIdentity ->
            stopped += revokedIdentity.key()
            runCatching { AndroidBookConnection.revoke(revokedIdentity) }
        }
        identity = null
        revoked = true
    }

    suspend fun load() {
        val pairing = withContext(Dispatchers.IO) { AndroidBookConnection.load() }
        defaultAddress = ""
        val saved = pairing.first
        val stale = saved != null && saved.key() in stopped
        val authorized = saved == null || saved.credentials.role != "friend" || withContext(Dispatchers.IO) {
            AndroidBookConnection.stillAuthorized(saved)
        }
        if (!authorized) {
            stopped += saved.key()
            withContext(Dispatchers.IO) { runCatching { AndroidBookConnection.revoke(saved) } }
            identity = null
            revoked = true
            loaded = true
            return
        }
        identity = if (stale) null else saved
        revoked = stale || (saved == null && pairing.second)
        loaded = true
    }

    suspend fun pair(address: String, code: String): Boolean = try {
        identity = AndroidBookConnection.pair(address, code)
        defaultAddress = ""
        revoked = false
        true
    } catch (c: CancellationException) { throw c }
    catch (e: Exception) {
        Log.w("CoachBookPair", "pair failed: ${e.javaClass.simpleName}")
        false
    }

    suspend fun sable(open: suspend (String, String) -> Unit) {
        try {
            identity = AndroidBookConnection.sable(open)
            defaultAddress = ""
            revoked = false
        } catch (c: CancellationException) { throw c }
        catch (e: SableLoginError) { throw e }
        catch (e: Exception) {
            Log.w("CoachSable", "sable failed: ${e.javaClass.simpleName}")
            throw e
        }
    }

    suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        val context = ExplainWire.context(blocks, chosen)
        return send(ExplainWire.request(requestId, chosen, context), context, requestId)
    }

    suspend fun explainLesson(blocks: List<SourceBlock>, bookPages: Int,
        requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        if (!BookLessonWire.fits(blocks)) return ExplainResult.Refused(BookLessonWire.TOO_LONG)
        return send(BookLessonWire.request(requestId, blocks, bookPages), blocks, requestId)
    }

    suspend fun explainSectionGuide(
        context: ExplainSectionContext,
        bookPages: Int,
        requestId: String = UUID.randomUUID().toString().replace("-", ""),
    ): SectionGuideResult {
        val body = context.sectionBlocks.filterNot { it.heading }
        if (!BookLessonWire.fits(body)) return SectionGuideResult.Refused(BookLessonWire.TOO_LONG)
        val wire = ExplainWire.sectionGuideRequest(requestId, body, bookPages, context)
        return sendGuide(wire, body, requestId)
    }

    suspend fun explainSelection(
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

    suspend fun structureUnstructuredBook(
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

    suspend fun fetchChapterCompanion(
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
            val reply = withContext(Dispatchers.IO) { http.companionChapter(wire) }
            if (snapshot != null && withContext(Dispatchers.IO) { runCatching { imports?.snapshot(blocks) }.getOrNull() } != snapshot) {
                return ChapterCompanionFetchResult.Failed("保存的原文已变化，请重新打开这本书后重试。")
            }
            val body = reply.body
            val isRevoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && withContext(Dispatchers.IO) { http.libraryAuthStatus() } == 401)
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
            val kind = input["kind"]?.jsonPrimitive?.contentOrNull ?: "explain"
            val sectionScoped = kind == "lesson" || kind == "guide" || kind == "structure" || input["sectionContext"] != null
            val wire = if (sectionScoped) {
                if (snapshot == null) return null to ExplainResult.Refused("请先打开个人导入的书并允许讲解。")
                try { BookLessonWire.withSource(input, snapshot) }
                catch (_: IllegalArgumentException) { return null to ExplainResult.Refused(BookLessonWire.TOO_LONG) }
            } else if (snapshot == null) input else JsonObject(input + ("localSource" to snapshot))
            val reply = withContext(Dispatchers.IO) { http.explainLibrary(wire) }
            if (BuildConfig.DEBUG) {
                Log.i("CoachBookHttp", "kind=$kind requestId=$requestId status=${reply.statusCode ?: "unreachable"}")
            }
            if (snapshot != null && withContext(Dispatchers.IO) { imports!!.snapshot(context) } != snapshot)
                return null to ExplainResult.Refused("保存的原文已变化，请重新打开这本书后重试。")
            val body = reply.body
            // A 403 can mean either a revoked device or an unauthorized passage. Check only
            // the original server with the original identity; never resend the book text.
            val isRevoked = reply.statusCode == 401 ||
                (reply.statusCode == 403 && withContext(Dispatchers.IO) { http.libraryAuthStatus() } == 401)
            // A late response must not invalidate a pairing made while the request was in flight.
            if (identity != paired) return null to ExplainResult.Refused("连接已更换，请重新选择这段后再试。")
            val trouble = if (isRevoked) ExplainTrouble.Revoked else ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> {
                    stopped += paired.key()
                    withContext(Dispatchers.IO) { runCatching { AndroidBookConnection.revoke(paired) } }
                    load()
                    null to if (identity == null) ExplainResult.Refused(ExplainTrouble.revoked(sableLogin), trouble)
                    else ExplainResult.Refused("配对已更新，请再试一次。", ExplainTrouble.Other)
                }
                body == null -> null to ExplainResult.Refused(ExplainTrouble.message(trouble, reply.error), trouble)
                else -> body to null
            }
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO) { runCatching { http.cancelLibraryExplain(requestId) } }
            throw cancelled
        } catch (_: Exception) {
            null to ExplainResult.Refused("保存的原文或发送许可无法核对，请重新打开这本书后重试。")
        } finally { http.close() }
    }

    internal companion object {
        /** 原文页、答后讲解、选段提问各有一个 explainer；谁先碰到 401，其他的读到同一份凭据时也算停用。 */
        private val stopped: MutableSet<Triple<String, String, String>> = ConcurrentHashMap.newKeySet()
        private fun AndroidBookIdentity.key() = Triple(serverUrl, credentials.deviceId, credentials.token)

        fun markStopped(identity: AndroidBookIdentity) {
            stopped += identity.key()
        }

        fun clearStopped(url: String, credentials: PairedCredentials) {
            stopped.remove(Triple(url, credentials.deviceId, credentials.token))
        }

        fun clearStoppedForTest() {
            stopped.clear()
        }
    }
}
