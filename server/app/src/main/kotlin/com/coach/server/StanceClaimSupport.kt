package com.coach.server

import java.util.Locale

/**
 * 逐句「陈述 ↔ 引文」词面相关性粗筛（TON-1976 §3 规则 1 / TON-1977）：
 * - 只做词面粗筛：陈述与它自己引的原句没有任何共同实词、数字或词表对应时丢句，
 *   用来拦掉两句共用一条真实引文、第二句与原句毫不相干的情况。
 * - 不判断语义蕴含：与原句意思相反、但用词相同的陈述（如否定、范围相反）会通过。
 *   语义是否支持由读者逐句点回原句和验收逐句核对把关（TON-1977 第二次退回裁定）。
 * - 同语种（中文↔中文含繁简与古今通假、英文↔英文）核验实词/数字重叠；
 * - 跨语种（中文陈述↔英文引文）核验直接 ASCII/数字对齐 + 英文实词词元→中文词根对齐（严格排除纯虚词）。
 */
internal object StanceClaimSupport {
    private val latinTokenRegex = Regex("""[A-Za-z]{2,}""")
    private val digitTokenRegex = Regex("""\d+(?:\.\d+)?%?""")
    private val cjkCharRegex = Regex("""[\u4e00-\u9fff]""")

    /** 英文纯语法虚词：绝不作为跨语种支撑依据（防止 "only" → "只" 放过无关句）。 */
    private val enStopwords = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
        "in", "on", "at", "to", "of", "for", "by", "with", "from", "as", "into", "onto",
        "about", "over", "under", "through", "during", "before", "after", "upon", "per",
        "than", "then", "that", "this", "these", "those", "it", "its", "itself",
        "they", "them", "their", "theirs", "themselves",
        "he", "him", "his", "himself", "she", "her", "hers", "herself",
        "we", "us", "our", "ours", "ourselves", "you", "your", "yours", "yourself", "i", "me", "my", "mine", "myself",
        "and", "or", "but", "if", "so", "nor", "yet", "though", "although", "because", "since", "unless", "until", "while", "whereas",
        "not", "no", "none", "neither", "only", "just", "also", "too", "very", "quite", "rather", "merely", "even", "still", "already",
        "more", "most", "much", "many", "some", "any", "all", "each", "every", "both", "few", "several", "other", "another", "such",
        "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "here", "there", "now", "ever", "never",
        "will", "would", "shall", "should", "can", "could", "may", "might", "must", "ought",
        "do", "does", "did", "done", "doing", "have", "has", "had", "having", "get", "got",
        "one", "ones", "own", "same", "well", "way", "thing", "things",
    )

    /** 中文纯虚词与元话语字：不单独充当同语种实词重合证据。 */
    private val zhStopChars: Set<Char> = (
        "的了在是有和与这那一个中里上下之其为以而于也者则故此被把将对从向到会能可要就都还只最更很" +
            "说讲写指称原文书句段作者注认表示出来去着过得地种座名并及或但且若虽因所即乃亦无不非未勿" +
            "他她它们我你您自己己彼此某各每些种条项件位次处方面点里内外部前左右" +
            "一二三四五六七八九十百千万两零"
        ).toSet()

    private val irregularLemmas = mapOf(
        "went" to "go", "gone" to "go", "goes" to "go",
        "came" to "come", "comes" to "come",
        "saw" to "see", "seen" to "see",
        "grew" to "grow", "grown" to "grow", "grows" to "grow", "growing" to "grow",
        "wrote" to "write", "written" to "write",
        "spoke" to "speak", "spoken" to "speak",
        "took" to "take", "taken" to "take",
        "gave" to "give", "given" to "give",
        "found" to "find", "thought" to "think", "felt" to "feel",
        "kept" to "keep", "left" to "leave", "held" to "hold",
        "told" to "tell", "said" to "say", "made" to "make",
        "knew" to "know", "known" to "know",
        "became" to "become", "began" to "begin", "begun" to "begin",
        "ran" to "run", "sat" to "sit", "stood" to "stand",
        "met" to "meet", "led" to "lead", "lost" to "lose", "won" to "win",
        "paid" to "pay", "bought" to "buy", "sold" to "sell",
        "brought" to "bring", "built" to "build", "sent" to "send", "spent" to "spend",
        "understood" to "understand", "drew" to "draw", "drawn" to "draw",
        "drove" to "drive", "driven" to "drive", "chose" to "choose", "chosen" to "choose",
        "broke" to "break", "broken" to "break", "fell" to "fall", "fallen" to "fall",
        "rose" to "rise", "risen" to "rise", "fought" to "fight", "caught" to "catch",
        "taught" to "teach", "sought" to "seek", "flew" to "fly", "flown" to "fly",
        "froze" to "freeze", "frozen" to "freeze", "bore" to "bear", "born" to "bear", "borne" to "bear",
        "children" to "child", "men" to "man", "women" to "woman", "people" to "person",
        "feet" to "foot", "teeth" to "tooth", "mice" to "mouse", "geese" to "goose",
        "leaves" to "leaf", "lives" to "life", "wives" to "wife", "knives" to "knife", "wolves" to "wolf",
        "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad",
        "greater" to "great", "greatest" to "great", "less" to "little", "least" to "little",
        "higher" to "high", "highest" to "high", "lower" to "low", "lowest" to "low",
        "larger" to "large", "largest" to "large", "smaller" to "small", "smallest" to "small",
        "older" to "old", "oldest" to "old", "elder" to "old", "younger" to "young", "youngest" to "young",
        "stronger" to "strong", "strongest" to "strong", "weaker" to "weak", "weakest" to "weak",
        "simpler" to "simple", "simplest" to "simple", "happier" to "happy", "happiest" to "happy",
        "easier" to "easy", "easiest" to "easy", "harder" to "hard", "hardest" to "hard",
        "fittest" to "fit", "fitter" to "fit",
    )

    private val enWordNumbers = mapOf(
        "zero" to "0", "one" to "1", "first" to "1",
        "two" to "2", "second" to "2", "twice" to "2", "double" to "2",
        "three" to "3", "third" to "3",
        "four" to "4", "fourth" to "4",
        "five" to "5", "fifth" to "5",
        "six" to "6", "sixth" to "6",
        "seven" to "7", "seventh" to "7",
        "eight" to "8", "eighth" to "8",
        "nine" to "9", "ninth" to "9",
        "ten" to "10", "tenth" to "10",
        "eleven" to "11", "twelve" to "12", "dozen" to "12",
        "thirteen" to "13", "fourteen" to "14", "fifteen" to "15",
        "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19",
        "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "hundred" to "100", "thousand" to "1000", "million" to "1000000",
    )

    /** 常用繁简对照表（覆盖古籍《论语》《道德经》《周易》与常见中文文本）。 */
    private val tradToSimp: Map<Char, Char> = buildMap {
        val pairs = listOf(
            '學' to '学', '習' to '习', '時' to '时', '說' to '说', '遠' to '远', '來' to '来',
            '慍' to '愠', '溫' to '温', '見' to '见', '龍' to '龙', '潛' to '潜', '飛' to '飞',
            '復' to '复', '無' to '无', '為' to '为', '與' to '与', '經' to '经', '書' to '书',
            '義' to '义', '體' to '体', '實' to '实', '問' to '问', '對' to '对', '應' to '应',
            '關' to '关', '點' to '点', '這' to '这', '個' to '个', '從' to '从', '進' to '进',
            '過' to '过', '還' to '还', '當' to '当', '發' to '发', '現' to '现', '開' to '开',
            '長' to '长', '門' to '门', '間' to '间', '聞' to '闻', '離' to '离', '難' to '难',
            '雲' to '云', '風' to '风', '馬' to '马', '魚' to '鱼', '鳥' to '鸟', '黃' to '黄',
            '國' to '国', '園' to '园', '種' to '种', '論' to '论', '語' to '语', '傳' to '传',
            '註' to '注', '隱' to '隐', '贊' to '赞', '晉' to '晋', '陰' to '阴', '陽' to '阳',
            '剛' to '刚', '動' to '动', '靜' to '静', '變' to '变', '萬' to '万', '辭' to '辞',
            '貞' to '贞', '厲' to '厉', '終' to '终', '純' to '纯', '誠' to '诚', '閑' to '闲',
            '業' to '业', '幾' to '几', '聖' to '圣', '賢' to '贤', '貴' to '贵', '賤' to '贱',
            '極' to '极', '爭' to '争', '處' to '处', '眾' to '众', '衆' to '众', '惡' to '恶',
            '淵' to '渊', '觀' to '观', '戶' to '户', '歸' to '归', '滅' to '灭', '絶' to '绝',
            '絕' to '绝', '棄' to '弃', '盜' to '盗', '賊' to '贼', '樸' to '朴', '慾' to '欲',
            '穀' to '谷', '沖' to '冲', '銳' to '锐', '紛' to '纷', '塵' to '尘', '氣' to '气',
            '專' to '专', '嬰' to '婴', '兒' to '儿', '滌' to '涤', '覽' to '览', '愛' to '爱',
            '闔' to '阖', '達' to '达', '輻' to '辐', '轂' to '毂', '鑿' to '凿', '榮' to '荣',
            '寵' to '宠', '驚' to '惊', '託' to '托', '視' to '视', '聽' to '听', '繩' to '绳',
            '紀' to '纪', '執' to '执', '禦' to '御', '猶' to '犹', '儼' to '俨', '渙' to '涣',
            '釋' to '释', '曠' to '旷', '渾' to '浑', '濁' to '浊', '沒' to '没', '譽' to '誉',
            '廢' to '废', '親' to '亲', '亂' to '乱', '憂' to '忧', '臺' to '台', '餘' to '余',
            '悶' to '闷', '頑' to '顽', '異' to '异', '師' to '师', '樂' to '乐', '禮' to '礼',
            '藝' to '艺', '齊' to '齐', '舉' to '举', '錯' to '错', '諸' to '诸', '直' to '直',
            '枉' to '枉', '則' to '则', '鮮' to '鲜', '巧' to '巧', '令' to '令', '顏' to '颜',
            '黨' to '党', '鄉' to '乡', '鄰' to '邻', '里' to '里', '術' to '术', '擇' to '择',
            '謹' to '谨', '節' to '节', '愛' to '爱', '眾' to '众', '餘' to '余', '則' to '则',
            '文' to '文', '質' to '质', '彬' to '彬', '勝' to '胜', '野' to '野', '史' to '史',
            '過' to '过', '憚' to '惮', '改' to '改', '慎' to '慎', '追' to '追', '歸' to '归',
            '厚' to '厚', '抑' to '抑', '與' to '与', '溫' to '温', '良' to '良', '恭' to '恭',
            '儉' to '俭', '讓' to '让', '貧' to '贫', '諂' to '谄', '富' to '富', '驕' to '骄',
            '切' to '切', '磋' to '磋', '琢' to '琢', '磨' to '磨', '詩' to '诗', '書' to '书',
            '易' to '易', '禮' to '礼', '春' to '春', '秋' to '秋', '傳' to '传', '記' to '记',
            '稱' to '称', '謂' to '谓', '讀' to '读', '譯' to '译', '解' to '解', '釋' to '释',
            '號' to '号', '顯' to '显', '隱' to '隐', '藏' to '藏', '躍' to '跃', '淵' to '渊',
            '亢' to '亢', '悔' to '悔', '群' to '群', '羣' to '群', '首' to '首', '吉' to '吉',
            '凶' to '凶', '亨' to '亨', '利' to '利', '元' to '元', '乾' to '乾', '坤' to '坤',
            '彖' to '彖', '象' to '象', '爻' to '爻', '卦' to '卦', '繫' to '系', '辭' to '辞',
            '陰' to '阴', '陽' to '阳', '剛' to '刚', '柔' to '柔', '健' to '健', '順' to '顺',
            '確' to '确', '確' to '确', '拔' to '拔', '遯' to '遁', '悶' to '闷', '樂' to '乐',
            '憂' to '忧', '違' to '违', '確' to '确', '德' to '德', '施' to '施', '普' to '普',
            '閑' to '闲', '存' to '存', '誠' to '诚', '善' to '善', '伐' to '伐', '博' to '博',
            '化' to '化', '進' to '进', '脩' to '修', '修' to '修', '辭' to '辞', '立' to '立',
            '誠' to '诚', '居' to '居', '業' to '业', '幾' to '几', '存' to '存', '義' to '义',
            '終' to '终', '驕' to '骄', '憂' to '忧', '惕' to '惕', '咎' to '咎', '聲' to '声',
            '氣' to '气', '溼' to '湿', '燥' to '燥', '親' to '亲', '類' to '类', '動' to '动',
            '靜' to '静', '貴' to '贵', '高' to '高', '賢' to '贤', '輔' to '辅', '窮' to '穷',
            '災' to '灾', '體' to '体', '會' to '会', '合' to '合', '幹' to '干', '貞' to '贞',
            '固' to '固', '實' to '实', '虛' to '虚', '損' to '损', '益' to '益', '盈' to '盈',
            '謙' to '谦', '滿' to '满', '招' to '招', '覆' to '覆', '載' to '载', '廣' to '广',
            '備' to '备', '萬' to '万', '資' to '资', '統' to '统', '雲' to '云', '雨' to '雨',
            '施' to '施', '品' to '品', '流' to '流', '形' to '形', '終' to '终', '始' to '始',
            '位' to '位', '時' to '时', '乘' to '乘', '御' to '御', '變' to '变', '化' to '化',
            '正' to '正', '性' to '性', '命' to '命', '保' to '保', '太' to '太', '和' to '和',
            '庶' to '庶', '寧' to '宁', '寧' to '宁', '體' to '体', '總' to '总', '結' to '结',
            '構' to '构', '轉' to '转', '換' to '换', '獨' to '独', '斷' to '断', '決' to '决',
            '選' to '选', '擇' to '择', '機' to '机', '會' to '会', '條' to '条', '件' to '件',
            '價' to '价', '錢' to '钱', '買' to '买', '賣' to '卖', '貨' to '货', '幣' to '币',
            '產' to '产', '業' to '业', '勞' to '劳', '動' to '动', '資' to '资', '財' to '财',
            '經' to '经', '濟' to '济', '學' to '学', '科' to '科', '術' to '术', '醫' to '医',
            '藥' to '药', '療' to '疗', '診' to '诊', '斷' to '断', '護' to '护', '養' to '养',
            '訴' to '诉', '訟' to '讼', '權' to '权', '責' to '责', '罰' to '罚', '罪' to '罪',
            '戰' to '战', '爭' to '争', '勝' to '胜', '敗' to '败', '軍' to '军', '兵' to '兵',
            '馬' to '马', '車' to '车', '船' to '船', '飛' to '飞', '橋' to '桥', '路' to '路',
            '鐵' to '铁', '銅' to '铜', '銀' to '银', '金' to '金', '鐘' to '钟', '鏡' to '镜',
            '燈' to '灯', '紙' to '纸', '筆' to '笔', '畫' to '画', '圖' to '图', '表' to '表',
            '歷' to '历', '記' to '记', '錄' to '录', '證' to '证', '據' to '据', '驗' to '验',
            '測' to '测', '試' to '试', '算' to '算', '計' to '计', '數' to '数', '量' to '量',
            '雙' to '双', '對' to '对', '單' to '单', '複' to '复', '雜' to '杂', '簡' to '简',
            '繁' to '繁', '輕' to '轻', '重' to '重', '緩' to '缓', '急' to '急', '遲' to '迟',
            '早' to '早', '晚' to '晚', '晝' to '昼', '夜' to '夜', '歲' to '岁', '紀' to '纪',
            '樹' to '树', '葉' to '叶', '華' to '华', '實' to '实', '麥' to '麦', '稻' to '稻',
            '糧' to '粮', '菜' to '菜', '菓' to '果', '梨' to '梨', '桃' to '桃', '蘋' to '苹',
        )
        for ((t, s) in pairs) put(t, s)
    }

    /** 文言→现代常用实词同义扩展（用于古籍白话转述核验）。 */
    private val classicalGlosses: Map<Char, List<String>> = mapOf(
        '说' to listOf("悦", "喜悦", "高兴", "开心", "愉快"),
        '习' to listOf("温习", "复习", "练习", "实践", "实习"),
        '朋' to listOf("朋友", "同门", "友人", "志同道合"),
        '愠' to listOf("怨恨", "生气", "恼怒", "不满"),
        '潜' to listOf("潜藏", "隐藏", "隐伏", "蛰伏"),
        '勿' to listOf("不要", "不宜", "不可", "切忌", "暂不"),
        '见' to listOf("显现", "出现", "现身", "拜见"),
        '飞' to listOf("飞翔", "腾飞", "高飞"),
        '故' to listOf("旧", "旧知", "原有"),
        '温' to listOf("温习", "复习", "重温"),
        '师' to listOf("老师", "为师", "教导"),
        '常' to listOf("恒常", "永恒", "不变"),
        '名' to listOf("命名", "名称", "概念"),
        '善' to listOf("善", "美善", "美好", "善于"),
        '争' to listOf("争", "竞争", "争抢", "争夺"),
        '处' to listOf("处", "居处", "停留", "身处"),
        '恶' to listOf("厌恶", "讨厌", "嫌弃"),
        '几' to listOf("接近", "近似"),
        '曰' to listOf("说", "讲", "指出"),
        '子' to listOf("孔子", "先生"),
        '复' to listOf("复", "又", "按语"),
        '造' to listOf("形", "具体", "塑造"),
        '事' to listOf("事物", "具体", "行事"),
        '形' to listOf("形体", "具体", "形象"),
        '利' to listOf("有利", "适合", "有益"),
        '田' to listOf("田野", "地面", "人间"),
        '天' to listOf("天空", "高处", "高位"),
    )

    private val enToZhLexicon: Map<String, List<String>> by lazy { loadLexicon() }

    private fun loadLexicon(): Map<String, List<String>> {
        val stream = StanceClaimSupport::class.java.getResourceAsStream("/com/coach/server/en_zh_support_lexicon.tsv")
            ?: return emptyMap()
        val out = HashMap<String, MutableList<String>>(4096)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) continue
                val key = line.substring(0, tab).trim().lowercase(Locale.ROOT)
                if (key.isEmpty() || key in enStopwords) continue
                val terms = line.substring(tab + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (terms.isEmpty()) continue
                val bucket = out.getOrPut(key) { mutableListOf() }
                for (t in terms) {
                    if (t !in bucket) bucket += t
                }
            }
        }
        return out
    }

    internal fun toSimplified(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(tradToSimp[ch] ?: ch)
        }
        return sb.toString()
    }

    internal fun englishStems(rawWord: String): List<String> {
        val w = rawWord.lowercase(Locale.ROOT)
        if (w.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        out += w
        irregularLemmas[w]?.let { out += it }

        fun addStem(candidate: String) {
            if (candidate.length >= 3) {
                out += candidate
                irregularLemmas[candidate]?.let { out += it }
            }
        }

        if (w.endsWith("ies") && w.length > 4) addStem(w.dropLast(3) + "y")
        if (w.endsWith("ves") && w.length > 4) {
            addStem(w.dropLast(3) + "f")
            addStem(w.dropLast(3) + "fe")
        }
        if (w.endsWith("es") && w.length > 4) addStem(w.dropLast(2))
        if (w.endsWith("s") && !w.endsWith("ss") && w.length > 3) addStem(w.dropLast(1))

        if (w.endsWith("ing") && w.length > 5) {
            val base = w.dropLast(3)
            addStem(base)
            addStem(base + "e")
            if (base.length >= 4 && base[base.length - 1] == base[base.length - 2]) {
                addStem(base.dropLast(1))
            }
        }
        if (w.endsWith("ied") && w.length > 4) addStem(w.dropLast(3) + "y")
        if (w.endsWith("ed") && w.length > 4) {
            val base = w.dropLast(2)
            addStem(base)
            addStem(base + "e")
            if (base.length >= 4 && base[base.length - 1] == base[base.length - 2]) {
                addStem(base.dropLast(1))
            }
        }
        if (w.endsWith("ily") && w.length > 5) addStem(w.dropLast(3) + "y")
        if (w.endsWith("ly") && w.length > 4) {
            val base = w.dropLast(2)
            addStem(base)
            addStem(base + "e")
        }
        if (w.endsWith("ier") && w.length > 4) addStem(w.dropLast(3) + "y")
        if (w.endsWith("iest") && w.length > 5) addStem(w.dropLast(4) + "y")
        if (w.endsWith("er") && w.length > 4) {
            addStem(w.dropLast(2))
            addStem(w.dropLast(1))
        }
        if (w.endsWith("est") && w.length > 5) {
            addStem(w.dropLast(3))
            addStem(w.dropLast(2))
        }
        if (w.endsWith("ness") && w.length > 6) {
            val base = w.dropLast(4)
            addStem(base)
            if (base.endsWith("i")) addStem(base.dropLast(1) + "y")
        }
        if (w.endsWith("ment") && w.length > 6) addStem(w.dropLast(4))
        if (w.endsWith("ation") && w.length > 7) {
            addStem(w.dropLast(5) + "e")
            addStem(w.dropLast(5))
            addStem(w.dropLast(3))
        }
        if (w.endsWith("tion") && w.length > 6) {
            addStem(w.dropLast(4) + "te")
            addStem(w.dropLast(3))
        }
        if (w.endsWith("ity") && w.length > 5) {
            addStem(w.dropLast(3))
            addStem(w.dropLast(3) + "e")
        }
        if (w.endsWith("able") && w.length > 6) {
            addStem(w.dropLast(4))
            addStem(w.dropLast(4) + "e")
        }
        if (w.endsWith("ful") && w.length > 5) addStem(w.dropLast(3))
        if (w.endsWith("less") && w.length > 6) addStem(w.dropLast(4))
        if (w.endsWith("al") && w.length > 5) addStem(w.dropLast(2))
        if (w.endsWith("ic") && w.length > 5) {
            addStem(w.dropLast(2))
            addStem(w.dropLast(2) + "y")
        }
        return out.toList()
    }

    private val yaoLabelRegex = Regex("""(?:初[六九]|[六九][二三四五]|上[六九]|用[九六])""")
    private val zhNonNumericIdioms = listOf(
        "千方百计", "百分比", "百分号", "百分率", "百分之",
        "百姓", "百科", "百般", "千万", "万物", "万一",
        "十分", "十足", "十字", "十翼",
        "零工", "零星", "零售", "零件", "零食", "零碎",
        "双手", "双方", "双向", "双重",
    )
    private val zhCompoundTenRegex = Regex("""([二三四五六七八九])?十([一二三四五六七八九])?""")
    private val zhUnitNumRegex = Regex("""([一二两三四五六七八九])(百|千|万)""")
    private val zhDigitMap = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '双' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private fun extractCanonicalNumbers(text: String, forClaim: Boolean = false): Set<String> {
        val nums = HashSet<String>()
        for (m in digitTokenRegex.findAll(text)) {
            nums += m.value.removeSuffix("%")
        }
        for (m in latinTokenRegex.findAll(text)) {
            val w = m.value.lowercase(Locale.ROOT)
            if (forClaim && w == "one") continue
            enWordNumbers[w]?.let { nums += it }
        }
        var simp = toSimplified(text)
        if (forClaim) {
            simp = yaoLabelRegex.replace(simp, " ")
        }
        for (word in zhNonNumericIdioms) {
            simp = simp.replace(word, " ")
        }
        if ("百万" in simp) {
            nums += "1000000"
            simp = simp.replace("百万", " ")
        }
        simp = zhCompoundTenRegex.replace(simp) { m ->
            val tens = m.groupValues[1].firstOrNull()?.let { zhDigitMap[it] } ?: 1
            val ones = m.groupValues[2].firstOrNull()?.let { zhDigitMap[it] } ?: 0
            nums += (tens * 10 + ones).toString()
            " "
        }
        simp = zhUnitNumRegex.replace(simp) { m ->
            val digit = zhDigitMap[m.groupValues[1].first()] ?: 1
            val unit = when (m.groupValues[2].first()) {
                '百' -> 100
                '千' -> 1000
                else -> 10_000
            }
            nums += (digit * unit).toString()
            " "
        }
        for ((ch, value) in zhDigitMap) {
            if (forClaim && ch == '一') continue
            if (ch in simp) nums += value.toString()
        }
        if ('百' in simp) nums += "100"
        if ('千' in simp) nums += "1000"
        if ('万' in simp) nums += "10000"
        return nums
    }

    private fun extractLatinContentStems(text: String): Set<String> {
        val out = HashSet<String>()
        for (m in latinTokenRegex.findAll(text)) {
            val raw = m.value.lowercase(Locale.ROOT)
            if (raw.length < 3 || raw in enStopwords) continue
            for (st in englishStems(raw)) {
                if (st !in enStopwords) out += st
            }
        }
        return out
    }

    private fun hasCjk(text: String): Boolean = cjkCharRegex.containsMatchIn(text)

    /**
     * 核验单句 [claimText] 是否与它自己的引用原文（[citations] 中的 `quote`）在内容上相符。
     */
    fun isSupported(
        claimText: String,
        citations: List<CheckedStanceCitation>,
        blocks: List<StanceSourceBlock>,
    ): Boolean {
        if (citations.isEmpty()) return false
        val evidenceQuote = citations.joinToString(" ") { it.quote }.trim()
        if (evidenceQuote.isEmpty()) return false

        val claimHasCjk = hasCjk(claimText)
        val evHasCjk = hasCjk(evidenceQuote)

        // 1. 直接数字对齐（含百分比与中文数字）：陈述中出现的每个数字都必须出现在该句全部引文中
        val claimNums = extractCanonicalNumbers(claimText, forClaim = true)
        val evNums = extractCanonicalNumbers(evidenceQuote, forClaim = false)
        if (claimNums.isNotEmpty()) {
            if (!claimNums.all { it in evNums }) return false
            // 仅 "1"（如 1 / 1%）过泛时不单独当支撑，仍需继续核验其余实词；其余数字全部命中即通过
            if (claimNums.any { it != "1" }) return true
        }

        // 2. 直接非虚词英文/拉丁词元对齐（如中英混排时提到原词或缩写，或纯英文↔英文）
        val claimLatin = extractLatinContentStems(claimText)
        val evLatin = extractLatinContentStems(evidenceQuote)
        if (claimLatin.isNotEmpty() && evLatin.isNotEmpty() && claimLatin.any { it in evLatin }) {
            return true
        }

        // 3. 纯英文陈述 ↔ 纯英文引文：必须在步骤 1 或 2 命中
        if (!claimHasCjk && !evHasCjk) {
            return evLatin.isEmpty() // 仅当引文本身无可识别英文实词时不误伤
        }

        // 4. 中文陈述 ↔ 中文引文（含繁简、古籍文言白话转述）
        if (claimHasCjk && evHasCjk) {
            val claimSimp = toSimplified(claimText)
            val evSimp = toSimplified(evidenceQuote)
            val claimChars = claimSimp.filter { it in '\u4e00'..'\u9fff' && it !in zhStopChars }.toSet()
            val evChars = evSimp.filter { it in '\u4e00'..'\u9fff' && it !in zhStopChars }.toSet()
            if (evChars.isEmpty()) return true
            if (claimChars.any { it in evChars }) return true
            // 古籍文言字→现代同义词扩展核验
            for (ch in evChars) {
                val glosses = classicalGlosses[ch] ?: continue
                if (glosses.any { it in claimSimp }) return true
            }
            // 同块古籍上下文（例如短引文为「潛龍勿用」而陈述写「初九」在同一爻块中）
            val blockSimp = toSimplified(blocks.joinToString(" ") { it.text })
            if (blockSimp.length <= 240) {
                val blockChars = blockSimp.filter { it in '\u4e00'..'\u9fff' && it !in zhStopChars }.toSet()
                val shared = claimChars.intersect(blockChars)
                if (shared.size >= 2) return true
            }
            return false
        }

        // 5. 跨语种：中文陈述 ↔ 英文引文
        if (claimHasCjk && !evHasCjk) {
            return checkChineseClaimAgainstEnglishEvidence(toSimplified(claimText), evidenceQuote)
        }

        // 6. 跨语种：英文陈述 ↔ 中文引文
        return checkChineseClaimAgainstEnglishEvidence(toSimplified(evidenceQuote), claimText)
    }

    /** 英文归属框架词干（仅允许描述书中说法，绝不收动作、资产、行业、时间、结果类词）。 */
    private val enHighRiskFramingStems: Set<String> = setOf(
        "book", "text", "passage", "author", "section", "chapter", "original", "verse",
        "line", "commentary", "phrase", "term", "word", "image", "symbol", "emblem",
        "metaphor", "meaning", "mean", "sense", "say", "state", "describe", "explain",
        "mention", "note", "define", "present", "refer", "treat", "write", "depict",
        "call", "frame", "use", "represent", "symbolize", "indicate", "denote", "signify",
        "express", "illustrate", "show", "read", "quote", "cite", "gloss", "literal",
        "literary", "concept", "idea", "principle", "system",
    )

    /**
     * 高危直接回答的 100% 白名单全覆盖核验（TON-1982 第二次退回范围裁定第 1 条）：
     * 去掉归属框架词后，每个实词/实字都必须来自本句所引原句（不再按比例放行）：
     * - 中文对中文：每个非停用汉字必须在所引原句（繁简归一）、原句字对应的 `classicalGlosses` 释义字、或框架词表内；
     *   句中若夹带拉丁词（长度>=3 非停用词）或数字，也必须落在原句内。
     * - 英文对英文：每个长度>=3 的非停用英文词，词干必须在所引原句或英文框架词表内；数字必须落在原句内。
     * - 中文句对英文原句（如 Kybalion）：每个非停用汉字必须来自原句英文词在 `enToZhLexicon` 里的译名，
     *   或属于框架/停用字；词表映射不到即丢句（fail-closed）。
     * - 英文句对中文原句：每个长度>=3 的非停用英文词必须在英文框架词表内，或其 `enToZhLexicon` 译名出现在原句内。
     */
    internal fun isHighRiskFullyGrounded(
        sentenceText: String,
        citations: List<CheckedStanceCitation>,
        zhAttributionFramingTerms: List<String>,
    ): Boolean {
        if (citations.isEmpty()) return false
        val evidenceQuote = citations.joinToString(" ") { it.quote }.trim()
        if (evidenceQuote.isEmpty()) return false

        val sentenceHasCjk = hasCjk(sentenceText)
        val evHasCjk = hasCjk(evidenceQuote)

        val evLatinStems = extractLatinContentStems(evidenceQuote)
        val evNums = extractCanonicalNumbers(evidenceQuote)
        // 数字核验：句中出现的阿拉伯数字必须来自原句
        for (m in digitTokenRegex.findAll(sentenceText)) {
            val num = m.value.removeSuffix("%")
            if (num !in evNums) return false
        }

        // 1. 中文对中文：按字 100% 白名单核验
        if (sentenceHasCjk && evHasCjk) {
            // 若中文句夹带非停用英文词，其词干也必须落在原句中（防止夹带 ETF/BTC 等未出现词）
            for (m in latinTokenRegex.findAll(sentenceText)) {
                val raw = m.value.lowercase(Locale.ROOT)
                if (raw.length < 3 || raw in enStopwords) continue
                val stems = englishStems(raw).filter { it !in enStopwords }
                if (stems.none { it in evLatinStems }) return false
            }

            var stripped = toSimplified(sentenceText)
            for (term in zhAttributionFramingTerms) {
                stripped = stripped.replace(term, "")
            }
            val sentenceChars = stripped.filter { it in '\u4e00'..'\u9fff' && it !in zhStopChars }.toSet()
            if (sentenceChars.isEmpty()) {
                // 纯框架空壳（无任何来自原句的实字或英文词）不放行
                val sentenceLatin = extractLatinContentStems(sentenceText)
                return sentenceLatin.isNotEmpty() && sentenceLatin.all { it in evLatinStems }
            }

            val evSimp = toSimplified(evidenceQuote)
            val allowedChars = HashSet<Char>()
            for (ch in evSimp) {
                if (ch in '\u4e00'..'\u9fff') {
                    allowedChars += ch
                    classicalGlosses[ch]?.forEach { gloss ->
                        for (gCh in gloss) {
                            if (gCh in '\u4e00'..'\u9fff') allowedChars += gCh
                        }
                    }
                }
            }
            return sentenceChars.all { it in allowedChars }
        }

        // 2. 英文对英文：按词 100% 白名单核验（长度>=3 非停用词，词干须在原句或英文框架词表内）
        if (!sentenceHasCjk && !evHasCjk) {
            var matchedEvidenceWord = 0
            for (m in latinTokenRegex.findAll(sentenceText)) {
                val raw = m.value.lowercase(Locale.ROOT)
                if (raw.length < 3 || raw in enStopwords) continue
                val stems = englishStems(raw).filter { it !in enStopwords }
                val inEvidence = stems.any { it in evLatinStems }
                val inFraming = stems.any { it in enHighRiskFramingStems }
                if (!inEvidence && !inFraming) return false
                if (inEvidence) matchedEvidenceWord++
            }
            return matchedEvidenceWord >= 1
        }

        // 3. 中文句对英文原句（如 Kybalion）：每个非停用汉字必须来自原句英文词在 enToZhLexicon 的译名，映射不到即丢句
        if (sentenceHasCjk && !evHasCjk) {
            for (m in latinTokenRegex.findAll(sentenceText)) {
                val raw = m.value.lowercase(Locale.ROOT)
                if (raw.length < 3 || raw in enStopwords) continue
                val stems = englishStems(raw).filter { it !in enStopwords }
                if (stems.none { it in evLatinStems || it in enHighRiskFramingStems }) return false
            }

            val allowedLexiconChars = HashSet<Char>()
            for (m in latinTokenRegex.findAll(evidenceQuote)) {
                val raw = m.value.lowercase(Locale.ROOT)
                if (raw.length < 3 || raw in enStopwords) continue
                for (stem in englishStems(raw)) {
                    if (stem in enStopwords) continue
                    enToZhLexicon[stem]?.forEach { zhTerm ->
                        for (ch in toSimplified(zhTerm)) {
                            if (ch in '\u4e00'..'\u9fff') allowedLexiconChars += ch
                        }
                    }
                }
            }

            var stripped = toSimplified(sentenceText)
            for (term in zhAttributionFramingTerms) {
                stripped = stripped.replace(term, "")
            }
            val sentenceChars = stripped.filter { it in '\u4e00'..'\u9fff' && it !in zhStopChars }.toSet()
            if (sentenceChars.isEmpty()) {
                val sentenceLatin = extractLatinContentStems(sentenceText)
                return sentenceLatin.isNotEmpty() && sentenceLatin.any { it in evLatinStems }
            }
            if (allowedLexiconChars.isEmpty()) return false
            return sentenceChars.all { it in allowedLexiconChars }
        }

        // 4. 英文句对中文原句：每个非停用英文词必须在框架词表内，或其译名落在原句中
        val evSimp = toSimplified(evidenceQuote)
        var matchedEvidenceWord = 0
        for (m in latinTokenRegex.findAll(sentenceText)) {
            val raw = m.value.lowercase(Locale.ROOT)
            if (raw.length < 3 || raw in enStopwords) continue
            val stems = englishStems(raw).filter { it !in enStopwords }
            val inFraming = stems.any { it in enHighRiskFramingStems }
            val inEvidence = stems.any { stem ->
                enToZhLexicon[stem]?.any { zhTerm -> toSimplified(zhTerm) in evSimp } == true
            }
            if (!inEvidence && !inFraming) return false
            if (inEvidence) matchedEvidenceWord++
        }
        return matchedEvidenceWord >= 1
    }

    private fun checkChineseClaimAgainstEnglishEvidence(claimSimp: String, englishText: String): Boolean {
        val rawWords = latinTokenRegex.findAll(englishText)
            .map { it.value.lowercase(Locale.ROOT) }
            .filter { it.length >= 3 && it !in enStopwords }
            .distinct()
            .toList()
        if (rawWords.isEmpty()) return true

        var recognizedCount = 0
        var matchedCount = 0
        for (word in rawWords) {
            val zhCandidates = LinkedHashSet<String>()
            for (stem in englishStems(word)) {
                if (stem in enStopwords) continue
                enToZhLexicon[stem]?.let { zhCandidates.addAll(it) }
            }
            if (zhCandidates.isEmpty()) continue
            recognizedCount++
            if (zhCandidates.any { term -> term in claimSimp }) {
                matchedCount++
            }
        }

        if (matchedCount >= 1) return true
        if (recognizedCount >= 1) return false

        // 若引文中所有英文词均未进入通用词表，检查英文词是否直接出现在陈述中（已于步骤 2 检查过）
        // 或极罕见专词无法离线判伪时保持谨慎不误杀
        return true
    }
}
