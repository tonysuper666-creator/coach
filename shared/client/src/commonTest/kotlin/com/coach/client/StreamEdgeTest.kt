package com.coach.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** TON-1241 审计：断线续传与终态帧的边界。 */
class StreamEdgeTest {
    private val thread = "kp:K1"
    private fun TutorModel.tutorMessage(id: String) =
        state.value.threads.getValue(thread).items.filterIsInstance<ChatItem.Tutor>().single { it.message.id == id }.message

    /** 缺了一段 delta、终态帧先到：续传回来的已是落库的终态快照（不带 model），终态帧里的出答模型不能丢。 */
    @Test fun heldEndFrameSurvivesAFinishedSnapshot() {
        val m = TutorModel({ 1_000L })
        m.onFrame("""{"t":"msg.start","msg":"m1","threadId":"$thread","courseId":"C","model":"claude-opus","fallback":false}""")
        m.onFrame("""{"t":"msg.delta","msg":"m1","i":1,"text":"答"}""")
        m.onFrame("""{"t":"msg.delta","msg":"m1","i":3,"text":"是"}""")
        m.onFrame("""{"t":"msg.end","msg":"m1","status":"complete","reason":null,"chars":3,"model":"gpt-codex"}""")
        m.onFrame("""{"t":"msg.snapshot","msg":"m1","threadId":"$thread","i":3,"text":"答案是","status":"complete","reason":null}""")
        val message = m.tutorMessage("m1")
        assertEquals("答案是", message.text)
        assertEquals("complete", message.status)
        assertEquals("gpt-codex", message.model, "消息头显示的应是实际出答的模型")
    }

    /** 服务端失败时 reason 可能是 JSON null：界面应显示「原因未知」，而不是「（null）」。 */
    @Test fun nullReasonIsNoReason() {
        val m = TutorModel({ 1_000L })
        m.onFrame("""{"t":"msg.start","msg":"m1","threadId":"$thread","courseId":"C","model":"claude-opus","fallback":false}""")
        m.onFrame("""{"t":"msg.end","msg":"m1","status":"failed","reason":null,"chars":0,"model":"claude-opus"}""")
        assertEquals("failed", m.tutorMessage("m1").status)
        assertNull(m.tutorMessage("m1").reason)
        val ended = ChatStreamAssembler().feed("""{"t":"msg.end","msg":"m2","status":"failed","reason":null,"model":null}""").single() as ChatUpdate.Ended
        assertNull(ended.reason)
        assertNull(ended.model)
    }
}
