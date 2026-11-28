package com.coach.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import com.coach.desktop.incident.runCoachApplication
import androidx.compose.ui.window.rememberWindowState
import com.coach.desktop.books.exam.*
import com.coach.desktop.books.AnswerDrafts
import com.coach.desktop.books.LocalBookRelogin
import com.coach.desktop.books.IncidentHooks
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.LearningJournal
import com.coach.desktop.books.LearningMigrationBackup
import com.coach.desktop.books.HttpLearningRemote
import com.coach.desktop.books.LearningTransfer
import com.coach.client.CourseHttp
import kotlinx.coroutines.isActive
import com.coach.desktop.books.TextbookLoginScreen
import com.coach.desktop.books.rememberAnswerDrafts
import com.coach.ui.CoachTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import com.coach.desktop.incident.IncidentExitPrompt
import com.coach.desktop.incident.IncidentLog
import com.coach.desktop.incident.IncidentPromptState
import com.coach.desktop.incident.IncidentWatchdog
import com.coach.desktop.incident.runIncidentTestSwitch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path

internal fun windowsLibraryDirectory(
    environment: (String) -> String? = System::getenv,
    property: (String) -> String? = System::getProperty,
    home: Path = Path.of(System.getProperty("user.home")),
): Path {
    val explicit = environment("COACH_LIBRARY_DIR")?.takeIf(String::isNotBlank)
    require(explicit != null || !developmentLaunch(environment, property)) {
        "Development runs require an isolated COACH_LIBRARY_DIR for the Windows textbook library."
    }
    val target = explicit?.let(Path::of)?.toAbsolutePath()?.normalize() ?: home.resolve("Coach/books")
    require(!developmentLaunch(environment, property) || target != home.resolve("Coach/books").toAbsolutePath().normalize()) {
        "Development runs must not open the learner's textbook library."
    }
    val learnerLibrary = home.resolve("Coach/books")
    require(!developmentLaunch(environment, property) || !Files.exists(target) || !Files.exists(learnerLibrary) || !Files.isSameFile(target, learnerLibrary)) {
        "Development runs must not open an alias of the learner's textbook library."
    }
    return target
}

/** An optional per-process override; without it the existing system preference remains authoritative. */
internal fun windowsThemeOverride(value: String?): Boolean? = when {
    value.equals("dark", ignoreCase = true) -> true
    value.equals("light", ignoreCase = true) -> false
    else -> null
}

/**
 * 「异常退出后询问是否发送问题记录」。默认开；顶栏徽标里的「不再提示」和设置里的开关改的是同一个标记。
 * 关掉只是不再问，问题记录本身留在本机。
 */
@Stable
internal class ReportOffersState(private val clientDirectory: Path) {
    var enabled by mutableStateOf(!Files.exists(marker(clientDirectory)))
        private set

    fun set(enabled: Boolean) {
        Files.createDirectories(clientDirectory)
        if (enabled) Files.deleteIfExists(marker(clientDirectory)) else Files.writeString(marker(clientDirectory), "")
        this.enabled = enabled
    }

    private companion object {
        fun marker(directory: Path): Path = directory.resolve("disable-report-offers")
    }
}

/**
 * Startup login gate for the Windows textbook app (TON-1782):
 * - First launch or missing identity shows the full-screen login page (Sable primary, pairing code tucked below).
 * - Existing identity enters the bookshelf/last reading spot immediately without waiting on network, so offline use is never blocked.
 * - Explicit 401 revocation clears credentials and returns to the login page with a clear explanation.
 */
