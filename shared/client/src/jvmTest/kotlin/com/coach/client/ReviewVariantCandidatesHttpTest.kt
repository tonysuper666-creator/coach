package com.coach.client

import com.coach.domain.reviewqueue.QueueAvailability
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.*

/**
 * 题级候选的客户端解析：只把服务端如实报告的行交给队列；
 * 没见过的可用状态不算可练，未核键照实标 false，字段缺失时返回 null（不知道，而不是「没有」）。
 */
class ReviewVariantCandidatesHttpTest {
    private fun serve(body: String, seen: MutableList<String>): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange.requestURI.toString()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test fun onlyRowsTheServerActuallyAttestsAreHandedOn() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val server = serve(
            """{"constructed":{},"kpId":"K1","variants":[
               {"questionId":"q-live","kpId":"K1","fingerprint":"fp-live","keyState":"VERIFIED","availability":"VALID"},
               {"questionId":"q-unverified","kpId":"K1","fingerprint":"fp-unv","keyState":"UNREVIEWED","availability":"VALID"},
               {"questionId":"q-quarantined","kpId":"K1","fingerprint":"fp-q","keyState":"VERIFIED","availability":"QUARANTINED"},
               {"questionId":"","kpId":"K1","fingerprint":"fp-blank","keyState":"VERIFIED","availability":"VALID"}]}""",
            seen,
        )
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "synthetic", "synthetic-token", "C")
        try {
            val rows = assertNotNull(http.reviewVariantCandidates("K1"))
            // 未知 availability 和空题号在这里就丢掉；未核键保留但如实标 false，由选择器再拒。
            assertEquals(listOf("q-live", "q-unverified"), rows.map { it.question.id })
            assertEquals("C", rows.first().courseId)
            assertEquals("K1", rows.first().kpId)
            assertEquals("fp-live", rows.first().question.fingerprint)
            assertNull(rows.first().question.version, "服务端没给版本就保持未知")
            assertEquals(QueueAvailability.VALID, rows.first().availability)
            assertTrue(rows.first().keyVerified)
            assertFalse(rows[1].keyVerified, "未核键不能因为字段缺失就被当成已核")
            assertEquals(listOf("/v1/courses/C/pool?kp=K1"), seen)
        } finally { http.close(); server.stop(0) }
    }

    @Test fun aServerWithoutTheQuestionLevelFieldIsUnknownNotEmpty() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val server = serve("""{"constructed":{"K1":3}}""", seen)
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "synthetic", "synthetic-token", "C")
        try {
            // 老服务端只报计数：这时不能说「题库没有多余题」，只能说这次拿不到。
            assertNull(http.reviewVariantCandidates("K1"))
            assertEquals(mapOf("K1" to 3), http.constructedSupply())
        } finally { http.close(); server.stop(0) }
    }
}
