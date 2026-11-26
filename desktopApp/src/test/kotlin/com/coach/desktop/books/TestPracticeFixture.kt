package com.coach.desktop.books

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path

internal fun knowledgeRecapTextbook(root: Path): Path {
    Files.createDirectories(root)
    val file = root.resolve("knowledge-recap.md")
    val sections = listOf(
        "细胞" to "细胞是生物体结构和功能的基本单位。细胞里有细胞核，并能通过分裂和分化维持生命活动的连续性。",
        "细胞膜" to "细胞膜是包围细胞的一层薄膜。它控制物质进出，同时保护内部环境并参与细胞之间的信息交流过程。",
        "细胞核" to "细胞核是细胞里储存遗传信息的结构。它里面有染色体，负责调控细胞代谢、生长以及繁殖等生命活动。",
        "染色体" to "染色体是遗传物质的载体。同一种生物的数目相对稳定，在细胞分裂过程中能够均等分配到两个子细胞中。",
        "线粒体" to "线粒体是细胞里提供能量的结构。它有自己的遗传物质，能够把有机物中的化学能转化为细胞可利用的能量。",
        "核糖体" to "核糖体是合成蛋白质的场所。它附着在内质网上或游离在细胞质中，按照遗传指令把氨基酸连接成肽链。",
    )
    Files.writeString(file, sections.mapIndexed { index, (title, body) ->
        "## 第${index + 1}节 $title\n\n$body"
    }.joinToString("\n\n"))
    return file
}

/** Test-only fixture helper replacing the removed regex cloze generator in legacy tests. */
internal fun testPracticeFixture(book: Textbook, lesson: BookLesson, mode: QuestionMode): Practice? {
    if (mode == QuestionMode.NONE) return null
    val sentences = lesson.text.split(Regex("(?<=[。！？.!?])\\s*|\\n")).map(String::trim).filter { it.length >= 12 }
    val reference = sentences.firstOrNull() ?: lesson.text.trim().takeIf { it.isNotEmpty() } ?: return null
    if (mode == QuestionMode.SHORT_ANSWER) return Practice(
        "合上原文，用自己的话说明「${lesson.title}」的主要内容，并举一个例子。", lesson.text, "", emptyList(),
    )
    val answer = Regex("[A-Za-z][A-Za-z0-9-]{3,}").find(reference)?.value ?: "A"
    return Practice(
        prompt = "根据本节原文回答：${lesson.title}",
        reference = reference,
        answer = answer,
        options = listOf(answer, "Option B", "Option C", "Option D"),
    )
}

/**
 * 2.0 宽窗里原页按版面并排两页（originalPdfSheets），文字视图宽窗两页对开（bookImmersiveColumns，最多两页），
 * 小测试书在默认测试窗里一屏就放下，「上一页 / 下一页」会整组跳或直接禁用。要逐页点按钮的测试放进这个
 * 竖窄窗：宽度放不下两页原页，也放不下两页文字，一屏只显示一页。
 */
internal val SinglePageReaderWindow = Modifier.size(600.dp, 760.dp)

/**
 * 2.0 沉浸阅读器的「下一页 / 上一页」按屏翻：一张导入页可能要翻好几屏。
 * 旧测试按「点一次 = 换一张导入页」写；这里按当前规格点到目标状态为止，次数有上限。
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.turnUntil(
    controller: TextbookController,
    tag: String,
    maxTurns: Int = 40,
    click: SemanticsNodeInteraction.() -> Unit = { performClick() },
    done: (LibraryUi) -> Boolean,
) {
    repeat(maxTurns) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
        if (done(controller.state.value)) return
        onNodeWithTag(tag).click()
        waitForIdle()
    }
    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && done(controller.state.value) }
}

/**
 * TON-1784（#570）起本地不再按模板出选择题：选择题由已配对的服务按本节第一段够长的正文生成
 * （[GeneratedLessonPractice]）。测试用本地函数顶替服务，回一道格式完整、引用发出去那段的题。
 */
internal const val GENERATED_CHOICE_PROMPT = "这段原文说的是哪一项？"
internal val GENERATED_CHOICE_OPTIONS = listOf("原文所述的内容", "与原文相反的说法", "原文没有提到的说法", "把因果关系说反了")

internal fun generatedChoiceReply(block: SourceBlock) = Explanation(
    "题干：$GENERATED_CHOICE_PROMPT 选项A：${GENERATED_CHOICE_OPTIONS[0]} 选项B：${GENERATED_CHOICE_OPTIONS[1]} " +
        "选项C：${GENERATED_CHOICE_OPTIONS[2]} 选项D：${GENERATED_CHOICE_OPTIONS[3]} 答案：A 解析：这段原文支持选项A。【c1】",
    listOf(Citation("c1", block.id, block.page)), 1)

internal fun generatedChoiceGate(root: Path) = AnswerExplain(
    ExplainConsent(root.resolve("consent")), {}, { true }, { true },
    { block, _ -> ExplainResult.Done(generatedChoiceReply(block)) },
    ask = { block, _ -> ExplainResult.Done(generatedChoiceReply(block)) },
)

/** 生成题的作答只留在这个窗口（[AnswerExplain.answers]），不写进 controller 的答题状态。 */
internal fun AnswerExplain.answerFor(book: Textbook, lesson: BookLesson): PracticeAnswer =
    answers["${book.id}:${lesson.id}"] ?: PracticeAnswer()

/** 生成题所依据的那段：本节第一段 40..4000 字的正文（与 [GeneratedLessonPractice] 同一规则）。 */
internal fun generatedChoiceBlock(lesson: BookLesson): SourceBlock =
    lesson.blocks.first { !it.heading && it.text.trim().length in 40..4000 }

/**
 * 去指定的导入页：2.0 按屏翻，一屏可放好几张导入页（对开 / 分栏），所以「点 N 次下一页」到不了确定的页。
 * 当前规格里直达某页的入口是底栏页码 → 输入页码 → 回车（ImmersiveReaderChrome 的 page-jump）。
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.jumpToPage(controller: TextbookController, page: Int) {
    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
    onNodeWithTag("reader-page-number").performClick()
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("page-jump").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("page-jump").performTextReplacement(page.toString())
    onNodeWithTag("page-jump").performKeyInput { pressKey(Key.Enter) }
    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.page == page }
}
