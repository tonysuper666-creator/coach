package com.coach.desktop.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.V7TextButton
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * One file from the latest drop onto a course, with the type the app decided on.
 * [role] null means 往年题: only its exam techniques are kept, never the question text.
 */
internal data class CourseIntakeItem(
    val key: String,
    val fileName: String,
    val role: CourseMaterialRole?,
    val bookId: String? = null,
    /** The drop created this library entry, so turning it into a past paper may delete it again. */
    val newlyStaged: Boolean = false,
    val weekNum: Int? = null,
    val paperKey: String? = null,
    val techniqueCount: Int = 0,
    val note: String? = null,
)

internal data class CourseIntakeReceipt(val courseId: String, val items: List<CourseIntakeItem>)

internal object CourseIntake {
    fun isPastPaperFile(path: Path): Boolean {
        val name = path.fileName?.toString().orEmpty()
        return CourseClientGates.isPastExamMaterial(name, name)
    }

    /** Reads the paper once, keeps (exam point, technique) pairs keyed by its content hash; the file itself is not copied. */
    fun importPaper(db: Connection, courseId: String, path: Path, key: String = path.toString()): CourseIntakeItem {
        val name = path.fileName?.toString().orEmpty()
        val base = CourseIntakeItem(key = key, fileName = name, role = null)
        if (name.substringAfterLast('.', "").lowercase() !in CoursePastPaper.supportedExtensions) {
            return base.copy(note = "往年题只认 PDF、TXT、Markdown，这份没读")
        }
        if (!Files.isRegularFile(path)) return base.copy(note = "原文件不在了，请重新拖进来")
        if (!CoursePastPaper.hasExamPoints(db, courseId)) return base.copy(note = "还没有考点：先放教材和课件，再放往年题")
        val count = runCatching { CoursePastPaper.import(db, courseId, path) }
            .getOrElse { return base.copy(note = "这份往年题读不出文字") }
        return base.copy(
            paperKey = CoursePastPaper.paperKey(path),
            techniqueCount = count,
            note = if (count == 0) "没对上已有考点" else null,
        )
    }

    fun summary(items: List<CourseIntakeItem>, isExam: Boolean): String? {
        val materials = items.count { it.role != null }
        val papers = items.filter { it.role == null }
        val parts = buildList {
            if (materials > 0) add(if (isExam) "已补充 $materials 份材料并更新考点" else "已添加 $materials 份材料")
            if (papers.isNotEmpty()) {
                add("${papers.size} 份当往年题，提取 ${papers.sumOf { it.techniqueCount }} 条考法，题干不保存")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
    }
}

private val CourseIntakeItem.typeLabel: String get() = role?.labelZh ?: "往年题"

@Composable
internal fun CourseIntakeReceiptCard(
    receipt: CourseIntakeReceipt,
    busy: Boolean,
    onChangeType: (key: String, role: CourseMaterialRole?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoachTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("course-intake-receipt"),
        shape = RoundedCornerShape(8.dp),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("刚放进来", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                    CoachCardMeta("类型认错了，点类型直接改。")
                }
                V7TextButton(onClick = onDismiss, modifier = Modifier.testTag("course-intake-dismiss")) { Text("收起") }
            }
            for (item in receipt.items) {
                var open by remember(item.key) { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("course-intake-row-${item.fileName}"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.fileName, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = item.note ?: when {
                            item.role == null -> "提取 ${item.techniqueCount} 条考法 · 题干不保存"
                            item.weekNum != null -> "第 ${item.weekNum} 周"
                            else -> "未分周"
                        }
                        Text(meta, style = MaterialTheme.typography.bodySmall,
                            color = if (item.note != null) colors.dangerFg else colors.fgSecondary,
                            modifier = Modifier.testTag("course-intake-meta-${item.fileName}"))
                    }
                    Box {
                        PaperButton(
                            onClick = { open = true },
                            enabled = !busy,
                            modifier = Modifier.testTag("course-intake-type-${item.fileName}"),
                        ) { Text("${item.typeLabel} ▾") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            val choices: List<CourseMaterialRole?> = listOf<CourseMaterialRole?>(null) + CourseMaterialRole.entries
                            for (choice in choices) {
                                val label = choice?.labelZh ?: "往年题"
                                DropdownMenuItem(
                                    text = { Text(if (choice == item.role) "✓ $label" else label) },
                                    onClick = {
                                        open = false
                                        if (choice != item.role) onChangeType(item.key, choice)
                                    },
                                    modifier = Modifier.testTag("course-intake-choice-${choice?.name ?: "PAST_PAPER"}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
