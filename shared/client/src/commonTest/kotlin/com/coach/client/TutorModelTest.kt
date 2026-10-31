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

    @Test fun serverConfirmationAndRepeatedHistoryMergeWithTheOptimisticLearnerMessage() {
        val m = model()
        m.onSent(thread,"ask","Synthetic question","client-1")
        val user = """{"t":"msg.user","role":"user","msg":"saved-user","clientMsgId":"client-1","threadId":"$thread","createdAt":123,"text":"Synthetic question"}"""
        m.onFrame(user); m.onFrame(user)
        assertEquals(1,m.state.value.threads.getValue(thread).items.size)
        m.onFrame(start("reply")); m.onFrame(start("reply"))
        m.onFrame(delta("reply",1,"Synthetic answer"))
        val reply = """{"t":"msg.snapshot","role":"assistant","msg":"reply","threadId":"$thread","model":"synthetic-model","i":1,"status":"complete","text":"Synthetic answer"}"""
        val history = """{"t":"msg.history","threadId":"$thread","legacyHistoryUncovered":true,"messages":[$user,$reply]}"""
        m.onFrame(history); m.onFrame(history)
        val view = m.state.value.threads.getValue(thread)
        assertEquals(listOf("l:saved-user","t:reply"),view.items.map { it.key })
        assertEquals(123L,view.items.filterIsInstance<ChatItem.Learner>().single().createdAt)
        assertEquals("synthetic-model",m.tutorMessage("reply").model)
        assertTrue(view.historyLoaded)
        assertTrue(view.notice!!.contains("缺失的提问无法恢复"))
    }

    @Test fun historyArrivingWhileANewSendIsPendingRetainsThatSend() {
        val m = model()
        m.onSent(thread,"ask","Still sending","pending-1")
        m.onFrame("""{"t":"msg.history","threadId":"$thread","messages":[]}""")
        assertEquals("Still sending",m.state.value.threads.getValue(thread).items.filterIsInstance<ChatItem.Learner>().single().text)
    }

    @Test fun clientMessageIdsAreScopedToTheSendingDevice() {
        val m = model()
        m.bindDevice("desktop")
        m.onSent(thread,"ask","Desktop question","same-local-id")
        m.onFrame("""{"t":"msg.user","msg":"phone-user","threadId":"$thread","clientMsgId":"same-local-id","deviceId":"phone","text":"Phone question"}""")
        m.onFrame("""{"t":"msg.user","msg":"desktop-user","threadId":"$thread","clientMsgId":"same-local-id","deviceId":"desktop","text":"Desktop question"}""")
        assertEquals(setOf("Desktop question","Phone question"),m.state.value.threads.getValue(thread).items.filterIsInstance<ChatItem.Learner>().map { it.text }.toSet())
        assertEquals(2,m.state.value.threads.getValue(thread).items.size)
    }

    @Test fun restoringOldAnswersDoesNotConsumeTheFirstTokenMeasurementOfANewSend() {
        val samples = mutableListOf<TtftSample>()
        val m = model(samples)
        m.onSent(thread,"ask","A new question","new-send")
        m.onFrame("""{"t":"msg.history","threadId":"$thread","messages":[{"t":"msg.snapshot","role":"assistant","msg":"old","threadId":"$thread","i":1,"text":"Old answer","status":"complete"}]}""")
        assertTrue(samples.isEmpty())
        m.onFrame(start("new")); m.onFrame(delta("new",1,"New answer"))
        assertEquals("new",samples.single().messageId)
    }

    @Test fun aTransportRejectedSendDoesNotLeaveAnExtraLearnerRowOnRetry() {
        val m = model()
        m.onSent(thread,"ask","Synthetic question","rejected")
        m.onSendRejected(thread,"rejected")
        assertTrue(m.state.value.threads.getValue(thread).items.isEmpty())
        m.onSent(thread,"ask","Synthetic question","retry")
        assertEquals(1,m.state.value.threads.getValue(thread).items.size)
    }
}

