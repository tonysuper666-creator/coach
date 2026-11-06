package com.coach.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import kotlin.test.*

/** TON-1241: 重连风暴里「Mac 未连接」不能跟着每次握手一闪一闪；冷启动也不先闪一下红横幅。 */
@OptIn(ExperimentalTestApi::class)
class ConnectionBannerTest {
    private fun ComposeUiTest.host(initial: Boolean, flips: (Boolean) -> Unit): (Boolean) -> Unit {
        var connected by mutableStateOf(initial)
        mainClock.autoAdvance = false
        setContent { if (!steadyConnected(connected)) Text("Mac 未连接").also { flips(true) } else flips(false) }
        return { connected = it }
    }

    private fun ComposeUiTest.bannerShown() = onAllNodesWithText("Mac 未连接").fetchSemanticsNodes().isNotEmpty()

    @Test fun coldStartHandshakeDoesNotFlashBanner() = runComposeUiTest {
        val set = host(initial = false) {}
        mainClock.advanceTimeBy(400)
        assertFalse(bannerShown(), "第一次握手还没完成就亮了红横幅")
        set(true)
        mainClock.advanceTimeBy(5_000)
        assertFalse(bannerShown())
    }

    @Test fun macThatNeverAnswersStillShowsBanner() = runComposeUiTest {
        host(initial = false) {}
        mainClock.advanceTimeBy(STARTUP_GRACE_MS + 100)
        assertTrue(bannerShown())
    }

    @Test fun reconnectStormKeepsBannerSteady() = runComposeUiTest {
        val shown = mutableListOf<Boolean>()
        val set = host(initial = true) { if (shown.lastOrNull() != it) shown += it }
        mainClock.advanceTimeBy(100)
        // 连上 300ms、断开 250ms，循环 20 次：与 06:43 的日志同一节奏。
        repeat(20) {
            set(false); mainClock.advanceTimeBy(250)
            set(true); mainClock.advanceTimeBy(300)
        }
        set(false); mainClock.advanceTimeBy(DISCONNECT_GRACE_MS + 100)
        assertTrue(bannerShown())
        assertTrue(shown.count { it } <= 1, "横幅出现/消失 ${shown.size} 次：$shown")
        set(true); mainClock.advanceTimeBy(RECONNECT_SETTLE_MS + 100)
        assertFalse(bannerShown(), "连接稳定后横幅应收起")
    }

    @Test fun realDisconnectShowsBannerWithinOneSecond() = runComposeUiTest {
        val set = host(initial = true) {}
        mainClock.advanceTimeBy(100)
        set(false)
        mainClock.advanceTimeBy(1_000)
        assertTrue(bannerShown())
    }
}
