package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

/** Local content identities, never substitutes for a paired course, KP or HTTP document ID. */
internal data class BookExplanationRequest(val book: String, val lesson: String, val blocks: List<SourceBlock>)
internal data class BookPassage(val book: String, val page: Int, val start: Int, val end: Int, val text: String) {
    companion object {
        fun from(book: Textbook, block: SourceBlock): BookPassage {
            val page = book.pages.firstOrNull { it.number == block.page }
            require(page != null && !block.heading && block.start >= 0 && block.end <= page.text.length &&
                block.end > block.start && block.id == "${book.id}:p${block.page}:${block.start}" &&
                page.text.substring(block.start, block.end) == block.text) {
                "这段原文的位置无法确认，请重新选择。"
            }
            return BookPassage(book.id, page.number, block.start, block.end, block.text)
        }
    }
}
internal data class BookPassageQuestion(val passage: BookPassage, val question: String) {
    companion object {
        /**
         * The bound the provider already enforces on the trimmed question, counted in UTF-16 code
         * units (Kotlin [String.length]). The composer reads this same constant so it cannot offer
         * text that the provider will reject.
         */
        const val MAX_QUESTION_LENGTH = 500

        /** The exact predicate the provider applies; the composer must not be more permissive. */
        fun accepts(question: String) = question.trim().length in 1..MAX_QUESTION_LENGTH
    }
}
internal sealed interface BookLearningReply {
    data class Text(val text: String, val sources: List<BookPassage> = emptyList()) : BookLearningReply
    data class Unavailable(val message: String) : BookLearningReply
}

/** A real, authorized explanation/answer provider must be supplied here; source text is not a reply. */
internal interface BookLearningSupport {
    fun consentRequired(book: String): Boolean = false
    fun allow(book: String) {}
    suspend fun explain(request: BookExplanationRequest): BookLearningReply
    suspend fun ask(request: BookPassageQuestion): BookLearningReply
}

internal object UnconnectedBookLearningSupport : BookLearningSupport {
    override suspend fun explain(request: BookExplanationRequest) =
        BookLearningReply.Unavailable("这节的讲解尚未接通。可以主动打开对应原文，或先试着做题。")
    override suspend fun ask(request: BookPassageQuestion) =
        BookLearningReply.Unavailable("选段问答尚未接通，暂时不能回答。阅读位置已保留。")
}
