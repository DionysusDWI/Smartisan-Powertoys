package com.shware.mode.mod.brightness

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
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

/**
 * ★★ MOD：**TNT GO 亮度键**（任务 AI）—— 契约服务 + TNT 屏上的 OSD 亮度卡片。
 *
 * ## 契约四件套（照 `mod-hello` / `mod-tntgo-battery` 抄）
 *
 * | # | 事 | 本类怎么做的 |
 * |---|---|---|
 * | 1 | 5 秒内 `startForeground` | [onStartCommand] 第一句 |
 * | 2 | `createDisplayContext` 拿 WindowManager | [attachOverlay] |
 * | 3 | `TYPE_APPLICATION_OVERLAY` | [buildCard] 的参数 |
 * | 4 | `onDestroy` 撤窗 | [onDestroy] |
 *
 * ## 本 mod 与电量 mod 的关键差别
 *
 * ★ **干活的不在本类**，在 [KeyFilterService]（无障碍按键过滤器）。
 * 本类只负责「**看得见**」：把当前亮度、串口回读、以及**每一种失败原因**画在 TNT 屏上。
 * ⇒ 即使宿主没拉起本 mod，只要无障碍开了，亮度键**照样能用**（只是没有 OSD）。
 *
 * ## ★ 为什么要 OSD（不是装饰）
 *
 * 背光变化**不进 framebuffer** —— 截图 / 录屏**永远看不出**
 * （.paper/07 §4）。所以"按下去了吗 / 生效了吗 / 现在几档"必须另有一个可见载体。
 */
class BrightnessModService : Service(), BrightnessCore.Listener {

    private object C {
        const val EXTRA_DISPLAY_ID = "com.shware.mode.extra.DISPLAY_ID"
        const val META_TOUCH = "com.shware.mode.MOD_TOUCH"
        const val TOUCH_NONE = "none"
        const val TOUCH_SELF = "self"
    }

