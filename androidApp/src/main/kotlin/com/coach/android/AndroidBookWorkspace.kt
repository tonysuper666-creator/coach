package com.coach.android

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.AndroidBookConnection
import com.coach.desktop.books.AndroidBookIdentity
import com.coach.desktop.books.LocalBookRelogin
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TextbookLoginScreen
import com.coach.ui.CoachTheme
import com.coach.ui.adaptive.Hinge
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private data class BookCopyProgress(val bytes: Long, val total: Long?)
internal class PickedBookCancelled : Exception()

/**
 * Startup login gate for the Android textbook workspace (TON-1782):
 * - First launch or missing identity shows the full-screen login page (Sable primary, pairing code tucked below).
 * - Existing identity enters the bookshelf/last reading spot immediately without waiting on network, so offline use is never blocked.
 * - Explicit 401 revocation clears credentials and returns to the login page with a clear explanation.
 */
@Composable
internal fun AndroidTextbookGate(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable (AndroidBookIdentity) -> Unit,
) {
    var identity by remember { mutableStateOf<AndroidBookIdentity?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var revokedReason by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val (saved, wasRevoked) = withContext(Dispatchers.IO) { AndroidBookConnection.load() }
            identity = saved
            if (saved == null && wasRevoked) {
                revokedReason = "授权已撤销，请重新登录。"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            revokedReason = "无法读取登录信息，请重新登录。"
        } finally {
            initialized = true
        }
    }

    LaunchedEffect(identity) {
        val current = identity ?: return@LaunchedEffect
        var startup = true
        while (currentCoroutineContext().isActive) {
            val authorized = try {
                AndroidBookConnection.stillAuthorized(current, startup)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            }
            if (!authorized) {
                identity = null
                revokedReason = "授权已撤销，请重新登录。"
                break
            }
            startup = false
            delay(5_000)
        }
    }

    val paired = identity
    if (paired != null) {
        val onRelogin: (String?) -> Unit = remember(paired) {
            { reason ->
                val active = identity
                identity = null
                revokedReason = reason ?: "授权已撤销，请重新登录。"
                if (active != null) {
                    scope.launch(Dispatchers.IO) { runCatching { AndroidBookConnection.revoke(active) } }
                }
            }
        }
        CompositionLocalProvider(LocalBookRelogin provides onRelogin) {
            key(paired.credentials.deviceId, paired.credentials.token) {
                content(paired)
            }
        }
    } else {
        CoachTheme(darkTheme) {
            if (!initialized) {
                Box(
                    Modifier.fillMaxSize().safeDrawingPadding().testTag("android-login-loading"),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                TextbookLoginScreen(
                    sableLogin = true,
                    defaultAddress = "",
                    hasBuiltInAddress = AndroidBookConnection.hasBuiltInAddress(),
                    revokedReason = revokedReason,
                    onSableLogin = { open ->
                        val signedIn = AndroidBookConnection.sable(open)
                        revokedReason = null
                        identity = signedIn
                    },
                    onPair = { address, code ->
                        try {
                            val signedIn = AndroidBookConnection.pair(address, code)
                            revokedReason = null
                            identity = signedIn
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
    }
}

@Composable
internal fun AndroidBookWorkspace(onPersonalCourse: () -> Unit, hinge: Hinge? = null) {
    val context = LocalContext.current.applicationContext
    val initialized by produceState<Result<TextbookController>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PDFBoxResourceLoader.init(context)
                Class.forName("org.sqlite.JDBC")
                TextbookController(TextbookLibrary(context.filesDir.toPath().resolve("books"), streamPdfExtras = true, lazyPdfExtras = true))
            }
        }
    }
    var openError by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableStateOf<BookCopyProgress?>(null) }
    var copyCancel by remember { mutableStateOf<AtomicBoolean?>(null) }
    var exportTarget by remember { mutableStateOf<Pair<Textbook, Int>?>(null) }
    val pending = remember { mutableListOf<CompletableDeferred<Uri?>>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
        pending.firstOrNull()?.complete(result)
    }
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        AndroidTextbookGate {
            val library = initialized?.getOrNull()
            if (library == null) {
                CoachTheme(isSystemInDarkTheme()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        if (initialized == null) CircularProgressIndicator()
                        else Text("书架暂时无法打开，请关闭应用后重试。")
                    }
                }
                return@AndroidTextbookGate
            }
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                WindowsTextbookHost(library, onPersonalCourse = onPersonalCourse, hinge = hinge, onExportNotes = { selectedBook, currentPage -> exportTarget = selectedBook to currentPage }, releasePickedBook = { selected ->
                    val directory = selected.parent
                    runCatching { Files.deleteIfExists(selected); Files.deleteIfExists(directory) }
                }, chooseBook = pick@{
                    if (pending.isNotEmpty() || copyProgress != null) return@pick null
                    val result = CompletableDeferred<Uri?>()
                    pending.add(result)
                    try {
                        picker.launch(arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN, "application/*", "image/*", "text/*"))
                        result.await()?.let { uri ->
                            val cancelled = AtomicBoolean(false)
                            copyCancel = cancelled
                            copyProgress = BookCopyProgress(0, null)
                            try {
                                copyPickedBook(context, uri, cancelled) { bytes, total ->
                                    copyProgress = BookCopyProgress(bytes, total)
                                }
                            } finally {
                                copyCancel = null
                                copyProgress = null
                            }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: PickedBookCancelled) { null }
                    catch (_: Exception) { openError = true; null }
                    finally { pending.remove(result) }
                })
                if (openError) AlertDialog(onDismissRequest = { openError = false },
                    text = { Text("无法读取教材，请确认文件可用且不超过 256 MB。") },
                    confirmButton = { TextButton(onClick = { openError = false }) { Text("知道了") } })
                copyProgress?.let { progress ->
                    AlertDialog(onDismissRequest = {}, title = { Text("正在准备教材") },
                        text = {
                            Column {
                                val total = progress.total
                                if (total != null && total > 0) {
                                    LinearProgressIndicator(progress = { (progress.bytes.toFloat() / total).coerceIn(0f, 1f) })
                                    Text("已读取 ${progress.bytes / 1024 / 1024} / ${total / 1024 / 1024} MB")
                                } else {
                                    LinearProgressIndicator()
                                    Text("已读取 ${progress.bytes / 1024 / 1024} MB")
                                }
                            }
                        }, confirmButton = {
                            TextButton(onClick = { copyCancel?.set(true) }) { Text("取消导入") }
                        })
                }
                exportTarget?.let { (selectedBook, currentPage) ->
                    AndroidBookNotesExportDialog(
                        book = selectedBook,
                        library = library.library,
                        currentPage = currentPage,
                        onDismiss = { exportTarget = null },
                    )
                }
            }
        }
    }
}

