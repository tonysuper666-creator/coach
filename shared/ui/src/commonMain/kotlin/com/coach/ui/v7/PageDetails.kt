package com.coach.ui.v7

import androidx.compose.runtime.*

/** Optional, already-loaded reading annotations. This is presentation data, never a second course source. */
@Immutable data class V7PageDetails(
    val course: String = "", val sectionLabel: String = "", val sectionTitle: String = "", val readStatus: String = "", val deck: String = "", val meta: String = "",
    val lectureLabel: String = "", val lecture: String = "", val personalNote: String = "",
    val sourceLabel: String = "", val sourceCaption: String = "", val sourceSvg: String = "", val thumbnailCaption: String = "", val sourceText: String = "",
    val highlight: String = "", val selection: String = "", val emphasis: String = "",
    val highlights: List<String> = emptyList(),
    val types: List<String> = emptyList(), val weekStats: String = "", val nextStep: String = "",
    val mustKnow: List<String> = emptyList(), val missing: List<String> = emptyList(),
    val revisitLabel: String = "", val revisit: String = "",
    val directory: List<V7DirectoryEntry> = emptyList(), val shelf: List<String> = emptyList(),
    val question: V7MarginQuestion? = null, val related: List<V7Related> = emptyList(),
)
@Immutable data class V7DirectoryEntry(val week: Int, val title: String, val state: String)
@Immutable data class V7Related(val title: String, val status: String, val passed: Boolean = false)
@Immutable data class V7MarginQuestion(val stem: String, val options: List<String>, val answer: Int, val explanation: String, val count: Int, val source: String = "")
val LocalV7PageDetails = staticCompositionLocalOf<V7PageDetails?> { null }
