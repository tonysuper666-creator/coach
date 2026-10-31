package com.coach.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TutorModelTest {
    private val thread = "kp:K1"
    private fun model(samples: MutableList<TtftSample> = mutableListOf()) = TutorModel({ 1_000L }) { samples += it }
    private fun TutorModel.tutorMessage(id: String) =
        state.value.threads.getValue(thread).items.filterIsInstance<ChatItem.Tutor>().single { it.message.id == id }.message

    private fun start(id: String, model: String = "claude-opus", fallback: Boolean = false) =
        """{"t":"msg.start","msg":"$id","threadId":"$thread","courseId":"C","model":"$model","fallback":$fallback}"""
    private fun delta(id: String, i: Int, text: String) = """{"t":"msg.delta","msg":"$id","i":$i,"text":"$text"}"""

    @Test fun streamedTextAccumulatesAndDuplicateIndexIsDropped() {
        val m = model()
        m.onFrame(start("m1")); m.onFrame(delta("m1", 1, "你好，")); m.onFrame(delta("m1", 2, "世界")); m.onFrame(delta("m1", 2, "世界"))
        m.onFrame("""{"t":"msg.end","msg":"m1","status":"complete","chars":5}""")
        val message = m.tutorMessage("m1")
        assertEquals("你好，世界", message.text)
        assertEquals("complete", message.status)
        assertTrue(message.done)
    }

    @Test fun headerCarriesTheModelThatActuallyAnswered_includingFallback() {
        val m = model()
        m.onFrame(start("m1", "claude-opus")); m.onFrame("""{"t":"msg.end","msg":"m1","status":"failed","reason":"first_token_timeout","chars":0}""")
        m.onFrame(start("m2", "gpt-codex", fallback = true)); m.onFrame(delta("m2", 1, "答"))
        assertEquals("failed", m.tutorMessage("m1").status)
        assertEquals("first_token_timeout", m.tutorMessage("m1").reason)
        assertEquals("gpt-codex", m.tutorMessage("m2").model)
        assertTrue(m.tutorMessage("m2").fallback)
    }

    @Test fun endFrameCarriesTheModelTheBridgeActuallyUsed() {
        val m = model()
        m.onFrame(start("m1", "claude-opus"))
        m.onFrame(delta("m1", 1, "答"))
        assertEquals("claude-opus", m.tutorMessage("m1").model)
        m.onFrame("""{"t":"msg.end","msg":"m1","status":"complete","chars":1,"model":"gpt-codex"}""")
        assertEquals("gpt-codex", m.tutorMessage("m1").model)
        // 终态帧没带模型名（旧服务端）时保持开头写的名字
        m.onFrame(start("m2", "claude-opus")); m.onFrame("""{"t":"msg.end","msg":"m2","status":"complete","chars":0}""")
        assertEquals("claude-opus", m.tutorMessage("m2").model)
    }

    @Test fun snapshotReplacesTheBodyAndBumpsTheRenderEpoch() {
        val m = model()
        m.onFrame(start("m1")); m.onFrame(delta("m1", 1, "旧")); m.onFrame(delta("m1", 2, "文"))
        m.onFrame("""{"t":"msg.snapshot","msg":"m1","threadId":"$thread","i":2,"text":"新文","status":"streaming"}""")
        m.onFrame(delta("m1", 3, "字"))
        val message = m.tutorMessage("m1")
        assertEquals("新文字", message.text)
        assertEquals(1, message.epoch)
    }

    @Test fun firstTokenIsMeasuredFromTheTapAndProgressFramesDoNotCount() {
        val samples = mutableListOf<TtftSample>()
        val m = model(samples)
        m.onSent(thread, "next", null)
        m.onFrame(start("m1"))
        m.onFrame("""{"t":"msg.progress","msg":"m1","phase":"thinking"}""")
        assertTrue(m.tutorMessage("m1").thinking)
        assertTrue(samples.isEmpty())
        m.onFrame(delta("m1", 1, "第一个字"))
        m.onFrame(delta("m1", 2, "第二段"))
        assertEquals(1, samples.size)
        assertEquals("next", samples.single().kind)
        assertFalse(m.tutorMessage("m1").thinking)
    }

    @Test fun tutorStateDrivesButtonLabelAndBusy() {
        val m = model()
        m.onFrame("""{"t":"tutor.state","threadId":"$thread","state":"TEACH","nextLabel":"换个例子再讲"}""")
        val view = m.state.value.threads.getValue(thread)
        assertEquals("换个例子再讲", view.nextLabel)
        assertTrue(view.busy)
        m.onFrame("""{"t":"tutor.state","threadId":"$thread","state":"AWAIT","nextLabel":"下一步"}""")
        assertFalse(m.state.value.threads.getValue(thread).busy)
    }

    @Test fun poolEmptyShowsTheServersRealProgressText() {
        val m = model()
        m.onFrame("""{"t":"tutor.progress","threadId":"$thread","phase":"pool_queued","text":"正在补题（约 20 秒）"}""")
        assertEquals("正在补题（约 20 秒）", m.state.value.threads.getValue(thread).poolText)
    }

    @Test fun disconnectIsRecordedWithTheLastSeenTime() {
        var now = 5_000L
        val m = TutorModel({ now })
        m.onConnection(true); now = 9_000; m.onConnection(false)
        assertFalse(m.state.value.connected)
        assertEquals(5_000L, m.state.value.lastSeenAtMs)
        assertNull(m.state.value.threads[thread])
    }
}
