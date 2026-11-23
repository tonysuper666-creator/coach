package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun courseVisionFallsBackToOpenAiOnPrimaryTimeout() = runBlocking {
        // Primary hangs past requestTimeout; OpenAI backup server responds 200.
        val hangingSocket = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CopyOnWriteArrayList<java.net.Socket>()
        val acceptThread = Thread {
            runCatching {
                accepted.add(hangingSocket.accept())
            }
        }.apply { isDaemon = true; start() }

        val oaiHits = AtomicInteger(0)
        val oaiServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        oaiServer.createContext("/v1/chat/completions") { exchange ->
            oaiHits.incrementAndGet()
            val body = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("message", buildJsonObject { put("content", "繁體板書內容 OK") })
                    })
                })
                put("usage", buildJsonObject {
                    put("prompt_tokens", 120)
                    put("completion_tokens", 18)
                })
            }.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        oaiServer.start()

        try {
            val transport = AnthropicHaikuVisionTransport(
                apiKeyProvider = { "synthetic-ant-key" },
                openAiKeyProvider = { "synthetic-oai-key" },
                endpoint = "http://127.0.0.1:${hangingSocket.localPort}/v1/messages",
                openAiEndpoint = "http://127.0.0.1:${oaiServer.address.port}/v1/chat/completions",
                connectTimeout = Duration.ofMillis(300),
                requestTimeout = Duration.ofMillis(300),
            )
            val reply = transport.recognizePage(1, "iVBORw0KGgo=", "a".repeat(64))
            assertEquals(1, oaiHits.get())
            assertEquals(BridgeClient.GPT_62_ASTRA, reply.model)
            assertTrue(reply.text.contains("繁體板書內容"))
        } finally {
            accepted.forEach { runCatching { it.close() } }
            runCatching { hangingSocket.close() }
            runCatching { acceptThread.interrupt() }
            oaiServer.stop(0)
        }
    }
}
