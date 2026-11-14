package com.coach.store

/** A tutor-readable page; learner visibility also includes the document-name filter. */
data class SourcePage(val docId: String, val pdfPage: Int, val text: String, val score: Double = 0.0, val learnerVisible: Boolean = true, val sourceSha256: String? = null) {
    val key: String get() = "$docId:$pdfPage"
}

fun tutorSourceAllowed(vararg fields: String): Boolean = fields.none {
    val value = java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC).lowercase()
    value.contains("answer") || value.contains("答案") || value.contains("sealed")
}
