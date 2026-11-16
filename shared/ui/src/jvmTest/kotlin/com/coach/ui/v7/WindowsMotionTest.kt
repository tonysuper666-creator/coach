package com.coach.ui.v7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsMotionTest {
    private fun reg(mask: String) = "\r\nHKEY_CURRENT_USER\\Control Panel\\Desktop\r\n    UserPreferencesMask    REG_BINARY    $mask\r\n\r\n"

    @Test fun animationEffectsOnKeepsMotion() = assertEquals(false, WindowsMotion.parse(reg("9E1E078012000000")))

    @Test fun animationEffectsOffReducesMotion() = assertEquals(true, WindowsMotion.parse(reg("9012038010000000")))

    @Test fun unreadableValueDoesNotGuess() {
        assertNull(WindowsMotion.parse("ERROR: The system was unable to find the specified registry key or value."))
        assertNull(WindowsMotion.parse(reg("9E1E")))
    }
}
