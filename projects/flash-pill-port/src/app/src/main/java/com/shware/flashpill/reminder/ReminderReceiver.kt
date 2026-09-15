package com.shware.flashpill.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 胶囊提醒广播接收器：AlarmManager 触发后弹通知。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "flashpill_reminder"
        private const val NOTIF_ID_BASE = 3000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "闪念胶囊提醒",
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("闪念胶囊提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID_BASE + (id.hashCode() and 0xFFF), notification)
    }
}