package com.coach.desktop.books

import com.coach.client.CourseHttp
import com.coach.client.MockReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in real 20-question end-to-end evaluation (`COACH_AI3_LIVE_EVAL=1`) running through the
 * live Kotlin `CollectionQaEngine` -> local Coach Server `/v1/library/collection/ask` -> isolated
 * Coach Bridge (`Haiku 5.5` streaming under the 95% API grant guard, ZERO CodeBuddy) over all
 * 16 imported `ECNM10119` materials, plus a real skip-read `BOOKLIST` anti-spoiler test.
 *
 * Outputs sanitized citation metadata (NO copyrighted full-page text) to
 * `~/Coach/state/v2-impl/2.0-AI-3/e2e-20q-report.json` for cross-platform `winbox` verification.
 */
class CollectionQaLiveEvalTest {

    private data class QuestionSpec(
        val id: String,
        val expectedConflictId: String?,
        val conflictAllowed: Boolean = false,
        val question: String,
    )

    @Test
    fun realCourseTwentyNaturalQuestionsAndBooklistAntiSpoiler() = runBlocking {
        assumeTrue("Opt-in live eval requires COACH_AI3_LIVE_EVAL=1", System.getenv("COACH_AI3_LIVE_EVAL") == "1")
        val serverUrl = requireNotNull(System.getenv("COACH_AI3_SERVER_URL")).trim()
        val deviceId = requireNotNull(System.getenv("COACH_AI3_DEVICE_ID")).trim()
        val token = requireNotNull(System.getenv("COACH_AI3_TOKEN")).trim()

        val matRoot = Path.of(System.getProperty("user.home"), "Coach/materials/ECNM10119-mcq-src")
        assumeTrue("Course materials directory must exist", Files.isDirectory(matRoot))

        val relPaths = listOf(
            "参考资料/ECNM10119-Module-Handbook-updated.pdf",
            "参考资料/Week1/ECNM10119-Week1-Lecture-Slides.pdf",
            "参考资料/Week2/ECNM10119-Week2-Lecture-Slides.pdf",
            "参考资料/Week3/ECNM10119-Week3-Lecture-Slides.pdf",
            "参考资料/Week4/ECNM10119-Week4-Lecture-Slides.pdf",
            "参考资料/Week4/ECNM10119-Tutorial-Week4.pdf",
            "参考资料/Week5/ECNM10119-Week5-Lecture-Slides.pdf",
            "参考资料/Week5/ECNM10119-Tutorial-Week5.pdf",
            "参考资料/Week5/ECNM10119-Tutorial-Week5-KEY.pdf",
            "参考资料/Week6/ECNM10119-Week6-Lecture-Slides.pdf",
            "参考资料/Week8/ECNM10119-Week8-Lecture-Slides.pdf",
            "参考资料/Bhattacharya-Hyde-Tu-2014-Health-Economics.pdf",
            "参考资料/Required-Readings/Fang_Keane_Silverman2008_NBERw12289.pdf",
            "参考资料/Required-Readings/Finkelstein2012_Oregon_NBERw17190.pdf",
            "参考资料/Required-Readings/Kessler_McClellan2000_NBERw5596.pdf",
            "课堂录音/Week4-16Oct-转写.md",
        )

        val tmpRoot = Files.createTempDirectory("coach-ai3-live-eval-")
        val http = CourseHttp(serverUrl, deviceId, token, "library")
        try {
            val lib = TextbookLibrary(tmpRoot.resolve("library"), ocr = BookOcr { _, _ -> "" }, lazyPdfExtras = true)
            val bookByRel = LinkedHashMap<String, Textbook>()
            val relByBookId = LinkedHashMap<String, String>()
            for (rel in relPaths) {
                val p = matRoot.resolve(rel)
                require(Files.isRegularFile(p)) { "Missing material: $p" }
                val imported = lib.importBook(p)
                bookByRel[rel] = imported
                relByBookId[imported.id] = rel
            }

            // Build Course collection & align topics
            val courseId = lib.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db,
                    title = "ECNM10119 Health Economics",
                    materialBookIds = bookByRel.values.map { it.id },
                )
                assertEquals(0, rejected.size)
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }

