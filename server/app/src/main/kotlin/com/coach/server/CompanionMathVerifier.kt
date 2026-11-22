package com.coach.server

import com.coach.domain.LedgerJson
import com.coach.store.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Structured symbolic / numeric math check executed by Python SymPy (`companion_math_verify.py`).
 * Kinds:
 *  - `identity`: verifies `simplify(lhs - rhs) == 0`
 *  - `eval`: substitutes `variables` into `expression`, verifies exact rational / numeric match with `expected`
 *  - `ratio`: exact rational equality between `lhs` (`claimed`) and `rhs` (`expected`)
 *  - `solve`: solves `lhs = rhs` for `variable`, verifies `expected` is a root
 *  - `inequality`: solves `inequality` for `variable`, verifies boundary `expectedThreshold` and `expectedRelation`
 */
data class MathCheckSpec(
    val id: String = "",
    val kind: String = "eval",
    val lhs: String = "",
    val rhs: String = "",
    val expression: String = "",
    val variables: Map<String, String> = emptyMap(),
    val expected: String = "",
    val inequality: String = "",
    val variable: String = "",
    val expectedRelation: String = "",
    val expectedThreshold: String = "",
    val tolerance: String = "0.015",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        if (lhs.isNotBlank()) put("lhs", lhs)
        if (rhs.isNotBlank()) put("rhs", rhs)
        if (expression.isNotBlank()) put("expression", expression)
        if (variables.isNotEmpty()) {
            put("variables", buildJsonObject {
                for ((k, v) in variables) put(k, v)
            })
        }
        if (expected.isNotBlank()) put("expected", expected)
        if (inequality.isNotBlank()) put("inequality", inequality)
        if (variable.isNotBlank()) put("variable", variable)
        if (expectedRelation.isNotBlank()) put("expectedRelation", expectedRelation)
        if (expectedThreshold.isNotBlank()) put("expectedThreshold", expectedThreshold)
        put("tolerance", tolerance)
    }

    companion object {
        fun fromJson(obj: JsonObject, defaultId: String = ""): MathCheckSpec? {
            var kind = obj.str("kind").lowercase().ifBlank {
                when {
                    obj.str("inequality").isNotBlank() || obj.str("op").isNotBlank() || obj.str("relation").isNotBlank() -> "inequality"
                    obj.str("numerator").isNotBlank() && obj.str("denominator").isNotBlank() -> "ratio"
                    obj.str("equation").isNotBlank() -> "solve"
                    obj.str("expression").isNotBlank() && obj.str("expected").isNotBlank() -> "eval"
                    obj.str("lhs").isNotBlank() && obj.str("rhs").isNotBlank() -> "identity"
                    else -> ""
                }
            }
            if (kind.isBlank()) return null
            val vars = (obj["variables"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                val raw = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (k.isBlank() || raw.isBlank()) null else k.trim() to raw
            }?.toMap().orEmpty()

            var lhs = obj.str("lhs").ifBlank { obj.str("claimed") }
            var rhs = obj.str("rhs")
            val expression = obj.str("expression")
            var expected = obj.str("expected")
            var inequality = obj.str("inequality")
            val variable = obj.str("variable")
            var expectedRelation = obj.str("expectedRelation")
            var expectedThreshold = obj.str("expectedThreshold")

            when (kind) {
                "ratio" -> {
                    val num = obj.str("numerator")
                    val den = obj.str("denominator")
                    if (lhs.isBlank() && num.isNotBlank() && den.isNotBlank()) {
                        lhs = "($num)/($den)"
                    } else if (lhs.isBlank() && expression.isNotBlank()) {
                        lhs = expression
                    }
                    if (rhs.isBlank() && expected.isNotBlank()) {
                        rhs = expected
                    }
                }
                "inequality" -> {
                    if (inequality.isBlank()) {
                        val leftPart = lhs.ifBlank { expression }
                        val opPart = obj.str("op").ifBlank { obj.str("relation") }
                        val rightPart = rhs.ifBlank { expected }
                        if (leftPart.isNotBlank() && opPart.isNotBlank() && rightPart.isNotBlank()) {
                            inequality = "$leftPart $opPart $rightPart"
                        } else if (leftPart.isNotBlank() && Regex("""<=|>=|<|>""").containsMatchIn(opPart)) {
                            inequality = "$leftPart $opPart"
                        }
                    }
                }
                "solve" -> {
                    val eq = obj.str("equation")
                    if (eq.isNotBlank()) {
                        if (Regex("""<=|>=|<|>""").containsMatchIn(eq)) {
                            kind = "inequality"
                            inequality = eq
                            val expMatch = Regex("""(<=|>=|<|>)\s*(.+)$""").find(expected)
                            if (expMatch != null && expectedRelation.isBlank()) {
                                expectedRelation = expMatch.groupValues[1].trim()
                                expectedThreshold = expMatch.groupValues[2].trim()
                            }
                        } else if (lhs.isBlank()) {
                            val idx = eq.indexOf('=')
                            if (idx >= 0) {
                                lhs = eq.substring(0, idx).trim()
                                if (rhs.isBlank()) rhs = eq.substring(idx + 1).trim()
                            } else {
                                lhs = eq
                                if (rhs.isBlank()) rhs = "0"
                            }
                        }
                    }
                }
            }

            return MathCheckSpec(
                id = obj.str("id").ifBlank { defaultId },
                kind = kind,
                lhs = lhs,
                rhs = rhs,
                expression = expression,
                variables = vars,
                expected = expected,
                inequality = inequality,
                variable = variable,
                expectedRelation = expectedRelation,
                expectedThreshold = expectedThreshold,
                tolerance = obj.str("tolerance").ifBlank { "0.015" },
            )
        }

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    }
}

data class SympyCheckResult(
    val id: String,
    val kind: String,
    val passed: Boolean,
    val exactValue: String = "",
    val decimalValue: String = "",
    val expectedExact: String = "",
    val reason: String = "",
)

data class SympyBatchResponse(
    val engine: String,
    val sympyVersion: String,
    val results: List<SympyCheckResult>,
)

