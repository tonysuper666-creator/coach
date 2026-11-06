package com.coach.ui

import androidx.compose.ui.test.*
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class NavAccessibilityTest {
    @Test fun compactRailAnnouncesLabelsRatherThanGlyphs() = runComposeUiTest {
        setContent { CoachTheme(false) { AppNavRail(AppTab.TODAY, compact = true) {} } }
        for (tab in AppTab.entries) {
            onNodeWithContentDescription(tab.label).assertExists()
            onNodeWithText(tab.glyph).assertDoesNotExist()
        }
    }
}