            // Seed W2-C offline discrepancies onto course_topics.pack_payload_json (anchored dynamically to imported pages)
            val w4Trans = bookByRel.getValue("课堂录音/Week4-16Oct-转写.md")
            val w4Tut = bookByRel.getValue("参考资料/Week4/ECNM10119-Tutorial-Week4.pdf")
            val w5Slides = bookByRel.getValue("参考资料/Week5/ECNM10119-Week5-Lecture-Slides.pdf")
            val w5Key = bookByRel.getValue("参考资料/Week5/ECNM10119-Tutorial-Week5-KEY.pdf")
            val w4Slides = bookByRel.getValue("参考资料/Week4/ECNM10119-Week4-Lecture-Slides.pdf")
            val bht = bookByRel.getValue("参考资料/Bhattacharya-Hyde-Tu-2014-Health-Economics.pdf")
            val fang = bookByRel.getValue("参考资料/Required-Readings/Fang_Keane_Silverman2008_NBERw12289.pdf")

            fun findPageQuote(book: Textbook, phrase: String, fallbackPage: Int): Pair<Int, String> {
                for (pg in book.pages) {
                    val norm = SentenceCitationGate.normalizeText(pg.text)
                    val idx = norm.indexOf(phrase, ignoreCase = true)
                    if (idx >= 0) {
                        val end = minOf(norm.length, idx + maxOf(phrase.length, 32))
                        return pg.number to norm.substring(idx, end)
                    }
                }
                val fb = book.page(fallbackPage) ?: book.pages.first()
                return fb.number to SentenceCitationGate.normalizeText(fb.text).take(40)
            }

            val rConf1a = findPageQuote(w4Trans, "strict inequality", 1)
            val rConf1b = findPageQuote(w4Tut, "700", 2)
            val rConf2a = findPageQuote(w5Slides, "northeast", 18)
            val rConf2b = findPageQuote(bht, "zero-profit line", 197)
            val rConf3a = findPageQuote(w5Slides, "separating", 24)
            val rConf3b = findPageQuote(bht, "partial insurance", 207)
            val rConf4a = findPageQuote(w4Slides, "3B", 47)
            val rConf4b = findPageQuote(w5Key, "100", 1)
            val rConf5a = findPageQuote(w5Slides, "Medigap", 46)
            val rConf5b = findPageQuote(fang, "advantageous selection", 2)
            val rConf6a = findPageQuote(w5Slides, "Cutler", 39)
            val rConf6b = findPageQuote(bht, "Harvard", 219)

