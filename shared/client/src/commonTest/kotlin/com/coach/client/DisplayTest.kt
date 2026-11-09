package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

class DisplayTest {
    private fun row(id: String, status: KpStatus, title: String = "【W3·Ch6】$id", module: String = "03 医院与医生") =
        KpRow(id, title, status, explained = false, conceptIds = listOf(id), module = module)

    @Test fun oneVocabularyForEveryStatus() {
        assertEquals(listOf("未学", "在学", "会了", "稳了", "要补"), KpStatus.entries.map { it.label })
        val banned = listOf("已读", "未读", "在读", "还缺", "要回炉", "需修复", "需回炉", "学习中", "稳固", "已掌握", "已通过", "未开始")
        for (s in KpStatus.entries) for (w in banned) assertFalse(w in s.label + s.symbol, "${s.name} shows $w")
        assertEquals(5, KpStatus.entries.map { it.symbol }.toSet().size, "each status has its own symbol")
    }

    @Test fun markersNeverReachTheTitle() {
        assertEquals("医院市场结构", titleMain("【W3·slides】医院市场结构"))
        assertEquals("医院市场结构", titleMain("【Essay】【W3·slides】医院市场结构"))
        assertEquals("医院市场结构：差异化寡头", titleMain("【W3·Ch6】医院市场结构：【Essay】差异化寡头 ｜ 【W4·Ch8】另一个"))
        assertEquals("没有标签的标题", titleMain("没有标签的标题"))
        assertEquals("P(A | B)", titleMain("P(A | B)"))
        assertEquals("W3·Ch6", displayTitle("【W3·Ch6】"), "a title that is only a marker never renders empty")
        for (t in listOf("【W3·slides】【Essay】x", "x【Essay】", "【a】【b】【c】d")) assertFalse(Regex("[【】]").containsMatchIn(titleMain(t)), t)
    }

    @Test fun sourceLineIsPlainLanguage() {
        assertEquals("第 3 周 · 课件", titleSource("【W3·slides】X"))
        assertEquals("第 4 周 · 教材第 8 章", titleSource("【W4·Ch8】X"))
        assertEquals("第 1 周 · 教材第 1–2 章 · 论述常考", titleSource("【W1·Ch1–2】【Essay】X"))
        assertEquals("", titleSource("X"))
    }

    @Test fun countsAreShownAsLearnedOverTotal() {
        val rows = listOf(row("a", KpStatus.SOLID), row("b", KpStatus.PASSED), row("c", KpStatus.IN_PROGRESS), row("d", KpStatus.NEEDS_REPAIR), row("e", KpStatus.NEW))
        assertEquals("会了 2 / 5", learnedCount(rows))
        assertEquals("5 节 · 会了 2 · 在学 1 · 要补 1", statusSummary(rows))
        assertEquals("2 节 · 会了 0", statusSummary(listOf(row("a", KpStatus.NEW), row("b", KpStatus.NEW))))
    }

    @Test fun weekLineUsesTheCourseNameNotTheModuleNumber() {
        val outline = Event(1, "e1", "C", "outline_revised", recordedAt = 1, occurredAt = 1, deviceId = "a", actor = "SYSTEM", payload = json(
            "modules" to JsonArray(listOf(json("id" to "m3", "title" to "03 医院与医生"))),
            "knowledgePoints" to JsonArray(listOf(
                json("id" to "a", "moduleId" to "m3", "title" to "【W3·Ch6】A"), json("id" to "b", "moduleId" to "m3", "title" to "【W3·Ch6】B"),
                json("id" to "c", "moduleId" to "m3", "title" to "【W3·Ch6】C")))))
        val view = CourseView(listOf(outline), project(listOf(outline)).let { p ->
            p.copy(mastery = mapOf("a" to Mastery(passed = true), "b" to Mastery(passed = true, solid = true)))
        })
        assertEquals("第 3 周 · 医院与医生 · 2/3", view.weekDirectoryLine(3))
        assertFalse("03" in view.weekDirectoryLine(3))
    }
}
