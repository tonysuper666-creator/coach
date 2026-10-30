package com.coach.core

import java.text.Normalizer

/** Sentence buffering keeps a reference split across SSE chunks from flashing on screen. */
class TutorOutputGuard(
    private val pending: List<PendingChoice> = emptyList(),
    private val allowExamSection: Boolean = false,
    private val streamSafePrefixes: Boolean = false,
    private val onLeak: (String) -> Unit = {},
) {
    private val raw = StringBuilder()
    private val buffered = StringBuilder()
    private val leaked = mutableSetOf<String>()
    private val committed = StringBuilder()
    private var displayed = ""
    private var revision: String? = null
    fun takeRevision(): String? = revision.also { revision = null }
    private var examSection = false
    private var examLines = 0
    var letterReferenceStripped: Boolean = false
        private set

    fun push(delta: String): String {
        raw.append(delta)
        // Detection uses original model bytes, including sentences that will be removed below.
        val normalized = normalizeLeakText(raw.toString())
        pending.forEach { p ->
            val key = normalizeLeakText(p.key.options.single { it.optionId == p.key.correctOptionId }.text)
            if (key.isNotEmpty() && key in normalized && leaked.add(p.attemptId)) onLeak(p.attemptId)
        }
        buffered.append(delta)
        while (true) {
            val end = buffered.indexOfFirst { it in "。！？.!?\n" }
            if (end < 0) break
            val sentence = buffered.substring(0,end+1)
            buffered.delete(0,end+1)
            committed.append(filter(sentence))
        }
        val tail = buffered.toString()
        val safe = if (streamSafePrefixes && !examSection && !referencesLetter(tail)) tail.take(safeCut(tail)) else ""
        return updateDisplay(committed.toString() + safe)
    }

    fun finish(): String {
        committed.append(filter(buffered.toString()))
        buffered.setLength(0)
        return updateDisplay(committed.toString())
    }

    private fun updateDisplay(next: String): String {
        val delta = if (next.startsWith(displayed)) next.substring(displayed.length) else {
            revision = next
            ""
        }
        displayed = next
        return delta
    }

    private fun safeCut(text: String): Int {
        val risk = Regex("选|答案|正确|option|choice|answer|choose|select|期中怎么考|(?<![a-z])[a-d](?![a-z])", RegexOption.IGNORE_CASE)
        var cut = risk.find(text)?.range?.first ?: text.length
        val probes = listOf("选","答案","正确","option","choice","answer","choose","select","期中怎么考")
        for (length in 1..minOf(12,text.length)) {
            val tail = text.takeLast(length).lowercase()
            if (probes.any { it.startsWith(tail) }) cut = minOf(cut,text.length-length)
        }
        return cut
    }

    private fun filter(sentence: String): String {
        if (referencesLetter(sentence)) {
            letterReferenceStripped = true
            return ""
        }
        if (sentence.trimStart().startsWith("#") && !sentence.contains("期中怎么考")) examSection = false
        if (sentence.contains("期中怎么考")) { examSection = true; examLines = 0 }
        if (examSection) {
            if (sentence.isBlank()) { examSection = false; return sentence }
            if (sentence.endsWith('\n')) examLines++
            if (!allowExamSection || examLines > 3 || (examLines == 3 && !sentence.endsWith('\n'))) return ""
        }
        return sentence
    }

    companion object {
        private val reference = Regex(
            "(?:选(?:择|项)?|答案(?:应该是|应该为|应为|应是|是|为|[:：])?|正确答案(?:是|为)?|" +
                "(?:the\\s+)?(?:correct\\s+)?(?:option|choice)|answer\\s*(?:is|:)?|choose|select)\\s*[:：]?\\s*[(（]?([A-D])(?![A-Za-z])" +
                "|(?<![A-Za-z])([A-D])\\s*(?:选项|项(?:正确|是)|is\\s+(?:the\\s+)?correct)" +
                "|^\\s*[(（]?[A-D][.)）、:：]\\s+",
            setOf(RegexOption.IGNORE_CASE,RegexOption.MULTILINE),
        )
        fun referencesLetter(text: String): Boolean = reference.containsMatchIn(Normalizer.normalize(text,Normalizer.Form.NFKC).replace(Regex("[*_`]+"),""))
        fun normalizeLeakText(text: String): String = Normalizer.normalize(text,Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
        fun modelInput(text: String): String = withoutLetterReferences(
            text.replace(Regex("(?m)^\\s*(?:[-*]\\s*)?[(（]?[A-D][).:、）]\\s+",RegexOption.IGNORE_CASE),"- ")
        )
        fun withoutLetterReferences(text: String): String {
            val guard = TutorOutputGuard(allowExamSection=true)
            return guard.push(text) + guard.finish()
        }
    }
}
