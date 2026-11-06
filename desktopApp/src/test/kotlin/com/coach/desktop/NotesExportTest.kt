package com.coach.desktop

import com.coach.client.CourseView
import com.coach.domain.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NotesExportTest {
    private fun sample(): CourseView {
        val rows = mutableListOf<Event>()
        fun emit(type: String, payload: JsonObject) { val n = rows.size.toLong() + 1; rows += Event(n, "e$n", "synthetic", type, recordedAt = n, occurredAt = n, deviceId = "test", actor = "test", payload = payload) }
        emit("outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "kp", "title" to "【W1·合成】公式与需求", "week" to 1)))))
        fun answer(id: String, question: String = id, score: Int = 0) {
            emit("question_posed", json("attemptId" to id, "questionId" to question, "kpIds" to listOf("kp"), "format" to "SINGLE_CHOICE", "stem" to "合成题 $id", "options" to JsonArray(listOf(json("optionId" to "o", "text" to "合成选项"))), "letterMap" to json("o" to "B")))
            emit("answer_submitted", json("attemptId" to id, "answer" to "B"))
            emit("attempt_graded", json("attemptId" to id, "score" to score, "max" to 1, "feedback" to "合成讲解 $id"))
        }
        answer("old", "retry"); answer("correct", "retry", 1)
        answer("void"); emit("attempt_voided", json("attemptId" to "void"))
        for (i in 1..5) answer("pending-$i")
        return CourseView(rows, project(rows))
    }

    @Test fun packagedCoreUsesTheAppsPendingSelectionAndFrozenLetterMap() = runBlocking {
        val view = sample()
        val draft = NotesCore.build(notesRequest(view, "synthetic", NotesSelection(week = 1)))
        assertEquals((1..5).map { "pending-$it" }, draft.attemptIds)
        assertFalse(draft.markdown.contains("合成题 old"))
        assertFalse(draft.markdown.contains("合成题 void"))
        assertTrue(draft.markdown.contains("我的答案：B. 合成选项"))
        assertTrue(draft.markdown.contains("合成讲解 pending-5"))
        val point = NotesCore.build(notesRequest(view, "synthetic", NotesSelection(pointId = "kp")))
        assertEquals(draft.attemptIds, point.attemptIds)
    }

    @Test fun privateAtomicExportNeverOverwritesAndRefusesGit() {
        val root = Files.createTempDirectory("notes-synthetic-test")
        try {
            val draft = NotesDraft("合成数据", emptyList(), 1)
            val first = saveNotes(root, draft, null)
            val second = saveNotes(root, draft, "%PDF-test".toByteArray())
            assertNotEquals(first, second)
            assertEquals("合成数据", Files.readString(first.resolve("复习提纲.md")))
            assertFalse(Files.exists(first.resolve("复习提纲.pdf")))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(first.resolve("复习提纲.md"))))
            Files.createDirectory(root.resolve(".git"))
            assertFailsWith<IllegalArgumentException> { saveNotes(root, draft, null) }
            assertEquals(3, Files.list(root).use { it.count().toInt() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun longChineseNotesPaginateAndPdfHasValidCrossReferences() {
        val layout = NotesPageLayout()
        layout.text("# 合成复习提纲\n" + (1..180).joinToString("\n") { "第 $it 条：合成中文正文，用来验证换行与分页。" })
        val pages = layout.finish()
        assertTrue(pages.size >= 4)
        val pdf = imagePdf(pages)
        val ascii = pdf.toString(Charsets.ISO_8859_1)
        val offset = Regex("startxref\\n(\\d+)").find(ascii)!!.groupValues[1].toInt()
        assertTrue(ascii.substring(offset).startsWith("xref\n"))
        assertTrue(ascii.contains("/Count ${pages.size}"))
    }

    @Test fun unavailableFormulaIsExplicitlyCountedWithoutDroppingTheDocument() = runBlocking {
        val result = notesPdf("# 合成公式\n前文 \$x^2\$ 后文") { _, _ -> null }
        assertEquals(1, result.unresolved)
        assertTrue(result.bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        assertFalse(NotesPageLayout().formula(java.awt.image.BufferedImage(12000, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), true))
    }
}
