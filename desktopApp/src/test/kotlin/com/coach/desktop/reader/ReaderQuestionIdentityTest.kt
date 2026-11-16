package com.coach.desktop.reader

import com.coach.client.ReaderReply
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReaderQuestionIdentityTest {
    @Test fun pageResponseKeepsTheWeekForTheRealQuestionEvenWithoutAnAnchor() = runBlocking {
        val fake = FakeReaderService(Triple("book", "v1", 2))
        val source = object : ReaderSource by fake {
            override suspend fun study(document: String, pdfPage: Int) = ReaderReply(200, buildJsonObject {
                put("documentId", document); put("pdfPage", pdfPage); put("unlocated", 0)
                put("questions", JsonArray(listOf(buildJsonObject {
                    put("week", 2); put("id", "same-id"); put("stem", "question"); put("anchor", "")
                })))
            })
        }
        val reader = ReaderRemote(source)
        reader.loadCatalog(); reader.open("book"); reader.show(1)
        val question = (reader.study as StudyState.Ready).view.questions.single()
        assertEquals("same-id", question.id)
        assertEquals("", question.anchor)
        val weekGetter = question.javaClass.methods.firstOrNull { it.name == "getWeek" }
        assertNotNull(weekGetter, "page practice must retain the week supplied by the service")
        assertEquals(2, weekGetter.invoke(question))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun blankAnchorStillOffersTheExactQuestionAndKeepsReadingPosition() {
        val fake = FakeReaderService(Triple("book", "v1", 3))
        val source = object : ReaderSource by fake {
            override suspend fun study(document: String, pdfPage: Int) = ReaderReply(200, buildJsonObject {
                put("documentId", document); put("pdfPage", pdfPage); put("unlocated", 2)
                put("questions", JsonArray(listOf(buildJsonObject {
                    put("week", 2); put("id", "same-id"); put("stem", "Real page question"); put("anchor", "")
                })))
            })
        }
        val reader = ReaderRemote(source)
        val session = ReaderSession(ReaderFixture("Synthetic", emptyList(), emptyList()), 1400, reader)
        runBlocking { reader.loadCatalog(); session.openRemoteAt("book", 2) }
        session.focusPane(ReaderPane.Related)
        var opened: StudyQuestion? = null
        runComposeUiTest {
            setContent { ReaderWindow(session, onPractice = { opened = it }) }
            onNodeWithTag("study-practice-0").performClick()
            assertEquals("same-id", opened?.id)
            assertEquals(2, opened?.week)
            assertEquals(1, session.state.position.page)
            onNodeWithText("本课程另有2个题目或考点条目位置待确认").assertExists()
        }
    }
}
