package com.coach.server

import com.coach.client.ChatItem
import com.coach.client.TutorClient
import com.coach.client.TutorMessage
import com.coach.client.TutorModel
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 真实 [TutorClient] ↔ 本地 Ktor WS 桩：桩按脚本漏发帧，验证在线（不断线）缺号时客户端自己发 msg.resume 并拿回全文。
 * 全程本地桩，不调用任何真实订阅 CLI。
 */
class TutorClientResumeTest {
    private val full = "牛顿第二定律：合力等于质量乘加速度。"
    private val thread = "kp:K1"

    private fun start(id: String) = """{"t":"msg.start","msg":"$id","threadId":"$thread","courseId":"C","model":"claude-opus","fallback":false}"""
    private fun delta(id: String, i: Int, text: String) = """{"t":"msg.delta","msg":"$id","i":$i,"text":"$text"}"""
    private fun math(id: String, i: Int, tex: String) = """{"t":"msg.math","msg":"$id","i":$i,"tex":"$tex","display":true}"""
    private fun end(id: String, chars: Int) = """{"t":"msg.end","msg":"$id","status":"complete","chars":$chars}"""
    private fun snapshot(id: String, i: Int, text: String, status: String) =
        """{"t":"msg.snapshot","msg":"$id","threadId":"$thread","i":$i,"text":"$text","status":"$status"}"""

    /** 桩服务端：每条连接跑一次 [script]；客户端发来的 msg.resume 进入 resumes。 */
    private class Stub(private val script: suspend DefaultWebSocketServerSession.(connection: Int, resumes: Channel<String>) -> Unit) : AutoCloseable {
        val connections = AtomicInteger()
        val resumes = Channel<String>(Channel.UNLIMITED)
        private val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/v1/ws") {
                    val connection = connections.incrementAndGet()
                    val reader = launch {
                        for (frame in incoming) if (frame is Frame.Text) {
                            val message = Json.parseToJsonElement(frame.readText()).jsonObject
                            if (message["t"]?.jsonPrimitive?.content == "msg.resume") resumes.trySend(message["msg"]!!.jsonPrimitive.content)
                        }
                    }
                    try { script(connection, resumes) } finally { reader.cancel() }
                }
            }
        }.apply { start(wait = false) }
        val port: Int get() = runBlocking { engine.engine.resolvedConnectors().first().port }
        override fun close() { engine.stop(0, 200) }
    }

    private suspend fun DefaultWebSocketServerSession.emit(vararg frames: String) = frames.forEach { send(Frame.Text(it)) }

    private fun <T> withClient(stub: Stub, block: (TutorModel) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:${stub.port}", "device", "t".repeat(43), model)
        client.start(scope)
        try { return block(model) } finally { scope.cancel(); client.close() }
    }

    private fun TutorModel.messages(): List<TutorMessage> =
        state.value.threads[thread]?.items.orEmpty().filterIsInstance<ChatItem.Tutor>().map { it.message }

    private fun TutorModel.waitFor(what: String, check: (List<TutorMessage>) -> Boolean) = runBlocking {
        try { withTimeout(15_000) { while (!check(messages())) delay(20) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timeout waiting for $what; messages=${messages()}") }
    }

    @Test fun aMissingBodyFrameIsRecoveredOverTheSameConnectionAndLaterFramesKeepAppending() {
        Stub { _, resumes ->
            emit(start("m1"), delta("m1", 1, "牛顿"), delta("m1", 3, "合力等于"))   // 第 2 帧丢了
            assertEquals("m1", resumes.receive())
            emit(snapshot("m1", 3, "牛顿第二定律：合力等于", "streaming"))
            emit(delta("m1", 3, "合力等于"), delta("m1", 4, "质量乘加速度。"), end("m1", full.length))   // 重复帧 + 后续帧
            delay(500)
        }.use { stub ->
            withClient(stub) { model ->
                model.waitFor("complete") { it.singleOrNull()?.status == "complete" }
                assertEquals(full, model.messages().single().text)
                assertEquals(1, stub.connections.get(), "不断线")
            }
        }
    }

    @Test fun aMissingFormulaFrameInTheMiddleIsRecovered() {
        val body = "能量 \$\$E=mc^2\$\$。"
        Stub { _, resumes ->
            emit(start("m1"), delta("m1", 1, "能量 "))   // 第 2 帧（公式）丢了
            emit(delta("m1", 3, "。"))
            assertEquals("m1", resumes.receive())
            emit(snapshot("m1", 3, body, "complete"))
            delay(500)
        }.use { stub ->
            withClient(stub) { model ->
                model.waitFor("complete") { it.singleOrNull()?.status == "complete" }
                assertEquals(body, model.messages().single().text)
                assertEquals(1, stub.connections.get())
            }
        }
    }

    @Test fun aLostLastBodyFrameIsNotMarkedCompleteUntilTheFullTextArrives() {
        Stub { _, resumes ->
            emit(start("m1"), delta("m1", 1, "牛顿第二定律："), delta("m1", 2, "合力等于质量"))   // 第 3 帧（末段）丢了
            emit(end("m1", full.length))
            assertEquals("m1", resumes.receive())
            delay(300)   // 快照到之前不能是完成
            emit(snapshot("m1", 3, full, "streaming"))   // 服务端此刻仍视为生成中：靠暂扣的终态帧收尾
            delay(500)
        }.use { stub ->
            withClient(stub) { model ->
                model.waitFor("stream start") { it.isNotEmpty() }
                Thread.sleep(150)
                assertFalse(model.messages().single().done, "全文不完整前不标完成")
                model.waitFor("complete") { it.singleOrNull()?.status == "complete" }
                assertEquals(full, model.messages().single().text)
                assertEquals(1, stub.connections.get())
            }
        }
    }

    @Test fun afterADisconnectTheReconnectResumesTheUnfinishedMessage() {
        Stub { connection, resumes ->
            if (connection == 1) {
                emit(start("m1"), delta("m1", 1, "牛顿第二定律："))
                delay(200)
                close()   // 断线
            } else {
                assertEquals("m1", resumes.receive())
                emit(snapshot("m1", 2, full, "complete"))
                delay(500)
            }
        }.use { stub ->
            withClient(stub) { model ->
                model.waitFor("complete after reconnect") { it.singleOrNull()?.status == "complete" }
                assertEquals(full, model.messages().single().text)
                assertTrue(stub.connections.get() >= 2)
            }
        }
    }

    @Test fun aGapRightAfterReconnectStillRecoversOnTheNewConnection() {
        Stub { connection, resumes ->
            if (connection == 1) {
                emit(start("m1"), delta("m1", 1, "牛顿第二定律："))
                delay(200)
                close()
            } else {
                assertEquals("m1", resumes.receive())   // 重连续传
                emit(snapshot("m1", 1, "牛顿第二定律：", "streaming"))
                emit(delta("m1", 3, "质量乘加速度。"))   // 第 2 帧在新连接上也丢了
                assertEquals("m1", resumes.receive())
                emit(snapshot("m1", 3, full, "complete"))
                delay(500)
            }
        }.use { stub ->
            withClient(stub) { model ->
                model.waitFor("complete") { it.singleOrNull()?.status == "complete" }
                assertEquals(full, model.messages().single().text)
            }
        }
    }
}
