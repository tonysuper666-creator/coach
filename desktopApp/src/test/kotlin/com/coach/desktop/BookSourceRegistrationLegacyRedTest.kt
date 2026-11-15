package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlin.test.Test

/** Compiles on PR217. Proves only the missing production registration entry, not real content supply. */
@OptIn(ExperimentalTestApi::class)
class BookSourceRegistrationLegacyRedTest {
    @Test fun reviewOffersExplicitSourceRegistration() {
        val f = PersistedSourceFixture()
        val c = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.book != null }
            onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
            onNodeWithTag("host-today-review").performClick()
            waitUntil(timeoutMillis = 20_000) { c.state.value.screen == BookScreen.REVIEWS && !c.state.value.busy }
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { c.state.value.screen == BookScreen.REVIEWING && !c.state.value.busy }
            onNodeWithTag("review-import-source").assertExists().performScrollTo().assertIsEnabled()
        }
    }
}
