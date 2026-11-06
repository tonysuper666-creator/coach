package com.coach.android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.CourseView
import com.coach.client.HttpLedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID

/** Only local preferences, Android alarms and the existing ledger replica; no push service. */
internal class ReviewReminder(private val context: Context) {
    private val prefs = context.getSharedPreferences("review-reminder", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val hour get() = prefs.getInt("hour", 19)
    val minute get() = prefs.getInt("minute", 0)
    val permissionAsked get() = prefs.getBoolean("permissionAsked", false)
    val serverUrl get() = prefs.getString("serverUrl", null)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val generation get() = prefs.getLong("generation", 0)

    fun configure(url: String) {
        val changed = serverUrl != url
        if (changed) check(prefs.edit().putString("serverUrl", url).commit())
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "到期复习提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val scheduled = PendingIntent.getBroadcast(context, NOTIFICATION,
            Intent(context, ReviewReminderReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        // Opening the app while an inexact alarm is awaiting delivery must not skip that day's reminder.
        if (changed || scheduled == null) reschedule()
    }

    fun markPermissionAsked() { check(prefs.edit().putBoolean("permissionAsked", true).commit()) }

    fun update(enabled: Boolean = this.enabled, hour: Int = this.hour, minute: Int = this.minute) = synchronized(LOCK) {
        require(hour in 0..23 && minute in 0..59)
        check(prefs.edit().putBoolean("enabled", enabled).putInt("hour", hour).putInt("minute", minute)
            .putLong("generation", generation + 1).commit())
        manager.cancel(NOTIFICATION)
        reschedule()
    }

    fun notificationsAllowed(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun alarm(at: Long = 0) = PendingIntent.getBroadcast(context, NOTIFICATION,
        Intent(context, ReviewReminderReceiver::class.java).setAction(ACTION).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun reschedule() = synchronized(LOCK) {
        if (!enabled || serverUrl == null || BuildConfig.COACH_COURSE.isBlank()) {
            alarms.cancel(alarm())
            prefs.edit().remove("nextAt").apply()
        } else {
            val at = nextReminderAt(System.currentTimeMillis(), hour, minute, ZoneId.systemDefault())
            check(prefs.edit().putLong("nextAt", at).commit())
            // User-selected daily reminder: inexact alarms avoid an additional special-access permission.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(at))
            Log.i("CoachReminder", "scheduledAt=$at")
        }
    }

    suspend fun deliver(expectedAt: Long) {
        val revision = synchronized(LOCK) {
            if (!enabled || expectedAt != prefs.getLong("nextAt", -1)) return
            reschedule()
            generation
        }
        val url = serverUrl ?: return
        // Validate the saved endpoint using the same isolated-package guard as the foreground app.
        coachServerUrl(url, context.packageName)
        val credentials = KeystoreCredentials(context, url).load() ?: return
        val driver = AndroidSqliteDriver(ClientDatabase.Schema, context, "coach-client.db")
        val transport = HttpLedgerTransport(url, credentials.deviceId, credentials.token)
        try {
            val db = ClientDatabase(driver)
            val sync = SyncEngine(db, credentials.deviceId, transport, listOf(BuildConfig.COACH_COURSE),
                { UUID.randomUUID().toString() }, { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
            // Bound receiver work. Offline reminders use the same replica the review page opens.
            try { withTimeoutOrNull(5_000) { sync.syncOnce() } } catch (_: Exception) { /* Local replica remains available. */ }
            val count = db.transactionWithResult {
                CourseView(sync.localEvents(BuildConfig.COACH_COURSE), sync.projection(BuildConfig.COACH_COURSE))
                    .dueReviews(System.currentTimeMillis()).size
            }
            synchronized(LOCK) {
                if (!enabled || generation != revision || !notificationsAllowed()) return
                if (count == 0) { manager.cancel(NOTIFICATION); Log.i("CoachReminder", "due=0 skipped"); return }
                val open = PendingIntent.getActivity(context, NOTIFICATION,
                    Intent(context, CoachActivity::class.java).putExtra("tab", "REVIEW").putExtra("coachUrl", url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_review_reminder).setContentTitle("今天有 $count 道到期")
                    .setContentText("点开开始复习").setContentIntent(open).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_REMINDER).build())
                Log.i("CoachReminder", "due=$count notified")
            }
        } finally { transport.close(); driver.close() }
    }

    companion object {
        const val ACTION = "com.coach.android.REVIEW_REMINDER"
        const val CHANNEL = "due-reviews"
        const val NOTIFICATION = 1293
        private val LOCK = Any()
    }
}

class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = ReviewReminder(context.applicationContext)
        if (intent.action != ReviewReminder.ACTION) { reminder.reschedule(); return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { reminder.deliver(intent.getLongExtra("at", -1)) }
            catch (e: Exception) { Log.w("CoachReminder", "Reminder unavailable: ${e.javaClass.simpleName}") }
            finally { pending.finish() }
        }
    }
}
