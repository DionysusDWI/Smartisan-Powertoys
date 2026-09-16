package com.shware.mode.mod.tntgo

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
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shware.mode.tntgoserial.TntgoState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ★★★ MOD：**TNT GO 电量 ＋ 功耗 ＋ 可用时间**（任务 AQ）。
 *
 * TNT 屏上常驻显示手机 + TNT GO 双电量，并**算出** TNT GO 的功耗与剩余可用时间。
 *
 * ## 契约五件套（照 [com.shware.mode.mod.ModContract] 抄）
 *
 * | # | 事 | 本类怎么做的 |
 * |---|---|---|
 * | 1 | 5 秒内 `startForeground` | [onStartCommand] 第一句 |
 * | 2 | `createDisplayContext` 拿 WindowManager | [attachOverlay] |
 * | 3 | `TYPE_APPLICATION_OVERLAY` | [touchFlags] 那组参数 |
 * | 4 | `onDestroy` 撤窗 | [onDestroy] |
 * | 5 | ★ **`MOD_TOUCH=self`**（任务 AQ 改的，为了能拖） | [touchFlags] |
 *
 * ## ★★★ 本 mod 的三个"用户看得见的数字"分别是怎么来的
 *
 * | 数字 | 来源 | 诚实性约束 |
 * |---|---|---|
 * | **功耗** | `|I| × V`（电池侧） | ⚠️ **含对外供电** —— AQ2 实测端口功率读不到、扣不掉。<br/>★ 卡片上**必须**有那行小字 |
 * | **可用时间** | `容量 × SOC / |I| × 0.9` | 容量是**学的**（[TntgoCapacity]）⇒ 一律带 `≈` |
 * | **温度** | `+BATCG` 第 5 字段 ÷ 10 | 解析不出来时**不显示**（不画一个假的 0 °C） |
 *
 * ## 拖动：照 [`mod-tntgo-brightness`] 抄，**不是** `mod-perfmon`
 *
 * ★ 用户 2026-09-14 §1 指定得对：亮度 mod **同样画在 TNT 屏上**，已经把大屏适配踩完了；
 * `mod-perfmon` 是**手机屏**的，直接抄会掉进同一个坑
 * （`resources.displayMetrics` 返回的是手机屏尺寸）。
 *
 * ## ⚠️ 与亮度 mod 的一处关键差异
 *
 * 亮度 mod 的卡片**平时 `INVISIBLE`** ⇒ 改成 `self` 后平时完全不挡。
 * **本 mod 是常驻显示的** ⇒ 改用 `self` 后**那块矩形会一直吃点击**。
 * 用户 2026-09-14 §4 明确接受（「吃点击没啥问题，只要可以拖走」）
 * ⇒ **缓解手段就是可拖动本身**。
 */
class TntgoBatteryService : Service() {

    // ------------------------------------------------------------------ ★ 契约（抄自 ModContract）

    private object C {
        const val EXTRA_DISPLAY_ID = "com.shware.mode.extra.DISPLAY_ID"
        const val META_TOUCH = "com.shware.mode.MOD_TOUCH"
        const val TOUCH_NONE = "none"
        const val TOUCH_SELF = "self"
    }

    companion object {
        private const val TAG = "ModeMod/Tntgo"
        private const val CHANNEL_ID = "mode_mod_tntgo"
        private const val NOTIF_ID = 2002

        /** 轮询间隔。TNT GO 的主动推送节奏也在几十秒量级；更密只会白白抢 USB 接口 */
        private const val POLL_MS = 30_000L

        /** ★ 数据陈旧时的置灰色（修 F1） */
        private val STALE_COLOR = Color.parseColor("#607D8B")
        private val POWER_COLOR = Color.parseColor("#FFE082")
        private val RUNTIME_COLOR = Color.parseColor("#81D4FA")
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null

    /** ★ 卡片的窗口参数（拖动时要改 x/y 并 `updateViewLayout`） */
    private var params: WindowManager.LayoutParams? = null

    private var phoneView: TextView? = null
    private var tntView: TextView? = null
    private var powerView: TextView? = null
    private var powerNoteView: TextView? = null
    private var runtimeView: TextView? = null
    private var updatedView: TextView? = null
    private var statusView: TextView? = null

    private var touchMode: String = C.TOUCH_NONE

    /** ★★ 目标屏尺寸 —— **必须直接问那块 Display**，见 [attachOverlay] 里的两条错路 */
    private var screenW = 0
    private var screenH = 0

    /** 当前挂在哪块屏 —— 字号变化重建卡片时要用它挂回去 */
    private var currentDisplayId = -1

    // ---- ★ 拖动状态（照 mod-tntgo-brightness）----
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private lateinit var gestures: GestureDetector
    private lateinit var capacity: TntgoCapacity

    /**
     * ★★★ AR12：**亮度 → 功耗**的台账与曲线（`I(b)`）。
     *
     * 可行性判据（两个亮度稳态下 `I` 是否可分辨）**AR12c 已达标**，
     * 自变量域（MCU 还是 UI）**AR12c-2 已判定为 MCU**。
     * ⇒ 现在它既**记**样本，也**算**一条 `I(MCU)` 曲线（AR12b）。
     * 详见 [`TntgoBklProfile`] 与 [`TntgoBklCurve`] 的类注释。
     */
    private lateinit var bklProfile: TntgoBklProfile

    /**
     * ★★ 上一轮成功读数时的**亮度上下文**（轮询线程写、主线程读）。
     *
     * ## 为什么必须成对存下来
     *
     * 卡片渲染比轮询晚（都在主线程排队），而亮度状态文件是**另一个进程**写的。
     * 若渲染时**重新**去读一次亮度文件，就可能出现
     * 「`I` 是 30 秒前那笔、亮度是刚刚那一笔」⇒ **功耗与 `@亮度` 对不上**，
     * 而**界面上完全看不出来**。
     *
     * ⇒ ★ 把「这笔电流 + 当时亮度 + 当时曲线」**作为一个整体**存下来，渲染只用这一份快照。
     */
    @Volatile
    private var lastBkl: Pair<TntgoBklCurve.Curves, TntgoState.Brightness?>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tntgo-serial").apply { isDaemon = true }
    }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var reader: TntgoSerialReader

