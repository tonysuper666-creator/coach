package com.coach.server

import com.coach.client.ChatStreamAssembler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 客户端最小消费端的行为：`(msg, i)` 去重、`msg.snapshot` 优先、可见正文与服务端一致。
 * 纯逻辑，不起服务端（DESIGN §6 断线续传的判断都在这一层）。
 */
class ChatStreamAssemblerTest {

    private fun delta(id: String, i: Int, text: String) =
        """{"t":"msg.delta","msg":"$id","i":$i,"text":"${text.replace("\"", "\\\"")}"}"""

    @Test
    fun duplicateIndicesAreDropped() {
        val assembler = ChatStreamAssembler()
        val first = assembler.feed(delta("m1", 1, "你"))
        val duplicate = assembler.feed(delta("m1", 1, "你"))
        val outOfOrder = assembler.feed(delta("m1", 1, "好"))
        val next = assembler.feed(delta("m1", 2, "好"))

        assertEquals(1, first.size)
        assertTrue(duplicate.isEmpty(), "duplicate (msg, i) was applied twice")
        assertTrue(outOfOrder.isEmpty(), "a late duplicate was appended")
        assertEquals(1, next.size)
        assertEquals("你好", assembler.text("m1"))
    }

    @Test
    fun snapshotReplacesTheBufferAndBlocksEarlierDeltas() {
        val assembler = ChatStreamAssembler()
        assembler.feed(delta("m1", 1, "旧"))
        assembler.feed(delta("m1", 2, "的"))
        val snapshot = assembler.feed("""{"t":"msg.snapshot","msg":"m1","i":7,"text":"已经落库的前半段","status":"streaming"}""")

        assertEquals(1, snapshot.size)
        assertEquals("已经落库的前半段", assembler.text("m1"))
        // 重连后服务端可能重发旧序号：必须丢掉，否则会重复
        assertTrue(assembler.feed(delta("m1", 6, "重复")).isEmpty())
        assembler.feed(delta("m1", 8, "，接着写"))
        assertEquals("已经落库的前半段，接着写", assembler.text("m1"))
    }

    @Test
    fun mathBlocksKeepTheirMarkdownShapeAndStatusIsTracked() {
        val assembler = ChatStreamAssembler()
        assembler.feed("""{"t":"msg.start","msg":"m2","model":"claude-sonnet-5-5","fallback":false}""")
        assembler.feed(delta("m2", 1, "公式是 "))
        assembler.feed("""{"t":"msg.math","msg":"m2","i":2,"tex":"E = p \\cdot q","display":true}""")
        val end = assembler.feed("""{"t":"msg.end","msg":"m2","status":"complete","reason":null,"chars":18}""")

        assertEquals("公式是 \$\$E = p \\cdot q\$\$", assembler.text("m2"))
        assertEquals("complete", assembler.status("m2"))
        assertEquals(1, end.size)
    }

    @Test
    fun lateReplayAfterEndCannotDuplicateTheAnswer() {
        val assembler = ChatStreamAssembler()
        assembler.feed(delta("m1", 1, "完整回答。"))
        assembler.feed("""{"t":"msg.end","msg":"m1","status":"complete"}""")
        assertTrue(assembler.feed(delta("m1", 1, "完整回答。")).isEmpty())
        assertEquals("完整回答。", assembler.text("m1"))
    }

    @Test
    fun delayedSnapshotCannotRollBackANewerResume() {
        val assembler = ChatStreamAssembler()
        assembler.feed("""{"t":"msg.snapshot","msg":"m1","i":8,"text":"较新的回答","status":"streaming"}""")
        assertTrue(assembler.feed("""{"t":"msg.snapshot","msg":"m1","i":6,"text":"旧回答","status":"streaming"}""").isEmpty())
        assembler.feed(delta("m1", 9, "。"))
        assertEquals("较新的回答。", assembler.text("m1"))
    }

    @Test
    fun unknownFramesAreIgnoredInsteadOfThrowing() {
        val assembler = ChatStreamAssembler()
        assertTrue(assembler.feed("not json").isEmpty())
        assertTrue(assembler.feed("""{"t":"ledger","course":"c"}""").isEmpty())
        assertTrue(assembler.feed("""{"t":"msg.delta","msg":"m3"}""").isEmpty())
    }
}
