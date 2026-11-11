package com.coach.domain

private val BRACKETED = Regex("【[^】]*】")
private val SEPARATOR = Regex("""\s+[｜|]\s+(?=【)""")
private val SPACES = Regex("""[ 	 　]+""")

/** Learner-facing title: first segment with every 【…】 calendar marker removed. The raw title stays in the ledger. */
fun cleanTitle(raw: String): String {
    val main = raw.split(SEPARATOR, limit = 2).first().replace(BRACKETED, "").replace(SPACES, " ").trim()
    return main.ifBlank { raw.replace("【", "").replace("】", "").trim() }
}

/** The one status rule for a knowledge point; the course list and the chat answer both call it. */
enum class KpState(val label: String) { NEW("未学"), IN_PROGRESS("在学"), PASSED("会了"), SOLID("稳了"), NEEDS_REPAIR("要补") }

fun kpState(m: Mastery?, opened: Boolean): KpState = when {
    m?.needsRepair == true -> KpState.NEEDS_REPAIR
    m?.solid == true -> KpState.SOLID
    m?.passed == true -> KpState.PASSED
    opened -> KpState.IN_PROGRESS
    else -> KpState.NEW
}