    /** 最近一次成功读到的（读失败时**不清空**） */
    @Volatile
    private var lastOk: TntgoSerialReader.Reading.Ok? = null

    /**
     * ★★★ [lastOk] 是**哪一刻**读到的（修 F1 · CC 审计「严重」）。
     *
     * 原来卡片上的「更新」用的是 Date() = **渲染时刻** ——
     * 于是每 30 秒渲染一次就把时间戳刷新一次，**哪怕这一次根本没读到数**。
     * ⇒ 界面上「更新 14:57:19」看起来是新鲜的，实际数据可能是十分钟前的。
     * ★ **这正是本项目最忌讳的那类错**：没有编造数值，但**编造了新鲜度**。
     *
     * ⇒ 改成记录**数据本身的时刻**，并据此显示"多久以前"。
     */
    @Volatile
    private var lastOkAt: Long = 0L

    /**
     * ★ 电流滑动窗口（最近 [TntgoPower.MEDIAN_WINDOW] 次）。
     *
     * 用**中位数**而不是均值 —— 串口读数偶发跳变（实测相邻两次能差一倍），
     * 均值会被单次坏值整个拖偏，中位数对它免疫。
     */
    private val currentWindow = ArrayDeque<Int>()

    /** 累计有效读数个数（够 [TntgoPower.MIN_SAMPLES] 才给可用时间） */
    private var sampleCount = 0

    /**
     * 建卡片时用的字号。
     *
     * ★ 轮询时比对它 —— 设置界面改了字号 ⇒ **整块重建卡片**。
     * 比逐项比对省事且不会漏（卡片很小，重建开销可忽略）。
     */
    private var appliedFontScale = 0f

    private var lastPolling = false

