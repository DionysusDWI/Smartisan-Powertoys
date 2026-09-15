package com.shware.mode.mod.perfmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * ★★★★ **快捷开关**（任务 AK8）—— 下拉通知栏磁贴。
 *
 * ## 为什么需要它
 *
 * 在这之前，开性能模式要「打开应用 → 点一下」——**两步且要离开当前画面**。
 * 性能模式的使用场景（游戏、演示、卡顿时临时开）要求**一步可达**。
 *
 * ## 三个入口（覆盖不同场景）
 *
 * | 入口 | 场景 | 需不需要人工配置 |
 * |---|---|---|
 * | ★ **QS 磁贴**（本文件） | 下拉通知栏点一下 | ⚠️ **需要用户把磁贴拖进快捷面板**（Android 强制） |
 * | **通知栏按钮** | 磁贴没配好时的兜底 | ✅ 免配置（只要 mod 服务在跑） |
 * | **桌面图标** | 常规入口 | ✅ 免配置 |
 * | **广播**（[Toggler]） | 给 Tasker / 自动化用 | ✅ 免配置 |
 *
 * ## ⚠️ Android 的硬性限制
 *
 * **磁贴不能由应用自己添加** —— 用户必须手动在「快捷设置」编辑界面里把它拖出来。
 * （`deploy.sh` 会用 `settings put secure sysui_qs_tiles` **尝试**代劳，但**不保证**各家 ROM 都认。）
 */
class PerfModeTileService : TileService() {

    companion object {
        private const val TAG = "ModeMod/PerfMode"
    }

    /** 磁贴被拉进面板 / 面板展开时调用 —— 刷新显示 */
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        Toggler.toggle(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = PerfModeEngine.state == PerfModeEngine.State.ON
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        if (on) {
            val left = PerfModeEngine.remainingMs() / 1000
            // ★ subtitle 是 API 29+ 的 API —— 正好本机是 29
            tile.subtitle = "%s · %d:%02d".format(
                PerfModeEngine.tier.label, left / 60, left % 60
            )
        } else {
            tile.subtitle = "已关闭"
        }
        tile.updateTile()
    }
}

/**
 * ★★ **开关的唯一入口** —— 磁贴、通知按钮、广播、设置界面**全走这里**，
 * 免得四份各自为政的开关逻辑（本项目在别处吃过"两条路径行为不一致"的亏）。
 */
object Toggler {

    private const val TAG = "ModeMod/PerfMode"

    const val ACTION_TOGGLE = "com.shware.mode.mod.perfmode.TOGGLE"

    /** 翻转开关；返回翻转后的状态 */
    fun toggle(ctx: Context): Boolean = set(ctx, PerfModeEngine.state != PerfModeEngine.State.ON)

    /** 显式设定状态 */
    fun set(ctx: Context, on: Boolean): Boolean {
        if (on) {
            PerfModeEngine.start(
                Prefs.minutes(ctx), Prefs.tempLimit(ctx).toFloat(),
                PerfLock.Tier.of(Prefs.tier(ctx))
            )
        } else {
            PerfModeEngine.stop("手动关闭")
        }
        // ★ 让常驻服务活着（否则进程一没，续期就断了）
        runCatching {
            ctx.startForegroundService(Intent(ctx, PerfModeService::class.java))
        }.onFailure { Log.w(TAG, "拉起服务失败：${it.message}") }

        // 刷新磁贴
        runCatching {
            TileService.requestListeningState(
                ctx, android.content.ComponentName(ctx, PerfModeTileService::class.java)
            )
        }
        // 刷新通知
        runCatching { PerfModeService.refreshNotification(ctx) }
        return on
    }
}

/** 给 Tasker / 自动化用的广播入口 */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Toggler.ACTION_TOGGLE) Toggler.toggle(context)
    }
}
