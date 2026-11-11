package com.coach.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.UUID

/** Host runner (tools/dev/check-reminder.py) owns the synthetic private copy, isolated server and emulator lifecycle. */
@RunWith(AndroidJUnit4::class)
class ReminderSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val base get() = "http://${BuildConfig.COACH_TAIL_HOST}:${args.getString("coachPort")}"
    private val prefs get() = context.getSharedPreferences("review-reminder", 0)
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun phase(): Unit = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val reminder = ReviewReminder(context)
        when (args.getString("phase")) {
            "prepare" -> {
                val client = PairingClient(base)
                try { KeystoreCredentials(context, base).save(client.pair(args.getString("pairingCode")!!, "TON-1293 reminder emulator")) }
                finally { client.close() }
                val count = countDue(sync = true)
                assertTrue("Backup must contain due reviews for notification acceptance", count > 0)
                prefs.edit().putInt("expectedCount", count).commit()
                launch("TODAY")
                waitText("今天")
                Thread.sleep(500)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()?.startsWith("每日提醒") == true })
                launch()
                click(waitNode { it.text?.toString() == "每日提醒 · 已关闭" }); waitText("复习提醒")
                val toggle = waitNode { it.isCheckable }
                dumpNode(instrumentation.uiAutomation.rootInActiveWindow, "root")
                dumpNode(toggle, "selected")
                assertTrue("Switch must be visible and operable", toggle.isVisibleToUser && toggle.isEnabled && toggle.isClickable)
                assertTrue("Switch must expose its label", toggle.text?.contains("每日到期提醒") == true ||
                    toggle.contentDescription?.contains("每日到期提醒") == true)
                assertFalse(toggle.isChecked)
                click(toggle)
                // The system label varies by locale/Android version (including its apostrophe).
                val deny = waitNode { isPermissionDeny(it) }
                dumpNode(deny, "permission-deny")
                click(deny)
                waitText("通知权限未开启或提醒通知已关闭，暂时无法提醒。请到系统设置中允许通知。")
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
                assertTrue(reminder.permissionAsked)
                assertTrue(reminder.enabled)
                waitNode { it.isCheckable && it.isChecked }
                assertTrue("Enabled choice must persist", ReviewReminder(context).enabled)
                screenshot("permission-denied.png")
                click(waitNode { it.isCheckable })
                waitNode { it.isCheckable && !it.isChecked }
                assertFalse("Switch click must disable the reminder", ReviewReminder(context).enabled)
                click(waitNode { it.isCheckable })
                waitNode { it.isCheckable && it.isChecked }
                assertTrue("Switch click must enable the reminder", ReviewReminder(context).enabled)
                Thread.sleep(500)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { isPermissionDeny(it) })
                waitText("复习提醒")
                click(waitText("完成"))
                reminder.update(enabled = false)
                android.util.Log.i("CoachReminderTest", "permissionDenied=explained repeatedPrompt=false expected=$count")
            }
            "schedule" -> {
                reminder.configure(base)
                assertTrue(reminder.notificationsAllowed())
                val time = ZonedDateTime.now().plusMinutes(2)
                reminder.update(enabled = true, hour = time.hour, minute = time.minute)
                val restored = ReviewReminder(context)
                assertTrue(restored.enabled); assertEquals(time.hour, restored.hour); assertEquals(time.minute, restored.minute)
                launch(); click(waitText("每日提醒 %02d:%02d".format(time.hour, time.minute)))
                waitText("提醒时间 %02d:%02d".format(time.hour, time.minute))
                click(waitText("提醒时间 %02d:%02d".format(time.hour, time.minute)))
                // Actual native time picker opens; leave the already selected two-minute time unchanged.
                click(waitNode { it.viewIdResourceName == "android:id/button1" }); click(waitText("完成"))
                android.util.Log.i("CoachReminderTest", "scheduledAt=${prefs.getLong("nextAt", -1)} expected=${prefs.getInt("expectedCount", -1)}")
            }
            "tap" -> {
                val due = dueReviews(sync = false)
                val count = due.size
                val notification = manager.activeNotifications.single { it.id == ReviewReminder.NOTIFICATION }
                assertEquals("今天有 $count 个知识点要复习", notification.notification.extras.getString("android.title"))
                instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
                waitText("今天有 $count 个知识点要复习")
                screenshot("notification.png")
                click(waitText("今天有 $count 个知识点要复习"))
                // Review page, rather than Today's landing page: the Review tab is the selected destination
                // and the page header carries the reminder entry that only Review shows (checked in prepare).
                waitNode { node -> node.contentDescription?.toString() == "导航：复习" && generateSequence(node) { it.parent }.any { it.isSelected } }
                waitText("复习")
                waitNode { it.text?.toString()?.startsWith("每日提醒") == true }
                // The page's own due list must show, to the user, the same due set the notification counted:
                // its count header and one row per due item (title, due label, Start), scrolled into view if needed.
                reveal { it.text?.toString() == "$count 节到期" }
                val rows = due.map { item ->
                    val label = (if (item.revisit) "再看一次 · " else "") + if (item.overdueDays == 0) "今天到期" else "逾期 ${item.overdueDays} 天"
                    val title = displayTitle(item.kp.title)
                    reveal { row -> children(row).any { it.text?.toString() == label && shown(it) } &&
                        children(row).any { child -> child.text?.toString()?.let { it.isNotBlank() && title.contains(it) } == true && shown(child) } &&
                        find(row) { it.text?.toString() == "开始" && shown(it) } != null }
                }
                assertEquals(count, rows.size)
                screenshot("review.png")
                assertTrue(manager.activeNotifications.none { it.id == ReviewReminder.NOTIFICATION })
                android.util.Log.i("CoachReminderTest", "notificationCount=$count pageCount=$count visibleDueRows=${rows.size} tap=REVIEW")
            }
            "timezone" -> {
                val expectedZone = args.getString("expectedZone")!!
                assertEquals(expectedZone, ZoneId.systemDefault().id)
                val expected = nextReminderAt(System.currentTimeMillis(), reminder.hour, reminder.minute, ZoneId.of(expectedZone))
                assertEquals("Timezone broadcast must reschedule wall-clock reminder", expected, prefs.getLong("nextAt", -1))
                android.util.Log.i("CoachReminderTest", "timezone=$expectedZone rescheduledAt=$expected")
            }
            "restored" -> {
                assertTrue(reminder.enabled)
                assertTrue(prefs.getLong("nextAt", -1) > System.currentTimeMillis())
                android.util.Log.i("CoachReminderTest", "bootRestored=true scheduledAt=${prefs.getLong("nextAt", -1)}")
                reminder.update(enabled = false)
                assertTrue(manager.activeNotifications.none { it.id == ReviewReminder.NOTIFICATION })
                android.util.Log.i("CoachReminderTest", "disabled=true notificationCancelled=true")
            }
            "zero" -> {
                // Host has stopped the isolated server. Empty the disposable local replica only.
                context.deleteDatabase("coach-client.db")
                assertEquals(0, countDue(sync = false))
                reminder.configure(base); reminder.update(enabled = true)
                reminder.deliver(prefs.getLong("nextAt", -1))
                assertTrue(manager.activeNotifications.none { it.id == ReviewReminder.NOTIFICATION })
                // A stale alarm must not deliver after disabling.
                val at = prefs.getLong("nextAt", -1)
                reminder.update(enabled = false); reminder.deliver(at)
                assertTrue(manager.activeNotifications.isEmpty())
                android.util.Log.i("CoachReminderTest", "due=0 silent=true staleAlarmAfterDisable=silent")
            }
            else -> error("Unknown reminder smoke phase")
        }
    }

    private suspend fun countDue(sync: Boolean): Int = dueReviews(sync).size

    private suspend fun dueReviews(sync: Boolean): List<DueReview> {
        val credentials = KeystoreCredentials(context, base).load()!!
        val driver = AndroidSqliteDriver(ClientDatabase.Schema, context, "coach-client.db")
        val transport = HttpLedgerTransport(base, credentials.deviceId, credentials.token)
        try {
            val engine = SyncEngine(ClientDatabase(driver), credentials.deviceId, transport, listOf(BuildConfig.COACH_COURSE),
                { UUID.randomUUID().toString() }, { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
            if (sync) engine.syncOnce()
            return CourseView(engine.localEvents(BuildConfig.COACH_COURSE), engine.projection(BuildConfig.COACH_COURSE))
                .dueReviews(System.currentTimeMillis())
        } finally { transport.close(); driver.close() }
    }

    private fun launch(tab: String = "REVIEW") {
        context.startActivity(Intent(context, CoachActivity::class.java).putExtra("coachUrl", base)
            .putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
    private fun screenshot(name: String) {
        Thread.sleep(300)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.cacheDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun dumpNode(node: AccessibilityNodeInfo?, path: String) {
        if (node == null) return
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        android.util.Log.i("CoachReminderTest", "$path class=${node.className} id=${node.viewIdResourceName} text=${node.text} description=${node.contentDescription} checkable=${node.isCheckable} checked=${node.isChecked} clickable=${node.isClickable} selected=${node.isSelected} scrollable=${node.isScrollable} visible=${node.isVisibleToUser} bounds=${bounds.toShortString()}")
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), "$path/$i")
    }
    private fun isPermissionDeny(node: AccessibilityNodeInfo) =
        node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true &&
            node.packageName?.toString()?.endsWith("permissioncontroller") == true
    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            Thread.sleep(50)
        }
        dumpNode(instrumentation.uiAutomation.rootInActiveWindow, "timeout")
        android.util.Log.i("CoachReminderTest", "timeout enabled=${ReviewReminder(context).enabled} permissionAsked=${ReviewReminder(context).permissionAsked}")
        throw AssertionError("Accessibility target did not appear")
    }
    private fun waitText(text: String) = waitNode { it.text?.toString() == text }
    private fun children(node: AccessibilityNodeInfo) = (0 until node.childCount).mapNotNull { node.getChild(it) }
    private fun shown(node: AccessibilityNodeInfo) =
        node.isVisibleToUser && android.graphics.Rect().also { node.getBoundsInScreen(it) }.let { it.width() > 0 && it.height() > 0 }
    /** Waits for an on-screen match, scrolling the page's scrollable containers forward while it is not in view. */
    private fun reveal(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root) { predicate(it) && shown(it) }?.let { return it }
            val scrolled = generateSequence(listOf(root)) { level -> level.flatMap { node -> node?.let { children(it) }.orEmpty() }.takeIf { it.isNotEmpty() } }
                .flatten().filterNotNull().filter { it.isScrollable && shown(it) }
                .any { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            Thread.sleep(if (scrolled) 300 else 50)
        }
        dumpNode(instrumentation.uiAutomation.rootInActiveWindow, "timeout")
        throw AssertionError("Due list content did not become visible")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("No clickable ancestor")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