    private val poller = object : Runnable {
        override fun run() {
            pollRead()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = renderPhone()
    }

    /** 用户点了系统弹窗之后 —— 立刻重试一次，别让用户干等 30 s */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
            Log.i(TAG, "USB 权限弹窗结果: granted=$granted")
            reader.resetPermissionRequested()
            handler.postDelayed({ pollRead() }, 500)
        }
    }

    // ------------------------------------------------------------------ ★ 图层控制（任务 AP / AP6）
    // ═══════════════════════════════════════════════════════════════════
    // 宿主**不拥有**别人的窗口（Android 的硬边界），所以它在图层管理页上按的
    // 「隐藏」只能是**请求**。这一段就是"听从这个请求"。
    //
    // ⚠️ 只改 visibility，**绝不 removeView** —— 窗口的位置/大小由本 mod 自己管，
    //    而且 removeView 之后再显示要重建窗口，代价大且容易出状态错。
    //
    // ⚠️ `layerId` 必须和本模块 manifest 里的 `MOD_ID` **保持一致**。
    // ═══════════════════════════════════════════════════════════════════

    private val layerId = "tntgo.battery"
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
                    "toggle" -> root?.visibility != View.VISIBLE
                    else -> return
                }
                root?.visibility = if (vis) View.VISIBLE else View.GONE
                Log.i("ModeMod/Layer", "$layerId ← ${i?.getStringExtra(EXTRA_LAYER_OP)}（$vis）")
            }
        }
        ContextCompat.registerReceiver(
            this, rx, IntentFilter(ACTION_LAYER_CONTROL),
            // ★ F5 修复：宿主与 mod **同 UID 同 APK**（只是不同进程）⇒ 同道广播
            //    用 NOT_EXPORTED 就够，**不需要**对外暴露这个 receiver。
            //    ⚠️ 与 BATTERY_CHANGED 不同 —— 那个是**系统**广播，必须 EXPORTED。
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        layerRx = rx
        Log.i("ModeMod/Layer", "$layerId 已登记图层控制监听")
    }

    private fun unregisterLayerControl() {
        layerRx?.let { runCatching { unregisterReceiver(it) } }
        layerRx = null
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        registerLayerControl()
        super.onCreate()

        reader = TntgoSerialReader(this)
        capacity = TntgoCapacity(this)
        bklProfile = TntgoBklProfile(this)      // ★ AR12：亮度-功耗台账
        touchMode = readOwnTouchMode()

        // ★ 手势：长按进拖动 / 双击开设置（手写长按判定容易和双击打架，必须用系统的）
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                dragging = true
                applyCardBackground()
                Log.i(TAG, "★ 长按 → 进入拖动模式")
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.i(TAG, "★ 双击 → 打开设置")
                openSettings()
                return true
            }
        })

        ContextCompat.registerReceiver(
            this, usbReceiver,
            IntentFilter(TntgoSerialReader.ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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

    /**
     * ★★ N9：一句话说清**当前容量是怎么来的**（日志与界面共用同一套说法）。
     *
     * 三种态必须分开 —— 尤其"段数够了但样本打架"这一种：
     * 它用的是**最小样本**而不是中位数，看日志的人得能分辨出来，
     * 否则会以为学习值算错了。
     */
    private fun capacityNote(): String {
        val f = capacity.fusion
            ?: return "先验，已积累 ${capacity.segmentCount}/${TntgoCapacity.MIN_SEGMENTS} 段"
        return if (f.converged) {
            "已学习 ${f.count} 段，取中位数，离散度 ${"%.1f".format(f.dispersion * 100)}%"
        } else {
            // ★ 离散度一律 `%.1f`（**两个分支必须同精度**）。
            //
            // ⚠️ 这里原来写的是 `%.0f`。而阈值恰好是 **10%** ⇒
            //    `10.4%`（超标）与 `9.6%`（达标）**都会被印成「10%」**
            //    ⇒ 日志会读成"离散度 10% > 10%" 这种自相矛盾的话。
            //    实机抓到的中间版本正是 `离散度 12% > 10%`（少了 .1 的位）。
            "⚠️ 已学习 ${f.count} 段但未收敛（离散度 " +
                "${"%.1f".format(f.dispersion * 100)}% > " +
                "${"%.0f".format(TntgoCapacity.DISPERSION_MAX * 100)}%）⇒ 取最保守的一段"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val fromLauncher = intent?.getIntExtra(C.EXTRA_DISPLAY_ID, -1) ?: -1
        // ⚠️ 判据必须是 >= 0：手机屏的 id 就是 0，用 `> 0` 会把"画在手机屏上"误判成"没给"
        val displayId = if (fromLauncher >= 0) fromLauncher else fallbackDisplayId()

        if (root == null) {
            Log.i(TAG, "启动：display=$displayId  触摸=$touchMode  " +
                    "容量=${"%.0f".format(capacity.capacityMah)} mAh（${capacityNote()}）")
            attachOverlay(displayId)
            handler.post(poller)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterLayerControl()
        handler.removeCallbacks(poller)
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching {
            root?.let { wm?.removeViewImmediate(it); Log.i(TAG, "overlay 已撤下") }
        }
        root = null; wm = null; params = null
        phoneView = null; tntView = null; powerView = null
        powerNoteView = null; runtimeView = null; updatedView = null; statusView = null
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 读

    // ------------------------------------------------------------------ 配置变化

    /**
     * 字号被设置界面改过 ⇒ **整块重建卡片**。
     *
     * ⚠️ 重建时**保留当前位置** —— 用户拖好的位置不能因为改字号就弹回默认。
     * （`attachOverlay` 会从 `TntgoConfig` 读回已存的位置，所以只要先撤窗再挂窗即可。）
     */
    private fun checkFontScaleChanged() {
        val now = TntgoConfig.fontScale(this)
        if (now == appliedFontScale) return
        Log.i(TAG, "字号变了 (${appliedFontScale} → ${now}) ⇒ 重建卡片")
        appliedFontScale = now
        if (root == null || wm == null) return

        // 记下当前窗口坐标（此刻最真实），撤窗、重建、再按记下的位置挂回去
        val keepX = params?.x
        val keepY = params?.y
        if (keepX != null && keepY != null) TntgoConfig.savePos(this, keepX, keepY)

        val displayId = currentDisplayId
        runCatching { root?.let { wm?.removeViewImmediate(it) } }
        root = null; wm = null; params = null
        phoneView = null; tntView = null; powerView = null
        powerNoteView = null; runtimeView = null; updatedView = null; statusView = null
        if (displayId >= 0) attachOverlay(displayId)
    }

    // ------------------------------------------------------------------ 读

    /** 串口读取 —— **永远在后台线程**（每次 0.7–2.5 s，还有 open/close） */
    private fun pollRead() {
        // ★ 先看一眼字号变没变（设置界面可能刚改过）—— 变了就整块重建
        checkFontScaleChanged()

        if (lastPolling) return
        lastPolling = true
        renderTnt(reading = null, note = "读取中…")
        io.execute {
            val r = runCatching { reader.read() }.getOrElse {
                TntgoSerialReader.Reading.Failed("${it.javaClass.simpleName}: ${it.message}")
            }
            if (r is TntgoSerialReader.Reading.Ok) {
                lastOk = r
                val now = System.currentTimeMillis()
                lastOkAt = now

                // ★ 喂给容量学习（**只在成功读到数时喂** —— 读失败时不知道那段时间的电流，
                //   交给 gap 检测器去判"断档"）
                //
                // ⚠️ 这条注释原来写的是"并**作废**那一段"，**N6a 之后已不成立** ——
                //    断档现在只把时长单独记账（`segGapMs`），**段继续累计**。
                //    被漏记的仅是断档期间的电荷 ⇒ `C_est` 偏保守。**别按旧注释去"修"它。**
                val learned = capacity.onReading(r.percent, r.currentMa, now)
                if (learned != null) {
                    Log.i(TAG, "容量更新为 ${"%.0f".format(capacity.capacityMah)} mAh（${capacityNote()}）")
                }

                // ★★★ AR12：把 (UI, MCU, 电流, 充电状态) 记进台账。
                //
                // ⚠️ 亮度**读不到就不记**（`record` 收到 null 直接返回）——
                //    AR §阶段 C+ 的红线：亮度未知时**绝不猜**，
                //    猜错的亮度会让曲线学歪、而且用户看不出来。
                // ★ AR12b：`mcu` 是**实测直读量**，不是用出厂曲线反解的推算值
                //    （反解会让"曲线一改、已采样本被追溯篡改"）。
                val bkl = bklProfile.currentBrightness(this)
                bklProfile.record(bkl?.ui, bkl?.mcu, r.currentMa, now)
                bklProfile.refreshCurve()
                lastBkl = bklProfile.loadCurve() to bkl
                if (bkl == null && sampleCount % 5 == 0) {
                    // ★★ 每 5 轮提示一次。**必须说出"它是怎么不可用的"** ——
                    //    2026-09-15 那次故障里，四种完全不同的原因在日志里长得一模一样，
                    //    我先怀疑的是"亮度 mod 死了"，实际进程活得好好的、是**心跳没被排程**。
                    val why = TntgoState.readBrightnessRaw(this).second
                    Log.w(TAG, "AR12：亮度不可用 ⇒ 本笔不入台账 —— ${why?.describe() ?: "（原因未知）"}")
                }

                currentWindow.addLast(r.currentMa)
                while (currentWindow.size > TntgoPower.MEDIAN_WINDOW) currentWindow.removeFirst()
                sampleCount++
            }
            lastPolling = false
            handler.post { renderTnt(r, note = null) }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderPhone() {
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        phoneView?.text = "手机电量      $pct%"
    }

    private fun renderTnt(reading: TntgoSerialReader.Reading?, note: String?) {
        val keep = lastOk
        val (value, color, detail) = when (reading) {
            is TntgoSerialReader.Reading.Ok ->
                Triple(
                    "${reading.percent}%   ${if (reading.charging) "↑充电" else "↓放电"}",
                    Color.parseColor("#81C784"),
                    buildString {
                        append("${reading.millivolts} mV   ${reading.currentMa} mA")
                        // ★ F9：`null` 才是"没读到"。原来判 `> 0.0` ⇒ **真实的 0 °C 会被当成没读到**。
                        reading.tempC?.let { append("   ${"%.1f".format(it)}°C") }
                    },
                )

            is TntgoSerialReader.Reading.NoDevice ->
                Triple("未连接", Color.parseColor("#E0E0E0"), "没找到 TNT GO（VID 0x31CE / PID 0x5101）")

            is TntgoSerialReader.Reading.NoPermission ->
                Triple("等待 USB 授权", Color.parseColor("#FFB74D"), "请在系统弹窗点「允许」（勾「默认」以后就不弹了）")

            // ★★★ AS3b：端口被另一个 mod 占着 ⇒ ★ **本轮让路**。
            //
            // ⚠️ 刻意**不按失败处理**：
            //   · **不改颜色告警** —— 这是**设计行为**（优先级：后台轮询让路给人的手指），
            //     每 30 s 最多发生一次，而且下一轮通常就拿到了；
            //   · **不显示"上次 xx%"** —— 那不是"陈旧"，只是**这一拍没读**，
            //     数值仍是最近一次真实读数，正常显示即可。
            //   · ★ 但**必须在 detail 里说出来** —— 否则它和"读失败"在日志/界面上
            //     又混成一件事了（本会话真的因此误判过一次）。
            //
            // ★ 为什么"让路"不影响容量学习：它表现为一次**断档**，
            //   而 N6a 已经把断档改成"段继续 + 断档单独记账"（`segGapMs`），
            //   代价只是容量估计**偏保守** —— 正合用户要求。
            is TntgoSerialReader.Reading.Busy ->
                Triple(
                    keep?.let { "${it.percent}%" } ?: "…",
                    Color.parseColor("#B0BEC5"),
                    note ?: "端口被「${reading.holder}」占着，本轮让路（★ 不是故障）",
                )

            is TntgoSerialReader.Reading.Failed ->
                Triple(
                    keep?.let { "上次 ${it.percent}%" } ?: "读取失败",
                    Color.parseColor("#EF9A9A"),
                    reading.why,
                )

            null -> Triple(
                keep?.let { "${it.percent}%" } ?: "…",
                Color.parseColor("#B0BEC5"),
                note ?: "",
            )
        }

        tntView?.text = "TNT GO 电量  $value"
        tntView?.setTextColor(color)
        // ★ 用【数据时刻】而不是渲染时刻；并显式标出"多久以前"
        updatedView?.text = if (lastOkAt == 0L) "更新         —"
        else {
            val ageS = (System.currentTimeMillis() - lastOkAt) / 1000
            val mark = when {
                ageS < 90 -> ""                       // 正常节奏内，不啰嗦
                ageS < 3600 -> "  ⚠ 分钟前"  // ★ 明显陈旧，必须说出来
                else -> "  ⚠ 小时前"
            }
            "更新         "
        }
        statusView?.text = detail
        statusView?.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

        renderPower()
    }

    /**
     * ★★ 功耗与可用时间两行。
     *
     * ⚠️ **读不到数时两行都要收起来** —— 保留上一次的数字却不标时间，
     * 用户会以为那是当前的。
     */
    private fun renderPower() {
        val ok = lastOk
        if (ok == null) {
            powerView?.visibility = View.GONE
            powerNoteView?.visibility = View.GONE
            runtimeView?.visibility = View.GONE
            return
        }

        // ── 功耗
        val liveMa = TntgoPower.median(currentWindow.toList()) ?: ok.currentMa
        val (curves, bkl) = lastBkl ?: (TntgoBklCurve.Curves() to null)

        // ★★★ AR12b：把电流换成【当前亮度下学到的中位数】。
        //
        // | 情形 | 用哪个数 | 为什么 |
        // |---|---|---|
        // | 曲线可用 + 落在实测带内 | ★ 学到的 `I(b)` | 瞬时值噪声大（实测 MAD 26–41 mA 是**同一稳态内**的离散），学过的是中位数 |
        // | 亮度过暗/过亮，超出实测带 | ★ **瞬时值** | ⛔ **绝不外推** —— 延长出去的直线可能差一倍，却看着很精确 |
        // | 曲线档位不足 / 亮度未知 | 瞬时值（照旧） | 如实降级，**不编一个亮度** |
        // | 正在充电 | ★ **瞬时值** | `I` 在充电态是"充电器供给 − 负载"，随 CC/CV 阶段变；亮度只是**遮住**了它 |
        // | ★★ 曲线被判反物理 | **瞬时值** | 走向与物理相悖 ⇒ 那条直线是被异常点拽着的，插值出来的数是**假的**（AR13 接上的第三道闸） |
        //
        // ★ 每次选用哪个数、以及"该处未测"，都必须**写在卡片上** ——
        //    否则用户无法判断这个数字能不能信（本项目的核心纪律）。
        val est = if (bkl != null && !ok.charging) {
            // ★ AR13：显式传【放电】方向 —— 方向错了会把正确的曲线判死（见 TntgoBklCurve.Trend）
            TntgoBklCurve.estimate(curves.discharging, bkl.mcu, TntgoBklCurve.Trend.Discharge)
        } else {
            null
        }
        val learned = est?.takeIf { it.where == TntgoBklCurve.Where.InBand }?.absMa
        val shownMa = learned?.let { (if (ok.currentMa < 0) -it else it).toInt() } ?: liveMa

        val w = TntgoPower.watts(ok.millivolts, shownMa)
        powerView?.text = "功耗         ${TntgoPower.formatWatts(w)}"
        powerView?.visibility = View.VISIBLE

        // ★★★ 「含对外供电」这行小字**不能省** —— AQ2 实测端口功率读不到、扣不掉，
        //     少了它用户会把总功耗当成 TNT GO 自己的耗电。
        powerNoteView?.text = powerNoteText(ok, bkl, est)
        powerNoteView?.visibility = View.VISIBLE

        // ── 可用时间
        //
        // ★ AR12b：喂给它的电流**与功耗行是同一个数** —— 两行必须自洽，
        //   否则会出现「功耗按曲线算、可用时间按瞬时值算」这种看不出矛盾的组合。
        val runEst = TntgoPower.estimate(
            soc = ok.percent,
            currentMa = shownMa,
            capacityMah = capacity.capacityMah,
            sampleCount = sampleCount,
        )
        runtimeView?.text = "可用         ${TntgoPower.formatEstimate(runEst)}"
        runtimeView?.visibility = View.VISIBLE
    }

    /**
     * ★★★ **功耗行下面那行小字** —— 它承担三件事，一件都不能丢：
     *
     * 1. **「含对外供电」** —— AQ2 实测端口功率读不到、扣不掉。少了它，
     *    用户会把总功耗当成 TNT GO 自己的耗电（这不是措辞问题，是数据含义的一部分）。
     * 2. ★ **这个数是从哪来的** —— 学到的中位数（`@ 亮度 N%`）还是瞬时值（`@ 亮度 N%瞬时`）。
     *    同一个 `≈ 5.1 W`，两种来源的可信度完全不同。
     * 3. ★★ **亮度未知 / 该处未测 / 曲线被拒** 必须说出来 ——
     *    这正是 AR §阶段 C+ 的红线：**宁可不给，也不猜**。
     *
     * ★★★★★ **G3（2026-09-16）已修：给不出数的【原因】必须分开说** ——
     *
     * | 原因 | 卡片 | 用户会做什么 |
     * |---|---|---|
     * | `Where` 带外（真没测过） | `★ 该处未测（实测 …），未外推` | **等** —— 暗处本来就没测过 |
     * | ★ `Unusable.Rejected`（走向反物理） | `★ 曲线被拒（走向反物理）` | ★ **重采** —— 有别的东西在漂，等不来 |
     *
     * ⛔ 改之前这两件事**长得一模一样**（都走 `where != InBand` 那一支）⇒
     * 用户读到"没测过"会去**等**，而该做的是**重采**。**两个动作相反。**
     * ★ 根因不是"忘了写文案"，而是 `Estimate` 里**放不下"被拒"这个结论**
     *   （同纪律 ⑳：`Gate` 当初放不下"反物理"⇒ `maxDrop()` 零调用点）。
     *
     * @param est 曲线查询结果；`null` = 这次没走曲线（充电态或亮度未知）
     */
    private fun powerNoteText(
        ok: TntgoSerialReader.Reading.Ok,
        bkl: TntgoState.Brightness?,
        est: TntgoBklCurve.Estimate?,
    ): String {
        val base = "                （含对外供电）"
        if (bkl == null) {
            // ★ 亮度 mod 没运行 / 心跳停了 ⇒ 如实说，**不猜一个亮度**
            return "$base\n                亮度未知（亮度组件未运行）"
        }
        val pct = "亮度 ${bkl.ui}%"
        if (ok.charging) {
            // 充电态不用曲线的理由：`I = 充电器供给 − 负载`，随 CC/CV 阶段变
            return "$base\n                $pct（↑充电中，电流随充电阶段变，未用曲线）"
        }
        if (est == null) return "$base\n                $pct"

        // ★★★ G3：**先问"能不能信"，再问"在哪里"** ——
        //   被拒时**位置仍然照报**（它没说错），但原因必须说出来。
        if (est.unusable != null) {
            val why = when (est.unusable) {
                TntgoBklCurve.Unusable.Rejected -> "★ 曲线被拒（走向反物理）"
                TntgoBklCurve.Unusable.NotEnoughLevels -> "★ 尚无实测档位"
            }
            return "$base\n                @ $pct $why"
        }

        val where = when (est.where) {
            TntgoBklCurve.Where.InBand -> return "$base\n                @ $pct（曲线）"
            TntgoBklCurve.Where.BelowBand -> "低于实测带"
            TntgoBklCurve.Where.AboveBand -> "高于实测带"
            TntgoBklCurve.Where.NoLevels -> "尚无实测档位"
        }
        val band = if (est.bandMcuMin != null && est.bandMcuMax != null) {
            "（实测 ${est.bandMcuMin}~${est.bandMcuMax}）"
        } else {
            ""
        }
        // ★ 关键：**不外推**，并明确告诉用户"这个数不是你这档测出来的"
        return "$base\n                @ $pct ★ 该处未测$band，未外推"
    }

    // ------------------------------------------------------------------ overlay

    private fun attachOverlay(displayId: Int) {
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId)
        if (display == null) { Log.w(TAG, "没有 display $displayId"); return }

        runCatching {
            val ctx = createDisplayContext(display)

            // ★★ 屏幕尺寸**必须直接问那块 Display**（照亮度 mod，两条错路都踩过）：
            //    1. `resources.displayMetrics`（Service 的）⇒ 给的是**默认屏**（手机屏）
            //    2. `createDisplayContext(display).resources.displayMetrics` ⇒ **还是手机屏**
            //    ⇒ **只有 `display.getRealMetrics(dm)` 对**（实测 2160×1440 vs 错的 1080×2142）
            //    用错的后果：默认位置算出 y=1883 而 TNT 屏只有 1440 高 ⇒ 卡片跑到屏外，
            //    而且拖动的钳制也形同虚设。
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
            Log.i(TAG, "目标屏尺寸：${screenW}×${screenH}（Display.getRealMetrics）")

            val w = ctx.getSystemService(WindowManager::class.java)
            val card = buildCard(ctx)
            val d = ctx.resources.displayMetrics.density

            val p = WindowManager.LayoutParams(
                // ⚠️ 高度 WRAP_CONTENT；宽度给**定值** —— 内容行数会变（充电/断连时少一行），
                //    WRAP_CONTENT 会让卡片宽度跟着跳，很难看。
                (TntgoConfig.CARD_WIDTH_DP * d).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                touchFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                // ★★ **全程只用 `TOP|START`** —— 位置语义必须唯一。
                //    亮度 mod 曾混用 `BOTTOM|CENTER_HORIZONTAL`（那种下 x 是**居中偏移**）
                //    与 `TOP|START`（x 是**距左边缘**）⇒ 存出 `(-145, 90)`，卡片被甩到屏外。
                gravity = Gravity.TOP or Gravity.START
                val sx = TntgoConfig.posX(this@TntgoBatteryService)
                val sy = TntgoConfig.posY(this@TntgoBatteryService)
                if (sx != TntgoConfig.POS_UNSET && sy != TntgoConfig.POS_UNSET) {
                    x = sx; y = sy
                }
            }

            w.addView(card, p)
            params = p
            currentDisplayId = displayId
            // ★ 首次布局完成后才算默认位置（那时才拿得到卡片真实宽高）
            card.post { applyInitialPosition(card) }
            wm = w
            root = card
            Log.i(TAG, "✓ overlay 已挂到 display $displayId  标志=0x${Integer.toHexString(touchFlags())}")
        }.onFailure { Log.e(TAG, "✗ 挂 overlay 失败：${it.message}", it) }
    }

    private fun buildCard(ctx: Context): LinearLayout {
        val scale = TntgoConfig.fontScale(this)
        appliedFontScale = scale          // ★ 记下这次用的字号，轮询时比对
        val d = ctx.resources.displayMetrics.density
        val pad = (14 * d).toInt()

        fun TextView.sp(size: Float) = apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale)
        }

        val title = TextView(ctx).apply {
            text = "★ TNT GO"
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            setTypeface(typeface, Typeface.BOLD)
        }
        phoneView = TextView(ctx).apply {
            setTextColor(Color.WHITE); sp(13f)
        }
        tntView = TextView(ctx).apply {
            sp(15f); setTypeface(typeface, Typeface.BOLD)
        }
        powerView = TextView(ctx).apply {
            setTextColor(POWER_COLOR); sp(13f)
        }
        powerNoteView = TextView(ctx).apply {
            // ★ 诚实标注：这行字不是装饰，见类注释与 AQ §3.1
            //
            // ★★ AR12b：它现在是**多行**的（第二行回答"这个数是从哪来的"：
            //    `@ 亮度 66%（曲线）` / `★ 该处未测` / `亮度未知`）。
            //    ⚠️ 不要再加 `maxLines = 1` —— 那会把**最关键的那半句**截掉。
            text = "                （含对外供电）"
            setTextColor(Color.parseColor("#78909C")); sp(9f)
        }
        runtimeView = TextView(ctx).apply {
            setTextColor(RUNTIME_COLOR); sp(13f)
        }
        updatedView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#90A4AE")); sp(10f)
            // ★ 修 F1 的版式部分：这行会变长（数据陈旧时要加"⚠N 分钟前"），
            //   不限行数就会把卡片撑高、把别的行挤走。单行 + 省略号。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B0BEC5")); sp(10f)
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(phoneView); addView(tntView)
            addView(powerView); addView(powerNoteView); addView(runtimeView)
            addView(updatedView); addView(statusView)
        }

        applyCardBackgroundTo(card)
        attachTouchHandlers(card)

        renderPhone()
        renderTnt(reading = null, note = "启动中…")
        return card
    }

    /**
     * 按申报的触摸行为选标志。
     *
     * ★ 任务 AQ：本 mod 从 `none` 改成了 **`self`**（为了能长按拖动）。
     * ⇒ `FLAG_NOT_TOUCH_MODAL`：**只吃自己矩形内**的点击，矩形外照常穿透。
     *
     * ⚠️ 与亮度 mod 的差别：它的卡片**平时 INVISIBLE**（不可见 View 不参与触摸分发）
     * ⇒ 平时完全不挡。**本 mod 常驻可见** ⇒ 那块矩形会一直吃点击。
     * 用户 2026-09-14 已明确接受，缓解手段就是**可拖走**。
     */
    private fun touchFlags(): Int =
        if (touchMode == C.TOUCH_SELF) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    // ------------------------------------------------------------------ ★ 手势

    /**
     * 把「长按拖动 / 双击设置」接到卡片上（照 `mod-tntgo-brightness`）。
     *
     * ⚠️ 两个要点：
     * 1. **必须同时喂给 [gestures]** —— 手写长按判定容易和双击打架
     * 2. `ACTION_DOWN` 时 `OnTouchListener` **返回 false**，让 View 自己的
     *    `onTouchEvent` 正常走（长按检测在里面）；后续事件**照样**会派发给我们
     */
    private fun attachTouchHandlers(card: LinearLayout) {
        card.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                }

                MotionEvent.ACTION_MOVE -> if (dragging) {
                    dragTo(e.rawX - downRawX, e.rawY - downRawY)
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    applyCardBackground()
                    params?.let {
                        TntgoConfig.savePos(this, it.x, it.y)
                        Log.i(TAG, "★ 松手 → 卡片位置已存 (${it.x}, ${it.y})")
                    }
                    return@setOnTouchListener true
                }
            }
            dragging
        }
    }

    /**
     * 首次布局完成后定位置（**必须在 `post` 里做** —— 这时才拿得到卡片真实宽高）。
     *
     * - 没存过 ⇒ 算**默认位置**（**右下角、任务栏上方**）并存下来，语义从此唯一
     * - 存过 ⇒ 沿用，但**仍要夹一次**（换屏 / 旧值 / 分辨率变化都可能让它跑到屏外）
     */
    private fun applyInitialPosition(card: View) {
        val p = params ?: return
        val w = wm ?: return
        if (card.width == 0 || card.height == 0) {
            card.postDelayed({ applyInitialPosition(card) }, 100)
            return
        }
        val d = resources.displayMetrics.density
        val (offX, offY) = frameOffset()
        val minX = -offX
        val minY = -offY
        val maxX = (screenW - card.width - offX).coerceAtLeast(minX)
        val maxY = (screenH - card.height - offY).coerceAtLeast(minY)

        if (TntgoConfig.posX(this) == TntgoConfig.POS_UNSET ||
            TntgoConfig.posY(this) == TntgoConfig.POS_UNSET
        ) {
            // ★ 默认：**右下角**、任务栏上方（用户 2026-09-14 §4 要求保持）
            p.x = (screenW - card.width - (TntgoConfig.DEFAULT_RIGHT_INSET_DP * d).toInt() - offX)
                .coerceIn(minX, maxX)
            p.y = (screenH - card.height - (TntgoConfig.DEFAULT_BOTTOM_INSET_DP * d).toInt() - offY)
                .coerceIn(minY, maxY)
            TntgoConfig.savePos(this, p.x, p.y)
            Log.i(TAG, "卡片默认位置(右下角) → (${p.x}, ${p.y})  [屏 ${screenW}x$screenH 卡片 ${card.width}x${card.height}]")
        } else {
            p.x = p.x.coerceIn(minX, maxX)
            p.y = p.y.coerceIn(minY, maxY)
            Log.i(TAG, "卡片位置(沿用上次拖动) → (${p.x}, ${p.y})")
        }
        w.updateViewLayout(card, p)
    }

    /** 按手指位移挪窗口，**并夹在屏内**（拖出屏幕就找不回来了） */
    private fun dragTo(dx: Float, dy: Float) {
        val p = params ?: return
        val v = root ?: return
        val w = wm ?: return
        val (offX, offY) = frameOffset()

        // 窗口的 frame 左/上 = params.x/y + off ⇒ 钳制时要减掉这个 off，
        // 否则卡片能钻到状态栏/任务栏底下
        p.x = (startX + dx).toInt().coerceIn(-offX, (screenW - v.width - offX).coerceAtLeast(-offX))
        p.y = (startY + dy).toInt().coerceIn(-offY, (screenH - v.height - offY).coerceAtLeast(-offY))
        w.updateViewLayout(v, p)
    }

    /**
     * 窗口坐标与屏幕坐标的**偏移量**。
     *
     * ⚠️ 实测（坚果 A10）：`params.y = 750` 时窗口 frame 顶部在 **858** —— 差 **108px**，
     * 正是状态栏/刘海的高度 ⇒ **窗口坐标是相对【内容区】算的**。
     * 用「实际位置 − params」**现算**，不硬编码 108（换台机器就不一样了）。
     */
    private fun frameOffset(): Pair<Int, Int> {
        val v = root ?: return 0 to 0
        val p = params ?: return 0 to 0
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (loc[0] - p.x) to (loc[1] - p.y)
    }

    private fun applyCardBackground() {
        root?.let { applyCardBackgroundTo(it) }
    }

    /** ★ 圆角半透明黑；**拖动中描一圈琥珀色边**当作"抓住了"的反馈（与 perfmon/亮度同一套观感） */
    private fun applyCardBackgroundTo(view: View) {
        val d = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(0xCC1A1A1A.toInt())
            cornerRadius = 14 * d
            setStroke((1 * d).toInt(), 0x33FFFFFF)
            if (dragging) setStroke((2 * d).toInt(), 0xFFFFD54F.toInt())
        }
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "打开设置失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 工具

    private fun readOwnTouchMode(): String = runCatching {
        val si = packageManager.getServiceInfo(
            ComponentName(this, TntgoBatteryService::class.java), PackageManager.GET_META_DATA
        )
        si.metaData?.getString(C.META_TOUCH)?.trim().orEmpty()
    }.getOrDefault("").takeIf { it == C.TOUCH_SELF } ?: C.TOUCH_NONE

    private fun fallbackDisplayId(): Int =
        getSystemService(DisplayManager::class.java)
            .displays.maxByOrNull { it.displayId }?.displayId ?: Display.DEFAULT_DISPLAY

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MODE 组件", NotificationManager.IMPORTANCE_MIN)
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("MODE 组件运行中")
            .setContentText("TNT GO 电量")
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
//    Kotlin **每个类只允许一个** companion object，而本类已经有一个了。
// ═══════════════════════════════════════════════════════════════════════════
private const val ACTION_LAYER_CONTROL = "com.shware.mode.action.LAYER_CONTROL"
private const val EXTRA_LAYER_OP = "com.shware.mode.extra.LAYER_OP"
private const val EXTRA_LAYER_TARGET = "com.shware.mode.extra.LAYER_TARGET"
