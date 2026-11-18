package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.desktop.books.ExplainConsent
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookExplainConsentSmoke {
    @Test fun consentPersistsPerBookOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "book-consent-")
        try {
            val consent = ExplainConsent(directory)
            assertFalse(consent.allowed("first-book"))
            consent.allow("first-book")
            val reopened = ExplainConsent(directory)
            assertTrue(reopened.allowed("first-book"))
            assertFalse(reopened.allowed("second-book"))
        } finally {
            Files.deleteIfExists(directory.resolve("explain-consent/first-book"))
            Files.deleteIfExists(directory.resolve("explain-consent"))
            Files.deleteIfExists(directory)
        }
    }
}
