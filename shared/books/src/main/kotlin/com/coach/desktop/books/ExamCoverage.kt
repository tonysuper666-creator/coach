package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

internal data class CoverageBankPoint(
    val name: String,
    val choices: Int,
    val essay: Boolean,
)

internal data class CoverageGap(
    val name: String,
    val reason: String,
)

internal data class CoverageReport(
    val heldOutPoints: Int,
    val covered: Int,
    val rate: Double,
    val uncovered: List<CoverageGap>,
    val coveredPoints: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("heldOutPoints", heldOutPoints)
        put("covered", covered)
        put("coverage", rate)
        put("neverGenerated", uncovered.count { it.reason == ExamCoverage.NEVER_GENERATED })
        put("droppedByCheck", uncovered.count { it.reason == ExamCoverage.QUESTION_DROPPED })
        put("coveredPoints", buildJsonArray {
            coveredPoints.forEach { add(JsonPrimitive(it)) }
        })
        put("uncovered", buildJsonArray {
            uncovered.forEach { gap ->
                add(buildJsonObject {
                    put("name", gap.name)
                    put("reason", gap.reason)
                })
            }
        })
    }
}

/**
 * Scores a course bank against points taken from held-out questions.
 * The question text is an input to the decomposer and is not written into the report.
 */
internal object ExamCoverage {
    const val TEXTBOOK_SILENT = "教材没讲"
    const val POINT_MISSED = "考点没识别出"
    const val NEVER_GENERATED = "从未生成"
    const val QUESTION_DROPPED = "识别了但题被核对丢掉"

    private val json = Json { ignoreUnknownKeys = true }
    private val cjkChar = Regex("""[\u4E00-\u9FFF]""")
    private val romanNumeral = Regex("""^[ivxlcdm]+$""", RegexOption.IGNORE_CASE)
    private val cjkGenericPoints = setOf(
        "考点", "模型", "理论", "实验", "方法", "分析", "函数", "曲线", "概念", "问题", "市场", "大学", "政策",
    )
    private val umbrellaHeadNouns = setOf(
        "experiment", "experiments", "study", "studies", "trial", "trials", "survey", "surveys",
        "model", "models", "theory", "theories", "hypothesis", "hypotheses",
        "policy", "policies", "program", "programs", "programme", "programmes",
        "project", "projects", "plan", "plans", "scheme", "schemes", "system", "systems",
        "act", "acts", "law", "laws", "reform", "reforms",
        "case", "cases", "example", "examples", "analysis", "analyses",
        "approach", "approaches", "method", "methods", "framework", "frameworks",
        "research", "review", "test", "tests", "report", "reports",
        "measure", "measures", "data", "evidence",
        "topic", "topics", "concept", "concepts", "issue", "issues",
        "problem", "problems", "question", "questions",
    )
    private val genericPhraseModifiers = setOf(
        "insurance", "health", "medical", "clinical", "healthcare",
        "policy", "economic", "economics", "financial", "fiscal", "monetary",
        "social", "public", "private", "general", "simple", "basic",
        "main", "major", "minor", "standard", "normal", "common", "typical",
        "natural", "empirical", "theoretical", "experimental",
        "randomized", "randomised", "field", "laboratory", "lab",
        "national", "local", "regional", "rural", "urban", "global", "international",
        "federal", "state", "government", "care", "welfare", "tax",
        "market", "research", "case", "hospital", "patient", "physician", "doctor",
        "education", "labor", "labour", "income", "trade",
        "price", "cost", "risk", "choice", "decision",
        "behavior", "behaviour", "incentive", "information",
        "development", "growth", "short", "long", "new", "old",
        "early", "late", "modern", "classical", "traditional", "alternative",
        "optimal", "linear", "static", "dynamic", "partial", "full",
        "complete", "direct", "indirect", "annual", "first", "second", "third",
    )
    private val genericSingleWords = umbrellaHeadNouns + genericPhraseModifiers + HeldOutDecomposer.skipWords +
        HeldOutDecomposer.boilerplateWords + HeldOutDecomposer.glueWordSet + setOf(
            "university", "universities", "college", "colleges", "school", "schools",
            "department", "departments", "faculty", "faculties", "institute", "institutes",
            "office", "offices", "board", "boards", "academy", "press", "journal", "journals",
            "author", "authors", "lecturer", "professor", "course", "courses",
            "lecture", "lectures", "chapter", "chapters", "section", "sections",
            "part", "parts", "unit", "units", "page", "pages", "note", "notes",
            "source", "sources", "table", "tables", "figure", "figures",
            "chart", "charts", "graph", "graphs", "box", "boxes", "panel", "panels",
            "diagram", "diagrams", "exhibit", "exhibits", "appendix", "appendices",
            "paper", "papers", "exam", "exams", "examination", "examinations",
            "midterm", "final", "quiz", "answer", "answers", "solution", "solutions",
            "result", "results", "finding", "findings", "outcome", "outcomes",
            "definition", "definitions", "theorem", "theorems", "lemma", "lemmas",
            "proposition", "propositions", "corollary", "equation", "equations",
            "formula", "formulas", "function", "functions", "curve", "curves",
            "line", "lines", "slope", "slopes", "intercept", "intercepts",
            "mechanism", "mechanisms", "process", "processes", "structure", "structures",
            "pattern", "patterns", "trend", "trends", "relationship", "relationships",
            "relation", "relations", "comparison", "comparisons", "difference", "differences",
            "change", "changes", "effect", "effects", "impact", "impacts",
            "cause", "causes", "reason", "reasons", "factor", "factors",
            "variable", "variables", "parameter", "parameters", "constant", "constants",
            "term", "terms", "index", "indices", "indexes", "ratio", "ratios",
            "rate", "rates", "share", "shares", "level", "levels", "degree", "degrees",
            "type", "types", "kind", "kinds", "form", "forms", "category", "categories",
            "group", "groups", "class", "classes", "set", "sets", "sample", "samples",
            "population", "populations", "distribution", "distributions",
            "average", "averages", "mean", "means", "median", "variance", "deviation",
            "range", "margin", "margins", "threshold", "thresholds", "limit", "limits",
            "bound", "bounds", "constraint", "constraints", "objective", "objectives",
            "goal", "goals", "metric", "metrics", "score", "scores", "value", "values",
            "amount", "amounts", "total", "totals", "number", "numbers",
            "quantity", "quantities", "size", "sizes", "scale", "weight", "weights",
            "time", "times", "period", "periods", "year", "years", "month", "months",
            "week", "weeks", "day", "days", "age", "ages", "stage", "stages",
            "phase", "phases", "step", "steps", "order", "rank", "status",
            "condition", "conditions", "situation", "situations", "context", "setting",
            "environment", "regulation", "regulations", "mandate", "mandates",
            "institution", "institutions", "organization", "organizations",
            "markets", "economy", "uncertainty", "preference", "preferences",
            "decisions", "incentives", "selection", "hazard", "demand", "supply",
            "prices", "costs", "benefits", "profit", "profits", "loss", "losses",
            "gain", "gains", "return", "returns", "wealth", "wage", "wages",
            "earnings", "spending", "expenditure", "budget", "taxes", "subsidy", "subsidies",
            "grant", "grants", "transfer", "transfers", "payment", "payments",
            "coverage", "access", "quality", "efficiency", "equity", "equality",
            "inequality", "poverty", "training", "employment", "unemployment",
            "work", "worker", "workers", "job", "jobs", "firm", "firms",
            "company", "companies", "industry", "industries", "sector", "sectors",
            "area", "areas", "region", "regions", "country", "countries",
            "nation", "nations", "city", "cities", "clinics", "doctors",
            "physicians", "patients", "person", "people", "individual", "individuals",
            "household", "households", "family", "families", "child", "children",
            "adult", "adults", "woman", "women", "man", "men",
            "consumer", "consumers", "producer", "producers", "buyer", "buyers",
            "seller", "sellers", "student", "students", "candidate", "candidates",
            "governments", "agency", "agencies", "service", "services",
            "good", "goods", "product", "products", "treatment", "treatments",
            "disease", "diseases", "illness", "illnesses", "mortality", "morbidity",
            "survival", "life", "death", "knowledge", "signal", "signals",
            "contract", "contracts", "equilibrium", "competition",
            "production", "consumption", "allocation", "investment",
            "output", "input", "inputs", "capital", "credit", "debt",
            "bank", "banks", "savings", "control", "controls", "bias",
            "error", "errors", "noise", "moral", "adverse",
            "extension", "extensions", "application", "applications",
            "implication", "implications", "discussion", "motivation", "intuition",
            "empirics", "recap", "agenda", "roadmap", "takeaways", "remarks",
            "readings", "review", "enrollment", "explanation", "explanations",
            "interpretation", "interpretations", "conclusion", "conclusions",
            "introduction", "background", "overview", "summary", "outline",
        )

