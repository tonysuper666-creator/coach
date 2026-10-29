package com.coach.store

import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

class ChoiceLedgerTest {
    private val options=(1..4).map { Option("option-$it","Synthetic choice text $it") }
    private fun request(i: Int)=json("attemptId" to "a$i","questionId" to "q$i","kpIds" to listOf("kp"),"purpose" to "PRACTICE","policyVersion" to 1)
    private fun map(e: Event)=e.payload.getValue("letterMap").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun submit(snapshot: JsonObject, letter: String, command: String): WireCommand {
        val body=CommandBody("submit_answer","course",System.currentTimeMillis(),json("attemptId" to snapshot.str("attemptId"),"answer" to letter,"offline" to true,"posedSnapshot" to snapshot))
        val bytes=canonicalJson(LedgerJson.encodeToJsonElement(body))
        return WireCommand("phone",command,bytes,sha256(bytes.toByteArray()))
    }
    @Test fun offlineGroupsReuseOnlineRulesAndSurviveRestartRollbackAndRetry() {
        val root=Files.createTempDirectory("choice-ledger-")
        val directory=root.resolve("db");var store=LedgerStore(directory)
        try {
            repeat(6) { i -> store.registerQuestion("q$i","course","ignored","SINGLE_CHOICE","Synthetic stem $i","VERIFIED","option-2",options) }
            val backup=store.backup(root.resolve("backup"))
            val pack=store.offlineChoicePack("course","pack",(0..5).map(::request),"phone",Random(1076))
            val all=pack.groups.flatten()
            assertEquals(listOf(3,3),pack.groups.map { it.size });assertEquals(6,pack.keys.size)
            var previous: String?=null
            pack.groups.forEach { group ->
                val letters=group.map { map(it).getValue("option-2") }
                assertEquals(3,letters.distinct().size);assertNotEquals(previous,letters.first());previous=letters.last()
            }
            assertEquals(pack,store.offlineChoicePack("course","pack",(0..5).map(::request),"phone",Random(99)))
            assertEquals(6,store.events("course").size)
            val first=all.first();val correct=map(first).getValue("option-2")
            val altered=JsonObject(first.payload+ ("letterMap" to JsonObject(map(first).mapValues { (id,letter) -> JsonPrimitive(when(id) { "option-2" -> map(first).getValue("option-1"); "option-1" -> correct; else -> letter }) })))
            assertEquals("INVALID_LETTER_MAP",store.command(submit(altered,correct,"altered")).error)
            assertEquals(6,store.events("course").size)
            val good=submit(first.payload,correct,"good")
            assertEquals("OK",store.command(good).status)
            val grade=store.events("course").last();assertEquals(1L,grade.payload.num("score"))
            assertEquals("回答正确（$correct）。",grade.payload.str("feedback"))
            store.close();store=LedgerStore(directory)
            assertEquals("DUPLICATE",store.command(good).status)
            assertEquals(first,store.events("course").first())
            store.close();store=LedgerStore.restore(directory,backup)
            assertEquals("OK",store.command(good).status)
            assertEquals(first.payload.getValue("letterMap"),store.events("course").first().payload.getValue("letterMap"))
            assertEquals(1L,store.events("course").last().payload.num("score"))
            assertEquals("DUPLICATE",store.command(good).status)
            println("LETTER_LEDGER offlineGroups=2 questions=6 retryStable=true restartStable=true rollbackStable=true feedbackMatchesCard=true")
        } finally { store.close();root.toFile().deleteRecursively() }
    }
    @Test fun anInvalidMemberRollsBackTheWholeGroup() {
        val root=Files.createTempDirectory("choice-atomic-")
        try { LedgerStore(root).use { store ->
            store.registerQuestion("q0","course","ignored","SINGLE_CHOICE","Synthetic stem","VERIFIED","option-2",options)
            assertFails { store.poseChoiceGroup("course","g",listOf(request(0),request(1)),Random(1)) }
            assertTrue(store.events("course").isEmpty())
            val event=store.poseChoiceGroup("course","g",listOf(request(0)),Random(1)).single()
            assertEquals(event,store.poseChoiceGroup("course","g",listOf(request(0)),Random(999)).single())
        } } finally { root.toFile().deleteRecursively() }
    }
}

class LegacyChoiceSchemaTest {
    @Test fun migrationConvertsLegacyKeysBacksUpAndPreservesHistoricalEvents() {
        val root=Files.createTempDirectory("choice-v1-")
        try {
            LedgerStore(root).close()
            val rawOptions=json("A" to "First text","B" to "Second text","C" to "Third text","D" to "Fourth text")
            val historical=Event(1,"historical","course","question_posed",recordedAt=1,occurredAt=1,
                deviceId="server",actor="SYSTEM",idemKey="attempt:historical",
                payload=json("attemptId" to "historical","questionId" to "old","fingerprint" to "old-fingerprint","format" to "SINGLE_CHOICE"))
            java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { db ->
                db.createStatement().use { it.execute("PRAGMA user_version=1") }
                db.prepareStatement("INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) VALUES('old','course','SINGLE_CHOICE','Legacy synthetic stem',?,'COACH','old-fingerprint','VERIFIED')").use {
                    it.setString(1,rawOptions.toString());it.executeUpdate()
                }
                db.createStatement().use { it.execute("INSERT INTO answer_keys VALUES('old','{\"choice\":\"B\"}',NULL,NULL)") }
                db.prepareStatement("INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) VALUES(1,'historical','course','question_posed',1,1,1,'server','SYSTEM',0,'attempt:historical',?,?)").use {
                    it.setString(1,historical.payload.toString());it.setString(2,LedgerJson.encodeToString(historical));it.executeUpdate()
                }
            }
            LedgerStore(root).use { store ->
                val event=store.poseChoiceGroup("course","new",listOf(json("questionId" to "old","attemptId" to "new","kpIds" to listOf("kp"),"purpose" to "PRACTICE","policyVersion" to 1)),Random(1)).single()
                assertEquals(historical,store.events("course").first())
                val options=LedgerJson.decodeFromJsonElement<List<Option>>(event.payload.getValue("options"))
                val correct=options.single { it.text == "Second text" }.optionId
                assertTrue(correct !in CHOICE_LETTERS)
                assertEquals(questionFingerprint("Legacy synthetic stem",options),event.payload.str("fingerprint"))
                val backups=Files.list(root.resolve("backups")).use { it.filter { path -> path.fileName.toString().startsWith("pre-V002-") }.toList() }
                assertTrue(backups.isNotEmpty())
                java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use { db ->
                    db.createStatement().executeQuery("SELECT key_json FROM answer_keys WHERE question_id='old'").use {
                        assertTrue(it.next());assertEquals(correct,LedgerJson.parseToJsonElement(it.getString(1)).jsonObject.str("correctOptionId"))
                    }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
