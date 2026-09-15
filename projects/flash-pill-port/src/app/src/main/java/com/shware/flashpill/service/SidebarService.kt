package com.shware.flashpill.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shware.flashpill.MainActivity
import com.shware.flashpill.config.ApiConfig
import com.shware.flashpill.data.Capsule
import com.shware.flashpill.data.CapsuleRepository
import com.shware.flashpill.transcribe.SiliconFlowTranscriber
import com.shware.flashpill.transcribe.WavRecorder
import com.shware.flashpill.ui.CaptureBubbleView
import com.shware.flashpill.ui.CapsuleListActivity
import com.shware.flashpill.ui.SidebarHandleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 侧边栏服务（M2）：
 *  - 常驻悬浮把手：按住录音（松手保存），短按打开列表，拖动换位置
 *  - 录音气泡：实时波形 + 计时
 *  - 捕获落库 + 异步转写
 */
class SidebarService : Service() {

    companion object {
        private const val TAG = "SidebarService"
        private const val CHANNEL_ID = "flashpill_sidebar"
        private const val NOTIF_ID = 2001
        private const val PREFS = "sidebar_prefs"
        private const val KEY_HANDLE_Y = "handle_y"

        const val ACTION_START = "com.shware.flashpill.action.START_SIDEBAR"
        const val ACTION_STOP = "com.shware.flashpill.action.STOP_SIDEBAR"

        fun start(context: Context) {
            val intent = Intent(context, SidebarService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SidebarService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var windowManager: WindowManager
    private var handleView: SidebarHandleView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var bubbleView: CaptureBubbleView? = null

    private val recorder = WavRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: CapsuleRepository? = null

    private var capturing = false
    private var currentAudio: File? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = CapsuleRepository.get(this)
        startForegroundCompat()
        addHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelCapture()
        removeHandle()
        removeBubble()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 悬浮把手 ----------------

    private fun addHandle() {
        if (handleView != null) return

        val view = SidebarHandleView(this).apply {
            onPressStart = { beginCapture() }        // 按下立即开录（短按会丢弃）
            onLongPressEnd = { endCapture(save = true) }  // 按住 ≥250ms 松手 → 保存
            onTap = {                                 // 短按 → 丢弃并打开列表
                cancelCapture()
                openCapsuleList()
            }
            onDragStart = { cancelCapture() }         // 拖动 → 取消录音
            onDrag = { rawY ->
                handleParams?.let { p ->
                    p.y = (rawY - dp(60)).toInt()
                    runCatching { windowManager.updateViewLayout(this, p) }
                }
            }
            onDragEnd = { saveHandleY() }
            onCancel = { cancelCapture() }
        }

        val params = WindowManager.LayoutParams(
            dp(14),
            dp(120),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(4)
            y = getSavedHandleY()
        }

        try {
            windowManager.addView(view, params)
            handleView = view
            handleParams = params
        } catch (e: Exception) {
            Log.e(TAG, "add handle failed", e)
        }
    }

    private fun removeHandle() {
        handleView?.let { runCatching { windowManager.removeView(it) } }
        handleView = null
        handleParams = null
    }

    private fun saveHandleY() {
        val y = handleParams?.y ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_HANDLE_Y, y).apply()
    }

    private fun getSavedHandleY(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_HANDLE_Y, dp(400))

    private fun openCapsuleList() {
        val intent = Intent(this, CapsuleListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "open list failed: ${it.message}") }
    }

    // ---------------- 录音 ----------------

    private fun beginCapture() {
        if (capturing) return
        try {
            val dir = File(getExternalFilesDir(null), "audio").apply { mkdirs() }
            val file = File(dir, "capsule_${System.currentTimeMillis()}.wav")
            currentAudio = file

            recorder.onAmplitude = { amp ->
                bubbleView?.post { bubbleView?.pushAmplitude(amp) }
            }
            recorder.start(file)
            capturing = true
            handleView?.setRecording(true)
            showBubble()
        } catch (e: Exception) {
            Log.e(TAG, "begin capture failed", e)
            capturing = false
        }
    }

    /** 结束录音；save=false 时丢弃音频文件。 */
    private fun endCapture(save: Boolean) {
        if (!capturing) return
        capturing = false
        handleView?.setRecording(false)

        val file = recorder.stop()
        recorder.onAmplitude = null
        removeBubble()

        if (!save) {
            file?.delete()
            return
        }
        if (file == null || file.length() <= 44) {
            file?.delete()
            return
        }

        persistAndTranscribe(file)
    }

    private fun cancelCapture() = endCapture(save = false)

    private fun persistAndTranscribe(file: File) {
        scope.launch {
            val repo = repository ?: return@launch
            val capsule = Capsule(
                text = "",
                audioPath = file.absolutePath,
                status = Capsule.STATUS_CAPTURED,
                source = "sidebar"
            )
            repo.add(capsule)

            if (!ApiConfig.hasSiliconFlowKey) return@launch

            repo.update(capsule.copy(status = Capsule.STATUS_TRANSCRIBING))
            try {
                val text = SiliconFlowTranscriber(ApiConfig.siliconFlowApiKey)
                    .transcribe(file.absolutePath)
                repo.update(capsule.copy(text = text, status = Capsule.STATUS_TRANSCRIBED))
            } catch (e: Exception) {
                Log.w(TAG, "transcribe failed: ${e.message}")
                repo.update(capsule.copy(status = Capsule.STATUS_CAPTURED))
            }
        }
    }

    // ---------------- 气泡 ----------------

    private fun showBubble() {
        if (bubbleView != null) return

        val view = CaptureBubbleView(this).apply { reset() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(56),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(80)
        }

        try {
            windowManager.addView(view, params)
            bubbleView = view
        } catch (e: Exception) {
            Log.e(TAG, "add bubble failed", e)
        }
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // ---------------- 前台通知 ----------------

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "闪念胶囊侧边栏",
                NotificationManager.IMPORTANCE_MIN
            )
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("闪念胶囊运行中")
            .setContentText("按住侧边把手说话，短按打开列表")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}