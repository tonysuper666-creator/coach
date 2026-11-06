package com.coach.android

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.CommandBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Host supplies an isolated server, single-use pairing code and the desktop's exported Markdown.
 * No tutor generation: setup advances only the copy's study cursor; UI opens the original mistakes.
 * Private assertions identify fields by ordinal, never include their content in instrumentation logs.
 */
@RunWith(AndroidJUnit4::class)
class NotesReviewSmoke {
    @Test fun desktopExportAgreesWithPairedReview() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("coachPort")!!.toInt()
        require(port !in setOf(18791, 8791, 8790))
        require(context.packageName.endsWith(".ton1294"))
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:$port"
        val week = args.getString("week", "1").toInt()
        val client = PairingClient(base)
        val credentials = try { client.pair(args.getString("pairingCode")!!, "TON-1294 emulator") } finally { client.close() }
        KeystoreCredentials(context, base).save(credentials)
        val driver = AndroidSqliteDriver(ClientDatabase.Schema, context, "coach-client.db")
        val transport = HttpLedgerTransport(base, credentials.deviceId, credentials.token)
        val sync = SyncEngine(ClientDatabase(driver), credentials.deviceId, transport, listOf(BuildConfig.COACH_COURSE),
            { UUID.randomUUID().toString() }, { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
        val folder = File(context.cacheDir, "notes-review").apply { mkdirs() }
        try {
            sync.syncOnce()
            val events = sync.localEvents(BuildConfig.COACH_COURSE)
            val view = CourseView(events, sync.projection(BuildConfig.COACH_COURSE))
            val wrong = weekMistakes(events, week)
            assertEquals(args.getString("expectedCount")!!.toInt(), wrong.size)
            val markdown = File(context.cacheDir, "desktop-notes.md").readText()
            val grades = events.filter { it.type in setOf("attempt_graded", "grade_reviewed") }.associateBy { it.payload.str("attemptId") }
            wrong.forEachIndexed { i, e ->
                val p = e.payload
                assertTrue("stem ${i + 1}", markdown.contains(p.str("stem")))
                val map = p["letterMap"]!!.jsonObject
                p["options"]!!.jsonArray.forEach { raw ->
                    val option = raw.jsonObject
                    assertTrue("option ${i + 1}", markdown.contains("${map[option.str("optionId")]!!.jsonPrimitive.content}. ${option.str("text")}"))
                }
                val feedback = grades.getValue(p.str("attemptId")).payload.str("feedback")
                assertTrue("feedback ${i + 1}", feedback.isNotBlank() && markdown.contains(feedback))
                e.threadId?.let { thread ->
                    val card = view.quizGroups(thread).flatMap { it.cards }.first { it.attemptId == p.str("attemptId") }
                    assertTrue("visible card feedback ${i + 1}", card.grade?.feedback == feedback)
                }
            }
            suspend fun command(type: String, payload: JsonObject) {
                val cmd = sync.enqueue(CommandBody(type, BuildConfig.COACH_COURSE, System.currentTimeMillis(), payload))
                sync.syncOnce()
                assertTrue("setup command $type", sync.receipt(cmd.commandId)?.status in setOf("OK", "DUPLICATE"))
            }
            command("set_study_scope", json("week" to week))
            repeat(view.kps.size + 1) {
                val state = sync.projection(BuildConfig.COACH_COURSE)
                val scope = state.studyScope!!
                val target = scope.target(state.mastery, weekPoints(events).map { it.id }.toSet())
                if (target != null) command("advance_study_scope", json("scopeId" to scope.id, "kpId" to target))
            }
            context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun find(text: String, timeout: Long = 15000): AccessibilityNodeInfo {
                fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString() == text) return node
                    for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                    return null
                }
                val until = android.os.SystemClock.elapsedRealtime() + timeout
                while (android.os.SystemClock.elapsedRealtime() < until) {
                    walk(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Review UI element missing")
            }
            fun capture(name: String) {
                instrumentation.waitForIdleSync(); Thread.sleep(300)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            var button = find("再练这一周错题")
            capture("today.png")
            while (!button.isClickable) button = button.parent ?: throw AssertionError("No click target")
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            find("第 $week 周错题")
            // Each item may be outside the LazyColumn viewport on the required AVD.
            wrong.forEach { e ->
                var seen = false
                repeat(12) {
                    if (!seen) {
                        seen = runCatching { find(e.payload.str("stem"), 600) }.isSuccess
                        if (!seen) {
                            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                                if (node == null) return false
                                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                                return false
                            }
                            scroll(instrumentation.uiAutomation.rootInActiveWindow)
                        }
                    }
                }
                assertTrue("visible review stem", seen)
            }
            capture("week-mistakes.png")
            android.util.Log.i("CoachNotes", "paired=true week=$week ledgerExportAgreement=${wrong.size}/${wrong.size} reviewVisible=${wrong.size} generationCalls=0")
        } finally { transport.close(); driver.close() }
    }
}
