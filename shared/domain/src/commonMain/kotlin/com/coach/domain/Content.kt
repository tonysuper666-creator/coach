package com.coach.domain

import kotlinx.serialization.Serializable

/** Course content contains no learner position, answers, mastery or review state. */
@Serializable data class Course(
    val id: String, val code: String, val title: String,
    val tz: String = "Europe/London", val dayStartHour: Int = 4,
    val instructionsMd: String = "", val examWeights: Map<String, Int> = emptyMap(),
    val examWeightGroups: List<ExamWeightGroup> = emptyList()
)
@Serializable data class ExamWeightGroup(
    val question: String, val points: Int, val kpIds: List<String>,
    val allocations: Map<String, Int>, val estimated: Boolean = true
)
@Serializable data class Module(val id: String, val order: Int, val title: String)
@Serializable data class SourceAnchor(
    val docId: String, val pdfPage: Int, val bookPage: Int? = null,
    val quote: String? = null, val basis: String = "CHAPTER_RANGE"
)
@Serializable data class KnowledgePoint(
    val id: String, val moduleId: String, val order: Int, val title: String,
    val kind: String? = null, val examFormats: Set<Format> = emptySet(),
    val prerequisites: List<String> = emptyList(), val anchors: List<SourceAnchor> = emptyList(),
    val retired: Boolean = false, val legacyId: String? = null,
    val conceptIds: Set<String> = emptySet()
)
@Serializable data class CourseContent(
    val course: Course, val modules: List<Module>, val knowledgePoints: List<KnowledgePoint>
)
@Serializable data class TextbookPage(val pdfPage: Int, val bookPage: Int?, val text: String)
@Serializable data class TextbookDocument(
    val id: String, val title: String, val markdown: String, val pages: List<TextbookPage>
)
@Serializable data class ContentSeed(val content: CourseContent, val documents: List<TextbookDocument>)
