package com.coach.ui

import com.coach.client.KpRow
import com.coach.client.KpStatus
import kotlin.test.*

class RouteAndRecordsHelpersTest {
    private fun kp(id: String, module: String, status: KpStatus = KpStatus.NEW) = KpRow(id, id, status, false, listOf(id), module)

    @Test fun routeGroupsConsecutiveKnowledgePointsByChapterAndCountsBothLayers() {
        val modules = routeModules(listOf(kp("a", "第一章", KpStatus.SOLID), kp("b", "第一章", KpStatus.PASSED), kp("c", "第一章"), kp("d", "第二章", KpStatus.IN_PROGRESS)))
        assertEquals(listOf("第一章", "第二章"), modules.map { it.module })
        assertEquals(2, modules[0].passed); assertEquals(1, modules[0].solid); assertEquals(0, modules[1].passed)
    }

    @Test fun dayLabelsCoverTheExamWeekAndLeapDays() {
        assertEquals("1月1日", dayLabel(0))
        assertEquals("11月2日", dayLabel(20_394)) // 2025-11-02
        assertEquals("2月29日", dayLabel(19_782)) // 2024-02-29
        assertEquals("12月31日", dayLabel(19_722)) 
    }
}