data class MathVerificationStats(
    val checkedItems: Int = 0,
    val initialFailedItems: Int = 0,
    val rejectedItems: Int = 0,
    val regeneratedPassedItems: Int = 0,
    val finalDroppedItems: Int = 0,
    val sympyChecksRun: Int = 0,
    val sympyChecksPassed: Int = 0,
    val engine: String = "sympy",
    val deduplicatedExercises: Int = 0,
    val placeholderLeaksBlocked: Int = 0,
    val failureSummaries: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("checkedItems", checkedItems)
        put("initialFailedItems", initialFailedItems)
        put("rejectedItems", rejectedItems)
        put("regeneratedPassedItems", regeneratedPassedItems)
        put("finalDroppedItems", finalDroppedItems)
        put("sympyChecksRun", sympyChecksRun)
        put("sympyChecksPassed", sympyChecksPassed)
        put("engine", engine)
        put("deduplicatedExercises", deduplicatedExercises)
        put("placeholderLeaksBlocked", placeholderLeaksBlocked)
        put("failureSummaries", buildJsonArray { failureSummaries.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        fun fromJson(obj: JsonObject?): MathVerificationStats {
            if (obj == null) return MathVerificationStats()
            return MathVerificationStats(
                checkedItems = obj["checkedItems"]?.jsonPrimitive?.intOrNull ?: 0,
                initialFailedItems = obj["initialFailedItems"]?.jsonPrimitive?.intOrNull ?: 0,
                rejectedItems = obj["rejectedItems"]?.jsonPrimitive?.intOrNull ?: 0,
                regeneratedPassedItems = obj["regeneratedPassedItems"]?.jsonPrimitive?.intOrNull ?: 0,
                finalDroppedItems = obj["finalDroppedItems"]?.jsonPrimitive?.intOrNull ?: 0,
                sympyChecksRun = obj["sympyChecksRun"]?.jsonPrimitive?.intOrNull ?: 0,
                sympyChecksPassed = obj["sympyChecksPassed"]?.jsonPrimitive?.intOrNull ?: 0,
                engine = obj["engine"]?.jsonPrimitive?.contentOrNull ?: "sympy",
                deduplicatedExercises = obj["deduplicatedExercises"]?.jsonPrimitive?.intOrNull ?: 0,
                placeholderLeaksBlocked = obj["placeholderLeaksBlocked"]?.jsonPrimitive?.intOrNull ?: 0,
                failureSummaries = (obj["failureSummaries"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
    }
}

/** Detects leaked internal placeholders (e.g. `〔依原文公式〕`, `[依原文公式]`, `（简述）`, `[TODO]`). */
object PlaceholderLeakGuard {
    private val PLACEHOLDER_PATTERNS = listOf(
        Regex("""[\[〔【\(（]\s*依原文公式\s*[\]〕】\)）]"""),
        Regex("""[\[〔【\(（]\s*简述\s*[\]〕】\)）]"""),
        Regex("""\[已改写[^\]]*\]"""),
        Regex("""(?i)\[(?:todo|tbd|placeholder|formula|insert\s+formula|待补|待填|原文公式)\]"""),
        Regex("""〔(?:待补|待填|原文公式|公式略)〕"""),
    )

    fun findLeaks(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val hits = mutableListOf<String>()
        for (re in PLACEHOLDER_PATTERNS) {
            re.findAll(text).forEach { hits += it.value }
        }
        return hits.distinct()
    }

    fun hasLeak(text: String): Boolean = findLeaks(text).isNotEmpty()

    fun stripLeaks(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for (re in PLACEHOLDER_PATTERNS) {
            out = re.replace(out, "")
        }
        return out.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }
}

/**
 * Deduplicates cross-page/cross-chunk exercise hints (e.g. Exercise 10 spanning PDF pp. 188-189)
 * and duplicate derivations/practice questions across split chunks.
 */
object CompanionItemDeduplicator {
    private val LABEL_NUM_RE = Regex(
        """(?i)(?:习题|练习题?|问题|题|exercise|ex\.?|problem|prob\.?|question|q\.?)\s*#?\s*(\d+)\s*(?:\(([a-z])\)|([a-z]))?""",
    )
    private val BARE_NUM_RE = Regex("""^(\d+)\s*(?:\(([a-z])\)|([a-z]))?[.)]?$""", RegexOption.IGNORE_CASE)

    fun canonicalExerciseKey(label: String): String {
        val norm = Normalizer.normalize(label, Normalizer.Form.NFKC).trim()
        val m = LABEL_NUM_RE.find(norm) ?: BARE_NUM_RE.find(norm)
        if (m != null) {
            val num = m.groupValues[1]
            val sub = (m.groupValues[2].ifBlank { m.groupValues[3] }).lowercase()
            return if (sub.isNotBlank()) "$num$sub" else num
        }
        return norm.lowercase().replace(Regex("""\s+"""), "")
    }

    /**
     * Merges cross-page duplicate exercise hints sharing the same canonical problem number.
     * Returns `(deduplicatedHints, removedCount)`.
     */
    fun deduplicateExerciseHints(hints: List<ExerciseProgressiveHint>): Pair<List<ExerciseProgressiveHint>, Int> {
        if (hints.size <= 1) return hints to 0
        val orderedKeys = mutableListOf<String>()
        val byKey = LinkedHashMap<String, ExerciseProgressiveHint>()
        var removed = 0
        for (hint in hints) {
            val key = canonicalExerciseKey(hint.problemLabel)
            val existing = byKey[key]
            if (existing == null) {
                orderedKeys += key
                byKey[key] = hint
            } else {
                removed++
                byKey[key] = mergeExerciseHints(existing, hint)
            }
        }
        return orderedKeys.map { byKey.getValue(it) } to removed
    }

    private fun mergeExerciseHints(a: ExerciseProgressiveHint, b: ExerciseProgressiveHint): ExerciseProgressiveHint {
        val anchor = pickEarlierAnchor(a.anchor, b.anchor)
        return a.copy(
            problemLabel = if (a.problemLabel.length <= b.problemLabel.length) a.problemLabel else b.problemLabel,
            questionSummary = pickRicherText(a.questionSummary, b.questionSummary),
            hint1Direction = pickRicherText(a.hint1Direction, b.hint1Direction),
            hint2Method = pickRicherText(a.hint2Method, b.hint2Method),
            hint3Check = pickRicherText(a.hint3Check, b.hint3Check),
            anchor = anchor,
        )
    }

    private fun pickEarlierAnchor(a: VerifiedSourceAnchor?, b: VerifiedSourceAnchor?): VerifiedSourceAnchor? {
        if (a == null) return b
        if (b == null) return a
        return when {
            a.pdfPage < b.pdfPage -> a
            b.pdfPage < a.pdfPage -> b
            a.startUtf16 <= b.startUtf16 -> a
            else -> b
        }
    }

    private fun pickRicherText(first: String, second: String): String {
        val c1 = first.trim()
        val c2 = second.trim()
        if (c1.isBlank()) return c2
        if (c2.isBlank()) return c1
        val leak1 = PlaceholderLeakGuard.hasLeak(c1)
        val leak2 = PlaceholderLeakGuard.hasLeak(c2)
        if (leak1 && !leak2) return c2
        if (leak2 && !leak1) return c1
        if (c1 == c2) return c1
        return if (c2.length > c1.length + 12) c2 else c1
    }

    fun deduplicateDerivations(items: List<DerivationStepTemplate>): List<DerivationStepTemplate> {
        val seen = HashSet<String>()
        return items.filter { d ->
            val key = "${normKey(d.title)}@${d.anchor?.pdfPage ?: 0}"
            seen.add(key)
        }
    }

    fun deduplicateQuizzes(items: List<CompanionQuizItem>): List<CompanionQuizItem> {
        val seen = HashSet<String>()
        return items.filter { q ->
            seen.add(normKey(q.stem))
        }
    }

    private fun normKey(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("""[\s\p{Punct}，。、；：？！“”‘’（）()\[\]]+"""), "")
}

/** Deterministic cues extracted from raw PDF source blocks to catch stacked-fraction & multiplier misreads. */
data class SourceMultiplierCue(
    val pdfPage: Int,
    val phrase: String,
    val exactFactor: Rational,
    val factorDecimal: String,
)

data class SourcePageMathCues(
    val multipliers: List<SourceMultiplierCue>,
    val stackedFractionWarnings: List<String>,
) {
    fun formatForPrompt(): String {
        if (multipliers.isEmpty() && stackedFractionWarnings.isEmpty()) return ""
        return buildString {
            append("\n[PDF Math & Fraction Extraction Cues — verify against raw blocks carefully]:\n")
            for (m in multipliers) {
                append("- p.").append(m.pdfPage)
                    .append(": prose says \"").append(m.phrase)
                    .append("\" => exact multiplicative factor is ").append(m.exactFactor)
                    .append(" (= ").append(m.factorDecimal)
                    .append("x; NOTE: N% more than baseline 1 means (1 + N/100)x, NOT (N/100)x and NOT integer numerator alone!)\n")
            }
            for (w in stackedFractionWarnings) {
                append("- ").append(w).append('\n')
            }
        }
    }
}

object SourcePageMathExtractor {
    private val PCT_MORE_RE = Regex("""(?i)(\d+(?:\.\d+)?)\s*%\s+(?:more|higher|greater)\s+than""")
    private val PCT_LESS_RE = Regex("""(?i)(\d+(?:\.\d+)?)\s*%\s+(?:less|lower|smaller)\s+than""")
    private val STACKED_FRAC_SUM_RE = Regex(
        """(?m)\b([A-Za-z][A-Za-z0-9_]{0,4})\s*=\s*(\d{1,2})\s*(?:\r?\n[^\n]{0,40}){0,3}?\b(?:j\s*=\s*(\d{1,2})\b|\b(\d{1,2})\s*\r?\n\s*1\b)""",
    )

    fun extract(blocks: List<SectionBlockInput>): SourcePageMathCues {
        val byPage = blocks.groupBy { it.pdfPage }
        val multipliers = mutableListOf<SourceMultiplierCue>()
        val warnings = mutableListOf<String>()

        for ((page, pageBlocks) in byPage.entries.sortedBy { it.key }) {
            val joined = pageBlocks.joinToString("\n") { it.text }
            for (m in PCT_MORE_RE.findAll(joined)) {
                val pctStr = m.groupValues[1]
                val pctRat = runCatching { Rational.parseDecimal(pctStr) }.getOrNull() ?: continue
                val factor = Rational.ONE + (pctRat / Rational.of(100, 1))
                val dec = ExactRationalEvaluator.formatRounded(factor, 4).trimEnd('0').trimEnd('.')
                val cue = SourceMultiplierCue(page, m.value.trim(), factor, dec)
                if (multipliers.none { it.pdfPage == page && it.exactFactor == factor }) {
                    multipliers += cue
                }
            }
            for (m in PCT_LESS_RE.findAll(joined)) {
                val pctStr = m.groupValues[1]
                val pctRat = runCatching { Rational.parseDecimal(pctStr) }.getOrNull() ?: continue
                if (pctRat < Rational.of(100, 1)) {
                    val factor = Rational.ONE - (pctRat / Rational.of(100, 1))
                    val dec = ExactRationalEvaluator.formatRounded(factor, 4).trimEnd('0').trimEnd('.')
                    val cue = SourceMultiplierCue(page, m.value.trim(), factor, dec)
                    if (multipliers.none { it.pdfPage == page && it.exactFactor == factor }) {
                        multipliers += cue
                    }
                }
            }
            for (m in STACKED_FRAC_SUM_RE.findAll(joined)) {
                val lhsVar = m.groupValues[1]
                val num = m.groupValues[2].toLongOrNull() ?: continue
                val den = (m.groupValues[3].ifBlank { m.groupValues[4] }).toLongOrNull() ?: continue
                if (num in 1..20 && den in 2..20 && num != den) {
                    val frac = Rational.of(num, den)
                    val msg = "p.$page: detected likely PDF stacked-fraction split near `$lhsVar = $num ... $den` (possible fraction $frac = ${
                        ExactRationalEvaluator.formatRounded(frac, 4).trimEnd('0').trimEnd('.')
                    }; cross-check with surrounding prose before writing coefficient!)"
                    if (msg !in warnings) warnings += msg
                }
            }
        }
        return SourcePageMathCues(multipliers = multipliers, stackedFractionWarnings = warnings)
    }
}

/** Subprocess interface to `companion_math_verify.py` (Python SymPy), with clean Rational fallback. */
interface SymbolicMathEngine {
    fun runBatch(checks: List<MathCheckSpec>): SympyBatchResponse
}

class SympySubprocessEngine(
    private val commandOverride: List<String>? = null,
    private val timeoutSeconds: Long = 25L,
) : SymbolicMathEngine {

    override fun runBatch(checks: List<MathCheckSpec>): SympyBatchResponse {
        if (checks.isEmpty()) {
            return SympyBatchResponse(engine = "sympy", sympyVersion = "", results = emptyList())
        }
        val cmd = commandOverride ?: resolveSympyCommand()
        if (cmd != null) {
            val scriptPath = ensureScriptExtracted()
            val payload = buildJsonObject {
                put("checks", buildJsonArray { checks.forEach { add(it.toJson()) } })
            }.toString()
            val fullCmd = cmd + scriptPath.toString()
            val result = runCatching { executeProcess(fullCmd, payload) }.getOrNull()
            if (result != null) return result
        }
        return fallbackEvaluate(checks)
    }

    private fun executeProcess(command: List<String>, stdinJson: String): SympyBatchResponse {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdinJson) }
        val stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("sympy_timeout_${timeoutSeconds}s")
        }
        if (proc.exitValue() != 0) {
            val err = proc.errorStream.bufferedReader(Charsets.UTF_8).readText().take(300)
            throw IllegalStateException("sympy_exit_${proc.exitValue()}:$err")
        }
        val obj = LedgerJson.parseToJsonElement(stdout.trim()).jsonObject
        val engine = obj["engine"]?.jsonPrimitive?.contentOrNull ?: "sympy"
        val version = obj["sympyVersion"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val results = (obj["results"] as? JsonArray).orEmpty().mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            SympyCheckResult(
                id = r["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                kind = r["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                passed = r["passed"]?.jsonPrimitive?.booleanOrNull == true,
                exactValue = r["exactValue"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                decimalValue = r["decimalValue"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                expectedExact = r["expectedExact"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reason = r["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return SympyBatchResponse(engine = engine, sympyVersion = version, results = results)
    }

    private fun fallbackEvaluate(checks: List<MathCheckSpec>): SympyBatchResponse {
        val results = checks.map { c ->
            when (c.kind.lowercase()) {
                "eval" -> {
                    var expr = c.expression
                    for ((k, v) in c.variables) {
                        expr = expr.replace(Regex("""\b${Regex.escape(k)}\b"""), "($v)")
                    }
                    val tol = runCatching { BigDecimal(c.tolerance) }.getOrDefault(BigDecimal("0.015"))
                    val res = ExactRationalEvaluator.verify(expr, c.expected, decimalPlaces = 4, tolerance = tol)
                    SympyCheckResult(
                        id = c.id,
                        kind = c.kind,
                        passed = res.validExpression && res.matchesClaimed,
                        exactValue = res.exactRational,
                        decimalValue = res.verifiedValue,
                        reason = if (res.validExpression && res.matchesClaimed) "rational_fallback_ok" else (res.errorMessage ?: "rational_mismatch"),
                    )
                }
                "ratio" -> {
                    val a = runCatching { ExactRationalEvaluator.evaluate(c.lhs) }.getOrNull()
                    val b = runCatching { ExactRationalEvaluator.evaluate(c.expected.ifBlank { c.rhs }) }.getOrNull()
                    val ok = a != null && b != null && a == b
                    SympyCheckResult(
                        id = c.id,
                        kind = c.kind,
                        passed = ok,
                        exactValue = a?.toString().orEmpty(),
                        expectedExact = b?.toString().orEmpty(),
                        reason = if (ok) "rational_ratio_ok" else "rational_ratio_mismatch",
                    )
                }
                else -> SympyCheckResult(
                    id = c.id,
                    kind = c.kind,
                    passed = false,
                    reason = "sympy_unavailable_for_${c.kind}",
                )
            }
        }
        return SympyBatchResponse(engine = "rational_fallback", sympyVersion = "none", results = results)
    }

    companion object {
        @Volatile
        private var cachedScriptPath: Path? = null

        @Volatile
        private var cachedCommand: List<String>? = null

        @Volatile
        private var commandProbed: Boolean = false

        private fun ensureScriptExtracted(): Path {
            cachedScriptPath?.takeIf { Files.isRegularFile(it) }?.let { return it }
            synchronized(this) {
                cachedScriptPath?.takeIf { Files.isRegularFile(it) }?.let { return it }
                val stream = CompanionMathVerifier::class.java.getResourceAsStream("/companion_math_verify.py")
                    ?: error("Missing bundled resource /companion_math_verify.py")
                val tmpDir = Files.createTempDirectory("coach-sympy-")
                val script = tmpDir.resolve("companion_math_verify.py")
                stream.use { Files.copy(it, script) }
                script.toFile().deleteOnExit()
                tmpDir.toFile().deleteOnExit()
                cachedScriptPath = script
                return script
            }
        }

        internal fun resolveSympyCommand(): List<String>? {
            if (commandProbed) return cachedCommand
            synchronized(this) {
                if (commandProbed) return cachedCommand
                val candidates = buildList {
                    System.getenv("COACH_SYMPY_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        add(listOf(it))
                    }
                    add(listOf("python3"))
                    add(listOf("/opt/homebrew/bin/python3"))
                    val uvCandidates = listOf(
                        "${System.getProperty("user.home")}/.local/bin/uv",
                        "/opt/homebrew/bin/uv",
                        "/usr/local/bin/uv",
                        "uv",
                    )
                    for (uv in uvCandidates) {
                        if (uv == "uv" || File(uv).canExecute()) {
                            add(listOf(uv, "run", "--offline", "--with", "sympy", "python3"))
                            add(listOf(uv, "run", "--with", "sympy", "python3"))
                        }
                    }
                }
                for (cand in candidates) {
                    if (probeSympy(cand)) {
                        cachedCommand = cand
                        commandProbed = true
                        return cand
                    }
                }
                commandProbed = true
                return null
            }
        }

        private fun probeSympy(cmd: List<String>): Boolean = runCatching {
            val pb = ProcessBuilder(cmd + listOf("-c", "import sympy; print(sympy.__version__)"))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            val ok = proc.waitFor(15, TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0 && out.isNotBlank()
            }
        }.getOrDefault(false)
    }
}

/** Deterministic math & source-consistency checks run before and alongside SymPy/LLM audits. */
object DeterministicMathChecks {
    private val MATH_QUIZ_CUE_RE = Regex(
        """(?i)(?:\d|/|%|=|≤|≥|<|>|Δ|∑|计算|求解?|等于|多少|系数|临界|阈值|区间|期望|效用|保费|利润|亏损|弹性|均值|占比|比例|概率|边际|斜率|截距|下限|上限)""",
    )
    private val INLINE_NUM_EQ_RE = Regex(
        """([($+\-\s]*\d+(?:,\d{3})*(?:\.\d+)?[%)\s]*(?:[+\-*/×÷·^][($+\-\s]*\d+(?:,\d{3})*(?:\.\d+)?[%)\s]*)+)\s*=\s*([+-]?\$?\d+(?:,\d{3})*(?:\.\d+)?%?(?:/\d+)?)""",
    )
    private val ZH_MUL_EQ_RE = Regex(
        """(\d+(?:\.\d+)?(?:/\d+)?)\s*乘以\s*(\d+(?:\.\d+)?(?:/\d+)?)\s*[，, ]*(?:等于|得|为|结果为)\s*(\d+(?:\.\d+)?(?:/\d+)?)""",
    )
    private val ZH_SUB_EQ_RE = Regex(
        """(\d+(?:\.\d+)?)\s*减去?\s*(\d+(?:\.\d+)?)\s*[，, ]*(?:等于|得|为|结果为)\s*(-?\d+(?:\.\d+)?)""",
    )
    private val ISO_DATE_RE = Regex("""^\s*\d{4}-\d{1,2}-\d{1,2}\s*$""")

    // Detects Chinese multiplier phrases like "1.5倍", "三倍", "3倍", "系数为 3"
    private val ZH_NUM_TIMES_RE = Regex("""(\d+(?:\.\d+)?)\s*倍""")
    private val ZH_COEFF_RE = Regex("""(?:加权)?系数(?:为|是|等于)\s*(\d+(?:\.\d+)?(?:/\d+)?)""")
    private val FRAC_5_2_RE = Regex("""(?:\(?\s*5\s*/\s*2\s*\)?|2\.5\s*[·*×]?\s*(?:E\[|X|x))""")
    private val FRAC_3_2_RE = Regex("""(?:\(?\s*3\s*/\s*2\s*\)?|1\.5\s*[·*×]?\s*(?:E\[|X|x))""")

    // Extracts explicit numeric/fractional coefficients multiplying quality/expectation/valuation expressions in formulas
    private val FORMULA_VAR_COEFF_RE = Regex(
        """(?<![A-Za-z0-9_/.^])(\(?\s*\d+(?:\.\d+)?\s*/\s*\d+(?:\.\d+)?\s*\)?|\d+(?:\.\d+)?)(?!\s*/\s*\d|\s*\^)\s*(?:[*·×]\s*|\s*)(?:(?:∑|Σ|sum)\s*)?(?:(E\s*\[\s*[A-Za-z][A-Za-z0-9_]*\s*\]|E_[A-Za-z][A-Za-z0-9_]*|[XxQq](?:_[A-Za-z0-9]+)?(?![A-Za-z0-9_]))(\s*/\s*(\d+))?|(\(\s*[Pp](?:\s*[+-]\s*[A-Za-z0-9]+)?\s*/\s*\d+\s*\)))""",
    )
    private val UTILITY_LHS_COEFF_RE = Regex(
        """(?:U_?B|ΔU_?B|V_?B|ΔV_?B|E\s*\[\s*U_?B\s*\]|E\s*\[\s*V_?B\s*\]|(?:^|[\s,;(（])(?:U|P_U))\s*=\s*(?:[+\-]?\s*)(?<![A-Za-z0-9_/.^])(\(?\s*\d+(?:\.\d+)?\s*/\s*\d+(?:\.\d+)?\s*\)?|\d+(?:\.\d+)?)(?!\s*/\s*\d|\s*\^)\s*(?:[*·×]\s*|\s*)(?:(?:∑|Σ|sum)\s*)?(?:([A-Za-z][A-Za-z0-9_]*(?:\[[A-Za-z0-9_]+\])?|\d+(?:\.\d+)?)(\s*/\s*(\d+))?|\(\s*[A-Za-z0-9_+\-\s/*]+\))""",
    )
    private val NEGATION_WINDOW_RE = Regex("""(?:不是|并非|非|切勿|不要|勿|误写为?|错写为?|而非|NOT\b|not\b)\s*$""")

    fun needsMathVerification(quiz: CompanionQuizItem): Boolean {
        if (quiz.kind.equals("SHORT_CALC", ignoreCase = true)) return true
        val combined = buildString {
            append(quiz.stem).append(' ')
            append(quiz.correctOption).append(' ')
            append(quiz.explanation)
        }
        return MATH_QUIZ_CUE_RE.containsMatchIn(combined)
    }

    /**
     * Extracts numeric/rational coefficients directly from mathematical formulas in prose and declared `mathChecks`.
     * Handles `U_B = 3*X_j + M`, `ΔU_B = 3*E[X] - P`, `3*(P/2) - P`, `3X`, `3*X/2` (= 3/2), `U = 5*M`, etc.
     */
    fun extractFormulaCoefficients(
        formulaText: String,
        declaredChecks: List<MathCheckSpec> = emptyList(),
    ): List<Rational> {
        val out = mutableListOf<Rational>()

        fun parseFactor(rawToken: String, trailingDen: String): Rational? {
            val cleaned = rawToken.trim().removePrefix("(").removeSuffix(")").trim()
            val base = runCatching { ExactRationalEvaluator.evaluate(cleaned) }.getOrNull() ?: return null
            if (trailingDen.isNotBlank()) {
                val den = trailingDen.trim().toBigIntegerOrNull()
                if (den != null && den > java.math.BigInteger.ZERO) {
                    return base / Rational.of(den, java.math.BigInteger.ONE)
                }
            }
            return base
        }

        fun scanText(raw: String, checkNegation: Boolean) {
            if (raw.isBlank()) return
            val norm = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('\u2212', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
            for (m in FORMULA_VAR_COEFF_RE.findAll(norm)) {
                if (checkNegation) {
                    val prefix = norm.substring((m.range.first - 14).coerceAtLeast(0), m.range.first)
                    if (NEGATION_WINDOW_RE.containsMatchIn(prefix)) continue
                }
                val coeff = parseFactor(m.groupValues[1], m.groupValues[4]) ?: continue
                if (coeff > Rational.ZERO) out += coeff
            }
            for (m in UTILITY_LHS_COEFF_RE.findAll(norm)) {
                if (checkNegation) {
                    val prefix = norm.substring((m.range.first - 14).coerceAtLeast(0), m.range.first)
                    if (NEGATION_WINDOW_RE.containsMatchIn(prefix)) continue
                }
                val coeff = parseFactor(m.groupValues[1], m.groupValues[4]) ?: continue
                if (coeff > Rational.ZERO) out += coeff
            }
        }

        scanText(formulaText, checkNegation = true)
        for (c in declaredChecks) {
            scanText(c.lhs, checkNegation = false)
            scanText(c.rhs, checkNegation = false)
            scanText(c.expression, checkNegation = false)
            scanText(c.inequality, checkNegation = false)
            if (c.kind.equals("ratio", ignoreCase = true)) {
                // Also capture self-consistent wrong ratio checks like {"kind":"ratio","lhs":"3","rhs":"3"}
                val l = runCatching { ExactRationalEvaluator.evaluate(c.lhs) }.getOrNull()
                val r = runCatching { ExactRationalEvaluator.evaluate(c.rhs) }.getOrNull()
                if (l != null && r != null && l == r && l > Rational.ONE) {
                    out += l
                }
            }
        }
        return out.distinct()
    }

    /**
     * Checks prose-vs-formula self-consistency and source-page multiplier consistency
     * (covering BOTH Chinese multiplier phrases AND algebraic formula coefficients).
     * Returns a list of deterministic failure reasons (empty if clean).
     */
    fun checkDeterministicConsistency(
        itemText: String,
        sourceCues: SourcePageMathCues,
        anchorPage: Int?,
        declaredChecks: List<MathCheckSpec> = emptyList(),
        formulaTextOverride: String? = null,
    ): List<String> {
        val failures = mutableListOf<String>()
        val norm = Normalizer.normalize(itemText, Normalizer.Form.NFKC)

        // 1. Placeholder leaks
        val leaks = PlaceholderLeakGuard.findLeaks(norm)
        if (leaks.isNotEmpty()) {
            failures += "placeholder_leak:${leaks.joinToString(",")}"
        }

        // 2. Internal Chinese multiplier vs formula contradiction (e.g. "1.5倍" while formula uses 5/2 = 2.5!)
        val zhTimesValues = ZH_NUM_TIMES_RE.findAll(norm).mapNotNull { m ->
            runCatching { Rational.parseDecimal(m.groupValues[1]) }.getOrNull()
        }.toMutableList()
        if ("三倍" in norm) zhTimesValues += Rational.of(3, 1)
        if ("两倍" in norm || "二倍" in norm) zhTimesValues += Rational.of(2, 1)
        if ("四倍" in norm) zhTimesValues += Rational.of(4, 1)

        val has5Over2 = FRAC_5_2_RE.containsMatchIn(norm)
        val has3Over2 = FRAC_3_2_RE.containsMatchIn(norm)
        if (has5Over2 && Rational.of(3, 2) in zhTimesValues && Rational.of(5, 2) !in zhTimesValues) {
            failures += "chinese_multiplier_formula_contradiction: prose claims 1.5x (1.5倍) but formula uses 5/2 (= 2.5x, i.e., 150% more)"
        }
        if (has3Over2 && Rational.of(3, 1) in zhTimesValues && Rational.of(3, 2) !in zhTimesValues) {
            failures += "chinese_multiplier_formula_contradiction: prose claims 3x (三倍/3倍) but formula uses 3/2 (= 1.5x)"
        }

        // 3. Cross-check against source-page prose multiplier cues (Chinese phrases AND direct formula coefficients!)
        val relevantCues = if (anchorPage != null) {
            sourceCues.multipliers.filter { it.pdfPage == anchorPage }.ifEmpty { sourceCues.multipliers }
        } else {
            sourceCues.multipliers
        }
        val coeffClaims = ZH_COEFF_RE.findAll(norm).mapNotNull { m ->
            val raw = m.groupValues[1]
            runCatching { ExactRationalEvaluator.evaluate(raw) }.getOrNull()
        }.toList()
        val allClaimedFactors = (zhTimesValues + coeffClaims).distinct()
        val formulaCoeffs = extractFormulaCoefficients(formulaTextOverride ?: itemText, declaredChecks)

        for (cue in relevantCues) {
            // Classic stacked-fraction drop error: source says "50% more" (3/2) but prose or formula uses numerator-only integer 3!
            if (cue.exactFactor.den != java.math.BigInteger.ONE) {
                val numeratorOnly = Rational.of(cue.exactFactor.num, java.math.BigInteger.ONE)
                if (numeratorOnly in allClaimedFactors && cue.exactFactor !in allClaimedFactors) {
                    failures += "source_multiplier_mismatch(p.${cue.pdfPage}): source says \"${cue.phrase}\" (exact factor ${cue.exactFactor} = ${cue.factorDecimal}), but item claims $numeratorOnly (dropped denominator ${cue.exactFactor.den})"
                }
                if (numeratorOnly in formulaCoeffs && relevantCues.none { it.exactFactor == numeratorOnly }) {
                    failures += "formula_coefficient_mismatch(p.${cue.pdfPage}): source says \"${cue.phrase}\" (exact factor ${cue.exactFactor} = ${cue.factorDecimal}), but formula uses integer coefficient $numeratorOnly (dropped denominator ${cue.exactFactor.den})"
                }
            }
            // Classic mistranslation error: source says "150% more" (5/2 = 2.5) but prose writes "1.5倍" OR formula uses 3/2 (1.5)!
            val pctOnly = cue.exactFactor - Rational.ONE
            if (pctOnly > Rational.ONE && relevantCues.none { it.exactFactor == pctOnly }) {
                if (pctOnly in zhTimesValues && cue.exactFactor !in zhTimesValues) {
                    failures += "source_multiplier_mistranslation(p.${cue.pdfPage}): source says \"${cue.phrase}\" (exact factor ${cue.exactFactor} = ${cue.factorDecimal}x), but prose wrote ${pctOnly}x instead of adding baseline 1"
                }
                if (pctOnly in formulaCoeffs && cue.exactFactor !in formulaCoeffs) {
                    failures += "formula_coefficient_mistranslation(p.${cue.pdfPage}): source says \"${cue.phrase}\" (exact factor ${cue.exactFactor} = ${cue.factorDecimal}), but formula uses coefficient $pctOnly instead of adding baseline 1"
                }
            }
        }

        return failures.distinct()
    }

    /** Extracts inline numeric equations from text to feed into SymPy batch verification. */
    fun extractInlineChecks(itemId: String, text: String): List<MathCheckSpec> {
        if (text.isBlank()) return emptyList()
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = mutableListOf<MathCheckSpec>()
        var idx = 0
        for (m in INLINE_NUM_EQ_RE.findAll(norm)) {
            val lhs = balanceOuterParens(m.groupValues[1].trim())
            val rhs = m.groupValues[2].trim()
            if (ISO_DATE_RE.matches(lhs)) continue
            // Skip plain section numbering like "8-1" or unbalanced fragments
            if (lhs.length < 3 || !hasBalancedParens(lhs)) continue
            out += MathCheckSpec(
                id = "$itemId:inline:${idx++}",
                kind = "eval",
                expression = lhs,
                expected = rhs,
            )
        }
        for (m in ZH_MUL_EQ_RE.findAll(norm)) {
            out += MathCheckSpec(
                id = "$itemId:zhmul:${idx++}",
                kind = "eval",
                expression = "(${m.groupValues[1]})*(${m.groupValues[2]})",
                expected = m.groupValues[3],
            )
        }
        for (m in ZH_SUB_EQ_RE.findAll(norm)) {
            out += MathCheckSpec(
                id = "$itemId:zhsub:${idx++}",
                kind = "eval",
                expression = "(${m.groupValues[1]})-(${m.groupValues[2]})",
                expected = m.groupValues[3],
            )
        }
        return out
    }

    private fun balanceOuterParens(expr: String): String {
        var s = expr.trim()
        while (s.startsWith("(") && s.count { it == '(' } > s.count { it == ')' }) {
            s = s.substring(1).trim()
        }
        while (s.endsWith(")") && s.count { it == ')' } > s.count { it == '(' }) {
            s = s.dropLast(1).trim()
        }
        return s
    }

    private fun hasBalancedParens(expr: String): Boolean {
        var depth = 0
        for (ch in expr) {
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth < 0) return false
            }
        }
        return depth == 0
    }

    /** Checks whether a computed numeric value matches a numeric MCQ `correctOption`. */
    fun optionMatchesComputedValue(correctOption: String, computedExact: String, computedDecimal: String): Boolean {
        val optNorm = Normalizer.normalize(correctOption, Normalizer.Form.NFKC)
            .replace('\u2212', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace(",", "")
            .replace("$", "")
            .replace("£", "")
            .replace("美元", "")
            .replace("元", "")
            .trim()
        val targetVal = runCatching { ExactRationalEvaluator.evaluate(computedExact) }.getOrNull()
            ?: runCatching { ExactRationalEvaluator.evaluate(computedDecimal) }.getOrNull()
            ?: return true // Non-numeric symbolic answer (checked by SymPy identity)

        // Extract candidate numbers/fractions/percentages from correctOption
        val numRe = Regex("""-?\d+(?:\.\d+)?(?:/\d+)?%?""")
        val found = numRe.findAll(optNorm).mapNotNull { m ->
            val hasPct = m.value.endsWith("%")
            val r = runCatching { ExactRationalEvaluator.evaluate(m.value) }.getOrNull() ?: return@mapNotNull null
            r to hasPct
        }.toList()
        if (found.isEmpty()) return true // Qualitative option text
        val targetBd = targetVal.toBigDecimal(4, RoundingMode.HALF_UP)
        val targetPctBd = (targetVal * Rational.of(100, 1)).toBigDecimal(4, RoundingMode.HALF_UP)
        val tol = BigDecimal("0.02")
        return found.any { (f, hasPct) ->
            val fBd = f.toBigDecimal(4, RoundingMode.HALF_UP)
            val fAsPctPoints = (f * Rational.of(100, 1)).toBigDecimal(4, RoundingMode.HALF_UP)
            fBd.subtract(targetBd).abs() <= tol ||
                fBd.subtract(targetPctBd).abs() <= tol ||
                (hasPct && fAsPctPoints.subtract(targetBd).abs() <= tol)
        }
    }
}

/**
 * End-to-end post-generation mathematical verifier for derivations and numeric/formulaic practices:
 * 1. Deterministic source-page multiplier & prose-vs-formula checks + inline equation SymPy verification
 * 2. Independent formula-to-source-page audit (`BOOK_MATH_VERIFY`) generating symbolic SymPy checks
 * 3. Batch execution of ALL checks in Python SymPy (`SympySubprocessEngine`)
 * 4. Full-item rejection + targeted regeneration (`BOOK_MATH_REPAIR`) + full re-verification;
 *    any item still failing is strictly dropped and never enters the companion pack.
 */
class CompanionMathVerifier(
    private val sympy: SymbolicMathEngine = SympySubprocessEngine(),
    private val enableLlmAudit: Boolean = true,
    private val maxRegenAttempts: Int = 2,
) {
    data class ChunkMathVerificationResult(
        val pretest: List<CompanionQuizItem>,
        val derivations: List<DerivationStepTemplate>,
        val optionalPractice: List<CompanionQuizItem>,
        val stats: MathVerificationStats,
        val extraCostMicrodollars: Long = 0L,
    )

    private data class CandidateAuditItem(
        val id: String,
        val kind: String, // "derivation" | "optionalPractice" | "pretest"
        val anchorPage: Int?,
        val combinedText: String,
        val formulaText: String,
        val declaredChecks: List<MathCheckSpec>,
        val payloadJson: JsonObject,
        val correctOption: String = "",
    )

    private data class LlmItemAudit(
        val itemId: String,
        val sourceMatches: Boolean,
        val mismatchReason: String,
        val sympyChecks: List<MathCheckSpec>,
        val mcqComputedAnswer: String,
    )

    private data class LlmAuditCallOutcome(
        val succeeded: Boolean,
        val audits: Map<String, LlmItemAudit>,
        val costMicrodollars: Long,
        val failureReason: String = "",
    )

    private data class EvaluatedVerdict(
        val itemId: String,
        val passed: Boolean,
        val failures: List<String>,
        val checksRun: Int,
        val checksPassed: Int,
    )

    suspend fun verifyAndRepairChunk(
        request: ChapterCompanionRequest,
        chunk: BoundedSectionChunk,
        pretest: List<CompanionQuizItem>,
        derivations: List<DerivationStepTemplate>,
        optionalPractice: List<CompanionQuizItem>,
        declaredChecksByItemId: Map<String, List<MathCheckSpec>>,
        transport: BatchAiTransport,
        ledger: BookTokenLedger,
        rawPages: Map<Int, String>,
    ): ChunkMathVerificationResult {
        if (chunk.isExerciseSection) {
            return ChunkMathVerificationResult(pretest, derivations, optionalPractice, MathVerificationStats())
        }
        val sourceCues = SourcePageMathExtractor.extract(chunk.blocks)
        var activePretest = pretest
        var activeDerivations = derivations
        var activePractice = optionalPractice
        val activeDeclared = declaredChecksByItemId.toMutableMap()

        val initialCandidates = buildCandidates(activePretest, activeDerivations, activePractice, activeDeclared)
        if (initialCandidates.isEmpty()) {
            return ChunkMathVerificationResult(activePretest, activeDerivations, activePractice, MathVerificationStats())
        }

        var totalChecksRun = 0
        var totalChecksPassed = 0
        var lastEngine = "sympy"
        var extraCost = 0L
        var placeholderLeaks = 0
        val failureSummaries = mutableListOf<String>()

        suspend fun evaluateAll(candidates: List<CandidateAuditItem>, roundLabel: String): Map<String, EvaluatedVerdict> {
            val detFailuresById = LinkedHashMap<String, MutableList<String>>()
            val allSympyChecks = mutableListOf<MathCheckSpec>()
            val checkOwner = HashMap<String, String>()

            for (cand in candidates) {
                val det = DeterministicMathChecks.checkDeterministicConsistency(
                    itemText = cand.combinedText,
                    sourceCues = sourceCues,
                    anchorPage = cand.anchorPage,
                    declaredChecks = cand.declaredChecks,
                    formulaTextOverride = cand.formulaText,
                ).toMutableList()
                if (det.any { it.startsWith("placeholder_leak:") }) placeholderLeaks++
                detFailuresById[cand.id] = det

                // Declared mathChecks + inline equations
                val inline = DeterministicMathChecks.extractInlineChecks(cand.id, cand.combinedText)
                (cand.declaredChecks + inline).forEachIndexed { idx, spec ->
                    val cid = spec.id.ifBlank { "${cand.id}:decl:$idx" }
                    val tagged = spec.copy(id = cid)
                    allSympyChecks += tagged
                    checkOwner[cid] = cand.id
                }
            }

            // Independent LLM source-page formula audit -> SymPy checks (STRICT FAIL-CLOSED!)
            val llmAudits: Map<String, LlmItemAudit> = if (enableLlmAudit && candidates.isNotEmpty()) {
                val outcome = runIndependentLlmAudit(
                    request = request,
                    chunk = chunk,
                    candidates = candidates,
                    sourceCues = sourceCues,
                    roundLabel = roundLabel,
                    transport = transport,
                    ledger = ledger,
                )
                extraCost += outcome.costMicrodollars
                if (!outcome.succeeded) {
                    val reason = outcome.failureReason.ifBlank { "audit_unavailable" }
                    for (cand in candidates) {
                        detFailuresById.getOrPut(cand.id) { mutableListOf() } += "audit_failed:$reason"
                    }
                    emptyMap()
                } else {
                    for (cand in candidates) {
                        val audit = outcome.audits[cand.id]
                        if (audit == null) {
                            detFailuresById.getOrPut(cand.id) { mutableListOf() } += "audit_omitted_item:${cand.id}"
                        } else {
                            if (!audit.sourceMatches) {
                                val reason = audit.mismatchReason.ifBlank { "source_formula_mismatch" }
                                detFailuresById.getOrPut(cand.id) { mutableListOf() } += "source_audit:$reason"
                            }
                            audit.sympyChecks.forEachIndexed { idx, spec ->
                                val cid = "${cand.id}:audit:$idx"
                                val tagged = spec.copy(id = cid)
                                allSympyChecks += tagged
                                checkOwner[cid] = cand.id
                            }
                        }
                    }
                    outcome.audits
                }
            } else {
                emptyMap()
            }

            // Execute ALL checks in one SymPy batch subprocess
            val batchResp = withContext(Dispatchers.IO) { sympy.runBatch(allSympyChecks) }
            if (batchResp.engine.isNotBlank()) lastEngine = batchResp.engine
            val resultsByOwner = HashMap<String, MutableList<SympyCheckResult>>()
            for (res in batchResp.results) {
                val owner = checkOwner[res.id] ?: continue
                resultsByOwner.getOrPut(owner) { mutableListOf() } += res
            }

            val verdicts = LinkedHashMap<String, EvaluatedVerdict>()
            for (cand in candidates) {
                val fails = detFailuresById[cand.id].orEmpty().toMutableList()
                val symRes = resultsByOwner[cand.id].orEmpty()
                totalChecksRun += symRes.size
                val passedSym = symRes.count { it.passed }
                totalChecksPassed += passedSym
                for (r in symRes) {
                    if (!r.passed) {
                        fails += "sympy(${r.kind}:${r.id}):${r.reason}"
                    }
                }
                // Check MCQ numeric option alignment against audited / evaluated answer
                if (cand.correctOption.isNotBlank()) {
                    val llmComputed = llmAudits[cand.id]?.mcqComputedAnswer.orEmpty()
                    if (llmComputed.isNotBlank() && !DeterministicMathChecks.optionMatchesComputedValue(cand.correctOption, llmComputed, llmComputed)) {
                        fails += "mcq_option_mismatch(correctOption='${cand.correctOption}',computed='$llmComputed')"
                    }
                    val evalPassed = symRes.filter { it.kind == "eval" && it.passed && it.id.contains(":audit:") }
                    if (evalPassed.size == 1) {
                        val single = evalPassed.single()
                        if (single.exactValue.isNotBlank() &&
                            !DeterministicMathChecks.optionMatchesComputedValue(cand.correctOption, single.exactValue, single.decimalValue)
                        ) {
                            fails += "mcq_sympy_eval_mismatch(correctOption='${cand.correctOption}',sympy='${single.exactValue}')"
                        }
                    }
                }
                verdicts[cand.id] = EvaluatedVerdict(
                    itemId = cand.id,
                    passed = fails.isEmpty(),
                    failures = fails.distinct(),
                    checksRun = symRes.size,
                    checksPassed = passedSym,
                )
            }
            return verdicts
        }

        // Round 0: verify initial candidates
        val initialVerdicts = evaluateAll(initialCandidates, "round0")
        val initialFailedIds = initialVerdicts.values.filterNot { it.passed }.map { it.itemId }.toSet()
        for (v in initialVerdicts.values.filterNot { it.passed }) {
            failureSummaries += "${v.itemId}: ${v.failures.joinToString("; ")}"
        }

        if (initialFailedIds.isEmpty()) {
            return ChunkMathVerificationResult(
                pretest = activePretest,
                derivations = activeDerivations,
                optionalPractice = activePractice,
                stats = MathVerificationStats(
                    checkedItems = initialCandidates.size,
                    initialFailedItems = 0,
                    rejectedItems = 0,
                    regeneratedPassedItems = 0,
                    finalDroppedItems = 0,
                    sympyChecksRun = totalChecksRun,
                    sympyChecksPassed = totalChecksPassed,
                    engine = lastEngine,
                    placeholderLeaksBlocked = placeholderLeaks,
                    failureSummaries = emptyList(),
                ),
                extraCostMicrodollars = extraCost,
            )
        }

        // Reject failing items and attempt targeted regeneration + full re-verification
        var remainingFailedIds = initialFailedIds
        var currentVerdicts = initialVerdicts
        var regeneratedPassed = 0
        var attempt = 0

        while (remainingFailedIds.isNotEmpty() && attempt < maxRegenAttempts) {
            attempt++
            val failedCandidates = buildCandidates(activePretest, activeDerivations, activePractice, activeDeclared)
                .filter { it.id in remainingFailedIds }
            if (failedCandidates.isEmpty()) break

            val (repairedMap, repairCost) = runTargetedRegeneration(
                request = request,
                chunk = chunk,
                failedCandidates = failedCandidates,
                verdicts = currentVerdicts,
                sourceCues = sourceCues,
                attempt = attempt,
                transport = transport,
                ledger = ledger,
                rawPages = rawPages,
            )
            extraCost += repairCost

            // Replace items with regenerated versions (or mark absent if repair dropped them)
            val newCandidateList = mutableListOf<CandidateAuditItem>()
            activeDerivations = activeDerivations.mapNotNull { d ->
                if (d.id !in remainingFailedIds) {
                    d
                } else {
                    val rep = repairedMap[d.id]?.derivation
                    if (rep != null) {
                        repairedMap[d.id]?.mathChecks?.let { activeDeclared[d.id] = it }
                        rep
                    } else {
                        null
                    }
                }
            }
            activePractice = activePractice.mapNotNull { q ->
                if (q.id !in remainingFailedIds) {
                    q
                } else {
                    val rep = repairedMap[q.id]?.quiz
                    if (rep != null) {
                        repairedMap[q.id]?.mathChecks?.let { activeDeclared[q.id] = it }
                        rep
                    } else {
                        null
                    }
                }
            }
            activePretest = activePretest.mapNotNull { q ->
                if (q.id !in remainingFailedIds) {
                    q
                } else {
                    val rep = repairedMap[q.id]?.quiz
                    if (rep != null) {
                        repairedMap[q.id]?.mathChecks?.let { activeDeclared[q.id] = it }
                        rep
                    } else {
                        null
                    }
                }
            }

            newCandidateList += buildCandidates(activePretest, activeDerivations, activePractice, activeDeclared)
                .filter { it.id in remainingFailedIds }

            if (newCandidateList.isEmpty()) {
                remainingFailedIds = emptySet()
                break
            }

            val reVerdicts = evaluateAll(newCandidateList, "regen$attempt")
            val newlyPassed = reVerdicts.values.filter { it.passed }.map { it.itemId }.toSet()
            regeneratedPassed += newlyPassed.size
            remainingFailedIds = reVerdicts.values.filterNot { it.passed }.map { it.itemId }.toSet()
            currentVerdicts = reVerdicts
        }

        // Strict fail-closed drop: ANY item still failing after regeneration is REMOVED from the pack!
        val stillFailed = remainingFailedIds
        if (stillFailed.isNotEmpty()) {
            activeDerivations = activeDerivations.filterNot { it.id in stillFailed }
            activePractice = activePractice.filterNot { it.id in stillFailed }
            activePretest = activePretest.filterNot { it.id in stillFailed }
        }
        val finalDropped = (initialFailedIds.size - regeneratedPassed).coerceAtLeast(0)

        return ChunkMathVerificationResult(
            pretest = activePretest,
            derivations = activeDerivations,
            optionalPractice = activePractice,
            stats = MathVerificationStats(
                checkedItems = initialCandidates.size,
                initialFailedItems = initialFailedIds.size,
                rejectedItems = initialFailedIds.size,
                regeneratedPassedItems = regeneratedPassed,
                finalDroppedItems = finalDropped,
                sympyChecksRun = totalChecksRun,
                sympyChecksPassed = totalChecksPassed,
                engine = lastEngine,
                placeholderLeaksBlocked = placeholderLeaks,
                failureSummaries = failureSummaries,
            ),
            extraCostMicrodollars = extraCost,
        )
    }

    private fun buildCandidates(
        pretest: List<CompanionQuizItem>,
        derivations: List<DerivationStepTemplate>,
        optionalPractice: List<CompanionQuizItem>,
        declaredChecks: Map<String, List<MathCheckSpec>>,
    ): List<CandidateAuditItem> = buildList {
        for (d in derivations) {
            val formulaText = buildString {
                append(d.title).append('\n')
                append(d.setup).append('\n')
                d.steps.forEach { append(it).append('\n') }
                append(d.graphCheck)
            }
            val combined = buildString {
                append(formulaText).append('\n')
                append(d.pitfall)
            }
            add(
                CandidateAuditItem(
                    id = d.id,
                    kind = "derivation",
                    anchorPage = d.anchor?.pdfPage,
                    combinedText = combined,
                    formulaText = formulaText,
                    declaredChecks = declaredChecks[d.id].orEmpty(),
                    payloadJson = buildJsonObject {
                        put("itemId", d.id)
                        put("id", d.id)
                        put("kind", "derivation")
                        put("title", d.title)
                        put("setup", d.setup)
                        put("steps", buildJsonArray { d.steps.forEach { add(JsonPrimitive(it)) } })
                        put("graphCheck", d.graphCheck)
                        put("pitfall", d.pitfall)
                        d.anchor?.let { put("pdfPage", it.pdfPage) }
                    },
                ),
            )
        }
        for (q in optionalPractice) {
            if (!DeterministicMathChecks.needsMathVerification(q)) continue
            add(quizCandidate(q, "optionalPractice", declaredChecks[q.id].orEmpty()))
        }
        for (q in pretest) {
            if (!DeterministicMathChecks.needsMathVerification(q)) continue
            add(quizCandidate(q, "pretest", declaredChecks[q.id].orEmpty()))
        }
    }

    private fun quizCandidate(q: CompanionQuizItem, kind: String, checks: List<MathCheckSpec>): CandidateAuditItem {
        val formulaText = buildString {
            append(q.stem).append('\n')
            append("Correct: ").append(q.correctOption).append('\n')
            append(q.explanation)
        }
        val combined = buildString {
            append(q.stem).append('\n')
            append("Options: ").append(q.options.joinToString(" | ")).append('\n')
            append("Correct: ").append(q.correctOption).append('\n')
            append(q.explanation)
        }
        return CandidateAuditItem(
            id = q.id,
            kind = kind,
            anchorPage = q.anchor?.pdfPage,
            combinedText = combined,
            formulaText = formulaText,
            declaredChecks = checks,
            correctOption = q.correctOption,
            payloadJson = buildJsonObject {
                put("itemId", q.id)
                put("id", q.id)
                put("kind", kind)
                put("stem", q.stem)
                put("options", buildJsonArray { q.options.forEach { add(JsonPrimitive(it)) } })
                put("correctOption", q.correctOption)
                put("explanation", q.explanation)
                q.anchor?.let { put("pdfPage", it.pdfPage) }
            },
        )
    }

    private suspend fun runIndependentLlmAudit(
        request: ChapterCompanionRequest,
        chunk: BoundedSectionChunk,
        candidates: List<CandidateAuditItem>,
        sourceCues: SourcePageMathCues,
        roundLabel: String,
        transport: BatchAiTransport,
        ledger: BookTokenLedger,
    ): LlmAuditCallOutcome {
        val userPrompt = buildString {
            append("Chapter: ").append(request.chapterTitle).append('\n')
            append("Section: ").append(chunk.title).append('\n')
            append(sourceCues.formatForPrompt())
            append("\n=== ORIGINAL SOURCE PAGE BLOCKS ===\n")
            for (blk in chunk.blocks) {
                append("[PDF p.").append(blk.pdfPage).append(" | ").append(blk.id).append("]\n")
                append(blk.text.trim()).append("\n\n")
            }
            append("=== CANDIDATE ITEMS TO AUDIT ===\n")
            append(buildJsonArray { candidates.forEach { add(it.payloadJson) } }.toString())
        }
        val batchKey = sha256(
            "${request.bookId}:${request.chapterId}:${chunk.sectionId}:${chunk.chunkIndex}:mathverify:$roundLabel".toByteArray(),
        )
        val reply = runCatching {
            transport.generateSection(
                systemPrompt = MATH_AUDIT_SYSTEM_PROMPT,
                userPrompt = userPrompt,
                task = "BOOK_MATH_VERIFY",
                batchKey = batchKey,
                maxTokens = 12_000,
            )
        }.getOrElse { err ->
            // Fail closed: audit transport failure MUST reject candidates rather than silently passing them!
            return LlmAuditCallOutcome(
                succeeded = false,
                audits = emptyMap(),
                costMicrodollars = 0L,
                failureReason = "transport_error:${err.javaClass.simpleName}",
            )
        }
        val costMicro = TokenLedgerPricing.costMicrodollars(
            reply.routeChannel,
            reply.inputTokens,
            reply.outputTokens,
            reply.cachedTokens,
        )
        ledger.record(
            TokenLedgerEntry(
                bookId = request.bookId,
                chapterId = request.chapterId,
                sectionId = "${chunk.sectionId}#${chunk.chunkIndex}:mathverify:$roundLabel",
                task = "BOOK_MATH_VERIFY",
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                cachedTokens = reply.cachedTokens,
                costMicrodollars = costMicro,
                ttftMs = reply.ttftMs,
                elapsedMs = reply.elapsedMs,
                status = "complete",
            ),
        )
        val obj = extractJsonObject(reply.text)
            ?: return LlmAuditCallOutcome(
                succeeded = false,
                audits = emptyMap(),
                costMicrodollars = costMicro,
                failureReason = "invalid_audit_json",
            )
        val auditsArray = obj["itemAudits"] as? JsonArray
            ?: return LlmAuditCallOutcome(
                succeeded = false,
                audits = emptyMap(),
                costMicrodollars = costMicro,
                failureReason = "missing_itemAudits_array",
            )
        val audits = LinkedHashMap<String, LlmItemAudit>()
        for (el in auditsArray) {
            val item = el as? JsonObject ?: continue
            val id = (item["itemId"] ?: item["id"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isBlank()) continue
            val matches = item["sourceMatches"]?.jsonPrimitive?.booleanOrNull ?: false
            val reason = item["sourceMismatchReason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val mcqAns = item["mcqComputedAnswer"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val checks = (item["sympyChecks"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, cEl ->
                (cEl as? JsonObject)?.let { MathCheckSpec.fromJson(it, defaultId = "$id:audit:$idx") }
            }
            audits[id] = LlmItemAudit(
                itemId = id,
                sourceMatches = matches,
                mismatchReason = reason,
                sympyChecks = checks,
                mcqComputedAnswer = mcqAns,
            )
        }
        return LlmAuditCallOutcome(succeeded = true, audits = audits, costMicrodollars = costMicro)
    }

    private data class RepairedItem(
        val derivation: DerivationStepTemplate? = null,
        val quiz: CompanionQuizItem? = null,
        val mathChecks: List<MathCheckSpec> = emptyList(),
    )

    private suspend fun runTargetedRegeneration(
        request: ChapterCompanionRequest,
        chunk: BoundedSectionChunk,
        failedCandidates: List<CandidateAuditItem>,
        verdicts: Map<String, EvaluatedVerdict>,
        sourceCues: SourcePageMathCues,
        attempt: Int,
        transport: BatchAiTransport,
        ledger: BookTokenLedger,
        rawPages: Map<Int, String>,
    ): Pair<Map<String, RepairedItem>, Long> {
        val userPrompt = buildString {
            append("Chapter: ").append(request.chapterTitle).append('\n')
            append("Section: ").append(chunk.title).append('\n')
            append(sourceCues.formatForPrompt())
            append("\n=== ORIGINAL SOURCE PAGE BLOCKS ===\n")
            for (blk in chunk.blocks) {
                append("[PDF p.").append(blk.pdfPage).append(" | ").append(blk.id).append("]\n")
                append(blk.text.trim()).append("\n\n")
            }
            append("=== REJECTED ITEMS & EXACT VERIFICATION FAILURES ===\n")
            append(buildJsonArray {
                for (cand in failedCandidates) {
                    add(buildJsonObject {
                        put("itemId", cand.id)
                        put("kind", cand.kind)
                        put("failures", buildJsonArray {
                            verdicts[cand.id]?.failures.orEmpty().forEach { add(JsonPrimitive(it)) }
                        })
                        put("rejectedItem", cand.payloadJson)
                    })
                }
            }.toString())
        }
        val batchKey = sha256(
            "${request.bookId}:${request.chapterId}:${chunk.sectionId}:${chunk.chunkIndex}:mathrepair:$attempt".toByteArray(),
        )
        val reply = runCatching {
            transport.generateSection(
                systemPrompt = MATH_REPAIR_SYSTEM_PROMPT,
                userPrompt = userPrompt,
                task = "BOOK_MATH_REPAIR",
                batchKey = batchKey,
                maxTokens = 12_000,
            )
        }.getOrElse {
            return emptyMap<String, RepairedItem>() to 0L
        }
        val costMicro = TokenLedgerPricing.costMicrodollars(
            reply.routeChannel,
            reply.inputTokens,
            reply.outputTokens,
            reply.cachedTokens,
        )
        ledger.record(
            TokenLedgerEntry(
                bookId = request.bookId,
                chapterId = request.chapterId,
                sectionId = "${chunk.sectionId}#${chunk.chunkIndex}:mathrepair:$attempt",
                task = "BOOK_MATH_REPAIR",
                routeChannel = reply.routeChannel,
                model = reply.model,
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                cachedTokens = reply.cachedTokens,
                costMicrodollars = costMicro,
                ttftMs = reply.ttftMs,
                elapsedMs = reply.elapsedMs,
                status = "complete",
            ),
        )
        val obj = extractJsonObject(reply.text) ?: return emptyMap<String, RepairedItem>() to costMicro
        val out = LinkedHashMap<String, RepairedItem>()
        val candById = failedCandidates.associateBy { it.id }

        for (el in (obj["repairedItems"] as? JsonArray).orEmpty()) {
            val item = el as? JsonObject ?: continue
            val itemId = (item["itemId"] ?: item["id"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val orig = candById[itemId] ?: continue
            val checks = (item["mathChecks"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, cEl ->
                (cEl as? JsonObject)?.let { MathCheckSpec.fromJson(it, defaultId = "$itemId:rep:$idx") }
            }
            val page = item["pdfPage"]?.jsonPrimitive?.intOrNull ?: orig.anchorPage
            val quote = item["quoteAnchor"]?.jsonPrimitive?.contentOrNull
            val anchor = if (!quote.isNullOrBlank()) {
                Utf16AnchorVerifier.verify(page, quote, chunk.blocks, rawPages)
            } else {
                null
            }
            if (orig.kind == "derivation") {
                val title = item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val setup = item["setup"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val steps = (item["steps"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                if (title.isBlank() || steps.isEmpty()) continue
                out[itemId] = RepairedItem(
                    derivation = DerivationStepTemplate(
                        id = itemId,
                        sectionId = chunk.sectionId,
                        title = title,
                        setup = setup,
                        steps = steps,
                        graphCheck = item["graphCheck"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                        pitfall = item["pitfall"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                        anchor = anchor,
                    ),
                    mathChecks = checks,
                )
            } else {
                val stem = item["stem"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val options = (item["options"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                val correct = item["correctOption"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val explanation = item["explanation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val kind = item["quizKind"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { if (options.size >= 2) "MCQ" else "SHORT_CALC" }
                if (stem.isBlank() || correct.isBlank()) continue
                if (kind == "MCQ" && (options.size < 2 || correct !in options)) continue
                val revealHint = item["revealHint"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank {
                    anchor?.let { "答案依据在第 ${it.pdfPage} 页。" } ?: "阅读本节正文即可核对。"
                }
                out[itemId] = RepairedItem(
                    quiz = CompanionQuizItem(
                        id = itemId,
                        sectionId = chunk.sectionId,
                        stem = stem,
                        options = options,
                        correctOption = correct,
                        explanation = explanation,
                        revealHint = revealHint,
                        kind = kind,
                        anchor = anchor,
                    ),
                    mathChecks = checks,
                )
            }
        }
        return out to costMicro
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        runCatching { return LedgerJson.parseToJsonElement(trimmed).jsonObject }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return LedgerJson.parseToJsonElement(trimmed.substring(start, end + 1)).jsonObject }
        }
        return null
    }

    companion object {
        internal val MATH_AUDIT_SYSTEM_PROMPT = """
            You are a strict, independent mathematical auditor for textbook study-companion derivations and practice items.
            You have NO tools. Your job is to verify EVERY formula, coefficient, sign, inequality, condition, critical threshold, and numerical calculation in each candidate item against the ORIGINAL SOURCE PAGE BLOCKS.
            CRITICAL PDF FRACTION RULE:
            PDF text extraction often splits stacked fractions across lines (for example, "3" on one line and "2" on another line around a summation index for 3/2, or "5" and "2" for 5/2).
            ALWAYS cross-check equations with surrounding prose! For example:
            - "50% more than a seller" means multiplier = 1 + 0.5 = 3/2 = 1.5 (NOT 3!).
            - "150% more than sellers" means multiplier = 1 + 1.5 = 5/2 = 2.5 ("2.5倍", NOT "1.5倍"!).
            - Check EVERY Chinese phrasing ("1.5倍", "2.5倍", "三倍", "不小于", "不超过", "临界值") matches the exact formula!
            For each candidate item, output:
            - "sourceMatches": true ONLY IF every coefficient, variable, sign, condition, bound, critical value, and Chinese multiplier description matches the original page with ZERO discrepancies. Otherwise false!
            - "sourceMismatchReason": exact explanation of what mismatches the source page (empty string if sourceMatches=true).
            - "sympyChecks": array of exact machine-verifiable symbolic/numeric checks covering EVERY equation, substitution, threshold, and calculation in the item (use plain ASCII math: *, /, ^, Rational fractions like (3/2), variable names like P, X, q, r, p):
              - identity: {"kind":"identity","lhs":"(5/2)*(P/2) - P","rhs":"P/4"}
              - eval: {"kind":"eval","expression":"(3/2)*X","variables":{"X":"2"},"expected":"3"}
              - ratio: {"kind":"ratio","lhs":"3/2","rhs":"3/2"} (compare item's claimed coefficient vs source's exact coefficient!)
              - inequality: {"kind":"inequality","inequality":"(3/2)*(P+10)/2 - P >= 0","variable":"P","expectedRelation":"<=","expectedThreshold":"30"}
              - solve: {"kind":"solve","lhs":"(3/2)*(P+10)/2 - P","rhs":"0","variable":"P","expected":"30"}
            - "mcqComputedAnswer": for numeric MCQ items, the exact computed answer from the ORIGINAL source formula (empty string for non-numeric or derivation items).
            Output ONE valid JSON object only (no Markdown fences, no extra prose):
            {"itemAudits":[{"itemId":"...","sourceMatches":true,"sourceMismatchReason":"","sympyChecks":[...],"mcqComputedAnswer":""}]}
        """.trimIndent()

        internal val MATH_REPAIR_SYSTEM_PROMPT = """
            You are a precision mathematical re-generator for textbook study-companion modules. You have NO tools.
            One or more derivations or practice questions were REJECTED by symbolic math / source-page verification.
            Regenerate each rejected item from scratch strictly grounded in the ORIGINAL SOURCE PAGE BLOCKS and the PDF Math Cues.
            RULES:
            1. Every coefficient, fraction, sign, inequality direction, critical threshold, and Chinese multiplier wording (e.g. "50% more" = 1.5倍 = 3/2; "150% more" = 2.5倍 = 5/2) MUST match the original textbook page 100%.
            2. Recompute every numerical answer accurately and make sure `correctOption` is an EXACT string in `options` matching the verified result.
            3. NEVER copy >=8 consecutive English words or >=12 consecutive Chinese characters from the source text. Write clean Simplified Chinese; NEVER emit placeholders like "[依原文公式]", "〔依原文公式〕", or "（简述）".
            4. Include `mathChecks` (SymPy-ready `identity`, `eval`, `inequality`, `solve`, or `ratio` specs) for every regenerated item.
            5. Include exact `pdfPage` and short verbatim `quoteAnchor` (3-6 exact words from that page).
            Output ONE valid JSON object only (no Markdown fences):
            {
              "repairedItems": [
                {
                  "itemId": "exact rejected itemId",
                  "title": "(for derivation)",
                  "setup": "(for derivation)",
                  "steps": ["..."],
                  "graphCheck": "(for derivation)",
                  "pitfall": "(for derivation)",
                  "stem": "(for quiz)",
                  "options": ["..."],
                  "correctOption": "(exact match in options)",
                  "explanation": "(for quiz)",
                  "revealHint": "答案依据在第 X 页。",
                  "quizKind": "MCQ",
                  "pdfPage": 1,
                  "quoteAnchor": "3-6 exact words",
                  "mathChecks": [{"kind":"eval","expression":"(3/2)*X","variables":{"X":"2"},"expected":"3"}]
                }
              ]
            }
        """.trimIndent()
    }
}