            lib.withCourseDatabase { db ->
                val detail = CourseStore.loadCourseDetail(db, courseId)
                val targetTopic = detail.allTopics.first()
                val pack = CourseTopicPack(
                    topicId = targetTopic.topicId,
                    summary = "Cross-material discrepancy index for ECNM10119",
                    authorityNotice = "L1 slides/transcript/key precede L2 textbook and L3 empirical readings.",
                    discrepancies = listOf(
                        CourseDiscrepancyItem(
                            point = "第4周讨论课保险合同不等号（严格不等号 vs 弱不等号、700）",
                            authoritativeView = "录音 42:41 明确纠正为严格不等号 strict inequality（> 700）",
                            secondaryView = "同一段讨论课前句曾口误为弱不等号",
                            advice = "以录音 42:41 自纠口径为准：严格不等号 > 700",
                            citations = listOf(
                                CourseCitationRef(w4Trans.id, w4Trans.title, CourseMaterialRole.TRANSCRIPT, rConf1a.first, 0, 10, "42:41", rConf1a.second),
                                CourseCitationRef(w4Tut.id, w4Tut.title, CourseMaterialRole.TUTORIAL, rConf1b.first, 0, 10, null, rConf1b.second),
                            ),
                        ),
                        CourseDiscrepancyItem(
                            point = "Rothschild-Stiglitz 零利润线斜率与亏损方位（右上方 northeast vs 右侧 right）",
                            authoritativeView = "第5周讲义以消费横纵轴画图，标为右上方 northeast 亏损",
                            secondaryView = "课本以保额保费轴标为右侧亏损",
                            advice = "两处方向等价，取决于纵轴方向",
                            citations = listOf(
                                CourseCitationRef(w5Slides.id, w5Slides.title, CourseMaterialRole.LECTURE_SLIDES, rConf2a.first, 0, 10, null, rConf2a.second),
                                CourseCitationRef(bht.id, bht.title, CourseMaterialRole.CORE_TEXTBOOK, rConf2b.first, 0, 10, null, rConf2b.second),
                            ),
                        ),
                        CourseDiscrepancyItem(
                            point = "Rothschild-Stiglitz 分离均衡健壮人群部分保险合同符号 Ω2 vs Ω3",
                            authoritativeView = "第5周讲义在部分图中采用不同角标 Ω2/Ω3 记号",
                            secondaryView = "课本采用另一角标编号",
                            advice = "注意核对讲义与课本中的合同符号 Ω2 与 Ω3 对应关系",
                            citations = listOf(
                                CourseCitationRef(w5Slides.id, w5Slides.title, CourseMaterialRole.LECTURE_SLIDES, rConf3a.first, 0, 10, null, rConf3a.second),
                                CourseCitationRef(bht.id, bht.title, CourseMaterialRole.CORE_TEXTBOOK, rConf3b.first, 0, 10, null, rConf3b.second),
                            ),
                        ),
                        CourseDiscrepancyItem(
                            point = "Akerlof 均匀分布最低质量下限下最高成交价是否永远直接套 3B",
                            authoritativeView = "第5周习题解答指出当触发质量上限 100 封顶时不再适用 3B，需按 (B+100)/2 等边界重算",
                            secondaryView = "讲义内点解直接写为 3B",
                            advice = "先检查质量分布上限是否触及 100，再决定能否用 3B",
                            citations = listOf(
                                CourseCitationRef(w4Slides.id, w4Slides.title, CourseMaterialRole.LECTURE_SLIDES, rConf4a.first, 0, 10, null, rConf4a.second),
                                CourseCitationRef(w5Key.id, w5Key.title, CourseMaterialRole.SOLUTION_KEY, rConf4b.first, 0, 10, null, rConf4b.second),
                            ),
                        ),
                        CourseDiscrepancyItem(
                            point = "Medigap 有利选择（advantageous selection）是否主要由风险厌恶驱动",
                            authoritativeView = "Fang et al. (2008) 论文实证反驳：主要由认知能力而非风险厌恶驱动",
                            secondaryView = "传统理论或讲义初始假说侧重风险厌恶偏好",
                            advice = "实证结论以 Fang 2008 为准，否定风险厌恶为主因",
                            citations = listOf(
                                CourseCitationRef(fang.id, fang.title, CourseMaterialRole.REQUIRED_READING, rConf5b.first, 0, 10, null, rConf5b.second),
                                CourseCitationRef(w5Slides.id, w5Slides.title, CourseMaterialRole.LECTURE_SLIDES, rConf5a.first, 0, 10, null, rConf5a.second),
                            ),
                        ),
                        CourseDiscrepancyItem(
                            point = "哈佛大学 1990 年代保费补贴改革 PPO 死亡螺旋实证文献署名差异",
                            authoritativeView = "讲义与论文常引用 Cutler & Reber (1998)",
                            secondaryView = "课本部分章节提及 Cutler & Zeckhauser",
                            advice = "注意辨析两篇相关哈佛医疗保险实证文献作者署名",
                            citations = listOf(
                                CourseCitationRef(w5Slides.id, w5Slides.title, CourseMaterialRole.LECTURE_SLIDES, rConf6a.first, 0, 10, null, rConf6a.second),
                                CourseCitationRef(bht.id, bht.title, CourseMaterialRole.CORE_TEXTBOOK, rConf6b.first, 0, 10, null, rConf6b.second),
                            ),
                        ),
                    ),
                )
                CourseStore.saveTopicPack(db, targetTopic.topicId, pack, targetTopic.packSourcesHash.orEmpty())
            }

