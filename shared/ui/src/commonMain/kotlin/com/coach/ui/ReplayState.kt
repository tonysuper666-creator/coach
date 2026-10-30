package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.coach.ui.markdown.*
import kotlinx.serialization.json.*

/** One owner above window/layout branches; serialized state also survives process death. */
class ReplayState(saved: String? = null) {
    var input by mutableStateOf(TextFieldValue()); var selected by mutableStateOf("")
    var cursor by mutableStateOf(0); var running by mutableStateOf(false)
    var scrollIndex by mutableStateOf(0); var scrollOffset by mutableStateOf(0)
    var message by mutableStateOf(0)
    // Reading anchor: stable block key plus the fraction of that block scrolled past the top edge; pinned = following the tail.
    var autoplayRun by mutableStateOf(0L)
    var pinned by mutableStateOf(true); var anchorKey by mutableStateOf(-1); var anchorFraction by mutableStateOf(0f)
    init { if (saved != null) runCatching {
        val s = Json.parseToJsonElement(saved).jsonObject
        input = TextFieldValue(s["draft"]!!.jsonPrimitive.content, TextRange(s["selection"]?.jsonPrimitive?.int ?: 0))
        selected = s["option"]!!.jsonPrimitive.content; cursor = s["cursor"]!!.jsonPrimitive.int
        running = s["running"]!!.jsonPrimitive.boolean
        scrollIndex = s["scrollIndex"]!!.jsonPrimitive.int; scrollOffset = s["scrollOffset"]!!.jsonPrimitive.int
        message = s["message"]?.jsonPrimitive?.int ?: 0
        autoplayRun = s["autoplayRun"]?.jsonPrimitive?.long ?: 0L
        pinned = s["pinned"]?.jsonPrimitive?.boolean ?: true; anchorKey = s["anchorKey"]?.jsonPrimitive?.int ?: -1; anchorFraction = s["anchorFraction"]?.jsonPrimitive?.float ?: 0f
    } }
    fun snapshot() = buildJsonObject {
        put("draft", input.text); put("selection", input.selection.end); put("option", selected); put("cursor", cursor); put("running", running)
        put("scrollIndex", scrollIndex); put("scrollOffset", scrollOffset); put("message", message)
        put("autoplayRun", autoplayRun); put("pinned", pinned); put("anchorKey", anchorKey); put("anchorFraction", anchorFraction)
    }.toString()
}
data class ReplayCorpus(val messages: List<String>, val assets: Map<String, MathAsset>, val chinese: List<MathAsset>) {
    companion object {
        fun load(json: String): ReplayCorpus {
            val root = Json.parseToJsonElement(json).jsonObject
            val assets = root.getValue("assets").jsonArray.map { v -> val a = v.jsonObject
                MathAsset(a.getValue("tex").jsonPrimitive.content, a.getValue("display").jsonPrimitive.boolean, a.getValue("sha").jsonPrimitive.content, a.getValue("svg").jsonPrimitive.content)
            }
            return ReplayCorpus(root.getValue("messages").jsonArray.map { it.jsonPrimitive.content }, assets.associateBy { mathKey(it.tex, it.display) }, assets.filter { it.tex.contains(Regex("[\\u3400-\\u9fff]")) }.take(14))
        }
    }
}

fun shouldSendOnEnter(desktop: Boolean, down: Boolean, shift: Boolean, composing: Boolean): Boolean = desktop && down && !shift && !composing
