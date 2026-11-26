package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.coach.ui.CoachTheme
import com.coach.domain.fsrs.FsrsRating
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BookNotesPageTest {
    @Test fun markdownKeepsSelectedSentencePageAndDropsExtraOriginalText() {
        val secretPage = "SECRET_PAGE_BODY that the reader must not export"
        val secretSent = "SECRET_SENT_BLOCK neighboring paragraph"
        val secretReference = "SECRET_REFERENCE the whole section"
        val page = assembleBookNotesPage(
            bookId = "book-1",
            title = "合成教材",
            notes = listOf(
                StoredNoteRow("n1", 12, 4, "only this sentence", "我写的话"),
                StoredNoteRow("blank", 2, 0, "ignored quote", "   "),
            ),
            questions = listOf(
                StoredQuestionRow(7, 4, "selected sentence only", "为什么会这样", "因为条件变了。后面还有很长的解释。"),
                StoredQuestionRow(8, 5, secretSent, "   ", "不应出现"),
            ),
            mistakes = listOf(
                StoredMistakeRow("sec-9", "CHOICE", "题干甲", "正解甲", "我的甲", "我的甲", true, null, false, false),
                StoredMistakeRow("sec-3", "CHOICE", "题干乙", "A", secretReference, "B", true, null, false, false),
                StoredMistakeRow("sec-1", "CHOICE", "已掌握", "A", "", "A", true, null, false, true),
                StoredMistakeRow("book-1:p15:8", "CHOICE", "猜的题", "A", "", "A", true, null, true, false),
                StoredMistakeRow("sec-short", "SHORT_ANSWER", "用自己的话说", "", "", "没记住", true, false, false, false),
            ),
            tocStartPages = mapOf("sec-9" to 9, "sec-3" to 3, "sec-1" to 1, "sec-short" to 6),
        )
        val markdown = renderBookNotesMarkdown(page)
        assertEquals(6, page.itemCount)
        assertEquals(listOf(12), page.annotations.map { it.page })
        assertEquals(listOf(4), page.questions.map { it.page })
        assertEquals(listOf(3, 6, 9, 15), page.mistakes.map { it.page })
        assertTrue(markdown.contains("第 12 页"))
        assertTrue(markdown.contains("选中的原句：only this sentence"))
        assertTrue(markdown.contains("我写的：我写的话"))
        assertTrue(markdown.contains("问题：为什么会这样"))
        assertTrue(markdown.contains("选中的原句：selected sentence only"))
        assertTrue(markdown.contains("第 3 页"))
        assertTrue(markdown.contains("我的答案：B"))
        assertTrue(markdown.contains("正解：A"))
        assertTrue(markdown.contains("状态：不确定"))
        assertTrue(markdown.contains("第 15 页"))
        assertFalse(markdown.contains(secretPage), markdown)
        assertFalse(markdown.contains(secretSent), markdown)
        assertFalse(markdown.contains(secretReference), markdown)
        assertFalse(markdown.contains("已掌握"))
        assertFalse(markdown.contains("ignored quote"))
        val bytes = notesMarkdownUtf8Bytes("中文笔记\n第 3 页\n")
        assertEquals(0xEF, bytes[0].toInt() and 0xFF)
        assertEquals(0xBB, bytes[1].toInt() and 0xFF)
        assertEquals(0xBF, bytes[2].toInt() and 0xFF)
        assertEquals("中文笔记\n第 3 页\n", bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8))
    }

    @Test fun booklistPutsEachBookInItsOwnSection() {
        val first = assembleBookNotesPage(
            "a", "甲书",
            notes = listOf(StoredNoteRow("1", 2, 0, "甲句", "甲注")),
            questions = emptyList(),
            mistakes = emptyList(),
        )
        val second = assembleBookNotesPage("b", "乙书", emptyList(), emptyList(), emptyList())
        val markdown = renderBooklistNotesMarkdown("这组书", listOf(first, second))
        assertTrue(markdown.contains("## 甲书"))
        assertTrue(markdown.contains("## 乙书"))
        assertTrue(markdown.contains("第 2 页"))
        assertTrue(markdown.contains("这一类还没有。"))
        val firstAt = markdown.indexOf("## 甲书")
        val secondAt = markdown.indexOf("## 乙书")
        assertTrue(firstAt in 0 until secondAt)
    }

    @Test fun twoHundredItemsAssembleAndRenderWithinOneSecond() {
        val notes = (1..200).map { index ->
            StoredNoteRow("n$index", page = (index % 40) + 1, start = index, quote = "句 $index", note = "注 $index")
        }
        val started = System.nanoTime()
        val page = assembleBookNotesPage("book", "厚书", notes, emptyList(), emptyList())
        val markdown = renderBookNotesMarkdown(page)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, page.itemCount)
        assertTrue(markdown.contains("第 1 页"))
        assertTrue(markdown.contains("注 200"))
        assertTrue(elapsedMs < 1000, "200 条整理用了 ${elapsedMs}ms")
    }

    @Test fun libraryLoadMatchesRowsAndIgnoresPageBodyAndSentBlocks() {
        val root = Files.createTempDirectory("book-notes-page-")
        val library = TextbookLibrary(root)
        val bookId = "synthetic-book"
        DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate(
                    "INSERT INTO pages(book,page,text) VALUES('$bookId',4,'SECRET_PAGE_BODY that must stay in the library')",
                )
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('$bookId','n1','v1',8,0,4,'原句','我的批注','sha',1,'m',0)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('$bookId','gone','v1',1,0,1,'删掉的','不该出现','sha',1,'m2',1)""",
                )
                sql.executeUpdate(
                    "INSERT INTO book_highlights(book,page,start,end,quote) VALUES('$bookId',8,0,3,'HIGHLIGHT_ONLY')",
                )
                sql.executeUpdate(
                    """INSERT INTO book_ask_turns(
                        book,thread_id,selection_id,selection_page,selection_start,selection_end,selection_text,
                        section_location,action_kind,label,question_text,answer_text,citations_json,sent_blocks_json,elapsed_ms,created_at
                    ) VALUES('$bookId','t1','s1',8,0,2,'选中句','', 'ask','问','这句什么意思','一句摘要','[]','SECRET_SENT_BLOCK',1,1)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_toc_nodes(book,id,ordinal,level,kind,title,start_page,end_page,source)
                       VALUES('$bookId','lesson-8',1,1,'chapter','第八节',8,8,'TEST')""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_reviews(
                        book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,
                        answer_text,revealed,understood,completed,guessed
                    ) VALUES(
                        '$bookId','lesson-8','CHOICE','合成','第八节','题干原文','SECRET_REFERENCE','正确项','[]','错误项',
                        '错误项',1,NULL,0,0
                    )""",
                )
            }
        }
        val loaded = library.loadBookNotes(bookId, "合成教材")
        val markdown = renderBookNotesMarkdown(loaded)
        assertEquals(1, loaded.annotations.size)
        assertEquals(1, loaded.questions.size)
        assertEquals(1, loaded.mistakes.size)
        assertEquals(8, loaded.annotations.single().page)
        assertEquals(8, loaded.questions.single().page)
        assertEquals(8, loaded.mistakes.single().page)
        assertTrue(markdown.contains("原句"))
        assertTrue(markdown.contains("我的批注"))
        assertTrue(markdown.contains("选中句"))
        assertTrue(markdown.contains("题干原文"))
        assertTrue(markdown.contains("错误项"))
        assertTrue(markdown.contains("正确项"))
        assertFalse(markdown.contains("SECRET_PAGE_BODY"), markdown)
        assertFalse(markdown.contains("SECRET_SENT_BLOCK"), markdown)
        assertFalse(markdown.contains("SECRET_REFERENCE"), markdown)
        assertFalse(markdown.contains("HIGHLIGHT_ONLY"), markdown)
        assertFalse(markdown.contains("不该出现"), markdown)
    }

    @Test fun notesPageClickJumpsToSourcePageAndCopyMatchesMarkdown() = runSkikoComposeUiTest {
        val page = assembleBookNotesPage(
            "book",
            "合成教材",
            notes = listOf(StoredNoteRow("n1", 6, 0, "原句", "批注")),
            questions = emptyList(),
            mistakes = emptyList(),
        )
        var jumped: Int? = null
        var copied: String? = null
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
            override fun getText(): AnnotatedString? = copied?.let(::AnnotatedString)
        }
        setContent {
            CoachTheme(false) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    BookNotesPageView(
                        page = page,
                        status = null,
                        onBack = {},
                        onJump = { jumped = it },
                        onCopy = { clipboard.setText(AnnotatedString(renderBookNotesMarkdown(page))) },
                        onExport = {},
                    )
                }
            }
        }
        onNodeWithTag("book-notes-count").assertExists()
        onNodeWithTag("book-notes-item-note-n1").performClick()
        assertEquals(6, jumped)
        onNodeWithTag("book-notes-copy").performClick()
        val text = assertNotNullCopied(copied)
        assertTrue(text.contains("第 6 页"))
        assertTrue(text.contains("原句"))
        assertTrue(text.contains("批注"))
        assertEquals(renderBookNotesMarkdown(page), text)
    }

    @Test fun firstSelfCheckAndGuessStayUntilMastered() {
        val root = Files.createTempDirectory("book-notes-self-check-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lessons = book.lessons
        assertTrue(lessons.size >= 3, "合成书至少要有三节")
        val reviewLesson = lessons[0]
        val guessedLesson = lessons[1]
        val masteredLesson = lessons[2]
        library.saveAnswer(
            book.id, reviewLesson.id, QuestionMode.SHORT_ANSWER,
            PracticeAnswer("还要再看一遍", revealed = true, understood = false),
        )
        library.saveAnswer(
            book.id, guessedLesson.id, QuestionMode.SHORT_ANSWER,
            PracticeAnswer("这句我是猜的", revealed = true, understood = true, guessed = true),
        )
        library.saveAnswer(
            book.id, masteredLesson.id, QuestionMode.SHORT_ANSWER,
            PracticeAnswer("这节能讲清楚", revealed = true, understood = true),
        )
        val pages = lessons.associate { it.id to it.firstPage }
        val loaded = library.loadBookNotes(book.id, book.title, pages)
        assertEquals(2, loaded.mistakes.size)
        assertEquals(reviewLesson.firstPage, loaded.mistakes.single { it.lines.any { line -> line.second == "还要再看一遍" } }.page)
        assertEquals(1, reviewLesson.firstPage)
        val markdown = renderBookNotesMarkdown(loaded)
        assertTrue(markdown.contains("还要再看一遍"))
        assertTrue(markdown.contains("这句我是猜的"))
        assertTrue(markdown.contains("状态：不确定"))
        assertFalse(markdown.contains("这节能讲清楚"))
        assertFalse(markdown.contains("Photosynthesis is the process"), markdown)
        val card = library.review(book.id, reviewLesson.id, QuestionMode.SHORT_ANSWER)
        library.saveReviewRating(card, FsrsRating.GOOD, PracticeAnswer("还要再看一遍", revealed = true, understood = true))
        val after = renderBookNotesMarkdown(library.loadBookNotes(book.id, book.title, pages))
        assertFalse(after.contains("还要再看一遍"), after)
        assertTrue(after.contains("这句我是猜的"))
    }

    @Test fun twoHundredStoredItemsIncludeFirstSelfCheck() {
        val root = Files.createTempDirectory("book-notes-two-hundred-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        library.saveAnswer(
            book.id, lesson.id, QuestionMode.SHORT_ANSWER,
            PracticeAnswer("第 1 页还要复习", revealed = true, understood = false),
        )
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.prepareStatement(
                """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,0)""",
            ).use { sql ->
                repeat(199) { index ->
                    sql.setString(1, book.id)
                    sql.setString(2, "n$index")
                    sql.setString(3, "v1")
                    sql.setInt(4, (index % 40) + 1)
                    sql.setInt(5, index)
                    sql.setInt(6, index + 1)
                    sql.setString(7, "句 $index")
                    sql.setString(8, "注 $index")
                    sql.setString(9, "sha")
                    sql.setInt(10, 1)
                    sql.setString(11, "m$index")
                    sql.executeUpdate()
                }
            }
        }
        val started = System.nanoTime()
        val loaded = library.loadBookNotes(book.id, book.title, mapOf(lesson.id to lesson.firstPage))
        val markdown = renderBookNotesMarkdown(loaded)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, loaded.itemCount)
        assertEquals(199, loaded.annotations.size)
        assertEquals(1, loaded.mistakes.size)
        assertEquals(lesson.firstPage, loaded.mistakes.single().page)
        assertTrue(markdown.contains("共 200 条"))
        assertTrue(markdown.contains("第 1 页还要复习"))
        assertTrue(markdown.contains("注 198"))
        assertFalse(markdown.contains("Photosynthesis is the process"), markdown)
        assertTrue(elapsedMs < 1000, "200 条打开用了 ${elapsedMs}ms")
    }

    private fun assertNotNullCopied(value: String?): String = checkNotNull(value) { "没有复制到剪贴板" }
}
