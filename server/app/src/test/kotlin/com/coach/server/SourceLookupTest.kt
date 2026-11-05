package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SourceLookupTest {
    @Test fun citationsAreWithheldUntilValidatedAndPartialTailsAreDiscarded() {
        val gate = SourceCitationGate(mapOf("S1" to "A synthetic page explains adverse selection."))
        assertEquals(emptyList(),gate.push("“A synthetic page"))
        assertEquals(listOf("“A synthetic page explains adverse selection.”【S1】\n\n"),gate.push(" explains adverse selection.”【S1】\n\n"))
        assertEquals(emptyList(),gate.push("“invented quotation”【S1】\n\nUnknown.【S99】\n\nNo citation.\n\n"))
        gate.push("Unfinished valid source【S1】")
        assertEquals(emptyList(),gate.finish(false))
        assertEquals(1,gate.accepted)
    }

    @Test fun sourcePacksRespectBothBudgetsAndPreferHigherScores() {
        val pages = (1..8).map { SourcePage("book",it,"text ".repeat(1000),it.toDouble()) }
        val pack = SourcePack.prepare("old page",pages)
        assertEquals(6,pack.pages.size)
        assertEquals(8,pack.pages.first().pdfPage)
        assertTrue(pack.render().length <= 40_000)
        assertEquals("",pack.legacy)
        val merged = SourcePack.prepare(pack.render(),listOf(SourcePage("new",1,"new relevant page",100.0)))
        assertEquals(6,merged.pages.size)
        assertEquals("new",merged.pages.first().docId)
        assertTrue(merged.pages.none { it.docId == "book" && it.pdfPage == 3 })
        val unchanged = SourcePack.prepare("existing full page",listOf(SourcePage("same",1,"existing full page")))
        assertEquals("existing full page",unchanged.render())
        val large = SourcePack.prepare("old ".repeat(20000),listOf(SourcePage("book",1,"x".repeat(40_000))))
        assertTrue(large.render().length <= 40_000)
    }

    @Test fun restrictedPagesHaveNoLocatorOrAnchorAndCannotBeCited() {
        val secret = "A synthetic restricted sentence about unusual insurance scenarios."
        val pack = SourcePack.prepare("",listOf(SourcePage("book",1,"A public source.",1.0),
            SourcePage("sealed-secret",4,secret,100.0,learnerVisible=false)))
        assertEquals(mapOf("S1" to "A public source."),pack.anchors)
        assertTrue(pack.render().contains("内部参考（不可引用、不可复述）"))
        assertFalse(pack.render().contains("sealed-secret"))
        assertFalse(pack.render().substringAfter("内部参考").contains("【S"))
        val gate = SourceCitationGate(pack.anchors)
        assertTrue(gate.push("“$secret”【S1】\n\n").isEmpty())
        assertEquals(listOf("“A public source.”【S1】\n\n"),gate.push("“A public source.”【S1】\n\n"))
        assertTrue(SourcePack.prepare("",listOf(SourcePage("secret",1,secret,learnerVisible=false))).pages.isEmpty())
        assertFalse(SourcePack.prepare(pack.render(),emptyList()).anchors.values.any { secret in it })
    }

    @Test fun dedupeNeverDropsTextTheSamePromptCannotCarry() {
        val page = "A synthetic page defining adverse selection for the tutor."
        // The existing context is over the whole-pack budget, so it cannot ride along as legacy.
        val overBudget = "pasted learner context. ".repeat(2000) + page
        val kept = SourcePack.prepare(overBudget,listOf(SourcePage("book",1,page,50.0)))
        assertEquals("",kept.legacy)
        assertEquals(listOf(1),kept.pages.map { it.pdfPage })
        assertTrue(kept.render().contains(page))
        // Other hits fill the six-page cap, so the legacy block holding the duplicate is dropped too.
        val others = (1..7).map { SourcePage("book",it + 1,"A distinct synthetic page $it.",100.0 - it) }
        val capped = SourcePack.prepare("pasted learner context.\n\n$page",listOf(SourcePage("book",1,page,200.0)) + others)
        assertEquals("",capped.legacy)
        assertEquals(6,capped.pages.size)
        assertTrue(capped.render().contains(page))
        // Falling back to the hits must restore the anchor without giving the restricted page one.
        val secret = "A synthetic restricted sentence about unusual reinsurance scenarios."
        val mixed = SourcePack.prepare(overBudget,listOf(SourcePage("book",1,page,50.0),
            SourcePage("sealed-ref",4,secret,90.0,learnerVisible=false)))
        assertEquals(mapOf("S1" to page),mixed.anchors)
        assertFalse(mixed.render().substringAfter("内部参考").contains("【S"))
    }

    @Test fun restrictedTextAlreadyInContextNeverBecomesAnAnchor() {
        val secret = "A synthetic restricted sentence about unusual reinsurance scenarios."
        val public = "A synthetic public page about adverse selection."
        val pack = SourcePack.prepare("Learner pasted note: $secret",listOf(
            SourcePage("book",1,public,10.0),SourcePage("sealed-ref",4,secret,90.0,learnerVisible=false)))
        assertEquals(mapOf("S1" to public),pack.anchors)
        assertEquals(listOf("book"),pack.pages.map { it.docId })
        assertFalse(pack.render().contains("sealed-ref"))
        assertFalse(pack.render().contains("【S2】"))
    }

    @Test fun restrictedOnlyHitsStayFailClosedAndLookupCarriesNoLegacy() {
        val secret = "A synthetic restricted sentence about unusual reinsurance scenarios."
        val only = SourcePack.prepare("",listOf(SourcePage("sealed-ref",4,secret,90.0,learnerVisible=false)))
        assertTrue(only.browsable.isEmpty())
        assertEquals(emptyMap(),only.anchors)
        assertEquals("",only.legacy)
        assertFalse(only.render().contains("【S"))
        val mixed = SourcePack.prepare("",listOf(SourcePage("book",1,"A synthetic public page.",10.0),
            SourcePage("sealed-ref",4,secret,90.0,learnerVisible=false)))
        assertEquals(mapOf("S1" to "A synthetic public page."),mixed.anchors)
        assertEquals("",mixed.legacy)
        assertFalse(mixed.render().substringBefore("内部参考").contains(secret))
        assertFalse(mixed.render().substringAfter("内部参考").contains("【S"))
        // Over-budget context holding the same restricted text must still fail closed.
        val pasted = SourcePack.prepare("pasted learner context. ".repeat(2000) + secret,
            listOf(SourcePage("sealed-ref",4,secret,90.0,learnerVisible=false)))
        assertTrue(pasted.browsable.isEmpty())
        assertEquals(emptyMap(),pasted.anchors)
        assertEquals("",pasted.legacy)
    }

    @Test fun duplicateTextNeverBreaksThePageOrCharacterBudget() {
        val pages = (1..8).map { SourcePage("book",it,"text ".repeat(1000) + it,it.toDouble()) }
        val pack = SourcePack.prepare("pasted learner context. ".repeat(2000),pages)
        assertTrue(pack.pages.size <= 6)
        assertEquals("",pack.legacy)
        assertTrue(pack.render().length <= 40_000)
    }

    @Test fun dualFtsIsCourseScopedAndAnswerDirectoriesAreInternalOnly() {
        LedgerStore(Files.createTempDirectory("source-fts-")).use { store ->
            store.registerSourcePage("c","book","Book",1,"Adverse selection and 逆向选择 are examples.")
            store.registerSourcePage("other","other-book","Book",1,"Adverse selection")
            store.registerSourcePage("c","private","Hidden",1,"Adverse selection",false)
            store.registerSourcePage("c","answer_keys/book","Keys",1,"Adverse selection")
            store.registerSourcePage("c","answers","答案",1,"Adverse selection")
            store.registerSourcePage("c","Answers/book","Solutions",1,"Adverse selection")
            store.registerSourcePage("c","answer-keys/book","Solutions",1,"Adverse selection")
            val hits = store.searchSourcePages("c","adverse selection")
            assertEquals("book",hits.first().docId)
            assertTrue(hits.drop(1).isNotEmpty())
            assertTrue(hits.drop(1).all { !it.learnerVisible && it.docId.contains("answer",ignoreCase=true) })
            assertTrue(hits.none { it.docId == "private" || it.docId == "other-book" })
            val pack = SourcePack.prepare("",hits)
            assertEquals(mapOf("S1" to hits.first().text),pack.anchors)
            hits.drop(1).forEach { assertNull(store.browsablePage("c",it.docId,it.pdfPage)) }
            assertEquals(listOf("book"),store.searchSourcePages("c","逆向选择是什么").map { it.docId })
            store.registerSourcePage("c","book","Book",1,"Moral hazard is different.")
            assertTrue(store.searchSourcePages("c","adverse selection").all { !it.learnerVisible })
            assertEquals(listOf("book"),store.searchSourcePages("c","moral hazard").map { it.docId })
            assertEquals(emptyList(),store.searchSourcePages("c","\" OR * : ( )"))
        }
    }

    @Test fun osSandboxDeniesAnswerKeysChineseAnswerDirectoryWritesAndSymlinkEscapes() {
        val dir = Files.createTempDirectory("source-sandbox-")
        try {
            val allowed = dir.resolve("page.txt").also { Files.writeString(it,"allowed synthetic textbook") }
            val keys = Files.createDirectories(dir.resolve("answer_keys")).resolve("key.txt").also { Files.writeString(it,"secret") }
            val chinese = Files.createDirectories(dir.resolve("答案")).resolve("key.txt").also { Files.writeString(it,"secret") }
            val unrelated = dir.resolve("ledger.sqlite").also { Files.writeString(it,"private") }
            val link = dir.resolve("escape").also { Files.createSymbolicLink(it,keys) }
            val volumeAlias = java.nio.file.Path.of("/System/Volumes/Data" + keys.toRealPath())
            val sandbox = SourceReadSandbox()
            for (target in listOf(allowed,keys,chinese,unrelated,link,volumeAlias)) {
                val process = ProcessBuilder(sandbox.command(listOf(allowed),listOf(target))).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val output = process.inputStream.readAllBytes().decodeToString()
                assertTrue(process.waitFor(2,TimeUnit.SECONDS))
                assertEquals(target == allowed,process.exitValue() == 0,"target=${target.fileName}")
                if (target != allowed) assertEquals("",output)
            }
            // Run a write-capable system utility under the EXACT same read-only profile.
            val command = sandbox.command(listOf(allowed),emptyList()).toMutableList()
            command[2] = command[2].replace("(literal \"/bin/cat\")","(literal \"/usr/bin/touch\")")
            command[3] = "/usr/bin/touch"
            command += dir.resolve("new-file").toString()
            val writer = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(writer.waitFor(2,TimeUnit.SECONDS))
            assertTrue(writer.exitValue() != 0)
            assertFalse(Files.exists(dir.resolve("new-file")))
        } finally { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private class Harness(protected: Boolean = false, timeoutMs: Long = 90_000) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createTempDirectory("source-chat-")).also {
            it.registerSourcePage("c","book","Synthetic book",1,"Adverse selection means hidden risk before contracting.")
        }
        val actor = LedgerActor(scope,store)
        val stub = StubTutor()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val frames = Collections.synchronizedList(mutableListOf<JsonObject>())
        val chat = ChatService(actor,scope,BridgeClient(BridgeConfig(stub.baseUrl)),
            fallbackOnFirstTokenTimeout=false,allowSourceLookup=true,sourceLookupProtected={protected},
            sourceLookupTimeoutMs=timeoutMs,log={logs += it})
        val collector = scope.launch(start=CoroutineStart.UNDISPATCHED) { chat.hub.frames.collect { frames += it } }
        suspend fun awaitEnd(id: String): String = withTimeout(8_000) {
            while (true) {
                val row = chat.snapshot(id)
                if (row?.str("status") != "streaming") return@withTimeout row!!.str("text")
                delay(10)
            }
            error("unreachable")
        }
        fun lookupCalls(): List<Pair<String,Int>> = DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { st -> st.executeQuery("SELECT status,physical_calls FROM ai_calls WHERE request_type='source_lookup'").use { r -> buildList { while(r.next()) add(r.getString(1) to r.getInt(2)) } } }
        }
        override fun close() { runBlocking { actor.close() }; scope.cancel(); stub.close() }
    }

    @Test fun splitMarkerTriggersExactlyOneLookupWithOnlyValidatedSupplementOnSameMessage() = runBlocking {
        Harness().use { h ->
            h.stub.stream(5,"Initial answer.","【需查", "原文:adverse selection】",StubTutor.terminal("complete"),StubTutor.DONE)
            h.stub.stream(5,"“hidden risk before contracting”【S1】\n\n", "“made up”【S1】\n\n", "Unknown.【S9】\n\n", "Unanchored after filtering. Choose B【S1】\n\n", "Plain text.\nplaceholder【S1】\n\n", "【需查原文:again】",StubTutor.terminal("complete"),StubTutor.DONE)
            val id = h.chat.ask("t","c","Explain adverse selection")
            val text = h.awaitEnd(id)
            assertEquals("Initial answer.\n\n补充：\n“hidden risk before contracting”【S1】\n\n",text)
            assertEquals(2,h.stub.requests)
            assertEquals(listOf("complete" to 1),h.lookupCalls())
            val progress = h.frames.first { it.str("t") == "msg.progress" && it.str("phase") == "source_lookup" }
            assertEquals("导师去翻原文了",(com.coach.client.ChatStreamAssembler().handle(progress).single() as com.coach.client.ChatUpdate.Progress).text)
            assertEquals(1,h.frames.filter { it.str("t") == "msg.start" }.map { it.str("msg") }.distinct().size)
            assertTrue(h.stub.requestBodies.first().contains("hidden risk before contracting"))
            assertTrue(h.actor.execute { events("c") }.none { it.type in setOf("attempt_graded","question_posed","explanation_delivered") })
        }
    }

    @Test fun restrictedLookupEchoIsNeverPublishedOrPersisted() = runBlocking {
        Harness().use { h ->
            val secret = "Adverse selection reveals a synthetic confidential sequence of silver umbrellas"
            h.actor.execute { registerSourcePage("c","private-ref","Private reference",1,secret,learnerVisible=false) }
            h.stub.stream(1,"Initial answer.【需查原文:adverse selection】",StubTutor.terminal("complete"),StubTutor.DONE)
            h.stub.stream(1,"$secret【S1】\n\n", "“hidden risk before contracting”【S1】\n\n",StubTutor.terminal("complete"),StubTutor.DONE)
            val id = h.chat.ask("t","c","Explain adverse selection")
            val result = h.awaitEnd(id)
            assertFalse(result.contains(secret))
            assertTrue(result.contains("hidden risk before contracting"))
            assertFalse(h.frames.any { it.toString().contains(secret) })
            assertFalse(h.chat.snapshot(id).toString().contains(secret))
            assertTrue(h.stub.requestBodies.last().contains(secret))
        }
    }

    @Test fun protectionAndInitialTeachingNeverStartLookupEvenWhenMarkerIsInjected() = runBlocking {
        Harness(protected=true).use { h ->
            h.stub.stream(1,"Safe answer.【需查原文:adverse selection】",StubTutor.terminal("complete"),StubTutor.DONE)
            assertEquals("Safe answer.",h.awaitEnd(h.chat.ask("t","c","adverse selection")))
            assertEquals(1,h.stub.requests)
            assertEquals(emptyList(),h.lookupCalls())
        }
        Harness().use { h ->
            h.chat.openTutor("t","c","kp",setOf("concept"),"Synthetic topic")
            h.stub.stream(1,"Lesson.【需查原文:adverse selection】",StubTutor.terminal("complete"),StubTutor.DONE)
            assertEquals("Lesson.",h.awaitEnd(h.chat.nextStep("t","c")))
            assertEquals(1,h.stub.requests)
            assertEquals(emptyList(),h.lookupCalls())
        }
    }

    @Test fun timeoutKeepsVerifiedParagraphAndCancellationAbortsLookup() = runBlocking {
        for (cancel in listOf(false,true)) Harness(timeoutMs=if(cancel) 5000 else 500).use { h ->
            h.stub.stream(1,"Initial.【需查原文:adverse selection】",StubTutor.terminal("complete"),StubTutor.DONE)
            h.stub.stream(100,"Verified.【S1】\n\n","Unverified tail", "more", "more", "more", "more",StubTutor.terminal("complete"),StubTutor.DONE)
            val id = h.chat.ask("t","c","adverse selection")
            withTimeout(4000) { while (!h.chat.snapshot(id)!!.str("text").contains("Verified")) delay(10) }
            assertEquals("source_lookup",h.chat.snapshot(id)!!.str("phase"))
            if (cancel) assertTrue(h.chat.cancel(id))
            val text = h.awaitEnd(id)
            assertTrue(text.contains("Verified"))
            assertFalse(text.contains("Unverified"))
            val row = h.chat.snapshot(id)!!
            assertEquals(if(cancel) "cancelled" else "truncated",row.str("status"))
            assertEquals(if(cancel) "cancelled" else "source_lookup_timeout",row.str("reason"))
            assertEquals(1,h.lookupCalls().size)
        }
    }
}
