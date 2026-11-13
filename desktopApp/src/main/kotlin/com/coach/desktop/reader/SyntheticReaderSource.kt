package com.coach.desktop.reader

import com.coach.client.CourseHttp
import com.coach.client.ReaderReply
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 合成样例文档数据源（样例用合成文档）。
 *
 * 假数据窗口已替换为使用本数据源或真实服务消费 ReaderHttpSource；
 * 包含合成真实结构的两份资料（教材 orchard-text 与阅读 orchard-read），支持按页加载、
 * 页图生成、词框坐标（QuoteLocator 引文匹配）、题目关联及读写进度。
 */
class SyntheticReaderSource : ReaderSource {
    data class PageData(
        val pdfPage: Int,
        val printedPage: Int?,
        val text: String,
        val anchorPhrase: String,
        val questionStem: String,
    )

    data class DocData(
        val id: String,
        val title: String,
        val version: String,
        val pages: List<PageData>,
    ) {
        var progressPage: Int = 1
        var revision: Long = 0L
        val notes = mutableListOf<JsonObject>()
    }

    private val docs: Map<String, DocData> = mapOf(
        "orchard-text" to DocData(
            id = "orchard-text",
            title = "果园讲义（合成文档）",
            version = "orchard-v1",
            pages = listOf(
                PageData(
                    pdfPage = 1,
                    printedPage = 11,
                    text = "果园 建设 与 管理 基础 。 第一章 选址 与 土壤 改良 。 果园讲义第1页核心原则 ： 土壤 排水 与 有机质 是 丰产 基石 。 规范 栽植 密度 ， 合理 规划 授粉 树 配置 。",
                    anchorPhrase = "土壤 排水 与 有机质 是 丰产 基石",
                    questionStem = "关于果园选址与土壤改良的核心原则单选题？",
                ),
                PageData(
                    pdfPage = 2,
                    printedPage = 12,
                    text = "果树 修剪 与 花果 管理 。 第二章 疏花 疏果 与 留枝 。 果园讲义第2页核心原则 ： 保持 树冠 通风 透光 ， 适度 负载 保障 果实 品质 与 树势 稳健 。",
                    anchorPhrase = "保持 树冠 通风 透光",
                    questionStem = "关于果树修剪通风透光要求的考题？",
                ),
                PageData(
                    pdfPage = 3,
                    printedPage = 13,
                    text = "病虫害 综合 防治 技术 。 第三章 绿色 防控 与 生物 制剂 。 果园讲义第3页核心原则 ： 预防 为主 ， 综合 防治 ， 优先 保护 利用 天敌 昆虫 。",
                    anchorPhrase = "预防 为主 ， 综合 防治",
                    questionStem = "关于病虫害绿色综合防控方针的考核？",
                ),
                PageData(
                    pdfPage = 4,
                    printedPage = 14,
                    text = "节水 灌溉 与 水肥 一体化 。 第四章 滴灌 与 微喷 技术 。 果园讲义第4页核心原则 ： 按 物候期 规律 精准 供水 供肥 ， 少量 多次 提高 吸收率 。",
                    anchorPhrase = "按 物候期 规律 精准 供水 供肥",
                    questionStem = "关于水肥一体化物候期规律的题目？",
                ),
                PageData(
                    pdfPage = 5,
                    printedPage = 15,
                    text = "采收 贮藏 与 分级 包装 。 第五章 采收期 测定 与 冷链 运输 。 果园讲义第5页核心原则 ： 适期 采收 ， 轻装 轻卸 ， 及时 预冷 抑制 呼吸 跃变 。",
                    anchorPhrase = "适期 采收 ， 轻装 轻卸",
                    questionStem = "关于果实采后预冷与冷链物流的题目？",
                ),
            ),
        ),
        "orchard-read" to DocData(
            id = "orchard-read",
            title = "拓展阅读（合成文档）",
            version = "read-v1",
            pages = listOf(
                PageData(
                    pdfPage = 1,
                    printedPage = 1,
                    text = "现代 果园 生态 栽培 前沿 。 第一节 生物 多样性 与 微生态 系统 调控 研究 进展 。",
                    anchorPhrase = "生物 多样性 与 微生态 系统",
                    questionStem = "关于微生态调控的拓展研究题？",
                ),
                PageData(
                    pdfPage = 2,
                    printedPage = 2,
                    text = "智慧 农业 与 无人 农机 应用 。 第二节 果园 巡检 机器视觉 与 变量 喷雾 装备 。",
                    anchorPhrase = "果园 巡检 机器视觉",
                    questionStem = "关于智慧果园视觉巡检的题目？",
                ),
                PageData(
                    pdfPage = 3,
                    printedPage = 3,
                    text = "采后 生理 与 气调 保鲜 。 第三节 减压 贮藏 对 货架期 风味 物质 的 保持 机理 。",
                    anchorPhrase = "减压 贮藏 对 货架期",
                    questionStem = "关于气调保鲜机理的题目？",
                ),
            ),
        ),
    )

