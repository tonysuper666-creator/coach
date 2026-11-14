package com.coach.domain.reviewqueue

/**
 * 复习队列的真实排序：考纲权重（`exam_weights_set`）是排程输入，不是展示用的次级标签。
 *
 * - 未逾期的项先按已核星级、再按考纲分数排序；有星级输入时按考点轮流穿插，避免一个考点占满前排；
 * - 逾期达到 [starvingAfterDays] 天的项整体越过权重，并且**在饥饿组内按到期日（等得久的先）排**，
 *   而不是再按权重排。否则一个逾期 60 天的低频项会被每天新加入的「逾期 3 天高权重项」永久压住。
 *   由此得到可验证的公平上界：任何逾期到上限的项，最多让先于它的有限个饥饿项各过一次就会被排到；
 *   未逾期的项插不到它前面。
 * - 考试日来自课程配置（`courseExamDay` / [com.coach.domain.Projection.examDay]），不是写死的某门课日期。
 *   进入考前 7 天窗口后，等待上限收紧到 [examStarvingAfterDays]，让到期项在考前都过一遍。
 *
 * 纯函数、无时钟、无随机：同一批到期项和同一份权重永远得到同一个顺序。
 */
fun scheduleOrder(
    entries: List<QueueEntry>,
    day: Long,
    weights: Map<String, Int>,
    examDay: Long? = null,
    starvingAfterDays: Int = 3,
    examStarvingAfterDays: Int = 1,
    reviewStars: Map<String, Int> = emptyMap(),
): List<QueueEntry> {
    require(starvingAfterDays >= 1 && examStarvingAfterDays >= 0)
    val daysToExam = examDay?.minus(day)
    val bound = if (daysToExam != null && daysToExam in 0L..7L) examStarvingAfterDays else starvingAfterDays
    fun starving(entry: QueueEntry) = day - entry.dueDay >= bound
    val starvingEntries = entries.filter { starving(it) }
        .sortedWith(compareBy({ it.dueDay }, { it.kpId }, { it.id }))
    val waitingEntries = entries.filterNot { starving(it) }
        .sortedWith(compareBy({ -(reviewStars[it.kpId] ?: 0) }, { -(weights[it.kpId] ?: 0) }, { it.dueDay }, { it.kpId }, { it.id }))
    if (reviewStars.isEmpty()) return starvingEntries + waitingEntries
    // One entry per topic per round: a high-star topic leads, without its backlog occupying
    // every slot before lower-star coverage. The existing oldest-overdue bound still wins.
    val topics = waitingEntries.groupBy { it.kpId }.values.toList()
    val interleaved = mutableListOf<QueueEntry>()
    for (round in 0 until (topics.maxOfOrNull { it.size } ?: 0))
        for (topic in topics) topic.getOrNull(round)?.let(interleaved::add)
    return starvingEntries + interleaved
}
