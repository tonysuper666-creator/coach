package com.coach.core

/** Detects substantial verbatim overlap unique to internally readable sources. */
class RestrictedSourceGuard(restricted: List<String>, browsable: List<String>) {
    private val privatePages = restricted.map(TutorOutputGuard::normalizeLeakText)
    private val publicPages = browsable.map(TutorOutputGuard::normalizeLeakText)

    fun overlaps(text: String): Boolean {
        val normalized = TutorOutputGuard.normalizeLeakText(text)
        fun privateWindow(window: String): Boolean =
            privatePages.any { window in it } && publicPages.none { window in it }
        if (normalized.length >= 24 && normalized.windowedSequence(24).any(::privateWindow)) return true
        return Regex("\\p{IsHan}{10,}").findAll(normalized).any { match ->
            match.value.windowedSequence(10).any(::privateWindow)
        }
    }

    /** Call only with complete sentences; a withheld sentence must never reach the visible stream. */
    fun filter(text: String): String = buildString {
        for (sentence in Regex("[^。！？.!?\\n]*[。！？.!?\\n]|[^。！？.!?\\n]+$").findAll(text)) {
            if (!overlaps(sentence.value)) append(sentence.value)
        }
    }
}