    fun evaluate(
        questions: List<String>,
        identified: List<String>,
        bank: List<CoverageBankPoint>,
        textbook: String,
    ): CoverageReport {
        val points = HeldOutDecomposer.decomposeQuestions(questions, identified, textbook)
        val textbookNorm = CourseExamPoints.normalize(textbook)
        val textbookSpaced = textbookNorm.replace('-', ' ')
        val coveredPoints = mutableListOf<String>()
        val uncovered = mutableListOf<CoverageGap>()
        for (point in points) {
            if (!point.matched) {
                uncovered += CoverageGap(point.name, POINT_MISSED)
                continue
            }
            val name = point.name
            if (isGenericPoint(name)) {
                uncovered += CoverageGap(name, POINT_MISSED)
                continue
            }
            val taught = termIn(name, textbookNorm, textbookSpaced)
            val known = identified.any { samePoint(name, it) }
            val item = bestBankPoint(name, bank)
            val hasTeaching = item != null && (item.choices > 0 || item.essay)
            if (taught && known && hasTeaching) {
                coveredPoints += name
            } else {
                val reason = when {
                    !taught -> TEXTBOOK_SILENT
                    !known -> POINT_MISSED
                    item == null -> NEVER_GENERATED
                    else -> QUESTION_DROPPED
                }
                uncovered += CoverageGap(name, reason)
            }
        }
        val total = coveredPoints.size + uncovered.size
        val rate = if (total == 0) 0.0 else coveredPoints.size.toDouble() / total
        return CoverageReport(total, coveredPoints.size, rate, uncovered, coveredPoints)
    }

