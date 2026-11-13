package com.coach.desktop.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Windows 桌面阅读器宿主：打开真实资料（合成样例文档）并翻页。
 *
 * 假数据窗口已改成打开真实资料（默认样例合成资料 orchard-text 或指定资料），
 * 并在学到某一处时能按位置直接打开对应页。
 */
fun main() = application {
    val source = remember { ReaderSourceResolver.resolve() }
    val remote = remember(source) { ReaderRemote(source) }
    val targetDoc = System.getProperty("coach.reader.doc") ?: System.getenv("COACH_READER_DOC") ?: "orchard-text"
    val targetPage = System.getProperty("coach.reader.page")?.toIntOrNull() ?: System.getenv("COACH_READER_PAGE")?.toIntOrNull()
    val targetAnchor = System.getProperty("coach.reader.anchor") ?: System.getenv("COACH_READER_ANCHOR")

    val session = remember(remote) {
        ReaderSession(ReaderFixture("资料", emptyList(), emptyList()), 1200, remote)
    }

    LaunchedEffect(remote) {
        remote.loadCatalog()
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
    }
}
