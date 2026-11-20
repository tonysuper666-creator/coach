package com.coach.android

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.coach.client.CourseView
import com.coach.client.RenderedMath
import com.coach.desktop.books.BookHighlightRecord
import com.coach.desktop.books.BookNotesScope
import com.coach.desktop.books.BookPassage
import com.coach.desktop.books.LibraryHome
import com.coach.desktop.books.LibraryUi
import com.coach.desktop.books.LocalBookReview
import com.coach.desktop.books.LocalReviewSource
import com.coach.desktop.books.Practice
import com.coach.desktop.books.PracticeAnswer
import com.coach.desktop.books.QuestionMode
import com.coach.desktop.books.SavedBookAnswer
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.buildBookNotesOutline
import com.coach.desktop.books.buildCourseNotesOutline
import com.coach.domain.Event
import com.coach.domain.project
import com.coach.ui.CoachTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidNotesExportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun openLibrary(): Pair<Path, TextbookLibrary> {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        Class.forName("org.sqlite.JDBC")
        val root = Files.createTempDirectory("coach-android-notes-lib-")
        return root to TextbookLibrary(root.resolve("library"))
    }

    private fun syntheticPngDataUri(): String {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(62, 109, 114))
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Test fun bookOutlineReadsSqliteRecordsAndRespectsScope() {
        val (root, library) = openLibrary()
        try {
            val mdFile = root.resolve("NotesExportSample.md")
            val ch1 = (1..10).joinToString("\n\n") { i ->
                "货币职能段落 $i：商品价值通过一般等价物表现出来，价格尺度衡量社会必要劳动时间，流通过程遵循商品交换规律。"
            }
            val ch2 = (1..10).joinToString("\n\n") { i ->
                "资本周转段落 $i：产业资本依次经过购买、生产和售卖三个阶段，固定资本与流动资本的周转速度影响年剩余价值率。"
            }
            Files.writeString(mdFile, "# 第一章 货币与价值\n\n$ch1\n\n# 第二章 资本循环\n\n$ch2")
            val book = library.importBook(mdFile)
            assertTrue(book.pages.isNotEmpty())
            assertTrue(book.lessons.isNotEmpty())

            val p1 = book.pages.first()
            val q1 = p1.text.take(minOf(16, p1.text.length))
            library.setHighlight(BookPassage(book.id, p1.number, 0, q1.length, q1), true)

            val firstLesson = book.lessons.first()
            library.saveAnswer(
                book.id,
                firstLesson.id,
                QuestionMode.SHORT_ANSWER,
                PracticeAnswer(text = "劳动二重性决定具体劳动与抽象劳动。", revealed = true, understood = true, guessed = false),
            )

            val dbHighlights = library.allHighlights(book.id)
            val dbAnswers = library.allAnswers(book.id)
            assertEquals(1, dbHighlights.size)
            assertEquals(q1, dbHighlights.single().quote)
            assertEquals(1, dbAnswers.size)
            assertEquals(true, dbAnswers.single().understood)

            val syntheticReview = LocalBookReview(
                book = book.id,
                lesson = firstLesson.id,
                mode = QuestionMode.CHOICE,
                bookTitle = book.title,
                lessonTitle = firstLesson.title,
                practice = Practice(
                    prompt = "价值尺度职能依托于哪种形态的货币？",
                    reference = q1,
                    answer = "A. 观念上的货币",
                    options = listOf("A. 观念上的货币", "B. 必须是足值金币"),
                ),
                originalAnswer = "B. 必须是足值金币",
                sources = setOf(LocalReviewSource.WRONG),
            )
            val highlightsWithFarPage = dbHighlights + BookHighlightRecord(page = p1.number + 50, start = 0, end = 6, quote = "远页摘录")
            val extraAnswers = dbAnswers + SavedBookAnswer(
                lessonId = "synthetic-far-lesson",
                mode = QuestionMode.SHORT_ANSWER,
                text = "远章补充作答",
                revealed = true,
                understood = null,
                guessed = true,
            )

            val full = buildBookNotesOutline(book, highlightsWithFarPage, listOf(syntheticReview), extraAnswers, BookNotesScope.FULL_BOOK, p1.number)
            assertTrue(full.markdown.contains("# ${book.title} · 复习提纲"))
            assertTrue(full.markdown.contains(q1))
            assertTrue(full.markdown.contains("远页摘录"))
            assertTrue(full.markdown.contains("价值尺度职能依托于哪种形态的货币"))
            assertTrue(full.markdown.contains("劳动二重性决定具体劳动与抽象劳动"))
            assertTrue(full.markdown.contains("远章补充作答"))

            val nearby = buildBookNotesOutline(book, highlightsWithFarPage, listOf(syntheticReview), extraAnswers, BookNotesScope.CURRENT_PAGE, p1.number)
            assertTrue(nearby.markdown.contains(q1))
            assertFalse(nearby.markdown.contains("远页摘录"))
            assertFalse(nearby.markdown.contains("远章补充作答"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun courseOutlineIncludesMasteryNotesMistakesAndRejectsEmptyLedger() {
        val rows = mutableListOf<Event>()
        fun emit(type: String, payload: JsonObject) {
            val n = rows.size.toLong() + 1L
            rows += Event(n, "e$n", "course-notes-test", type, recordedAt = n, occurredAt = n, deviceId = "test", actor = "test", payload = payload)
        }
        emit(
            "outline_revised",
            buildJsonObject {
                putJsonArray("knowledgePoints") {
                    add(
                        buildJsonObject {
                            put("id", "kp-1")
                            put("title", "需求弹性与边际效用")
                            put("week", 1)
                            put("reference", "教材 p.12–14")
                        },
                    )
                }
            },
        )
        emit("note_saved", buildJsonObject { put("kpId", "kp-1"); put("text", "价格下降时若缺乏弹性则总收益下降") })
        emit(
            "question_posed",
            buildJsonObject {
                put("attemptId", "att-1")
                put("questionId", "q-1")
                putJsonArray("kpIds") { add(JsonPrimitive("kp-1")) }
                put("format", "SINGLE_CHOICE")
                put("stem", "当需求价格弹性小于 1 时，降价会使总收益？")
                putJsonArray("options") {
                    add(buildJsonObject { put("optionId", "opt-a"); put("text", "增加") })
                    add(buildJsonObject { put("optionId", "opt-b"); put("text", "减少") })
                }
                put("letterMap", buildJsonObject { put("opt-a", "A"); put("opt-b", "B") })
            },
        )
        emit("answer_submitted", buildJsonObject { put("attemptId", "att-1"); put("answer", "A"); put("guessed", true) })
        emit("attempt_graded", buildJsonObject { put("attemptId", "att-1"); put("score", 0); put("max", 1); put("feedback", "弹性小于 1 时价格变动幅度大于销量变动幅度。") })

        val view = CourseView(rows, project(rows))
        val draft = buildCourseNotesOutline(view, "economics-101", week = 1)
        assertTrue(draft.markdown.contains("# 第 1 周复习提纲"))
        assertTrue(draft.markdown.contains("需求弹性与边际效用"))
        assertTrue(draft.markdown.contains("价格下降时若缺乏弹性则总收益下降"))
        assertTrue(draft.markdown.contains("A. 增加（标记为猜的）"))
        assertTrue(draft.markdown.contains("弹性小于 1 时价格变动幅度大于销量变动幅度。"))

        val byPoint = buildCourseNotesOutline(view, "economics-101", pointId = "kp-1")
        assertTrue(byPoint.markdown.contains("当需求价格弹性小于 1 时"))

        val empty = CourseView(emptyList(), project(emptyList()))
        val err = runCatching { buildCourseNotesOutline(empty, "economics-101", week = 1) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertEquals("课程尚无账本，请先同步", err?.message)
    }

    @Test fun pdfRendererPaginatesChineseHandlesFormulasAndRejectsRemoteImages() = runBlocking {
        val svgFormula = """<svg xmlns="http://www.w3.org/2000/svg" width="1ex" height="1ex" viewBox="0 0 1000 1000"><path d="M100 100 L900 900 M100 900 L900 100" fill="black"/></svg>"""
        val pngData = syntheticPngDataUri()
        val body = (1..140).joinToString("\n") { idx ->
            "第 $idx 条：复习提纲中文自动换行与跨页排版验证，测试可打印 A4 位图分页。"
        }
        val markdown = buildString {
            appendLine("# 公式与长文 PDF 验证")
            appendLine("内联公式：\$x^2\$ 与缺失公式：\$y_n\$。")
            appendLine("![内联位图]($pngData)")
            appendLine("![外链图](https://example.invalid/blocked.png)")
            appendLine(body)
        }

        val result = renderAndroidNotesPdf(markdown) { tex, _ ->
            if (tex == "x^2") RenderedMath("sha-x2", svgFormula) else null
        }

        assertEquals(1, result.unresolvedFormulas)
        assertEquals(1, result.unresolvedImages)
        assertTrue(result.pageCount >= 2)
        val ascii = result.bytes.toString(Charsets.ISO_8859_1)
        assertTrue(ascii.startsWith("%PDF-1.4"))
        assertTrue(ascii.contains("/Count ${result.pageCount}"))
        assertTrue(ascii.contains("%%EOF"))
        val startXref = Regex("startxref\\n(\\d+)").find(ascii)!!.groupValues[1].toInt()
        assertTrue(ascii.substring(startXref).startsWith("xref\n"))

        PDFBoxResourceLoader.init(compose.activity.applicationContext)
        PDDocument.load(result.bytes).use { doc ->
            assertEquals(result.pageCount, doc.numberOfPages)
        }
        assertNull(decodeInlineAndroidNotesImage("https://example.invalid/blocked.png"))
        assertNull(decodeInlineAndroidNotesImage("file:///etc/passwd"))
        assertNotNull(decodeInlineAndroidNotesImage(pngData)?.also { it.recycle() })
    }

    @Test fun saveDirectoryRejectsGitRepoAndPublishesAtomicFiles() {
        val repoRoot = Files.createTempDirectory("coach-notes-git-")
        val outRoot = Files.createTempDirectory("coach-notes-out-")
        try {
            Files.createDirectory(repoRoot.resolve(".git"))
            val nestedTarget = repoRoot.resolve("nested/new-sub")
            val error = runCatching {
                saveAndroidNotesToDirectory(nestedTarget, "# 测试\n正文", "%PDF-1.4\n%%EOF\n".toByteArray())
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("请选择 Git 仓库之外的私人目录", error?.message)
            assertFalse(Files.exists(repoRoot.resolve("nested")))

            val published = saveAndroidNotesToDirectory(outRoot, "# 导出正文\n第一条", "%PDF-1.4\n%%EOF\n".toByteArray())
            assertTrue(published.fileName.toString().startsWith("LearningCoach-复习-"))
            assertTrue(Files.readString(published.resolve("复习提纲.md")).contains("导出正文"))
            assertEquals("%PDF-1.4\n%%EOF\n", Files.readString(published.resolve("复习提纲.pdf")))
        } finally {
            repoRoot.toFile().deleteRecursively()
            outRoot.toFile().deleteRecursively()
        }
    }

    @Test fun bookNotesExportDialogWritesMarkdownAndPdfFromComposeAction() {
        val (root, library) = openLibrary()
        val outDir = Files.createTempDirectory("coach-notes-ui-")
        try {
            val mdFile = root.resolve("UiExportBook.md")
            Files.writeString(mdFile, "# 第一章 导论\n\n这是用于端到端导出验证的教材正文段落，包含知识点梳理与复习总结。")
            val book = library.importBook(mdFile)
            val p1 = book.pages.first()
            val quote = p1.text.take(minOf(10, p1.text.length))
            library.setHighlight(BookPassage(book.id, p1.number, 0, quote.length, quote), true)

            compose.setContent {
                CoachTheme(false) {
                    AndroidBookNotesExportDialog(
                        book = book,
                        library = library,
                        currentPage = p1.number,
                        defaultDirectory = outDir,
                        onDismiss = {},
                    )
                }
            }

            compose.onNodeWithTag("notes-export-panel").assertIsDisplayed()
            compose.onNodeWithTag("notes-export").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("已导出到", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("notes-status").assertTextContains("已导出到", substring = true)

            val exportedFolder = Files.list(outDir).use { paths -> paths.findFirst().orElse(null) }
            assertNotNull(exportedFolder)
            val md = Files.readString(exportedFolder!!.resolve("复习提纲.md"))
            assertTrue(md.contains(quote))
            val pdfBytes = Files.readAllBytes(exportedFolder.resolve("复习提纲.pdf"))
            assertTrue(pdfBytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        } finally {
            root.toFile().deleteRecursively()
            outDir.toFile().deleteRecursively()
        }
    }

    @Test fun libraryHomeShowsExportEntryWhenCallbackProvided() {
        val (root, library) = openLibrary()
        try {
            val mdFile = root.resolve("ShelfEntry.md")
            Files.writeString(mdFile, "# 第一节\n\n书架导出入口可见性测试正文内容。")
            val book = library.importBook(mdFile)
            var triggered = false
            compose.setContent {
                CoachTheme(false) {
                    LibraryHome(
                        ui = LibraryUi(
                            catalog = library.catalog(),
                            book = book,
                        ),
                        onImport = {},
                        onSelect = {},
                        onRead = {},
                        onLearn = {},
                        onPath = {},
                        onExportNotes = { _, _ -> triggered = true },
                    )
                }
            }
            compose.onNodeWithTag("book-export-notes").assertIsDisplayed().performClick()
            assertTrue(triggered)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