            // 20 natural Chinese questions (ZERO pinnedDocs, ZERO answer searchTerms!)
            val questions = listOf(
                QuestionSpec("QA-01", "R-CONF-1", question = "第 4 周讨论课讲保险合同那道题时，判断不买保险的条件用的是严格不等号还是弱不等号？健康状态下的收入下界是多少？"),
                QuestionSpec("QA-02", "R-CONF-2", question = "在 Rothschild-Stiglitz 模型里，零利润线的斜率公式怎么写？在零利润线的哪个方位保险公司会亏损？"),
                QuestionSpec("QA-03", "R-CONF-3", question = "在 Rothschild-Stiglitz 分离均衡图里，健康（robust）人群买到的部分保险合同是用 Ω2 还是 Ω3 表示？"),
                QuestionSpec("QA-04", "R-CONF-4", question = "Akerlof 均匀分布医疗保险模型里，如果买家估值加成是 B、政府设最低质量下限，最高能成交的价格是不是永远直接套 3B？"),
                QuestionSpec("QA-05", "R-CONF-5", question = "美国老年补充医保 Medigap 市场里出现的有利选择（advantageous selection），主要是由风险厌恶程度驱动的吗？"),
                QuestionSpec("QA-06", "R-CONF-6", question = "哈佛大学 1990 年代改革医保保费补贴、引发 PPO 死亡螺旋的实证案例是哪篇文献研究的？"),
                QuestionSpec("QA-07", null, question = "RAND 医疗保险实验的核心价格弹性大约是多少？它识别的和俄勒冈医保实验（Oregon HIE）有什么不同？"),
                QuestionSpec("QA-08", null, question = "Grossman 模型里为什么大学毕业生的最优健康存量高于高中辍学者？"),
                QuestionSpec("QA-09", null, question = "Grossman 模型里年龄增长是怎么通过折旧率导致最优健康存量下降、直至生命终点的？"),
                QuestionSpec("QA-10", null, question = "计算需求弧弹性（arc price elasticity）为什么要用中点或两期之和作分母，而不是直接用起点价格和起点数量？"),
                QuestionSpec("QA-11", null, question = "医生劳动力市场有哪些典型的进入壁垒（barriers to entry），它们怎么推高医生收入？"),
                QuestionSpec("QA-12", null, question = "什么是防御性医疗（defensive medicine）？Kessler 和 McClellan (1996) 利用美国各州的什么法律改革识别了它的影响，得出了什么结论？"),
                QuestionSpec("QA-13", null, question = "为什么风险厌恶的消费者愿意支付高于期望损失（actuarially fair premium）的保费来买全额保险？"),
                QuestionSpec("QA-14", null, question = "在 Rothschild-Stiglitz 模型里，为什么混同均衡（pooling equilibrium）永远不可能存在？"),
                QuestionSpec("QA-15", null, question = "事前道德风险（ex ante moral hazard）和事后道德风险（ex post moral hazard）有什么区别？"),
                QuestionSpec("QA-16", null, conflictAllowed = true, question = "在新药临床研发流程中，进入 Phase I 的候选药物大约有多大比例进入 Phase III，最终有多大比例通过 Phase III？"),
                QuestionSpec("QA-17", null, question = "衡量医疗技术成本效果比（ICER）时，分子和分母分别是什么？什么时候一项新技术在无需做复杂权衡下就直接占优？"),
                QuestionSpec("QA-18", null, question = "在测量 QALY 质量权重时，标准博弈法（SG）和时间权衡法（TTO）得出的权重通常哪个更高？为什么？"),
                QuestionSpec("QA-19", null, question = "本门课 ECNM10119 的课程手册规定期中与期末评估的权重比例分别是多少？期末题型是最终确定还是暂定？"),
                QuestionSpec("QA-20", null, question = "在存在道德风险的医疗市场里，如果患者对某项急救服务的需求完全无弹性（竖直需求线），设共保率 c > 0 还能减少社会无谓损失吗？"),
            )

            var totalDeliveredCitations = 0
            var macVerifiedCitations = 0
            var conflictRecall = 0
            var falsePositives = 0
            val reportItems = mutableListOf<JsonObject>()