/** 在线缺号：不断线也能自动找回（模型发现缺号 → 发送端发 msg.resume → 快照补齐）。 */
class TutorModelResumeTest {
    private val thread = "kp:K1"
    private var now = 1_000L
    private val requests = mutableListOf<String>()
    private var accept = true
    private val model = TutorModel({ now }).also { m -> m.resumeSender = { id -> requests += id; accept } }

    private fun start(id: String) = model.onFrame("""{"t":"msg.start","msg":"$id","threadId":"$thread","courseId":"C","model":"claude-opus","fallback":false}""")
    private fun delta(id: String, i: Int, text: String) = model.onFrame("""{"t":"msg.delta","msg":"$id","i":$i,"text":"$text"}""")
    private fun math(id: String, i: Int, tex: String) = model.onFrame("""{"t":"msg.math","msg":"$id","i":$i,"tex":"$tex","display":true}""")
    private fun end(id: String, chars: Int) = model.onFrame("""{"t":"msg.end","msg":"$id","status":"complete","chars":$chars}""")
    private fun snapshot(id: String, i: Int, text: String, status: String = "streaming") =
        model.onFrame("""{"t":"msg.snapshot","msg":"$id","threadId":"$thread","i":$i,"text":"$text","status":"$status"}""")
    private fun message(id: String) = model.state.value.threads.getValue(thread).items.filterIsInstance<ChatItem.Tutor>().single { it.message.id == id }.message

    @Test fun finishedHistoryRestoresMetadataWithoutSchedulingResumes() {
        for (status in listOf("complete","truncated","cancelled","failed")) {
            model.onFrame("""{"t":"msg.history","threadId":"$thread","messages":[{"t":"msg.snapshot","role":"assistant","msg":"old-$status","threadId":"$thread","i":1,"text":"Saved answer","status":"$status","model":"saved-model","reason":"saved-reason"}]}""")
            assertEquals(status,message("old-$status").status)
            assertEquals("saved-model",message("old-$status").model)
            assertEquals("saved-reason",message("old-$status").reason)
        }
        now += 21_000
        model.resumeTick()
        assertTrue(model.unfinishedMessageIds().isEmpty())
        assertTrue(requests.isEmpty(),"Finished historical messages must never request resume")
    }

    @Test fun streamingHistoryRetainsStallProbesAndGapRecoveryWithoutDuplicatingRows() {
        val history = """{"t":"msg.history","threadId":"$thread","messages":[{"t":"msg.user","role":"user","msg":"user-1","threadId":"$thread","text":"Synthetic question"},{"t":"msg.snapshot","role":"assistant","msg":"live","threadId":"$thread","model":"saved-model","i":1,"text":"甲","status":"streaming"}]}"""
        model.onFrame(history); model.onFrame(history)
        assertEquals(2,model.state.value.threads.getValue(thread).items.size)
        assertEquals(1,message("live").epoch,"An unchanged history snapshot must not reset rendering")
        now += 19_000; model.resumeTick()
        assertTrue(requests.isEmpty())
        now += 2_000; model.resumeTick()
        assertEquals(listOf("live"),requests,"Restored streaming messages still need stall probes")
        snapshot("live",1,"甲")
        delta("live",3,"丙")
        assertEquals(listOf("live","live"),requests,"A live gap still dispatches resume outside the model lock")
        snapshot("live",3,"甲乙丙","complete")
        now += 21_000; model.resumeTick()
        assertEquals(listOf("live","live"),requests)
        assertEquals("甲乙丙",message("live").text)
        assertEquals("saved-model",message("live").model)
        assertTrue(message("live").done)
    }

    @Test fun aMissingMiddleDeltaAsksForResumeOnceAndTheSnapshotRestoresTheFullText() {
        start("m1"); delta("m1", 1, "甲"); delta("m1", 3, "丙"); delta("m1", 4, "丁"); delta("m1", 5, "戊")
        assertEquals(listOf("m1"), requests, "连续多帧越过缺口只发一次 msg.resume")
        assertEquals("甲", message("m1").text)
        snapshot("m1", 5, "甲乙丙丁戊")
        delta("m1", 5, "戊"); delta("m1", 6, "己")
        assertEquals("甲乙丙丁戊己", message("m1").text, "快照之后继续接帧，重复帧不重复追加")
        end("m1", 6)
        assertEquals("complete", message("m1").status)
        assertEquals(listOf("m1"), requests)
    }