    fun readBank(path: Path): List<CoverageBankPoint> {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val rows = root["points"] as? JsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name").ifBlank { return@mapNotNull null }
            CoverageBankPoint(
                name = name,
                choices = (obj["choices"] as? JsonPrimitive)?.intOrNull ?: 0,
                essay = (obj["essay"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
    }

    fun readIdentified(path: Path?, bank: List<CoverageBankPoint>, teaching: List<ExamTeachingPage>): List<String> {
        if (teaching.isNotEmpty()) return CourseExamPoints.identifyFromPages("coverage", teaching).map { it.title }
        if (path != null && Files.exists(path)) {
            val root = json.parseToJsonElement(Files.readString(path)).jsonObject
            val rows = root["points"] as? JsonArray ?: return bank.map { it.name }
            return rows.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                obj.str("name").ifBlank { null }
            }
        }
        return bank.map { it.name }
    }

    fun readTeaching(path: Path?): List<ExamTeachingPage> {
        if (path == null || !Files.exists(path)) return emptyList()
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val rows = root["pages"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = obj.str("text")
            if (text.isBlank()) return@mapNotNull null
            ExamTeachingPage(
                bookId = obj.str("bookId").ifBlank { "book" },
                page = (obj["page"] as? JsonPrimitive)?.intOrNull ?: 1,
                role = CourseMaterialRole.fromWire(obj.str("role").ifBlank { "CORE_TEXTBOOK" }),
                text = text,
            )
        }
    }

    private fun termIn(name: String, textbookNorm: String, textbookSpaced: String): Boolean {
        val key = CourseExamPoints.normalize(name)
        if (key.length < 2 || textbookNorm.isBlank()) return false
        if (CourseExamPoints.termPattern(key).containsMatchIn(textbookNorm)) return true
        val keySpaced = key.replace('-', ' ')
        return CourseExamPoints.termPattern(keySpaced).containsMatchIn(textbookSpaced)
    }

    private fun bestBankPoint(name: String, bank: List<CoverageBankPoint>): CoverageBankPoint? {
        val targetNorm = normalizedPointKey(name)
        return bank.filter { samePoint(name, it.name) }
            .maxWithOrNull(
                compareBy<CoverageBankPoint>(
                    { it.choices > 0 || it.essay },
                    { normalizedPointKey(it.name) == targetNorm },
                    { normalizedPointKey(it.name).length },
                    { it.choices },
                    { it.essay },
                ),
            )
    }

    private fun normalizedPointKey(name: String): String =
        CourseExamPoints.normalize(name).replace('-', ' ').replace('–', ' ').replace('—', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun contentTokensOf(norm: String): List<String> =
        norm.split(' ').filter { it.isNotEmpty() && it !in HeldOutDecomposer.glueWordSet && it !in HeldOutDecomposer.skipWords }

    fun isUppercaseAcronym(raw: String): Boolean {
        val trimmed = raw.trim().trim('(', ')')
        return trimmed.length in 2..6 &&
            trimmed.any(Char::isUpperCase) &&
            trimmed.all { it.isUpperCase() || it.isDigit() } &&
            !romanNumeral.matches(trimmed) &&
            trimmed.lowercase() !in genericSingleWords
    }

    fun isGenericSingleWord(token: String): Boolean = token in genericSingleWords

    /** Rejects single common words and broad umbrella names so they cannot match or count as covered on their own. */
    fun isGenericPoint(raw: String): Boolean {
        val trimmed = raw.trim()
        val norm = normalizedPointKey(trimmed)
        if (norm.isBlank()) return true
        if (cjkChar.containsMatchIn(norm)) {
            return norm in cjkGenericPoints
        }
        val tokens = norm.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val contentTokens = contentTokensOf(norm)
        if (contentTokens.isEmpty()) return true
        if (contentTokens.size == 1) {
            return !isUppercaseAcronym(trimmed) && contentTokens[0] in genericSingleWords
        }
        val firstContent = contentTokens.first()
        val lastContent = contentTokens.last()
        val tailGeneric = lastContent in umbrellaHeadNouns &&
            contentTokens.dropLast(1).all { it in genericPhraseModifiers }
        val headGeneric = firstContent in umbrellaHeadNouns &&
            contentTokens.drop(1).all { it in genericPhraseModifiers }
        return tailGeneric || headGeneric
    }

    private fun samePoint(left: String, right: String): Boolean {
        if (isGenericPoint(left) || isGenericPoint(right)) return false
        val a = normalizedPointKey(left)
        val b = normalizedPointKey(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val (shortRaw, longRaw) = if (a.length <= b.length) left to right else right to left
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (cjkChar.containsMatchIn(short) || cjkChar.containsMatchIn(long)) {
            if (short.length < 2) return false
            return CourseExamPoints.termPattern(short).containsMatchIn(long)
        }
        val shortTokens = contentTokensOf(short)
        val longTokens = contentTokensOf(long)
        if (shortTokens.isEmpty() || longTokens.isEmpty()) return false
        if (shortTokens.size == 1) {
            if (isUppercaseAcronym(shortRaw)) {
                val acronym = shortRaw.trim().trim('(', ')')
                return "($acronym)" in longRaw || (longTokens.size <= 2 && CourseExamPoints.termPattern(short).containsMatchIn(long))
            }
            return false
        }
        if (short.length < 4) return false
        if (!CourseExamPoints.termPattern(short).containsMatchIn(long)) return false
        if (shortTokens.size * 3 < longTokens.size * 2) return false
        val extraTokens = longTokens.filter { it !in shortTokens }
        if (shortTokens.all { it in genericSingleWords } && extraTokens.any { it !in genericSingleWords }) {
            return false
        }
        return true
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

internal data class DecomposedQuestion(
    val name: String,
    val matched: Boolean,
)

/** Names the point a held-out question is asking. The stem itself is not returned. */
internal object HeldOutDecomposer {
    private val pointLine = Regex("""(?im)^\s*(?:考点|point)\s*[:：]\s*(.{2,80})\s*$""")
    private val explicitQuestionStart = Regex(
        """^\s*(?:(?:question|q)\s*\.?\s*\d{1,3}\b|第\s*\d{1,3}\s*题)(?:\s*[.)、:：])?(?:\s+.*|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val dotQuestionStart = Regex("""^\s*(\d{1,3})\.(?!\d)\s+\S.*$""")
    private val parenQuestionStart = Regex("""^\s*(\d{1,3})[)）、](?!\.)\s+\S.*$""")
    private val numberPrefix = Regex(
        """^\s*(?:(?:question|q)\s*\.?\s*\d{1,3}\s*[.)、:：]?\s*|\d{1,3}\s*[.)、:：]\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val partHeader = Regex(
        """(?i)^\s*(?:(?:part|section)\s+(?:[ivxlcdm]+|[a-z]|\d+|one|two|three|four|five)(?:\s*[:：\-–—.]\s*.*|\s+\(.*\)|\s+(?:multiple\s+choice|short\s+answer|long\s+answer|essay|problem|questions?|true\s*(?:/|or)\s*false|definitions?|calculations?|analysis).*|$)|第\s*[一二三四五六七八九十百\d]+\s*[部分节篇](?:.*)|[一二三四五六七八九十]+[、.]\s*(?:单项选择|多项选择|选择|填空|判断|简答|论述|计算|分析|名词解释|综合)题.*)$""",
    )
    private val instructionsHeader = Regex(
        """(?i)^\s*(?:(?:exam(?:ination)?|general|candidate|important|special|guidance)\s+)?(?:instructions|directions|rules|notes|regulations|information|notice)(?:\s+(?:to|for)\s+(?:candidates|students))?\s*[:：]?\s*$|^\s*(?:read\s+(?:the\s+following\s+)?instructions(?:\s+(?:to|for)\s+(?:candidates|students))?|before\s+you\s+leave|at\s+the\s+end\s+of\s+the\s+exam(?:ination)?)\s*[:：]?\s*$|^\s*(?:考场须知|考生须知|答题须知|注意事项|考试说明|考生守则)\s*[:：]?\s*$""",
    )
    private val coverStartLine = Regex(
        """(?i)^\s*(?:(?:the\s+)?(?:university|college|school|faculty|department|institute)\s+of\s+[^.!?。！？]{2,65}|(?-i:(?:The\s+)?(?:[A-Z][A-Za-z0-9&,'-]+\s+){1,4}(?:University|UNIVERSITY))|(?-i:[A-Z]{2,6}\s*\d{3,5})|(?-i:[A-Z]{2,6}\s*\d{3,5})\b.*(?:exam(?:ination)?|midterm|final|practice|mock|sample|test|paper|quiz)[^?]{0,80}|(?:practice|sample|mock|past|resit|supplementary)\s+(?:midterm\s+|final\s+)?(?:exam(?:ination)?|paper|test)[^?]{0,80}|(?:[a-z]+\s+){0,3}(?:midterm|final|degree|honours|ordinary)\s+examination[^?]{0,80}|(?:january|february|march|april|may|june|july|august|september|october|november|december)\s+20\d{2}(?:\s+(?:exam(?:ination)?|diet|paper))?|(?:time\s+allowed|duration|total\s+marks|candidate\s+(?:number|id)|student\s+(?:id|number|name)|seat\s+number|examination\s+number|exam(?:ination)?\s+date|date\s+of\s+exam(?:ination)?|diet|course\s+code|module\s+code)\s*[:：].*|this\s+(?:exam(?:ination)?|question)\s+paper\s+consists\s+of\b.*|(?:the\s+)?examinations?\s+office\b[^?]{0,60}|academic\s+registry\b[^?]{0,60}|board\s+of\s+examiners\b[^?]{0,60}|={3,}.*|-{3,}\s*(?:paper|exam|midterm|final).*|#\s+.*(?:exam(?:ination)?|midterm|final|paper|practice).*)\s*$""",
    )
    private val instructionLine = Regex(
        """(?i)\b(?:student\s+(?:id|number|name)|candidate\s+number|seat\s+number|examination\s+number|write\s+your\s+(?:name|id|candidate|answers?)|do\s+not\s+(?:open|turn|write|remove|leave)|calculators?\s+(?:are|is)\s+(?:not\s+)?(?:permitted|allowed)|(?:is|are)\s+(?:not\s+)?(?:permitted|allowed)\s+in\s+(?:this|the)\s+exam(?:ination)?|time\s+allowed|this\s+(?:exam(?:ination)?|paper)\s+consists\s+of|answer\s+(?:all|any\s+\d+)\s+questions?|attempt\s+(?:all|any\s+\d+)\s+questions?|marks?\s+will\s+be\s+awarded|credit\s+will\s+be\s+given|show\s+(?:all\s+)?(?:your\s+)?working|read\s+(?:all\s+)?instructions\s+carefully|use\s+(?:black|blue)\s+(?:pen|ink|ballpoint)|(?:closed|open)[\s-]+book|no\s+books|legibly|invigilator|remain\s+seated|examination\s+(?:room|hall)|(?:your|the|separate)\s+answer\s+(?:book|booklet|sheet)s?|(?:mobile\s+phones?|electronic\s+devices?|smartwatches?).*(?:prohibited|not\s+permitted|not\s+allowed|switched\s+off|turned\s+off)|(?:switched\s+off|turned\s+off|prohibited).*(?:mobile\s+phones?|electronic\s+devices?|smartwatches?)|dictionaries\s+(?:are|is)\s+(?:not\s+)?(?:permitted|allowed)|hand\s+in\s+your|when\s+told\s+to\s+stop|stop\s+writing|check\s+that\s+(?:this|the)\s+(?:paper|booklet)|unfair\s+means|academic\s+misconduct)\b|考生须知|考场须知|答题须知|不得携带|禁止使用计算器|请勿翻开|考试时间|共\s*\d+\s*页|满分\s*\d+\s*分|填写姓名|填写学号""",
    )
    private val draftOrBlankLine = Regex(
        """(?i)^\s*\[?\s*(?:this\s+page\s+(?:is\s+)?(?:intentionally\s+)?left\s+blank(?:\s+for\s+rough\s+work)?|intentionally\s+left\s+blank|blank\s+page|rough\s+work|draft\s+page|scrap\s+paper|space\s+for\s+rough\s+work|use\s+this\s+page\s+for\s+rough\s+work|additional\s+space(?:\s+for\s+answers?)?|草稿纸|草稿页|空白页|此页留白)\s*\]?\.?\s*$""",
    )
    private val endOfExamLine = Regex(
        """(?i)^\s*(?:end\s+of\s+(?:exam(?:ination)?|paper|test|questions?)|(?:[-–—*=#]\s*){2,}end\s+of\s+(?:exam(?:ination)?|paper|test)(?:\s*[-–—*=#]){2,}|试卷结束|全卷完|考试结束)\s*\.?\s*$""",
    )
    private val footerLine = Regex(
        """(?i)^\s*(?:page\s*\d+(?:\s*(?:of|/)\s*\d+)?|p\.?\s*\d+|第\s*\d+\s*页(?:\s*(?:/|共)\s*\d+\s*页)?|[-–—]\s*\d+\s*[-–—]|\d{1,3}|(?:please\s+)?turn\s+over|p\.?t\.?o\.?|continued\s+on\s+(?:the\s+)?next\s+page|©\s*.*|copyright\b.*)\s*$|\bpage\s+\d+\s+of\s+\d+\b|(?:please\s+)?turn\s+over\s*$""",
    )
    private val sectionSubtitleLine = Regex(
        """(?i)^\s*(?:\(?total\s*[:：]?\s*\d+\s*marks?\)?|\[?\d+\s*marks?\]?|answer\s+(?:all|any\s+\d+)\s+questions?.*|(?:multiple\s+choice|short\s+answer|long\s+answer|essay|problem)\s*(?:questions?)?(?:\s*\(.*\))?)\s*\.?\s*$""",
    )

    private val sectionNumber = Regex("""(?i)^(?:(?:chapter|section|lecture|week)\s+\d+(?:\.\d+)*|第[一二三四五六七八九十\d]+[章节周]|\d+(?:\.\d+)*)[\s.、:：—-]+""")
    private val definitionLine = Regex("""^([^:：]{2,64})[:：]\s*\S""")
    private val boldSpan = Regex("""\*\*([^*\n]{2,64})\*\*|__([^_\n]{2,64})__""")
    private val inlineDefinition = Regex(
        """(?:^|[.;]\s+)(?:The\s+|An?\s+)?([A-Za-z][A-Za-z0-9 -]{2,48})\s+(?:is\s+defined\s+as|refers\s+to|is\s+called|is\s+known\s+as)\b""",
    )
    private val capitalRun = Regex("""\b[A-Z][A-Za-z0-9]+(?:-[A-Z][A-Za-z0-9]+)*(?:\s+[A-Z][A-Za-z0-9]+(?:-[A-Z][A-Za-z0-9]+)*){1,5}\b""")
    private val lineOptionMarker = Regex(
        """^\s*(?:(\()([A-Fa-f])\)|(\[)([A-Fa-f])\]|([A-Fa-f])([.)、:：]))\s+(\S.*)$""",
    )
    private val subQuestionMarks = Regex("""(?i)\[\s*\d+\s*marks?\s*]|\(\s*\d+\s*marks?\s*\)""")
    private val subQuestionVerbLead = Regex(
        """(?i)^(?:explain|discuss|compare|contrast|define|calculate|compute|describe|derive|prove|show|sketch|draw|plot|identify|evaluate|analyze|analyse|outline|state|list|what|why|how|suppose|consider|assume|comment)\b""",
    )
    private val inlineOptionSpecs = listOf(
        InlineOptionSpec(Regex("""(?:^|(?<=\s))\(([A-F])\)(?=\s+\S)"""), minCount = 2, isLower = false),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))\[([A-F])\](?=\s+\S)"""), minCount = 2, isLower = false),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))([A-F])\)(?=\s+\S)"""), minCount = 2, isLower = false),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))([A-F])\.(?=\s+\S)"""), minCount = 3, isLower = false),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))\(([a-f])\)(?=\s+\S)"""), minCount = 3, isLower = true),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))\[([a-f])\](?=\s+\S)"""), minCount = 3, isLower = true),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))([a-f])\)(?=\s+\S)"""), minCount = 3, isLower = true),
        InlineOptionSpec(Regex("""(?:^|(?<=\s))([a-f])\.(?=\s+\S)"""), minCount = 3, isLower = true),
    )
    private val boilerplate = setOf("contents", "outline", "summary", "references", "overview", "目录", "小结", "参考文献")
    val boilerplateWords: Set<String> get() = boilerplate
    private val glueWords = setOf(
        "of", "in", "on", "at", "to", "for", "and", "or", "the", "a", "an", "with", "by", "as",
        "is", "are", "was", "were", "be", "vs", "via", "per",
    )
    val glueWordSet: Set<String> get() = glueWords
    private val skip = setOf(
        "explain", "discuss", "compare", "contrast", "define", "calculate", "describe", "carefully",
        "what", "why", "how", "does", "do", "can", "could", "would", "should", "the", "and", "for",
        "with", "from", "that", "this", "are", "was", "were", "been", "being", "have", "has", "had",
        "into", "about", "between", "which", "when", "where", "your", "their", "there", "these",
        "those", "using", "used", "make", "makes", "provide", "including", "include", "answer",
        "question", "points", "point", "graph", "graphs", "model", "case", "than", "then", "also",
        "each", "such", "only", "over", "under", "after", "before", "within", "without",
        "suppose", "consider", "assume", "given", "find", "show", "prove", "determine", "identify",
        "state", "list", "briefly", "evaluate", "analyze", "analyse", "illustrate", "derive",
        "compute", "estimate", "justify", "verify", "sketch", "draw", "plot", "figure", "table",
        "chart", "panel", "exhibit", "appendix", "option", "choice", "section", "part", "marks",
        "please", "following", "below", "above", "true", "false", "correct", "incorrect",
    )
    val skipWords: Set<String> get() = skip

    private enum class QuestionStyle { EXPLICIT_Q, DOT, PAREN }

    private data class QuestionMarker(val style: QuestionStyle, val number: Int)

    private data class ExamPage(val lines: List<String>, val resetToFirstPage: Boolean = false)

    private data class ExamLine(
        val raw: String,
        val trimmed: String,
        val pageBoundary: Boolean,
        val resetToFirstPage: Boolean,
    )

    private data class InlineOptionSpec(val regex: Regex, val minCount: Int, val isLower: Boolean)

    private data class LineOption(val letter: Char, val isUpper: Boolean, val style: String, val body: String)

    private data class TermMatcher(
        val title: String,
        val fromIdentified: Boolean,
        val wordCount: Int,
        val length: Int,
        val acronym: String?,
        val prefilter: String,
        val exactRegex: Regex,
        val contentMatchers: List<Pair<String, Regex>>,
    )

    fun questionsFromDocument(text: String): List<String> {
        extractStructuredOriginalQuestions(text)?.let { return it }
        return extractRawExamQuestions(text)
    }

    private fun extractStructuredOriginalQuestions(text: String): List<String>? {
        val held = mutableListOf<String>()
        var capture = false
        var sawOriginal = false
        val buf = StringBuilder()
        fun flush() {
            val question = buf.toString().trim().trim('>', ' ')
            if (question.isNotEmpty() && "原题详解已移除" !in question) held += question
            buf.clear()
        }
        for (line in text.lineSequence()) {
            if (line.startsWith("### ")) {
                if (capture) flush()
                capture = line.removePrefix("### ").trim().startsWith("英文原题")
                if (capture) sawOriginal = true
                continue
            }
            if (capture && line.startsWith("## ")) {
                flush()
                capture = false
                continue
            }
            if (capture) buf.appendLine(line)
        }
        if (capture) flush()
        return if (sawOriginal) held else null
    }

    private fun extractRawExamQuestions(text: String): List<String> {
        val pages = splitExamPages(text)
        val repeatedLines = findRepeatedPageLines(pages)
        val filteredPages = pages.filterNot { isBlankOrDraftPage(it.lines) }
        val flatLines = buildList {
            for (page in filteredPages) {
                var firstOnPage = true
                for (raw in page.lines) {
                    add(
                        ExamLine(
                            raw = raw,
                            trimmed = raw.trim(),
                            pageBoundary = firstOnPage && pages.size >= 2,
                            resetToFirstPage = firstOnPage && page.resetToFirstPage,
                        ),
                    )
                    firstOnPage = false
                }
            }
        }

        val questions = mutableListOf<StringBuilder>()
        var startedQuestions = false
        var sawStructuralMarker = false
        var inInstructions = false
        var inTrailingNoise = false
        var skipSubtitleLines = 0
        var lastInstructionNum = 0
        var currentSectionStyle: QuestionStyle? = null
        var lastQuestionNum = 0
        var prevLineBlank = true

        for ((index, entry) in flatLines.withIndex()) {
            val rawLine = entry.raw
            val trimmed = entry.trimmed
            if (entry.pageBoundary) inTrailingNoise = false
            if (entry.resetToFirstPage) {
                startedQuestions = false
                inInstructions = false
                inTrailingNoise = false
                skipSubtitleLines = 0
                lastInstructionNum = 0
                currentSectionStyle = null
                lastQuestionNum = 0
                prevLineBlank = true
            }
            if (trimmed.isEmpty()) {
                skipSubtitleLines = 0
                prevLineBlank = true
                continue
            }
            if (PRINTED_PAGE_MARK.matches(trimmed) || trimmed in repeatedLines || footerLine.matches(trimmed)) {
                sawStructuralMarker = true
                continue
            }
            if (draftOrBlankLine.matches(trimmed) || endOfExamLine.matches(trimmed)) {
                sawStructuralMarker = true
                inTrailingNoise = true
                continue
            }
            if (partHeader.matches(trimmed)) {
                sawStructuralMarker = true
                inInstructions = false
                inTrailingNoise = false
                skipSubtitleLines = 2
                currentSectionStyle = null
                lastQuestionNum = 0
                prevLineBlank = true
                continue
            }
            if (instructionsHeader.matches(trimmed)) {
                sawStructuralMarker = true
                startedQuestions = false
                inInstructions = true
                inTrailingNoise = false
                lastInstructionNum = 0
                currentSectionStyle = null
                lastQuestionNum = 0
                prevLineBlank = true
                continue
            }
            if (coverStartLine.matches(trimmed)) {
                sawStructuralMarker = true
                startedQuestions = false
                inInstructions = false
                inTrailingNoise = false
                skipSubtitleLines = 0
                lastInstructionNum = 0
                currentSectionStyle = null
                lastQuestionNum = 0
                prevLineBlank = true
                continue
            }

            val marker = parseQuestionMarker(trimmed)
            if (inTrailingNoise) {
                if (marker != null && marker.number == 1 && !looksLikeInstructionSequence(flatLines, index)) {
                    inTrailingNoise = false
                    currentSectionStyle = null
                    lastQuestionNum = 0
                } else {
                    continue
                }
            }

            if (inInstructions) {
                val exitToQuestion = marker != null && !instructionLine.containsMatchIn(trimmed) && (
                    marker.style == QuestionStyle.EXPLICIT_Q ||
                        (lastInstructionNum == 0 && !looksLikeInstructionSequence(flatLines, index)) ||
                        (lastInstructionNum > 0 && marker.number <= lastInstructionNum && !looksLikeInstructionSequence(flatLines, index))
                    )
                if (!exitToQuestion) {
                    if (marker != null) lastInstructionNum = marker.number
                    prevLineBlank = false
                    continue
                }
                inInstructions = false
            }

            if (marker != null && (instructionLine.containsMatchIn(trimmed) ||
                    (marker.number == 1 && (!startedQuestions || lastQuestionNum >= 2) && looksLikeInstructionSequence(flatLines, index)))
            ) {
                sawStructuralMarker = true
                inInstructions = true
                lastInstructionNum = marker.number
                prevLineBlank = false
                continue
            }

            if (skipSubtitleLines > 0) {
                if (marker == null && (sectionSubtitleLine.matches(trimmed) || ('?' !in trimmed && trimmed.length <= 70))) {
                    skipSubtitleLines -= 1
                    prevLineBlank = false
                    continue
                }
                skipSubtitleLines = 0
            }

            if (marker != null && isNewQuestionStart(marker, currentSectionStyle, lastQuestionNum, prevLineBlank)) {
                startedQuestions = true
                sawStructuralMarker = true
                if (currentSectionStyle == null || marker.style == QuestionStyle.EXPLICIT_Q ||
                    (marker.number == 1 && lastQuestionNum >= 2)
                ) {
                    currentSectionStyle = marker.style
                }
                lastQuestionNum = marker.number
                questions += StringBuilder(trimmed)
            } else if (startedQuestions && questions.isNotEmpty()) {
                questions.last().append('\n').append(rawLine.trimEnd())
            }
            prevLineBlank = false
        }

        val parsed = questions.map { it.toString().trim() }.filter { it.length >= 12 }
        if (parsed.isNotEmpty() || sawStructuralMarker) return parsed
        val single = text.trim()
        return if (single.length >= 12 && !draftOrBlankLine.matches(single) && !footerLine.matches(single)) {
            listOf(single)
        } else {
            emptyList()
        }
    }

    private fun looksLikeInstructionSequence(lines: List<ExamLine>, startIndex: Int): Boolean {
        var inspected = 0
        for (i in startIndex until lines.size) {
            if (i > startIndex && lines[i].pageBoundary) break
            val t = lines[i].trimmed
            if (t.isEmpty()) continue
            if (partHeader.matches(t) || coverStartLine.matches(t)) break
            val m = parseQuestionMarker(t)
            if (m != null && m.number > 5 && i > startIndex) break
            if (instructionLine.containsMatchIn(t)) return true
            inspected += 1
            if (inspected >= 15) break
        }
        return false
    }

    private fun splitExamPages(text: String): List<ExamPage> {
        val pages = mutableListOf(mutableListOf<String>() to false)
        for (rawLine in text.lineSequence()) {
            if ('\u000C' in rawLine) {
                val parts = rawLine.split('\u000C')
                for ((idx, part) in parts.withIndex()) {
                    if (idx > 0) pages += mutableListOf<String>() to false
                    if (part.isNotBlank()) pages.last().first += part
                }
                continue
            }
            val mark = PRINTED_PAGE_MARK.matchEntire(rawLine.trim())
            if (mark != null) {
                val pdfPage = Regex("""PDF 第 (\d+) 页""").find(mark.value)?.groupValues?.get(1)?.toIntOrNull()
                val reset = pdfPage == 1 && pages.any { it.first.any(String::isNotBlank) }
                if (pages.last().first.isNotEmpty()) {
                    pages += mutableListOf<String>() to reset
                } else if (reset) {
                    pages[pages.lastIndex] = pages.last().first to true
                }
                continue
            }
            pages.last().first += rawLine
        }
        return pages.filter { (lines, _) -> lines.any(String::isNotBlank) }
            .map { (lines, reset) -> ExamPage(lines, reset) }
    }

    private fun findRepeatedPageLines(pages: List<ExamPage>): Set<String> {
        if (pages.size < 2) return emptySet()
        val counts = mutableMapOf<String, Int>()
        for (page in pages) {
            val nonEmpty = page.lines.map { it.trim() }.filter { it.isNotEmpty() }
            val edgeLines = (nonEmpty.take(2) + nonEmpty.takeLast(2))
                .filter { it.length in 4..100 && parseQuestionMarker(it) == null && !footerLine.matches(it) }
                .toSet()
            for (line in edgeLines) counts[line] = (counts[line] ?: 0) + 1
        }
        return counts.filterValues { it >= 2 }.keys
    }

    private fun isBlankOrDraftPage(page: List<String>): Boolean {
        val nonEmpty = page.map { it.trim() }.filter { it.isNotEmpty() && !footerLine.matches(it) }
        if (nonEmpty.isEmpty()) return true
        val hasDraft = nonEmpty.any { draftOrBlankLine.matches(it) }
        if (!hasDraft) return false
        return nonEmpty.none { parseQuestionMarker(it) != null }
    }

    private fun isNewQuestionStart(
        marker: QuestionMarker,
        currentStyle: QuestionStyle?,
        lastQuestionNum: Int,
        prevLineBlank: Boolean,
    ): Boolean = when (currentStyle) {
        null -> true
        QuestionStyle.EXPLICIT_Q -> marker.style == QuestionStyle.EXPLICIT_Q ||
            (marker.style == QuestionStyle.DOT && marker.number == 1 && lastQuestionNum >= 2 && prevLineBlank)
        QuestionStyle.DOT -> marker.style == QuestionStyle.EXPLICIT_Q ||
            (marker.style == QuestionStyle.DOT && (
                marker.number > lastQuestionNum ||
                    (marker.number == 1 && (lastQuestionNum >= 2 || prevLineBlank))
                ))
        QuestionStyle.PAREN -> marker.style != QuestionStyle.PAREN ||
            marker.number > lastQuestionNum ||
            (marker.number == 1 && (lastQuestionNum >= 2 || prevLineBlank))
    }

    private fun parseQuestionMarker(trimmed: String): QuestionMarker? {
        if (explicitQuestionStart.matches(trimmed)) {
            val num = Regex("""\d{1,3}""").find(trimmed)?.value?.toIntOrNull() ?: return null
            if (num in 1..200) return QuestionMarker(QuestionStyle.EXPLICIT_Q, num)
        }
        dotQuestionStart.matchEntire(trimmed)?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@let
            if (num in 1..200) return QuestionMarker(QuestionStyle.DOT, num)
        }
        parenQuestionStart.matchEntire(trimmed)?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@let
            if (num in 1..200) return QuestionMarker(QuestionStyle.PAREN, num)
        }
        return null
    }

    fun decompose(questions: List<String>): List<String> {
        val names = linkedSetOf<String>()
        for (question in questions) {
            val name = focus(question)
            if (name.length in 2..80) names += name
        }
        return names.toList()
    }

    fun decomposeQuestions(
        questions: List<String>,
        identified: List<String>,
        textbook: String,
    ): List<DecomposedQuestion> {
        val matchers = buildTermMatchers(identified, extractTextbookTerms(textbook))
        val results = mutableListOf<DecomposedQuestion>()
        val seen = mutableSetOf<String>()
        var unmatchedIndex = 0
        for (question in questions) {
            val name = focusOrNull(question, matchers)
            if (name != null && name.length in 2..80) {
                val key = CourseExamPoints.normalize(name).replace('-', ' ')
                if (seen.add(key)) results += DecomposedQuestion(name, matched = true)
            } else {
                unmatchedIndex += 1
                results += DecomposedQuestion("未识别考点 #$unmatchedIndex", matched = false)
            }
        }
        return results
    }

    fun focus(stem: String): String = focusOrNull(stem, emptyList()).orEmpty()

    private fun focusOrNull(stem: String, matchers: List<TermMatcher>): String? {
        pointLine.find(stem)?.let { return clean(it.groupValues[1]) }
        val stemOnly = stripChoiceOptions(stem)
        val body = stemOnly.replace(numberPrefix, "").trim()
        bestTermMatch(body, matchers)?.let { return clean(it) }
        // Question stems open with Explain/Discuss/…. Those words are not the point name.
        val titled = capitalRun.findAll(body)
            .map { stripLeadingSkip(it.value) }
            .filter { it.length in 8..80 && !ExamCoverage.isGenericPoint(it) }
            .maxByOrNull { it.length }
        if (titled != null) return clean(titled)
        return null
    }

    /** Removes multiple-choice option lines/clauses so point naming uses only the stem while keeping essay sub-questions. */
    internal fun stripChoiceOptions(stem: String): String {
        val lines = stem.lines()
        var trimmedStem = stem
        for (i in 1 until lines.size) {
            val first = parseLineOption(lines[i]) ?: continue
            if (first.letter.lowercaseChar() != 'a') continue
            val collected = mutableListOf(first)
            var expected = first.letter + 1
            for (j in (i + 1) until lines.size) {
                val next = parseLineOption(lines[j]) ?: continue
                if (next.isUpper == first.isUpper && next.style == first.style && next.letter == expected) {
                    collected += next
                    expected = next.letter + 1
                } else if (next.letter.lowercaseChar() == 'a') {
                    break
                }
            }
            if (collected.size < 2) continue
            if (!isSubQuestionSequence(first.isUpper, collected, lines.subList(0, i))) {
                trimmedStem = lines.subList(0, i).joinToString("\n").trim()
                break
            }
        }
        return stripInlineChoiceOptions(trimmedStem)
    }

    private fun parseLineOption(line: String): LineOption? {
        val m = lineOptionMarker.matchEntire(line) ?: return null
        val (letterChar, style) = when {
            m.groupValues[2].isNotEmpty() -> m.groupValues[2][0] to "PAREN"
            m.groupValues[4].isNotEmpty() -> m.groupValues[4][0] to "BRACKET"
            else -> m.groupValues[5][0] to "PUNCT_${m.groupValues[6]}"
        }
        return LineOption(letterChar, letterChar.isUpperCase(), style, m.groupValues[7].trim())
    }

    private fun isSubQuestionSequence(
        isUpper: Boolean,
        collected: List<LineOption>,
        precedingLines: List<String>,
    ): Boolean {
        if (isUpper) return false
        if (collected.any { isSubQuestionBody(it.body) }) return true
        if (collected.size == 2) {
            val prevEnd = precedingLines.lastOrNull { it.isNotBlank() }?.trimEnd().orEmpty()
            return prevEnd.lastOrNull() !in setOf('?', '？', ':', '：')
        }
        return false
    }

    private fun isSubQuestionBody(body: String): Boolean =
        '?' in body || '？' in body || subQuestionMarks.containsMatchIn(body) || subQuestionVerbLead.containsMatchIn(body)

    private fun stripInlineChoiceOptions(text: String): String {
        var earliestCut: Int? = null
        for (spec in inlineOptionSpecs) {
            val matches = spec.regex.findAll(text).toList()
            if (matches.size < spec.minCount) continue
            var i = 0
            while (i < matches.size) {
                if (matches[i].groupValues[1].lowercase()[0] != 'a') {
                    i++
                    continue
                }
                val seq = mutableListOf(matches[i])
                var expected = matches[i].groupValues[1][0] + 1
                var j = i + 1
                while (j < matches.size) {
                    val ch = matches[j].groupValues[1][0]
                    if (ch == expected) {
                        seq += matches[j]
                        expected += 1
                    } else if (ch.lowercaseChar() == 'a') {
                        break
                    }
                    j++
                }
                if (seq.size >= spec.minCount) {
                    val hasSubQuestionPrompt = spec.isLower && seq.indices.any { idx ->
                        val start = seq[idx].range.last + 1
                        val end = if (idx + 1 < seq.size) seq[idx + 1].range.first else text.length
                        isSubQuestionBody(text.substring(start, end).trim())
                    }
                    if (!hasSubQuestionPrompt) {
                        val cut = seq.first().range.first
                        if (earliestCut == null || cut < earliestCut) earliestCut = cut
                        break
                    }
                }
                i = j
            }
        }
        return if (earliestCut != null) text.substring(0, earliestCut).trim() else text
    }

    private fun extractTextbookTerms(textbook: String): List<String> {
        if (textbook.isBlank()) return emptyList()
        val terms = linkedMapOf<String, String>()
        fun addTerm(raw: String) {
            val candidate = clean(raw.replace(sectionNumber, ""))
            if (candidate.length !in 2..64) return
            if (candidate.any { it in "。！？!?；;." } || candidate.none(Char::isLetter)) return
            val norm = CourseExamPoints.normalize(candidate).replace('-', ' ')
            if (norm.length < 2 || norm in boilerplate || norm in skip) return
            if (ExamCoverage.isGenericPoint(candidate)) return
            val words = norm.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > 6) return
            if (words.size == 1 && norm.all { it in 'a'..'z' || it in '0'..'9' }) {
                val isAcronym = ExamCoverage.isUppercaseAcronym(candidate)
                if (!isAcronym && norm.length < 4) return
            }
            terms.putIfAbsent(norm, candidate)
        }

        for (rawLine in textbook.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || PRINTED_PAGE_MARK.matches(line)) continue
            when {
                line.startsWith("#") -> addTerm(line.trimStart('#', ' '))
                definitionLine.containsMatchIn(line) -> addTerm(definitionLine.find(line)!!.groupValues[1])
                sectionNumber.containsMatchIn(line) -> addTerm(line)
            }
            for (m in boldSpan.findAll(line)) {
                addTerm(m.groupValues[1].ifEmpty { m.groupValues[2] })
            }
            for (m in inlineDefinition.findAll(line)) {
                addTerm(stripLeadingSkip(m.groupValues[1]))
            }
            for (m in capitalRun.findAll(line)) {
                val stripped = stripLeadingSkip(m.value)
                if (stripped.length >= 4) addTerm(stripped)
            }
        }
        return terms.values.toList()
    }

    private fun buildTermMatchers(identified: List<String>, textbookTerms: List<String>): List<TermMatcher> {
        val matchers = mutableListOf<TermMatcher>()
        val seen = mutableSetOf<String>()
        fun register(rawTitle: String, fromIdentified: Boolean) {
            val trimmed = rawTitle.trim()
            if (trimmed.length < 2) return
            val strippedParen = trimmed.replace(Regex("""\s*\([^)]{1,16}\)\s*$"""), "").trim()
            val variants = if (strippedParen.length >= 2 && strippedParen != trimmed) listOf(trimmed, strippedParen) else listOf(trimmed)
            for (variant in variants) {
                val norm = CourseExamPoints.normalize(variant).replace('-', ' ')
                if (norm.length < 2 || norm in skip || norm in boilerplate || ExamCoverage.isGenericPoint(variant) || !seen.add(norm)) continue
                val tokens = norm.split(' ').filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val isAcronym = tokens.size == 1 && ExamCoverage.isUppercaseAcronym(variant)
                if (tokens.size == 1 && !isAcronym && norm.all { it in 'a'..'z' || it in '0'..'9' } && norm.length < 4) continue
                if (tokens.all { it in skip || it in glueWords }) continue

                val exactRegex = buildPhraseRegex(tokens, norm)
                val lastToken = tokens.last()
                val prefilter = if (lastToken.all { it in 'a'..'z' } && lastToken.length >= 5 && lastToken.endsWith("y") && lastToken[lastToken.length - 2] !in "aeiou") {
                    lastToken.dropLast(1)
                } else {
                    lastToken
                }
                val contentTokens = tokens.filter { token ->
                    (token.length >= 4 || token.any { c -> c !in 'a'..'z' && c !in '0'..'9' }) &&
                        token !in skip && token !in glueWords
                }
                val contentMatchers = if (contentTokens.size >= 2 && contentTokens.any { !ExamCoverage.isGenericSingleWord(it) }) {
                    contentTokens.map { token -> tokenPrefilter(token) to buildPhraseRegex(listOf(token), token) }
                } else {
                    emptyList()
                }
                matchers += TermMatcher(
                    title = rawTitle.trim(),
                    fromIdentified = fromIdentified,
                    wordCount = tokens.size,
                    length = norm.length,
                    acronym = if (isAcronym) variant else null,
                    prefilter = prefilter,
                    exactRegex = exactRegex,
                    contentMatchers = contentMatchers,
                )
            }
        }
        identified.forEach { register(it, fromIdentified = true) }
        textbookTerms.forEach { register(it, fromIdentified = false) }
        return matchers
    }

    private fun tokenPrefilter(token: String): String =
        if (token.all { it in 'a'..'z' } && token.length >= 5 && token.endsWith("y") && token[token.length - 2] !in "aeiou") {
            token.dropLast(1)
        } else {
            token
        }

    private fun buildPhraseRegex(tokens: List<String>, norm: String): Regex {
        val last = tokens.last()
        val lastPattern = when {
            last.all { it in 'a'..'z' } && last.length >= 5 && last.endsWith("y") && last[last.length - 2] !in "aeiou" ->
                Regex.escape(last.dropLast(1)) + "(?:y|ies)"
            last.all { it in 'a'..'z' } && last.length >= 4 &&
                (last.endsWith("s") || last.endsWith("x") || last.endsWith("z") || last.endsWith("ch") || last.endsWith("sh")) ->
                Regex.escape(last) + "(?:es)?"
            last.all { it in 'a'..'z' } && last.length >= 4 ->
                Regex.escape(last) + "s?"
            else -> Regex.escape(last)
        }
        val bodyPattern = if (tokens.size == 1) {
            lastPattern
        } else {
            tokens.dropLast(1).joinToString("""\s+""") { Regex.escape(it) } + """\s+""" + lastPattern
        }
        val start = if (norm.first() in 'a'..'z') "(?<![a-z0-9])" else ""
        val end = if (norm.last() in 'a'..'z') "(?![a-z0-9])" else ""
        return Regex(start + bodyPattern + end)
    }

    private fun bestTermMatch(body: String, matchers: List<TermMatcher>): String? {
        if (matchers.isEmpty() || body.isBlank()) return null
        val bodySearch = CourseExamPoints.normalize(body).replace('-', ' ')
        var bestTitle: String? = null
        var bestScore = -1
        for (matcher in matchers) {
            val tier = when {
                matcher.acronym != null -> {
                    val found = matcher.acronym in body &&
                        Regex("""(?<![A-Za-z0-9])${Regex.escape(matcher.acronym)}(?![A-Za-z0-9])""").containsMatchIn(body)
                    if (found) 2 else 0
                }
                matcher.prefilter in bodySearch && matcher.exactRegex.containsMatchIn(bodySearch) -> 2
                matcher.contentMatchers.isNotEmpty() &&
                    matcher.contentMatchers.all { (pre, regex) -> pre in bodySearch && regex.containsMatchIn(bodySearch) } -> 1
                else -> 0
            }
            if (tier == 0) continue
            val score = tier * 100_000 + matcher.wordCount * 1_000 + matcher.length * 2 + if (matcher.fromIdentified) 1 else 0
            if (score > bestScore) {
                bestScore = score
                bestTitle = matcher.title
            }
        }
        return bestTitle
    }

    private fun stripLeadingSkip(run: String): String =
        run.trim().split(Regex("""\s+""")).dropWhile { it.lowercase() in skip }.joinToString(" ")

    private fun clean(raw: String): String = raw.trim().trim('.', ',', ';', ':', '。', '，').take(80)
}
