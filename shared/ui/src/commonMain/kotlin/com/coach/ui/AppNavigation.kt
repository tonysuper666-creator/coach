package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
internal enum class AppPage { HOME, WEEK, STUDY, SOURCE, MAINLINE, MOCK, ESSAY, WEEK_REVIEW, REVIEW_PRACTICE }

/** Each task carries its actual arrival point; showing a source never replaces that task. */
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
)

internal class AppNavigation(initial: List<AppEntry>) {
    var entries by mutableStateOf(initial)
        private set
    val current get() = entries.last()
    val canGoBack get() = entries.size > 1
    val parentLabel get() = entries.getOrNull(entries.lastIndex - 1)?.label ?: current.tab.label
    fun push(entry: AppEntry) { entries = entries + if (entry.page == AppPage.HOME) entry else entry.copy(tab = current.tab) }
    fun rename(label: String) { entries = entries.dropLast(1) + current.copy(label = label) }
    fun replace(entry: AppEntry) { entries = entries.dropLast(1) + entry.copy(tab = current.tab) }
    fun back() { if (canGoBack) entries = entries.dropLast(1) }
    fun select(tab: AppTab) { entries = listOf(AppEntry(tab = tab)) }
    fun encode(): String = Json.encodeToString(entries)
    companion object {
        fun restore(value: String): AppNavigation? = runCatching {
            Json.decodeFromString<List<AppEntry>>(value).takeIf { it.isNotEmpty() && it.first().page == AppPage.HOME }?.let(::AppNavigation)
        }.getOrNull()
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
