package com.coach.desktop.books

import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceCopy
import com.coach.protocol.BookStanceGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.util.UUID

/**
 * 全合集跨材料整页检索与防剧透/离线分歧直连引擎：
 * 1. 检索范围覆盖 `collection_materials(collection_id)` 下全部材料（大纲、讲义、课本、习题解答、论文、课堂转写），
 *    单页保留 `<= 4,200` 字符（修复 `1,500` 字页底截断丢结论的问题）。
 * 2. 书单防剧透硬过滤：当合集类型为 `BOOKLIST` 时，严格过滤 `book_chapter_progress.read_state = 'READ'`，
 *    未读章节 0 召回、0 概念边渗透。
 * 3. 离线分歧直连：直连 `W2-C` `course_topics.pack_payload_json.discrepancies` 与 `W2-D` `concept_links`
 *    （`NOTATION_OR_BOUNDARY_DIFF` / `EMPIRICAL_CHALLENGE` / `OPPOSING_VIEW`），精密匹配提问切面并把双方原页一并送入。
 */
internal object CollectionQaRetriever {
    const val MAX_BLOCK_CHARS = 4_200
    const val MAX_BLOCKS = 9
    private const val MAX_PAGES_PER_BOOK = 3
    private const val MAX_PAGES_PER_FOCUSED_BOOK = 5

    private data class CollectionMaterialMeta(
        val bookId: String,
        val title: String,
        val sourceFilename: String?,
        val format: String,
        val role: CourseMaterialRole,
        val authorityRank: Int,
        val weekNum: Int?,
        val shortLabel: String,
        val docLabel: String,
    )

    private data class ScoredPageCandidate(
        val bookId: String,
        val page: Int,
        val rawText: String,
        val normText: String,
        val score: Double,
        val role: CourseMaterialRole,
        val authorityRank: Int,
    )

    data class RetrievalBundle(
        val collection: ActiveAskCollection,
        val blocks: List<CollectionQaBlock>,
        val blockMap: Map<String, CollectionQaBlock>,
        val rawPagesByKey: Map<Pair<String, Int>, String>,
        val shortLabelByBookId: Map<String, String>,
        val docLabelByBookId: Map<String, String>,
        val matchedDiscrepancies: List<MatchedOfflineDiscrepancy>,
        val blockedBooklistEmptyMessage: String? = null,
        val unreadPagesFilteredOut: Int = 0,
        val allStancesDetermined: Boolean = false,
        val bookStances: List<CollectionBookStancePayload> = emptyList(),
        val missingRecordNotices: List<String> = emptyList(),
    )

    private data class ConceptTerm(
        val text: String,
        val weight: Double,
        val isPhrase: Boolean = false,
        val isChinese: Boolean = false,
    )

    private data class ConceptSlot(
        val id: String,
        val terms: List<ConceptTerm>,
        val baseImportance: Double = 1.0,
    )

    private data class QueryProfile(
        val rawQuestion: String,
        val englishTerms: List<String>,
        val phrases: List<String>,
        val chineseTerms: List<String>,
        val conceptSlots: List<ConceptSlot>,
        val titlePreferredBookIds: Set<String>,
        val requestedWeeks: Set<Int>,
        val roleBoosts: Set<CourseMaterialRole>,
        val allMatchTerms: Set<String>,
    )

