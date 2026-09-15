package com.shware.tntgo.battery

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 外接屏（TNT GO）上的电量卡片。
 *
 * MVP 采用 Presentation（零权限、系统标准 API）。
 * 若在 TNT OS 上被其窗口管理器接管/限制，可切换到 display-context overlay
 * （TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW）方案，见 docs/04。
 */
class BatteryPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var tvPhone: TextView
    private lateinit var tvScreen: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_battery)

        tvPhone = findViewById(R.id.tvPhone)
        tvScreen = findViewById(R.id.tvScreen)

        window?.let { w ->
            // 透明背景 + 右上角悬浮
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.TOP or Gravity.END)
            w.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // 不抢焦点、不拦截触摸，触摸继续传给下层
            w.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
    }

    fun update(phoneLevel: Int, phoneCharging: Boolean, tntLevel: Int?) {
        if (!isShowing) return

        tvPhone.text = buildString {
            append("手机 ")
            append(if (phoneLevel in 0..100) "$phoneLevel%" else "—")
            if (phoneCharging) append(" ⚡")
        }
        tvScreen.text = "屏幕 " + (tntLevel?.let { "$it%" } ?: "—")
    }
}