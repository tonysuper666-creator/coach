package com.coach.desktop.books

import kotlin.test.Test
import kotlin.test.assertEquals

class BookMapVerifyTest {
    @Test
    fun selfCheckImportsGeneratesAuditsAndReconcilesWithoutProseLeak() {
        assertEquals(0, BookMapVerifyMain.run(arrayOf("self-check")))
    }
}
