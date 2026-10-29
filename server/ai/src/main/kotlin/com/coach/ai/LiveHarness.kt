package com.coach.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 出题与批改的 live 跑批。真实 CLI（经桥副本 8791）＋ 本机语料，不花额外额度。
 *
 * 语料与结果都在 `~/Coach/corpus/gradegen/`（`COACH_CORPUS_DIR` 可改），仓库里只放这个加载器与合成用例：
 *
 * ```
 * inputs/kps.json            20+ 个 KP：id、objective、excerpt（教材页原文）
 * inputs/mcq60.json          108 题里的 60 道选择题（题干、选项、答案键）
 * inputs/planted.json        植入错键题（含人工标注的植入类型）
 * inputs/real_answers.json   他本人的 5 条中文作答（原始文本）
 * inputs/real_evidence.json  这 5 条作答里应当被接受的逐字引文
 * runs/ai_calls.jsonl        每次物理调用（call_id、task、served_by、ttft、dur、physical_calls）
 * ```
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "help"
    val options = parseOptions(args.drop(1))
    val corpus = Path.of(System.getenv("COACH_CORPUS_DIR") ?: "${System.getProperty("user.home")}/Coach/corpus/gradegen")
    val client = BridgeClient()
    val harness = LiveHarness(corpus, client, options)
    when (command) {
        "kps" -> harness.kps()
        "mcq" -> harness.mcq()
        "planted" -> harness.planted()
        "real-answers" -> harness.realAnswers()
        "constructed" -> harness.constructed()
        "liveTest" -> harness.liveTest()
        else -> println(USAGE)
    }
}

private const val USAGE = """
用法: LiveHarness <kps|mcq|planted|real-answers|constructed|liveTest> [--limit N] [--offset N] [--batch N] [--kp id] [--force]

  kps --limit 5 --offset 0      生成 + 盲复核 N 个 KP（Codex 出、Claude 复核）
  mcq --limit 60 --batch 10     108 题里的选择题逐批盲复核
  planted                       植入错键题：看盲复核能不能全拦下
  real-answers                  他本人的 5 条作答过逐字校验（纯本地，不调模型）
  constructed --kp <id>         一个 KP 出 2 道带 rubric 的构答题，并批两道
  liveTest                      验收子集：1 个 KP + 植入题 + 真实作答
"""

private fun parseOptions(args: List<String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        if (key.startsWith("--")) {
            val name = key.removePrefix("--")
            val next = args.getOrNull(index + 1)
            if (next != null && !next.startsWith("--")) {
                out[name] = next
                index += 2
            } else {
                out[name] = "true"
                index++
            }
        } else {
            index++
        }
    }
    return out
}

/** 每 10 分钟一轮的活人保护：每个 live 请求前都要过这道闸。 */
class Preflight(private val bridge: BridgeClient, private val bridgeLog: Path) {

    data class Verdict(val allowed: Boolean, val reason: String?)

    fun check(needsClaude: Boolean): Verdict {
        if (needsClaude) {
            blackout()?.let { return Verdict(false, it) }
            dailyBudget(true)?.let { return Verdict(false, it) }
        }
        val health = bridge.health() ?: return Verdict(false, "bridge_not_running")
        val rate = health["rate_limit"] as? JsonObject
        val utilization = rate?.stringOrNull("utilization")?.toDoubleOrNull()
        val status = rate?.stringOrNull("status")
        val remaining = (health["daily"] as? JsonObject)?.stringOrNull("remaining")?.toIntOrNull()
        if (remaining != null && remaining <= 0) return Verdict(false, "daily_claude_cap:remaining=$remaining")
        if (utilization != null && utilization >= 0.5) return Verdict(false, "rate_limit_paused:${utilization}")
        if (status != null && status != "allowed" && status != "unknown") return Verdict(false, "rate_limit_paused:$status")
        busyReason()?.let { return Verdict(false, it) }
        return Verdict(true, null)
    }

    /** 期中前黑名单：2026-11-01 18:00 至 2026-11-02 15:00 不跑任何 Claude live。 */
    fun blackout(now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): String? {
        val start = LocalDateTime.of(2026, 11, 1, 18, 0)
        val end = LocalDateTime.of(2026, 11, 2, 15, 0)
        return if (now.isAfter(start) && now.isBefore(end)) "blackout_before_midterm" else null
    }