    private val seenMutations = mutableMapOf<String, Long>()

    private fun sha256(str: String): String =
        MessageDigest.getInstance("SHA-256").digest(str.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override suspend fun catalog(): ReaderReply {
        val list = docs.values.map { d ->
            buildJsonObject {
                put("id", d.id)
                put("title", d.title)
                put("version", d.version)
                put("pages", d.pages.size)
            }
        }
        return ReaderReply(200, buildJsonObject { put("documents", JsonArray(list)) })
    }

    override suspend fun page(document: String, pdfPage: Int): ReaderReply {
        val doc = docs[document] ?: return ReaderReply(404, null)
        val p = doc.pages.firstOrNull { it.pdfPage == pdfPage } ?: return ReaderReply(404, null)
        val body = buildJsonObject {
            put("documentId", doc.id)
            put("version", doc.version)
            put("pdfPage", p.pdfPage)
            p.printedPage?.let { put("printedPage", it) }
            put("text", p.text)
            put("textSha256", sha256(p.text))
        }
        return ReaderReply(200, body)
    }

    override suspend fun image(document: String, pdfPage: Int, version: String?): ByteArray? {
        val doc = docs[document] ?: return null
        if (version != null && version != doc.version) return null
        if (doc.pages.none { it.pdfPage == pdfPage }) return null
        // 生成规范的页图位图（600x800），与 boxes 的宽高比 0.75 严格一致，供 Compose 绘制
        val width = 600
        val height = 800
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocN32Pixels(width, height)
        val canvas = org.jetbrains.skia.Canvas(bitmap)
        val bgPaint = org.jetbrains.skia.Paint().apply { color = 0xFFFBF9F5.toInt() }
        canvas.drawRect(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()), bgPaint)
        val borderPaint = org.jetbrains.skia.Paint().apply {
            color = 0xFFD8D2C4.toInt()
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(16f, 16f, width - 32f, height - 32f), borderPaint)
        return org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData()?.bytes
    }

    override suspend fun study(document: String, pdfPage: Int): ReaderReply {
        val doc = docs[document] ?: return ReaderReply(404, null)
        val p = doc.pages.firstOrNull { it.pdfPage == pdfPage } ?: return ReaderReply(404, null)
        val questions = JsonArray(listOf(
            buildJsonObject {
                put("id", "q-${doc.id}-$pdfPage-1")
                put("stem", p.questionStem)
                put("status", "unanswered")
                put("anchor", p.anchorPhrase)
            }
        ))
        val body = buildJsonObject {
            put("documentId", doc.id)
            put("pdfPage", pdfPage)
            put("unlocated", 0)
            put("available", true)
            put("questions", questions)
        }
        return ReaderReply(200, body)
    }

