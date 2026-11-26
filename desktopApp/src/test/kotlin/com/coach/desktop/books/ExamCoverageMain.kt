package com.coach.desktop.books

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offline coverage command.
 *
 *   gradlew :desktopApp:examCoverage -Pexam.bank=... -Pexam.holdout=... -Pexam.out=...
 *
 * Optional -Pexam.teaching=pages.json and -Pexam.textbook=textbook.txt.
 * The report lists point names and reason classes only. It is refused inside a git checkout.
 */
fun main(args: Array<String>) {
    val flags = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        val value = args.getOrNull(index + 1) ?: error("缺少 $key 的路径")
        flags[key.removePrefix("--")] = value
        index += 2
    }
    val bankPath = flags["bank"] ?: error("需要 --bank")
    val holdoutPath = flags["holdout"] ?: error("需要 --holdout")
    val outPath = Path.of(flags["out"] ?: error("需要 --out")).toAbsolutePath()
    refuseCheckout(outPath)
    val bank = ExamCoverage.readBank(Path.of(bankPath))
    val teaching = ExamCoverage.readTeaching(flags["teaching"]?.let(Path::of))
    val identified = ExamCoverage.readIdentified(flags["identified"]?.let(Path::of), bank, teaching)
    val textbook = when {
        flags["textbook"] != null -> Files.readString(Path.of(flags["textbook"]))
        teaching.isNotEmpty() -> teaching.filter { it.role == CourseMaterialRole.CORE_TEXTBOOK }.joinToString("\n") { it.text }
        else -> ""
    }
    val questions = HeldOutDecomposer.questionsFromDocument(Files.readString(Path.of(holdoutPath)))
    val report = ExamCoverage.evaluate(questions, identified, bank, textbook)
    Files.createDirectories(outPath.parent)
    Files.writeString(outPath, report.toJson().toString(), StandardCharsets.UTF_8)
    println("heldOutPoints=${report.heldOutPoints} covered=${report.covered} coverage=${"%.4f".format(report.rate)}")
    report.uncovered.forEach { println("${it.reason}\t${it.name}") }
}

private fun refuseCheckout(out: Path) {
    var dir = out.parent
    while (dir != null) {
        if (Files.exists(dir.resolve(".git"))) error("报告不能写进 git 仓库：$out")
        dir = dir.parent
    }
}
