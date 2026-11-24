package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingRegressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun friendCourseMockPropagatesFriendHeadersToPlannerAndItemsAndSkipsOwnerLedger() = runBlocking {
        val dir = Files.createTempDirectory("course-mock-friend")
        val calls = CopyOnWriteArrayList<Triple<String, String, Map<String, String>>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val bodyJson = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val task = exchange.requestHeaders.getFirst("X-Coach-Task").orEmpty()
            val friend = exchange.requestHeaders.getFirst("X-Coach-Friend").orEmpty()
            val device = exchange.requestHeaders.getFirst("X-Coach-Friend-Device").orEmpty()
            calls.add(Triple(task, model, mapOf("friend" to friend, "device" to device)))
            val contentText = if (calls.size == 1) {
                """{"sections":[{"title":"Part A","instructions":"Solve completely.","slots":[{"slotId":"A1","sectionPart":"Part A","itemType":"multi_part_calc","marks":10,"topicId":"t1","focusConcept":"Demand curve","requiresMathSpec":true}]}]}"""
            } else {
                """{"id":"A1","type":"multi_part_calc","stem":"Consider a linear budget problem with fresh parameters.","marks":10,"source":{"docId":"book-1","pdfPage":1,"quote":"Market demand slopes downward"},"subQuestions":[{"subId":"a","label":"(a)","prompt":"Compute the fresh intercept value.","kind":"calculation","marks":10,"mathSpec":{"expression":"20 / 4","expected":"5.00","decimalPlaces":2,"absTolerance":"0.015","unit":""}}],"rubricCriteria":[{"cid":"A1-a","subId":"a","slotLabel":"Compute intercept","maxMarks":10,"sourceBookId":"book-1","pdfPage":1,"quoteAnchor":"Market demand slopes downward"}]}"""
            }
            val reply = buildJsonObject {
                put("model", model)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", contentText)
                        })
                    })
                })
                put("x_coach", buildJsonObject {
                    put("status", "complete")
                    put("served_by", model)
                    put("physical_calls", 1)
                })
            }
            val bytes = reply.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val ledger = BookTokenLedger(dir)
        try {
            val bridge = BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${server.address.port}"))
            val itemBridge = HaikuPinnedBridgeTransport(
                bridge = bridge,
                defaultMaxTokens = CourseMockLimits.ITEM_MAX_TOKENS,
                backgroundBatch = false,
                model = CourseMockLimits.PINNED_ITEM_MODEL,
            )
            val svc = CourseMockService(
                plannerTransport = SonnetBridgeMockPlannerTransport(bridge),
                itemTransport = RoutedBatchAiTransport(primary = itemBridge, overflow = null, friendTransport = itemBridge),
                ledger = ledger,
            )
            val input = buildJsonObject {
                put("courseId", "course-econ-1")
                put("courseTitle", "Microeconomics")
                put("examinableTopics", buildJsonArray {
                    add(buildJsonObject {
                        put("topicId", "t1")
                        put("weekLabel", "W1")
                        put("title", "Consumer Demand")
                        put("examStatus", "EXAMINABLE")
                        put("sourcePages", buildJsonArray {
                            add(buildJsonObject {
                                put("bookId", "book-1")
                                put("bookTitle", "Micro Textbook")
                                put("role", "TEXTBOOK")
                                put("authorityRank", 1)
                                put("pdfPage", 1)
                                put("text", "Market demand slopes downward across standard consumer budgets.")
                            })
                        })
                    })
                })
            }

            val paper = svc.generate(input, friend = "bob", device = "tablet-1")
            assertEquals(2, calls.size)
            for ((task, model, meta) in calls) {
                assertEquals("READER_SELECTION", task)
                assertEquals(BridgeClient.CLAUDE, model)
                assertEquals(friendId("bob"), meta["friend"])
                assertEquals("tablet-1", meta["device"])
            }
            assertEquals(0, ledger.bookSummary("course-econ-1").calls)
            assertEquals(0L, paper["audit"]!!.jsonObject["ledgerCostMicrodollars"]?.jsonPrimitive?.contentOrNull?.toLong())
        } finally {
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun courseVisionRoutesThroughBridgeAcceptsOpenAiFallbackAndEnforcesQuotaAndMissingKey() = runBlocking {
        // Real 1x1 PNG encoded as base64.
        val samplePngB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val dir = Files.createTempDirectory("course-vision-bridge")
        val calls = CopyOnWriteArrayList<Map<String, String>>()
        var mode = "fallback_openai"
        val hits = AtomicInteger(0)

        val bridgeServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        bridgeServer.createContext("/v1/chat/completions") { exchange ->
            hits.incrementAndGet()
            val bodyJson = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val task = exchange.requestHeaders.getFirst("X-Coach-Task").orEmpty()
            val friend = exchange.requestHeaders.getFirst("X-Coach-Friend").orEmpty()
            val device = exchange.requestHeaders.getFirst("X-Coach-Friend-Device").orEmpty()
            val userContent = bodyJson["messages"]!!.jsonArray[1].jsonObject["content"] as JsonArray
            val imagePart = userContent.first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image_url" }.jsonObject
            val imageUrl = imagePart["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
            calls.add(mapOf("task" to task, "model" to model, "friend" to friend, "device" to device, "imageUrl" to imageUrl))

            val reply = when (mode) {
                "fallback_openai" -> buildJsonObject {
                    put("model", BridgeClient.GPT_62_ASTRA)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("index", 0)
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", "繁體板書內容 OK")
                            })
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 120)
                        put("completion_tokens", 18)
                        put("total_tokens", 138)
                    })
                    put("x_coach", buildJsonObject {
                        put("status", "complete")
                        put("served_by", BridgeClient.GPT_62_ASTRA)
                        put("fallback", true)
                        put("physical_calls", 2)
                    })
                }
                "friend_ok" -> buildJsonObject {
                    put("model", BridgeClient.CLAUDE)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("index", 0)
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", "朋友板書內容")
                            })
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 100)
                        put("completion_tokens", 12)
                        put("total_tokens", 112)
                    })
                    put("x_coach", buildJsonObject {
                        put("status", "complete")
                        put("served_by", BridgeClient.CLAUDE)
                        put("fallback", false)
                        put("physical_calls", 1)
                    })
                }
                "missing_key" -> buildJsonObject {
                    put("model", model)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("index", 0)
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", "")
                            })
                        })
                    })
                    put("x_coach", buildJsonObject {
                        put("status", "failed")
                        put("reason", "missing_key")
                        put("served_by", model)
                        put("physical_calls", 0)
                    })
                }
                else -> buildJsonObject {
                    put("model", model)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("index", 0)
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", "")
                            })
                        })
                    })
                    put("x_coach", buildJsonObject {
                        put("status", "failed")
                        put("reason", "credit_limit_95_percent")
                        put("served_by", model)
                        put("physical_calls", 0)
                    })
                }
            }
            val bytes = reply.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        bridgeServer.start()

        val ledger = BookTokenLedger(dir.resolve("ledger"))
        try {
            val bridge = BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${bridgeServer.address.port}"))
            val transport = BridgeCourseVisionTransport(bridge)
            val service = CourseVisionService(dir.resolve("vision"), transport = transport, tokenLedger = ledger)

            // 1) Owner vision routes via bridge with course_vision_ocr + inline data URI, and accepts cross-vendor fallback.
            val ownerReply = transport.recognizePage(1, samplePngB64, "a".repeat(64))
            assertEquals(1, hits.get())
            assertEquals("course_vision_ocr", calls[0]["task"])
            assertEquals(BridgeClient.OPUS, calls[0]["model"])
            assertEquals("data:image/png;base64,$samplePngB64", calls[0]["imageUrl"])
            assertEquals(BridgeClient.GPT_62_ASTRA, ownerReply.model)
            assertEquals(120, ownerReply.inputTokens)
            assertEquals(18, ownerReply.outputTokens)
            assertTrue(ownerReply.text.contains("繁體板書內容"))

            // 2) Missing key preserves the exact "视觉识别密钥尚未配置" user message.
            mode = "missing_key"
            val missingKeyErr = assertFailsWith<MockError> {
                transport.recognizePage(2, samplePngB64, "b".repeat(64))
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, missingKeyErr.status)
            assertTrue(missingKeyErr.message.orEmpty().contains("视觉识别密钥尚未配置"))

            // 3) 95% quota denial fails cleanly with 429 and does not cache the page.
            mode = "quota_exceeded"
            val quotaSha = "c".repeat(64)
            val quotaErr = assertFailsWith<MockError> {
                service.recognizeBatch(buildJsonObject {
                    put("bookId", "book-quota")
                    put("totalPages", 1)
                    put("pages", buildJsonArray {
                        add(buildJsonObject {
                            put("pageNumber", 1)
                            put("pageSha256", quotaSha)
                            put("pngBase64", samplePngB64)
                        })
                    })
                })
            }
            assertEquals(HttpStatusCode.TooManyRequests, quotaErr.status)
            assertNull(service.lookupCached(quotaSha))

            // 4) Friend vision propagates friend headers, uses READER_SELECTION + Claude, and skips owner BookTokenLedger.
            mode = "friend_ok"
            val friendSha = "d".repeat(64)
            val friendBatch = service.recognizeBatch(
                input = buildJsonObject {
                    put("bookId", "book-friend")
                    put("totalPages", 1)
                    put("pages", buildJsonArray {
                        add(buildJsonObject {
                            put("pageNumber", 1)
                            put("pageSha256", friendSha)
                            put("pngBase64", samplePngB64)
                        })
                    })
                },
                friend = "bob",
                device = "tablet-1",
            )
            assertEquals(1, friendBatch["billedCalls"]?.jsonPrimitive?.contentOrNull?.toInt())
            val friendCall = calls.last()
            assertEquals("READER_SELECTION", friendCall["task"])
            assertEquals(BridgeClient.CLAUDE, friendCall["model"])
            assertEquals(friendId("bob"), friendCall["friend"])
            assertEquals("tablet-1", friendCall["device"])
            assertEquals(0, ledger.bookSummary("book-friend").calls)

            // 5) Static guard: CourseVisionService.kt must never directly touch provider endpoints or API keys.
            val source = Files.readString(Path.of("src/main/kotlin/com/coach/server/CourseVisionService.kt"))
            for (forbidden in listOf(
                "api.anthropic.com",
                "api.openai.com",
                "COACH_ANTHROPIC_API_KEY",
                "ANTHROPIC_API_KEY",
                "COACH_OPENAI_API_KEY",
                "OPENAI_API_KEY",
            )) {
                assertFalse(source.contains(forbidden), "CourseVisionService.kt must not contain direct bypass token $forbidden")
            }
        } finally {
            bridgeServer.stop(0)
            dir.toFile().deleteRecursively()
        }
    }
}
