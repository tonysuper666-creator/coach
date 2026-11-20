package com.coach.desktop.books

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.android.KeystoreCredentials
import com.coach.client.CourseHttp
import com.coach.client.PairedCredentials
import com.coach.client.PairingClient
import com.coach.client.validateCoachBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal data class AndroidBookIdentity(val serverUrl: String, val credentials: PairedCredentials)

internal object AndroidBookConnection {
    private data class Route(val context: Context, val url: String, val store: KeystoreCredentials)
    @Volatile private var route: Route? = null

    fun bind(context: Context, url: String, store: KeystoreCredentials) {
        route = Route(context.applicationContext, url, store)
    }

    private fun normalize(address: String): String {
        val trimmed = address.trim().trimEnd('/')
        val uri = URI(if (trimmed.contains("://")) trimmed else "http://$trimmed")
        val selected = if (uri.scheme == "http" && uri.port == -1 && uri.host != null &&
            uri.rawUserInfo == null && uri.rawPath.orEmpty().isBlank() && uri.rawQuery == null && uri.rawFragment == null) {
            URI("http", null, uri.host, 18791, null, null, null).toString()
        } else uri.toString()
        validateCoachBaseUrl(selected)
        return selected
    }

    private fun store(address: String): KeystoreCredentials {
        val active = requireNotNull(route)
        if (address == active.url) return active.store
        val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return KeystoreCredentials(active.context, address, "paired-book-$key")
    }

    fun load(address: String): AndroidBookIdentity? = runCatching {
        val normalized = normalize(address)
        store(normalized).load()?.let { AndroidBookIdentity(normalized, it) }
    }.getOrNull()

    suspend fun pair(address: String, code: String): AndroidBookIdentity {
        val normalized = normalize(address)
        val client = PairingClient(normalized)
        try {
            val credentials = client.pair(code, "安卓")
            store(normalized).save(credentials)
            val active = requireNotNull(route)
            check(active.context.getSharedPreferences("coach-book-connection", Context.MODE_PRIVATE).edit()
                .putString("address", normalized).commit())
            return AndroidBookIdentity(normalized, credentials)
        } finally { client.close() }
    }

    /** 与安卓登录门同一个朋友服务地址；没配地址的安装包照旧只能用配对码。 */
    suspend fun sable(open: suspend (String, String) -> Unit): AndroidBookIdentity {
        val friendUrl = com.coach.android.BuildConfig.COACH_FRIEND_URL
        if (friendUrl.isBlank()) throw com.coach.client.SableLoginError("此安装包尚未配置朋友服务地址。")
        val normalized = normalize(friendUrl)
        val client = PairingClient(normalized)
        try {
            val credentials = client.sable(open)
            store(normalized).save(credentials)
            val active = requireNotNull(route)
            check(active.context.getSharedPreferences("coach-book-connection", Context.MODE_PRIVATE).edit()
                .putString("address", normalized).commit())
            return AndroidBookIdentity(normalized, credentials)
        } finally { client.close() }
    }

    fun preferredAddress(): String = route?.let { active ->
        active.context.getSharedPreferences("coach-book-connection", Context.MODE_PRIVATE)
            .getString("address", active.url).orEmpty().ifBlank { active.url }
    }.orEmpty()

    fun clear(identity: AndroidBookIdentity) { store(identity.serverUrl).clear() }
}

internal class BookExplainer(library: TextbookLibrary? = null) {
    private val imports = library?.let(::ImportedBookWire)
    var identity by mutableStateOf<AndroidBookIdentity?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var defaultAddress by mutableStateOf("")
        private set
    var revoked by mutableStateOf(false)
        private set
    /** 安卓的配对页一直放着「用 Sable 账号登录」，讲解面板跟它一致。 */
    val sableLogin: Boolean get() = true

    fun markRevoked() {
        identity?.let { revokedIdentity -> runCatching { AndroidBookConnection.clear(revokedIdentity) } }
        identity = null
        revoked = true
    }

    suspend fun load() {
        val (url, credentials) = withContext(Dispatchers.IO) {
            val address = AndroidBookConnection.preferredAddress()
            address to AndroidBookConnection.load(address)
        }
        defaultAddress = url
        identity = credentials
        if (identity != null) revoked = false
        loaded = true
    }

    suspend fun pair(address: String, code: String): Boolean = try {
        identity = withContext(Dispatchers.IO) { AndroidBookConnection.pair(address, code) }
        defaultAddress = identity?.serverUrl.orEmpty()
        revoked = false
        true
    } catch (c: CancellationException) { throw c } catch (_: Exception) { false }

    suspend fun sable(open: suspend (String, String) -> Unit) {
        identity = AndroidBookConnection.sable(open)
        defaultAddress = identity?.serverUrl.orEmpty()
        revoked = false
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

    suspend fun ask(chosen: SourceBlock, question: String, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult =
        send(ExplainWire.question(requestId, chosen, question), listOf(chosen), requestId)

    private suspend fun send(input: JsonObject, context: List<SourceBlock>, requestId: String): ExplainResult {
        val paired = identity ?: return ExplainResult.Refused("还没连上你的 Mac。先在讲解页配对。")
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
            val trouble = ExplainTrouble.of(reply.statusCode, reply.code)
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
