package com.coach.domain.reviewqueue

/**
 * 复习队列的真实排序：考纲权重（`exam_weights_set`）是排程输入，不是展示用的次级标签。
 *
 * - 权重高的先排，同权重按到期日、考点、条目身份稳定排序；
 * - 逾期达到 [starvingAfterDays] 天的项越过权重先排：低频内容等得够久就一定被排到，不会永久饿死；
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
): List<QueueEntry> {
    require(starvingAfterDays >= 1 && examStarvingAfterDays >= 0)
    val daysToExam = examDay?.minus(day)
    val bound = if (daysToExam != null && daysToExam in 0L..7L) examStarvingAfterDays else starvingAfterDays
    return entries.sortedWith(
        compareBy<QueueEntry>(
            { if (day - it.dueDay >= bound) 0 else 1 },
            { -(weights[it.kpId] ?: 0) },
            { it.dueDay },
            { it.kpId },
            { it.id },
        )
    )
}
