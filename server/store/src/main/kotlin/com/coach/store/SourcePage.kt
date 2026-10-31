package com.coach.store

/** A page copied from the tutor-visible course library, never an answer key. */
data class SourcePage(val docId: String, val pdfPage: Int, val text: String, val score: Double = 0.0) {
    val key: String get() = "$docId:$pdfPage"
}

fun tutorSourceAllowed(vararg fields: String): Boolean = fields.none {
    val value = java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC).lowercase()
    value.contains("answer_keys") || value.contains("答案") || value.contains("sealed")
}
