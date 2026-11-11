package com.coach.desktop

import com.coach.client.CourseView
import com.coach.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NotesExportTest {
    @Test fun syntheticEdgesKeepFivePendingAndExcludeMultipleChoice() = runBlocking {
        val cases = Json.parseToJsonElement(java.io.File("../tools/notes/fixtures/edge-cases.json").readText()).jsonArray
        val rows = mutableListOf<Event>()
        fun emit(type: String, payload: JsonObject) { val n = rows.size.toLong() + 1; rows += Event(n, "edge$n", "synthetic", type, recordedAt = n, occurredAt = n, deviceId = "test", actor = "test", payload = payload) }
        emit("outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "kp", "title" to "【W1·合成】边界", "week" to 1)))))
        for (raw in cases) {
            val c = raw.jsonObject
            val id = c.str("id")
            emit("question_posed", json("attemptId" to id, "questionId" to id, "kpIds" to listOf("kp"), "format" to c.str("format").ifBlank { "SINGLE_CHOICE" }, "stem" to c.str("stem"), "options" to c["options"]!!, "letterMap" to json("up" to "B", "down" to "A")))
            emit("answer_submitted", json("attemptId" to id, "answer" to c["answer"]!!))
            emit("attempt_graded", json("attemptId" to id, "score" to 0, "max" to 1, "feedback" to c.str("feedback")))
        }
        val draft = NotesCore.build(notesRequest(CourseView(rows, project(rows)), "synthetic", NotesSelection(week = 1)))
        assertEquals(listOf("formula", "image", "long", "empty", "ordinary"), draft.attemptIds)
        cases.take(5).forEach { raw ->
            val c = raw.jsonObject
            assertTrue(draft.markdown.contains(c.str("stem")))
            assertTrue(draft.markdown.contains(c.str("feedback")))
        }
        assertFalse(draft.markdown.contains("合成 S6"))
        val imageSource = Regex("data:image/png;base64,[^)]+").find(draft.markdown)!!.value
        assertEquals(240, inlineNotesImage(imageSource)!!.width)
        assertNull(inlineNotesImage("file:///private/image.png"))
        assertNull(inlineNotesImage("https://example.invalid/image.png"))
        assertNull(inlineNotesImage("data:image/png;base64,broken"))
        val tooWide = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(16001, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        assertNull(inlineNotesImage("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(tooWide)))
        val pdf = notesPdf("![合成图]($imageSource)\n![远程图](https://example.invalid/a.png)") { _, _ -> error("image must never reach math renderer") }
        assertEquals(1, pdf.unresolvedImages)
        assertEquals(0, pdf.unresolved)
    }

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

    @Test fun packagedCorePreservesUnicodeRequestsAndChineseErrors() = runBlocking {
        val view = sample()
        val events = view.events.map { event ->
            if (event.type == "attempt_graded") event.copy(payload = JsonObject(event.payload + ("feedback" to JsonPrimitive("合成讲解🧪，保留中文与生僻字𠮷")))) else event
        }
        val draft = NotesCore.build(notesRequest(CourseView(events, project(events)), "合成课程🧪", NotesSelection(week = 1)))
        assertTrue(draft.markdown.contains("我的答案：B. 合成选项"))
        assertTrue(draft.markdown.contains("合成讲解🧪，保留中文与生僻字𠮷"))
        assertFalse(draft.markdown.contains('\uFFFD'))
        val error = assertFailsWith<IllegalStateException> {
            NotesCore.build(buildJsonObject {
                put("snapshot", buildJsonObject { put("course", "合成课程"); put("events", JsonArray(emptyList())) })
                put("selection", buildJsonObject { put("week", 1) })
            })
        }
        assertEquals("课程尚无账本，请先同步", error.message)
    }

    private fun assertPrivate(path: Path, directory: Boolean) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            assertEquals(if (directory) "rwx------" else "rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()))
        } else {
            val entries = assertNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)).acl
            assertEquals(1, entries.size, "No inherited or additional grants may survive")
            val entry = entries.single()
            assertEquals(AclEntryType.ALLOW, entry.type())
            assertEquals(path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name")), entry.principal())
            assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
            assertEquals(if (directory) setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) else emptySet(), entry.flags())
        }
    }

    @Test fun privateAtomicExportNeverOverwritesAndRefusesGit() {
        val root = Files.createTempDirectory("合成笔记-test")
        try {
            val draft = NotesDraft("合成数据", emptyList(), 1)
            val first = saveNotes(root, draft, null)
            val second = saveNotes(root, draft, "%PDF-test".toByteArray())
            assertNotEquals(first, second)
            assertEquals("合成数据", Files.readString(first.resolve("复习提纲.md")))
            assertFalse(Files.exists(first.resolve("复习提纲.pdf")))
            for (folder in listOf(first, second)) {
                assertPrivate(folder, true)
                assertPrivate(folder.resolve("复习提纲.md"), false)
            }
            assertPrivate(second.resolve("复习提纲.pdf"), false)
            assertContentEquals("%PDF-test".toByteArray(), Files.readAllBytes(second.resolve("复习提纲.pdf")))
            Files.createDirectory(root.resolve(".git"))
            assertFailsWith<IllegalArgumentException> { saveNotes(root, draft, null) }
            assertEquals(3, Files.list(root).use { it.count().toInt() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun privateExportCleansEveryFailedStageAndPreservesTheCause() {
        for (stage in listOf("directory", "file", "pdf", "move")) {
            val root = Files.createTempDirectory("notes-synthetic-failure-")
            try {
                val denied = java.nio.file.AccessDeniedException("synthetic-$stage")
                val moveFailure = java.nio.file.AtomicMoveNotSupportedException("synthetic-stage", "synthetic-target", "synthetic-volume")
                val files = object : NotesExportFiles() {
                    override fun verifyPrivate(path: Path, directory: Boolean) {
                        super.verifyPrivate(path, directory)
                        if (stage == "directory" && directory || stage == "file" && !directory) {
                            if (!directory) assertEquals(0L, Files.size(path), "Permission checks must precede private writes")
                            throw denied
                        }
                    }
                    override fun write(path: Path, bytes: ByteArray) {
                        if (stage == "pdf" && path.fileName.toString().endsWith(".pdf")) throw denied
                        super.write(path, bytes)
                    }
                    override fun publish(staging: Path, target: Path): Path {
                        if (stage == "move") throw moveFailure
                        return super.publish(staging, target)
                    }
                }
                val error = assertFailsWith<java.io.IOException> { saveNotes(root, NotesDraft("合成私有正文", emptyList(), 1), "%PDF-test".toByteArray(), files) }
                assertSame(if (stage == "move") moveFailure else denied, error)
                assertEquals(0L, Files.list(root).use { it.count() }, "No partial export or staging directory after $stage failure")
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun unexpectedPermissionsAreRejectedBeforeWritingPrivateBytes() {
        val root = Files.createTempDirectory("notes-synthetic-permissions-")
        try {
            val files = object : NotesExportFiles() {
                override fun verifyPrivate(path: Path, directory: Boolean) {
                    if (!directory) {
                        assertEquals(0L, Files.size(path))
                        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
                        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-r--r--"))
                        else {
                            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                            // Reuse a real principal instead of a localized account name or SID lookup.
                            val current = acl.acl.single().principal()
                            val inherited = Files.getFileAttributeView(root, AclFileAttributeView::class.java).acl
                            val other = inherited.firstOrNull { it.principal() != current }?.principal() ?: current
                            acl.acl = acl.acl + AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other).setPermissions(AclEntryPermission.READ_DATA).build()
                        }
                    }
                    super.verifyPrivate(path, directory)
                }
            }
            assertFailsWith<IllegalStateException> { saveNotes(root, NotesDraft("合成私有正文", emptyList(), 1), null, files) }
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun targetCollisionAndGitAncestorNeverPublishOrOverwrite() {
        val root = Files.createTempDirectory("notes-synthetic-collision-")
        try {
            val draft = NotesDraft("合成正文", emptyList(), 1)
            for (nonempty in listOf(false, true)) {
                val existing = Files.createDirectory(root.resolve("existing-$nonempty"))
                if (nonempty) Files.writeString(existing.resolve("keep.md"), "原有合成正文")
                val files = object : NotesExportFiles() {
                    override fun publish(staging: Path, target: Path) = super.publish(staging, existing)
                }
                assertFailsWith<java.nio.file.FileAlreadyExistsException> { saveNotes(root, draft, null, files) }
                assertTrue(Files.isDirectory(existing))
                if (nonempty) assertEquals("原有合成正文", Files.readString(existing.resolve("keep.md")))
                else assertEquals(0L, Files.list(existing).use { it.count() })
            }
            val nested = Files.createDirectory(root.resolve("nested"))
            Files.writeString(root.resolve(".git"), "synthetic gitdir marker")
            assertEquals("请选择 Git 仓库之外的私人目录", assertFailsWith<IllegalArgumentException> { saveNotes(nested, draft, null) }.message)
            assertEquals(0L, Files.list(nested).use { it.count() })
            assertFalse(Files.list(root).use { it.anyMatch { p -> p.fileName.toString().startsWith(".coach-notes-") } })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun filesystemWithoutPrivatePermissionsFailsClosed() {
        val root = Files.createTempDirectory("notes-synthetic-unsupported-")
        try {
            val uri = java.net.URI.create("jar:" + root.resolve("synthetic.zip").toUri())
            java.nio.file.FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fs ->
                val folder = fs.getPath("/")
                assertEquals("笔记导出需要私有文件权限", assertFailsWith<IllegalStateException> {
                    saveNotes(folder, NotesDraft("合成正文", emptyList(), 1), null)
                }.message)
                assertEquals(0L, Files.list(folder).use { it.count() })
            }
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
        val rendered = notesPdf("内存解码公式 \$x\$。") { _, _ ->
            com.coach.client.RenderedMath("synthetic-svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1ex\" height=\"1ex\" viewBox=\"0 0 100 100\"><path d=\"M10 10 L90 90 M10 90 L90 10\" stroke=\"black\"/></svg>")
        }
        assertEquals(0, rendered.unresolved)
    }
}
