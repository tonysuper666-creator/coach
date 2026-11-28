package com.coach.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 更新提示：不压顶栏、任何状态能关、能选「这一版不再提示」。 */
@OptIn(ExperimentalTestApi::class)
class WindowsUpdateBannerTest {

    private fun session(dir: Path) = UpdateSession(
        libraryDir = dir,
        updatesDir = dir.resolve("updates"),
        save = { true },
        close = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        launch = { true },
    )

    private fun release(version: String) =
        WindowsRelease(version, "LearningCoach-$version.msi", 1, "0".repeat(64))

    @Test fun checkFailureLineSitsBelowTheTopBarAndCanBeClosed() {
        val dir = Files.createTempDirectory("coach-update-banner-fail")
        try {
            val session = session(dir)
            session.fail(UPDATE_INTERRUPTED)
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent {
                    CoachTheme(false) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(56.dp).testTag("host-bar"))
                            UpdateBanner(session, idle = true)
                        }
                    }
                }
                onNodeWithTag("update-banner").assertExists()
                onNodeWithTag("update-banner-text").assertTextContains("下载中断", substring = true)
                val bar = onNodeWithTag("host-bar").getUnclippedBoundsInRoot()
                val banner = onNodeWithTag("update-banner").getUnclippedBoundsInRoot()
                assertTrue(banner.top >= bar.bottom, "banner must sit below the top bar, not over it")
                onNodeWithTag("update-banner-skip").assertDoesNotExist()
                onNodeWithTag("update-banner-close").performClick()
                onNodeWithTag("update-banner").assertDoesNotExist()
                assertTrue(session.dismissed)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun rollbackFailureCanBeClosed() {
        val dir = Files.createTempDirectory("coach-update-banner-rollback")
        try {
            val session = session(dir)
            session.noteRollback(ROLLBACK_FAILED)
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { CoachTheme(false) { Box(Modifier.fillMaxSize()) { UpdateBanner(session, idle = true) } } }
                onNodeWithTag("update-banner-text").assertTextContains("没能回到上一版", substring = true)
                onNodeWithTag("update-banner-close").performClick()
                onNodeWithTag("update-banner").assertDoesNotExist()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun skippedVersionStaysQuietUntilTheNextRelease() {
        val dir = Files.createTempDirectory("coach-update-banner-skip")
        try {
            val first = session(dir)
            first.offer(release("1.10.0"), dir.resolve("1.10.0.msi"))
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { CoachTheme(false) { Box(Modifier.fillMaxSize()) { UpdateBanner(first, idle = true) } } }
                onNodeWithTag("update-now").assertExists()
                onNodeWithTag("update-later").assertExists()
                onNodeWithTag("update-banner-skip").assertExists()
                onNodeWithTag("update-banner-skip").performClick()
                onNodeWithTag("update-banner").assertDoesNotExist()
            }
            assertEquals("1.10.0", readSkippedUpdateVersion(dir))
            val sameVersion = session(dir)
            sameVersion.offer(release("1.10.0"), dir.resolve("1.10.0.msi"))
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { CoachTheme(false) { Box(Modifier.fillMaxSize()) { UpdateBanner(sameVersion, idle = true) } } }
                onNodeWithTag("update-banner").assertDoesNotExist()
            }
            val newer = session(dir)
            newer.offer(release("1.11.0"), dir.resolve("1.11.0.msi"))
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { CoachTheme(false) { Box(Modifier.fillMaxSize()) { UpdateBanner(newer, idle = true) } } }
                onNodeWithTag("update-banner").assertExists()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
