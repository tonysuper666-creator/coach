package com.coach.desktop.regression

import com.coach.desktop.books.Explanation
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TON-1848: a companion answer is restored after the library is closed and reopened.
 * Before the ask table exists, the reopened answer is missing.
 */
class R1848AskSurvivesRestartTest {
    @Test
    fun answerSurvivesClosingTheLibrary() {
        val root = Files.createTempDirectory("coach-r1848-ask-")
        try {
            val directory = root.resolve("library")
            val library = TextbookLibrary(directory)
            val book = library.importBook(
                root.resolve("page.md").also {
                    Files.writeString(it, "# Chapter 1 Opening\n\nThe sentence under discussion stays on the page.\n")
                },
            )
            val block = book.sourceBlocks().first { it.text.contains("sentence under discussion") }
            val answer = "ANSWER_TOKEN_1848"
            val save = library.javaClass.methods.firstOrNull { it.name == "saveAskTurn" && it.parameterCount == 9 }
            save?.invoke(
                library,
                book.id,
                "thread-1848",
                block,
                "Chapter 1",
                "ask",
                "讲讲",
                "why this sentence",
                Explanation(answer, emptyList(), 12L),
                listOf(block),
            )
            val reopened = TextbookLibrary(directory)
            val load = reopened.javaClass.methods.firstOrNull { it.name == "loadLatestAskThread" && it.parameterCount == 1 }
            val thread = load?.invoke(reopened, book.id)
            val restored = thread?.let { value ->
                val turns = value.javaClass.getMethod("getTurns").invoke(value) as List<*>
                val entry = turns.last()
                val explanation = entry!!.javaClass.getMethod("getExplanation").invoke(entry)
                explanation.javaClass.getMethod("getText").invoke(explanation) as String
            }
            assertEquals(answer, restored)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
