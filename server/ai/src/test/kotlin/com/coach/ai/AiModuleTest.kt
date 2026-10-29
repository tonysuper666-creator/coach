package com.coach.ai

import com.coach.domain.grade.NumericValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeClientTest {

    private fun client(bridge: StubBridge) = BridgeClient(stubConfig(bridge))

    private fun request(
        stream: Boolean,
        background: Boolean = false,
        model: String = BridgeClient.CLAUDE,
    ) = ChatRequest(
        task = "test",
        model = model,
        system = "sys",
        user = "usr",
        stream = stream,
        background = background,
    )

    @Test
    fun streamAssemblesTextAndReadsTerminalFrame() {
        StubBridge().use { bridge ->
            bridge.enqueueSse(
                StubBridge.frame("逆向"),
                StubBridge.frame("选择"),
                StubBridge.terminal("complete"),
                StubBridge.DONE,
            )
            val result = client(bridge).chat(request(stream = true, model = BridgeClient.CODEX))
            assertIs<BridgeResult.Success>(result)
            assertEquals("逆向选择", result.text)
            assertEquals("complete", result.terminal.status)
            assertEquals(1, result.call.physicalCalls)
            assertEquals("gpt-6.1-sol", result.call.servedBy)
        }
    }

    @Test
    fun streamWithoutTerminalFrameCountsAsTruncated() {
        StubBridge().use { bridge ->
            bridge.enqueueSse(StubBridge.frame("讲了一半"), StubBridge.DONE)
            val result = client(bridge).chat(request(stream = true))
            assertIs<BridgeResult.Failure>(result)
            assertEquals("no_x_coach", result.reason)
            assertEquals("truncated", result.call.status)
        }
    }

    @Test
    fun failedTerminalIsNotSuccess() {
        StubBridge().use { bridge ->
            bridge.enqueueSse(StubBridge.frame("半句"), StubBridge.terminal("failed", "timeout"))
            val result = client(bridge).chat(request(stream = true))
            assertIs<BridgeResult.Failure>(result)
            assertEquals("status_failed", result.reason)
            assertEquals("timeout", result.terminal?.reason)
        }
    }

    @Test
    fun servedByFromAnotherVendorIsRejected() {
        StubBridge().use { bridge ->
            // 我们关了兜底，桥却把 Claude 的请求给了 Codex：异厂独立性没了，不能当成功
            bridge.enqueue(200, StubBridge.completion("ok", served = "gpt-6.1-sol"))
            val result = client(bridge).chat(request(stream = false))
            assertIs<BridgeResult.Failure>(result)
            assertEquals("served_by_mismatch:gpt-6.1-sol", result.reason)
            assertEquals("gpt-6.1-sol", result.call.servedBy)
        }
    }

    @Test
    fun fallbackDespiteFallbackOffIsRejected() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.fallbackCompletion("ok"))
            val result = client(bridge).chat(request(stream = false))
            assertIs<BridgeResult.Failure>(result)
            assertEquals("served_by_mismatch:fallback", result.reason)
        }
    }

    @Test
    fun httpErrorKeepsBridgePhysicalCalls() {
        StubBridge().use { bridge ->
            bridge.enqueue(504, StubBridge.errorWithTerminal(504))
            val result = client(bridge).chat(request(stream = false))
            assertIs<BridgeResult.Failure>(result)
            assertTrue(result.reason.startsWith("http_504"))
            // 每日上限按 ai_calls 算：CLI 已经跑过，错误体里的 physical_calls 不能丢
            assertEquals(1, result.call.physicalCalls)
            assertEquals("claude-sonnet-5-5", result.call.servedBy)
        }
    }

    @Test
    fun nonStreamReadsContentAndServedBy() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.completion("""[{"stem":"x"}]"""))
            val result = client(bridge).chat(request(stream = false))
            assertIs<BridgeResult.Success>(result)
            assertEquals("""[{"stem":"x"}]""", result.text)
            assertEquals("claude-sonnet-5-5", result.call.servedBy)
        }
    }

    @Test
    fun httpErrorIsFailureNotContent() {
        StubBridge().use { bridge ->
            bridge.enqueue(429, """{"error":{"type":"rate_limit","message":"daily_claude_cap"}}""")
            val result = client(bridge).chat(request(stream = false))
            assertIs<BridgeResult.Failure>(result)
            assertTrue(result.reason.startsWith("http_429"))
        }
    }

    @Test
    fun requestCarriesCoachHeaders() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.completion("ok"))
            client(bridge).chat(request(stream = false, background = true))
            val headers = bridge.requestedHeaders.single().mapKeys { it.key.lowercase() }
            assertTrue(headers["x-request-id"]?.first()?.isNotBlank() == true)
            assertEquals("off", headers["x-coach-fallback"]?.first())
            assertEquals("1", headers["x-coach-background"]?.first())
            assertTrue(bridge.requests.single().contains("\"stream\":false"))
        }
    }

    @Test
    fun healthReadsRateLimit() {
        StubBridge().use { bridge ->
            val rate = client(bridge).health()!!["rate_limit"]
            assertEquals("allowed", ((rate as kotlinx.serialization.json.JsonObject)["status"] as kotlinx.serialization.json.JsonPrimitive).content)
        }
    }
}

