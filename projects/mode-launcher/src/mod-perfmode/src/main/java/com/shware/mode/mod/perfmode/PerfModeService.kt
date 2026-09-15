package com.shware.mode.mod.perfmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * ★★ MOD：**性能模式**（任务 AK）—— 把 QTI perf HAL 的 `perfLockAcquire` 封装成 Powertoys 的一个功能。
 *
 * ## 这个服务只干一件事：**让进程活着**
 *
 * 锁的续期循环跑在 [PerfModeEngine] 的常驻线程上（同进程静态对象）。
 * 进程一旦被杀，续期就断了 —— 但**锁本身有 45 秒的寿命**（见 [PerfModeEngine] 的"崩溃自愈"设计）
 * ⇒ **不会留下永久锁**。
 *
 * ## 通知栏还兼一个作用：**磁贴没配好时的兜底开关**
 *
 * ★ Android **不允许应用自己往快捷面板加磁贴**（用户必须手动拖）。
 * ⇒ 通知上加两个动作按钮，**免配置也能一步开关**。
 *
 * ## 契约
 *
 * `MOD_ID = perf.mode` ｜ `MOD_TARGET = phone` ｜ `MOD_TOUCH = none`（**本 mod 不画卡片**）
 * ｜ `MOD_SETTINGS = .SettingsActivity`
 */
class PerfModeService : Service() {

    companion object {
        private const val TAG = "ModeMod/PerfMode"
        private const val CHANNEL_ID = "mode_mod_perfmode"
        // ★ 2004 —— 本 ID 保持不变。★ 通知槽位作用域是【包】不是进程；
        //   原先 mod-livecaption 也用 2004（两者同时跑会互相顶掉通知），
        //   2026-09-15 已把【它】改成 2006。全包分配表见 :app 的 ModContract「二·补」。
        private const val NOTIF_ID = 2004

        /** 旧名保留（曾经用过），新代码用 [Toggler.ACTION_TOGGLE] */
        const val ACTION_TOGGLE = "com.shware.mode.mod.perfmode.TOGGLE"

        /** ★ 由 [Toggler] 调用：状态变了，把通知刷新一下 */
        fun refreshNotification(ctx: Context) {
            runCatching {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(ctx))
            }
        }

        private fun buildNotification(ctx: Context): android.app.Notification {
            val on = PerfModeEngine.state == PerfModeEngine.State.ON

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, SettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // ★ 一个按钮直接翻转（点一下就开关，不用进应用）
            val toggleLabel = if (on) "关闭" else "开启"
            val togglePi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, ToggleReceiver::class.java).setAction(Toggler.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sub = if (on) {
                val left = PerfModeEngine.remainingMs() / 1000
                "已开启[%s] · 剩余 %d:%02d".format(PerfModeEngine.tier.label, left / 60, left % 60)
            } else {
                "待命 · 点「开启」或从快捷面板开启"
            }

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("性能模式")
                .setContentText(sub)
                .setContentIntent(open)
                .addAction(0, toggleLabel, togglePi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建（PerfLock 可用=${PerfLock.available()}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // ★ 走统一入口，免得"服务里的开关"和"磁贴里的开关"行为不一致
        if (intent?.action == Toggler.ACTION_TOGGLE) Toggler.toggle(this)
        // ★ 服务被系统重启时不自动重开（START_NOT_STICKY）—— 否则用户关了它还会自己回来
        return START_NOT_STICKY
    }

    /**
     * ★★ **服务销毁 = 立刻释放**。
     *
     * 就算这一步没跑到（进程被直接杀），锁也会在 [PerfModeEngine.LOCK_HOLD_MS] 后自然消失
     * —— **两层保险**。
     */
    override fun onDestroy() {
        Log.i(TAG, "服务销毁 ⇒ 释放性能锁")
        PerfModeEngine.releaseNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MODE 组件", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(NOTIF_ID, buildNotification(this))
    }
}
