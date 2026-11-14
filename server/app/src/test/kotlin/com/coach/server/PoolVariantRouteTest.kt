package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import kotlin.test.*

/**
 * `/pool` 的第二段：题级候选。只报点名考点上真实存在、已核键、还没出过题的单选；
 * 别的考点、未核键和已经出过的题不在这里出现，也不把题干/答案带出去。
 */
class PoolVariantRouteTest {
    @Test fun poolOffersOnlyLiveVerifiedUnposedQuestionsOfTheNamedKp() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("pool-variants-route")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir).also { it.enroll("phone", "synthetic-private-token-32-characters") }
        val actor = LedgerActor(scope, store)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
        engine.start(false)
        try {
            val options = listOf(Option("o1", "synthetic one"), Option("o2", "synthetic two"))
            actor.execute {
                registerQuestion("q-posed", "c", "fp-posed", "SINGLE_CHOICE", "Synthetic posed", "VERIFIED", "o1", options, listOf("kp"))
                registerQuestion("q-unverified", "c", "fp-unv", "SINGLE_CHOICE", "Synthetic unverified", "UNREVIEWED", "o1", options, listOf("kp"))
                registerQuestion("q-other-kp", "c", "fp-other", "SINGLE_CHOICE", "Synthetic other point", "VERIFIED", "o1", options, listOf("kp2"))
                registerQuestion("q-live", "c", "fp-live", "SINGLE_CHOICE", "Synthetic live", "VERIFIED", "o1", options, listOf("kp"))
                // 主线先取走第一道活题：它已经在学习者眼前出过，不能再当复习变式。
                takePoolGroup("c", "kp", "synthetic-group", 1)
            }
            val port = engine.engine.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            suspend fun get(path: String, authenticated: Boolean = true): HttpResponse<String> = withContext(Dispatchers.IO) {
                val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(java.time.Duration.ofSeconds(5))
                if (authenticated) b.header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                client.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }

            assertEquals(401, get("/v1/courses/c/pool?kp=kp", false).statusCode())

            val named = Json.parseToJsonElement(get("/v1/courses/c/pool?kp=kp").body()).jsonObject
            val rows = named.getValue("variants").jsonArray.map { it.jsonObject }
            assertEquals(listOf("q-live"), rows.map { it.getValue("questionId").jsonPrimitive.content },
                "只有这个考点上还没出过的已核题")
            val only = rows.single()
            assertEquals("kp", only.getValue("kpId").jsonPrimitive.content)
            assertEquals("VERIFIED", only.getValue("keyState").jsonPrimitive.content)
            assertEquals("VALID", only.getValue("availability").jsonPrimitive.content)
            // 指纹原样来自题库（注册时按内容算过），不是路由现编的。
            val stored = actor.execute { reviewVariantCandidates("c", "kp").single().question.fingerprint }
            assertEquals(stored, only.getValue("fingerprint").jsonPrimitive.contentOrNull)
            assertEquals(setOf("questionId", "kpId", "fingerprint", "keyState", "availability"), only.keys,
                "题级候选不夹带题干、选项或答案")

            // 不点名考点时不给任何题目：题库不能按课程整包枚举。
            val unnamed = Json.parseToJsonElement(get("/v1/courses/c/pool").body()).jsonObject
            assertEquals(0, (unnamed.getValue("variants") as JsonArray).size)
            assertTrue(unnamed["kpId"] is JsonNull)
            // 没有该考点就如实为空，不拿别的考点的题补。
            assertEquals(0, (Json.parseToJsonElement(get("/v1/courses/c/pool?kp=kp-missing").body()).jsonObject
                .getValue("variants") as JsonArray).size)

            // 旧的构造题计数还在，形状没变。
            assertTrue(named.getValue("constructed") is JsonObject)
            assertEquals(0, named.getValue("constructed").jsonObject.size)
        } finally { engine.stop(0, 0); scope.cancel(); dir.toFile().deleteRecursively() }
    }
}
