package com.shware.mode.mod.hello

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★★★ 一个 MOD 的**参考实现** —— 同时也是「mod 契约」的活文档。
 *
 * 它演示了 mod 必须做对的**四件事**：
 *
 * | # | 事 | 不做会怎样 |
 * |---|---|---|
 * | 1 | `onStartCommand` 里 **5 秒内 `startForeground`** | 进程被系统直接 ANR 掉 |
 * | 2 | 用 **`createDisplayContext(display)`** 拿 WindowManager | `addView` 只会画在**手机屏**上 |
 * | 3 | window 类型用 **`TYPE_APPLICATION_OVERLAY`** | 需要 `SYSTEM_ALERT_WINDOW` appop |
 * | 4 | `onDestroy` 里 **`removeView`** | 关了开关，卡片还赖在屏上 |
 *
 * ## 目标屏从哪来
 *
 * **优先**用启动器投喂的 `EXTRA_DISPLAY_ID`（它已经算好了 TNT 屏是 `100000`）；
 * 拿不到再自己兜底找。**别自己硬编码 100000** —— 换个机型就不是它了。
 *
 * ## 契约不共享代码
 *
 * 下面 [C] 里的常量是**照抄** `:app` 的 `com.shware.mode.mod.ModContract` 的，
 * **故意不引依赖** —— 这正是"插件"的含义：mod 不需要编译期就知道启动器的存在。
 */
class HelloModService : Service() {

    // ------------------------------------------------------------------ ★ 契约（抄自 ModContract）

    private object C {
        const val ACTION_MOD = "com.shware.mode.action.MOD"
        const val EXTRA_DISPLAY_ID = "com.shware.mode.extra.DISPLAY_ID"
        const val META_TOUCH = "com.shware.mode.MOD_TOUCH"

        /** 纯展示：整块矩形完全穿透（默认） */
        const val TOUCH_NONE = "none"

        /** 交互：只吃自己矩形内的点击 */
        const val TOUCH_SELF = "self"
    }

