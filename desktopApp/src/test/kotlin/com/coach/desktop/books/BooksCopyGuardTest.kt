package com.coach.desktop.books

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UX-13 静态守卫：扫描 `shared/books/src/main/kotlin` 所有非注释字符串字面量，
 * 严禁暴露内部设备名、上游模型厂商名或诊断错误码。
 */
class BooksCopyGuardTest {
    private val banned = listOf(
        "Anthropic", "OpenAI", "Gemini", "Claude", "DeepSeek", "Grok", "ChatGPT",
        "Mac", "winbox", "朋友服务",
        "HTTP 4", "HTTP 5", "traceId",
        "智能", "小助手", "✨",
    )
    private val bannedAiWord = Regex("(?<![A-Za-z0-9_])AI(?![A-Za-z0-9_])")

    @Test fun sharedBooksUserVisibleCopyHasNoBannedTerms() {
        val root = locateSharedBooksMain()
        val offenders = mutableListOf<String>()

        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            for (text in extractKotlinStringLiterals(file.readText())) {
                banned.firstOrNull { it in text }?.let { term ->
                    offenders += "${file.relativeTo(root).invariantSeparatorsPath}: 「$text」含禁用词「$term」"
                }
                if (bannedAiWord.containsMatchIn(text)) {
                    offenders += "${file.relativeTo(root).invariantSeparatorsPath}: 「$text」含禁用词「AI」"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "shared/books/src/main/kotlin 中出现 UX-13 禁用词：\n" + offenders.joinToString("\n"),
        )
    }

    @Test fun explainPrivacyUsesPlainLearningServiceCopy() {
        val samples = listOf(
            ExplainPrivacy.explain("Book"),
            ExplainPrivacy.page("Book"),
            ExplainPrivacy.ask("Book"),
            ExplainPrivacy.answer("Book"),
        )
        for (copy in samples) {
            assertTrue("学习服务" in copy, "Expected '学习服务' in: $copy")
            for (term in banned) {
                assertFalse(term in copy, "Unexpected banned term '$term' in: $copy")
            }
        }
    }

    /** Linear-time lexer skipping `//` and `/* */` comments and extracting `"..."` and `"""..."""` literals. */
    private fun extractKotlinStringLiterals(source: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val n = source.length
        while (i < n) {
            when {
                // Line comment
                i + 1 < n && source[i] == '/' && source[i + 1] == '/' -> {
                    i += 2
                    while (i < n && source[i] != '\n') i++
                }
                // Block comment (supports nesting in Kotlin)
                i + 1 < n && source[i] == '/' && source[i + 1] == '*' -> {
                    var depth = 1
                    i += 2
                    while (i + 1 < n && depth > 0) {
                        if (source[i] == '/' && source[i + 1] == '*') { depth++; i += 2 }
                        else if (source[i] == '*' && source[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                }
                // Raw triple-quoted string
                i + 2 < n && source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"' -> {
                    val start = i + 3
                    i = start
                    while (i + 2 < n && !(source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"')) i++
                    result += source.substring(start, i.coerceAtMost(n))
                    i = (i + 3).coerceAtMost(n)
                }
                // Regular double-quoted string
                source[i] == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < n) {
                            sb.append(source[i]).append(source[i + 1])
                            i += 2
                        } else {
                            sb.append(source[i])
                            i++
                        }
                    }
                    result += sb.toString()
                    if (i < n) i++
                }
                // Character literal
                source[i] == '\'' -> {
                    i++
                    if (i < n && source[i] == '\\') i += 2 else i++
                    if (i < n && source[i] == '\'') i++
                }
                else -> i++
            }
        }
        return result
    }

    private fun locateSharedBooksMain(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var dir: File? = start
        while (dir != null) {
            File(dir, "shared/books/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            if (dir.name == "books") File(dir, "src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("找不到 shared/books/src/main/kotlin（从 $start 起找）")
    }
}