    /** Coach 的 Claude 物理调用每日硬上限（默认 60，与同一天其他卡合计，计数在桥的 state/daily.json）。 */
    fun dailyBudget(needsClaude: Boolean): String? {
        if (!needsClaude) return null
        val statePath = Path.of(
            System.getenv("COACH_BRIDGE_STATE") ?: "${System.getProperty("user.home")}/Coach/bridge/state/daily.json",
        )
        if (!statePath.exists()) return null
        val state = runCatching { JsonText.json.parseToJsonElement(statePath.readText()).jsonObject }.getOrNull() ?: return null
        val date = state["date"]?.jsonPrimitive?.content ?: return null
        val today = java.time.LocalDate.now().toString()
        val used = if (date == today) state["claude_calls"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 else 0
        val cap = System.getenv("BRIDGE_DAILY_CLAUDE_CAP")?.toIntOrNull() ?: 60
        return if (used >= cap) "daily_claude_cap:$used/$cap" else null
    }

    /** DeepTutor 自己正在用订阅（bridge.log 最后一行 10 分钟内有活动）→ 中止整批。 */
    fun busyReason(now: Instant = Instant.now()): String? {
        if (!bridgeLog.exists()) return null
        val lastLine = bridgeLog.readText().lineSequence().lastOrNull { it.isNotBlank() } ?: return null
        val timestamp = lastLine.substringBefore(' ').let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        } ?: return null
        val minutes = Duration.between(timestamp, now).toMinutes()
        return if (minutes < 10) "deep_tutor_busy:${minutes}m" else null
    }
}

