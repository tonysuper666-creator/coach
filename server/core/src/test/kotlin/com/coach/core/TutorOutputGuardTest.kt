package com.coach.core

import kotlin.test.*

class TutorOutputGuardTest {
    @Test fun referencesAreRemovedEvenWhenSplitAcrossChunks() {
        for (text in listOf("选 B。", "选 **B**。", "答案是 `C`。", "答案是Ｃ。", "Choose option D.", "The answer is A.", "B is correct.", "B 选项正确。")) {
            for (chunkSize in 1..text.length) {
                val guard = TutorOutputGuard()
                val visible = text.chunked(chunkSize).joinToString("") { guard.push(it) } + guard.finish()
                assertTrue(visible.isBlank(),"reference remained: $text -> $visible")
                assertTrue(guard.letterReferenceStripped)
            }
        }
    }
    @Test fun modelInputsKeepOptionTextsWithoutTheirDisplayLetters() {
        val text = TutorOutputGuard.modelInput("A. First option\nB) Second option\n答案是 C。")
        assertTrue(text.contains("First option"))
        assertTrue(text.contains("Second option"))
        assertFalse(text.contains("A."))
        assertFalse(text.contains("B)"))
        assertFalse(text.contains("答案是"))
    }
    @Test fun safeTextStreamsPromptlyAndSnapshotsRemoveAnOffendingSentence() {
        val guard = TutorOutputGuard(streamSafePrefixes=true)
        var display = ""
        fun append(raw: String) {
            display += guard.push(raw)
            guard.takeRevision()?.let { display = it }
        }
        append("先想一想，")
        assertEquals("先想一想，",display)
        append("选 **")
        append("B**。")
        assertEquals("",display)
        append("比较机会成本。")
        display += guard.finish()
        guard.takeRevision()?.let { display = it }
        assertEquals("比较机会成本。",display)
        assertTrue(guard.letterReferenceStripped)
    }
    @Test fun algebraAndOrdinaryProseSurvive() {
        val guard = TutorOutputGuard()
        val text = "Let A be a random variable. Compare the costs. \$\$P(A) = 0.5\$\$。"
        assertEquals(text,guard.push(text) + guard.finish())
        assertFalse(guard.letterReferenceStripped)
    }
    @Test fun examSectionAppearsOnlyOnFirstExplanationAndIsCapped() {
        val text = "正文。\n### 期中怎么考\n第一行\n第二行\n第三行不能出现\n第四行不能出现\n\n### 例子\n可见。"
        val first = TutorOutputGuard(allowExamSection=true)
        val visible = text.chunked(3).joinToString("") { first.push(it) } + first.finish()
        assertTrue("期中怎么考" in visible)
        assertFalse("第三行不能出现" in visible)
        assertTrue("可见" in visible)
        val recap = TutorOutputGuard()
        val later = recap.push(text) + recap.finish()
        assertFalse("期中怎么考" in later)
        assertTrue("正文" in later)
        assertTrue("可见" in later)
    }
}
