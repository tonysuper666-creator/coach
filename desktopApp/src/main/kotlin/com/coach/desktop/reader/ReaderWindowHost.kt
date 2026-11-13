package com.coach.desktop.reader

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * 独立宿主：只用自造样张打开阅读窗口，不连书库与同步，不经过 Main.kt。
 * 正式接线见 ReaderWindow.kt 头部说明。
 */
fun main() = application {
    val session = remember { ReaderSession(ReaderFixture.synthetic(), 1200) }
    Window(onCloseRequest = ::exitApplication, title = "Coach 阅读（自造样张）", state = rememberWindowState(width = 1200.dp, height = 760.dp)) {
        ReaderWindow(session)
    }
}
