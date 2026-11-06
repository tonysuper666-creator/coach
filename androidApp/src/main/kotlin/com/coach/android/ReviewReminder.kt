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
import com.coach.client.db.ClientDatabase
import com.coach.domain.Event
import com.coach.domain.LedgerJson
import com.coach.domain.Projection
import com.coach.domain.project
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

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

    fun dismissNotification() { manager.cancel(NOTIFICATION) }

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
        val driver = AndroidSqliteDriver(ClientDatabase.Schema, context, "coach-client.db")
        try {
            val db = ClientDatabase(driver)
            // Read one committed replica snapshot. Never sync, reset epochs or write projection caches
            // from the receiver: the foreground SyncEngine is the sole owner of synchronization.
            val count = db.transactionWithResult {
                val q = db.ledgerQueries
                val events = q.allEvents(BuildConfig.COACH_COURSE).executeAsList().map { LedgerJson.decodeFromString<Event>(it) }
                val local = project(events)
                val snapshot = if (local.requiresServerSnapshot) q.getMeta("serverProjection:${BuildConfig.COACH_COURSE}")
                    .executeAsOneOrNull()?.let { LedgerJson.decodeFromString<Projection>(it) } else null
                CourseView(events, snapshot ?: local).dueReviews(System.currentTimeMillis()).size
            }
            synchronized(LOCK) {
                if (!enabled || generation != revision || !notificationsAllowed()) return
                if (count == 0) { manager.cancel(NOTIFICATION); Log.i("CoachReminder", "due=0 skipped"); return }
                val open = PendingIntent.getActivity(context, NOTIFICATION,
                    Intent(context, CoachActivity::class.java).putExtra("tab", "REVIEW").putExtra("coachUrl", url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_review_reminder).setContentTitle("今天有 $count 个知识点要复习")
                    .setContentText("点开开始复习").setContentIntent(open).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_REMINDER).build())
                Log.i("CoachReminder", "due=$count notified")
            }
        } finally { driver.close() }
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