    companion object {
        private const val TAG = "ModeMod/Bright"
        private const val CHANNEL_ID = "mode_mod_brightness"
        // ★ 2005（原来 2003）。★ 通知 ID 作用域是【包】不是进程 ——
        //   2003 与 mod-perfmon 撞了，两者同时跑会互相顶掉通知。
        //   全包分配表登记在 :app 的 ModContract「二·补」；
        //   ⚠️ mod 刻意不依赖 :app ⇒ 值只能手抄，改表不等于改这里。
        private const val NOTIF_ID = 2005
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null

    /** ★ 卡片的窗口参数（拖动时要改 x/y 并 `updateViewLayout`） */
    private var params: WindowManager.LayoutParams? = null
    private var bigView: TextView? = null
    private var mcuView: TextView? = null
    private var statusView: TextView? = null

    private var touchMode: String = C.TOUCH_NONE

    /**
     * ★★ **目标屏的尺寸**。
     *
     * ⚠️⚠️ **两条错路，2026-09-13 都踩过**：
     * 1. `resources.displayMetrics`（Service 的）⇒ 给的是**默认屏**（手机屏）
     * 2. `createDisplayContext(display).resources.displayMetrics` ⇒ **还是手机屏**
     *    （实测打出 `屏 1080x2142` —— `createDisplayContext` 的 Resources 并不会
     *     把 `DisplayMetrics` 换成那块屏的）
     *
     * ⇒ **只有直接问 `Display` 自己才对**：`display.getRealMetrics(dm)` ⇒ 实测 `2160×1440` ✅
     *
     * 用错尺寸的后果：默认位置算出 `y=1883`，而 TNT 屏只有 **1440** 高 ⇒ **卡片跑到屏外**，
     * 而且拖动的钳制也形同虚设（钳到 2340 去了）。
     */
    private var screenW = 0
    private var screenH = 0

    // ---- ★ 拖动状态（照 mod-perfmon）----
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private lateinit var gestures: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private var lastRenderedKeyCount = -1
    private var lastRamping = false

    private val hideRunnable = Runnable { root?.visibility = View.INVISIBLE }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
            Log.i(TAG, "USB 权限弹窗结果: granted=$granted")
            // 权限刚拿到 ⇒ 立刻查一次当前值，别让用户干等
            BrightnessCore.refresh()
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

    private val layerId = "tntgo.brightness"
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
        BrightnessCore.attach(this)
        touchMode = readOwnTouchMode()

        // ★ 手势（照 mod-perfmon）：长按 → 拖动（琥珀色描边反馈）／双击 → 打开设置
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // ⚠️ onDown 必须返回 true，否则后面的长按/双击都不派发
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                if (touchMode != C.TOUCH_SELF) return   // 申报为 none 时根本收不到触摸，防呆
                dragging = true
                applyCardBackground()
                Log.i(TAG, "★ 长按 → 进入拖动模式")
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.i(TAG, "★ 双击 → 打开设置")
                openSettings()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                show()      // ★ 点一下 = 续显 2.5 秒（免得刚想看就消失）
                return true
            }
        })

        ContextCompat.registerReceiver(
            this, usbReceiver,
            IntentFilter(TntgoBkl.ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val fromLauncher = intent?.getIntExtra(C.EXTRA_DISPLAY_ID, -1) ?: -1
        // ⚠️ 判据必须是 >= 0：手机屏的 id 就是 0，用 `> 0` 会把"画在手机屏上"误判成"没给"
        //    ⇒ 静默落到 fallback（TNT 虚拟屏）。详见 ModContract.EXTRA_DISPLAY_ID
        val displayId = if (fromLauncher >= 0) fromLauncher else fallbackDisplayId()

        // ★★★ AR12：**心跳的排程点必须在这里，不能在 `if (root == null)` 里**。
        //
        // ⚠️⚠️ 真实踩到的坑（2026-09-15，实机故障）：心跳原来写在
        //    `if (root == null) { … }` 里面，而 **`install -r` 重启进程后，
        //    无障碍服务会先于 `onStartCommand` 连上**，走 `setA11y → refresh →
        //    notifyListeners → publishBrightness` 把状态文件**写了一次**
        //    ⇒ 时间戳看起来正常，但**心跳从未被排程** ⇒ 30 秒后再无人更新
        //    ⇒ 电量侧连续判「亮度未知」，**而日志里一行异常都没有**。
        //
        // ★ 教训与 AR12 的纪律同源：**"文件里有一次写入"不等于"发布者在心跳"** ——
        //   一次性的写会让**新鲜度判据当场失效**，因为它恰好落进有效窗口。
        // ⇒ 排程点放在 `root == null` 之外，并先 `removeCallbacks`（幂等，
        //   多次 `onStartCommand` 不会叠加出多条心跳链）。
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, TntgoState.HEARTBEAT_MS)
        Log.i(TAG, "AR12：亮度心跳已排程（每 ${TntgoState.HEARTBEAT_MS / 1000}s 一次）")

        if (root == null) {
            Log.i(TAG, "启动：display=$displayId  触摸=$touchMode")
            attachOverlay(displayId)
            BrightnessCore.addListener(this)
            // ★ 启动即查一次当前值 —— 这是唯一可信的"现在几档"来源
            BrightnessCore.refresh()
        }
        return START_STICKY
    }

    /**
     * ★★★ AR12 **亮度心跳**。
     *
     * 每 [`TntgoState.HEARTBEAT_MS`] 重发一次当前亮度（**即使没变**）。
     * 电量侧据此区分两种情况：
     *
     * | 现象 | 含义 |
     * |---|---|
     * | 时间戳持续前进 | ★ 亮度 mod 在管这块屏 ⇒ 记的值**可信** |
     * | 时间戳停住 | ★★ 亮度 mod **不在了** ⇒ 显示「亮度未知」并降级 |
     *
     * ⚠️ 与 `TntgoState.VALID_MS`（= 心跳 × 3）必须对得上 ——
     *    心跳漏一拍不该立刻判死，连漏三拍才判。
     *
     * ## ★★ 为什么必须**自己留下日志**
     *
     * 这条心跳曾经**静默停摆 10 分钟**（见 `onStartCommand` 里那段踩坑记录）：
     * 状态文件的 `ts` 停住，而**整条链上一行日志都没有** ——
     * 只能在"电量侧一直说亮度未知"这个**远端的症状**上才看得出来。
     *
     * ⇒ ★ 每次心跳都记一笔（**含"这次为什么没发出去"**），
     *   前 3 次与每 10 次打印一次，既不刷屏、也不会再出现"静默停摆"。
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            heartbeatTicks++
            // ★★ `ok` 是**布尔状态**，不是"去猜那段文案里有没有 ok 字样" ——
            //    第一版我拿 `lastPublishReason` 做判据，文案是 `✓ 已写入…`，
            //    于是**每一次成功都被打成了"没有发布"**（真机日志当场看到）。
            val ok = BrightnessCore.republish()
            val verbose = heartbeatTicks <= 3 || heartbeatTicks % 10 == 0
            if (ok) {
                if (verbose) {
                    val s = BrightnessCore.current()
                    Log.i(TAG, "♥ 心跳 #$heartbeatTicks：${BrightnessCore.lastPublishReason}")
                }
            } else {
                // ★ 这里**不许静默** —— 没写出去必须能在日志里看见
                Log.w(TAG, "♥ 心跳 #$heartbeatTicks：**没有发布** —— ${BrightnessCore.lastPublishReason}")
            }
            handler.postDelayed(this, TntgoState.HEARTBEAT_MS)
        }
    }

    /** 心跳计数（诊断用：日志里看得出"还在跳"） */
    private var heartbeatTicks = 0

    override fun onDestroy() {

        unregisterLayerControl()
        handler.removeCallbacks(hideRunnable)
        // ★ AR12：心跳必须停 —— 否则服务已死却还在发"我还活着"
        handler.removeCallbacks(heartbeat)
        BrightnessCore.removeListener(this)
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching {
            root?.let { wm?.removeViewImmediate(it); Log.i(TAG, "overlay 已撤下") }
        }
        root = null; wm = null; params = null
        bigView = null; mcuView = null; statusView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 渲染

    override fun onBrightnessState(s: BrightnessCore.State) {
        handler.post { render(s) }
    }

    private fun render(s: BrightnessCore.State) {
        val bad = s.health == BrightnessCore.Health.NO_DEVICE ||
                s.health == BrightnessCore.Health.NO_PERMISSION ||
                s.health == BrightnessCore.Health.BUSY ||
                s.health == BrightnessCore.Health.FAILED

        val pct = s.ui
        bigView?.text = if (pct == null) "☀  --" else "☀  $pct%"
        bigView?.setTextColor(
            when {
                bad -> Color.parseColor("#EF9A9A")
                s.health == BrightnessCore.Health.PENDING -> Color.parseColor("#FFD54F")
                else -> Color.parseColor("#FFF59D")
            }
        )

        mcuView?.text = buildString {
            append("目标 MCU ")
            append(pct?.let { TntgoBkl.uiToMcu(it).toString() } ?: "—")
            append("   回读 +BKL=")
            append(s.mcu?.toString() ?: "—")
        }

        statusView?.text = buildString {
            if (!s.a11yConnected) {
                append("⚠ 无障碍未启用 ⇒ 亮度键收不到\n")
                append("   设置 → 无障碍 → TNT GO 亮度键\n")
            }
            if (s.note.isNotEmpty()) append(s.note)
            if (s.keyCount > 0) append("\n已接住 ${s.keyCount} 次按键")
        }

        // ★ 按键驱动的变化：亮 2.5 s 后自动隐藏（不挡 TNT 桌面）
        //   ⚠️ 出错时**不隐藏** —— 否则又是一个"静默失败"
        //   ⚠️ ★ 长按无极调节期间**也不隐藏** —— 否则调到一半卡片就没了（任务 AJ）
        if (s.keyCount != lastRenderedKeyCount || s.ramping != lastRamping) {
            lastRenderedKeyCount = s.keyCount
            lastRamping = s.ramping
            show()
        } else if (bad || !s.a11yConnected || s.ramping) {
            show()
        }
    }

    private fun show() {
        handler.removeCallbacks(hideRunnable)
        root?.visibility = View.VISIBLE
        val bad = BrightnessCore.health == BrightnessCore.Health.NO_DEVICE ||
                BrightnessCore.health == BrightnessCore.Health.NO_PERMISSION ||
                BrightnessCore.health == BrightnessCore.Health.BUSY ||
                BrightnessCore.health == BrightnessCore.Health.FAILED
        // ★ 长按调节中 / 用户正拖着 —— 都不排隐藏
        if (!bad && BrightnessCore.a11yConnected && !BrightnessCore.ramping && !dragging) {
            handler.postDelayed(hideRunnable, BrightnessCore.OSD_HOLD_MS)
        }
    }

    // ------------------------------------------------------------------ overlay

    private fun attachOverlay(displayId: Int) {
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId)
        if (display == null) { Log.w(TAG, "没有 display $displayId"); return }

        runCatching {
            val ctx = createDisplayContext(display)
            // ★★ 屏幕尺寸必须【直接问那块 Display】—— 见 [screenW] 的注释
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            Log.i(TAG, "目标屏尺寸：${screenW}×${screenH}（Display.getRealMetrics）")
            val w = ctx.getSystemService(WindowManager::class.java)
            val card = buildCard(ctx)
            val p = WindowManager.LayoutParams(
                // ⚠️ 必须 WRAP_CONTENT（MATCH_PARENT 会让触摸区变整屏）
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                touchFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                // ★★ **全程只用 `TOP|START`** —— 位置语义必须唯一。
                //
                // ⚠️ 踩过的坑（2026-09-13）：默认位置原本用 `BOTTOM|CENTER_HORIZONTAL`，
                //    那种 gravity 下 `params.x = 0` 表示**居中偏移**；
                //    而拖完存下来、下次用 `TOP|START` 恢复时，`x` 却表示**距左边缘**
                //    —— **两套坐标系混用** ⇒ 实测存出了 `(-145, 90)`，下次启动卡片被甩到屏外。
                //
                // ⇒ 现在：**一律 `TOP|START`**；默认位置在**首次布局拿到真实尺寸后**再算（见下面 `card.post`）。
                gravity = Gravity.TOP or Gravity.START
                val savedX = Prefs.posX(this@BrightnessModService)
                val savedY = Prefs.posY(this@BrightnessModService)
                if (savedX != Prefs.POS_UNSET && savedY != Prefs.POS_UNSET) {
                    x = savedX
                    y = savedY
                }
            }
            card.visibility = View.INVISIBLE      // ★ 先藏着，有按键/出错才亮
            w.addView(card, p)
            params = p

            // ★ 首次布局完成后：要么算出【默认位置】（底部居中、任务栏上方）并存下，
            //   要么把【恢复的旧位置】夹一次（免得旧值/换屏后把卡片留在屏外）。
            card.post { applyInitialPosition(card) }
            wm = w
            root = card
            Log.i(TAG, "✓ overlay 已挂到 display $displayId  标志=0x${Integer.toHexString(touchFlags())}")
        }.onFailure { Log.e(TAG, "✗ 挂 overlay 失败：${it.message}", it) }
    }

    private fun buildCard(ctx: Context): LinearLayout {
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        val title = TextView(ctx).apply {
            text = "★ TNT GO 亮度"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        bigView = TextView(ctx).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        }
        mcuView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#90A4AE")); textSize = 10f
        }
        statusView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B0BEC5")); textSize = 10f
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(bigView); addView(mcuView); addView(statusView)
        }
        // ★ 圆角底 + 拖动时描边（与 mod-perfmon 同一套观感）
        applyCardBackgroundTo(card)
        attachTouchHandlers(card)

        render(BrightnessCore.current())
        return card
    }

    /**
     * 本 mod 申报 `self`（用户 2026-09-13 要求可点击高亮 + 可拖动）。
     *
     * ⇒ `FLAG_NOT_TOUCH_MODAL`：**只吃自己矩形内**的点击，矩形外照常穿透。
     * ⚠️ 卡片**默认 INVISIBLE**（只在按键后亮 2.5 秒），而不可见的 View 不参与触摸分发
     * ⇒ **平时完全不挡**（这是把 `none` 换成 `self` 后仍然安全的原因）。
     */
    private fun touchFlags(): Int =
        if (touchMode == C.TOUCH_SELF) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    // ------------------------------------------------------------------ ★ 手势（照 mod-perfmon）

    /**
     * 把「长按拖动 / 双击设置 / 点击续显」接到卡片上。
     *
     * ⚠️ 两个要点（与 `mod-perfmon` 一致，那里踩过）：
     * 1. **必须同时喂给 [gestures]** —— 手写长按判定容易和双击打架
     * 2. `ACTION_DOWN` 时 `OnTouchListener` **返回 false**，让 View 自己的 `onTouchEvent`
     *    正常走（长按检测在里面）；后续事件**照样**会派发给我们 ——
     *    只要窗口收到了 DOWN，整个手势都归它，与 View 是否"消费"无关
     */
    private fun attachTouchHandlers(card: LinearLayout) {
        card.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(hideRunnable)   // ★ 摸到就别隐藏
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
                        Prefs.savePos(this, it.x, it.y)
                        Log.i(TAG, "★ 松手 → 卡片位置已存 (${it.x}, ${it.y})")
                    }
                    show()          // 拖完再按常规计时隐藏
                    return@setOnTouchListener true
                }
            }
            dragging
        }
    }

    /**
     * 首次布局完成后定位置（**必须在 `post` 里做** —— 这时才拿得到卡片的真实宽高）。
     *
     * - 没存过 ⇒ 算**默认位置**：底部居中、任务栏上方（并把结果存下来，语义从此唯一）
     * - 存过 ⇒ 直接沿用，但**仍要夹一次**（换屏 / 旧值 / 分辨率变化都可能让它跑到屏外）
     */
    private fun applyInitialPosition(card: View) {
        val p = params ?: return
        val w = wm ?: return
        if (card.width == 0 || card.height == 0) {
            Log.w(TAG, "卡片还没量出宽高，位置稍后再算")
            card.postDelayed({ applyInitialPosition(card) }, 100)
            return
        }
        val sw = screenW
        val sh = screenH
        val (offX, offY) = frameOffset()
        val minX = -offX
        val minY = -offY
        val maxX = (sw - card.width - offX).coerceAtLeast(minX)
        val maxY = (sh - card.height - offY).coerceAtLeast(minY)

        if (Prefs.posX(this) == Prefs.POS_UNSET || Prefs.posY(this) == Prefs.POS_UNSET) {
            // 默认：底部居中、任务栏上方
            //   ① 亮度 OSD 的习惯位置（不挡内容）
            //   ② 避开了 mod-tntgo-battery 的「右下角」
            //   ③ ⚠️ 离底边留 90px —— TNT 任务栏是**系统装饰层，在 overlay 之上**
            p.x = ((sw - card.width) / 2 - offX).coerceIn(minX, maxX)
            p.y = (sh - card.height - 90 - offY).coerceIn(minY, maxY)
            Prefs.savePos(this, p.x, p.y)
            Log.i(TAG, "卡片默认位置(底部居中) → (${p.x}, ${p.y})  [屏 ${sw}x${sh} 卡片 ${card.width}x${card.height}]")
        } else {
            p.x = p.x.coerceIn(minX, maxX)
            p.y = p.y.coerceIn(minY, maxY)
            Log.i(TAG, "卡片位置(沿用上次拖动) → (${p.x}, ${p.y})")
        }
        w.updateViewLayout(card, p)
    }

    /** 按手指位移挪窗口，**并夹在屏内**（拖出屏幕就找不回来了） */
    private fun dragTo(dx: Float, dy: Float) {        val p = params ?: return
        val v = root ?: return
        val w = wm ?: return
        val sw = screenW
        val sh = screenH
        val (offX, offY) = frameOffset()

        // 窗口的 frame 左/上 = params.x/y + off ⇒ 钳制时要减掉这个 off，
        // 否则卡片能钻到状态栏/任务栏底下（任务 T 实测差 108px）
        p.x = (startX + dx).toInt().coerceIn(-offX, (sw - v.width - offX).coerceAtLeast(-offX))
        p.y = (startY + dy).toInt().coerceIn(-offY, (sh - v.height - offY).coerceAtLeast(-offY))
        w.updateViewLayout(v, p)
    }

    /**
     * 窗口坐标与屏幕坐标的**偏移量**。
     *
     * ⚠️ 实测（坚果 A10）：`params.y = 750` 时窗口 frame 顶部在 **858** ——
     * 差 **108px**，正是状态栏/刘海的高度 ⇒ **窗口坐标是相对【内容区】算的**。
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

    /**
     * ★ 卡片底：**圆角**半透明黑；**拖动中描一圈琥珀色边**当作"抓住了"的反馈。
     * （与 `mod-perfmon` 同一套观感 —— 用户要求两边一致）
     */
    private fun applyCardBackgroundTo(view: View) {
        val d = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(0xCC1A1A1A.toInt())
            cornerRadius = 14 * d                       // ★ "圆润一点"
            setStroke((1 * d).toInt(), 0x33FFFFFF)      // 常态一圈极淡的边，显得精致
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
            ComponentName(this, BrightnessModService::class.java), PackageManager.GET_META_DATA
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
            .setContentText("TNT GO 亮度")
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
