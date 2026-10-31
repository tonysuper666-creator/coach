package com.coach.server

import com.coach.client.ChatItem
import com.coach.client.TutorModel
import kotlin.test.*

class AlphaClientStateTest {
    @Test fun reconnectTargetsOnlyUnfinishedRepliesAndSnapshotReplacesPartialText() {
        val model = TutorModel({ 1L })
        model.onFrame("""{"t":"msg.start","msg":"one","threadId":"thread","model":"claude-sonnet-5-5"}""")
        model.onFrame("""{"t":"msg.delta","msg":"one","i":1,"text":"partial"}""")
        model.onConnection(false)
        assertEquals(listOf("one"), model.unfinishedMessageIds())
        model.onFrame("""{"t":"msg.snapshot","msg":"one","threadId":"thread","i":3,"text":"complete answer","status":"complete"}""")
        model.onFrame("""{"t":"msg.delta","msg":"one","i":2,"text":"late duplicate"}""")
        val message = (model.state.value.threads.getValue("thread").items.single() as ChatItem.Tutor).message
        assertEquals("complete answer", message.text)
        assertTrue(model.unfinishedMessageIds().isEmpty())
    }

    @Test fun rateLimitNoticeSurvivesTheReplacementAnswerAndPartialTextStaysCleared() {
        val model = TutorModel({ 1L })
        model.onSent("thread", "teach", null)
        model.onFrame("""{"t":"msg.start","msg":"old","threadId":"thread","model":"claude-sonnet-5-5"}""")
        model.onFrame("""{"t":"msg.delta","msg":"old","i":1,"text":"partial"}""")
        model.onFrame("""{"t":"msg.snapshot","msg":"old","threadId":"thread","i":2,"text":"","status":"failed"}""")
        model.onFrame("""{"t":"tutor.progress","threadId":"thread","phase":"fallback","text":"Claude 暂时限流，已换 gpt-6.1-sol 回答"}""")
        model.onFrame("""{"t":"msg.end","msg":"old","status":"failed","reason":"rate_limited"}""")
        model.onFrame("""{"t":"msg.start","msg":"new","threadId":"thread","model":"gpt-6.1-sol","fallback":true}""")
        model.onFrame("""{"t":"msg.delta","msg":"new","i":1,"text":"replacement"}""")
        val view = model.state.value.threads.getValue("thread")
        assertEquals("Claude 暂时限流，已换 gpt-6.1-sol 回答", view.notice)
        val messages = view.items.map { (it as ChatItem.Tutor).message }
        assertEquals("", messages.first().text)
        assertEquals("gpt-6.1-sol", messages.last().model)
        assertEquals("replacement", messages.last().text)
        assertTrue(model.state.value.ttft.any { it.fallback })
    }
}
