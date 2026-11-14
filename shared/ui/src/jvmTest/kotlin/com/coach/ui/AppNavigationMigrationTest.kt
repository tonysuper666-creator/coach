package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import com.coach.domain.json
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Original Scout-W / Expert v2 attachment, kept byte-for-byte; now exercised by the product codec. */
class AppNavigationMigrationTest {
    private val cases = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/navigation/expert-v2-legacy-cases.json")).readText())
        .jsonObject.getValue("cases").jsonArray

    @Test fun all27OriginalInputsRunThroughTheProductMigratorAndReopen() {
        assertEquals(27, cases.size)
        for (case in cases) {
            val c = case.jsonObject
            val name = c.getValue("id").jsonPrimitive.content
            val input = c.getValue("old_input").jsonPrimitive.content
            val old = runCatching { Json.parseToJsonElement(input) as? JsonArray }.getOrNull()
            if (old == null || old.isEmpty()) {
                assertNull(AppNavigation.restore(input), name)
                val recovered = assertNotNull(AppNavigation.restore(AppNavigation.recover(input).encode()))
                assertEquals(input, Json.parseToJsonElement(recovered.encode()).jsonObject.getValue("unreadableLegacy").jsonPrimitive.content, name)
                continue
            }
            val migrated = assertNotNull(AppNavigation.restore(input), name)
            val encoded = Json.parseToJsonElement(migrated.encode()).jsonObject
            assertEquals(2, encoded.getValue("version").jsonPrimitive.int, name)
            // No normalization may drop explicit null/defaults, unknown tokens or additional fields.
            assertEquals(old, JsonArray(encoded.getValue("entries").jsonArray.takeLast(old.size)), name)
            val decoded = c.getValue("codec").jsonObject["decoded"] as? JsonArray
            if (decoded != null) for ((frame, expected) in migrated.entries.takeLast(old.size).zip(decoded)) {
                val e = expected.jsonObject
                assertEquals(e.getValue("page").jsonPrimitive.content, frame.page.name, name)
                assertEquals(e.getValue("tab").jsonPrimitive.content, frame.tab.name, name)
                assertEquals(e.getValue("id").jsonPrimitive.content, frame.id, name)
                assertEquals(e.getValue("autoStart").jsonPrimitive.boolean, frame.autoStart, name)
                assertEquals(e.getValue("review").jsonPrimitive.boolean, frame.review, name)
                assertEquals(e["week"]?.jsonPrimitive?.intOrNull, frame.week, name)
                assertEquals(e.getValue("pdfPage").jsonPrimitive.int, frame.pdfPage, name)
                assertEquals(e.getValue("label").jsonPrimitive.content, frame.label, name)
            }
            val reopened = assertNotNull(AppNavigation.restore(migrated.encode()))
            for (frame in migrated.entries.reversed()) {
                assertEquals(frame.copy(original = null), reopened.current.copy(original = null), "$name back to ${frame.page}")
                if (frame.original != null) assertEquals(frame.original, reopened.current.original, name)
                reopened.back()
            }
            assertFalse(reopened.canGoBack, name)
        }
    }

    @Test fun primaryProjectionKeepsLegacyTaskAndSourceStateKeys() {
        val owners = mapOf(AppTab.TODAY to AppTab.TODAY, AppTab.ROUTE to AppTab.ROUTE,
            AppTab.REVIEW to AppTab.TODAY, AppTab.RECORDS to AppTab.ROUTE, AppTab.RESOURCES to AppTab.RESOURCES)
        assertEquals(listOf("今天", "课程", "书架"), primaryAppTabs.map { it.navigationLabel })
        for ((legacy, primary) in owners) {
            val nav = assertNotNull(AppNavigation.restore("""[{"tab":"$legacy"},{"page":"STUDY","tab":"$legacy","id":"SYN-KP","review":true,"week":3},{"page":"SOURCE","tab":"$legacy","id":"SYN-DOC","pdfPage":4,"label":"原文"}]"""))
            assertEquals(primary, nav.current.primaryTab)
            assertEquals("C:AppEntry(page=SOURCE, tab=$legacy, id=SYN-DOC, autoStart=false, review=false, week=null, pdfPage=4, label=原文)", nav.current.sourceStateKey("C"))
            nav.back()
            assertEquals("C:STUDY:$legacy:SYN-KP:true:3", nav.current.stateKey("C"))
            assertEquals(legacy, nav.current.tab)
        }
    }