    @Test fun aMissingFormulaFrameIsRecoveredToo() {
        start("m1"); delta("m1", 1, "能量 "); math("m1", 3, "E=mc^2")
        assertEquals(listOf("m1"), requests)
        snapshot("m1", 3, "能量 $$"+"E=mc^2"+"$$")
        math("m1", 3, "E=mc^2"); delta("m1", 4, "。")
        assertEquals("能量 $$"+"E=mc^2"+"$$。", message("m1").text)
    }

    @Test fun aLostLastFrameKeepsTheMessageOpenUntilTheSnapshotArrives() {
        start("m1"); delta("m1", 1, "甲"); delta("m1", 2, "乙")
        end("m1", 3)   // 第 3 帧（末段正文）丢了，终态帧到达
        assertEquals(listOf("m1"), requests)
        assertEquals("streaming", message("m1").status, "全文不完整前不标完成")
        assertFalse(message("m1").done)
        snapshot("m1", 3, "甲乙丙")   // 服务端此刻仍视为生成中，终态帧已被暂扣
        assertEquals("甲乙丙", message("m1").text)
        assertEquals("complete", message("m1").status)
    }

    @Test fun aSnapshotOfAFinishedMessageClosesIt() {
        start("m1"); delta("m1", 1, "甲"); delta("m1", 3, "丙"); end("m1", 3)
        snapshot("m1", 3, "甲乙丙", "complete")
        assertEquals("甲乙丙", message("m1").text)
        assertEquals("complete", message("m1").status)
    }

    @Test fun aRejectedSendIsRetriedOnTheNextTickAndAnUnansweredOneAfterTheWindow() {
        accept = false
        start("m1"); delta("m1", 1, "甲"); delta("m1", 3, "丙")
        assertEquals(listOf("m1"), requests)
        accept = true
        model.resumeTick()
        assertEquals(listOf("m1", "m1"), requests, "队列满/断线没发出去，下一次就补发")
        model.resumeTick()
        assertEquals(2, requests.size, "已发出且还在等快照时不重复发")
        now += 3_000
        model.resumeTick()
        assertEquals(3, requests.size, "快照迟迟不到，过了窗口再发")
        snapshot("m1", 3, "甲乙丙")
        now += 10_000
        model.resumeTick()
        assertEquals(3, requests.size, "快照到了就不再发")
    }

    @Test fun aSenderThatDeliversTheSnapshotSynchronouslyDoesNotDeadlock() {
        val m = TutorModel({ now })
        m.resumeSender = { id ->
            requests += id
            m.onFrame("""{"t":"msg.snapshot","msg":"$id","threadId":"$thread","i":3,"text":"甲乙丙","status":"streaming"}""")
            true
        }
        m.onFrame("""{"t":"msg.start","msg":"m1","threadId":"$thread","courseId":"C","model":"x","fallback":false}""")
        m.onFrame("""{"t":"msg.delta","msg":"m1","i":1,"text":"甲"}""")
        m.onFrame("""{"t":"msg.delta","msg":"m1","i":3,"text":"丙"}""")
        assertEquals("甲乙丙", m.state.value.threads.getValue(thread).items.filterIsInstance<ChatItem.Tutor>().single().message.text)
    }

    @Test fun aLostTerminalFrameIsFoundByTheStallProbe() {
        start("m1"); delta("m1", 1, "甲")
        now += 19_000; model.resumeTick()
        assertTrue(requests.isEmpty(), "还没安静够久，正常思考不打扰")
        now += 2_000; model.resumeTick()
        assertEquals(listOf("m1"), requests)
        snapshot("m1", 1, "甲", "complete")
        assertEquals("complete", message("m1").status)
    }

    @Test fun reconnectingForgetsTheOldConnectionsPendingResume() {
        start("m1"); delta("m1", 1, "甲"); delta("m1", 3, "丙")
        model.onConnection(false); model.onConnection(true)
        delta("m1", 4, "丁")
        assertEquals(listOf("m1", "m1"), requests)
    }
}
