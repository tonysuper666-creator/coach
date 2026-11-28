package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceCopy
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionQaStanceClientTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun oneUndeterminedBookForcesWholeCollectionToUseLegacyPath() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        val bookA = library.importBook(
            Files.writeString(root.resolve("walden.txt"), "Chapter 1\n\nI went to the woods because I wished to live deliberately."),
        )
        val bookB = library.importBook(
            Files.writeString(root.resolve("plain.txt"), "Chapter 1\n\nDeliberate inquiry reveals simple principles of economics."),
        )
        // Only bookA is in book-stance-forced.json; bookB is Undetermined -> entire collection must use legacy path
        Files.writeString(
            library.directory.resolve(ForcedFileBookStanceSource.FILE_NAME),
            buildJsonObject {
                put(bookA.id, buildJsonObject {
                    put("defaultStance", "life")
                    put("guards", JsonArray(emptyList()))
                })
            }.toString(),
        )

        val capturedPayloads = CopyOnWriteArrayList<JsonObject>()
        val turn = CollectionQaEngine.ask(
            library = library,
            collectionId = CollectionQaVirtualCollections.READ_LIBRARY_ID,
            question = "Why did he go to the woods to live deliberately?",
            remoteAsk = { payload ->
                capturedPayloads += payload
                val b1 = payload.getValue("blocks").jsonArray.first().jsonObject
                val text = b1.getValue("text").jsonPrimitive.content
                CourseServerCallResult(
                    body = buildJsonObject {
                        put("insufficientEvidence", false)
                        put("sentences", buildJsonArray {
                            add(buildJsonObject {
                                put("claim", "他想有意识地生活。")
                                put("blockId", b1.getValue("blockId").jsonPrimitive.content)
                                put("quoteAnchor", text.lineSequence().last())
                            })
                        })
                    },
                    error = null,
                )
            },
        ).getOrThrow()

        assertEquals(1, capturedPayloads.size)
        val sent = capturedPayloads.single()
        assertNull(sent["stance"], "库里有一本未判定时整次走旧路径，请求不能带 stance")
        assertNull(sent["bookStances"], "库里有一本未判定时不能带 bookStances")
        assertNull(turn.stanceAnswer, "旧路径返回不得生成 stanceAnswer")
        assertEquals(1, turn.sentences.size)
    }

    @Test
    fun fictionBookWithoutReadingRecordIsSkippedAndReportedInTurn() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        val walden = library.importBook(
            Files.writeString(root.resolve("walden.txt"), "Chapter 1\n\nPride and simplicity both appear in human affairs."),
        )
        val pride = library.importBook(
            Files.writeString(root.resolve("pride.txt"), "Chapter 1\n\nPride is a very common failing in society and later marriage at Pemberley."),
        )
        Files.writeString(
            library.directory.resolve(ForcedFileBookStanceSource.FILE_NAME),
            buildJsonObject {
                put(walden.id, buildJsonObject {
                    put("defaultStance", "life")
                    put("guards", JsonArray(emptyList()))
                })
                put(pride.id, buildJsonObject {
                    put("defaultStance", "original")
                    put("guards", buildJsonArray { add(JsonPrimitive("fiction")) })
                })
            }.toString(),
        )

        val capturedPayloads = CopyOnWriteArrayList<JsonObject>()
        val turn = CollectionQaEngine.ask(
            library = library,
            collectionId = CollectionQaVirtualCollections.READ_LIBRARY_ID,
            question = "这几本书里怎么谈骄傲？",
            remoteAsk = { payload ->
                capturedPayloads += payload
                val b1 = payload.getValue("blocks").jsonArray.single().jsonObject
                val pageText = walden.page(b1.getValue("page").jsonPrimitive.int)!!.text
                val qStr = "Pride and simplicity"
                val start = pageText.indexOf(qStr)
                CourseServerCallResult(
                    body = buildJsonObject {
                        put("stance", "mixed")
                        put("layers", buildJsonArray {
                            add(buildJsonObject {
                                put("layer", "book")
                                put("label", BookStanceCopy.BOOK_LIFE)
                                put("bookId", walden.id)
                                put("bookTitle", walden.title)
                                put("sentences", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", "作者谈到了骄傲与简朴。")
                                        put("citations", buildJsonArray {
                                            add(buildJsonObject {
                                                put("docId", "${walden.id}:p1:0")
                                                put("pdfPage", 1)
                                                put("startUtf16", start)
                                                put("endUtf16", start + qStr.length)
                                                put("quote", qStr)
                                                put("bookId", walden.id)
                                                put("bookTitle", walden.title)
                                            })
                                        })
                                    })
                                })
                            })
                        })
                        put("missingRecordNotices", buildJsonArray {
                            add(JsonPrimitive(BookStanceCopy.noReadingRecord(pride.title)))
                        })
                        put("modelCalled", true)
                    },
                    error = null,
                )
            },
        ).getOrThrow()

        val sent = capturedPayloads.single()
        val sentBlockBookIds = sent.getValue("blocks").jsonArray.map { it.jsonObject.getValue("bookId").jsonPrimitive.content }
        assertTrue(walden.id in sentBlockBookIds)
        assertFalse(pride.id in sentBlockBookIds, "没有阅读记录的小说整本不得检索发送")
        val stanceAnswer = turn.stanceAnswer!!
        assertTrue(stanceAnswer.missingRecordNotices.any { "还没有阅读记录，没有用它。" in it })
        assertTrue(turn.sentences.none { it.citation.bookId == pride.id })
    }

    @Test
    fun highRiskAnswerRendersNoticeInlineWithNoBlockingCardOrDialog() = runSkikoComposeUiTest(size = Size(1000f, 750f)) {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        val zhouyi = library.importBook(
            Files.writeString(root.resolve("zhouyi.txt"), "乾卦\n\n九五：飛龍在天，利見大人。"),
        )
        Files.writeString(
            library.directory.resolve(ForcedFileBookStanceSource.FILE_NAME),
            buildJsonObject {
                put(zhouyi.id, buildJsonObject {
                    put("defaultStance", "esoteric")
                    put("guards", buildJsonArray { add(JsonPrimitive("esoteric")) })
                })
            }.toString(),
        )
        val pageText = zhouyi.page(1)!!.text
        val qStr = "飛龍在天，利見大人"
        val start = pageText.indexOf(qStr)
        var askedStances = mutableListOf<String>()

        setContent {
            CoachTheme(false) {
                MyLibraryScreen(
                    library = library,
                    onBack = {},
                    onOpenBook = {},
                    onAskCollection = { payload ->
                        askedStances += payload.getValue("stance").jsonPrimitive.content
                        CourseServerCallResult(
                            body = buildJsonObject {
                                put("stance", payload.getValue("stance").jsonPrimitive.content)
                                put("boundary", BookStanceCopy.HIGH_RISK_BOUNDARY)
                                put("highRiskNotice", BookStanceCopy.HIGH_RISK_NOTICE)
                                put("systemNotice", BookStanceCopy.ESOTERIC_SYSTEM_NOTICE)
                                put("layers", buildJsonArray {
                                    add(buildJsonObject {
                                        put("layer", "book")
                                        put("label", BookStanceCopy.BOOK_DEFAULT)
                                        put("bookId", zhouyi.id)
                                        put("bookTitle", zhouyi.title)
                                        put("sentences", buildJsonArray {
                                            add(buildJsonObject {
                                                put("text", "九五爻原文写到飛龍在天与利見大人。")
                                                put("citations", buildJsonArray {
                                                    add(buildJsonObject {
                                                        put("docId", "${zhouyi.id}:p1:0")
                                                        put("pdfPage", 1)
                                                        put("startUtf16", start)
                                                        put("endUtf16", start + qStr.length)
                                                        put("quote", qStr)
                                                        put("bookId", zhouyi.id)
                                                        put("bookTitle", zhouyi.title)
                                                    })
                                                })
                                            })
                                        })
                                    })
                                })
                                put("modelCalled", true)
                            },
                            error = null,
                        )
                    },
                )
            }
        }

        // 不打扰 1 & 2: 直接提问出卡，无对话框/遮罩，输入框与结果区同屏可见
        onNodeWithTag("library-qa-input").performTextInput("按周易九五飛龍在天我该买基金吗？")
        onNodeWithTag("library-qa-send").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stance-answer-card").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("stance-high-risk-notice").assertIsDisplayed()
        onNodeWithText(BookStanceCopy.HIGH_RISK_NOTICE).assertIsDisplayed()
        onNodeWithTag("stance-book-sentence-0-0").assertIsDisplayed()
        assertTrue(onAllNodesWithTag("stance-boundary-card").fetchSemanticsNodes().isEmpty(), "high_risk 不得渲染拦截卡")
        assertTrue(onAllNodesWithTag("stance-boundary-action").fetchSemanticsNodes().isEmpty(), "high_risk 不得渲染拦截按钮")
        onNodeWithTag("library-qa-input").assertIsDisplayed()

        // 换个角度里的 original 在 esoteric 书上显示成「只看书里怎么讲」
        onNodeWithTag("stance-switch-original").assertIsDisplayed()

        // 不打扰 3 & 4: 一键关闭不弹窗，关后提问框和历史回答入口仍在
        onNodeWithTag("library-qa-close").performClick()
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag("library-qa-results").fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag("library-qa-input").assertIsDisplayed()
        onNodeWithTag("library-qa-history-toggle").assertIsDisplayed()

        // 重新打开历史回答并在卡底切换立场
        onNodeWithTag("library-qa-history-toggle").performClick()
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag("stance-switch-knowledge").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("stance-switch-knowledge").performClick()
        waitUntil(timeoutMillis = 5_000) {
            askedStances.size >= 2
        }
        assertEquals("knowledge", askedStances.last())
    }
}
