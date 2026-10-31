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

class BrowseRoutesTest {
    @Test fun learnerCanReadCourseTextButNeverAnswerOrSealedMaterial() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("browse-route")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=LedgerStore(dir).also { it.enroll("phone","synthetic-private-token-32-characters") }
        val actor=LedgerActor(scope,store)
        val engine=embeddedServer(Netty,host="127.0.0.1",port=0) { ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=8)) }
        engine.start(false)
        try {
            actor.execute {
                registerSourcePage("c","textbook:1","01 Synthetic chapter.md",32,"First synthetic page about deductibles.")
                registerSourcePage("c","textbook:1","01 Synthetic chapter.md",33,"Second synthetic page.")
                registerSourcePage("c","answers:1","Chapter answers.md",5,"Synthetic answer key text.",tutorVisible=false)
                registerSourcePage("c","textbook:2","答案 附录.md",7,"Synthetic hidden appendix.")
                registerSourcePage("other","textbook:3","Other course.md",1,"Another course.")
            }
            val port=engine.engine.resolvedConnectors().single().port
            val client=HttpClient.newHttpClient()
            suspend fun get(path: String, authenticated: Boolean=true): HttpResponse<String> = withContext(Dispatchers.IO) {
                val b=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(java.time.Duration.ofSeconds(5))
                if(authenticated) b.header("X-Coach-Device","phone").header("Authorization","Bearer synthetic-private-token-32-characters")
                client.send(b.build(),HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(401,get("/v1/courses/c/sources",false).statusCode())
            val docs=Json.parseToJsonElement(get("/v1/courses/c/sources").body()).jsonObject.getValue("docs").jsonArray.map { it.jsonObject }
            assertEquals(listOf("textbook:1"),docs.map { it.getValue("docId").jsonPrimitive.content })
            assertEquals(32,docs.single().getValue("firstPdfPage").jsonPrimitive.int); assertEquals(33,docs.single().getValue("lastPdfPage").jsonPrimitive.int)
            val page=Json.parseToJsonElement(get("/v1/courses/c/sources/textbook:1/pages/32").body()).jsonObject
            assertTrue("deductibles" in page.getValue("text").jsonPrimitive.content)
            // Hidden and foreign material is not found, not merely filtered from the list.
            assertEquals(404,get("/v1/courses/c/sources/answers:1/pages/5").statusCode())
            assertEquals(404,get("/v1/courses/c/sources/textbook:2/pages/7").statusCode())
            assertEquals(404,get("/v1/courses/c/sources/textbook:3/pages/1").statusCode())
            assertEquals(404,get("/v1/courses/c/sources/textbook:1/pages/99").statusCode())
            assertEquals(404,get("/v1/courses/c/sources/textbook:1/pages/x").statusCode())
            // Supply: an empty course reports no constructed questions at all.
            assertEquals(0,Json.parseToJsonElement(get("/v1/courses/c/pool").body()).jsonObject.getValue("constructed").jsonObject.size)
            actor.execute {
                val rubric=JsonArray(listOf(json("id" to "R1","text" to "Explains the concept","points" to 10,"basis" to "Synthetic source")))
                val q=json("stem" to "Explain this synthetic concept.","reference_answer" to "A synthetic reference answer.","rubric" to rubric)
                val subject=json("course" to "c","kp" to "kp-with-supply","generator" to "claude-sonnet-5-5")
                val id=enqueueJob("review_constructed",subject.toString(),"pack")
                assertTrue(storeConstructed(claimJob(id)!!,SupplyJob(id,"review_constructed",subject,"pack"),q,"frozen","gpt-6.1-sol"))
            }
            val supply=Json.parseToJsonElement(get("/v1/courses/c/pool").body()).jsonObject.getValue("constructed").jsonObject
            assertEquals(1,supply.getValue("kp-with-supply").jsonPrimitive.int); assertNull(supply["kp-without"])
        } finally { engine.stop(0,0); scope.cancel() }
    }
}
