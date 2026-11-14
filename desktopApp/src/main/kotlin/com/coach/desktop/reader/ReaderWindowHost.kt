package com.coach.desktop.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.coach.desktop.VerifyHooks
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.nio.file.Path

private fun Component.skiaLayers(): Sequence<SkiaLayer> = sequence {
    if (this@skiaLayers is SkiaLayer) yield(this@skiaLayers)
    if (this@skiaLayers is Container) {
        for (child in components) yieldAll(child.skiaLayers())
    }
}

/**
 * Windows 桌面阅读器宿主：打开真实资料（本地 TextbookLibrary 资料或合成样例文档）并翻页。
 *
 * 假数据窗口已改成打开真实资料，无配置不把样张当用户资料（优先打开书架导入的真实教材），
 * 支持按页翻页、断点书签恢复，并在学到某一处时能按位置直接打开对应页。
 */
fun main() {
    if (System.getProperty("os.name")?.startsWith("Windows") == true && System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", System.getenv("SKIKO_RENDER_API") ?: "SOFTWARE_FAST")
    }
    application {
        val libraryDir = System.getenv("COACH_LIBRARY_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: System.getProperty("coach.library.dir")?.takeIf(String::isNotBlank)?.let(Path::of)
        val source = remember { ReaderSourceResolver.resolveInternal(libraryDirectory = libraryDir) }
        val remote = remember(source) { ReaderRemote(source) }
        val explicitDoc = System.getProperty("coach.reader.doc") ?: System.getenv("COACH_READER_DOC")
        val targetPage = System.getProperty("coach.reader.page")?.toIntOrNull() ?: System.getenv("COACH_READER_PAGE")?.toIntOrNull()
        val targetAnchor = System.getProperty("coach.reader.anchor") ?: System.getenv("COACH_READER_ANCHOR")

        // 本机草稿存储：只对真实本地书库启用；HTTP/合成来源没有可信的拥有者/课程/来源身份，停用（不拿本地路径代替）
        val drafts = remember(source, libraryDir) { ReaderDraftScope.forSource(source, libraryDir) }
        val session = remember(remote) {
            ReaderSession(ReaderFixture("资料", emptyList(), emptyList()), 1200, remote, drafts)
        }

        LaunchedEffect(remote) {
            remote.loadCatalog()
            val targetDoc = explicitDoc ?: remote.documents.firstOrNull()?.id ?: "orchard-text"
            if (targetPage != null) {
                session.openRemoteAt(targetDoc, targetPage, targetAnchor)
            } else {
                session.openRemote(targetDoc)
            }
        }

        val docTitle = session.remote?.current?.title ?: "资料阅读"
        Window(
            onCloseRequest = ::exitApplication,
            title = "Coach 阅读器 · $docTitle",
            state = rememberWindowState(width = 1200.dp, height = 760.dp),
        ) {
            ReaderWindow(session)
            LaunchedEffect(window) {
                delay(500)
                val layer = window.skiaLayers().firstOrNull()
                println("READER window showing=${window.isShowing} focused=${window.isFocused} active=${window.isActive} bounds=${window.bounds} renderApi=${layer?.renderApi}")
                VerifyHooks.captures(window)
            }
            LaunchedEffect(Unit) {
                System.getProperty("coach.autoExitSeconds")?.toLongOrNull()?.let {
                    delay(it * 1000)
                    exitApplication()
                }
            }
        }
    }
}
