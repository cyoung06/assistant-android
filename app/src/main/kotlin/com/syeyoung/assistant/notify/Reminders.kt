package com.syeyoung.assistant.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.syeyoung.assistant.MainActivity
import com.syeyoung.assistant.R
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.plainText
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deadline reminders, scheduled on the device from the synced tasks (no push server): a day and an
 * hour before a timed deadline; 9:00 the day before and on the day for a date-only one.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val CHANNEL = "deadlines"
    private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    private val HORIZON: Duration = Duration.ofDays(8)

    fun schedule(context: Context, tasks: List<TaskResponse>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Cancel what was scheduled last time; the list is rebuilt from the current tasks.
        prefs.getStringSet("codes", emptySet())!!.forEach { code ->
            alarms.cancel(pending(context, code.toInt(), null))
        }
        val now = ZonedDateTime.now(ZONE)
        val codes = mutableSetOf<String>()
        for (t in tasks.filter { !it.closed && it.due != null }) {
            val moments = moments(t.due!!.date, t.due!!.time)
            moments.forEachIndexed { i, (at, text) ->
                if (at.isBefore(now) || at.isAfter(now.plus(HORIZON))) return@forEachIndexed
                val code = (t.id.hashCode() * 31 + i)
                val intent = Intent(context, ReminderReceiver::class.java)
                    .putExtra("task", t.id.toString()).putExtra("title", plainText(t.title)).putExtra("text", text).putExtra("code", code)
                // Inexact is fine for a reminder, and needs no exact-alarm permission.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pending(context, code, intent))
                codes += code.toString()
            }
        }
        prefs.edit().putStringSet("codes", codes).apply()
    }

    /** When to remind about a deadline, with the notification text. */
    fun moments(dueDate: String, dueTime: String?): List<Pair<ZonedDateTime, String>> {
        val date = LocalDate.parse(dueDate)
        val time = dueTime?.let { LocalTime.parse(it.take(5)) }
        return if (time != null) {
            val due = date.atTime(time).atZone(ZONE)
            listOf(due.minusDays(1) to "Due tomorrow at $time", due.minusHours(1) to "Due in an hour ($time)")
        } else {
            listOf(date.minusDays(1).atTime(9, 0).atZone(ZONE) to "Due tomorrow", date.atTime(9, 0).atZone(ZONE) to "Due today")
        }
    }

    private fun pending(context: Context, code: Int, intent: Intent?) = PendingIntent.getBroadcast(
        context, code, intent ?: Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun notify(context: Context, task: String, title: String, text: String, code: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Deadlines", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TASK, task).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            code,
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.notify(context, intent.getStringExtra("task") ?: return, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty(), intent.getIntExtra("code", 0))
    }
}