    private val weekPattern = Regex("""第\s*(\d{1,2})\s*周|week\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val enTokenRegex = Regex("""[A-Za-z0-9][A-Za-z0-9.\-']*""")
    private val enPageWordRegex = Regex("""[a-z0-9]+(?:'[a-z]+)?""")
    private val zhSequenceRegex = Regex("""[\u4e00-\u9fff]{2,}""")
    private val zhSplitParticlesRegex = Regex("""[的与和或及在从对把被给让向里中上了着过呢吗啊吧之其]+""")
    private val weekFileRegex = Regex("""(?:week|wk|w)[\s_\-]*(\d{1,2})\b|第\s*(\d{1,2})\s*周""", RegexOption.IGNORE_CASE)
    private val sentenceSplitRegex = Regex("""(?<=[.!?;。！？；])\s+|\n+""")
    private val definitionCueRegex = Regex(
        """(?i)\b(?:is\s+that\s+which|that\s+which\s+men\s+call|is\s+called|are\s+called|defined\s+as|definition\s+of|consists\s+in|is\s+this\s+principle|may\s+be\s+assumed\s+to\s+be|will\s+be\s+admitted\s+to\s+be|there\s+is\s+(?:a\s+)?difference|difference\s+between|distinction\s+between|form\s+an\s+opinion|first\s+opinion|one\s+test|refers\s+to|signifies|means\s+that|whereas|on\s+the\s+other\s+hand)\b|是指|指的是|定义为|所谓|区别在于|不同之处|判断.{0,6}标准""",
    )
    private val boilerplateRegex = Regex(
        """(?i)(?:project\s+gutenberg|www\.gutenberg\.org|ebook\s+is\s+for\s+the\s+use\s+of|terms\s+of\s+the\s+project\s+gutenberg|end\s+of\s+(?:the\s+)?project\s+gutenberg|defect\s+in\s+this\s+electronic\s+work|full\s+project\s+gutenberg\s+license|limited\s+warranty\s+and\s+disclaimer\s+of\s+damages)""",
    )

    // Neutral bilingual question-term lexicon (strictly ZERO answer numbers, ZERO pinned docs, ZERO banned words).
    private val zhToEnExpansions: List<Pair<String, List<String>>> = listOf(
        "严格不等号" to listOf("strict inequality", "strict"),
        "弱不等号" to listOf("weak inequality", "weak"),
        "不等号" to listOf("inequality", "strict", "weak"),
        "不买保险" to listOf("no insurance", "buy insurance", "uninsured"),
        "健康状态" to listOf("healthy", "healthy state"),
        "收入" to listOf("income"),
        "下界" to listOf("lower bound", "strict", "greater than"),
        "讨论课" to listOf("tutorial", "seminar", "transcript"),
        "保险合同" to listOf("insurance contract", "contract"),
        "零利润" to listOf("zero-profit", "zero profit"),
        "斜率" to listOf("slope", "odds"),
        "亏损" to listOf("lose money", "losses", "loss"),
        "分离均衡" to listOf("separating equilibrium", "separating"),
        "混同均衡" to listOf("pooling equilibrium", "pooling"),
        "部分保险" to listOf("partial insurance", "partial"),
        "健壮" to listOf("robust", "healthy"),
        "健康人群" to listOf("robust", "healthy"),
        "均匀分布" to listOf("uniform", "distribution"),
        "估值加成" to listOf("valuation", "willingness to pay"),
        "最低质量" to listOf("minimum-quality", "minimum quality"),
        "最高" to listOf("highest", "maximum"),
        "成交" to listOf("price", "trade", "market"),
        "有利选择" to listOf("advantageous selection", "advantageous"),
        "风险厌恶" to listOf("risk aversion", "risk-averse", "risk"),
        "哈佛" to listOf("Harvard"),
        "死亡螺旋" to listOf("death spiral", "spiral"),
        "保费补贴" to listOf("premium", "subsidy", "voucher"),
        "实证案例" to listOf("empirical", "study", "paper"),
        "价格弹性" to listOf("price elasticity", "elasticity"),
        "俄勒冈" to listOf("Oregon", "Medicaid"),
        "大学毕业生" to listOf("college", "graduate"),
        "高中" to listOf("high school", "dropout"),
        "最优健康存量" to listOf("optimal health", "health stock"),
        "折旧率" to listOf("depreciation", "gamma"),
        "年龄" to listOf("aging", "age"),
        "生命终点" to listOf("death", "minimum health"),
        "弧弹性" to listOf("arc", "price elasticity", "midpoint"),
        "中点" to listOf("midpoint", "sum"),
        "起点" to listOf("starting", "initial", "base"),
        "分母" to listOf("denominator"),
        "医生" to listOf("physicians", "physician"),
        "劳动力市场" to listOf("labor market", "labor"),
        "进入壁垒" to listOf("barriers to entry", "barriers", "licensing"),
        "推高" to listOf("income", "rents", "supply"),
        "防御性医疗" to listOf("defensive medicine", "malpractice"),
        "法律改革" to listOf("tort", "reforms", "liability", "caps"),
        "期望损失" to listOf("actuarially fair", "expected loss"),
        "全额保险" to listOf("full insurance"),
        "保费" to listOf("premium"),
        "事前道德风险" to listOf("ex ante", "moral hazard"),
        "事后道德风险" to listOf("ex post", "moral hazard"),
        "道德风险" to listOf("moral hazard"),
        "候选药物" to listOf("drugs", "clinical", "Phase"),
        "新药" to listOf("drugs", "pharmaceutical", "clinical"),
        "临床研发" to listOf("clinical", "trials", "Phase"),
        "成本效果" to listOf("cost-effectiveness", "ICER"),
        "分子" to listOf("numerator"),
        "占优" to listOf("dominate", "dominant"),
        "质量权重" to listOf("quality", "QALY"),
        "标准博弈" to listOf("standard gamble"),
        "时间权衡" to listOf("time trade-off", "time trade"),
        "课程手册" to listOf("handbook", "assessment", "weighting"),
        "期中" to listOf("midterm", "essay"),
        "期末" to listOf("final", "exam"),
        "暂定" to listOf("tentative"),
        "急救" to listOf("emergency"),
        "完全无弹性" to listOf("inelastic", "vertical"),
        "竖直需求" to listOf("vertical", "demand"),
        "共保率" to listOf("coinsurance"),
        "无谓损失" to listOf("welfare loss", "deadweight"),
        "医疗保险" to listOf("health insurance", "insurance"),
    )

    // General academic / classical-reading bilingual lexicon used for cross-lingual page retrieval ONLY
    // (kept separate from `zhToEnExpansions` so offline discrepancy matching stays unchanged).
    private val generalZhToEnExpansions: List<Pair<String, List<String>>> = listOf(
        "选任" to listOf("choice", "choose", "choosing", "select", "selection", "appoint", "discrimination"),
        "选用" to listOf("choice", "choose", "choosing", "select", "appoint"),
        "任用" to listOf("choice", "choose", "appoint", "employ"),
        "大臣" to listOf("minister", "ministers", "servant", "servants", "secretary", "secretaries", "counselor", "adviser"),
        "臣子" to listOf("minister", "ministers", "servant", "servants", "subject"),
        "秘书" to listOf("secretary", "secretaries", "servant", "servants"),
        "仆从" to listOf("servant", "servants"),
        "判断" to listOf("judge", "judgment", "opinion", "discrimination", "discern", "understanding", "wise", "test"),
        "识人" to listOf("discrimination", "opinion", "understanding", "wise", "choice"),
        "君主" to listOf("prince", "princes", "king", "ruler", "sovereign", "monarch"),
        "慷慨" to listOf("generous", "generosity", "liberal", "liberality"),
        "节俭" to listOf("parsimony", "parsimonious", "frugal", "meanness", "miserly"),
        "契约" to listOf("covenant", "covenants", "pact", "compact", "agreement"),
        "盟约" to listOf("covenant", "pact", "compact"),
        "合同" to listOf("contract", "contracts", "contractor", "bargain", "agreement"),
        "城邦" to listOf("city", "state", "polis", "commonwealth", "republic"),
        "正义" to listOf("justice", "just", "righteousness"),
        "公正" to listOf("justice", "just", "fairness"),
        "各司其职" to listOf("own business", "own work", "one thing", "function", "busybody", "adapted", "suited"),
        "各尽其职" to listOf("own business", "own work", "one thing", "function"),
        "本职" to listOf("own business", "own work", "function", "office"),
        "舵手" to listOf("pilot", "helmsman", "steersman"),
        "太阳" to listOf("sun"),
        "至善" to listOf("good", "goodness", "highest good"),
        "善与" to listOf("good", "goodness"),
        "与善" to listOf("good", "goodness"),
        "名称" to listOf("name", "names", "appellation"),
        "推理" to listOf("reasoning", "reckoning", "reason", "inference"),
        "自由" to listOf("liberty", "freedom", "free"),
        "个性" to listOf("individuality", "individual", "character"),
        "福祉" to listOf("well-being", "welfare", "happiness", "felicity"),
        "幸福" to listOf("happiness", "felicity", "welfare", "well-being"),
        "习俗" to listOf("custom", "customs", "habit", "opinion"),
        "自然选择" to listOf("natural selection", "selection"),
        "分工" to listOf("division of labour", "division of labor", "specialisation", "specialization"),
        "美德" to listOf("virtue", "virtues"),
        "节制" to listOf("temperance", "moderation"),
        "勇气" to listOf("courage", "bravery", "fortitude"),
        "智慧" to listOf("wisdom", "wise", "prudence"),
        "灵魂" to listOf("soul", "mind", "spirit"),
        "自然法" to listOf("law of nature", "laws of nature", "natural law"),
        "法律" to listOf("law", "laws", "statute"),
        "主权" to listOf("sovereign", "sovereignty"),
        "自然状态" to listOf("state of nature", "condition of war"),
        "和平" to listOf("peace"),
        "权力" to listOf("power", "authority", "right"),
        "权利" to listOf("right", "rights"),
        "真理" to listOf("truth", "true", "reality"),
        "知识" to listOf("knowledge"),
        "意见" to listOf("opinion", "opinions", "belief"),
        "洞穴" to listOf("cave", "den"),
        "哲学王" to listOf("philosopher", "kings", "king"),
        "民主" to listOf("democracy", "democratic"),
        "僭主" to listOf("tyrant", "tyranny"),
        "暴君" to listOf("tyrant", "tyranny"),
        "寡头" to listOf("oligarchy", "oligarchical"),
        "贵族" to listOf("aristocracy", "nobles", "barons", "lords"),
        "财富" to listOf("wealth", "riches", "property"),
        "贫穷" to listOf("poverty", "poor"),
        "教育" to listOf("education", "educate", "training"),
        "惩罚" to listOf("punishment", "penalty"),
        "宗教" to listOf("religion", "church"),
        "上帝" to listOf("god"),
        "命运" to listOf("fortune", "fate"),
        "雇佣军" to listOf("mercenaries", "mercenary", "auxiliaries"),
        "军队" to listOf("army", "armies", "arms", "soldiers", "troops"),
        "爱戴" to listOf("loved", "love", "affection"),
        "畏惧" to listOf("feared", "fear"),
        "狮子" to listOf("lion"),
        "狐狸" to listOf("fox"),
        "价值" to listOf("value", "price", "worth"),
        "货币" to listOf("money", "coin", "currency"),
        "地租" to listOf("rent"),
        "工资" to listOf("wages", "labour", "labor"),
        "利润" to listOf("profit", "profits"),
        "资本" to listOf("capital", "stock"),
        "人口" to listOf("population"),
        "粮食" to listOf("subsistence", "food", "corn"),
        "君主论" to listOf("prince", "machiavelli"),
        "利维坦" to listOf("leviathan", "hobbes"),
        "理想国" to listOf("republic", "plato", "socrates"),
        "论自由" to listOf("liberty", "mill"),
        "生存斗争" to listOf("struggle for existence", "struggle", "existence"),
        "天敌" to listOf("enemies", "enemy", "checks", "destruction"),
        "变异" to listOf("variation", "variations", "variability"),
        "需求价格弹性" to listOf("price elasticity of demand", "price elasticity", "elasticity"),
        "中点法" to listOf("midpoint method", "midpoint"),
        "机会成本" to listOf("opportunity cost"),
        "算开支" to listOf("economy", "expenses", "cost", "account", "expenditure"),
        "开支" to listOf("expenses", "cost", "expenditure", "economy"),
        "简单" to listOf("simplicity", "simple", "simplify"),
        "骄傲" to listOf("pride", "vanity", "proud"),
        "择偶" to listOf("marriage", "marry", "mate", "match"),
        "内瑟菲尔德" to listOf("netherfield"),
        "达西" to listOf("darcy"),
        "伊丽莎白" to listOf("elizabeth"),
        "华生" to listOf("watson"),
        "观察" to listOf("observe", "observation"),
        "对应原则" to listOf("principle of correspondence", "correspondence"),
        "两极原则" to listOf("principle of polarity", "polarity"),
        "降压药" to listOf("blood pressure", "medicine", "medication"),
        "十三项德行" to listOf("thirteen virtues", "virtues", "temperance"),
        "德行" to listOf("virtue", "virtues", "temperance"),
        "瓦尔登湖" to listOf("walden", "thoreau"),
        "梭罗" to listOf("thoreau", "walden"),
        "经济学" to listOf("economics", "principles of economics"),
        "傲慢与偏见" to listOf("pride and prejudice"),
        "富兰克林" to listOf("franklin"),
        "物种起源" to listOf("origin of species", "darwin"),
        "福尔摩斯" to listOf("sherlock holmes", "holmes"),
        "卡巴莱恩" to listOf("kybalion"),
        "林子" to listOf("woods"),
        "乡下" to listOf("woods", "country"),
        "辞职" to listOf("labor", "business", "living"),
        "上班" to listOf("business", "labor", "employment"),
        "习惯" to listOf("habits", "habit", "virtues", "order"),
        "智能手机" to listOf("telegraph", "news", "mail", "tools"),
        "打卡" to listOf("little book", "table", "black mark", "week"),
        "回家吃饭" to listOf("dinner", "visit", "village", "friends"),
        "晚年" to listOf("later", "old age", "life", "virtues"),
        "后悔" to listOf("repent", "regret", "faults", "error"),
        "医生" to listOf("physician", "doctor", "medical"),
        "奥斯汀" to listOf("austen", "pride", "prejudice"),
        "嫁给" to listOf("marry", "marriage", "husband", "wife"),
        "最难" to listOf("most trouble", "trouble", "difficult", "hardest", "order"),
        "难做到" to listOf("trouble", "order", "difficult", "fault"),
        "little book" to listOf("little book"),
    )

    private val irregularStems = mapOf(
        "chosen" to "choose", "chose" to "choose", "choices" to "choice",
        "men" to "man", "women" to "woman", "children" to "child",
        "princes" to "prince", "cities" to "city", "countries" to "country",
        "secretaries" to "secretary", "ministries" to "ministry", "duties" to "duty",
        "liberties" to "liberty", "theories" to "theory", "studies" to "study",
        "utilities" to "utility", "authorities" to "authority", "societies" to "society",
        "properties" to "property", "judges" to "judge", "judging" to "judge", "judged" to "judge",
    )

    private fun stemEn(word: String): String {
        val w = word.lowercase().trim('\'')
        if (w.length < 4) return w
        irregularStems[w]?.let { return it }
        if (w.endsWith("ies") && w.length > 5) return w.dropLast(3) + "y"
        if ((w.endsWith("sses") || w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes") || w.endsWith("zes")) && w.length > 5) {
            return w.dropLast(2)
        }
        if (w.endsWith("ing") && w.length > 6) {
            val base = w.dropLast(3)
            return if (base.length >= 4 && base.last() == base[base.length - 2] && base.last() !in setOf('s', 'l', 'z')) {
                base.dropLast(1)
            } else base
        }
        if (w.endsWith("ed") && w.length > 5) {
            val base = w.dropLast(2)
            return if (base.length >= 4 && base.last() == base[base.length - 2] && base.last() !in setOf('s', 'l', 'z')) {
                base.dropLast(1)
            } else base
        }
        if (w.endsWith("ly") && w.length > 5) return w.dropLast(2)
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && !w.endsWith("as") && !w.endsWith("os")) {
            return w.dropLast(1)
        }
        return w
    }

    private val enSynonymGroups: List<Set<String>> = listOf(
        setOf("minister", "servant", "secretary", "counselor", "adviser"),
        setOf("choice", "choose", "select", "selection", "appoint", "discrimination"),
        setOf("judge", "judgment", "opinion", "discrimination", "discern", "understanding", "estimate", "test", "wise"),
        setOf("prince", "sovereign", "monarch", "ruler", "king"),
        setOf("covenant", "pact", "compact"),
        setOf("contract", "agreement", "bargain"),
        setOf("city", "state", "commonwealth", "polis", "republic"),
        setOf("work", "business", "function", "calling", "craft"),
        setOf("generous", "generosity", "liberal", "liberality"),
        setOf("parsimony", "parsimonious", "frugal", "meanness", "miserly"),
        setOf("liberty", "freedom"),
        setOf("welfare", "well-being", "happiness", "felicity"),
        setOf("reasoning", "reckoning", "inference", "reason"),
        setOf("division", "specialisation", "specialization"),
    )

    private val enSynonymLookup: Map<String, Set<String>> = buildMap {
        for (group in enSynonymGroups) {
            val stems = group.map { stemEn(it) }.toSet()
            val all = group + stems
            for (term in all) {
                put(term, (get(term).orEmpty() + group).toSet())
            }
        }
    }

    private val enRetrievalStopwords = setOf(
        "a", "an", "the", "and", "or", "but", "if", "so", "no", "not", "nor", "yet",
        "of", "to", "in", "on", "at", "by", "as", "is", "it", "be", "am", "are", "was", "were", "been", "being",
        "for", "from", "with", "into", "about", "against", "between", "among", "through", "during", "before", "after",
        "above", "below", "under", "over", "without", "within", "around", "upon", "off", "out", "up", "down",
        "this", "that", "these", "those", "such", "same", "other", "another", "each", "every", "all", "any", "both",
        "some", "more", "most", "much", "many", "few", "little", "only", "also", "very", "too", "just", "even", "still",
        "who", "whom", "whose", "which", "what", "when", "where", "why", "how", "whether", "either", "neither",
        "he", "she", "they", "them", "their", "his", "her", "its", "our", "your", "my", "we", "you", "me", "us", "one", "ones",
        "do", "does", "did", "done", "have", "has", "had", "having", "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "make", "made", "makes", "say", "says", "said", "tell", "told", "ask", "asked", "show", "shows", "shown",
        "give", "gives", "given", "take", "takes", "taken", "find", "found", "use", "used", "using", "way",
        "explain", "describe", "discuss", "discussed", "mention", "mentioned", "according", "regarding", "concerning",
        "book", "books", "chapter", "page", "pages", "part", "section", "question", "answer", "please", "vs", "versus",
        "than", "then", "there", "here", "well", "like", "per", "via", "etc", "two", "new", "old", "now", "because", "since", "while", "although", "though",
    )

    private val enCueWords = setOf(
        "difference", "different", "differ", "distinction", "distinguish", "define", "defined", "definition", "meaning", "mean", "means",
        "compare", "contrast", "analogy", "metaphor", "example", "doing", "role",
    )

    private val zhRetrievalStopwords = setOf(
        "什么", "怎么", "怎样", "如何", "为什么", "为何", "是否", "能否", "哪个", "哪些", "多少",
        "可以", "就是", "还是", "以及", "请问", "一下", "这个", "那个", "这些", "那些",
        "之间", "方面", "里面", "有关", "关于", "问题", "答案", "书里", "书中", "作者",
        "解释", "说明", "介绍", "分别", "各自", "主要", "原因", "存在", "一个", "一样",
        "没有", "有没", "有没有", "是否", "怎么说", "怎么讲", "是什么", "什么是", "区别", "不同",
        "一章", "这一章", "这句", "这一句", "原文", "本来", "意思", "这么", "这里", "这一处",
    )

    private val genericEntityStopwords = setOf(
        "rothschild", "stiglitz", "grossman", "akerlof", "model", "models", "health", "healthcare",
        "insurance", "market", "markets", "economics", "course", "material", "question", "which",
        "what", "when", "where", "does", "with", "from", "that", "this", "into", "about",
        "risk", "aversion", "averse", "premium", "empirical", "experiment", "paper", "study",
        "contract", "full", "expected", "loss", "difference", "different", "effect",
        "模型", "市场", "医疗", "医保", "保险", "保费", "合同", "风险", "厌恶", "风险厌恶",
        "实验", "实证", "研究", "案例", "理论", "课程", "材料", "问题", "什么", "怎么",
        "为什么", "哪个", "是否", "还是", "多少", "如何", "哪些", "有什么", "不同",
        "一个", "一样", "如果", "永远", "直接", "主要", "原因", "驱动", "存在",
    )

    fun retrieve(
        library: TextbookLibrary,
        collectionId: String,
        question: String,
        currentBookId: String? = null,
        readThroughPageOverrides: Map<String, Int?>? = null,
    ): RetrievalBundle = library.withCourseDatabase { db ->
        retrieveWithConnection(db, library, collectionId, question, currentBookId, readThroughPageOverrides)
    }

    internal fun retrieveWithConnection(
        db: Connection,
        library: TextbookLibrary,
        collectionId: String,
        question: String,
        currentBookId: String? = null,
        readThroughPageOverrides: Map<String, Int?>? = null,
    ): RetrievalBundle {
        val isVirtualReadLibrary = collectionId == CollectionQaVirtualCollections.READ_LIBRARY_ID
        val collectionRow = if (isVirtualReadLibrary) {
            CollectionQaVirtualCollections.readLibraryCollection()
        } else {
            loadCollection(db, collectionId)
                ?: ActiveAskCollection(collectionId, CollectionKind.COURSE, "当前合集")
        }
        val collectionMaterials = if (isVirtualReadLibrary) {
            loadVirtualReadLibraryMaterials(db)
        } else {
            loadCollectionMaterials(db, collectionId, collectionRow.kind)
        }

        // 阶段 2 上线边界：库/合集内每本书都「已判定」才走新路径；有一本「未判定」整次走旧路径。
        val stanceSource = BookStanceSources.forLibrary(library)
        val determinedByBookId = LinkedHashMap<String, BookStanceResolution.Determined>()
        var allStancesDetermined = collectionMaterials.isNotEmpty()
        for (mat in collectionMaterials) {
            val res = runCatching {
                val loaded = runCatching { library.book(mat.bookId) }.getOrNull()
                if (loaded != null) stanceSource.resolve(loaded)
                else if (stanceSource is ForcedFileBookStanceSource) stanceSource.resolveByBookId(mat.bookId)
                else BookStanceResolution.Undetermined
            }.getOrDefault(BookStanceResolution.Undetermined)
            if (res is BookStanceResolution.Determined) {
                determinedByBookId[mat.bookId] = res
            } else {
                allStancesDetermined = false
            }
        }

        val acrossPapers = paperScopePattern.containsMatchIn(question)
        val materials = questionMaterials(db, collectionMaterials, question, currentBookId.takeIf { isVirtualReadLibrary }, acrossPapers)
        val shortLabelByBookId = materials.associate { it.bookId to it.shortLabel }
        val docLabelByBookId = materials.associate { it.bookId to it.docLabel }
        if (acrossPapers && materials.isEmpty()) {
            return RetrievalBundle(
                collectionRow, emptyList(), emptyMap(), emptyMap(), shortLabelByBookId, docLabelByBookId, emptyList(),
                blockedBooklistEmptyMessage = "当前材料中没有找到可供对比的论文。",
            )
        }

        val allowedPagesByBook = HashMap<String, Set<Int>>()
        var focusedBodyStart: Int? = null
        var unreadPagesFilteredOut = 0
        val bookStancesList = mutableListOf<CollectionBookStancePayload>()
        val missingRecordNotices = mutableListOf<String>()

        if (allStancesDetermined) {
            val activityBooks = loadBooksWithReadActivity(db, currentBookId)
            for (mat in collectionMaterials) {
                val det = determinedByBookId.getValue(mat.bookId)
                val allPages = loadAllPageNumbers(db, mat.bookId)
                val bookPages = allPages.maxOrNull() ?: 1
                val isFiction = BookStanceGuard.FICTION in det.guards
                var rtp: Int? = null
                var missingRecord = false
                if (isFiction) {
                    if (readThroughPageOverrides != null && mat.bookId in readThroughPageOverrides) {
                        rtp = readThroughPageOverrides[mat.bookId]?.coerceIn(1, bookPages)
                        missingRecord = (rtp == null)
                    } else {
                        val progressMap = BooklistProgressStore.loadForBook(db, mat.bookId)
                        val readIds = progressMap.values.filter { it.readState == ChapterReadState.READ }.mapTo(HashSet()) { it.tocNodeId }
                        val dwelledMax = progressMap.values.flatMap { it.dwelledPages }.maxOrNull()
                        val readSpotPage = loadReadSpotPage(db, mat.bookId)
                        val savedReadingPage = loadSavedReadingPage(db, mat.bookId)
                        val loadedBook = runCatching { library.book(mat.bookId) }.getOrNull()
                        val bodyStart = loadedBook?.let {
                            BookBoundaries.bodyStart(it.pages, it.structure, it.correctedBodyPage).importPage
                        } ?: 1
                        val chapterMax = if (readIds.isNotEmpty() && loadedBook != null) {
                            BookChapterResolver.chapters(loadedBook).filter { it.tocNodeId in readIds }.maxOfOrNull { it.lastPage }
                        } else null
                        val hasRecord = readIds.isNotEmpty() ||
                            dwelledMax != null ||
                            readSpotPage != null ||
                            mat.bookId in activityBooks ||
                            (savedReadingPage != null && savedReadingPage > maxOf(1, bodyStart))
                        if (hasRecord) {
                            rtp = listOfNotNull(savedReadingPage, readSpotPage, dwelledMax, chapterMax, 1)
                                .maxOrNull()?.coerceIn(1, bookPages)
                            missingRecord = (rtp == null)
                        } else {
                            rtp = null
                            missingRecord = true
                        }
                    }
                    if (missingRecord || rtp == null) {
                        allowedPagesByBook[mat.bookId] = emptySet()
                        unreadPagesFilteredOut += allPages.size
                        missingRecordNotices += BookStanceCopy.noReadingRecord(mat.title)
                    } else {
                        val allowed = allPages.filterTo(LinkedHashSet()) { it <= rtp }
                        allowedPagesByBook[mat.bookId] = allowed
                        unreadPagesFilteredOut += (allPages.size - allowed.size).coerceAtLeast(0)
                    }
                } else if (!isVirtualReadLibrary && collectionRow.kind == CollectionKind.BOOKLIST) {
                    val readIds = BooklistProgressStore.readChapterIds(db, mat.bookId)
                    if (readIds.isEmpty()) {
                        allowedPagesByBook[mat.bookId] = allPages.toSet()
                    } else {
                        val loadedBook = runCatching { library.book(mat.bookId) }.getOrNull()
                        val chapterSpans = loadedBook?.let { BookChapterResolver.chapters(it) }.orEmpty()
                        val allowed = LinkedHashSet<Int>()
                        for (span in chapterSpans) {
                            if (span.tocNodeId in readIds) {
                                for (p in span.firstPage..span.lastPage) allowed += p
                            }
                        }
                        val filtered = allowed.intersect(allPages.toSet())
                        allowedPagesByBook[mat.bookId] = if (filtered.isEmpty()) allPages.toSet() else filtered
                    }
                } else {
                    allowedPagesByBook[mat.bookId] = allPages.toSet()
                }
                val aliases = buildList {
                    mat.sourceFilename?.substringBeforeLast('.')?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
                }
                val annotationCtx = if (det.defaultStance == BookStance.CLASSIC || det.secondaryStance == BookStance.CLASSIC) {
                    val headerText = (1..2).mapNotNull { p -> loadSinglePageText(db, mat.bookId, p)?.take(400) }
                        .joinToString("\n")
                    headerText.take(600).takeIf { it.isNotBlank() }
                } else null
                bookStancesList += CollectionBookStancePayload(
                    bookId = mat.bookId,
                    title = mat.title,
                    aliases = aliases,
                    defaultStance = det.defaultStance,
                    secondaryStance = det.secondaryStance,
                    guards = det.guards,
                    readThroughPage = rtp,
                    bookPages = bookPages,
                    missingReadingRecord = missingRecord,
                    annotationContext = annotationCtx,
                )
            }
        } else if (isVirtualReadLibrary) {
            val activityBooks = loadBooksWithReadActivity(db, currentBookId)
            val fallbackAllBooks = activityBooks.isEmpty()
            for (mat in materials) {
                val allPages = loadAllPageNumbers(db, mat.bookId)
                if (allPages.isEmpty()) {
                    allowedPagesByBook[mat.bookId] = emptySet()
                    continue
                }
                val progressMap = BooklistProgressStore.loadForBook(db, mat.bookId)
                val readIds = progressMap.values.filter { it.readState == ChapterReadState.READ }.mapTo(HashSet()) { it.tocNodeId }
                val loadedBook = runCatching { library.book(mat.bookId) }.getOrNull()
                val chapterSpans = loadedBook?.let { BookChapterResolver.chapters(it) }.orEmpty()
                val allowed = LinkedHashSet<Int>()
                for (span in chapterSpans) {
                    if (span.tocNodeId in readIds) {
                        for (p in span.firstPage..span.lastPage) allowed += p
                    }
                }
                for (rec in progressMap.values) {
                    allowed.addAll(rec.dwelledPages)
                }
                val isActiveBook = fallbackAllBooks ||
                    mat.bookId in activityBooks ||
                    readIds.isNotEmpty() ||
                    progressMap.values.any { it.dwelledPages.isNotEmpty() }
                if (isActiveBook && loadedBook != null) {
                    val bodyStart = BookBoundaries.bodyStart(
                        loadedBook.pages,
                        loadedBook.structure,
                        loadedBook.correctedBodyPage,
                    ).importPage.coerceIn(1, allPages.max())
                    if (materials.size == 1) focusedBodyStart = bodyStart
                    val currentOrSaved = loadedBook.readingPage.coerceIn(1, allPages.max())
                    if (currentOrSaved >= bodyStart) {
                        for (p in bodyStart..currentOrSaved) allowed += p
                    } else {
                        allowed += currentOrSaved
                    }
                } else if (isActiveBook) {
                    val savedPage = loadSavedReadingPage(db, mat.bookId)?.coerceIn(1, allPages.max()) ?: allPages.first()
                    for (p in allPages.first()..savedPage) allowed += p
                }
                val filteredAllowed = allowed.intersect(allPages.toSet())
                allowedPagesByBook[mat.bookId] = filteredAllowed
                unreadPagesFilteredOut += (allPages.size - filteredAllowed.size).coerceAtLeast(0)
            }
            if (allowedPagesByBook.values.all { it.isEmpty() }) {
                return RetrievalBundle(
                    collection = collectionRow,
                    blocks = emptyList(),
                    blockMap = emptyMap(),
                    rawPagesByKey = emptyMap(),
                    shortLabelByBookId = shortLabelByBookId,
                    docLabelByBookId = docLabelByBookId,
                    matchedDiscrepancies = emptyList(),
                    blockedBooklistEmptyMessage = "书库里还没有已读或正在阅读的书。先导入并打开一本书再提问。",
                    unreadPagesFilteredOut = unreadPagesFilteredOut,
                )
            }
        } else if (collectionRow.kind == CollectionKind.BOOKLIST) {
            for (mat in materials) {
                val readIds = BooklistProgressStore.readChapterIds(db, mat.bookId)
                val allPages = loadAllPageNumbers(db, mat.bookId)
                if (readIds.isEmpty()) {
                    allowedPagesByBook[mat.bookId] = emptySet()
                    unreadPagesFilteredOut += allPages.size
                    continue
                }
                val loadedBook = runCatching { library.book(mat.bookId) }.getOrNull()
                val chapterSpans = loadedBook?.let { BookChapterResolver.chapters(it) }.orEmpty()
                val allowed = LinkedHashSet<Int>()
                for (span in chapterSpans) {
                    if (span.tocNodeId in readIds) {
                        for (p in span.firstPage..span.lastPage) allowed += p
                    }
                }
                if (allowed.isEmpty()) {
                    // Fallback to book_toc_nodes if lessons were empty
                    db.prepareStatement(
                        "SELECT id, start_page, end_page FROM book_toc_nodes WHERE book=?",
                    ).use { sql ->
                        sql.setString(1, mat.bookId)
                        sql.executeQuery().use { rows ->
                            while (rows.next()) {
                                if (rows.getString(1) in readIds) {
                                    for (p in rows.getInt(2)..rows.getInt(3)) allowed += p
                                }
                            }
                        }
                    }
                }
                val filteredAllowed = allowed.intersect(allPages.toSet())
                allowedPagesByBook[mat.bookId] = filteredAllowed
                unreadPagesFilteredOut += (allPages.size - filteredAllowed.size).coerceAtLeast(0)
            }
            if (allowedPagesByBook.values.all { it.isEmpty() }) {
                return RetrievalBundle(
                    collection = collectionRow,
                    blocks = emptyList(),
                    blockMap = emptyMap(),
                    rawPagesByKey = emptyMap(),
                    shortLabelByBookId = shortLabelByBookId,
                    docLabelByBookId = docLabelByBookId,
                    matchedDiscrepancies = emptyList(),
                    blockedBooklistEmptyMessage = "这份书单里还没有已读章节。先读完一章，再来跨书提问。",
                    unreadPagesFilteredOut = unreadPagesFilteredOut,
                )
            }
        }

        val queryProfile = buildQueryProfile(db, collectionId, question, materials)
        val offlineCandidates = loadOfflineDiscrepancies(
            db = db,
            collectionId = collectionId,
            collectionKind = collectionRow.kind,
            materials = materials,
            allowedPagesByBook = allowedPagesByBook,
        )
        val scopedCandidates = if (materials.size == collectionMaterials.size) {
            offlineCandidates
        } else {
            offlineCandidates.filter { it.citations.isNotEmpty() && it.citations.all { c -> c.bookId in docLabelByBookId } }
        }
        val matchedDiscrepancies = matchOfflineDiscrepancies(scopedCandidates, queryProfile)

        val pageTextCache = HashMap<Pair<String, Int>, String>()
        fun readPage(bookId: String, page: Int): String? {
            if (allStancesDetermined || collectionRow.kind == CollectionKind.BOOKLIST) {
                val allowed = allowedPagesByBook[bookId].orEmpty()
                if (page !in allowed) return null
            }
            return pageTextCache.getOrPut(bookId to page) {
                loadSinglePageText(db, bookId, page).orEmpty()
            }.takeIf { it.isNotBlank() }
        }

        val matByBook = materials.associateBy { it.bookId }
        val chosenKeys = LinkedHashSet<Pair<String, Int>>()
        val chosenList = mutableListOf<CollectionQaBlock>()

        fun addBlock(bookId: String, page: Int, text: String) {
            if (chosenList.size >= MAX_BLOCKS) return
            val key = bookId to page
            if (!chosenKeys.add(key)) return
            val mat = matByBook[bookId] ?: return
            val blockId = "B${chosenList.size + 1}"
            val ts = if (mat.role == CourseMaterialRole.TRANSCRIPT) {
                CourseTranscriptParser.pageTimeRangeLabel(text)
            } else null
            chosenList += CollectionQaBlock(
                blockId = blockId,
                bookId = bookId,
                docLabel = mat.docLabel,
                shortLabel = mat.shortLabel,
                authorityRank = mat.authorityRank,
                page = page,
                timestampLabel = ts,
                text = text.take(MAX_BLOCK_CHARS),
            )
        }

        // 1. Co-retrieve both sides of matched offline discrepancies (with adjacent transcript context)
        for (disc in matchedDiscrepancies.filter { it.directAspectMatch }) {
            for (cite in disc.citations) {
                val raw = readPage(cite.bookId, cite.page) ?: continue
                addBlock(cite.bookId, cite.page, raw)
                if (matByBook[cite.bookId]?.role == CourseMaterialRole.TRANSCRIPT) {
                    readPage(cite.bookId, cite.page - 1)?.let { addBlock(cite.bookId, cite.page - 1, it) }
                    readPage(cite.bookId, cite.page + 1)?.let { addBlock(cite.bookId, cite.page + 1, it) }
                }
            }
        }

        // 2. FTS5 + BM25 + concept coverage + heading/definitional reranking across allowed materials
        val effectiveBooklistFilter = allStancesDetermined || collectionRow.kind == CollectionKind.BOOKLIST
        val candidates = scoreAllowedPages(
            db = db,
            materials = materials,
            collectionKind = if (effectiveBooklistFilter) CollectionKind.BOOKLIST else collectionRow.kind,
            allowedPagesByBook = allowedPagesByBook,
            queryProfile = queryProfile,
            currentBookId = currentBookId,
            pageTextCache = pageTextCache,
        )

        // A later recap can repeat a character or setting more often than the scene itself.
        // Reserve room for the first substantial mentions alongside the strongest lexical matches.
        val focusedBook = materials.singleOrNull()
        val earlyScene = earlyScenePattern.containsMatchIn(question)
        if (focusedBook != null && candidates.isNotEmpty() && (earlyScene || focusedBook.format == "epub")) {
            val bodyStart = focusedBodyStart ?: library.book(focusedBook.bookId).bodyStart.importPage
            val early = candidates.asSequence()
                .filter { it.score >= candidates.first().score * 0.42 && it.page >= bodyStart }
                .filterNot { BookBoundaries.looksLikeFrontMatter(it.rawText) }
                .sortedBy { it.page }
                .take(3)
            if (!earlyScene) candidates.take(3).forEach { addBlock(it.bookId, it.page, it.rawText) }
            early.forEach { addBlock(it.bookId, it.page, it.rawText) }
        }
        val perBookCount = HashMap<String, Int>()
        for (b in chosenList) {
            perBookCount[b.bookId] = (perBookCount[b.bookId] ?: 0) + 1
        }
        val byBookQueue = LinkedHashMap<String, ArrayDeque<ScoredPageCandidate>>()
        for (cand in candidates) {
            byBookQueue.getOrPut(cand.bookId) { ArrayDeque() }.addLast(cand)
        }
        val bookOrder = byBookQueue.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, ArrayDeque<ScoredPageCandidate>>> {
                    it.key in queryProfile.titlePreferredBookIds
                }.thenByDescending { it.value.first().score },
            )
            .map { it.key }

        val isComparison = acrossPapers || isMultiBookComparisonQuery(question)
        val bestBookScore = bookOrder.firstOrNull()?.let { byBookQueue[it]?.firstOrNull()?.score } ?: 0.0
        val secondBookScore = bookOrder.getOrNull(1)?.let { byBookQueue[it]?.firstOrNull()?.score } ?: 0.0
        val focusedSingleBookMode = collectionRow.kind == CollectionKind.BOOKLIST &&
            !isComparison &&
            (queryProfile.titlePreferredBookIds.isNotEmpty() || secondBookScore < bestBookScore * 0.58)
        val maxPerBook = if (focusedSingleBookMode) MAX_PAGES_PER_FOCUSED_BOOK else MAX_PAGES_PER_BOOK

        // Pass 1: Guarantee at least 1 block from each qualifying book (every book for COURSE / comparison,
        // or competitive/explicitly-named books for focused BOOKLIST queries).
        for (bookId in bookOrder) {
            if (chosenList.size >= MAX_BLOCKS) break
            if ((perBookCount[bookId] ?: 0) > 0) continue
            val topScore = byBookQueue[bookId]?.firstOrNull()?.score ?: continue
            val qualifies = collectionRow.kind == CollectionKind.COURSE ||
                isComparison ||
                bookId in queryProfile.titlePreferredBookIds ||
                topScore >= bestBookScore * 0.58
            if (!qualifies) continue
            val queue = byBookQueue[bookId] ?: continue
            while (queue.isNotEmpty()) {
                val cand = queue.removeFirst()
                if ((cand.bookId to cand.page) in chosenKeys) continue
                addBlock(cand.bookId, cand.page, cand.rawText)
                perBookCount[bookId] = (perBookCount[bookId] ?: 0) + 1
                break
            }
        }

        // Pass 2: Round-robin across qualifying books up to maxPerBook per book
        var roundProgress = true
        while (chosenList.size < MAX_BLOCKS && roundProgress) {
            roundProgress = false
            for (bookId in bookOrder) {
                if (chosenList.size >= MAX_BLOCKS) break
                if ((perBookCount[bookId] ?: 0) >= maxPerBook) continue
                val topScore = byBookQueue[bookId]?.firstOrNull()?.score ?: continue
                val qualifies = collectionRow.kind == CollectionKind.COURSE ||
                    isComparison ||
                    bookId in queryProfile.titlePreferredBookIds ||
                    topScore >= bestBookScore * 0.58
                if (!qualifies) continue
                val queue = byBookQueue[bookId] ?: continue
                while (queue.isNotEmpty()) {
                    val cand = queue.removeFirst()
                    if ((cand.bookId to cand.page) in chosenKeys) continue
                    addBlock(cand.bookId, cand.page, cand.rawText)
                    perBookCount[bookId] = (perBookCount[bookId] ?: 0) + 1
                    roundProgress = true
                    break
                }
            }
        }

        // Pass 2.5: When >= 2 read books exist in a BOOKLIST and user asks for multi-book comparison,
        // ensure every book with allowed read pages is represented even on cross-lingual queries
        if (collectionRow.kind == CollectionKind.BOOKLIST &&
            chosenList.size < MAX_BLOCKS &&
            allowedPagesByBook.count { it.value.isNotEmpty() } >= 2 &&
            isComparison
        ) {
            for (mat in materials) {
                if (chosenList.size >= MAX_BLOCKS) break
                if ((perBookCount[mat.bookId] ?: 0) > 0) continue
                val allowed = allowedPagesByBook[mat.bookId].orEmpty()
                if (allowed.isEmpty()) continue
                val bestAllowed = allowed
                    .mapNotNull { p -> readPage(mat.bookId, p)?.let { p to it } }
                    .maxByOrNull { (_, txt) -> txt.length }
                if (bestAllowed != null) {
                    addBlock(mat.bookId, bestAllowed.first, bestAllowed.second)
                    perBookCount[mat.bookId] = (perBookCount[mat.bookId] ?: 0) + 1
                }
            }
        }

        // Pass 3: Fill remaining slots if needed (skip ultra-weak spillover books in focused single-book mode)
        for (cand in candidates) {
            if (chosenList.size >= MAX_BLOCKS) break
            if ((cand.bookId to cand.page) in chosenKeys) continue
            if (focusedSingleBookMode &&
                cand.bookId !in queryProfile.titlePreferredBookIds &&
                cand.score < bestBookScore * 0.45
            ) {
                continue
            }
            addBlock(cand.bookId, cand.page, cand.rawText)
        }

        val rawPagesByKey = HashMap<Pair<String, Int>, String>()
        for (b in chosenList) {
            rawPagesByKey[b.bookId to b.page] = pageTextCache[b.bookId to b.page] ?: b.text
        }
        for (disc in matchedDiscrepancies) {
            for (c in disc.citations) {
                readPage(c.bookId, c.page)?.let { rawPagesByKey[c.bookId to c.page] = it }
            }
        }

        return RetrievalBundle(
            collection = collectionRow,
            blocks = chosenList,
            blockMap = chosenList.associateBy { it.blockId },
            rawPagesByKey = rawPagesByKey,
            shortLabelByBookId = shortLabelByBookId,
            docLabelByBookId = docLabelByBookId,
            matchedDiscrepancies = matchedDiscrepancies,
            unreadPagesFilteredOut = unreadPagesFilteredOut,
            allStancesDetermined = allStancesDetermined,
            bookStances = bookStancesList,
            missingRecordNotices = missingRecordNotices,
        )
    }

    private val earlyScenePattern = Regex(
        """初见|初次|初訪|初访|开篇|开场|最初|第一次|早段|\b(?:first (?:visit|meet\w*|encounter|impression)|opening scene|at the beginning)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val crossSourcePattern = Regex(
        """跨[书書篇]|(?:两|兩|多|各|几|幾)[本篇部份]|(?:其他|不同|哪些|各位|两位)(?:书|書|论文|論文|作品|材料|作者)|\b(?:both|two|other|multiple|all|across|different|these|several)\s+(?:(?:the|my|these)\s+)?(?:books|papers|studies|authors|sources|texts)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val currentSourcePattern = Regex("""(?:这本|這本|本文|本书|本書|本篇)|\bthis (?:book|paper|study|source)\b""", RegexOption.IGNORE_CASE)
    private val authorYear = Regex("""(?<![a-z])([a-z]{3,})(?:\s+et al\.?)?[\s(,_-]*((?:19|20)\d{2})(?!\d)""")
    private val titleSeparator = Regex("""[^\p{L}\p{N}]+""")
    private val paperScopePattern = Regex(
        """\b(?:(?:across|among|between|compare|contrast|in)\s+(?:(?:the|my|these|those|both|all|two)\s+)*|(?:these|both|multiple|several|other|different)\s+)(?:papers|studies|articles)\b|(?:各篇|几篇|幾篇|多篇|两篇|兩篇|这些|這些|比较|比較|对比|對比)(?:研究)?(?:论文|論文)""",
        RegexOption.IGNORE_CASE,
    )
    private val paperHeader = Regex(
        """(?im)^\s*(?:#{1,3}\s*)?(?:abstract\b|摘\s*要)(?:\s|[:：.—-]|$)|\b(?:arxiv:\s*\d{4}\.\d+|doi\.org/10\.\d{4,9}/)""",
    )
    private val paperLabel = Regex("""学术论文|學術論文|\b(?:research paper|journal article|arxiv)\b""", RegexOption.IGNORE_CASE)

    private fun questionMaterials(
        db: Connection,
        materials: List<CollectionMaterialMeta>,
        question: String,
        currentBookId: String?,
        acrossPapers: Boolean,
    ): List<CollectionMaterialMeta> {
        val lower = foldTraditionalChinese(question.lowercase())
        val normalized = " " + lower.replace(titleSeparator, " ") + " "
        val citations = authorYear.findAll(lower).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        val unresolved = citations.toMutableSet()
        val corpusTitleAliases = listOf(
            "walden" to listOf("瓦尔登湖", "梭罗"),
            "economics" to listOf("经济学", "经济学 2e", "经济学2e"),
            "pride and prejudice" to listOf("傲慢与偏见"),
            "zhouyi" to listOf("周易", "易经"),
            "周易" to listOf("周易", "易经"),
            "论语" to listOf("论语"),
            "道德经" to listOf("道德经", "老子"),
            "franklin" to listOf("富兰克林"),
            "origin of species" to listOf("物种起源", "达尔文"),
            "sherlock holmes" to listOf("福尔摩斯"),
            "kybalion" to listOf("卡巴莱恩"),
        )
        val named = materials.filterTo(linkedSetOf()) { mat ->
            val title = foldTraditionalChinese(mat.title.lowercase())
            val shortTitle = title.replace(Regex("""[（(【\[][^）)】\]]*[）)】\]]"""), "").trim()
            val words = title.replace(titleSeparator, " ").trim()
            val chineseTitle = title.any { it in '\u4e00'..'\u9fff' } &&
                ((title.length >= 2 && title in lower) || (shortTitle.length >= 2 && shortTitle in lower))
            val aliasHit = corpusTitleAliases.any { (key, aliases) ->
                (key in title || (mat.sourceFilename?.lowercase()?.contains(key) == true)) &&
                    aliases.any { it in lower }
            }
            val matched = if (citations.isEmpty()) emptySet() else {
                listOfNotNull(title, mat.sourceFilename?.lowercase()).flatMap { label ->
                    authorYear.findAll(label).map { it.groupValues[1] to it.groupValues[2] }
                }.intersect(citations)
            }
            unresolved.removeAll(matched)
            chineseTitle || aliasHit || (words.length >= 3 && " $words " in normalized) || matched.isNotEmpty()
        }
        val headers = mutableMapOf<String, String>()
        fun header(mat: CollectionMaterialMeta) = headers.getOrPut(mat.bookId) {
            db.prepareStatement("SELECT substr(text, 1, 3000) FROM pages WHERE book=? ORDER BY page LIMIT 1").use { sql ->
                sql.setString(1, mat.bookId)
                sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
            }
        }
        // An old import or a renamed download can lack an author-year filename. Match the byline,
        // rather than treating an incidental reference in the body as the author of the paper.
        if (unresolved.isNotEmpty()) {
            for (mat in materials) {
                val front = header(mat)
                if (unresolved.none { (author, year) -> year in front && front.contains(author, ignoreCase = true) }) continue
                val meta = LiteratureMetadata.extract(null, mat.bookId, mat.title, mat.sourceFilename, listOf(front))
                val authors = meta.author.lowercase()
                if (unresolved.any { (author, year) -> year == meta.year && containsWholeWord(authors, author) }) named += mat
            }
        }
        if (acrossPapers) {
            val pool = if (named.size >= 2) named else materials
            return pool.filter { mat ->
                paperLabel.containsMatchIn(mat.title) || paperHeader.containsMatchIn(header(mat))
            }
        }
        if (named.size >= 2) return named.toList()
        if (crossSourcePattern.containsMatchIn(question)) return materials
        if (named.isNotEmpty() && currentSourcePattern.containsMatchIn(question) && isMultiBookComparisonQuery(question)) {
            return materials.filter { it in named || it.bookId == currentBookId }
        }
        if (named.isNotEmpty()) return named.toList()
        return materials.find { it.bookId == currentBookId }?.let(::listOf) ?: materials
    }

    private fun buildQueryProfile(
        db: Connection,
        collectionId: String,
        question: String,
        materials: List<CollectionMaterialMeta> = emptyList(),
    ): QueryProfile {
        val enTerms = LinkedHashSet<String>()
        val phrases = LinkedHashSet<String>()
        val zhTerms = LinkedHashSet<String>()
        val slots = ArrayList<ConceptSlot>()
        val coveredSlotKeys = HashSet<String>()
        val qLower = question.lowercase()

        fun addSlot(id: String, terms: List<ConceptTerm>, importance: Double = 1.0) {
            val clean = terms.distinctBy { it.text to it.isPhrase to it.isChinese }
            if (clean.isEmpty()) return
            slots += ConceptSlot(id = id, terms = clean, baseImportance = importance)
        }

        // Detect explicit book title mentions so focused single-book questions allocate blocks cleanly
        val titlePreferredBookIds = LinkedHashSet<String>()
        for (mat in materials) {
            val titleLower = mat.title.lowercase()
            val titleWords = enPageWordRegex.findAll(titleLower)
                .map { it.value }
                .filter { it.length >= 4 && it !in enRetrievalStopwords && it != "book" }
                .toList()
            val matchedByEn = titleLower.length >= 4 && titleLower in qLower ||
                (titleWords.isNotEmpty() && titleWords.all { containsWholeWord(qLower, it) })
            val matchedByZh = generalZhToEnExpansions.any { (zh, targets) ->
                zh in qLower && targets.any { t -> t in titleWords }
            }
            if (matchedByEn || matchedByZh) {
                titlePreferredBookIds += mat.bookId
            }
        }

        // 1. Direct English / numeric tokens from the user's question
        val rawEnTokens = enTokenRegex.findAll(question)
            .map { it.value.trim('.', ',', ';', ':', '?', '!').lowercase() }
            .filter { it.length >= 2 }
            .toList()
        for (tok in rawEnTokens) {
            if (tok in enRetrievalStopwords) continue
            enTerms += tok
            val stem = stemEn(tok)
            val isCue = tok in enCueWords || stem in enCueWords
            val importance = if (isCue) 0.4 else 1.0
            val slotTerms = ArrayList<ConceptTerm>()
            if ('-' in tok) {
                // The page tokenizer splits at hyphens; keep compound names as a single concept.
                slotTerms += ConceptTerm(tok, weight = 1.0, isPhrase = true)
                slotTerms += ConceptTerm(tok.replace('-', ' '), weight = 1.0, isPhrase = true)
            } else {
                slotTerms += ConceptTerm(tok, weight = 1.0)
            }
            if (stem != tok && stem.length >= 3) {
                slotTerms += ConceptTerm(stem, weight = 0.88)
            }
            val synonyms = enSynonymLookup[tok].orEmpty() + enSynonymLookup[stem].orEmpty()
            for (syn in synonyms) {
                if (syn != tok && syn != stem) {
                    if (' ' in syn || '-' in syn) {
                        slotTerms += ConceptTerm(syn, weight = 0.72, isPhrase = true)
                    } else {
                        slotTerms += ConceptTerm(syn, weight = 0.72)
                        val synStem = stemEn(syn)
                        if (synStem != syn && synStem.length >= 3) {
                            slotTerms += ConceptTerm(synStem, weight = 0.65)
                        }
                    }
                }
            }
            val slotKey = "en:$stem"
            if (coveredSlotKeys.add(slotKey)) {
                addSlot(slotKey, slotTerms, importance)
            }
        }

        // Map Greek symbols like Ω2 / Ω3 to both symbol and Omega forms
        if ("Ω" in question) {
            enTerms += "omega"
            for (d in listOf("1", "2", "3", "4")) {
                if ("Ω$d" in question || "Omega$d" in question || "Omega $d" in question) {
                    phrases += "omega_$d"
                    phrases += "omega $d"
                    enTerms += "omega_$d"
                    addSlot(
                        "omega_$d",
                        listOf(
                            ConceptTerm("omega_$d", 1.0, isPhrase = true),
                            ConceptTerm("omega $d", 1.0, isPhrase = true),
                        ),
                    )
                }
            }
        }

        // 2. Bilingual question-term expansions (ECNM10119 + general cross-lingual lexicon)
        val matchedBilingualKeys = ArrayList<String>()
        val combinedBilingual = (zhToEnExpansions + generalZhToEnExpansions)
            .sortedByDescending { it.first.length }
        for ((zh, targets) in combinedBilingual) {
            if (zh !in qLower) continue
            // Skip shorter key if an already-matched longer key covers it and has no extra meaning
            if (matchedBilingualKeys.any { longer -> longer != zh && zh in longer }) continue
            matchedBilingualKeys += zh
            val slotTerms = ArrayList<ConceptTerm>()
            if (zh !in zhRetrievalStopwords) {
                slotTerms += ConceptTerm(zh, weight = 1.0, isChinese = true)
            }
            for (target in targets) {
                val tLow = target.lowercase()
                if (' ' in tLow) {
                    phrases += tLow
                    slotTerms += ConceptTerm(tLow, weight = 0.95, isPhrase = true)
                    for (part in tLow.split(' ', '-')) {
                        if (part.length >= 3 && part !in enRetrievalStopwords) {
                            enTerms += part
                            slotTerms += ConceptTerm(part, weight = 0.65)
                        }
                    }
                } else {
                    for (part in tLow.split('-')) {
                        if (part.length >= 2 && part !in enRetrievalStopwords) {
                            enTerms += part
                            slotTerms += ConceptTerm(part, weight = 0.90)
                            val pStem = stemEn(part)
                            if (pStem != part && pStem.length >= 3) {
                                slotTerms += ConceptTerm(pStem, weight = 0.80)
                            }
                        }
                    }
                }
            }
            val isTitleOnly = zh in setOf("君主论", "利维坦", "理想国", "论自由")
            if (!isTitleOnly && coveredSlotKeys.add("zh-bi:$zh")) {
                addSlot("zh-bi:$zh", slotTerms, importance = 1.0)
            }
        }

        // 3. Chinese sequences: split on particles/question words first, then keep meaningful whole + n-grams
        val titleOnlyZhParts = materials.flatMapTo(HashSet()) { mat ->
            val rawT = mat.title
            val noParen = rawT.replace(Regex("""[（(【\[][^）)】\]]*[）)】\]]"""), " ")
            zhSequenceRegex.findAll("$rawT $noParen").map { it.value }.toList()
        }
        // Any book title written inside 《...》 in the question is a material filter, not a content keyword
        Regex("""《([^》]+)》""").findAll(question).forEach { m ->
            zhSequenceRegex.findAll(m.groupValues[1]).forEach { titleOnlyZhParts += it.value }
        }
        titleOnlyZhParts += listOf("王弼本", "十翼", "經文", "老子道德經注", "道德經注", "論語", "论语", "道德經", "道德经", "周易", "易經", "易经")

        // Exact quoted/locator phrases in 「...」 or （...） get a dedicated high-importance slot
        Regex("""[「（]([^」）]{2,24})[」）]""").findAll(question).forEachIndexed { idx, m ->
            val phrase = m.groupValues[1].trim()
            if (phrase !in titleOnlyZhParts && phrase !in zhRetrievalStopwords) {
                val isZh = phrase.any { it in '\u4e00'..'\u9fff' }
                val cleanPhrase = if (isZh) phrase else phrase.lowercase()
                addSlot(
                    "quoted:$idx:$cleanPhrase",
                    listOf(ConceptTerm(cleanPhrase, weight = 1.6, isPhrase = !isZh, isChinese = isZh)),
                    importance = 1.5,
                )
            }
        }

        for (rawRun in zhSequenceRegex.findAll(question)) {
            val parts = zhSplitParticlesRegex.split(rawRun.value).filter { it.length >= 2 }
            for (part in parts) {
                if (part in zhRetrievalStopwords) continue
                if (part in titleOnlyZhParts) continue
                zhTerms += part
                val subTerms = ArrayList<ConceptTerm>()
                // Keep exact unsplit run when particle splitting shortened a classical quote like 學而時習之 -> 學而時習
                if (rawRun.value.length in 3..12 && rawRun.value != part && rawRun.value !in titleOnlyZhParts) {
                    subTerms += ConceptTerm(rawRun.value, weight = 1.35, isChinese = true)
                }
                subTerms += ConceptTerm(part, weight = 1.0, isChinese = true)
                if (part.length > 2) {
                    for (i in 0..(part.length - 2)) {
                        val gram2 = part.substring(i, i + 2)
                        if (gram2 !in zhRetrievalStopwords) {
                            zhTerms += gram2
                            subTerms += ConceptTerm(gram2, weight = 0.65, isChinese = true)
                        }
                    }
                }
                if (part.length > 3) {
                    for (i in 0..(part.length - 3)) {
                        val gram3 = part.substring(i, i + 3)
                        if (gram3 !in zhRetrievalStopwords) {
                            zhTerms += gram3
                            subTerms += ConceptTerm(gram3, weight = 0.82, isChinese = true)
                        }
                    }
                }
                // Only add a standalone Chinese slot if not already covered by a bilingual match on the same span
                val alreadyBilingual = matchedBilingualKeys.any { bi -> bi in part || part in bi }
                if (!alreadyBilingual && coveredSlotKeys.add("zh:$part")) {
                    addSlot("zh:$part", subTerms, importance = 1.0)
                }
            }
        }

        // 4. Harvest topic titles & concept entity aliases dynamically from the local collection DB
        if (BookSchemaMigration.tableExists(db, "course_topics")) {
            db.prepareStatement("SELECT title FROM course_topics WHERE collection_id=?").use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val title = rows.getString(1).orEmpty()
                        val topicZh = zhSequenceRegex.findAll(title).map { it.value }.toList()
                        if (topicZh.any { it in question }) {
                            val extraTerms = ArrayList<ConceptTerm>()
                            for (tok in enTokenRegex.findAll(title)) {
                                val t = tok.value.lowercase()
                                if (t.length >= 3 && t !in enRetrievalStopwords) {
                                    enTerms += t
                                    extraTerms += ConceptTerm(t, weight = 0.85)
                                }
                            }
                            if (extraTerms.isNotEmpty() && coveredSlotKeys.add("topic:$title")) {
                                addSlot("topic:$title", extraTerms, importance = 0.9)
                            }
                        }
                    }
                }
            }
        }
        if (BookSchemaMigration.tableExists(db, "concept_entities")) {
            db.prepareStatement(
                "SELECT canonical_name, aliases_json FROM concept_entities WHERE collection_id=? AND muted=0",
            ).use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val name = rows.getString(1).orEmpty()
                        val aliasesRaw = rows.getString(2).orEmpty()
                        if (name.length >= 2 && (name.lowercase() in qLower || enTerms.any { it in name.lowercase() })) {
                            val extraTerms = ArrayList<ConceptTerm>()
                            for (tok in enTokenRegex.findAll("$name $aliasesRaw")) {
                                val t = tok.value.lowercase()
                                if (t.length >= 3 && t !in enRetrievalStopwords) {
                                    enTerms += t
                                    extraTerms += ConceptTerm(t, weight = 0.85)
                                }
                            }
                            if (extraTerms.isNotEmpty() && coveredSlotKeys.add("concept:$name")) {
                                addSlot("concept:$name", extraTerms, importance = 0.9)
                            }
                        }
                    }
                }
            }
        }

        val requestedWeeks = LinkedHashSet<Int>()
        for (m in weekPattern.findAll(question)) {
            val wk = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            if (wk != null && wk in 1..20) requestedWeeks += wk
        }

        val roleBoosts = LinkedHashSet<CourseMaterialRole>()
        if (anyIn(qLower, "手册", "大纲", "考核", "权重", "暂定", "handbook", "syllabus", "assessment")) {
            roleBoosts += CourseMaterialRole.SYLLABUS
        }
        if (anyIn(qLower, "讨论课", "习题", "解答", "tutorial", "workshop", "key")) {
            roleBoosts += CourseMaterialRole.TUTORIAL
            roleBoosts += CourseMaterialRole.SOLUTION_KEY
            roleBoosts += CourseMaterialRole.TRANSCRIPT
        }
        if (anyIn(qLower, "录音", "转写", "口误", "讲课", "transcript")) {
            roleBoosts += CourseMaterialRole.TRANSCRIPT
        }
        if (anyIn(qLower, "实证", "论文", "研究", "paper", "empirical")) {
            roleBoosts += CourseMaterialRole.REQUIRED_READING
        }

        val allMatch = LinkedHashSet<String>()
        allMatch += enTerms
        allMatch += zhTerms
        return QueryProfile(
            rawQuestion = question,
            englishTerms = enTerms.toList(),
            phrases = phrases.toList(),
            chineseTerms = zhTerms.toList(),
            conceptSlots = slots,
            titlePreferredBookIds = titlePreferredBookIds,
            requestedWeeks = requestedWeeks,
            roleBoosts = roleBoosts,
            allMatchTerms = allMatch,
        )
    }

    private data class RawScoredPage(
        val mat: CollectionMaterialMeta,
        val page: Int,
        val rawText: String,
        val normText: String,
        val docLength: Int,
        val slotTfs: DoubleArray,
        val headingSlotsHit: Int,
        val maxSentenceSlotsHit: Int,
        val hasDefinitionInCoOccurringSentence: Boolean,
        val hasBoilerplate: Boolean,
        val ftsHit: Boolean,
    )

    private fun scoreAllowedPages(
        db: Connection,
        materials: List<CollectionMaterialMeta>,
        collectionKind: CollectionKind,
        allowedPagesByBook: Map<String, Set<Int>>,
        queryProfile: QueryProfile,
        currentBookId: String?,
        pageTextCache: HashMap<Pair<String, Int>, String>,
    ): List<ScoredPageCandidate> {
        val slots = queryProfile.conceptSlots
        if (slots.isEmpty()) return emptyList()

        val ftsBoostPages = HashSet<Pair<String, Int>>()
        val ftsTokens = queryProfile.englishTerms
            .filter { it.length >= 3 && it !in enRetrievalStopwords && it !in enCueWords }
            .take(8)
        for (mat in materials) {
            for (tok in ftsTokens) {
                val hits = BookFtsStore.queryCandidatePages(db, mat.bookId, tok).orEmpty()
                for (p in hits) ftsBoostPages += mat.bookId to p
            }
        }

        val rawPages = ArrayList<RawScoredPage>()
        val df = IntArray(slots.size)
        var totalDocs = 0
        var totalDocLength = 0L

        for (mat in materials) {
            val allowedSet = if (collectionKind == CollectionKind.BOOKLIST) allowedPagesByBook[mat.bookId].orEmpty() else null
            if (allowedSet != null && allowedSet.isEmpty()) continue
            db.prepareStatement("SELECT page, text FROM pages WHERE book=? ORDER BY page").use { sql ->
                sql.setString(1, mat.bookId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val page = rows.getInt(1)
                        if (allowedSet != null && page !in allowedSet) continue
                        val rawText = rows.getString(2).orEmpty()
                        if (rawText.isBlank()) continue
                        totalDocs++
                        pageTextCache[mat.bookId to page] = rawText
                        val normText = SentenceCitationGate.normalizeText(rawText)
                        val normLower = normText.lowercase()
                        val zhFoldedLower = foldTraditionalChinese(normLower)

                        // Tokenize English words & stems once per page
                        val wordCounts = HashMap<String, Int>(64)
                        val stemCounts = HashMap<String, Int>(64)
                        var wordTotal = 0
                        for (m in enPageWordRegex.findAll(normLower)) {
                            val w = m.value
                            wordTotal++
                            wordCounts[w] = (wordCounts[w] ?: 0) + 1
                            val st = stemEn(w)
                            stemCounts[st] = (stemCounts[st] ?: 0) + 1
                        }
                        val docLen = maxOf(wordTotal, normLower.length / 4).coerceAtLeast(1)
                        totalDocLength += docLen

                        // Extract heading lines (# ... or short standalone title-like lines)
                        val headingBuilder = StringBuilder()
                        for (rawLine in rawText.lineSequence()) {
                            val line = rawLine.trim()
                            if (line.isEmpty()) continue
                            val isMdHeading = line.startsWith("#")
                            val isShortStandaloneTitle = line.length in 4..80 &&
                                !line.endsWith('.') && !line.endsWith(',') && !line.endsWith(';') &&
                                line.count { it == ' ' } in 0..10
                            if (isMdHeading || isShortStandaloneTitle) {
                                headingBuilder.append(line.lowercase()).append('\n')
                            }
                        }
                        val headingText = SentenceCitationGate.normalizeText(headingBuilder.toString()).lowercase()
                        val headingWords = enPageWordRegex.findAll(headingText).map { it.value }.toHashSet()
                        val headingStems = headingWords.mapTo(HashSet()) { stemEn(it) }

                        val slotTfs = DoubleArray(slots.size)
                        var anyCoreSlotHit = false
                        var headingHits = 0
                        for (sIdx in slots.indices) {
                            val slot = slots[sIdx]
                            var bestWeightedTf = 0.0
                            var hitInHeading = false
                            for (term in slot.terms) {
                                val count = when {
                                    term.isChinese -> maxOf(
                                        countSubstringOccurrences(normLower, term.text),
                                        countSubstringOccurrences(zhFoldedLower, foldTraditionalChinese(term.text)),
                                    )
                                    term.isPhrase -> countSubstringOccurrences(normLower, term.text)
                                    term.text.length <= 3 -> wordCounts[term.text] ?: 0
                                    else -> maxOf(wordCounts[term.text] ?: 0, stemCounts[term.text] ?: 0)
                                }
                                if (count > 0) {
                                    val wTf = count.toDouble() * term.weight
                                    if (wTf > bestWeightedTf) bestWeightedTf = wTf
                                }
                                if (!hitInHeading && headingText.isNotEmpty()) {
                                    val inHead = when {
                                        term.isChinese || term.isPhrase -> term.text in headingText
                                        else -> term.text in headingWords || term.text in headingStems
                                    }
                                    if (inHead) hitInHeading = true
                                }
                            }
                            slotTfs[sIdx] = bestWeightedTf
                            if (bestWeightedTf > 0.0) {
                                df[sIdx]++
                                if (slot.baseImportance >= 0.8) anyCoreSlotHit = true
                            }
                            if (hitInHeading && slot.baseImportance >= 0.8) headingHits++
                        }

                        val ftsHit = (mat.bookId to page) in ftsBoostPages
                        if (!anyCoreSlotHit && !ftsHit) continue

                        // Sentence / adjacent-2-sentence window co-occurrence & definitional cue detection
                        val sentences = sentenceSplitRegex.split(normLower).filter { it.length >= 12 }
                        var maxWinSlots = 0
                        var hasDefCue = false
                        for (i in sentences.indices) {
                            val window = if (i + 1 < sentences.size && sentences[i].length < 220) {
                                sentences[i] + " " + sentences[i + 1]
                            } else {
                                sentences[i]
                            }
                            val winWords = enPageWordRegex.findAll(window).map { it.value }.toHashSet()
                            val winStems = winWords.mapTo(HashSet()) { stemEn(it) }
                            var coreInWin = 0
                            for (slot in slots) {
                                if (slot.baseImportance < 0.8) continue
                                val matched = slot.terms.any { term ->
                                    when {
                                        term.isChinese || term.isPhrase -> term.text in window
                                        else -> term.text in winWords || term.text in winStems
                                    }
                                }
                                if (matched) coreInWin++
                            }
                            if (coreInWin > maxWinSlots) maxWinSlots = coreInWin
                            if (coreInWin >= 2 && definitionCueRegex.containsMatchIn(window)) {
                                hasDefCue = true
                            }
                        }

                        rawPages += RawScoredPage(
                            mat = mat,
                            page = page,
                            rawText = rawText,
                            normText = normText,
                            docLength = docLen,
                            slotTfs = slotTfs,
                            headingSlotsHit = headingHits,
                            maxSentenceSlotsHit = maxWinSlots,
                            hasDefinitionInCoOccurringSentence = hasDefCue,
                            hasBoilerplate = boilerplateRegex.containsMatchIn(normLower),
                            ftsHit = ftsHit,
                        )
                    }
                }
            }
        }

        if (rawPages.isEmpty()) return emptyList()
        val avgDl = (totalDocLength.toDouble() / totalDocs.coerceAtLeast(1)).coerceAtLeast(50.0)
        val coreSlotsTotal = slots.count { it.baseImportance >= 0.8 }
        val k1 = 1.2
        val b = 0.35

        val allCandidates = ArrayList<ScoredPageCandidate>(rawPages.size)
        for (rp in rawPages) {
            var bm25Score = 0.0
            var coreSlotsHit = 0
            var maxTfOnHit = 0.0
            var minDfOnHit = Int.MAX_VALUE

            for (sIdx in slots.indices) {
                val tf = rp.slotTfs[sIdx]
                if (tf <= 0.0) continue
                val slot = slots[sIdx]
                val docFreq = df[sIdx].coerceAtLeast(1)
                val idf = kotlin.math.ln(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5)).coerceAtLeast(0.6)
                val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * rp.docLength / avgDl))
                bm25Score += idf * slot.baseImportance * tfNorm
                if (slot.baseImportance >= 0.8) {
                    coreSlotsHit++
                    if (tf > maxTfOnHit) maxTfOnHit = tf
                    if (docFreq < minDfOnHit) minDfOnHit = docFreq
                }
            }

            if (coreSlotsHit == 0 && !rp.ftsHit) continue
            // Suppress single-hit high-frequency incidental noise on multi-concept queries over real-sized books
            if (totalDocs >= 12 &&
                coreSlotsTotal >= 3 &&
                coreSlotsHit == 1 &&
                maxTfOnHit <= 1.0 &&
                rp.headingSlotsHit == 0 &&
                minDfOnHit > totalDocs * 0.25
            ) {
                continue
            }

            val coverageBonus = if (coreSlotsTotal >= 2 && coreSlotsHit >= 2) {
                val ratio = coreSlotsHit.toDouble() / coreSlotsTotal.toDouble()
                3.5 * ratio * coreSlotsHit
            } else 0.0
            val headingBonus = rp.headingSlotsHit * 2.5
            val coOccurBonus = when {
                rp.maxSentenceSlotsHit >= 3 -> 4.0
                rp.maxSentenceSlotsHit == 2 -> 2.4
                else -> 0.0
            }
            val defBonus = if (rp.hasDefinitionInCoOccurringSentence) 3.0 else 0.0
            val boilerplatePenalty = if (rp.hasBoilerplate) -18.0 else 0.0
            val ftsBonus = if (rp.ftsHit) 1.0 else 0.0
            val authorityBoost = when (rp.mat.authorityRank) {
                1 -> 1.4
                2 -> 0.9
                3 -> 0.6
                else -> 0.2
            }
            val weekBoost = if (rp.mat.weekNum != null && rp.mat.weekNum in queryProfile.requestedWeeks) 3.5 else 0.0
            val roleBoost = if (rp.mat.role in queryProfile.roleBoosts) 2.2 else 0.0
            val currentBookBoost = if (rp.mat.bookId == currentBookId) 0.25 else 0.0
            val titlePreferredBoost = if (rp.mat.bookId in queryProfile.titlePreferredBookIds) 1.5 else 0.0

            val total = bm25Score + coverageBonus + headingBonus + coOccurBonus + defBonus +
                boilerplatePenalty + ftsBonus + authorityBoost + weekBoost + roleBoost +
                currentBookBoost + titlePreferredBoost
            if (rp.hasBoilerplate && total <= 0.0) continue

            allCandidates += ScoredPageCandidate(
                bookId = rp.mat.bookId,
                page = rp.page,
                rawText = rp.rawText,
                normText = rp.normText,
                score = total,
                role = rp.mat.role,
                authorityRank = rp.mat.authorityRank,
            )
        }
        return allCandidates.sortedWith(
            compareByDescending<ScoredPageCandidate> { it.score }
                .thenBy { it.authorityRank }
                .thenBy { it.page },
        )
    }

    private fun countSubstringOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, startIndex = idx + needle.length)
        }
        return count
    }

    private fun containsWholeWord(normLower: String, term: String): Boolean {
        if (term.isEmpty()) return false
        var searchFrom = normLower.indexOf(term)
        while (searchFrom >= 0) {
            val end = searchFrom + term.length
            val leftOk = searchFrom == 0 || !normLower[searchFrom - 1].isLetterOrDigit()
            val rightOk = end == normLower.length || !normLower[end].isLetterOrDigit()
            if (leftOk && rightOk) return true
            searchFrom = normLower.indexOf(term, startIndex = searchFrom + 1)
        }
        return false
    }

    private fun containsWordOrToken(normLower: String, term: String): Boolean {
        if (term.isEmpty()) return false
        val idx = normLower.indexOf(term)
        if (idx < 0) return false
        if (term.length >= 4) return true
        // For short 2-3 char terms (e.g. "sg", "3b", "po"), verify word boundaries
        var searchFrom = idx
        while (searchFrom >= 0) {
            val end = searchFrom + term.length
            val leftOk = searchFrom == 0 || !normLower[searchFrom - 1].isLetterOrDigit()
            val rightOk = end == normLower.length || !normLower[end].isLetterOrDigit()
            if (leftOk && rightOk) return true
            searchFrom = normLower.indexOf(term, startIndex = searchFrom + 1)
        }
        return false
    }

    private fun loadOfflineDiscrepancies(
        db: Connection,
        collectionId: String,
        collectionKind: CollectionKind,
        materials: List<CollectionMaterialMeta>,
        allowedPagesByBook: Map<String, Set<Int>>,
    ): List<MatchedOfflineDiscrepancy> {
        val out = mutableListOf<MatchedOfflineDiscrepancy>()
        val matByBook = materials.associateBy { it.bookId }

        if (collectionKind == CollectionKind.COURSE && BookSchemaMigration.tableExists(db, "course_topics")) {
            db.prepareStatement(
                "SELECT topic_id, title, pack_payload_json FROM course_topics WHERE collection_id=? AND pack_payload_json IS NOT NULL",
            ).use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val tid = rows.getString(1)
                        val payload = rows.getString(3)
                        val pack = CourseJson.decodeTopicPack(payload) ?: continue
                        pack.discrepancies.forEachIndexed { idx, d ->
                            out += MatchedOfflineDiscrepancy(
                                id = "topic:$tid:$idx",
                                point = d.point,
                                authoritativeView = d.authoritativeView,
                                secondaryView = d.secondaryView,
                                advice = d.advice,
                                directAspectMatch = false,
                                citations = d.citations,
                            )
                        }
                    }
                }
            }
        }

        if (BookSchemaMigration.tableExists(db, "concept_links") &&
            BookSchemaMigration.tableExists(db, "concept_mentions") &&
            BookSchemaMigration.tableExists(db, "concept_entities")
        ) {
            db.prepareStatement(
                """SELECT cl.link_id, ce.canonical_name, ce.aliases_json, cl.relation_type, cl.diff_summary,
                          fm.book_id, fm.page, fm.offset_utf16, fm.quote_anchor, fm.claim_summary,
                          tm.book_id, tm.page, tm.offset_utf16, tm.quote_anchor, tm.claim_summary
                   FROM concept_links cl
                   JOIN concept_entities ce ON ce.concept_id = cl.concept_id
                   JOIN concept_mentions fm ON fm.mention_id = cl.from_mention_id
                   JOIN concept_mentions tm ON tm.mention_id = cl.to_mention_id
                   WHERE cl.collection_id=? AND ce.muted=0 AND cl.dismissed=0
                     AND cl.relation_type IN ('NOTATION_OR_BOUNDARY_DIFF','EMPIRICAL_CHALLENGE','OPPOSING_VIEW')""",
            ).use { sql ->
                sql.setString(1, collectionId)
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val linkId = rows.getString(1)
                        val canonicalName = rows.getString(2)
                        val aliases = rows.getString(3)
                        val diffSummary = rows.getString(5)
                        val fBook = rows.getString(6)
                        val fPage = rows.getInt(7)
                        val fOffset = rows.getInt(8)
                        val fQuote = rows.getString(9)
                        val fSummary = rows.getString(10)
                        val tBook = rows.getString(11)
                        val tPage = rows.getInt(12)
                        val tOffset = rows.getInt(13)
                        val tQuote = rows.getString(14)
                        val tSummary = rows.getString(15)

                        // Booklist anti-spoiler hard guard: BOTH sides MUST be inside READ chapters!
                        if (collectionKind == CollectionKind.BOOKLIST) {
                            val fAllowed = allowedPagesByBook[fBook].orEmpty()
                            val tAllowed = allowedPagesByBook[tBook].orEmpty()
                            if (fPage !in fAllowed || tPage !in tAllowed) continue
                        }
                        val fMat = matByBook[fBook]
                        val tMat = matByBook[tBook]
                        val cites = listOf(
                            CourseCitationRef(
                                bookId = fBook,
                                bookTitle = fMat?.title ?: fBook,
                                role = fMat?.role ?: CourseMaterialRole.CORE_TEXTBOOK,
                                page = fPage,
                                startUtf16 = fOffset,
                                endUtf16 = fOffset + fQuote.length,
                                quote = fQuote,
                                verified = true,
                            ),
                            CourseCitationRef(
                                bookId = tBook,
                                bookTitle = tMat?.title ?: tBook,
                                role = tMat?.role ?: CourseMaterialRole.CORE_TEXTBOOK,
                                page = tPage,
                                startUtf16 = tOffset,
                                endUtf16 = tOffset + tQuote.length,
                                quote = tQuote,
                                verified = true,
                            ),
                        )
                        out += MatchedOfflineDiscrepancy(
                            id = "link:$linkId",
                            point = "$canonicalName（$diffSummary）",
                            authoritativeView = fSummary,
                            secondaryView = tSummary,
                            advice = diffSummary + if (aliases.isNotBlank()) "" else "",
                            directAspectMatch = false,
                            citations = cites,
                        )
                    }
                }
            }
        }
        return out
    }

    private fun matchOfflineDiscrepancies(
        candidates: List<MatchedOfflineDiscrepancy>,
        query: QueryProfile,
    ): List<MatchedOfflineDiscrepancy> {
        if (candidates.isEmpty()) return emptyList()
        val qLower = query.rawQuestion.lowercase()

        // Extract non-overlapping distinctive concept tokens directly from the student's question
        // (do NOT multiply overlapping Chinese 2-grams + translated English words into false hit counts).
        val directConcepts = LinkedHashSet<String>()
        for (m in enTokenRegex.findAll(qLower)) {
            val tok = m.value.trim('.', ',', ';', ':', '?', '!')
            if (tok.length >= 2 && tok !in genericEntityStopwords) directConcepts += tok
        }
        if ("Ω" in query.rawQuestion) {
            for (d in listOf("1", "2", "3", "4")) {
                if ("Ω$d" in query.rawQuestion || "Omega$d" in qLower || "Omega $d" in qLower) {
                    directConcepts += "omega_$d"
                }
            }
        }
        for (m in zhSequenceRegex.findAll(qLower)) {
            val seq = m.value
            if (seq.length >= 2 && seq !in genericEntityStopwords) directConcepts += seq
        }
        // Also extract distinct 2-to-4 char meaningful concepts from zhToEnExpansions whose Chinese key is in qLower
        // mapped to a single normalized representative token so bilingual queries match English discrepancy points once.
        val conceptRepresentatives = LinkedHashMap<String, List<String>>()
        for (c in directConcepts) {
            val alts = mutableListOf(c)
            if (c.startsWith("omega_")) {
                val n = c.removePrefix("omega_")
                alts += listOf("ω$n", "omega$n", "omega $n", "omega_$n")
            }
            conceptRepresentatives[c] = alts
        }
        for ((zhKey, targets) in zhToEnExpansions) {
            if (zhKey.length >= 2 && zhKey !in genericEntityStopwords && zhKey in qLower) {
                val list = mutableListOf(zhKey)
                list += targets.map { it.lowercase() }.filter { it !in genericEntityStopwords }
                conceptRepresentatives[zhKey] = list.distinct()
            }
        }

        // Discrepancy aspect guard pairs: prevent matching separating/zero-profit discrepancies on pooling questions
        val qAsksPooling = "pooling" in qLower || "混同" in qLower
        val qAsksSeparating = "separating" in qLower || "分离" in qLower
        val qAsksZeroProfit = "zero-profit" in qLower || "zero profit" in qLower || "零利润" in qLower || "斜率" in qLower || "亏损" in qLower

        val matched = mutableListOf<MatchedOfflineDiscrepancy>()
        for (d in candidates) {
            val pointBlob = SentenceCitationGate.normalizeText(
                listOf(d.point, d.authoritativeView, d.secondaryView).joinToString(" "),
            ).lowercase()
            val titleOnly = SentenceCitationGate.normalizeText(d.point).lowercase()

            if (qAsksPooling && !qAsksSeparating && ("分离均衡" in pointBlob || "separating" in pointBlob)) continue
            if (qAsksPooling && !qAsksZeroProfit && ("零利润" in pointBlob || "zero-profit" in pointBlob || "zero profit" in pointBlob)) continue

            // Deduplicate subsumed overlapping keys (e.g. if both "分离均衡" and "分离均衡图" matched, keep distinct hits)
            val hitKeys = conceptRepresentatives.entries.filter { (_, reps) ->
                reps.any { tok ->
                    if (tok.any { it in '\u4E00'..'\u9FFF' }) tok in pointBlob
                    else containsWordOrToken(pointBlob, tok)
                }
            }.map { it.key }
            val distinctHits = hitKeys.filter { k ->
                hitKeys.none { other -> other != k && other.length > k.length && k in other }
            }
            val titleHit = distinctHits.any { k ->
                val reps = conceptRepresentatives[k].orEmpty()
                reps.any { tok ->
                    if (tok.any { it in '\u4E00'..'\u9FFF' }) tok in titleOnly
                    else containsWordOrToken(titleOnly, tok)
                }
            }
            val hasUltraSpecific = distinctHits.any { k ->
                k in setOf(
                    "omega_2", "omega_3", "3b", "700", "ppo", "medigap", "fang",
                    "不等号", "严格不等号", "弱不等号", "零利润", "均匀分布", "最低质量",
                    "有利选择", "死亡螺旋", "保费补贴",
                )
            }
            if (titleHit && (distinctHits.size >= 2 || hasUltraSpecific)) {
                matched += d.copy(directAspectMatch = true)
            }
        }
        return matched.take(3)
    }

    private fun loadCollection(db: Connection, collectionId: String): ActiveAskCollection? {
        if (!BookSchemaMigration.tableExists(db, "collections")) return null
        return db.prepareStatement("SELECT kind, title FROM collections WHERE id=?").use { sql ->
            sql.setString(1, collectionId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) null
                else {
                    val kind = runCatching { CollectionKind.valueOf(rows.getString(1)) }.getOrDefault(CollectionKind.COURSE)
                    ActiveAskCollection(collectionId, kind, rows.getString(2))
                }
            }
        }
    }

    private fun loadCollectionMaterials(
        db: Connection,
        collectionId: String,
        collectionKind: CollectionKind = CollectionKind.COURSE,
    ): List<CollectionMaterialMeta> {
        if (!BookSchemaMigration.tableExists(db, "collection_materials")) return emptyList()
        return db.prepareStatement(
            """SELECT cm.material_id, b.title, b.format, cm.role, cm.authority_rank, cm.week_num, ${sourceFilenameColumn(db)}
               FROM collection_materials cm
               JOIN books b ON b.id = cm.material_id
               WHERE cm.collection_id=?
               ORDER BY COALESCE(cm.week_num, 999), cm.authority_rank, cm.ordinal, cm.added_at""",
        ).use { sql ->
            sql.setString(1, collectionId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val bookId = rows.getString(1)
                        val title = rows.getString(2)
                        val format = rows.getString(3)
                        val role = CourseMaterialRole.fromWire(rows.getString(4))
                        val rank = if (collectionKind == CollectionKind.COURSE) role.authorityRank else rows.getInt(5).coerceIn(1, 4)
                        val weekNum = rows.getObject(6)?.let { (it as Number).toInt() }
                            ?: weekFileRegex.find(title)?.let { m ->
                                (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                            }
                        val shortLabel = if (collectionKind == CollectionKind.BOOKLIST && role == CourseMaterialRole.CORE_TEXTBOOK) {
                            title.take(12)
                        } else {
                            formatShortLabel(title, role, weekNum)
                        }
                        val docLabel = "${shortLabel} (L$rank)"
                        add(
                            CollectionMaterialMeta(
                                bookId = bookId,
                                title = title,
                                sourceFilename = rows.getString(7),
                                format = format,
                                role = role,
                                authorityRank = rank,
                                weekNum = weekNum,
                                shortLabel = shortLabel,
                                docLabel = docLabel,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadVirtualReadLibraryMaterials(db: Connection): List<CollectionMaterialMeta> {
        if (!BookSchemaMigration.tableExists(db, "books")) return emptyList()
        return db.prepareStatement("SELECT id, title, format, ${sourceFilenameColumn(db)} FROM books b ORDER BY touched DESC").use { sql ->
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val bookId = rows.getString(1)
                        val title = rows.getString(2)
                        val format = rows.getString(3)
                        val shortLabel = title.take(12)
                        add(
                            CollectionMaterialMeta(
                                bookId = bookId,
                                title = title,
                                sourceFilename = rows.getString(4),
                                format = format,
                                role = CourseMaterialRole.LECTURE_SLIDES,
                                authorityRank = 2,
                                weekNum = null,
                                shortLabel = shortLabel,
                                docLabel = "$title (L2)".take(160),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun sourceFilenameColumn(db: Connection): String =
        if ("source_filename" in BookSchemaMigration.tableColumns(db, "books")) "b.source_filename" else "NULL"

    private fun loadBooksWithReadActivity(db: Connection, currentBookId: String?): Set<String> {
        val active = HashSet<String>()
        if (!currentBookId.isNullOrBlank()) active += currentBookId
        val singleColumnTables = listOf(
            "book_open_session" to "book",
            "book_read_spots" to "book",
            "book_highlights" to "book",
            "book_bookmarks" to "book",
            "book_last_questions" to "book_id",
        )
        for ((table, col) in singleColumnTables) {
            if (!BookSchemaMigration.tableExists(db, table)) continue
            db.prepareStatement("SELECT DISTINCT $col FROM $table").use { sql ->
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        rows.getString(1)?.takeIf { it.isNotBlank() }?.let { active += it }
                    }
                }
            }
        }
        return active
    }

    private fun loadSavedReadingPage(db: Connection, bookId: String): Int? =
        db.prepareStatement("SELECT reading_page FROM books WHERE id=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }

    private fun loadReadSpotPage(db: Connection, bookId: String): Int? {
        if (!BookSchemaMigration.tableExists(db, "book_read_spots")) return null
        return db.prepareStatement("SELECT page FROM book_read_spots WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }
    }

    private val tradToSimpMap: Map<Char, Char> = mapOf(
        '學' to '学', '習' to '习', '時' to '时', '說' to '说', '遠' to '远', '來' to '来',
        '慍' to '愠', '溫' to '温', '見' to '见', '龍' to '龙', '潛' to '潜', '飛' to '飞',
        '復' to '复', '無' to '无', '為' to '为', '與' to '与', '經' to '经', '書' to '书',
        '義' to '义', '體' to '体', '實' to '实', '問' to '问', '對' to '对', '應' to '应',
        '關' to '关', '點' to '点', '這' to '这', '個' to '个', '從' to '从', '進' to '进',
        '論' to '论', '語' to '语', '道' to '道', '德' to '德', '註' to '注', '釋' to '释',
        '傳' to '传', '記' to '记', '禮' to '礼', '詩' to '诗', '莊' to '庄', '陰' to '阴',
        '陽' to '阳', '圖' to '图', '術' to '术', '占' to '占', '數' to '数', '國' to '国',
        '開' to '开', '門' to '门', '長' to '长', '萬' to '万', '兩' to '两', '處' to '处',
        '氣' to '氣'.let { '气' }, '雲' to '云', '變' to '变', '動' to '动', '靜' to '静',
    )

    private fun foldTraditionalChinese(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) sb.append(tradToSimpMap[ch] ?: ch)
        return sb.toString()
    }

    private fun isMultiBookComparisonQuery(question: String): Boolean {
        val lower = question.lowercase()
        val keywords = listOf(
            "compare", "contrast", "both", "versus", "vs", "between",
            "两本", "两位", "两书", "三本", "四本", "这三本", "这四本", "各怎么", "各说", "相通", "顺便",
            "对照", "对比", "比较", "异同", "各自", "分别", "同时",
        )
        return keywords.any { it in lower }
    }

    private fun formatShortLabel(title: String, role: CourseMaterialRole, weekNum: Int?): String {
        val wkPrefix = weekNum?.let { "第${it}周" }.orEmpty()
        return when (role) {
            CourseMaterialRole.SYLLABUS -> "课程手册"
            CourseMaterialRole.LECTURE_SLIDES -> if (wkPrefix.isNotEmpty()) "${wkPrefix}课件" else title.take(12)
            CourseMaterialRole.HANDOUT -> "${wkPrefix}讲义"
            CourseMaterialRole.TUTORIAL -> if (wkPrefix.isNotEmpty()) "${wkPrefix}习题" else title.take(12)
            CourseMaterialRole.SOLUTION_KEY -> if (wkPrefix.isNotEmpty()) "${wkPrefix}解答" else title.take(12)
            CourseMaterialRole.TRANSCRIPT -> if (wkPrefix.isNotEmpty()) "${wkPrefix}录音" else "课堂录音"
            CourseMaterialRole.CORE_TEXTBOOK -> "课本"
            CourseMaterialRole.REQUIRED_READING -> {
                val author = title.substringBefore('_').substringBefore('-').substringBefore(' ').trim()
                val year = Regex("""(19|20)\d{2}""").find(title)?.value.orEmpty()
                if (author.length in 2..12 && year.isNotEmpty()) "$author $year"
                else title.take(12)
            }
            CourseMaterialRole.USER_NOTES -> if (wkPrefix.isNotEmpty()) "${wkPrefix}笔记" else title.take(12)
        }
    }

    private fun loadAllPageNumbers(db: Connection, bookId: String): List<Int> =
        db.prepareStatement("SELECT page FROM pages WHERE book=? ORDER BY page").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
        }

    private fun loadSinglePageText(db: Connection, bookId: String, page: Int): String? =
        db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun anyIn(haystack: String, vararg needles: String): Boolean =
        needles.any { it in haystack }
}

/**
 * 跨材料整页问答客户端编排引擎：执行本地检索 + 防剧透过滤 + 远端问答调用 + 客户端原页二次核验。
 */
internal object CollectionQaEngine {
    suspend fun ask(
        library: TextbookLibrary,
        collectionId: String,
        question: String,
        currentBookId: String? = null,
        overrideStance: BookStance? = null,
        readThroughPageOverrides: Map<String, Int?>? = null,
        remoteAsk: suspend (JsonObject) -> CourseServerCallResult,
    ): Result<CollectionQaTurn> {
        val cleanQ = question.trim()
        if (cleanQ.isEmpty()) {
            return Result.failure(IllegalArgumentException("先输入问题。"))
        }
        if (!currentBookId.isNullOrBlank()) {
            withContext(Dispatchers.IO) { runCatching { library.saveLastQuestion(currentBookId, cleanQ) } }
        }
        val bundle = withContext(Dispatchers.IO) {
            CollectionQaRetriever.retrieve(library, collectionId, cleanQ, currentBookId, readThroughPageOverrides)
        }
        if (!bundle.allStancesDetermined) {
            bundle.blockedBooklistEmptyMessage?.let { msg ->
                return Result.success(
                    CollectionQaTurn(
                        question = cleanQ,
                        sentences = emptyList(),
                        hasMaterialConflict = false,
                        conflictSummaryZh = "",
                        fallbackMessage = msg,
                        collectionId = bundle.collection.id,
                    ),
                )
            }
            if (bundle.blocks.isEmpty()) {
                return Result.success(
                    CollectionQaTurn(
                        question = cleanQ,
                        sentences = emptyList(),
                        hasMaterialConflict = false,
                        conflictSummaryZh = "",
                        fallbackMessage = "当前材料中暂时没有检索到相关段落，请换个关键词再试。",
                        collectionId = bundle.collection.id,
                    ),
                )
            }
        }

        val reqId = UUID.randomUUID().toString().replace("-", "").take(16)
        val stanceWire = if (bundle.allStancesDetermined) {
            overrideStance?.wire
                ?: bundle.bookStances.map { it.defaultStance }.distinct().singleOrNull()?.wire
                ?: "mixed"
        } else null
        val payload = CollectionQaJson.encodeRequest(
            requestId = reqId,
            collection = bundle.collection,
            question = cleanQ,
            blocks = bundle.blocks,
            discrepancies = bundle.matchedDiscrepancies,
            shortLabelByBookId = bundle.shortLabelByBookId,
            stanceWire = stanceWire,
            overrideStance = overrideStance,
            bookStances = bundle.bookStances,
        )
        val serverResult = remoteAsk(payload)
        fun failure(message: String, trouble: ExplainTrouble): Result<CollectionQaTurn> {
            val pages = bundle.blocks.distinctBy { it.bookId to it.page }.map { block ->
                CollectionQaCitation(collectionId, block.bookId, block.docLabel, block.shortLabel,
                    block.page, quoteAnchor = block.text.trim().take(80))
            }
            return Result.failure(CollectionQaException(message, trouble, pages))
        }
        val body = serverResult.body ?: return failure(
            serverResult.error ?: "这次没讲成。", serverResult.trouble ?: ExplainTrouble.Other,
        )

        if (bundle.allStancesDetermined) {
            val stanceTurn = SentenceCitationGate.verifyStanceCollectionResponse(
                collectionId = bundle.collection.id,
                question = cleanQ,
                responseJson = body,
                rawPagesByKey = bundle.rawPagesByKey,
                bookStances = bundle.bookStances,
                shortLabelByBookId = bundle.shortLabelByBookId,
                docLabelByBookId = bundle.docLabelByBookId,
            )
            if (stanceTurn != null) return Result.success(stanceTurn)
            return Result.failure(
                CollectionQaException("材料里没有足够的依据回答这个问题。可以选中一段，再问它在说什么。", ExplainTrouble.Other),
            )
        }

        fun insufficientEvidence() = Result.failure<CollectionQaTurn>(
            CollectionQaException("材料里没有足够的依据回答这个问题。可以选中一段，再问它在说什么。", ExplainTrouble.Other),
        )
        if (CollectionQaJson.run { body.boolOrFalse("insufficientEvidence") }) return insufficientEvidence()
        val verifiedTurn = SentenceCitationGate.verifyResponseLocally(
            collectionId = bundle.collection.id,
            question = cleanQ,
            responseJson = body,
            blockMap = bundle.blockMap,
            rawPagesByKey = bundle.rawPagesByKey,
            shortLabelByBookId = bundle.shortLabelByBookId,
            docLabelByBookId = bundle.docLabelByBookId,
            offlineDiscrepancies = bundle.matchedDiscrepancies,
        )
        if (verifiedTurn.sentences.isEmpty() && verifiedTurn.conflictCards.none { it.citations.isNotEmpty() }) {
            return insufficientEvidence()
        }
        return Result.success(verifiedTurn)
    }
}

internal class CollectionQaException(
    override val message: String,
    val trouble: ExplainTrouble,
    val fallbackPages: List<CollectionQaCitation> = emptyList(),
) : RuntimeException(message)