    companion object {
        private const val TAG = "ModeMod/Hello"
        private const val CHANNEL_ID = "mode_mod_hello"
        private const val NOTIF_ID = 2001
        private const val TICK_MS = 1000L
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var batteryView: TextView? = null
    private var clockView: TextView? = null
    private var titleView: TextView? = null

    /** ★ 自己申报的触摸行为（读自己 manifest 的 `MOD_TOUCH`） */
    private var touchMode: String = C.TOUCH_NONE

    /** 「刷新」按钮被按了几次 —— 让「按钮真的收到了点击」肉眼可见 */
    private var tapCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clockFmt: SimpleDateFormat

    private val ticker = object : Runnable {
        override fun run() {
            clockView?.text = clockFmt.format(Date())
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            batteryView?.text = "手机电量  ${readPhoneBattery()}%"
        }
    }

    // ------------------------------------------------------------------ 生命周期
    // ═══════════════════════════════════════════════════════════════════
    // ★ 图层控制（任务 AP / AP6）—— 响应宿主的 LAYER_CONTROL 广播
    //
    // 宿主**不拥有**别人的窗口（Android 的硬边界，不是没实现），
    // 所以它在图层管理页上按的「隐藏」只能是**请求**。这一段就是"听从这个请求"。
    // 契约见 .paper/plans/AP-图形化UI与对外API.md §4.3。
    //
    // ⚠️ 只改 visibility，**绝不 removeView** ——
    //    窗口的位置/大小由本 mod 自己管；removeView 之后再显示要重建窗口，
    //    代价大且容易留下错状态。
    //
    // ⚠️ `layerId` 必须和本模块 manifest 里的 `MOD_ID` **保持一致**。
    //    （宿主广播里带的 target 就是那个 id。改了一处忘了另一处，
    //      症状是"隐藏按钮点了没反应" —— 而不会报任何错。）
    // ═══════════════════════════════════════════════════════════════════

    private val layerId = "hello.card"
    private var layerRx: android.content.BroadcastReceiver? = null

    private fun registerLayerControl() {
        if (layerRx != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val target = i?.getStringExtra(EXTRA_LAYER_TARGET).orEmpty()
                if (target.isNotEmpty() && target != layerId) return   // 空 = 广播给所有
                val vis = when (i?.getStringExtra(EXTRA_LAYER_OP).orEmpty()) {
                    "hide" -> false
                    "show" -> true
                    "toggle" -> root?.visibility != android.view.View.VISIBLE
                    else -> return
                }
                root?.visibility =
                    if (vis) android.view.View.VISIBLE else android.view.View.GONE
                android.util.Log.i("ModeMod/Layer", "$layerId ← ${i?.getStringExtra(EXTRA_LAYER_OP)}（$vis）")
            }
        }
        ContextCompat.registerReceiver(
            this, rx, IntentFilter(ACTION_LAYER_CONTROL),
            // ★ F5 修复：宿主与 mod **同 UID 同 APK**（只是不同进程）⇒ 同道广播，
            //    用 NOT_EXPORTED 就够，**不需要**对外暴露这个 receiver。
            //    ⚠️ 与 BATTERY_CHANGED 不同 —— 那个是**系统**广播，必须 EXPORTED。
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        layerRx = rx
        android.util.Log.i("ModeMod/Layer", "$layerId 已登记图层控制监听")
    }

    private fun unregisterLayerControl() {
        layerRx?.let { runCatching { unregisterReceiver(it) } }
        layerRx = null
    }




    override fun onCreate() {

        registerLayerControl()
        super.onCreate()
        clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        // ★★★ 触摸行为：**读自己 manifest 的申报**，不猜。
        //    这样无论谁拉起本 mod（启动器 / adb / 别的），行为都一致。
        touchMode = readOwnTouchMode()
        Log.i(TAG, "申报的触摸行为 MOD_TOUCH=$touchMode")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ① ★ 5 秒内必须进来 —— 否则系统直接 ANR 本进程
        startForegroundCompat()

        // ② 目标屏：优先信启动器给的
        val fromLauncher = intent?.getIntExtra(C.EXTRA_DISPLAY_ID, -1) ?: -1
        // ⚠️ 判据必须是 >= 0：手机屏的 id 就是 0，用 `> 0` 会把"画在手机屏上"误判成"没给"
        //    ⇒ 静默落到 fallback（TNT 虚拟屏）。详见 ModContract.EXTRA_DISPLAY_ID
        val displayId = if (fromLauncher >= 0) fromLauncher else fallbackDisplayId()
        Log.i(TAG, "onStartCommand: 启动器给的 display=$fromLauncher，实际用 $displayId")

        if (root == null) {
            attachOverlay(displayId)
            handler.post(ticker)
            ContextCompat.registerReceiver(
                this, batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                // ⚠️ 系统广播必须用 EXPORTED —— Smartisan A10 上 NOT_EXPORTED 会生成一个
                //    合成权限 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，
                //    把系统发来的 BATTERY_CHANGED 挡在门外（实测 logcat:
                //    "Permission Denial: broadcasting BATTERY_CHANGED ... requires ..."）
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        return START_STICKY   // 被系统回收后自动重来
    }

    override fun onDestroy() {

        unregisterLayerControl()
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(batteryReceiver) }
        // ④ ★ 必须撤 —— 否则关了开关卡片还在
        runCatching {
            val v = root ?: return@runCatching
            wm?.removeViewImmediate(v)
            Log.i(TAG, "overlay 已撤下")
        }
        root = null; wm = null; batteryView = null; clockView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ overlay

    /**
     * ★ 把卡片挂到目标屏上。
     *
     * **`createDisplayContext` 是全部的机关** —— 少了它，`addView` 只会落在默认屏（手机屏）。
     */
    private fun attachOverlay(displayId: Int) {
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId)
        if (display == null) {
            Log.w(TAG, "没有 display $displayId —— 挂不上")
            return
        }

        runCatching {
            val displayContext = createDisplayContext(display)
            val w = displayContext.getSystemService(WindowManager::class.java)

            val card = buildCard(displayContext)
            val p = WindowManager.LayoutParams(
                // ⚠️ 必须 WRAP_CONTENT。用 MATCH_PARENT（哪怕全透明）触摸区就是【整屏】，
                //    交互型 mod 会吃掉 TNT 上所有点击，整个桌面就废了。
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                touchFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 40
                y = 40
            }

            w.addView(card, p)
            wm = w
            root = card
            Log.i(TAG, "✓ overlay 已挂到 display $displayId (${display.name})  触摸=$touchMode  标志=0x${Integer.toHexString(touchFlags())}")
        }.onFailure {
            Log.e(TAG, "✗ 挂 overlay 失败：${it.javaClass.simpleName}: ${it.message}", it)
        }
    }

    private fun buildCard(ctx: Context): LinearLayout {
        val pad = (14 * ctx.resources.displayMetrics.density).toInt()   // target-display density

        titleView = TextView(ctx).apply {
            text = "★ MODE 组件   [触摸=$touchMode]"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        batteryView = TextView(ctx).apply {
            text = "手机电量  ${readPhoneBattery()}%"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        clockView = TextView(ctx).apply {
            text = clockFmt.format(Date())
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 11f
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            addView(titleView); addView(batteryView); addView(clockView)
        }

        // ★ 交互型才有按钮 —— `none` 的窗口带 FLAG_NOT_TOUCHABLE，**自己也点不了**，
        //   放个按钮只会是死的（这正是两种模式互斥的原因）。
        if (touchMode == C.TOUCH_SELF) {
            card.addView(Button(ctx).apply {
                text = "刷新"
                textSize = 12f
                setOnClickListener {
                    tapCount++
                    refreshValues()
                    titleView?.text = "★ MODE 组件   [触摸=$touchMode]  按钮×$tapCount"
                    Log.i(TAG, "★ 按钮被点击 ×$tapCount（点击被本卡片吃掉，没有漏到后面）")
                }
            })
        }

        // 触摸日志 —— 用来【证明】触摸到底有没有到我们窗口，而不是靠肉眼猜
        card.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                Log.i(TAG, "↓ 卡片收到 DOWN @(${e.x.toInt()}, ${e.y.toInt()})  —— 本次点击【没有】漏到后面的窗口")
            }
            false   // 不消费，让按钮等子视图继续正常处理
        }

        return card
    }

    /**
     * ★★★ 按申报的触摸行为选窗口标志 —— **这是本文件最要紧的 8 行**。
     *
     * | 申报 | 标志 | 效果 |
     * |---|---|---|
     * | `none` | `NOT_FOCUSABLE \| NOT_TOUCHABLE` | 整块矩形**完全穿透**（连自己也点不了） |
     * | `self` | `NOT_FOCUSABLE \| NOT_TOUCH_MODAL` | **只吃自己矩形内**的点击，矩形外穿透 |
     *
     * ⚠️ 光有 `FLAG_NOT_FOCUSABLE` **不叫穿透** —— 它只让**矩形外**的点击传给下层
     *    （它隐含 `NOT_TOUCH_MODAL`），**矩形内**的仍然归本窗口。
     *    矩形内视图不消费时事件被**丢弃**，不会转给下层 ⇒ 卡片会静默吃掉那块区域的点击。
     *    详见 `com.shware.mode.mod.ModContract` §四。
     */
    private fun touchFlags(): Int =
        if (touchMode == C.TOUCH_SELF) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    /** 读自己 manifest 里 `<service>` 的 `MOD_TOUCH` 申报。读不到 ⇒ `none`（安全默认）。 */
    private fun readOwnTouchMode(): String = runCatching {
        val cn = ComponentName(this, HelloModService::class.java)
        val si = packageManager.getServiceInfo(cn, PackageManager.GET_META_DATA)
        si.metaData?.getString(C.META_TOUCH)?.trim().orEmpty()
    }.getOrDefault("").takeIf { it == C.TOUCH_SELF } ?: C.TOUCH_NONE

    private fun refreshValues() {
        batteryView?.text = "手机电量  ${readPhoneBattery()}%"
        clockView?.text = clockFmt.format(Date())
    }

    // ------------------------------------------------------------------ 工具

    /** 启动器没给目标屏时的兜底：取 displayId 最大的那块（坚果上就是 TNT 虚拟屏）。 */
    private fun fallbackDisplayId(): Int {
        val dm = getSystemService(DisplayManager::class.java)
        return dm.displays.maxByOrNull { it.displayId }?.displayId ?: Display.DEFAULT_DISPLAY
    }

    private fun readPhoneBattery(): Int =
        getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MODE 组件", NotificationManager.IMPORTANCE_MIN)
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("MODE 组件运行中")
            .setContentText("示例·状态卡片")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIF_ID, n)
    }
}


// ═══════════════════════════════════════════════════════════════════════════
// ★ 图层控制契约的字符串常量（任务 AP / AP6）
//
// ⚠️ 这三个值**必须与宿主的 `LayerManager` 逐字一致**。
//    mod 刻意不依赖宿主的模块（"无共享 AAR、各抄各的契约字符串"是既定约定），
//    所以这份重复是【故意】的 —— 抄错的症状是"隐藏按钮点了没反应"，
//    ★ 而且**不会报任何错**。
//
// ★ 放在**文件级**而不是类里的 companion object：
//    本类已经有一个 companion object 了，Kotlin **每个类只允许一个**。
// ═══════════════════════════════════════════════════════════════════════════
private const val ACTION_LAYER_CONTROL = "com.shware.mode.action.LAYER_CONTROL"
private const val EXTRA_LAYER_OP = "com.shware.mode.extra.LAYER_OP"
private const val EXTRA_LAYER_TARGET = "com.shware.mode.extra.LAYER_TARGET"
