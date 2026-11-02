package com.coach.ai

/** DESIGN §5.1 的 TEACH 流：讲解/追问是「流式纯文本」，不进 JSON、不用工具、不带字母。 */
object TeachPrompt {

    const val SYSTEM = "You are a patient, precise tutor for a university health-economics course. " +
        "Answer in the student's language, in plain prose. Keep it short: 3-8 sentences unless asked for more. " +
        "Never mention option letters (A/B/C/D), never quote an exam answer key, and never use tools, shell, or files. " +
        "Write any formula as inline LaTeX delimited by double dollar signs, e.g. \$\$E = p \\cdot q\$\$. Do not output JSON."

    fun build(
        question: String,
        pack: String,
        recent: List<Pair<String, String>> = emptyList(),
        allowSourceLookup: Boolean = false,
        reviewedBasis: Boolean = false,
    ): String {
        val turns = if (recent.isEmpty()) "" else recent.joinToString("\n") { (role, text) ->
            "${if (role == "user") "Student" else "Tutor"}: ${text.take(2_000)}"
        } + "\n"
        val lookup = if (allowSourceLookup) {
            "\nIf the course pack below is not enough to answer well, append one marker at the very end of your answer: " +
                "【需查原文:<what you need to look up>】. Never put it anywhere else."
        } else if (reviewedBasis) {
            // "answer only from the course pack" makes the model verify against source text before it writes (measured 4-7 s); the reviewed explanations are already the verified basis.
            "\nDo not request extra material; the reviewed explanations above are the basis for the answer."
        } else {
            "\nDo not request extra material; answer only from the course pack below."
        }
        return """
${if (pack.isBlank()) "" else "Course pack (the only allowed basis):\n<<<\n$pack\n>>>\n"}
${if (turns.isEmpty()) "" else "Recent turns:\n$turns\n"}
Student question: $question
$lookup
        """.trim()
    }
}

/** 追问/问答的请求：真流式、Claude 主、`X-Coach-Fallback: off`（兜底由 Coach 显式发起）。 */
fun teachRequest(
    question: String,
    pack: String,
    recent: List<Pair<String, String>> = emptyList(),
    model: String = BridgeClient.CLAUDE,
    allowSourceLookup: Boolean = false,
    effort: String? = "medium",
    reviewedBasis: Boolean = false,
): ChatRequest = ChatRequest(
    task = "TEACH",
    model = model,
    system = TeachPrompt.SYSTEM,
    user = TeachPrompt.build(question, pack, recent, allowSourceLookup, reviewedBasis),
    stream = true,
    effort = effort,
)
