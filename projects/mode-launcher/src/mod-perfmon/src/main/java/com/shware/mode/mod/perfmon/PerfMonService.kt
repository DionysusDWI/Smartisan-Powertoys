package com.shware.mode.mod.perfmon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

/**
 * ★★★ MOD：**手机侧系统性能数据窗口** —— 用户 2026-09-11 点名的第二个功能组件。
 *
 * > 「为**在 TNT 运行时的手机侧**添加**系统性能数据窗口**的这种悬浮组件。」
 *
 * ## 卡片长这样（宽度写死 168 dp）
 * ```
 * ┌────────────────────────┐
 * │ ★ 性能          49°C   │  ← 标题行（温度可关）
 * │ 内存 ▓▓▓▓▓▓▓░░░  70%   │  ← 迷你进度条 ×N（可勾选）
 * │ 存储 ▓▓▓▓░░░░░░  41%   │
 * │ 电量 ▓▓▓▓▓▓▓▓▓░  93% ↑ │
 * │ CPU  ▓▓▓▓▓░░░░░  64%   │
 * │ ↓303M ↑149M  825–2323M │  ← 页脚（网络/频率，可关）
 * └────────────────────────┘
 * ```
 *
 * ## ★★ 手势（用户 2026-09-12 追加）
 *
 * | 手势 | 行为 |
 * |---|---|
 * | **长按** | 进入拖动模式（**边框变亮**给反馈），拖到想放的位置松手 ⇒ **位置持久化** |
 * | **双击** | 打开设置（[SettingsActivity]，勾选显示哪些指标） |
 *
 * ## ⚠️⚠️ 代价必须显式知道：**能拖 ⇒ 必须收触摸**
 *
 * 拖动要求窗口能收到触摸 ⇒ `MOD_TOUCH` 从 `none` 变成 **`self`**：
 *
 * | | `none`（旧） | **`self`（现在）** |
 * |---|---|---|
 * | 标志 | `NOT_FOCUSABLE \| NOT_TOUCHABLE` = `0x18` | `NOT_FOCUSABLE \| NOT_TOUCH_MODAL` = `0x28` |
 * | 卡片矩形内的点击 | 完全穿透 | ★ **被卡片吃掉** |
 *
 * ⇒ 这符合用户定的第②条规则（"有交互的 overlay 不能漏到后面"），
 * 但**画在手机主屏上是有代价的** —— **缓解办法就是可拖动本身：挡了就拖走。**
 *
 * ## 另两条设计
 *
 * - ★ **只在 TNT 运行时显示**（用户原话就是"在 TNT 运行时的手机侧"）；
 *   不显示时**一个窗口都不占**，也顺带省掉每秒采样
 * - **1 s 刷新**，纯 `/proc` + `/sys` 读（微秒级，不伤续航）
 *
 * ## 契约四件套（照 [com.shware.mode.mod.ModContract] 抄，见 `mod-hello`）
 *
 * 1. `onStartCommand` 里 5 秒内 `startForeground`
 * 2. `createDisplayContext` 拿 WindowManager
 * 3. `TYPE_APPLICATION_OVERLAY`
 * 4. `onDestroy` 撤窗
 */
class PerfMonService : Service() {

    // ------------------------------------------------------------------ ★ 契约（抄自 ModContract）

    private object C {
        const val META_TOUCH = "com.shware.mode.MOD_TOUCH"
        const val META_TARGET = "com.shware.mode.MOD_TARGET"
        const val TOUCH_NONE = "none"
        const val TOUCH_SELF = "self"
        const val TARGET_PHONE = "phone"
    }

    companion object {
        private const val TAG = "ModeMod/Perf"
        private const val CHANNEL_ID = "mode_mod_perf"
        // ★ 2003 —— 本 ID 保持不变。★ 通知槽位作用域是【包】不是进程；
        //   原先 mod-tntgo-brightness 也用 2003（两者同时跑会互相顶掉通知），
        //   2026-09-15 已把【它】改成 2005。全包分配表见 :app 的 ModContract「二·补」。
        private const val NOTIF_ID = 2003

        /** 性能数据 1 s 一刷 */
        private const val TICK_MS = 1_000L

        /**
         * ★ **只在 TNT 运行时显示**（用户原话就是"在 TNT 运行时的手机侧"）。
         * 想常显就把这里改成 `false`。
         */
        private const val ONLY_WHEN_TNT = true

        private const val FIRST_VIRTUAL_DISPLAY_ID = 100000

        /** ★ 卡片固定宽度（dp）—— 写死就不会被长内容撑爆（旧版踩过：800px 宽） */
        private const val CARD_WIDTH_DP = 168

        /** 默认位置（没拖过时）：右上角，让开状态栏 */
        private const val DEFAULT_MARGIN_DP = 8
        private const val DEFAULT_TOP_DP = 140
    }

