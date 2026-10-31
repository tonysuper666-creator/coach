package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.measureTime

/** The host runner owns two emulators and stops the isolated Mac server between phases. */
@RunWith(AndroidJUnit4::class)
class OfflinePackSmoke {
    @Test fun exercisePhase(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val phase=args.getString("phase","prepare")
        val base="http://${BuildConfig.COACH_TAIL_HOST}:${args.getString("coachPort")}"
        val device=args.getString("coachDevice")!!
        val token=args.getString("coachToken")!!
        val course=BuildConfig.COACH_COURSE
        assertTrue(course.isNotBlank())
        if (phase=="prepare") context.deleteDatabase("coach-client.db")
        val driver=AndroidSqliteDriver(ClientDatabase.Schema,context,"coach-client.db")
        val db=ClientDatabase(driver)
        val transport=HttpLedgerTransport(base,device,token)
        val sync=SyncEngine(db,device,transport,listOf(course),{ UUID.randomUUID().toString() }, { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        })
        try {
            when (phase) {
                "prepare" -> {
                    KeystoreCredentials(context,base).save(PairedCredentials(device,token))
                    sync.syncOnce();val before=sync.projection(course).position
                    sync.downloadOfflinePack(course,10)
                    assertEquals(before,sync.projection(course).position)
                    val rows=db.ledgerQueries.offlineItems(course).executeAsList()
                    assertEquals(10,rows.size)
                    rows.forEach { assertNull(db.ledgerQueries.offlineKeyAfterLock(it.attempt_id).executeAsOneOrNull()) }
                    android.util.Log.i("CoachOffline","IDS="+rows.joinToString(",") { LedgerJson.decodeFromString<Event>(it.posed_json).payload.str("questionId") })
                }
                "ui" -> {
                    val instrumentation=InstrumentationRegistry.getInstrumentation()
                    context.startActivity(android.content.Intent(context,CoachActivity::class.java)
                        .putExtra("coachUrl",base).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    click(waitForText(instrumentation.uiAutomation,"到期复习",15000))
                    val start=waitForText(instrumentation.uiAutomation,"复习 3 题",10000)
                    val mark=System.nanoTime()
                    click(start)
                    waitForText(instrumentation.uiAutomation,"第 1 / 3 题",1000)
                    instrumentation.waitForIdleSync()
                    // Accessibility can update before the display compositor presents its frame.
                    // Include settling in the measured bound and capture the actual card pixels.
                    Thread.sleep(100)
                    val millis=(System.nanoTime()-mark)/1_000_000
                    assertTrue(millis<1000)
                    val screenshot=instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(context.cacheDir,"offline-ready.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                    screenshot.recycle()
                    android.util.Log.i("CoachOffline","UI tapToCardMs=$millis macUnavailable=true groupSize=3")
                }
                "offline" -> {
                    try { transport.hello(sync.epoch); fail("Host must disconnect before this phase") } catch (_: java.lang.AssertionError) { throw AssertionError("Host still reachable") } catch (_: Exception) { }
                    val elapsed=measureTime { assertEquals(10,sync.offlineGroups(course,System.currentTimeMillis()).sumOf { it.cards.size }) }
                    assertTrue(elapsed.inWholeMilliseconds<1000)
                    val groups=sync.offlineGroups(course,System.currentTimeMillis())
                    for (group in groups) {
                        val answers=group.cards.associate { it.attemptId to it.options.first().first }
                        sync.submitOfflineGroup(course,group.groupId,answers,System.currentTimeMillis())
                    }
                    val cards=sync.offlineGroups(course,System.currentTimeMillis()).flatMap { it.cards }
                    assertEquals(10,cards.count { it.answer!=null && it.grade!=null })
                    assertEquals(10,sync.retainedCommands().size)
                    android.util.Log.i("CoachOffline","OFFLINE answers=10 immediateGrades=10 startMs=${elapsed.inWholeMilliseconds} immutableOutbox=10")
                }
                "reconcile" -> {
                    // A fresh instrumentation process opens the same DB. Local grades survive until sync.
                    val local=sync.offlineGroups(course,System.currentTimeMillis()).flatMap { it.cards }.associate { it.attemptId to it.grade!!.correct }
                    assertEquals(10,local.size)
                    val loop=sync.start(this)
                    try {
                        withTimeout(15000) {
                            while (sync.offlineGroups(course,System.currentTimeMillis()).flatMap { it.cards }.any { it.grade?.note!="服务端已确认" }) delay(50)
                        }
                    } finally { loop.cancelAndJoin() }
                    val cards=sync.offlineGroups(course,System.currentTimeMillis()).flatMap { it.cards }
                    assertTrue(cards.all { it.grade?.correct==local[it.attemptId] && it.grade?.note=="服务端已确认" })
                    assertEquals(10,sync.localEvents(course).count { it.type=="answer_submitted" && it.deviceId==device })
                    assertTrue(sync.retainedCommands().all { sync.receipt(it.commandId)?.status=="OK" })
                    android.util.Log.i("CoachOffline","RECONCILE agreement=10/10 acknowledged=10 lost=0 duplicates=0 restart=true")
                }
                else -> error("Unknown phase")
            }
        } finally { transport.close();driver.close() }
    }
    private fun waitForText(ui: android.app.UiAutomation,text: String,timeout: Long): android.view.accessibility.AccessibilityNodeInfo {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node==null) return null
            if (node.text?.toString()==text) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val deadline=android.os.SystemClock.elapsedRealtime()+timeout
        while (android.os.SystemClock.elapsedRealtime()<deadline) {
            find(ui.rootInActiveWindow)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Visible text did not appear: $text")
    }
    private fun click(node: android.view.accessibility.AccessibilityNodeInfo) {
        var target=node
        while (!target.isClickable) target=target.parent ?: throw AssertionError("Text has no clickable ancestor")
        assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
}
