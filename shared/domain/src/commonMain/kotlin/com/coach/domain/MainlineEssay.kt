package com.coach.domain

import kotlinx.serialization.Serializable

/** Sent only after an answer receipt; identifiers are never rendered as teaching text. */
@Serializable
data class MainlineEssayIdentity(
    val itemId: String,
    val attemptId: String,
    val modelAnswerId: String,
    val rubricPointIds: List<String>,
) {
    val valid: Boolean get() = listOf(itemId, attemptId, modelAnswerId).all { it.isNotBlank() } &&
        rubricPointIds.isNotEmpty() && rubricPointIds.all { it.isNotBlank() } && rubricPointIds.distinct().size == rubricPointIds.size
}

@Serializable
data class MainlineRubricPoint(val id: String, val text: String)

/** Network-only body. Restoring a reader reauthorizes this identity and fetches current content. */
@Serializable
data class MainlineEssayDocument(
    val course: String,
    val identity: MainlineEssayIdentity,
    val contentVersion: String,
    val modelAnswer: String,
    val rubricPoints: List<MainlineRubricPoint>,
    val syntheticOnly: Boolean = false,
    val masteryEvidence: Boolean = false,
) {
    fun matches(course: String, identity: MainlineEssayIdentity): Boolean = this.course == course && this.identity == identity &&
        identity.valid && contentVersion.isNotBlank() && modelAnswer.isNotBlank() && !masteryEvidence &&
        rubricPoints.map { it.id } == identity.rubricPointIds && rubricPoints.all { it.text.isNotBlank() }
}
