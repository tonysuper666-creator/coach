package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path

/** Human reasons for an import the reader can act on. Wording stays stable for the rejection tests. */
internal object BookImportMessages {
    const val NOT_PDF = "这不是 PDF 文件。请换一份 PDF 再导入。"
    const val TRUNCATED = "这份 PDF 下到一半就断了，文件已损坏。请换一份完整的文件再导入。"
    const val PASSWORD = "这本 PDF 有密码保护。请输入打开密码后再导入。"
    const val WRONG_PASSWORD = "这本 PDF 有密码保护。刚才的密码不对，请再输入一次。"

    fun damagedPages(pages: List<Int>): String {
        val listed = pages.distinct().filter { it > 0 }.sorted().joinToString("、")
        return "第 $listed 页损坏，已跳过。其余页可以照常读。"
    }

    fun asksForPassword(message: String?): Boolean = message != null && "密码保护" in message

    /** A password prompt is still an open import. A wrong password, a damaged file, or a refused format is a failure. */
    fun isRecordedImportFailure(message: String): Boolean = when {
        message == PASSWORD -> false
        message.startsWith(WRONG_PASSWORD) || message.startsWith(NOT_PDF) || message.startsWith(TRUNCATED) -> true
        message.startsWith("这份教材不完整或已损坏") -> true
        message.startsWith("这个压缩包里没有可读的教材") -> true
        message.startsWith("这份 Office 文件可能已加密") -> true
        message.startsWith("无法确认这份文本的编码") -> true
        message.startsWith("请使用 PDF、电子书") -> true
        else -> false
    }

    fun pdfOpenFailure(file: Path, error: Exception, passwordAttempted: Boolean): IllegalArgumentException {
        if (error is BookPdfPasswordException || error.cause is BookPdfPasswordException) {
            val message = if (passwordAttempted) WRONG_PASSWORD else PASSWORD
            return IllegalArgumentException(message, error)
        }
        if (error is IllegalArgumentException && !error.message.isNullOrBlank()) return error
        val header = runCatching {
            Files.newInputStream(file).use { it.readNBytes(5) }.toString(Charsets.ISO_8859_1)
        }.getOrDefault("")
        return if (header.startsWith("%PDF")) IllegalArgumentException(TRUNCATED, error)
        else IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", error)
    }
}
