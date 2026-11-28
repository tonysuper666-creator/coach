package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.BookScreen
import com.coach.ui.v7.V7TextButton
import coach.design.tokens.CoachV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Reading and answering stay quiet. Home, course shelves and the review list may show one line. */
internal fun updateWouldInterrupt(screen: BookScreen): Boolean = when (screen) {
    BookScreen.READER, BookScreen.LEARNING, BookScreen.REVIEWING, BookScreen.COURSE_MOCK -> true
    else -> false
}

/** One line, and only when nobody is reading or answering. Never a dialog. */
internal fun shouldShowUpdateLine(idle: Boolean, ready: Boolean, failed: Boolean, deferred: Boolean): Boolean =
    idle && !deferred && (ready || failed)

private const val UPDATE_SKIP_FILE = "update-skip-version"

/** 用户点过「这一版不再提示」的版本号；只跳一个版本，下一版还会说。 */
internal fun readSkippedUpdateVersion(libraryDir: Path): String =
    runCatching { Files.readString(libraryDir.resolve(UPDATE_SKIP_FILE)).trim() }.getOrDefault("")

internal class UpdateSession(
    private val libraryDir: Path,
    private val updatesDir: Path,
    private val save: suspend () -> Boolean,
    private val close: () -> Unit,
    private val scope: CoroutineScope,
    private val launch: (Path) -> Boolean,
) {
    var ready by mutableStateOf<WindowsRelease?>(null)
        private set
    var staged by mutableStateOf<Path?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var deferred by mutableStateOf(false)
        private set
    var dismissed by mutableStateOf(false)
        private set
    var skippedVersion by mutableStateOf(readSkippedUpdateVersion(libraryDir))
        private set
    var busy by mutableStateOf(false)
        private set
    var rollbackStatus by mutableStateOf("")
        private set
    var rollbackBusy by mutableStateOf(false)
        private set

    fun offer(release: WindowsRelease, msi: Path) {
        ready = release
        staged = msi
        message = ""
        // A fresh offer is a new fact: show it again unless this exact version was skipped.
        dismissed = false
    }

    fun fail(text: String) {
        ready = null
        staged = null
        message = text
    }

    fun current() {
        ready = null
        staged = null
        message = ""
    }

    fun defer() {
        deferred = true
    }

    /** 关掉这一条，这一轮不再出现；安装或检查仍在后台继续。 */
    fun dismiss() {
        dismissed = true
    }

    /** 关的时候选了「这一版不再提示」：记住版本号，下一版照常提醒。 */
    fun skipVersion() {
        val version = ready?.version ?: return
        dismissed = true
        skippedVersion = version
        runCatching {
            Files.createDirectories(libraryDir)
            Files.writeString(libraryDir.resolve(UPDATE_SKIP_FILE), version)
        }
    }

    fun install() {
        val release = ready ?: return
        val msi = staged ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                WindowsRollback.captureBeforeInstall(libraryDir, release.version)
                message = "正在保存学习进度…"
                if (!save()) {
                    message = UPDATE_NOT_SAVED
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (!launch(msi)) {
                    message = UPDATE_INSTALLER
                    return@launch
                }
                WindowsRollback.retainInstallers(updatesDir, msi, release.version, runningAppVersion())
                ready = null
                close()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                fail(updateFailureMessage(error))
            } finally {
                busy = false
            }
        }
    }

    fun noteRollback(text: String) {
        rollbackStatus = text
    }

    fun rollback() {
        if (rollbackBusy) return
        rollbackBusy = true
        rollbackStatus = ""
        scope.launch {
            val status = try {
                if (!save()) UPDATE_NOT_SAVED
                else withContext(Dispatchers.IO) { WindowsRollback.rollback(libraryDir, updatesDir) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                ROLLBACK_FAILED
            }
            rollbackBusy = false
            if (status.isEmpty()) close() else rollbackStatus = status
        }
    }

    suspend fun fetchPrevious(update: WindowsUpdate) {
        if (Files.isRegularFile(updatesDir.resolve("previous.msi"))) return
        val manifest = withContext(Dispatchers.IO) { update.readManifest() }
        val previous = update.previousRelease(manifest) ?: return
        val stagedFile = withContext(Dispatchers.IO) { update.download(previous, updatesDir) }
        WindowsRollback.acceptPrevious(updatesDir, stagedFile, previous.version)
    }
}

