package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import kotlin.test.*

class ConstructedRoutesTest {
    @Test fun authenticatedPoolCardAndSubmittedRecordUseFrozenServerQuestion() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("constructed-route")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=LedgerStore(dir).also { it.enroll("phone","synthetic-private-token-32-characters") }
        val actor=LedgerActor(scope,store)
        val engine=embeddedServer(Netty,host="127.0.0.1",port=0) { ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=8)) }
        engine.start(false)
        try {
            actor.execute {
                val rubric=JsonArray(listOf(json("id" to "R1","text" to "Explains the concept","points" to 10,"basis" to "Synthetic source")))
                val q=json("stem" to "Explain this synthetic concept.","reference_answer" to "A synthetic reference answer.","rubric" to rubric)
                val subject=json("course" to "c","kp" to "kp","generator" to "claude-sonnet-5-5")
                val id=enqueueJob("review_constructed",subject.toString(),"pack")
                assertTrue(storeConstructed(claimJob(id)!!,SupplyJob(id,"review_constructed",subject,"pack"),q,"frozen","gpt-6.1-sol"))
            }
            val port=engine.engine.resolvedConnectors().single().port
            val client=HttpClient.newHttpClient()
            suspend fun request(path: String, body: String?=null, authenticated: Boolean=true): HttpResponse<String> = withContext(Dispatchers.IO) {
                val b=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(java.time.Duration.ofSeconds(5))
                if(authenticated) b.header("X-Coach-Device","phone").header("Authorization","Bearer synthetic-private-token-32-characters")
                if(body!=null) b.POST(HttpRequest.BodyPublishers.ofString(body))
                client.send(b.build(),HttpResponse.BodyHandlers.ofString())
            }
            val path="/v1/courses/c/constructed"
            assertEquals(401,request(path,"{}",false).statusCode())
            val response=request(path,json("kpId" to "kp","attemptId" to "a").toString())
            assertEquals(200,response.statusCode())
            val card=LedgerJson.decodeFromString<Event>(response.body())
            assertFalse(response.body().contains("synthetic reference answer"))
            assertEquals(404,request("/v1/courses/c/attempts/a").statusCode())
            val bytes=LedgerJson.encodeToString(CommandBody("submit_answer","c",System.currentTimeMillis(),
                json("attemptId" to "a","answer" to "My synthetic answer.","posedSnapshot" to card.payload)))
            assertEquals(200,request("/v1/commands",LedgerJson.encodeToString(WireCommand("phone","submit",bytes,sha256(bytes.toByteArray())))).statusCode())
            val record=request("/v1/courses/c/attempts/a")
            assertEquals(200,record.statusCode())
            val value=LedgerJson.parseToJsonElement(record.body()).jsonObject
            assertEquals("My synthetic answer.",value.getValue("answer").jsonObject.str("answer"))
            assertEquals("frozen",value.str("rubricFingerprint"))
            assertTrue(value.getValue("grades").jsonArray.isEmpty())
            assertEquals(404,request("/v1/courses/other/attempts/a").statusCode())
        } finally { engine.stopSuspend(0,500);actor.close();scope.cancel();dir.toFile().deleteRecursively() }
    }
}