    @Test fun unknownFutureFramesAndEnvelopeSurviveBackAndKnownLabelEdits() {
        val input = """{"version":9,"future":{"keep":[1,2]},"entries":[{"tab":"ARCHIVE","shelf":"old"},{"page":"LIBRARY","tab":"ARCHIVE","label":"历史","id":"SYN-ID","futureField":{"x":true}}]}"""
        val nav = assertNotNull(AppNavigation.restore(input))
        assertTrue(nav.current.unsupported)
        nav.rename("历史任务")
        val encoded = Json.parseToJsonElement(nav.encode()).jsonObject
        val original = Json.parseToJsonElement(input).jsonObject
        assertEquals(original["future"], encoded["future"])
        assertEquals(original["version"], encoded["version"])
        assertEquals(JsonObject(original.getValue("entries").jsonArray.last().jsonObject + ("label" to JsonPrimitive("历史任务"))), encoded.getValue("entries").jsonArray.last())
        val reopened = assertNotNull(AppNavigation.restore(nav.encode()))
        assertEquals("C:LIBRARY:ARCHIVE:SYN-ID:false:null", reopened.current.stateKey("C"))
        reopened.back()
        assertTrue(reopened.current.unsupported)
        assertEquals("old", Json.parseToJsonElement(reopened.encode()).jsonObject.getValue("entries").jsonArray.single().jsonObject.getValue("shelf").jsonPrimitive.content)
    }

    @Test fun malformedKnownFieldsRemainRecoverableAndCannotRunAsTasks() {
        for (frame in listOf("""{"page":null,"id":"SYN"}""", """{"page":{"future":true}}""",
            """{"page":"STUDY","tab":null,"id":"SYN"}""", """{"page":"MOCK","week":"unknown"}""")) {
            val old = Json.parseToJsonElement("[$frame]").jsonArray
            val nav = assertNotNull(AppNavigation.restore(old.toString()))
            assertTrue(nav.current.unsupported, frame)
            assertEquals(old.last(), Json.parseToJsonElement(nav.encode()).jsonObject.getValue("entries").jsonArray.last(), frame)
        }
    }

    @Test fun sqliteUpgradeReopenKeepsOriginalTaskSlotsAndExamDraftIdentities() {
        val file = Files.createTempFile("synthetic-navigation-", ".sqlite").toFile()
        val week = json("item" to "SYN-ITEM", "choice" to 2, "result" to json("correct" to false),
            "pending" to json("submissionId" to "SYN-PENDING"), "quizScroll" to 214, "readScroll" to 375)
        val drafts = json("SYN-MOCK" to json("Q1" to json("choice" to 1)), "SYN-OTHER" to json("Q2" to json("choice" to 3)))
        val active = json("week" to 3, "entry" to "resume-quiz", "targetKp" to "SYN-KP", "open" to true)
        val slots = mapOf("week:3" to week, "mock-drafts" to drafts, "active" to active,
            "choices:SYN-THREAD" to json("SYN-ATTEMPT" to "B"))
        val old = """[{"tab":"RECORDS"},{"page":"MAINLINE","tab":"RECORDS","week":3,"id":"continue"},{"page":"MOCK","tab":"RECORDS","id":"SYN-MOCK","label":"模拟考"}]"""
        fun open(): Pair<JdbcSqliteDriver, SyncEngine> {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
            val db = ClientDatabase(driver)
            // The transport is never called; use the existing strict local-ledger fixture's interface contract.
            return driver to SyncEngine(db, "synthetic", noNavigationNetwork, listOf("C"), { "id" }, { "hash" })
        }
        try {
            open().let { (driver, sync) ->
                driver.use {
                    ClientDatabase.Schema.create(it)
                    slots.forEach { (key, value) -> sync.saveMainlineState("C", value, key) }
                    sync.saveMainlineState("C", json("stack" to old), "navigation")
                }
            }
            open().let { (driver, sync) -> driver.use {
                val migrated = assertNotNull(AppNavigation.restore(sync.mainlineState("C", "navigation").getValue("stack").jsonPrimitive.content))
                sync.saveMainlineState("C", json("stack" to migrated.encode()), "navigation")
            } }
            open().let { (driver, sync) -> driver.use {
                val reopened = assertNotNull(AppNavigation.restore(sync.mainlineState("C", "navigation").getValue("stack").jsonPrimitive.content))
                assertEquals("SYN-MOCK", reopened.current.id)
                assertEquals(AppTab.ROUTE, reopened.current.primaryTab)
                assertEquals(drafts, sync.mainlineState("C", "mock-drafts"))
                reopened.back()
                assertEquals(3, reopened.current.week)
                assertEquals("continue", reopened.current.id)
                slots.forEach { (key, expected) -> assertEquals(expected, sync.mainlineState("C", key), key) }
            } }
        } finally { file.delete() }
    }
}

internal val noNavigationNetwork = object : com.coach.client.LedgerTransport {
    override suspend fun hello(epoch: String?): com.coach.protocol.Hello = error("No network in navigation migration")
    override suspend fun snapshot(course: String): com.coach.protocol.Snapshot = error("No network")
    override suspend fun events(course: String, after: Long): com.coach.protocol.EventsPage = error("No network")
    override suspend fun command(command: com.coach.protocol.WireCommand): com.coach.protocol.Receipt = error("No network")
    override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
}