class TaskContractTest {

    @Test
    fun blindReviewPromptCarriesNoAnswerKey() {
        val questions = listOf(
            Mcq("Stem one is long enough", mapOf("A" to "a", "B" to "b", "C" to "c", "D" to "d"), "C", "expl", "quote"),
        )
        val prompt = Prompts.blindReview("excerpt text", questions)
        assertFalse(prompt.contains("\"key\""))
        assertFalse(prompt.contains("explanation"))
        assertFalse(prompt.contains("answer_key"))
        assertTrue(prompt.contains("\"stem\""))
        assertTrue(prompt.contains("excerpt text"))
    }

    @Test
    fun blindReviewWithoutExcerptSaysSo() {
        val prompt = Prompts.blindReview("", emptyList())
        assertTrue(prompt.contains("No source excerpt"))
        assertFalse(prompt.contains("<<<"))
    }

    @Test
    fun structuralGateRejectsBadKeysAndMissingOptions() {
        val bad = Mcq("A stem that is long enough to pass", mapOf("A" to "x", "B" to "y", "C" to "z"), "D", "", "")
        val defects = QuestionGate.structural(bad)
        assertTrue(defects.contains("needs_four_options"))
        assertTrue(defects.contains("key_not_an_option"))
        assertTrue(defects.contains("missing_source_quote"))
        assertEquals(emptyList(), QuestionGate.structural(
            Mcq("A stem that is long enough to pass", mapOf("A" to "x", "B" to "y", "C" to "z", "D" to "w"), "D", "", "a quote here"),
        ))
    }

    @Test
    fun sourceQuoteCheckToleratesMarkdownButNotRewriting() {
        val excerpt = "HHI = Σs_i². Under the DOJ/FTC thresholds, **HHI above 0.25** is highly concentrated."
        assertTrue(QuestionGate.sourceQuoteVerbatim("HHI above 0.25 is highly concentrated", excerpt))
        assertFalse(QuestionGate.sourceQuoteVerbatim("HHI above 0.30 is highly concentrated", excerpt))
    }

    @Test
    fun gradeGateRejectsForgedEvidenceAndKeepsVerbatim() {
        val answer = "这是因为信息不对称导致逆向选择，签约前就有。"
        val rubric = listOf(RubricItem("R1", "mentions adverse selection", 2.0, "逆向选择"))
        val forged = DraftGrade(listOf(GradeItem("R1", 2.0, "ok", "逆向选择发生在签约之后")), 2.0, 2.0)
        val (defects, spans) = GradeGate.evidence(answer, forged)
        assertTrue(defects.isNotEmpty())
        assertTrue(spans.isEmpty())
        val honest = DraftGrade(listOf(GradeItem("R1", 2.0, "ok", "信息不对称导致逆向选择")), 2.0, 2.0)
        val (clean, accepted) = GradeGate.evidence(answer, honest)
        assertEquals(emptyList(), clean)
        assertEquals(listOf("信息不对称导致逆向选择"), accepted)
        assertEquals(emptyList(), GradeGate.arithmetic(honest, rubric))
    }

    @Test
    fun rubricFingerprintChangesWithPoints() {
        val a = listOf(RubricItem("R1", "x", 2.0, "b"))
        val b = listOf(RubricItem("R1", "x", 3.0, "b"))
        assertTrue(GradeGate.rubricFingerprint(a) != GradeGate.rubricFingerprint(b))
        assertEquals(GradeGate.rubricFingerprint(a), GradeGate.rubricFingerprint(listOf(RubricItem("R1", "x", 2.0, "b"))))
    }

    @Test
    fun choiceAndNumericGradingAreDeterministic() {
        assertTrue(GradePipeline.gradeChoice("B", "b"))
        assertFalse(GradePipeline.gradeChoice("B", "C"))
        assertEquals(true, GradePipeline.gradeNumeric("负十一分之三", "−3/11"))
        assertEquals(false, GradePipeline.gradeNumeric("0.25", "0.52"))
        assertNull(GradePipeline.gradeNumeric("讲不清", "0.25"))
        assertTrue(NumericValue.matches("百分之二十五", "25%"))
    }
}

class GradePipelineTest {

    private val question = ConstructedQuestion(
        kpId = "ch11",
        stem = "Why is moral hazard an information problem?",
        referenceAnswer = "因为买了保险之后信息不对称，他就不爱护身体。",
        rubric = listOf(
            RubricItem("R1", "states the information asymmetry after insurance", 2.0, "信息不对称"),
        ),
    )

    private fun client(bridge: StubBridge) = BridgeClient(stubConfig(bridge))

