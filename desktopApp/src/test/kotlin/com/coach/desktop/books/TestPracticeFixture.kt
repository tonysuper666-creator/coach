package com.coach.desktop.books

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
