package com.shware.flashpill.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.widget.Toast
import com.shware.flashpill.data.Capsule
import com.shware.flashpill.reminder.ReminderReceiver

/**
 * 胶囊的「去向」操作：分享 / 复制 / 转日历 / 提醒。
 */
object CapsuleActions {

    fun share(context: Context, capsule: Capsule) {
        val text = capsule.text.ifBlank { "（空胶囊）" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "分享胶囊"))
        }
    }

    fun copy(context: Context, capsule: Capsule) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("capsule", capsule.text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    /** 转系统日历（用 ACTION_INSERT，无需 WRITE_CALENDAR 权限） */
    fun toCalendar(context: Context, capsule: Capsule) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, capsule.text.take(60))
            putExtra(CalendarContract.Events.DESCRIPTION, capsule.text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "没有可用的日历应用", Toast.LENGTH_SHORT).show() }
    }

    /** 设置提醒（一次性 AlarmManager） */
    fun remind(context: Context, capsule: Capsule, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TEXT, capsule.text)
            putExtra(ReminderReceiver.EXTRA_ID, capsule.id)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            capsule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
        Toast.makeText(context, "已设置提醒", Toast.LENGTH_SHORT).show()
    }
}