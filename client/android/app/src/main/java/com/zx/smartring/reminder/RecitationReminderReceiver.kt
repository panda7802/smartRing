package com.zx.smartring.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zx.smartring.MainActivity
import com.zx.smartring.R
import com.zx.smartring.settings.AppSettingsStore
import com.zx.smartring.settings.ReminderTime
import java.util.Locale

class RecitationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RecitationReminderScheduler.ACTION_REMIND) return
        RecitationReminderScheduler.showNotification(context)
        RecitationReminderScheduler.scheduleNext(context)
    }
}

class RecitationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            RecitationReminderScheduler.sync(context)
        }
    }
}

object RecitationReminderScheduler {
    const val ACTION_REMIND = "com.zx.smartring.action.RECITATION_REMINDER"
    private const val CHANNEL_ID = "daily_recitation_reminder"
    private const val NOTIFICATION_ID = 530
    private const val ALARM_REQUEST_CODE = 531

    fun sync(context: Context) {
        val settings = AppSettingsStore.get(context)
        val window = settings.recitationWindow
        if (!settings.dailyReminderEnabled || window == null) {
            cancel(context)
            return
        }
        schedule(context, window.startMinuteOfDay)
    }

    fun scheduleNext(context: Context) {
        sync(context)
    }

    fun showNotification(context: Context) {
        val settings = AppSettingsStore.get(context)
        val window = settings.recitationWindow ?: return
        if (!settings.dailyReminderEnabled) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createNotificationChannel(context)
        val launchIntent = Intent(context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(
            R.string.recitation_notification_body,
            formatMinute(window.startMinuteOfDay),
            formatMinute(window.endMinuteOfDay)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_ring_monochrome)
            .setContentTitle(context.getString(R.string.recitation_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun schedule(context: Context, minuteOfDay: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            ReminderTime.nextOccurrenceMillis(minuteOfDay),
            alarmPendingIntent(context)
        )
    }

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RecitationReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recitation_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.recitation_notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun formatMinute(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
}