    @Test
    fun confirmWritesResult() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.completion("""{"items":[{"id":"R1","points":2,"reason":"ok","evidence":"因为买了保险之后信息不对称"}],"total":2,"awarded":2}"""))
            bridge.enqueue(200, StubBridge.completion("""{"verdict":"CONFIRM","note":"fine","revised_total":2}"""))
            val outcome = GradePipeline(client(bridge)).gradeConstructed(question, question.referenceAnswer)
            assertTrue(outcome.written)
            assertEquals(2.0, outcome.awarded)
            assertEquals("CONFIRM", outcome.crossReview?.verdict)
        }
    }

    @Test
    fun forgedEvidenceIsSentBackOnceAndThenFailsClosed() {
        StubBridge().use { bridge ->
            val forged = """{"items":[{"id":"R1","points":2,"reason":"ok","evidence":"他在买保险之前就信息不对称"}],"total":2,"awarded":2}"""
            bridge.enqueue(200, StubBridge.completion(forged))
            bridge.enqueue(200, StubBridge.completion(forged))
            val outcome = GradePipeline(client(bridge)).gradeConstructed(question, question.referenceAnswer)
            assertFalse(outcome.written)
            assertTrue(outcome.defects.any { it.startsWith("evidence_") })
            // 一次批改 + 一次带原因的修复；修复仍失败 → 不写、也不再叫复核
            assertEquals(2, bridge.requests.size)
            assertEquals(2, outcome.calls.size)
            assertTrue(outcome.calls[1].task == "repair_grade")
            assertTrue(bridge.requests[1].contains("Your previous grading output failed"))
        }
    }

    @Test
    fun rejectedEvidenceIsRepairedOnceAndThenWritten() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.completion("""{"items":[{"id":"R1","points":2,"reason":"ok","evidence":"他在买保险之前就信息不对称"}],"total":2,"awarded":2}"""))
            bridge.enqueue(200, StubBridge.completion("""{"items":[{"id":"R1","points":2,"reason":"ok","evidence":"因为买了保险之后信息不对称"}],"total":2,"awarded":2}"""))
            bridge.enqueue(200, StubBridge.completion("""{"verdict":"CONFIRM","note":"fine","revised_total":2}"""))
            val outcome = GradePipeline(client(bridge)).gradeConstructed(question, question.referenceAnswer)
            assertTrue(outcome.written)
            assertEquals(2.0, outcome.awarded)
            assertEquals(3, outcome.calls.size)
            assertEquals(listOf("grade_constructed", "repair_grade", "cross_review_grade"), outcome.calls.map { it.task })
        }
    }

    @Test
    fun repairCannotChangeFrozenRubric() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.completion("""{"items":[{"id":"R1","points":2,"reason":"ok","evidence":"他在买保险之前就信息不对称"}],"total":2,"awarded":2}"""))
            // 修复时偷偷把分数改成 5 分：算术闸门仍然拦
            bridge.enqueue(200, StubBridge.completion("""{"items":[{"id":"R1","points":5,"reason":"ok","evidence":"因为买了保险之后信息不对称"}],"total":5,"awarded":5}"""))
            val outcome = GradePipeline(client(bridge)).gradeConstructed(question, question.referenceAnswer)
            assertFalse(outcome.written)
            assertTrue(outcome.defects.contains("points_out_of_range:R1"))
        }
    }

    @Test
    fun truncatedGradingWritesNothing() {
        StubBridge().use { bridge ->
            // 桥如实回报截断（例如 max_output_tokens）：批改不写任何结果
            bridge.enqueue(200, """{"model":"claude-sonnet-5-5","choices":[{"index":0,"message":{"content":"{\"items\":["}}],""" +
                """"x_coach":{"status":"truncated","reason":"max_output_tokens","finish_reason":"length","served_by":"claude-sonnet-5-5","fallback":false,"physical_calls":1,"request_id":"r","queue_ms":0,"ttft_ms":800,"elapsed_ms":3000}}""")
            val outcome = GradePipeline(client(bridge)).gradeConstructed(question, question.referenceAnswer)
            assertFalse(outcome.written)
            assertTrue(outcome.defects.contains("grade_call_status_truncated"))
        }
    }

    @Test
    fun reviseDoesNotWrite() {
        StubBridge().use { bridge ->
            bridge.enqueue(200, StubBridge.completion("""{"items":[{"id":"R1","points":2,"reason":"ok","evidence":"因为买了保险之后信息不对称"}],"total":2,"awarded":2}"""))
            bridge.enqueue(200, StubBridge.completion("""{"verdict":"REVISE","note":"too generous","revised_total":1}"""))
            val outcome = GradePipeline(client(bridge)).gradeConstructed(question, question.referenceAnswer)
            assertFalse(outcome.written)
            assertTrue(outcome.defects.contains("review_revise"))
        }
    }

    @Test
    fun changedRubricIsRejectedBeforeAnyCall() {
        StubBridge().use { bridge ->
            val outcome = GradePipeline(client(bridge)).gradeConstructed(
                question,
                question.referenceAnswer,
                expectedFingerprint = "not-the-frozen-one",
            )
            assertFalse(outcome.written)
            assertTrue(outcome.defects.contains("rubric_changed"))
            assertTrue(bridge.requests.isEmpty())
        }
    }
}
