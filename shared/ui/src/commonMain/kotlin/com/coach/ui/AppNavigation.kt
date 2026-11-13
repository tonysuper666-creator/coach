package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Legacy enum tokens are storage identities. Primary destinations are a separate projection. */
@Serializable
internal enum class AppPage { HOME, WEEK, STUDY, SOURCE, MAINLINE, MOCK, ESSAY, WEEK_REVIEW, SETTINGS, UNKNOWN }

@Serializable
internal data class AppEntry(
    val page: AppPage = AppPage.HOME,
    val tab: AppTab = AppTab.TODAY,
    val id: String = "",
    val autoStart: Boolean = false,
    val review: Boolean = false,
    val week: Int? = null,
    val pdfPage: Int = 1,
    val label: String = tab.label,
    // Retain unknown fields/tokens and explicit defaults, including those from newer versions.
    @kotlinx.serialization.Transient val original: JsonObject? = null,
) {
    private val pageToken get() = (original?.get("page") as? JsonPrimitive)?.contentOrNull ?: page.name
    private val tabToken get() = (original?.get("tab") as? JsonPrimitive)?.contentOrNull ?: tab.name
    val primaryTab get() = tab.primary
    val unsupported get() = page == AppPage.UNKNOWN ||
        (original?.containsKey("tab") == true && AppTab.entries.none { it.name == tabToken }) ||
        (original?.containsKey("tab") == true && (original["tab"] as? JsonPrimitive)?.isString != true)

    /** These are the pre-upgrade SaveableStateProvider keys. Do not use projected tabs here. */
    fun stateKey(course: String) = "$course:$pageToken:$tabToken:$id:$review:$week"
    fun sourceStateKey(course: String) = "$course:AppEntry(page=$pageToken, tab=$tabToken, id=$id, autoStart=$autoStart, review=$review, week=$week, pdfPage=$pdfPage, label=$label)"
}

internal val primaryAppTabs = listOf(AppTab.TODAY, AppTab.ROUTE, AppTab.RESOURCES)
internal val AppTab.primary get() = when (this) {
    AppTab.REVIEW -> AppTab.TODAY
    AppTab.RECORDS -> AppTab.ROUTE
    else -> this
}
internal val AppTab.navigationLabel get() = when (this) {
    AppTab.ROUTE -> "课程"
    AppTab.RESOURCES -> "书架"
    else -> label
}

/** Version 1 was a bare array. Version 2 adds a lossless envelope without renaming task identities. */
internal class AppNavigation(initial: List<AppEntry>, private val envelope: JsonObject = JsonObject(emptyMap())) {
    var entries by mutableStateOf(initial)
        private set
    val current get() = entries.last()
    val canGoBack get() = entries.size > 1
    val parentLabel get() = entries.getOrNull(entries.lastIndex - 1)?.label ?: current.tab.navigationLabel
    fun push(entry: AppEntry) { entries = entries + if (entry.page == AppPage.HOME) entry else entry.copy(tab = current.tab) }
    fun rename(label: String) { entries = entries.dropLast(1) + current.copy(label = label) }
    fun replace(entry: AppEntry) { entries = entries.dropLast(1) + entry.copy(tab = current.tab) }
    fun back() { if (canGoBack) entries = entries.dropLast(1) }
    fun select(tab: AppTab) { entries = listOf(AppEntry(tab = tab, label = tab.navigationLabel)) }
    fun encode(): String = JsonObject(envelope + mapOf(
        "version" to (envelope["version"] ?: JsonPrimitive(2)),
        "entries" to JsonArray(entries.map(::encodeEntry)),
    )).toString()

    companion object {
        private val complete = Json { encodeDefaults = true }
        private val tolerant = Json { ignoreUnknownKeys = true }
        private fun decodeEntry(raw: JsonObject): AppEntry {
            val page = (raw["page"] as? JsonPrimitive)?.contentOrNull
            val tab = (raw["tab"] as? JsonPrimitive)?.contentOrNull
            val knownPage = AppPage.entries.firstOrNull { it.name == page } ?: if ("page" !in raw) AppPage.HOME else AppPage.UNKNOWN
            val knownTab = AppTab.entries.firstOrNull { it.name == tab } ?: AppTab.TODAY
            val readable = JsonObject(raw + mapOf("page" to JsonPrimitive(knownPage.name), "tab" to JsonPrimitive(knownTab.name)))
            // A malformed known field is retained as an unsupported frame, never run as a task.
            return runCatching { tolerant.decodeFromJsonElement<AppEntry>(readable) }.getOrElse {
                AppEntry(page = AppPage.UNKNOWN, tab = knownTab, label = "原来的任务")
            }.copy(original = raw)
        }
        private fun encodeEntry(entry: AppEntry): JsonObject {
            val raw = entry.original ?: return Json.encodeToJsonElement(entry).jsonObject
            val before = complete.encodeToJsonElement(decodeEntry(raw).copy(original = null)).jsonObject
            val after = complete.encodeToJsonElement(entry.copy(original = null)).jsonObject
            return JsonObject(raw + after.filter { (key, value) -> value != before[key] })
        }
        fun restore(value: String): AppNavigation? = runCatching {
            val parsed = Json.parseToJsonElement(value)
            val envelope = parsed as? JsonObject ?: JsonObject(emptyMap())
            val array = if (parsed is JsonArray) parsed else envelope["entries"] as? JsonArray ?: return null
            if (array.isEmpty() || array.any { it !is JsonObject }) return null
            val frames = array.map { decodeEntry(it.jsonObject) }
            // Old codecs rejected a task-only root. Preserve it and add a safe return destination.
            val rooted = if (frames.first().page == AppPage.HOME) frames else listOf(AppEntry(tab = frames.first().primaryTab)) + frames
            AppNavigation(rooted, envelope)
        }.getOrNull()
        /** Keep rejected input locally as recovery evidence rather than overwriting it on first render. */
        fun recover(value: String) = AppNavigation(listOf(AppEntry()), JsonObject(mapOf("unreadableLegacy" to JsonPrimitive(value))))
        val saver = Saver<AppNavigation, String>(save = { it.encode() }, restore = { restore(it) })
    }
}

/** The same dispatcher serves visible controls, Escape and the Android system back callback. */
internal class AppBackDispatcher {
    class Handler(val priority: Int, val action: () -> Unit)
    val handlers = mutableStateListOf<Handler>()
    val enabled get() = handlers.isNotEmpty()
    fun back(): Boolean {
        val handler = handlers.withIndex().maxWithOrNull(compareBy<IndexedValue<Handler>> { it.value.priority }.thenBy { it.index })?.value ?: return false
        handler.action()
        return true
    }
}

internal val LocalAppBack = staticCompositionLocalOf<AppBackDispatcher?> { null }
internal val LocalAppManagedNavigation = staticCompositionLocalOf { false }
internal val LocalTaskChrome = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

@Composable
internal fun AppBackHandler(enabled: Boolean = true, priority: Int = 0, onBack: () -> Unit) {
    val dispatcher = LocalAppBack.current ?: return
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher, enabled, priority) {
        val handler = AppBackDispatcher.Handler(priority) { latest() }
        if (enabled) dispatcher.handlers.add(handler)
        onDispose { dispatcher.handlers.remove(handler) }
    }
}

/** Page focus/quiz mode can hide the shared chrome without installing another navigation owner. */
@Composable
internal fun TaskChrome(hidden: Boolean) {
    val update = LocalTaskChrome.current
    DisposableEffect(update, hidden) {
        update?.invoke(hidden)
        onDispose { update?.invoke(false) }
    }
}

@Composable
internal expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
