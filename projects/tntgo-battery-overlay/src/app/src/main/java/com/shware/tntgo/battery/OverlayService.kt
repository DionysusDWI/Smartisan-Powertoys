package com.shware.tntgo.battery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 核心前台服务：
 *  1. 监听显示器变化，在目标外接屏上挂 Presentation 电量卡片
 *  2. 监听手机电量（广播）
 *  3. 周期读取 TNT GO 电量（USB 串口）
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "TntgoOverlay"
        private const val CHANNEL_ID = "tntgo_battery"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var displayManager: DisplayManager
    private val presentations = mutableMapOf<Int, BatteryPresentation>()
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var serial: TntgoSerial? = null

    private var phoneLevel = -1
    private var phoneCharging = false

    @Volatile
    private var tntLevel: Int? = null

    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attachPresentations()

        override fun onDisplayRemoved(displayId: Int) {
            presentations.remove(displayId)?.dismiss()
        }

        override fun onDisplayChanged(displayId: Int) = attachPresentations()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshPhoneBattery()
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            refreshPhoneBattery()
            pollTntgoBattery()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        serial = TntgoSerial(this)

        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!started) {
            started = true
            attachPresentations()
            handler.post(poller)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { unregisterReceiver(batteryReceiver) }
        presentations.values.forEach { runCatching { it.dismiss() } }
        presentations.clear()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** 为所有可用的目标外接屏挂上 / 更新 Presentation。 */
    private fun attachPresentations() {
        val displays = displayManager.displays

        for (display in displays) {
            if (!isTargetDisplay(display)) continue
            if (presentations.containsKey(display.displayId)) continue
            try {
                val presentation = BatteryPresentation(this, display)
                presentation.show()
                presentations[display.displayId] = presentation
                Log.i(TAG, "Presentation attached on display ${display.displayId} (${display.name})")
            } catch (e: Exception) {
                Log.w(TAG, "Presentation failed on display ${display.displayId}: ${e.message}")
            }
        }

        // 清理已移除的屏
        presentations.keys.toList().forEach { id ->
            if (displays.none { it.displayId == id }) {
                presentations.remove(id)?.dismiss()
            }
        }

        refreshPhoneBattery()
        updatePresentations()
    }

    /**
     * 目标外接屏判定：
     *  - 排除默认屏
     *  - 必须有 FLAG_PRESENTATION（外接投影屏标志）
     *  - 排除 FLAG_PRIVATE 的内置副屏（如小米背屏）
     *
     * 注意：原代码写的 Display.FLAG_OWN_CONTENT_ONLY 在 AOSP 里并不存在
     * （android.view.Display 只有 SUPPORTS_PROTECTED_BUFFERS / SECURE / PRIVATE /
     * PRESENTATION / ROUND 等公开常量；OWN_CONTENT_ONLY 是
     * DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY，属于"创建虚拟屏"的参数，
     * 且其值 1<<3 与 Display.FLAG_PRESENTATION 撞位，不能用来判 display.flags）。
     * 这里用 FLAG_PRIVATE 表达"仅本用户可见的内置屏"这一意图。
     */
    private fun isTargetDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        val hasPresentation = display.flags and Display.FLAG_PRESENTATION != 0
        val isPrivatePanel = display.flags and Display.FLAG_PRIVATE != 0
        return hasPresentation && !isPrivatePanel
    }

    private fun refreshPhoneBattery() {
        val bm = getSystemService(BatteryManager::class.java)
        phoneLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        phoneCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        updatePresentations()
    }

    private fun pollTntgoBattery() {
        val s = serial ?: return
        ioExecutor.execute {
            val level = s.readLevel()
            if (level != tntLevel) {
                tntLevel = level
                handler.post { updatePresentations() }
            }
        }
    }

    private fun updatePresentations() {
        presentations.values.forEach { it.update(phoneLevel, phoneCharging, tntLevel) }
    }
}