    /** 一条进度条要更新的三块 */
    private class BarRow(val fill: View, val rest: View, val value: TextView)

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val barRows = LinkedHashMap<String, BarRow>()
    private var tempView: TextView? = null
    private var footerView: TextView? = null

    private var touchMode: String = C.TOUCH_NONE
    private var target: String = C.TARGET_PHONE

    private lateinit var cfg: PerfConfig
    private lateinit var metrics: PerfMetrics
    private lateinit var displayManager: DisplayManager
    private lateinit var gestures: GestureDetector

    private val handler = Handler(Looper.getMainLooper())

    /** 现在有没有把窗口挂上去 */
    private var attached = false

    /** 已应用过的配置版本 —— 变了就重建卡片 */
    private var appliedCfgVer = -1

    // ---- 拖动状态 ----
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (attached) {
                checkConfigChanged()
                if (attached) render()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** TNT 屏增删 → 决定显不显 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncVisibility()
        override fun onDisplayRemoved(displayId: Int) = syncVisibility()
        override fun onDisplayChanged(displayId: Int) = syncVisibility()
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

    private val layerId = "perf.mon"
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
        cfg = PerfConfig(this)
        metrics = PerfMetrics(this)
        displayManager = getSystemService(DisplayManager::class.java)
        touchMode = readOwnMeta(C.META_TOUCH).takeIf { it == C.TOUCH_SELF } ?: C.TOUCH_NONE
        target = readOwnMeta(C.META_TARGET).takeIf { it.isNotEmpty() } ?: C.TARGET_PHONE

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // ⚠️ onDown 必须返回 true，否则后面的长按/双击都不派发
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

        displayManager.registerDisplayListener(displayListener, handler)
        // ★ 记下初始版本，否则启动后第一次 tick 会以为"配置变了"而白重建一次
        appliedCfgVer = cfg.version
        Log.i(TAG, "启动：target=$target 触摸=$touchMode  onlyWhenTnt=$ONLY_WHEN_TNT  指标=${cfg.enabled}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        syncVisibility()
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {

        unregisterLayerControl()
        handler.removeCallbacks(ticker)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 显隐

    private fun tntPresent(): Boolean =
        displayManager.displays.any { it.displayId >= FIRST_VIRTUAL_DISPLAY_ID }

    /** 目标屏 id：手机屏恒为 0（本 mod 申报 `MOD_TARGET=phone`） */
    private fun targetDisplayId(): Int =
        if (target == C.TARGET_PHONE) Display.DEFAULT_DISPLAY else fallbackDisplayId()

    private fun syncVisibility() {
        val should = !ONLY_WHEN_TNT || tntPresent()
        when {
            should && !attached -> {
                Log.i(TAG, "TNT 在 ⇒ 挂窗口到 display ${targetDisplayId()}")
                attach(targetDisplayId())
            }
            !should && attached -> {
                Log.i(TAG, "TNT 不在 ⇒ 撤窗口")
                detach()
            }
        }
    }

    // ------------------------------------------------------------------ 配置变化

    /**
     * 用户改了设置（勾选 / 复位位置）⇒ **整块重建卡片**。
     *
     * 比逐项比对省事且不会漏 —— 卡片很小，重建的开销可以忽略。
     */
    private fun checkConfigChanged() {
        if (cfg.version == appliedCfgVer) return
        Log.i(TAG, "配置变了 (v$appliedCfgVer → v${cfg.version}) ⇒ 重建卡片")
        appliedCfgVer = cfg.version
        if (!attached) return
        val d = targetDisplayId()
        detach()
        if (!ONLY_WHEN_TNT || tntPresent()) attach(d)
    }

    // ------------------------------------------------------------------ overlay

    private fun attach(displayId: Int) {
        val display = displayManager.getDisplay(displayId)
        if (display == null) { Log.w(TAG, "没有 display $displayId"); return }

        runCatching {
            val ctx = createDisplayContext(display)
            val w = ctx.getSystemService(WindowManager::class.java)
            val card = buildCard(ctx)

            val d = resources.displayMetrics.density
            val screenW = resources.displayMetrics.widthPixels
            val cardW = (CARD_WIDTH_DP * d).toInt()

            // ★ gravity 用 TOP|START：x/y 就是"距左上角的绝对像素"，
            //   拖动时方向直观（END 的 x 是"距右边距"，拖起来是反的）
            val saved = cfg.pos
            val p = WindowManager.LayoutParams(
                cardW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                touchFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = saved?.first ?: (screenW - cardW - (DEFAULT_MARGIN_DP * d).toInt())
                y = saved?.second ?: (DEFAULT_TOP_DP * d).toInt()
            }

            w.addView(card, p)
            wm = w
            root = card
            params = p
            attached = true
            Log.i(TAG, "✓ overlay 已挂到 display $displayId  标志=0x${Integer.toHexString(touchFlags())}" +
                    "  @(${p.x},${p.y})  指标=${cfg.enabled}")
        }.onFailure { Log.e(TAG, "✗ 挂 overlay 失败：${it.message}", it) }
    }

    private fun detach() {
        runCatching { root?.let { wm?.removeViewImmediate(it) } }
        root = null; wm = null; params = null
        barRows.clear(); tempView = null; footerView = null
        dragging = false
        attached = false
    }

    // ------------------------------------------------------------------ 卡片

    private fun buildCard(ctx: Context): LinearLayout {
        val d = resources.displayMetrics.density
        val pad = (9 * d).toInt()
        val on = cfg.enabled

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(buildHeader(ctx))
        }

        for (key in PerfMetrics.BAR_LABELS) {
            if (key in on) card.addView(barRow(ctx, key))
        }

        if (PerfMetrics.TEXT_LABELS.any { it in on }) {
            footerView = TextView(ctx).apply {
                setTextColor(0xFF90A4AE.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                maxLines = 2
                setPadding(0, (3 * d).toInt(), 0, 0)
            }
            card.addView(footerView)
        }

        applyCardBackgroundTo(card)
        attachTouchHandlers(card)

        render()
        return card
    }

    private fun buildHeader(ctx: Context): LinearLayout {
        val title = TextView(ctx).apply {
            text = "★ 性能"
            setTextColor(0xFFFFD54F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempView = if ("温度" in cfg.enabled) TextView(ctx).apply {
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        } else null

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            tempView?.let { addView(it) }
        }
    }

    /** 一条：`标签  ▓▓▓▓░░░░  70%` */
    private fun barRow(ctx: Context, key: String): LinearLayout {
        val d = resources.displayMetrics.density
        val barH = (6 * d).toInt()
        val radius = barH / 2f

        val label = TextView(ctx).apply {
            text = key
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            width = (30 * d).toInt()
        }

        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, barH, 1f)
            background = GradientDrawable().apply { cornerRadius = radius; setColor(0x33FFFFFF) }
        }
        val fill = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, barH, 0f)
            background = GradientDrawable().apply { cornerRadius = radius; setColor(0xFF66BB6A.toInt()) }
        }
        val rest = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, barH, 100f) }
        track.addView(fill); track.addView(rest)

        val value = TextView(ctx).apply {
            setTextColor(0xFFECEFF1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.END
            width = (40 * d).toInt()
        }

        barRows[key] = BarRow(fill, rest, value)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (1.5f * d).toInt(), 0, (1.5f * d).toInt())
            addView(label); addView(track); addView(value)
        }
    }

    // ------------------------------------------------------------------ 手势

    /**
     * 把「长按拖动 / 双击设置」接到卡片上。
     *
     * ⚠️ 两个要点：
     * 1. **必须同时喂给 [gestures]** —— 手写长按判定容易和双击打架
     * 2. `ACTION_DOWN` 时 `OnTouchListener` **返回 false**，让 View 自己的
     *    `onTouchEvent` 正常走（长按检测在里面）；后续事件**照样**会派发给我们 ——
     *    只要窗口收到了 DOWN，整个手势都归它，与 View 是否"消费"无关
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
                        cfg.savePos(it.x, it.y)
                        Log.i(TAG, "★ 松手 → 位置已存 (${it.x}, ${it.y})")
                    }
                    return@setOnTouchListener true
                }
            }
            dragging
        }
    }

    /** 按手指位移挪窗口，**并夹在屏内**（拖出屏幕就找不回来了） */
    private fun dragTo(dx: Float, dy: Float) {
        val p = params ?: return
        val v = root ?: return
        val w = wm ?: return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val (offX, offY) = frameOffset()

        // 窗口的 frame 左/上 = params.x/y + off ⇒ 钳制时要减掉这个 off，
        // 否则卡片能钻到状态栏底下（实测差 108px）
        p.x = (startX + dx).toInt().coerceIn(-offX, (sw - v.width - offX).coerceAtLeast(-offX))
        p.y = (startY + dy).toInt().coerceIn(-offY, (sh - v.height - offY).coerceAtLeast(-offY))
        w.updateViewLayout(v, p)
    }

    /**
     * 窗口坐标与屏幕坐标的**偏移量**。
     *
     * ⚠️ 实测（坚果 A10）：`params.y = 750` 时窗口 frame 顶部在 **858** ——
     * 差 **108px**，正是状态栏/刘海的高度。
     * ⇒ **窗口坐标是相对【内容区】算的，不是屏幕原点。**
     * 不修正的话，卡片会被允许往下多拖 108px（钻到导航栏底下）。
     *
     * 用「实际位置 − params」现算，不硬编码 108（换台机器就不一样了）。
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

    /** 卡片底：半透明黑 + 圆角；**拖动中描一圈琥珀色边**当作"抓住了"的反馈 */
    private fun applyCardBackgroundTo(view: View) {
        val d = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(0xA6000000.toInt())
            cornerRadius = 8 * d
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

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        if (!attached) return
        val ms = runCatching { metrics.sample() }.getOrElse { emptyList() }
        val byLabel = ms.associateBy { it.label }

        val failures = ArrayList<String>()
        val caveats = ArrayList<String>()

        for (key in PerfMetrics.BAR_LABELS) {
            val row = barRows[key] ?: continue     // 没勾选的不会在 map 里
            val m = byLabel[key]
            when {
                m == null || !m.ok -> {
                    setBar(row, 0, 0xFF546E7A.toInt())
                    row.value.text = "—"
                    failures += "$key ${m?.note ?: "无数据"}"
                }

                else -> {
                    val pct = m.pct ?: 0
                    // ★ 电量反着配色：满电是好事（绿），内存/存储/CPU 满了才是坏事（红）
                    val color = if (key == "电量") colorForBattery(pct) else colorForLoad(pct)
                    setBar(row, pct, color)
                    row.value.text = m.text
                    // ★ ok=true 时的 note 是【保留说明】（如 CPU 的"均频代理"）——
                    //   必须在页脚标出来，不能让人以为那是真·使用率
                    m.note?.let { caveats += "$key $it" }
                }
            }
        }

        tempView?.let { tv ->
            val t = byLabel["温度"]
            if (t != null && t.ok) {
                tv.text = t.text
                tv.setTextColor(colorForLoad(t.pct ?: 0))
                t.note?.let { caveats += "温度 $it" }
            } else {
                tv.text = "—"
                tv.setTextColor(0xFF546E7A.toInt())
                failures += "温度 ${t?.note ?: "无数据"}"
            }
        }

        footerView?.let { fv ->
            val parts = ArrayList<String>()
            for (k in listOf("网络", "频率")) {
                if (k !in cfg.enabled) continue
                val m = byLabel[k] ?: continue
                if (m.ok) { parts += m.text; m.note?.let { caveats += "$k $it" } }
                else failures += "$k ${m.note}"
            }
            val all = parts + caveats + failures
            fv.text = all.joinToString("  ")
            fv.setTextColor(
                when {
                    failures.isNotEmpty() -> 0xFFFFAB91.toInt()   // 橙：有读不到的
                    caveats.isNotEmpty() -> 0xFFFFE082.toInt()    // 淡黄：有保留说明
                    else -> 0xFF90A4AE.toInt()                     // 灰：一切正常
                }
            )
        }
    }

    private fun setBar(row: BarRow, pct: Int, color: Int) {
        val p = pct.coerceIn(0, 100)
        (row.fill.layoutParams as LinearLayout.LayoutParams).weight = p.toFloat()
        (row.rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - p).toFloat()
        (row.fill.background as? GradientDrawable)?.setColor(color)
        row.fill.requestLayout()
    }

    /** 占用率配色：越高越糟 */
    private fun colorForLoad(pct: Int): Int = when {
        pct >= 85 -> 0xFFEF5350.toInt()   // 红
        pct >= 60 -> 0xFFFFB74D.toInt()   // 橙
        else -> 0xFF66BB6A.toInt()        // 绿
    }

    /** 电量配色：越高越好（反着来） */
    private fun colorForBattery(pct: Int): Int = when {
        pct <= 15 -> 0xFFEF5350.toInt()
        pct <= 35 -> 0xFFFFB74D.toInt()
        else -> 0xFF66BB6A.toInt()
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 按申报的触摸行为选标志 —— 与 `mod-hello` 同一套（详见 `ModContract` §四）。
     *
     * ★ 本 mod 现在申报 **`self`**（因为要能拖动）⇒ `NOT_FOCUSABLE | NOT_TOUCH_MODAL`
     * ⇒ **卡片矩形内的点击会被自己吃掉**，矩形外照常穿透。
     * 这是"能拖"的必然代价，缓解办法就是**拖走**。
     */
    private fun touchFlags(): Int =
        if (touchMode == C.TOUCH_SELF) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    private fun readOwnMeta(key: String): String = runCatching {
        packageManager.getServiceInfo(
            ComponentName(this, PerfMonService::class.java), PackageManager.GET_META_DATA
        ).metaData?.getString(key)?.trim().orEmpty()
    }.getOrDefault("")

    private fun fallbackDisplayId(): Int =
        displayManager.displays.maxByOrNull { it.displayId }?.displayId ?: Display.DEFAULT_DISPLAY

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MODE 组件", NotificationManager.IMPORTANCE_MIN)
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("MODE 组件运行中")
            .setContentText("手机性能（仅 TNT 运行时显示）")
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