@Composable
internal fun rememberWindowsUpdateSession(
    libraryDir: Path,
    save: suspend () -> Boolean,
    close: () -> Unit,
    checkRequest: Int = 0,
    onResult: (String) -> Unit = {},
    updatesDir: Path = windowsUpdatesDirectory(),
    launch: (Path) -> Boolean = ::startWindowsInstaller,
): UpdateSession {
    val scope = rememberCoroutineScope()
    val session = remember(libraryDir, updatesDir) {
        UpdateSession(libraryDir, updatesDir, save, close, scope, launch).also { created ->
            WindowsRollback.consumeRollbackFailure(libraryDir)?.let { created.noteRollback(it) }
        }
    }
    LaunchedEffect(session, checkRequest) {
        val manual = checkRequest > 0
        val update = WindowsUpdate.installed() ?: run {
            if (manual) onResult("此安装包没有可用的更新渠道。")
            return@LaunchedEffect
        }
        try {
            val release = withContext(Dispatchers.IO) { update.check() }
            if (release != null) {
                if (manual) onResult("有新版本 ${release.version}，正在校验安装包…")
                val msi = withContext(Dispatchers.IO) { update.download(release, updatesDir) }
                session.offer(release, msi)
                if (manual) onResult("有新版本 ${release.version}")
            } else {
                session.current()
                if (manual) onResult("已经是最新版本")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            session.fail(updateFailureMessage(error))
            if (manual) onResult("无法完成更新检查，请重试。")
        }
        try {
            session.fetchPrevious(update)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // The previous package is optional. The current version keeps running.
        }
    }
    return session
}

/**
 * 顶栏下面的一行提示，占自己的位置，不压在顶栏上，也不盖住阅读区。
 * 任何状态都能关：有新版可以「这一版不再提示」，失败、回滚失败也有「关闭」。
 */
@Composable
internal fun UpdateBanner(session: UpdateSession, idle: Boolean, modifier: Modifier = Modifier) {
    val release = session.ready
    val failed = session.message.isNotEmpty() && release == null
    val rollbackFailed = release == null && session.message.isEmpty() && session.rollbackStatus.isNotEmpty()
    val installing = session.busy && session.message.isNotEmpty()
    val skipped = release != null && release.version == session.skippedVersion
    if (session.dismissed || skipped) return
    if (!shouldShowUpdateLine(idle, release != null || installing, failed || rollbackFailed, session.deferred)) return
    val colors = com.coach.ui.theme.CoachTheme.colors
    val line = when {
        session.message.isNotEmpty() -> session.message
        release != null -> "新版本 ${release.version} 已经下好了。"
        rollbackFailed -> session.rollbackStatus
        else -> return
    }
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Box(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.clip(shape)
                .background(colors.surface1)
                .border(1.dp, colors.borderSubtle, shape)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("update-banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                line,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgPrimary,
                modifier = Modifier.weight(1f, fill = false).testTag("update-banner-text"),
            )
            if (release != null && !session.busy) {
                V7TextButton(session::install, Modifier.testTag("update-now"), ringInside = true) { Text("现在更新") }
                V7TextButton(session::defer, Modifier.testTag("update-later"), ringInside = true) { Text("下次打开再更") }
                V7TextButton(session::skipVersion, Modifier.testTag("update-banner-skip"), ringInside = true) {
                    Text("这一版不再提示")
                }
            }
            V7TextButton(session::dismiss, Modifier.testTag("update-banner-close"), ringInside = true) { Text("关闭") }
        }
    }
}