            for (spec in questions) {
                val turn = CollectionQaEngine.ask(
                    library = lib,
                    collectionId = courseId,
                    question = spec.question,
                    remoteAsk = { payload ->
                        val r = http.askCollection(payload)
                        CourseServerCallResult(r.body, r.error, ExplainTrouble.of(r.statusCode, r.code))
                    },
                ).getOrThrow()

                val citArr = buildJsonArray {
                    for (s in turn.sentences) {
                        totalDeliveredCitations++
                        val rel = relByBookId.getValue(s.citation.bookId)
                        val book = bookByRel.getValue(rel)
                        val pageText = book.page(s.citation.page)?.text.orEmpty()
                        val gate = SentenceCitationGate.verifyOrSnapAnchor(s.citation.quoteAnchor, pageText)
                        if (gate.verified) macVerifiedCitations++
                        add(buildJsonObject {
                            put("relPath", rel)
                            put("page", s.citation.page)
                            s.citation.timestampLabel?.let { put("timestampLabel", it) }
                            put("quoteAnchor", s.citation.quoteAnchor)
                            put("macVerified", gate.verified)
                        })
                    }
                }
                if (spec.expectedConflictId != null && turn.hasMaterialConflict) conflictRecall++
                if (spec.expectedConflictId == null && !spec.conflictAllowed && turn.hasMaterialConflict) falsePositives++

                reportItems += buildJsonObject {
                    put("id", spec.id)
                    put("expectedConflictId", spec.expectedConflictId ?: "")
                    put("conflictAllowed", spec.conflictAllowed)
                    put("deliveredCitations", turn.sentences.size)
                    put("droppedUnverifiedCount", turn.droppedUnverifiedCount)
                    put("hasMaterialConflict", turn.hasMaterialConflict)
                    put("conflictCardCount", turn.conflictCards.size)
                    put("citations", citArr)
                }
            }

            // Also run real-book skip-read booklist anti-spoiler check (Ch1 READ vs unread Ch2/Fang2008)
            val booklistId = "booklist-ecnm-antisp"
            lib.upsertCollection(MaterialCollectionRecord(booklistId, CollectionKind.BOOKLIST, "ECNM 书单防剧透验证"))
            lib.attachMaterial(CollectionMaterialRecord(booklistId, bht.id, ordinal = 0))
            lib.attachMaterial(CollectionMaterialRecord(booklistId, fang.id, ordinal = 1))
            val bhtCh1 = BookChapterResolver.chapters(bht).first()
            lib.setChapterManualReadState(bht.id, bhtCh1.tocNodeId, bhtCh1.pagesTotal, read = true)
            val booklistBundle = CollectionQaRetriever.retrieve(lib, booklistId, "Medigap advantageous selection Fang cognitive")
            val booklistSpoilers = booklistBundle.blocks.count { it.bookId == fang.id || it.page !in bhtCh1.firstPage..bhtCh1.lastPage }

            val macPassRate = if (totalDeliveredCitations == 0) 0.0 else macVerifiedCitations.toDouble() / totalDeliveredCitations.toDouble()
            val reportDir = Path.of(System.getProperty("user.home"), "Coach/state/v2-impl/2.0-AI-3")
            Files.createDirectories(reportDir)
            val report = buildJsonObject {
                put("questionsEvaluated", questions.size)
                put("totalDeliveredCitations", totalDeliveredCitations)
                put("macVerifiedCitations", macVerifiedCitations)
                put("macPassRate", macPassRate)
                put("rCardConflictRecall", conflictRecall)
                put("rCardConflictTotal", 6)
                put("nonConflictFalsePositives", falsePositives)
                put("booklistUnreadSpoilers", booklistSpoilers)
                put("items", JsonArray(reportItems))
            }
            Files.writeString(reportDir.resolve("e2e-20q-report.json"), report.toString())

            assertTrue("Expected >= 20 delivered citations, got $totalDeliveredCitations", totalDeliveredCitations >= 20)
            assertTrue("Mac citation pass rate $macPassRate < 0.98", macPassRate >= 0.98)
            assertTrue("R-card conflict recall $conflictRecall < 5/6", conflictRecall >= 5)
            assertEquals("Non-conflict false positives must be 0", 0, falsePositives)
            assertEquals("Booklist skip-read unread spoilers must be 0", 0, booklistSpoilers)
        } finally {
            http.close()
            tmpRoot.toFile().deleteRecursively()
        }
    }
}
