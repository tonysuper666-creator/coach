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
    /** Whole days past its due day; 0 on the due day itself. */
    val waitingDays: Long,
    /** A card waiting [FsrsDailyQueue.starvingAfterDays] or more is promoted ahead of weight. */
    val starving: Boolean,
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
 * Fairness bound: a card deferred for [starvingAfterDays] days outranks non-starving cards whatever
 * its weight, so a low-weight chapter cannot be starved forever by the exam-weighted order.
 * Inside each band the order is exactly `syllabusWeight × (1 − R(t))` descending.
 */
class FsrsDailyQueue(
    val defaultCap: Int = 20,
    val preExamCap: Int = 30,
    val preExamWindowDays: Long = 21,
    val starvingAfterDays: Long = 3,
    private val config: FsrsConfig = FsrsConfig(),
) {
    init {
        require(defaultCap >= 0 && preExamCap >= defaultCap)
        require(preExamWindowDays >= 0 && starvingAfterDays >= 1)
    }

    fun isPreExam(day: Long, examDay: Long?): Boolean {
        val daysToExam = examDay?.minus(day) ?: return false
        return daysToExam in 0L..preExamWindowDays
    }

    fun capFor(day: Long, examDay: Long?): Int = if (isPreExam(day, examDay)) preExamCap else defaultCap

    fun plan(cards: List<FsrsQueueCard>, day: Long, examDay: Long? = null): DailyQueuePlan {
        val cap = capFor(day, examDay)
        val due = cards.filter { it.state.dueDay <= day }.map { rank(it, day) }
        val ordered = due.sortedWith(
            compareByDescending<QueuedCard> { it.starving }
                .thenByDescending { it.waitingDays }
                .thenByDescending { it.urgency }
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

    fun rank(card: FsrsQueueCard, day: Long): QueuedCard {
        val retrievability = Fsrs5.retrievability(card.state, day)
        val waitingDays = (day - card.state.dueDay).coerceAtLeast(0)
        return QueuedCard(
            card = card,
            retrievability = retrievability,
            urgency = card.syllabusWeight * (1.0 - retrievability),
            waitingDays = waitingDays,
            starving = waitingDays >= starvingAfterDays,
        )
    }

    /** Mean retrievability of a batch; null when empty so callers do not report a fake 100%. */
    fun meanRetrievability(cards: List<QueuedCard>): Double? =
        if (cards.isEmpty()) null else cards.sumOf { it.retrievability } / cards.size
}

// A deferred card keeps its FSRS state: pushing its due day forward would corrupt the memory model,
// and hiding it would make the cap a lie. It reappears tomorrow with a lower R(t) and therefore a
// higher urgency, so the order above drains the backlog instead of letting it compound.
