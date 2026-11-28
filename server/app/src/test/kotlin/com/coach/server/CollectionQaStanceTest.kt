package com.coach.server

import com.coach.protocol.BookStanceCopy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionQaStanceTest {

    private fun mixedBooksSpec(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("bookId", "book-zhouyi")
            put("title", "周易（經文 + 十翼）")
            put("aliases", buildJsonArray { add(JsonPrimitive("周易")); add(JsonPrimitive("zhouyi")) })
            put("defaultStance", "esoteric")
            put("secondaryStance", "classic")
            put("guards", buildJsonArray { add(JsonPrimitive("esoteric")) })
        })
        add(buildJsonObject {
            put("bookId", "book-econ")
            put("title", "Principles of Economics, 2e")
            put("aliases", buildJsonArray { add(JsonPrimitive("经济学 2e")) })
            put("defaultStance", "knowledge")
            put("guards", JsonArray(emptyList()))
        })
        add(buildJsonObject {
            put("bookId", "book-pride")
            put("title", "Pride and Prejudice")
            put("defaultStance", "original")
            put("guards", buildJsonArray { add(JsonPrimitive("fiction")) })
            put("readThroughPage", 12)
            put("bookPages", 220)
        })
        add(buildJsonObject {
            put("bookId", "book-walden")
            put("title", "Walden")
            put("defaultStance", "life")
            put("guards", JsonArray(emptyList()))
        })
    }

    @Test
    fun sharedAnchorDropsUnsupportedSecondSentenceInCollectionPath() = runBlocking {
        val waldenPage = "I went to the woods because I wished to live deliberately, to front only the essential facts of life."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(
                systemPrompt: String,
                userPrompt: String,
                friend: String?,
                device: String?,
                maxTokens: Int,
            ): CollectionQaTransportReply {
                val modelJson = """{"paragraphs":[{"layer":"book","bookId":"book-walden","sentences":[
                    {"text":"梭罗到林中居住是为了有意识地生活并面对生活的本质。","anchors":["S1"],"quote":"live deliberately, to front only the essential facts of life"},
                    {"text":"他每天晚上都会乘火车去波士顿歌剧院听音乐会。","anchors":["S1"],"quote":"live deliberately, to front only the essential facts of life"}
                ]}],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "梭罗为什么去林子里住？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1")
                    put("bookId", "book-walden")
                    put("docLabel", "Walden")
                    put("page", 5)
                    put("text", waldenPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        val layers = res.getValue("layers").jsonArray
        assertEquals(1, layers.size)
        val sents = layers[0].jsonObject.getValue("sentences").jsonArray
        assertEquals(1, sents.size)
        assertTrue(sents[0].jsonObject.getValue("text").jsonPrimitive.content.contains("有意识地生活"))
        assertEquals(1, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
    }

    @Test
    fun misattributedBookIdOnSentenceIsDroppedAndCounted() = runBlocking {
        val waldenPage = "Simplicity, simplicity, simplicity! I say, let your affairs be as two or three."
        val econPage = "Opportunity cost measures the value of the next best alternative forgone."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(
                systemPrompt: String,
                userPrompt: String,
                friend: String?,
                device: String?,
                maxTokens: Int,
            ): CollectionQaTransportReply {
                val modelJson = """{"paragraphs":[{"layer":"book","bookId":"book-econ","sentences":[
                    {"text":"经济学强调机会成本衡量放弃的次佳选择价值。","anchors":["S2"],"quote":"Opportunity cost measures the value of the next best alternative forgone"},
                    {"text":"梭罗主张将事务简化为两三件。","anchors":["S1"],"quote":"let your affairs be as two or three"}
                ]}],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "两本书分别说什么？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-walden"); put("docLabel", "Walden"); put("page", 8); put("text", waldenPage)
                })
                add(buildJsonObject {
                    put("blockId", "B2"); put("bookId", "book-econ"); put("docLabel", "Econ"); put("page", 20); put("text", econPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        val sents = res.getValue("layers").jsonArray.single().jsonObject.getValue("sentences").jsonArray
        assertEquals(1, sents.size)
        assertEquals("book-econ", sents[0].jsonObject.getValue("citations").jsonArray[0].jsonObject.getValue("bookId").jsonPrimitive.content)
        assertEquals(1, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
    }

    @Test
    fun namedZhouyiWithZeroHitsReturnsHighRiskWithoutCallingModel() = runBlocking {
        var calls = 0
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                calls++
                error("model must not be called")
            }
        }
        for (q in listOf("按《周易》，我该不该现在买基金？", "按周易的说法，我该不该现在买基金？")) {
            val request = buildJsonObject {
                put("collectionId", "__virtual_read_library__")
                put("collectionKind", "BOOKLIST")
                put("stance", "mixed")
                put("question", q)
                put("bookStances", mixedBooksSpec())
                put("blocks", JsonArray(emptyList()))
            }
            val res = CollectionQaService(transport).ask(request)
            assertEquals(0, calls)
            assertFalse(res.getValue("modelCalled").jsonPrimitive.boolean)
            assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, res.getValue("boundary").jsonPrimitive.content)
            assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, res.getValue("highRiskNotice").jsonPrimitive.content)
            assertEquals(BookStanceCopy.NO_BOOK_DIRECT, res.getValue("noBookNotice").jsonPrimitive.content)
            assertTrue(res.getValue("layers").jsonArray.isEmpty())
            assertTrue(res.getValue("sources").jsonArray.isEmpty())
        }
    }

    @Test
    fun namedZhouyiWithOnlyEconomicsHitStillReturnsHighRiskWithoutSubstitutingOrCallingModel() = runBlocking {
        var calls = 0
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                calls++
                error("model must not be called")
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "按《周易》，我该不该现在买基金？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1")
                    put("bookId", "book-econ")
                    put("docLabel", "Economics 2e")
                    put("page", 44)
                    put("text", "Mutual funds pool money from investors to purchase diversified portfolios.")
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(0, calls)
        assertFalse(res.getValue("modelCalled").jsonPrimitive.boolean)
        assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, res.getValue("boundary").jsonPrimitive.content)
        assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, res.getValue("highRiskNotice").jsonPrimitive.content)
        assertEquals(BookStanceCopy.NO_BOOK_DIRECT, res.getValue("noBookNotice").jsonPrimitive.content)
        assertTrue(res.getValue("layers").jsonArray.isEmpty())
        assertTrue(res.getValue("sources").jsonArray.isEmpty())
    }

    @Test
    fun unnamedHighRiskQuestionWithZeroHitsReturnsHighRiskWithoutCallingModel() = runBlocking {
        var calls = 0
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                calls++
                error("model must not be called")
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "我该不该现在买基金？")
            put("bookStances", mixedBooksSpec())
            put("blocks", JsonArray(emptyList()))
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(0, calls)
        assertFalse(res.getValue("modelCalled").jsonPrimitive.boolean)
        assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, res.getValue("boundary").jsonPrimitive.content)
        assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, res.getValue("highRiskNotice").jsonPrimitive.content)
        assertEquals(BookStanceCopy.NO_BOOK_DIRECT, res.getValue("noBookNotice").jsonPrimitive.content)
        assertTrue(res.getValue("layers").jsonArray.isEmpty())
    }

    @Test
    fun esotericHighRiskWithZhouyiBlockKeepsSafeBookSentenceAndDropsAdviceAndOutsideEvenWhenSwitchedToKnowledge() = runBlocking {
        val zhouyiPage = "九五：飛龍在天，利見大人。見龍在田，利見大人。"
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                assertTrue("HIGH-RISK BOUNDARY ACTIVE" in systemPrompt)
                val modelJson = """{"paragraphs":[
                    {"layer":"book","bookId":"book-zhouyi","sentences":[
                        {"text":"原文写九五为飛龍在天，利見大人。","anchors":["S1"],"quote":"飛龍在天，利見大人"},
                        {"text":"原文写九五说明当下很适合买入基金并加仓。","anchors":["S1"],"quote":"飛龍在天，利見大人"}
                    ]},
                    {"layer":"outside","bookId":"book-zhouyi","kind":"今人解读","text":"实际投资需要看估值与个人风险承受能力。"}
                ],"boundary":"high_risk"}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "knowledge")
            put("overrideStance", "knowledge")
            put("question", "按周易的说法，我该不该现在买基金？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-zhouyi"); put("docLabel", "周易"); put("page", 2); put("text", zhouyiPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertTrue(res.getValue("modelCalled").jsonPrimitive.boolean)
        assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, res.getValue("boundary").jsonPrimitive.content)
        assertEquals(BookStanceCopy.HIGH_RISK_NOTICE, res.getValue("highRiskNotice").jsonPrimitive.content)
        assertTrue(res["systemNotice"] is JsonNull, "switched to knowledge must not emit esoteric systemNotice")
        val layers = res.getValue("layers").jsonArray
        assertEquals(1, layers.size)
        val bookLayer = layers[0].jsonObject
        assertEquals("book", bookLayer.getValue("layer").jsonPrimitive.content)
        val sents = bookLayer.getValue("sentences").jsonArray
        assertEquals(1, sents.size)
        assertTrue(sents[0].jsonObject.getValue("text").jsonPrimitive.content.contains("飛龍在天"))
        assertEquals(1, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
    }

    @Test
    fun switchedToKnowledgeStillEnforcesFictionOutOfRangeGate() = runBlocking {
        val allowedPage = "She is tolerable, but not handsome enough to tempt me."
        val spoilerPage = "Elizabeth Bennet finally marries Mr. Darcy at Pemberley."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                // Stub ignores prompt and emits a spoiler anchor if passed, OR verify page 120 block was never sent and unknown anchor dropped
                assertFalse("Pemberley" in userPrompt, "future fiction page must be excluded from prompt")
                val modelJson = """{"paragraphs":[{"layer":"book","bookId":"book-pride","sentences":[
                    {"text":"达西一开始觉得她只是勉强过得去。","anchors":["S1"],"quote":"She is tolerable"},
                    {"text":"伊丽莎白最终嫁给了达西。","anchors":["S2"],"quote":"marries Mr. Darcy"}
                ]}],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "knowledge")
            put("overrideStance", "knowledge")
            put("question", "伊丽莎白最后嫁给谁了？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-pride"); put("docLabel", "Pride"); put("page", 12); put("text", allowedPage)
                })
                add(buildJsonObject {
                    put("blockId", "B2"); put("bookId", "book-pride"); put("docLabel", "Pride"); put("page", 180); put("text", spoilerPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        val sents = res.getValue("layers").jsonArray.single().jsonObject.getValue("sentences").jsonArray
        assertEquals(1, sents.size)
        assertEquals(12, sents[0].jsonObject.getValue("citations").jsonArray[0].jsonObject.getValue("pdfPage").jsonPrimitive.int)
    }

    @Test
    fun legacyRequestWithoutStanceUsesUnchangedLegacyPath() = runBlocking {
        val text = "Green shutters keep the room cool."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                assertTrue("insufficientEvidence" in systemPrompt)
                val out = buildJsonObject {
                    put("insufficientEvidence", false)
                    put("sentences", buildJsonArray {
                        add(buildJsonObject {
                            put("claim", "绿百叶窗保持室内凉爽。")
                            put("blockId", "B1")
                            put("quoteAnchor", text)
                        })
                    })
                }
                return CollectionQaTransportReply(out.toString(), "stub", 10, 10, 1, 2)
            }
        }
        val res = CollectionQaService(transport).ask(buildJsonObject {
            put("collectionId", "shelf")
            put("question", "房间怎么降温？")
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "b1"); put("docLabel", "House"); put("page", 1); put("text", text)
                })
            })
        })
        assertNull(res["stance"])
        assertNull(res["layers"])
        assertEquals(1, res.getValue("sentences").jsonArray.size)
    }

    @Test
    fun mixedCollectionRejectsGuessWithoutAdjacentVerifiedLifeBookParagraph() = runBlocking {
        val econPage = "Supply and demand interact to determine market equilibrium price."
        val waldenPage = "I went to the woods because I wished to live deliberately."
        var calls = 0
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                calls++
                assertTrue("immediately adjacent to a verified" in systemPrompt)
                // Both attempts cite only knowledge book (econ) yet include a Guess (even naming book-walden)
                val modelJson = """{"paragraphs":[
                    {"layer":"book","bookId":"book-econ","sentences":[{"text":"供求决定市场均衡价格。","anchors":["S1"],"quote":"Supply and demand interact"}]},
                    {"layer":"guess","bookId":"book-walden","text":"按他简朴生活的办法推想，消费越少越自由。"}
                ],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "怎么看待消费和简单生活？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-econ"); put("docLabel", "Economics 2e"); put("page", 10); put("text", econPage)
                })
                add(buildJsonObject {
                    put("blockId", "B2"); put("bookId", "book-walden"); put("docLabel", "Walden"); put("page", 5); put("text", waldenPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(2, calls, "invalid guess should trigger repair retry then fail closed")
        assertTrue(res.getValue("insufficientEvidence").jsonPrimitive.boolean)
        assertTrue(res.getValue("layers").jsonArray.isEmpty())
        assertTrue(res.getValue("sentences").jsonArray.isEmpty())
    }

    @Test
    fun mixedCollectionBindsGuessToAdjacentVerifiedLifeBookParagraph() = runBlocking {
        val econPage = "Supply and demand interact to determine market equilibrium price."
        val waldenPage = "I went to the woods because I wished to live deliberately."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                val modelJson = """{"paragraphs":[
                    {"layer":"book","bookId":"book-econ","sentences":[{"text":"供给与需求互动共同决定市场均衡价格。","anchors":["S1"],"quote":"Supply and demand interact to determine market equilibrium price"}]},
                    {"layer":"book","bookId":"book-walden","sentences":[{"text":"梭罗写到走进森林是为了过简朴审慎的生活。","anchors":["S2"],"quote":"wished to live deliberately"}]},
                    {"layer":"guess","text":"按他简朴生活的办法推想，消费减半可以减少焦虑。"}
                ],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "怎么看待消费和简单生活？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-econ"); put("docLabel", "Economics 2e"); put("page", 10); put("text", econPage)
                })
                add(buildJsonObject {
                    put("blockId", "B2"); put("bookId", "book-walden"); put("docLabel", "Walden"); put("page", 5); put("text", waldenPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertFalse(res.getValue("insufficientEvidence").jsonPrimitive.boolean)
        val layers = res.getValue("layers").jsonArray
        assertEquals(3, layers.size)
        val guessLayer = layers[2].jsonObject
        assertEquals("guess", guessLayer.getValue("layer").jsonPrimitive.content)
        assertEquals("book-walden", guessLayer.getValue("bookId").jsonPrimitive.content)
        assertEquals("Walden", guessLayer.getValue("bookTitle").jsonPrimitive.content)
    }

    @Test
    fun k4aStyleDropsSentenceMissingPercentagesWhenQuoteOnlyCoversResult() = runBlocking {
        val econPage = "When price rises by 8%, quantity demanded drops by 11.76%, giving a price elasticity of demand of 1.47."
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                assertTrue("ONLY ONE fact" in systemPrompt)
                assertTrue("5–200 chars" in systemPrompt)
                val modelJson = """{"paragraphs":[{"layer":"book","bookId":"book-econ","sentences":[
                    {"text":"当价格上涨8%、需求量下降11.76%时，需求价格弹性为1.47。","anchors":["S1"],"quote":"price elasticity of demand of 1.47"}
                ]}],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "knowledge")
            put("question", "需求价格弹性计算例子是什么？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-econ"); put("docLabel", "Economics 2e"); put("page", 42); put("text", econPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertTrue(res.getValue("layers").jsonArray.isEmpty())
        assertEquals(1, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
    }

    @Test
    fun k1bStyleKeepsThreeSplitExampleSentencesSharingOneAnchorWithDistinctHighlights() = runBlocking {
        val originPage = "For instance, wolves hunt swift deer in the mountains. Another example is two hounds chasing hares across open plains. A third example shows bees pollinating blossoms in summer."
        val q1 = "wolves hunt swift deer in the mountains"
        val q2 = "two hounds chasing hares across open plains"
        val q3 = "A third example shows bees pollinating blossoms in summer"
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                val modelJson = """{"paragraphs":[{"layer":"book","bookId":"book-origin","sentences":[
                    {"text":"例如狼在山地追捕敏捷的鹿。","anchors":["S1"],"quote":"$q1"},
                    {"text":"另一个例子是两只猎犬在平原追兔。","anchors":["S1"],"quote":"$q2"},
                    {"text":"第三个例子是蜜蜂在夏季为花朵授粉。","anchors":["S1"],"quote":"$q3"}
                ]}],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val books = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-origin")
                put("title", "On the Origin of Species")
                put("defaultStance", "knowledge")
                put("guards", JsonArray(emptyList()))
            })
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "knowledge")
            put("question", "达尔文举了哪些自然选择例子？")
            put("bookStances", books)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-origin"); put("docLabel", "Origin"); put("page", 15); put("text", originPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(0, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
        val sents = res.getValue("layers").jsonArray.single().jsonObject.getValue("sentences").jsonArray
        assertEquals(3, sents.size)
        val cites = sents.map { it.jsonObject.getValue("citations").jsonArray.single().jsonObject }
        assertEquals(listOf(q1, q2, q3), cites.map { it.getValue("quote").jsonPrimitive.content })
        assertEquals(listOf(originPage.indexOf(q1), originPage.indexOf(q2), originPage.indexOf(q3)), cites.map { it.getValue("startUtf16").jsonPrimitive.int })
    }

    @Test
    fun m2WaldenStyleSplitsHouseBuildingAndEarningLivingIntoSeparateCitedSentences() = runBlocking {
        val waldenPage = "I borrowed an axe and built my own house beside Walden Pond. I earned my living strictly by the labor of my hands as a surveyor and day laborer."
        val qHouse = "built my own house beside Walden Pond"
        val qLiving = "earned my living strictly by the labor of my hands"
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                val modelJson = """{"paragraphs":[
                    {"layer":"book","bookId":"book-walden","sentences":[
                        {"text":"梭罗在瓦尔登湖畔自己动手盖了房子。","anchors":["S1"],"quote":"$qHouse"},
                        {"text":"他靠双手做测量和零工来谋生。","anchors":["S1"],"quote":"$qLiving"}
                    ]}
                ],"boundary":null}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "mixed")
            put("question", "梭罗在瓦尔登湖是怎么盖房子和谋生的？")
            put("bookStances", mixedBooksSpec())
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-walden"); put("docLabel", "Walden"); put("page", 4); put("text", waldenPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(0, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
        val sents = res.getValue("layers").jsonArray.single().jsonObject.getValue("sentences").jsonArray
        assertEquals(2, sents.size)
        val cites = sents.map { it.jsonObject.getValue("citations").jsonArray.single().jsonObject }
        assertEquals(listOf(qHouse, qLiving), cites.map { it.getValue("quote").jsonPrimitive.content })
        assertEquals(listOf(waldenPage.indexOf(qHouse), waldenPage.indexOf(qLiving)), cites.map { it.getValue("startUtf16").jsonPrimitive.int })
    }

    @Test
    fun e4bStyleDropsHighRiskKybalionMedicationSentenceAndKeepsPureBookSentence() = runBlocking {
        val kybalionPage = "There is always a correspondence between the laws and the planes of being. Different planes hold each their own harmony."
        val kyQuote = "always a correspondence between the laws and the planes"
        val books = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-kybalion")
                put("title", "The Kybalion")
                put("aliases", buildJsonArray { add(JsonPrimitive("Kybalion")) })
                put("defaultStance", "esoteric")
                put("guards", buildJsonArray { add(JsonPrimitive("esoteric")) })
            })
        }
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                val modelJson = """{"paragraphs":[{"layer":"book","bookId":"book-kybalion","sentences":[
                    {"text":"本书认为不同层面各有对应规律，但降压药涉及真实治疗仍需遵医嘱。","anchors":["S1"],"quote":"$kyQuote"},
                    {"text":"原文说对应在于层面。","anchors":["S1"],"quote":"$kyQuote"}
                ]}],"boundary":"high_risk"}"""
                return CollectionQaTransportReply(modelJson, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "esoteric")
            put("question", "按 Kybalion，我能不能不吃降压药，靠心念调过来？")
            put("bookStances", books)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-kybalion"); put("docLabel", "The Kybalion"); put("page", 17); put("text", kybalionPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(BookStanceCopy.HIGH_RISK_BOUNDARY, res.getValue("boundary").jsonPrimitive.content)
        assertEquals(1, res.getValue("gate").jsonObject.getValue("droppedSentences").jsonPrimitive.int)
        val sents = res.getValue("layers").jsonArray.single().jsonObject.getValue("sentences").jsonArray
        assertEquals(1, sents.size)
        assertEquals("原文说对应在于层面。", sents[0].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun o3aStyleRefusalOnFictionCollectionPreservesFictionNoBookNotice() = runBlocking {
        val allowedPage = "She is tolerable, but not handsome enough to tempt me."
        val fictionBooks = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-pride")
                put("title", "Pride and Prejudice")
                put("defaultStance", "original")
                put("guards", buildJsonArray { add(JsonPrimitive("fiction")) })
                put("readThroughPage", 12)
                put("bookPages", 220)
            })
        }
        var calls = 0
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                calls++
                assertTrue("""output {"paragraphs":[],"boundary":null}""" in systemPrompt)
                // Force verified == null twice by emitting forbidden outside layer in original-only mode
                val invalidForOriginal = """{"paragraphs":[{"layer":"outside","kind":"通行讲法","text":"前几页尚未写出结局。"}],"boundary":null}"""
                return CollectionQaTransportReply(invalidForOriginal, "stub", 10, 10, 1, 2)
            }
        }
        val request = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "original")
            put("question", "最后伊丽莎白嫁给谁了？")
            put("bookStances", fictionBooks)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-pride"); put("docLabel", "Pride"); put("page", 12); put("text", allowedPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(request)
        assertEquals(2, calls)
        assertTrue(res.getValue("modelCalled").jsonPrimitive.boolean)
        assertTrue(res.getValue("layers").jsonArray.isEmpty())
        assertEquals(BookStanceCopy.NO_BOOK_FICTION, res.getValue("noBookNotice").jsonPrimitive.content)
    }

    @Test
    fun c2aAndC1aClassicCollectionPreservesOutsideAndRetriesZeroBookDropped() = runBlocking {
        val lunyuPage = "學而時習之，不亦說乎？有朋自遠方來，不亦樂乎？"
        val classicBooks = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-lunyu")
                put("title", "论语")
                put("defaultStance", "classic")
                put("guards", JsonArray(emptyList()))
            })
        }

        // Case C2-A: outside answer with tolerable formatting (empty anchors/newline/bookTitle) is preserved cleanly
        val c2aTransport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                val payload = """{"stance":"classic","paragraphs":[{"layer":"outside","kind":"注家读法","bookTitle":"论语","anchors":[],"text":"朱熹在论语集注中\n将「習」解释为鸟数飞。"}],"boundary":null}"""
                return CollectionQaTransportReply(payload, "stub", 10, 10, 1, 2)
            }
        }
        val c2aReq = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "classic")
            put("question", "朱熹怎么解释「習」？")
            put("bookStances", classicBooks)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-lunyu"); put("docLabel", "论语"); put("page", 1); put("text", lunyuPage)
                })
            })
        }
        val c2aRes = CollectionQaService(c2aTransport).ask(c2aReq)
        assertFalse(c2aRes.getValue("insufficientEvidence").jsonPrimitive.boolean)
        val c2aLayers = c2aRes.getValue("layers").jsonArray
        assertEquals(1, c2aLayers.size)
        assertEquals("outside", c2aLayers[0].jsonObject.getValue("layer").jsonPrimitive.content)
        assertTrue("朱熹" in c2aLayers[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(BookStanceCopy.NO_BOOK_DIRECT, c2aRes.getValue("noBookNotice").jsonPrimitive.content)

        // Case C1-A: attempt 1 drops all book sentences (ungrounded), attempt 2 retries and recovers book sentence
        var c1aCalls = 0
        val c1aTransport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                c1aCalls++
                val payload = if (c1aCalls == 1) {
                    """{"paragraphs":[{"layer":"book","bookId":"book-lunyu","sentences":[
                        {"text":"宇宙星辰与航海规则十分重要。","anchors":["S1"],"quote":"學而時習之，不亦說乎"}
                    ]}],"boundary":null}"""
                } else {
                    """{"paragraphs":[{"layer":"book","bookId":"book-lunyu","sentences":[
                        {"text":"原文说学了以后时常复习也很喜悦。","anchors":["S1"],"quote":"學而時習之，不亦說乎"}
                    ]}],"boundary":null}"""
                }
                return CollectionQaTransportReply(payload, "stub", 10, 10, 1, 2)
            }
        }
        val c1aReq = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "classic")
            put("question", "这一章原文在说什么？")
            put("bookStances", classicBooks)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-lunyu"); put("docLabel", "论语"); put("page", 1); put("text", lunyuPage)
                })
            })
        }
        val c1aRes = CollectionQaService(c1aTransport).ask(c1aReq)
        assertEquals(2, c1aCalls)
        assertEquals(1, c1aRes.getValue("layers").jsonArray.single().jsonObject.getValue("sentences").jsonArray.size)
        assertTrue(c1aRes["noBookNotice"] is JsonNull)
        assertTrue(c1aRes.getValue("gate").jsonObject.getValue("rejects").jsonArray.isEmpty())
    }

    @Test
    fun gateRejectsRecordsEnumNamesAcrossBothAttemptsWithoutModelText() = runBlocking {
        val lunyuPage = "學而時習之，不亦說乎？有朋自遠方來，不亦樂乎？"
        val classicBooks = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-lunyu")
                put("title", "论语")
                put("defaultStance", "classic")
                put("guards", JsonArray(emptyList()))
            })
        }
        var call = 0
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                call++
                val payload = if (call == 1) {
                    """{"paragraphs":[{"layer":"outside","kind":"注家读法","commentator":"朱熹","text":"朱熹在论语集注中解释習。"}],"boundary":null}"""
                } else {
                    "not valid json at all"
                }
                return CollectionQaTransportReply(payload, "stub", 10, 10, 1, 2)
            }
        }
        val req = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "classic")
            put("question", "朱熹怎么解释「習」？")
            put("bookStances", classicBooks)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-lunyu"); put("docLabel", "论语"); put("page", 1); put("text", lunyuPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(req)
        assertTrue(res.getValue("insufficientEvidence").jsonPrimitive.boolean)
        val rejects = res.getValue("gate").jsonObject.getValue("rejects").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("PARAGRAPH_FIELDS", "JSON"), rejects)
    }

    @Test
    fun c2aUnescapedQuotesRejectedAsJsonThenRetriesIntoOutsideCommentatorLayer() = runBlocking {
        val lunyuPage = "學而時習之，不亦說乎？有朋自遠方來，不亦樂乎？"
        val daodePage = "道可道，非常道。名可名，非常名。"
        val classicBooks = buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-daode")
                put("title", "道德经（王弼注）")
                put("defaultStance", "classic")
                put("guards", JsonArray(emptyList()))
            })
            add(buildJsonObject {
                put("bookId", "book-lunyu")
                put("title", "論語")
                put("defaultStance", "classic")
                put("guards", JsonArray(emptyList()))
            })
        }
        var call = 0
        var seenSystemPrompt = ""
        var seenRetryUserPrompt = ""
        val transport = object : CollectionQaTransport {
            override suspend fun ask(systemPrompt: String, userPrompt: String, friend: String?, device: String?, maxTokens: Int): CollectionQaTransportReply {
                call++
                seenSystemPrompt = systemPrompt
                if (call == 2) seenRetryUserPrompt = userPrompt
                val payload = if (call == 1) {
                    """{"paragraphs":[{"layer":"outside","bookId":"book-lunyu","kind":"注家读法","text":"朱熹在《论语集注》中把"习"解释为"鸟数飞也"，指反复练习以至于熟。"}],"boundary":null}"""
                } else {
                    """{"paragraphs":[{"layer":"outside","bookId":"book-lunyu","kind":"注家读法","text":"朱熹在《论语集注》中把「習」解释为「鸟数飞也」，强调反复练习直到熟练。"}],"boundary":null}"""
                }
                return CollectionQaTransportReply(payload, "stub", 10, 10, 1, 2)
            }
        }
        val req = buildJsonObject {
            put("collectionId", "__virtual_read_library__")
            put("collectionKind", "BOOKLIST")
            put("stance", "classic")
            put("question", "在《論語》（學而時習之）里，朱熹怎么解释「習」？")
            put("bookStances", classicBooks)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("blockId", "B1"); put("bookId", "book-daode"); put("docLabel", "道德经（王弼注）"); put("page", 1); put("text", daodePage)
                })
                add(buildJsonObject {
                    put("blockId", "B2"); put("bookId", "book-lunyu"); put("docLabel", "論語"); put("page", 1); put("text", lunyuPage)
                })
            })
        }
        val res = CollectionQaService(transport).ask(req)
        assertEquals(2, call)
        assertTrue(seenSystemPrompt.contains("Outside-only 注家读法 example:"))
        assertTrue(seenSystemPrompt.contains("「」"))
        assertTrue(seenRetryUserPrompt.contains("wrap quoted Chinese terms or commentary words in 「」"))
        assertFalse(res.getValue("insufficientEvidence").jsonPrimitive.boolean)
        val rejects = res.getValue("gate").jsonObject.getValue("rejects").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("JSON"), rejects)
        val layers = res.getValue("layers").jsonArray
        assertEquals(1, layers.size)
        val outside = layers.first().jsonObject
        assertEquals("outside", outside.getValue("layer").jsonPrimitive.content)
        assertEquals("书外 · 未对照原文 · 注家读法", outside.getValue("label").jsonPrimitive.content)
        assertTrue(outside.getValue("text").jsonPrimitive.content.contains("鸟数飞也"))
    }
}
