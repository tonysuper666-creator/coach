package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path

/**
 * 按课程记录用户点过「不再提示」的课程页说明条。
 *
 * 与 [BookHudPreferenceStore] 同一个思路：这类「别再提醒我」只影响一次界面展示，不值得为它改课程数据库，
 * 所以按课程 id 一行一条写在工作目录下，重开窗口仍然记得。
 */
internal class CourseUiPreferenceStore(private val directory: Path) {
    private val spoilerFile: Path get() = directory.resolve("course-spoiler-dismissed.txt")

    fun isSpoilerNoticeMuted(courseId: String): Boolean = courseId.isNotBlank() && contains(spoilerFile, courseId)

    fun muteSpoilerNotice(courseId: String) {
        if (courseId.isBlank()) return
        runCatching {
            Files.createDirectories(directory)
            val existing = if (Files.exists(spoilerFile)) {
                Files.readAllLines(spoilerFile).map(String::trim).filter(String::isNotEmpty).toMutableSet()
            } else {
                linkedSetOf()
            }
            if (existing.add(courseId)) {
                Files.writeString(spoilerFile, existing.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun contains(file: Path, courseId: String): Boolean = runCatching {
        Files.exists(file) && Files.readAllLines(file).any { it.trim() == courseId }
    }.getOrDefault(false)
}
