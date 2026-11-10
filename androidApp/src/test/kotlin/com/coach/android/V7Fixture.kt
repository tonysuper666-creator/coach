package com.coach.android
import com.coach.ui.v7.*

/** Reference illustrations only, never read from or written to a private course corpus. */
internal fun fixtureDetails(dark: Boolean): V7PageDetails {
    val svg = """<svg class="thumb" viewBox="0 0 300 170" xmlns="http://www.w3.org/2000/svg">
    <rect fill="PAPER" width="300" height="170"/>
    <rect fill="TEXT" x="18" y="14" width="150" height="4.5" rx="2"/><rect fill="TEXT" x="18" y="24" width="236" height="4.5" rx="2"/>
    <path fill="HIGHLIGHT" opacity=".85" d="M200 103 L270 103 L270 138 Z"/>
    <path fill="none" stroke="SUB" stroke-width=".8" stroke-dasharray="3 3" d="M58 103 H272"/><path fill="none" stroke="SUB" stroke-width=".8" stroke-dasharray="3 3" d="M58 126 H246"/>
    <path fill="none" stroke="SUB" stroke-width="1.2" d="M58 38 V140 H276"/><path fill="none" stroke="currentColor" stroke-width="1.6" d="M70 43 L272 139"/>
    <rect fill="TEXT" x="58" y="152" width="128" height="4" rx="2"/><rect fill="TEXT" x="192" y="152" width="50" height="4" rx="2"/>
    <rect fill="none" stroke="LINE" stroke-width="1" x=".5" y=".5" width="299" height="169"/></svg>"""
        .replace("PAPER", if (dark) "#24211D" else "#FFFFFF").replace("TEXT", if (dark) "#34302A" else "#E3DCCF")
        .replace("HIGHLIGHT", if (dark) "#6A5730" else "#EED596").replace("SUB", if (dark) "#A0978A" else "#665F55")
        .replace("LINE", if (dark) "#34302A" else "#E3DCCF")
    return V7PageDetails(
        course = "健康经济学", sectionLabel = "8.3", sectionTitle = "事后道德风险", readStatus = "上次读到第 3 段", deck = "有了保险，人会多用医疗",
        meta = "出处 教材 · 道德风险一节  ·  课上强调 ★ 2 处  ·  约 6 分钟",
        lectureLabel = "课上 · 第 8 周录音 14:32", lecture = "“共付不是罚款，是把价格信号还给病人。”",
        personalNote = "别和第 7 周的逆向选择搞混：一个在投保后，一个在投保前。",
        sourceLabel = "教材原页 · p.214 · 图 8-2", sourceCaption = "需求曲线与福利损失", sourceSvg = svg, thumbnailCaption = "共付把价格从 P=0 拉回到虚线处，三角形变小。",
        highlight = "价格变低后会多买医疗服务", selection = "边际收益低于社会承担的边际成本", emphasis = "需求对价格有多敏感",
        types = listOf("选择", "选择 · 论述", "选择 · 分析", "分析", "论述", "论述", "论述"),
        weekStats = "8 节 · 会了 2 · 在学 1 · 要补 2  ·  本周课上强调 5 处  ·  距期末 36 天",
        nextStep = "先把 8.3 读完，再补 8.4 共付和起付线的图。第 7 周“逆向选择”标了要补，周末看。",
        mustKnow = listOf("自付价格降低 → 沿需求曲线右移 → 多出的消费形成福利损失原页", "这是对价格的正常反应，不是骗保 ★", "需求越敏感，损失越大"),
        missing = listOf("能画出图，标出三角形的两条边", "说清它和逆向选择的区别"),
        revisitLabel = "要补 · 第 7 周", revisit = "逆向选择：投保前的信息不对称。和 8.3 容易混。",
        directory = listOf(V7DirectoryEntry(5, "健康需求", "8/8"), V7DirectoryEntry(6, "保险基础", "6/6"), V7DirectoryEntry(7, "逆向选择", "2/5"),
            V7DirectoryEntry(8, "道德风险", "3/8"), V7DirectoryEntry(9, "成本效果", "0/7"), V7DirectoryEntry(10, "医疗市场", "0/6")),
        related = listOf(V7Related("8.2 事前道德风险", "✓ 会了", true), V7Related("第 7 周 逆向选择", "! 要补")),
        shelf = listOf("教材 · 健康经济学", "文献 · 3 篇", "闲书 · 1 本"),
        question = V7MarginQuestion("共付比例从 0 提到 20%，下面哪一项最可能发生？", listOf("需求量不变", "需求量下降，损失变小", "逆向选择随之消失"), 1,
            "为什么是 B：自付价格升高，需求沿曲线左移，福利损失三角形缩小。", 3, "出处 · 教材 p.214"))
}