class LiveHarness(
    private val corpus: Path,
    private val client: BridgeClient,
    private val options: Map<String, String>,
) {
    private val json = Json { prettyPrint = true }
    private val inputs = corpus.resolve("inputs")
    private val runs = corpus.resolve("runs")
    private val callsPath = runs.resolve("ai_calls.jsonl")
    private val preflight = Preflight(
        client,
        Path.of(System.getenv("COACH_DEEP_TUTOR_BRIDGE_LOG") ?: "${System.getProperty("user.home")}/DeepTutor/data/user/runtime/bridge.log"),
    )
    private val force = options["force"] == "true"
    private var claudeCalls = 0

    init {
        runs.createDirectories()
        runs.resolve("kp").createDirectories()
        runs.resolve("mcq").createDirectories()
    }

    // ---------- 子命令 ----------

    fun kps(effective: Map<String, String> = options) {
        val all = JsonText.json.parseToJsonElement(inputs.resolve("kps.json").readText()).jsonArray
        val limit = effective["limit"]?.toInt() ?: all.size
        val offset = effective["offset"]?.toInt() ?: 0
        val groupSize = effective["group"]?.toInt() ?: 4
        val count = effective["count"]?.toInt() ?: 3
        val slice = all.drop(offset).take(limit).map { it.jsonObject }
        var accepted = 0
        var attempted = 0
        var generated = 0
        for (group in slice.chunked(groupSize)) {
            val pending = group.filter {
                val id = it["id"]!!.jsonPrimitive.content
                force || !runs.resolve("kp").resolve("$id.json").exists()
            }
            if (pending.isEmpty()) {
                println("skip group ${group.map { it["id"]!!.jsonPrimitive.content }}（已有结果）")
                continue
            }
            val pool = QuestionPool(client)
            val calls = ArrayList<AiCall>()
            val entries = ArrayList<QuestionPool.ReviewEntry>()
            val batches = LinkedHashMap<String, QuestionPool.GenBatch>()
            var nextId = 0
            for (kp in pending) {
                val gate = preflight.check(needsClaude = false)
                if (!gate.allowed) abort(gate.reason!!)
                val id = kp["id"]!!.jsonPrimitive.content
                val objective = kp["objective"]!!.jsonPrimitive.content
                val excerpt = kp["excerpt"]!!.jsonPrimitive.content
                val batch = pool.generateFor(id, objective, excerpt, count = count)
                calls.add(batch.call)
                batches[id] = batch
                generated += batch.questions.size
                if (batch.questions.isNotEmpty()) {
                    entries.add(QuestionPool.ReviewEntry(id, excerpt, batch.questions, nextId))
                    nextId += batch.questions.size
                }
                println("gen $id: ${batch.questions.size} 道 ${batch.wallMs}ms served=${batch.call.servedBy}")
            }
            val reviewGate = preflight.check(needsClaude = true)
            if (!reviewGate.allowed) abort(reviewGate.reason!!)
            val review = pool.reviewGroup(entries)
            review.call?.let { calls.add(it) }
            record(calls)
            for (entry in entries) {
                val batch = batches[entry.kpId]!!
                val acceptedList = ArrayList<Mcq>()
                val rejectedList = ArrayList<QuestionPool.Rejected>()
                batch.questions.forEachIndexed { index, mcq ->
                    val verdict = review.verdicts[entry.firstId + index]
                    val reasons = pool.score(mcq, verdict, entry.excerpt)
                    attempted++
                    if (reasons.isEmpty()) {
                        acceptedList.add(mcq)
                        accepted++
                    } else {
                        rejectedList.add(QuestionPool.Rejected(mcq, reasons, verdict?.answer))
                    }
                }
                val payload = buildJsonObject {
                    put("kp", entry.kpId)
                    put("gen_ms", batch.wallMs)
                    put("review_ms", review.wallMs)
                    put("batch_size", batch.questions.size)
                    put("parsed", batch.parsed)
                    put("accepted", buildJsonArray { acceptedList.forEach { add(mcqJson(it)) } })
                    put("rejected", buildJsonArray {
                        rejectedList.forEach { rejected ->
                            add(
                                buildJsonObject {
                                    put("stem", rejected.mcq.stem)
                                    put("key", rejected.mcq.key)
                                    put("reviewer_answer", rejected.reviewerAnswer ?: "")
                                    put("reasons", buildJsonArray { rejected.reasons.forEach { add(it) } })
                                },
                            )
                        }
                    })
                    put("group_size", pending.size)
                    put("physical_calls", calls.sumOf { it.physicalCalls })
                    put("claude_calls", calls.count { it.requestedModel == BridgeClient.CLAUDE })
                    put("calls", buildJsonArray { calls.forEach { add(it.toJson()) } })
                }
                runs.resolve("kp").resolve("${entry.kpId}.json").writeText(json.encodeToString(JsonObject.serializer(), payload))
                println(
                    "kp ${entry.kpId}: accepted=${acceptedList.size} rejected=${rejectedList.size} " +
                        "gen=${batch.wallMs}ms review(共享)=${review.wallMs}ms",
                )
            }
        }
        println("summary kps generated=$generated attempted=$attempted accepted=$accepted claude_calls=$claudeCalls")
    }

    fun mcq() {
        val all = JsonText.json.parseToJsonElement(inputs.resolve("mcq60.json").readText()).jsonArray
        val limit = options["limit"]?.toInt() ?: all.size
        val offset = options["offset"]?.toInt() ?: 0
        val batchSize = options["batch"]?.toInt() ?: 10
        val slice = all.drop(offset).take(limit)
        var agreed = 0
        var total = 0
        slice.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            val target = runs.resolve("mcq").resolve("batch-${offset + batchIndex * batchSize}.json")
            if (target.exists() && !force) {
                println("skip mcq batch ${offset + batchIndex * batchSize}")
                return@forEachIndexed
            }
            val gate = preflight.check(needsClaude = true)
            if (!gate.allowed) abort(gate.reason!!)
            val questions = batch.map { element ->
                val obj = element.jsonObject
                Mcq(
                    stem = obj["stem"]!!.jsonPrimitive.content,
                    options = obj["options"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                    key = obj["key"]!!.jsonPrimitive.content,
                    explanation = "",
                    sourceQuote = "",
                )
            }
            val request = ChatRequest(
                task = "blind_review_existing",
                model = BridgeClient.CLAUDE,
                system = Prompts.REVIEW_SYSTEM,
                user = Prompts.blindReview("", questions),
                stream = false,
                background = true,
                maxTokens = 4000,
            )
            val response = client.chat(request)
            record(listOf(response.call))
            val verdicts = when (response) {
                is BridgeResult.Failure -> emptyList()
                is BridgeResult.Success -> runCatching { TaskParsing.verdicts(response.text) }.getOrDefault(emptyList())
            }
            val rows = questions.mapIndexed { index, question ->
                val verdict = verdicts.firstOrNull { it.id == index }
                val agrees = verdict != null && verdict.answer == question.key && verdict.defects.isEmpty()
                if (agrees) agreed++
                total++
                buildJsonObject {
                    put("stem", question.stem)
                    put("key", question.key)
                    put("reviewer_answer", verdict?.answer ?: "NONE")
                    put("defects", buildJsonArray { verdict?.defects?.forEach { add(it) } })
                    put("note", verdict?.note ?: "")
                    put("agrees", agrees)
                }
            }
            val payload = buildJsonObject {
                put("offset", offset + batchIndex * batchSize)
                put("size", questions.size)
                put("rows", buildJsonArray { rows.forEach { add(it) } })
                put("call", response.call.toJson())
            }
            target.writeText(json.encodeToString(JsonObject.serializer(), payload))
            println("mcq batch ${offset + batchIndex * batchSize}: ${rows.count { it["agrees"]!!.jsonPrimitive.content == "true" }}/${questions.size} 一致")
        }
        println("summary mcq agreed=$agreed total=$total claude_calls=$claudeCalls")
    }

    fun planted() {
        val payload = JsonText.json.parseToJsonElement(inputs.resolve("planted.json").readText()).jsonObject
        val excerpt = payload["excerpt"]!!.jsonPrimitive.content
        val items = payload["items"]!!.jsonArray
        val questions = items.map { element ->
            val obj = element.jsonObject
            Mcq(
                stem = obj["stem"]!!.jsonPrimitive.content,
                options = obj["options"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                key = obj["key"]!!.jsonPrimitive.content,
                explanation = "",
                sourceQuote = "",
            )
        }
        val gate = preflight.check(needsClaude = true)
        if (!gate.allowed) abort(gate.reason!!)
        val response = client.chat(
            ChatRequest(
                task = "blind_review_planted",
                model = BridgeClient.CLAUDE,
                system = Prompts.REVIEW_SYSTEM,
                user = Prompts.blindReview(excerpt, questions),
                stream = false,
                background = true,
                maxTokens = 4000,
            ),
        )
        record(listOf(response.call))
        val verdicts = when (response) {
            is BridgeResult.Failure -> emptyList()
            is BridgeResult.Success -> runCatching { TaskParsing.verdicts(response.text) }.getOrDefault(emptyList())
        }
        val rows = questions.mapIndexed { index, question ->
            val plantedKind = items[index].jsonObject["plant"]!!.jsonPrimitive.content
            val verdict = verdicts.firstOrNull { it.id == index }
            val flagged = verdict == null || verdict.answer != question.key || verdict.defects.isNotEmpty()
            buildJsonObject {
                put("plant", plantedKind)
                put("stem", question.stem.take(120))
                put("key", question.key)
                put("reviewer_answer", verdict?.answer ?: "NONE")
                put("defects", buildJsonArray { verdict?.defects?.forEach { add(it) } })
                put("flagged", flagged)
            }
        }
        val plantedRows = rows.filter { it["plant"]!!.jsonPrimitive.content != "clean" }
        val cleanRows = rows.filter { it["plant"]!!.jsonPrimitive.content == "clean" }
        val caught = plantedRows.count { it["flagged"]!!.jsonPrimitive.content == "true" }
        val falseAlarm = cleanRows.count { it["flagged"]!!.jsonPrimitive.content == "true" }
        val out = buildJsonObject {
            put("planted_caught", caught)
            put("planted_total", plantedRows.size)
            put("clean_flagged", falseAlarm)
            put("clean_total", cleanRows.size)
            put("rows", buildJsonArray { rows.forEach { add(it) } })
            put("call", response.call.toJson())
        }
        runs.resolve("planted.json").writeText(json.encodeToString(JsonObject.serializer(), out))
        println("planted caught=$caught/${plantedRows.size} clean_flagged=$falseAlarm/${cleanRows.size}")
    }

    fun realAnswers() {
        val answers = JsonText.json.parseToJsonElement(inputs.resolve("real_answers.json").readText()).jsonObject
        val claims = JsonText.json.parseToJsonElement(inputs.resolve("real_evidence.json").readText()).jsonArray
        var rawPassed = 0
        var compliant = 0
        val rows = buildJsonArray {
            claims.forEach { element ->
                val claim = element.jsonObject
                val answerId = claim["answer_id"]!!.jsonPrimitive.content
                val quote = claim["quote"]!!.jsonPrimitive.content
                val answer = answers[answerId]?.jsonPrimitive?.content ?: ""
                val raw = com.coach.domain.grade.VerbatimEvidence.verify(answer, quote)
                val rawOk = raw is com.coach.domain.grade.EvidenceCheck.Accepted
                if (rawOk) rawPassed++
                // 批改 prompt 要求「逐字照抄整个分句」：短引文按分句边界补齐后再验一次
                val clause = if (rawOk) quote else expandToClause(answer, quote)
                val check = if (clause == null) raw else com.coach.domain.grade.VerbatimEvidence.verify(answer, clause)
                val ok = check is com.coach.domain.grade.EvidenceCheck.Accepted
                if (ok) compliant++
                add(
                    buildJsonObject {
                        put("answer_id", answerId)
                        put("quote", quote)
                        put("raw_accepted", rawOk)
                        put("raw_reason", if (raw is com.coach.domain.grade.EvidenceCheck.Rejected) raw.reason.name else "OK")
                        if (clause != null && clause != quote) put("clause_quote", clause)
                        put("compliant_accepted", ok)
                        if (check is com.coach.domain.grade.EvidenceCheck.Accepted) {
                            put("span_start", check.span.start)
                            put("span_end", check.span.end)
                        }
                    },
                )
            }
        }
        val perAnswer = answers.keys.associateWith { id ->
            val idRows = rows.filter { it.jsonObject["answer_id"]!!.jsonPrimitive.content == id }
            buildJsonObject {
                put("claims", idRows.size)
                put("raw_accepted", idRows.count { it.jsonObject["raw_accepted"]!!.jsonPrimitive.content == "true" })
                put("compliant_accepted", idRows.count { it.jsonObject["compliant_accepted"]!!.jsonPrimitive.content == "true" })
            }
        }
        val out = buildJsonObject {
            put("claims_total", rows.size)
            put("raw_accepted", rawPassed)
            put("compliant_accepted", compliant)
            put("answers_total", answers.size)
            put("answers_with_accepted_evidence", perAnswer.values.count { it["compliant_accepted"]!!.jsonPrimitive.content.toInt() > 0 })
            put("per_answer", JsonObject(perAnswer))
            put("rows", rows)
        }
        runs.resolve("real-answers.json").writeText(json.encodeToString(JsonObject.serializer(), out))
        println("real-answers raw=$rawPassed/${rows.size} compliant=$compliant/${rows.size} answers=${perAnswer.size}")
    }

    private fun expandToClause(answer: String, quote: String): String? {
        val index = answer.indexOf(quote)
        if (index < 0) return null
        var left = index
        while (left > 0 && !isSeparator(answer[left - 1])) left--
        var right = index + quote.length
        while (right < answer.length && !isSeparator(answer[right])) right++
        return answer.substring(left, right)
    }

    private fun isSeparator(ch: Char): Boolean =
        ch.isWhitespace() || ch in "，。；;、！？!?：:,（）()【】「」『』“”\"'‘’…"

    fun constructed() {
        val kpId = options["kp"] ?: run {
            println("需要 --kp <id>")
            return
        }
        val all = JsonText.json.parseToJsonElement(inputs.resolve("kps.json").readText()).jsonArray
        val kp = all.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == kpId }
        val excerpt = kp["excerpt"]!!.jsonPrimitive.content
        val objective = kp["objective"]!!.jsonPrimitive.content
        val target = runs.resolve("constructed-$kpId.json")
        if (target.exists() && !force) {
            println("skip constructed $kpId（已有结果）")
            return
        }
        val calls = ArrayList<AiCall>()
        val started = System.nanoTime()

        val gate = preflight.check(needsClaude = true)
        if (!gate.allowed) abort(gate.reason!!)
        val generated = client.chat(
            ChatRequest(
                task = "gen_constructed",
                model = BridgeClient.CODEX,
                system = Prompts.GEN_SYSTEM,
                user = Prompts.genConstructed(objective, excerpt, 2),
                stream = false,
                background = true,
                maxTokens = 4000,
            ),
        )
        calls.add(generated.call)
        val questions = when (generated) {
            is BridgeResult.Failure -> emptyList<ConstructedQuestion>()
            is BridgeResult.Success -> runCatching { TaskParsing.constructed(generated.text) }.getOrDefault(emptyList())
        }.filter { it.rubric.isNotEmpty() }
        if (questions.isEmpty()) {
            record(calls)
            println("constructed gen failed: ${(generated as? BridgeResult.Failure)?.reason}")
            return
        }
        val question = questions.first().copy(kpId = kpId)
        val fingerprint = GradeGate.rubricFingerprint(question.rubric)

        val reviewGate = preflight.check(needsClaude = true)
        if (!reviewGate.allowed) abort(reviewGate.reason!!)
        val rubricReview = client.chat(
            ChatRequest(
                task = "review_constructed_key",
                model = BridgeClient.CLAUDE,
                system = Prompts.CROSS_REVIEW_SYSTEM,
                user = Prompts.reviewConstructedKey(question, excerpt),
                stream = false,
                background = true,
                maxTokens = 1500,
            ),
        )
        calls.add(rubricReview.call)
        val rubricVerdict = when (rubricReview) {
            is BridgeResult.Failure -> null
            is BridgeResult.Success -> runCatching { TaskParsing.crossReview(rubricReview.text) }.getOrNull()
        }

        val pipeline = GradePipeline(client)
        val goodGate = preflight.check(needsClaude = true)
        if (!goodGate.allowed) abort(goodGate.reason!!)
        val good = pipeline.gradeConstructed(question, question.referenceAnswer, fingerprint)
        calls.addAll(good.calls)

        val partialAnswer = question.referenceAnswer.split("。", ".").firstOrNull()?.take(60) ?: question.referenceAnswer
        val partialGate = preflight.check(needsClaude = true)
        if (!partialGate.allowed) abort(partialGate.reason!!)
        val partial = pipeline.gradeConstructed(question, partialAnswer, fingerprint)
        calls.addAll(partial.calls)

        record(calls)
        val out = buildJsonObject {
            put("kp", kpId)
            put("rubric_fingerprint", fingerprint)
            put("wall_ms", (System.nanoTime() - started) / 1_000_000)
            put("question", buildJsonObject {
                put("stem", question.stem)
                put("reference_answer", question.referenceAnswer)
                put("rubric", buildJsonArray {
                    question.rubric.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("text", item.text)
                                put("points", item.points)
                                put("basis", item.basis)
                            },
                        )
                    }
                })
            })
            put("rubric_review", buildJsonObject {
                put("verdict", rubricVerdict?.verdict ?: "MISSING")
                put("note", rubricVerdict?.note ?: "")
            })
            put("grade_reference_answer", outcomeJson(good))
            put("grade_partial_answer", outcomeJson(partial))
            put("generated_count", questions.size)
            put("calls", buildJsonArray { calls.forEach { add(it.toJson()) } })
        }
        target.writeText(json.encodeToString(JsonObject.serializer(), out))
        println("constructed $kpId: rubric=${rubricVerdict?.verdict} good_written=${good.written} partial_written=${partial.written}")
    }

    /** 验收者复跑的子集：1 个 KP 的出题 + 盲复核、植入错键题、他的真实作答。 */
    fun liveTest() {
        kps(options + ("limit" to "1"))
        planted()
        realAnswers()
    }

    // ---------- 共用 ----------

    private fun outcomeJson(outcome: GradePipeline.Outcome): JsonObject = buildJsonObject {
        put("written", outcome.written)
        put("awarded", outcome.awarded ?: -1.0)
        put("defects", buildJsonArray { outcome.defects.forEach { add(it) } })
        put("review_verdict", outcome.crossReview?.verdict ?: "MISSING")
        put("evidence", buildJsonArray { outcome.evidence.forEach { add(it) } })
        put("physical_calls", outcome.calls.sumOf { it.physicalCalls })
    }

    private fun mcqJson(mcq: Mcq): JsonObject = buildJsonObject {
        put("stem", mcq.stem)
        put("key", mcq.key)
        put("options", buildJsonObject { mcq.options.forEach { (k, v) -> put(k, v) } })
        put("source_quote", mcq.sourceQuote)
    }

    private fun record(calls: List<AiCall>) {
        calls.forEach { call ->
            if (call.requestedModel == BridgeClient.CLAUDE) claudeCalls += 1
            callsPath.appendText(JsonObject.serializer().let { JsonText.json.encodeToString(it, call.toJson()) } + "\n")
        }
    }

    private fun abort(reason: String): Nothing {
        println("ABORT: $reason")
        System.err.println("ABORT: $reason")
        kotlin.system.exitProcess(2)
    }
}
