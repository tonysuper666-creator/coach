package com.coach.ui.reader.v2

import kotlinx.serialization.json.*

/** Normalized top-left coordinates of the *rendered full page*. Native adapters
 * must convert the anchor contract's crop/rotation coordinates before supplying these. */
data class ReaderRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
    val width get() = right - left
    val height get() = bottom - top
    fun union(other: ReaderRegion) = ReaderRegion(minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom))
    companion object { val Full = ReaderRegion(0f, 0f, 1f, 1f) }
}

/** Supply only after both the shared index and the native text layer agree.
 * A page-only legacy anchor or an OCR paragraph is insufficient. */
data class NativeSourceTarget(val documentId: String, val sha256: String, val anchorId: String,
    val pageIndex0: Int, val boxes: List<ReaderRegion>) {
    init { require(documentId.isNotBlank() && anchorId.isNotBlank() && pageIndex0 >= 0 && boxes.isNotEmpty()) }
}
enum class ReaderSourceFailure(val message: String) {
    MISSING("这一页没找到出处"), CHANGED("原句改过了，对不上"),
    AMBIGUOUS("出处不止一处，无法确认"), RECHECK("出处需要重新核对")
}
sealed interface ReaderSourceResolution {
    data class Found(val target: NativeSourceTarget) : ReaderSourceResolution
    data class Unconfirmed(val reason: ReaderSourceFailure) : ReaderSourceResolution
}
data class ReaderQuestion(val id: String, val text: String, val source: ReaderSourceResolution)
enum class ReaderFocus { READING, QUESTIONS }
enum class ReaderDrawer { NONE, QUESTIONS, SOURCE }
data class ReaderPosition(val pageIndex0: Int = 0, val columnId: Int? = null) {
    init { require(pageIndex0 >= 0) }
}

/** Window changes never rewrite this document/session state. Hoist above layouts. */
data class ReaderSession(
    val documentId: String,
    val sha256: String,
    val position: ReaderPosition = ReaderPosition(),
    val focus: ReaderFocus = ReaderFocus.READING,
    val selectedQuestionId: String? = null,
    val answers: Map<String, String> = emptyMap(),
    val noteDraft: String = "",
    val selectionAnchorId: String? = null,
    val confirmationNeeded: Boolean = false,
    val railVisible: Boolean = true,
    val overview: Boolean = false,
    val drawer: ReaderDrawer = ReaderDrawer.NONE,
    val returnPosition: ReaderPosition? = null,
    val source: NativeSourceTarget? = null,
    val sourceFailure: ReaderSourceFailure? = null,
    val flash: Int = 0,
) {
    fun showSource(question: ReaderQuestion): ReaderSession {
        val target = (question.source as? ReaderSourceResolution.Found)?.target
        val valid = target?.takeIf { it.documentId == documentId && it.sha256 == sha256 }
        val failure = (question.source as? ReaderSourceResolution.Unconfirmed)?.reason
            ?: if (valid == null) ReaderSourceFailure.CHANGED else null
        return copy(selectedQuestionId = question.id, drawer = ReaderDrawer.SOURCE,
            returnPosition = returnPosition ?: position, source = valid, sourceFailure = failure,
            confirmationNeeded = failure != null, flash = flash + 1)
    }

    fun closeDrawer() = copy(drawer = ReaderDrawer.NONE, position = returnPosition ?: position,
        returnPosition = null, source = null, sourceFailure = null)

    fun answer(questionId: String, text: String) = copy(answers = answers + (questionId to text))

    /** Private local state only. No source text or source geometry is persisted.
     * Recreated source drawers require native re-verification before highlighting. */
    fun snapshot(): String = buildJsonObject {
        put("schemaVersion", 1); put("documentId", documentId); put("sha256", sha256)
        put("pageIndex0", position.pageIndex0); position.columnId?.let { put("columnId", it) }
        put("focus", focus.name); selectedQuestionId?.let { put("questionId", it) }
        put("answers", buildJsonObject { answers.forEach { (id, answer) -> put(id, answer) } })
        put("noteDraft", noteDraft); selectionAnchorId?.let { put("selectionAnchorId", it) }
        put("confirmationNeeded", confirmationNeeded); put("railVisible", railVisible); put("overview", overview)
        put("drawer", drawer.name)
        returnPosition?.let { p -> put("returnPosition", buildJsonObject {
            put("pageIndex0", p.pageIndex0); p.columnId?.let { put("columnId", it) }
        }) }
    }.toString()

    companion object {
        fun restore(snapshot: String): ReaderSession? = runCatching {
            val j = Json.parseToJsonElement(snapshot).jsonObject
            require(j.getValue("schemaVersion").jsonPrimitive.int == 1)
            fun text(key: String) = j[key]?.jsonPrimitive?.content
            val drawer = ReaderDrawer.valueOf(j.getValue("drawer").jsonPrimitive.content)
            ReaderSession(documentId = j.getValue("documentId").jsonPrimitive.content,
                sha256 = j.getValue("sha256").jsonPrimitive.content,
                position = ReaderPosition(j.getValue("pageIndex0").jsonPrimitive.int, j["columnId"]?.jsonPrimitive?.int),
                focus = ReaderFocus.valueOf(j.getValue("focus").jsonPrimitive.content),
                selectedQuestionId = text("questionId"),
                answers = j.getValue("answers").jsonObject.mapValues { it.value.jsonPrimitive.content },
                noteDraft = text("noteDraft") ?: "", selectionAnchorId = text("selectionAnchorId"),
                confirmationNeeded = drawer == ReaderDrawer.SOURCE || j.getValue("confirmationNeeded").jsonPrimitive.boolean,
                railVisible = j.getValue("railVisible").jsonPrimitive.boolean, drawer = drawer,
                overview = j["overview"]?.jsonPrimitive?.boolean ?: false,
                returnPosition = j["returnPosition"]?.jsonObject?.let {
                    ReaderPosition(it.getValue("pageIndex0").jsonPrimitive.int, it["columnId"]?.jsonPrimitive?.int)
                }, sourceFailure = if (drawer == ReaderDrawer.SOURCE) ReaderSourceFailure.RECHECK else null)
        }.getOrNull()
    }
}