    override suspend fun boxes(document: String, pdfPage: Int, version: String): ReaderReply {
        val doc = docs[document] ?: return ReaderReply(404, null)
        if (version != doc.version) return ReaderReply(409, buildJsonObject { put("error", "资料版本已改变，请重新定位") })
        val p = doc.pages.firstOrNull { it.pdfPage == pdfPage } ?: return ReaderReply(404, null)
        val tokens = p.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val words = mutableListOf<JsonObject>()
        var y = 60.0
        val lineHeight = 36.0
        var x = 40.0
        for (token in tokens) {
            val tokenWidth = maxOf(24.0, token.length * 16.0)
            if (x + tokenWidth > 540.0) {
                x = 40.0
                y += lineHeight
            }
            words += buildJsonObject {
                put("x0", x)
                put("y0", y)
                put("x1", x + tokenWidth)
                put("y1", y + 26.0)
                put("text", token)
            }
            x += tokenWidth + 8.0
        }
        val body = buildJsonObject {
            put("documentId", doc.id)
            put("version", doc.version)
            put("pdfPage", pdfPage)
            put("width", 600.0)
            put("height", 800.0)
            put("words", JsonArray(words))
        }
        return ReaderReply(200, body)
    }

    override suspend fun saveNote(document: String, body: JsonObject): ReaderReply {
        val doc = docs[document] ?: return ReaderReply(404, null)
        val id = (body["id"] as? JsonPrimitive)?.contentOrNull ?: return ReaderReply(400, null)
        val mutationId = (body["mutationId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val rev = seenMutations[mutationId] ?: run {
            val next = ++doc.revision
            if (mutationId.isNotBlank()) seenMutations[mutationId] = next
            doc.notes.removeAll { (it["id"] as? JsonPrimitive)?.contentOrNull == id }
            doc.notes.add(buildJsonObject {
                for ((k, v) in body) put(k, v)
                put("revision", next)
            })
            next
        }
        return ReaderReply(200, buildJsonObject { put("id", id); put("revision", rev) })
    }

    override suspend fun state(document: String): ReaderReply {
        val doc = docs[document] ?: return ReaderReply(404, null)
        val body = buildJsonObject {
            put("document", buildJsonObject {
                put("id", doc.id)
                put("version", doc.version)
            })
            put("progress", buildJsonObject {
                put("pdfPage", doc.progressPage)
                put("version", doc.version)
                put("revision", doc.revision)
                put("stale", false)
            })
            put("notes", JsonArray(doc.notes))
        }
        return ReaderReply(200, body)
    }

    override suspend fun saveProgress(
        document: String,
        version: String,
        pdfPage: Int,
        baseRevision: Long,
        mutationId: String,
    ): ReaderReply {
        val doc = docs[document] ?: return ReaderReply(404, null)
        if (version != doc.version) return ReaderReply(409, buildJsonObject { put("error", "资料版本已改变，请重新定位") })
        val rev = seenMutations[mutationId] ?: run {
            val next = ++doc.revision
            doc.progressPage = pdfPage
            if (mutationId.isNotBlank()) seenMutations[mutationId] = next
            next
        }
        return ReaderReply(200, buildJsonObject {
            put("documentId", doc.id)
            put("version", doc.version)
            put("pdfPage", pdfPage)
            put("revision", rev)
        })
    }
}

/**
 * 资料来源解析器：优先读取已配置的隔离测试/生产服务（COACH_URL 与令牌），若无则提供内置合成样例文档。
 */
object ReaderSourceResolver {
    fun resolve(
        env: (String) -> String? = System::getenv,
        prop: (String) -> String? = System::getProperty,
    ): ReaderSource {
        val url = env("COACH_URL") ?: prop("coach.url")
        val course = env("COACH_COURSE") ?: prop("coach.course") ?: "orchard"
        val deviceId = env("COACH_DEVICE_ID") ?: "desktop"
        val token = env("COACH_TOKEN") ?: env("COACH_TOKEN_FILE")?.let { Path.of(it) }?.takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }

        if (url != null && token != null) {
            val http = CourseHttp(url, deviceId, token, course)
            return ReaderHttpSource(http.reader)
        }
        return SyntheticReaderSource()
    }
}