internal suspend fun copyPickedBook(
    context: Context, uri: Uri, cancelled: AtomicBoolean = AtomicBoolean(false),
    onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
): Path = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        .orEmpty().substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\x00-\\x1F]"), "").trim()
    val filename = displayName.take(150).takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: "教材"
    val totalSize = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null }
    }.getOrNull()
    val directory = Files.createTempDirectory(context.cacheDir.toPath(), "picked-book-")
    val destination = directory.resolve(filename)
    try {
        val input = requireNotNull(resolver.openInputStream(uri)) { "无法读取这份教材。" }
        input.use { source -> Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var lastReported = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) throw PickedBookCancelled()
                val n = source.read(buffer)
                if (n < 0) break
                total += n
                require(total <= 256L * 1024 * 1024) { "请选择不超过 256 MB 的教材。" }
                output.write(buffer, 0, n)
                if (total - lastReported >= 256 * 1024) {
                    withContext(Dispatchers.Main) { onProgress(total, totalSize) }
                    lastReported = total
                }
            }
            if (cancelled.get()) throw PickedBookCancelled()
            withContext(Dispatchers.Main) { onProgress(total, totalSize) }
            if (cancelled.get()) throw PickedBookCancelled()
        } }
        destination
    } catch (failure: Exception) {
        Files.deleteIfExists(destination)
        Files.deleteIfExists(directory)
        throw failure
    }
}
