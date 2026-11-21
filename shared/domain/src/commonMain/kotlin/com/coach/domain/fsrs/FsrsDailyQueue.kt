package com.coach.domain.fsrs

/**
 * A card the daily queue may show. Cards carry the material they came from, but the queue never
 * assumes a single book: one course may mix textbook chapters, lecture handouts and past-paper
 * breakdowns, and every card competes on the same footing.
 */
data class FsrsQueueCard(
    val cardId: String,
    val materialId: String,
    val kpId: String,
    /** Syllabus weight of the knowledge point; only the ratio between cards matters. */
    val syllabusWeight: Double,
    val state: FsrsState,
) {
    init {
        require(cardId.isNotBlank() && materialId.isNotBlank() && kpId.isNotBlank())
        require(syllabusWeight >= 0.0 && syllabusWeight.isFinite())
    }
}

/** One card as the queue ranked it on a given day. */
data class QueuedCard(
    val card: FsrsQueueCard,
    /** Retrievability now; a card never reviewed counts as 0, i.e. nothing to recall yet. */
    val retrievability: Double,
    /** `syllabusWeight × (1 − R(t))` — the deferral order the product promises. */
    val urgency: Double,
    /** Whole days past its due day; 0 on the due day itself. Breaks ties, never outranks urgency. */
    val waitingDays: Long,
)

data class DailyQueuePlan(
    val day: Long,
    val cap: Int,
    val preExam: Boolean,
    val priority: List<QueuedCard>,
    val deferred: List<QueuedCard>,
) {
    /** The line the UI shows above the queue. */
    fun summary(): String = "今日优先 ${priority.size} 题 · 另有 ${deferred.size} 题顺延"

    val priorityCount: Int get() = priority.size
    val deferredCount: Int get() = deferred.size

    /** How far the most-deferred card has slipped past its due day. */
    fun longestDeferralDays(): Long? = deferred.maxOfOrNull { it.waitingDays }
}

/**
 * FIFO-free daily cap: due cards are ranked by exam value against current forgetting, the first
 * [capFor] are shown, the rest stay due and are reported as deferred instead of silently vanishing.
 *
 * The order is exactly `syllabusWeight × (1 − R(t))` descending — the deferral rule the product
 * promises — with waiting days only breaking exact ties. Nothing else may outrank exam value:
 * a FIFO or longest-waiting-first rule would quietly turn the queue back into a backlog drainer
 * and starve the heavy chapters before an exam.
 *
 * Inside the exam window the queue also *pulls cards forward*: a card another scheduler left due
 * after the exam is eligible today, because the compression promise is about the exam date, not
 * about the day some earlier interval happened to land on. Such a card enters at its true (high)
 * R(t), so it sorts last and only takes a slot the more at-risk cards do not need.
 */
class FsrsDailyQueue(
    val defaultCap: Int = 20,
    val preExamCap: Int = 30,
    val preExamWindowDays: Long = 21,
) {
    init {
        require(defaultCap >= 0 && preExamCap >= defaultCap)
        require(preExamWindowDays >= 0)
    }

    fun isPreExam(day: Long, examDay: Long?): Boolean {
        val daysToExam = examDay?.minus(day) ?: return false
        return daysToExam in 0L..preExamWindowDays
    }

    fun capFor(day: Long, examDay: Long?): Int = if (isPreExam(day, examDay)) preExamCap else defaultCap

    fun plan(cards: List<FsrsQueueCard>, day: Long, examDay: Long? = null): DailyQueuePlan {
        val cap = capFor(day, examDay)
        val ceiling = examIntervalCeiling(day, examDay)
        val due = cards.filter { isDue(it, day, ceiling) }.map { rank(it, day) }
        val ordered = due.sortedWith(
            compareByDescending<QueuedCard> { it.urgency }
                .thenByDescending { it.waitingDays }
                .thenBy { it.card.cardId }
        )
        return DailyQueuePlan(
            day = day,
            cap = cap,
            preExam = isPreExam(day, examDay),
            priority = ordered.take(cap),
            deferred = ordered.drop(cap),
        )
    }

    /**
     * Interval ceiling in force today: the exam-window cap, or null outside the window.
     * Exposed so the simulation and the UI can explain why a not-yet-due card is on the list.
     */
    fun examIntervalCeiling(day: Long, examDay: Long?): Int? =
        if (examDay != null && isPreExam(day, examDay)) FsrsConfig.examIntervalCeiling(examDay - day) else null

    private fun isDue(card: FsrsQueueCard, day: Long, ceiling: Int?): Boolean {
        val dueDay = card.state.dueDay
        if (dueDay <= day) return true
        // Scheduled beyond the exam-window ceiling: the compression pulls it back into the window.
        return ceiling != null && dueDay - day > ceiling
    }

    fun rank(card: FsrsQueueCard, day: Long): QueuedCard {
        val retrievability = Fsrs5.retrievability(card.state, day)
        val waitingDays = (day - card.state.dueDay).coerceAtLeast(0)
        return QueuedCard(
            card = card,
            retrievability = retrievability,
            urgency = card.syllabusWeight * (1.0 - retrievability),
            waitingDays = waitingDays,
        )
    }

    /** Mean retrievability of a batch; null when empty so callers do not report a fake 100%. */
    fun meanRetrievability(cards: List<QueuedCard>): Double? =
        if (cards.isEmpty()) null else cards.sumOf { it.retrievability } / cards.size
}

// A deferred card keeps its FSRS state: pushing its due day forward would corrupt the memory model,
// and hiding it would make the cap a lie. It reappears tomorrow with a lower R(t) and therefore a
// higher urgency, so the order above drains the backlog instead of letting it compound.
