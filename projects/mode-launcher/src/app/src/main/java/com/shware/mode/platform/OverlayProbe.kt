package com.shware.mode.platform

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * ★ TNT 屏 overlay 探针 —— 用来回答**清单 #7**：
 * **overlay 画在 TNT 屏上时，z-order 是什么？**
 *
 * 为什么这块砖最要紧：`.paper/04` §2.5 已实测「**外接屏上没有任何系统窗口装饰**」
 * ⇒ 标题栏 / Dock / 拖拽手柄**全部要自绘**（数周级工作量）。
 * 自绘的唯一载体就是 overlay。**它的 z-order 不成立，整条路线要重估。**
 *
 * 平台事实：`createDisplayContext(ext).createWindowContext(TYPE_APPLICATION_OVERLAY)`
 * 是官方姿势；需 `SYSTEM_ALERT_WINDOW`（appop，可 adb 授予）。
 */
class OverlayProbe(private val context: Context) {

    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 在指定显示器上显示一个 overlay。
     *
     * @return null = 成功；否则是错误描述
     */
    fun show(displayId: Int, text: String, x: Int = 60, y: Int = 60): String? {
        if (isShowing) return "已经有一个 overlay 在显示（先关掉）"

        // ⚠️ 实测教训（小米 17 Pro Max / HyperOS 3）：
        //   `Settings.canDrawOverlays()` **与真正 addView 的结果会不一致** ——
        //   同一进程内在状态行读到 true、在 show() 里读到 false（appops 已 allow）。
        //   ⇒ **不做前置门禁**，直接尝试；让真正的异常说话。
        val precheck = canDrawOverlays()
        Log.i(TAG, "canDrawOverlays()=$precheck —— 仅记录，不作为门禁")

        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return "无此显示器: id=$displayId"

        return runCatching {
            val displayContext = context.createDisplayContext(display)
            // API 30+ 才有 createWindowContext；29 退回 displayContext
            val windowContext: Context =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    displayContext.createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
                    )
                } else {
                    displayContext
                }

            val w = windowContext.getSystemService(WindowManager::class.java)
                ?: return "拿不到 WindowManager"

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ⚠️ 探测框是【纯展示】的，所以必须带 FLAG_NOT_TOUCHABLE ——
                //    否则它会把它盖住的那块屏幕的点击全吃掉（哪怕 TextView 根本不可点）。
                //    详见 ModContract §四。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }

            val v = TextView(windowContext).apply {
                setText(text)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6D32F2F.toInt())   // 红底，肉眼好认
                textSize = 13f
                setPadding(28, 18, 28, 18)
            }

            w.addView(v, p)
            wm = w
            view = v
            params = p
            Log.i(TAG, "overlay 已加到 display $displayId (${display.name}) @($x,$y)")
            null
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 移动（同时验证 overlay 是不是可交互的） */
    fun moveTo(x: Int, y: Int): String? = runCatching {
        val p = params ?: return "没有 overlay"
        val v = view ?: return "没有 overlay"
        val w = wm ?: return "没有 WindowManager"
        p.x = x; p.y = y
        w.updateViewLayout(v, p)
        null
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    fun hide(): String? = runCatching {
        val w = wm ?: return null
        val v = view ?: return null
        w.removeViewImmediate(v)
        wm = null; view = null; params = null
        null
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    companion object {
        private const val TAG = "Mode/OverlayProbe"
    }
}
