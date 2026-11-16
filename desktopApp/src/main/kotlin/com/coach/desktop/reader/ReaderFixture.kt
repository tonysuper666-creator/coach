package com.coach.desktop.reader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*

/*
 * 桌面阅读窗口切片（独立于共享主界面）。
 *
 * 材料全部是明确标注的自造样张（ReaderFixture），不连书库、不连同步、不代表任何真实原句命中。
 * 唯一需要的真实接线（本文件不做）：Main.kt 里在 runCoach() 的窗口内容处改为
 *   ReaderWindow(session)，session 的页数/题目/锚点解析换成共享 reader 契约（1424/1426 合并后）。
 *
 * 适配边界：
 *  - anchorId→页码由 [ReaderFixture.pageOfAnchor] 提供，真实实现须来自锚点底座；解析不到则提示“这一页没找到出处”，不猜页。
 *  - pageCount==0 视为页数未知：不翻页、不显示页码，不把屏幕页码当书页码。
 *  - 题目栏的键位（上下选题、数字选项、Enter 查出处）在本窗口层处理，ReaderKeys 只管正文栏与通用键。
 */

data class FixtureSentence(val anchorId: String, val text: String)
data class FixtureQuestion(val stem: String, val options: List<String>, val anchorId: String)

/** 自造样张：页与句，题与出处锚点。pages 为空表示页数未知。 */
data class ReaderFixture(
    val title: String,
    val pages: List<List<FixtureSentence>>,
    val questions: List<FixtureQuestion>,
) {
    fun pageOfAnchor(anchorId: String): Int = pages.indexOfFirst { p -> p.any { it.anchorId == anchorId } }

    companion object {
        fun synthetic(pageCount: Int = 8): ReaderFixture {
            val pages = List(pageCount) { p -> List(3) { s -> FixtureSentence("p$p-s$s", "样张第${p + 1}段第${s + 1}句：这是自造的示例文字，不来自任何教材。") } }
            val qs = listOf(
                FixtureQuestion("样张题一：第3页第2句说了什么？", listOf("甲", "乙", "丙"), "p2-s1"),
                FixtureQuestion("样张题二：第6页第1句说了什么？", listOf("甲", "乙", "丙"), "p5-s0"),
                FixtureQuestion("样张题三：出处缺失的题", listOf("甲", "乙", "丙"), "missing-anchor"),
            )
            return ReaderFixture("自造样张（非教材）", pages, qs)
        }
    }
}
