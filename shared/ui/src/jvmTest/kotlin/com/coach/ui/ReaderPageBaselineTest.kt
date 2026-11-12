package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.coach.client.CourseHttp
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.test.*

/**
 * Synthetic HTTP -> production reader -> native desktop pixels, with no production credentials.
 * Captures are an upper bound including test synchronization/readback, not first-present telemetry.
 * Keep this fixture unchanged between the baseline and the cache candidate.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderPageBaselineTest {
    @Test fun recordsTwentyForwardAndReversePageTurnsAndCitationReopen() = BaselineReader().use { fixture ->
        val samples = mutableListOf<JsonObject>()
        val phase = System.getenv("COACH_READER_MEASUREMENT_PHASE") ?: "unspecified"
        require(phase.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
        val output = File("build/reader-measurements/$phase").also { it.mkdirs() }
        try {
            runDesktopComposeUiTest(1000, 900) {
                setContent {
                    CoachTheme(false) {
                        Surface { PrivateSourceReference(fixture.http.reader, "Synthetic p.1", tag = "baseline-source") }
                    }
                }

                fun displayed(page: Int): Boolean {
                    val node = onAllNodesWithTag("private-page-$page").fetchSemanticsNodes().singleOrNull()
                        ?: return false
                    if (node.boundsInRoot.isEmpty) return false
                    // A changing semantic tag can precede a new bitmap. Verify this page's actual pixels.
                    val pixels = onNodeWithTag("private-page-$page").captureToImage().toAwtImage()
                    return pixels.width > 4 && pixels.height > 4 &&
                        (pixels.getRGB(2, 2) and 0xffffff) == (fixture.color(page).rgb and 0xffffff)
                }

                fun waitForInput(tag: String) {
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let {
                            !it.config.contains(SemanticsProperties.Disabled)
                        } == true
                    }
                }

                fun record(kind: String, tag: String, page: Int) {
                    waitForInput(tag)
                    onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
                    val requestStart = fixture.images.size
                    val completedBeforeInput = fixture.images.count { it.page == page && it.finishedNs != null }
                    val start = System.nanoTime()
                    // Use pointer input through the native test window, rather than a semantics action.
                    onNodeWithTag(tag).performMouseInput { click() }
                    waitUntil(timeoutMillis = 15_000) { displayed(page) }
                    val end = System.nanoTime()
                    val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                    samples += buildJsonObject {
                        put("kind", kind)
                        put("page", page)
                        put("inputToVerifiedCaptureUpperBoundMs", (end - start) / 1_000_000.0)
                        put("completedImageResponsesBeforeInput", completedBeforeInput)
                        put("imageRequestsDuringSample", fixture.images.size - requestStart)
                        put("decodedCacheHit", "unobserved")
                        put("prefetchDecodeCompletedBeforeInput", "unobserved")
                        put("jvmHeapUsedBytes", heap)
                        put("pixelIdentityVerified", true)
                    }
                }

                record("citation_open", "baseline-source", 1)
                for (page in 2..21) record("forward", "private-next", page)
                for (page in 20 downTo 1) record("reverse", "private-prev", page)
                waitForInput("private-document-back")
                onNodeWithTag("private-document-back").performMouseInput { click() }
                record("citation_reopen", "baseline-source", 1)
                ImageIO.write(onNodeWithTag("private-page-1").captureToImage().toAwtImage(), "png", File(output, "synthetic-page.png"))
            }
        } finally {
            // Keep partial observations on a failed run; a missing sample is never a passing result.
            File(output, "report.json").writeText(buildJsonObject {
                put("schema", 1)
                put("phase", phase)
                put("syntheticOnly", true)
                put("productionRead", false)
                put("surface", "native Compose desktop test window; synthetic HTTP fixture")
                put("os", System.getProperty("os.name"))
                put("java", System.getProperty("java.version"))
                put("windowWidth", 1000)
                put("windowHeight", 900)
                put("sourceImageWidth", 1200)
                put("sourceImageHeight", 1600)
                put("sourceImageRgbaByteEstimate", 1200L * 1600 * 4)
                put("threeImageRgbaByteEstimate", 3L * 1200 * 1600 * 4)
                put("memoryBudgetVerified", false)
                put("firstPresentLatencyVerified", false)
                put("samples", JsonArray(samples))
                put("summary", buildJsonObject {
                    for ((kind, rows) in samples.groupBy { it.getValue("kind").jsonPrimitive.content }) {
                        val times = rows.map { it.getValue("inputToVerifiedCaptureUpperBoundMs").jsonPrimitive.double }.sorted()
                        put(kind, buildJsonObject {
                            put("count", times.size)
                            put("p50UpperBoundMs", if (times.size % 2 == 0) (times[times.size / 2 - 1] + times[times.size / 2]) / 2 else times[times.size / 2])
                            put("p95UpperBoundMs", times[ceil(times.size * .95).toInt() - 1])
                            put("maxUpperBoundMs", times.last())
                        })
                    }
                })
                put("imageRequests", JsonArray(fixture.images.map { request -> buildJsonObject {
                    put("page", request.page)
                    put("startedNs", request.startedNs)
                    request.finishedNs?.let { put("finishedNs", it) }
                } }))
            }.toString())
        }
        assertEquals(20, samples.count { it.getValue("kind").jsonPrimitive.content == "forward" })
        assertEquals(20, samples.count { it.getValue("kind").jsonPrimitive.content == "reverse" })
    }

    private class ImageRequest(val page: Int, val startedNs: Long = System.nanoTime()) {
        @Volatile var finishedNs: Long? = null
    }

    private class BaselineReader : AutoCloseable {
        val images = CopyOnWriteArrayList<ImageRequest>()
        private val current = AtomicInteger(1)
        private val revision = AtomicInteger(0)
        private val workers = Executors.newFixedThreadPool(4)
        fun color(page: Int) = Color(210 + page, 230 - page, 190 + page)
        // Encode once before sampling. The client must still request and decode these PNGs itself.
        private val pages = (1..24).associateWith { page ->
            ByteArrayOutputStream().also { output ->
                val bitmap = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
                bitmap.createGraphics().apply {
                    color = color(page)
                    fillRect(0, 0, bitmap.width, bitmap.height)
                    color = Color.BLACK
                    font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 32)
                    drawString("SYNTHETIC PAGE $page", 80, 100)
                    dispose()
                }
                ImageIO.write(bitmap, "png", output)
                bitmap.flush()
            }.toByteArray()
        }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = workers
            createContext("/v1/courses/READER_BASELINE/reader") { exchange ->
                exchange.use {
                    val path = exchange.requestURI.path
                    val image = if (path.endsWith("/image")) ImageRequest(path.substringBeforeLast('/').substringAfterLast('/').toInt()).also { images += it } else null
                    val body = when {
                        image != null -> pages.getValue(image.page)
                        path.endsWith("/resolve") -> """{"documentId":"synthetic-baseline","version":"v1","pdfPage":1}""".toByteArray()
                        path.endsWith("/state") -> """{"document":{"version":"v1"},"progress":{"version":"v1","pdfPage":${current.get()},"revision":${revision.get()}},"notes":[]}""".toByteArray()
                        path.endsWith("/progress") -> {
                            val write = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                            current.set(write.getValue("pdfPage").jsonPrimitive.int)
                            """{"revision":${revision.incrementAndGet()}}""".toByteArray()
                        }
                        path.endsWith("/study") -> """{"facts":[],"questions":[],"unlocated":0}""".toByteArray()
                        else -> """{"documents":[{"id":"synthetic-baseline","title":"Synthetic baseline","version":"v1","pages":24}]}""".toByteArray()
                    }
                    exchange.responseHeaders.set("Content-Type", if (image != null) "image/png" else "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    image?.finishedNs = System.nanoTime()
                }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "baseline-device", "synthetic-baseline-token", "READER_BASELINE")
        override fun close() {
            http.close()
            server.stop(0)
            workers.shutdownNow()
        }
    }
}