@Composable
internal fun WindowsTextbookGate(
    settings: DesktopConnectionSettings,
    sableIssuer: String = com.coach.client.SABLE_ORIGIN,
    darkTheme: Boolean = windowsThemeOverride(System.getProperty("coach.windows.theme")) ?: isSystemInDarkTheme(),
    offerReports: Boolean = true,
    onDisableOffers: () -> Unit = {},
    content: @Composable (DesktopIdentity) -> Unit,
) {
    val pairing = remember(settings) { DesktopPairing(settings) }
    val sable = remember(settings) { settings.sableLoginShown() }
    val hasBuiltIn = remember(settings) { settings.hasBuiltInFriendUrl() }
    var identity by remember(pairing) { mutableStateOf<DesktopIdentity?>(null) }
    var initialized by remember(pairing) { mutableStateOf(false) }
    var defaultAddress by remember(pairing) { mutableStateOf("") }
    var revokedReason by remember(pairing) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pairing) {
        try {
            val (address, session, wasRevoked) = withContext(Dispatchers.IO) {
                val opened = pairing.loadForStartup()
                val url = if (hasBuiltIn) "" else settings.serverUrl().orEmpty()
                Triple(url, opened, runCatching { pairing.wasRevoked() }.getOrDefault(false))
            }
            defaultAddress = address
            identity = session.identity
            revokedReason = when {
                session.notice != null -> session.notice
                session.identity == null && wasRevoked -> if (sable) "授权已撤销，请重新登录。" else "这台电脑的连接已停用，请重新配对。"
                else -> null
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
        try {
            withContext(Dispatchers.IO) { pairing.awaitRevocation(current) }
            identity = null
            revokedReason = if (sable) "授权已撤销，请重新登录。" else "这台电脑的连接已停用，请重新配对。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline or transient network/storage failures must not block local reading.
        }
    }

    val paired = identity
    val currentPaired by rememberUpdatedState(paired)
    val reportsEnabled by rememberUpdatedState(offerReports)
    val currentDarkTheme by rememberUpdatedState(darkTheme)
    val promptState = remember { IncidentPromptState(scope) }
    LaunchedEffect(paired) {
        promptState.onIdentityChanged(paired)
    }
    val promptHost = remember {
        IncidentPromptHost(
            prompt = {
                if (reportsEnabled) IncidentExitPrompt(
                    identity = currentPaired,
                    darkTheme = currentDarkTheme,
                    onNeverShow = onDisableOffers,
                    state = promptState,
                )
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
    if (paired != null) {
        val onRelogin: (String?) -> Unit = remember(pairing, paired, sable) {
            { reason ->
                val active = identity
                identity = null
                revokedReason = reason ?: if (sable) "授权已撤销，请重新登录。" else "这台电脑的连接已停用，请重新配对。"
                if (active != null) {
                    scope.launch(Dispatchers.IO) { runCatching { pairing.revoke(active) } }
                }
            }
        }
        CompositionLocalProvider(
            LocalBookRelogin provides onRelogin,
            LocalLogout provides { switching ->
                withContext(Dispatchers.IO) { pairing.logout(paired) }
                if (identity === paired) {
                    revokedReason = if (switching) "请登录要切换到的账号。" else "已退出登录。"
                    identity = null
                }
            },
            LocalIncidentPromptHost provides promptHost,
        ) {
            key(paired.credentials.deviceId, paired.credentials.token) {
                DisposableEffect(paired.credentials.deviceId, paired.credentials.token) {
                    onDispose { promptState.cancelSessionSend() }
                }
                RememberDesktopLedgerRecovery(settings, paired)
                content(paired)
            }
        }
    } else {
        CoachTheme(darkTheme) {
            if (!initialized) {
                Surface(Modifier.fillMaxSize().testTag("windows-login-loading"), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {}
            } else {
                TextbookLoginScreen(
                    sableLogin = sable,
                    defaultAddress = defaultAddress,
                    hasBuiltInAddress = hasBuiltIn,
                    revokedReason = revokedReason,
                    onSableLogin = { open ->
                        val signedIn = pairing.sable(open, sableIssuer)
                        revokedReason = null
                        identity = signedIn
                    },
                    onPair = { address, code ->
                        try {
                            val signedIn = withContext(Dispatchers.IO) {
                                pairing.pair(
                                    address,
                                    code,
                                    if (System.getProperty("os.name").startsWith("Windows")) "Windows 电脑" else "Mac 电脑",
                                )
                            }
                            revokedReason = null
                            identity = signedIn
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    },
                )
            }
        }
    }
    }
}

/** Windows mainline: first-run login gate, then local textbook shelf with zero-click resume. */
internal fun runWindowsTextbooks() {
    val directory = windowsLibraryDirectory()
    val initialBounds = windowsInitialWindowBounds()
    val settings = DesktopConnectionSettings()
    IncidentLog.installCrashHandler()
    IncidentLog.startSession(settings.home)
    IncidentWatchdog.start()
    runCoachApplication {
        val scope = rememberCoroutineScope()
        var saveDrafts by remember { mutableStateOf<suspend () -> Boolean>({ true }) }
        val closeGuard = remember(scope) {
            TextbookCloseGuard({ saveDrafts() }, scope, ::exitApplication)
        }
        Window(onCloseRequest = closeGuard::requestClose, title = "LearningCoach ${com.coach.desktop.incident.coachVersion()}", icon = AppIcon.window(),
            state = rememberWindowState(
                width = initialBounds.width.dp, height = initialBounds.height.dp,
                position = WindowPosition.Absolute(initialBounds.x.dp, initialBounds.y.dp),
            )) {
            CompositionLocalProvider(
                LocalIncidentFolderActions provides IncidentFolderActions(
                    openFolder = IncidentLog::openFolder,
                    clearRecords = IncidentLog::clear,
                ),
            ) {
            WindowsReadingMinimum(window)
            val reportOffers = remember(settings.clientDirectory) { ReportOffersState(settings.clientDirectory) }
            val offerReports = reportOffers.enabled
            val setReportOffers: (Boolean) -> Unit = reportOffers::set
            WindowsTextbookGate(
                settings,
                offerReports = offerReports,
                onDisableOffers = { setReportOffers(false) },
            ) { identity ->
                val allowOwnerSync = remember(identity, settings) { ownerLearningSyncAllowed(identity, settings) }
                val profiles = remember(directory) { LearningProfiles(directory) }
                val profile = if (LearningJournal.enabled || profiles.hasProfiles) {
                    val selected by produceState<LearningProfile?>(null, identity, allowOwnerSync) {
                        while (isActive) {
                            value = profiles.open(identity, LearningJournal.enabled, allowOwner = allowOwnerSync)
                            val canExpectScope = identity.credentials.role == "friend" || allowOwnerSync
                            if (!LearningJournal.enabled || value?.scope != null || !canExpectScope) break
                            delay(30_000)
                        }
                    }
                    selected ?: return@WindowsTextbookGate
                } else LearningProfile(directory, null)
                key(profile.directory) {
                    val controller = remember(profile.directory) {
                        WindowsRollback.prepareLaunch(profile.directory, runningAppVersion())
                        windowsBookController(profile.directory).also {
                            WindowsRollback.mergeParked(profile.directory, runningAppVersion())
                        }
                    }
                    val drafts = rememberAnswerDrafts(controller)
                    val exams = rememberLocalExamController(controller.library)
                    DisposableEffect(controller,drafts,exams) {
                        saveDrafts = {
                            controller.flushPendingSaves()
                            drafts.flush() && exams.flush()
                        }
                        onDispose { saveDrafts = { true } }
                    }
                    val sync = remember(profile) { SyncDisplay(profile.directory) }
                    LearningSyncEffect(identity, profile, sync, allowOwner = allowOwnerSync)
                    var updateRequest by remember { mutableIntStateOf(0) }
                    var updateResult by remember { mutableStateOf("") }
                    val updates = rememberWindowsUpdateSession(
                        libraryDir = profile.directory,
                        checkRequest = updateRequest,
                        onResult = { updateResult = it },
                        save = {
                            controller.state.first { !it.busy }
                            saveDrafts()
                        },
                        close = ::exitApplication,
                    )
                    val logout = LocalLogout.current
                    CompositionLocalProvider(
                        LocalAccountSettings provides {
                            AccountSection(identity, settings.clientDirectory) { switching ->
                                check(saveDrafts()) { "save" }
                                logout(switching)
                            }
                        },
                        LocalDataSettings provides {
                            DataSection(profile, sync, updateResult, onCheck = {
                                updateResult = "正在检查更新…"
                                updateRequest++
                            }, offerReports = offerReports, onReports = setReportOffers)
                            updates.ready?.let { release ->
                                TextButton(updates::install, enabled = !updates.busy && !updates.rollbackBusy) {
                                    Text("立即更新到 ${release.version}")
                                }
                            }
                            if (updates.message.isNotEmpty()) Text(updates.message)
                        },
                    ) {
                        WindowsTextbookHost(
                            controller = controller,
                            drafts = drafts,
                            exams = exams,
                            onRollback = updates::rollback,
                            rollbackStatus = updates.rollbackStatus,
                            rollbackBusy = updates.rollbackBusy || updates.busy,
                            updateBanner = { idle -> UpdateBanner(updates, idle) },
                            chooseReviewedBlocks = { pickReviewedBlocks(window) },
                            chooseBooks = {
                                withContext(Dispatchers.Swing) {
                                    val dialog = FileDialog(window, "导入文件 · ${com.coach.desktop.books.BookFormats.description}", FileDialog.LOAD)
                                    dialog.isMultipleMode = true
                                    try {
                                        dialog.isVisible = true
                                        dialog.files.map { it.toPath() }
                                    } finally { dialog.dispose() }
                                }
                            },
                            chooseBook = {
                                withContext(Dispatchers.Swing) {
                                    val dialog = FileDialog(window, "导入文件 · ${com.coach.desktop.books.BookFormats.description}", FileDialog.LOAD)
                                    try {
                                        dialog.isVisible = true
                                        dialog.file?.let { Path.of(dialog.directory, it) }
                                    } finally { dialog.dispose() }
                                }
                            },
                            firstOpenGuide = true,
                        )
                    }
                }
            }
            TextbookCloseWarning(closeGuard)
            }
            LaunchedEffect(window) { VerifyHooks.captures(window) }
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let { delay(it * 1000); closeGuard.requestClose() }
            }
            LaunchedEffect(Unit) {
                while (isActive) {
                    IncidentWatchdog.beat()
                    delay(1_000)
                }
            }
            LaunchedEffect(Unit) { runIncidentTestSwitch() }
        }
    }
}

internal class TextbookCloseGuard(
    private val flushDrafts: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val close: () -> Unit,
) {
    constructor(drafts: AnswerDrafts, scope: CoroutineScope, close: () -> Unit) : this(drafts::flush, scope, close)

    var failed by mutableStateOf(false)
        private set
    private var closing = false

    fun requestClose() {
        if (closing) return
        closing = true
        failed = false
        scope.launch {
            try {
                if (flushDrafts()) close() else failed = true
            } finally { closing = false }
        }
    }

    fun dismiss() { failed = false }
}

@Composable
internal fun TextbookCloseWarning(guard: TextbookCloseGuard) {
    if (guard.failed) AlertDialog(
        onDismissRequest = guard::dismiss,
        title = { Text("草稿还没有保存") },
        text = { Text("窗口会保持打开，草稿仍在。请排除保存问题后重试。") },
        confirmButton = { TextButton(guard::requestClose, Modifier.testTag("retry-close")) { Text("重试保存并关闭") } },
        dismissButton = { TextButton(guard::dismiss) { Text("继续编辑") } },
    )
}

/** Production host content with an owned library for offscreen tests. */
@Composable
internal fun WindowsTextbooksContent(directory: Path, chooseBook: suspend () -> Path?) {
    val controller = remember(directory) { windowsBookController(directory) }
    WindowsTextbookHost(
        controller,
        chooseReviewedBlocks = { pickReviewedBlocks(null) },
        chooseBook = chooseBook,
        firstOpenGuide = true,
    )
}

private suspend fun pickReviewedBlocks(owner: java.awt.Frame?): List<Path> = withContext(Dispatchers.Swing) {
    val dialog = FileDialog(owner, "选择已核对的排版块（*.review.json）", FileDialog.LOAD)
    try {
        dialog.file = "*.review.json"
        dialog.isMultipleMode = true
        dialog.isVisible = true
        dialog.files.map { it.toPath() }
    } finally {
        dialog.dispose()
    }
}

private fun windowsBookController(directory: Path): TextbookController {
    if (LearningJournal.enabled) LearningMigrationBackup.prepare(directory)
    val library = TextbookLibrary(
        directory,
        clock = isolatedTodayClock(System.getenv("COACH_URL"), System.getProperty(TODAY_OFFSET_PROPERTY)),
        streamPdfExtras = true,
        lazyPdfExtras = true,
    )
    if (LearningJournal.enabled) LearningJournal.installProfile(library)
    return TextbookController(library)
}

@Composable
private fun LearningSyncEffect(
    identity: DesktopIdentity,
    profile: LearningProfile,
    status: SyncDisplay,
    allowOwner: Boolean = false,
) {
    profile.scope?.takeIf {
        LearningJournal.enabled && (identity.credentials.role == "friend" || allowOwner)
    }?.let { account ->
        LaunchedEffect(identity, profile, allowOwner) {
            val credentials = identity.credentials
            val http = CourseHttp(identity.serverUrl,credentials.deviceId,credentials.token,"library")
            val remote = HttpLearningRemote(http)
            try {
                while (isActive) {
                    try {
                        val confirmed = withContext(Dispatchers.IO) {
                            val library = LearningTransfer(profile.directory.resolve("library.sqlite"), false)
                                .sync(account, remote, profile.epoch, allowOwner = allowOwner) { isActive }
                            val exams = LearningTransfer(profile.directory.resolve("exams.sqlite"), true)
                                .sync(account, remote, profile.epoch, allowOwner = allowOwner) { isActive }
                            if (library && exams) status.confirm() else null
                        }
                        status.record(confirmed)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        status.failure = when (failure) {
                            is java.io.IOException -> "连接失败，请检查网络。"
                            is java.sql.SQLException -> "暂时无法读写本机学习数据。"
                            else -> "同步未完成，稍后会自动重试。"
                        }
                        // The outbox keeps the change. The record stays on this computer until the user sends it.
                        runCatching { IncidentHooks.syncFailed("${failure.javaClass.name} ${failure.message.orEmpty()}") }
                    }
                    delay(15_000)
                }
            } finally { http.close() }
        }
    }
}
