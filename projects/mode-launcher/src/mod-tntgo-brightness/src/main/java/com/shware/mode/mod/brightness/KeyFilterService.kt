package com.shware.mode.mod.brightness

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * ★★★ 本 mod 的核心机关：**无障碍按键过滤器**。
 *
 * ## 为什么是这条路（而不是 Shizuku / 抢焦点窗口）
 *
 * | 手段 | 能不能拿到 TNT GO 的亮度键 |
 * |---|---|
 * | 普通 app 读 `/dev/input` | ❌ SELinux 拦（`u:r:untrusted_app`） |
 * | shell 读 `/dev/input` | ✅ 能（`getevent` 实测抓到过 —— 见 `.ref/tntgo_brightness_keys/`）<br/>⚠️ **但 shell 开不了 USB 串口**（`/dev/bus/usb` 是 `root:usb`）⇒ 要跨进程搭桥 |
 * | ★ `AccessibilityService` + `canRequestFilterKeyEvents` | ✅★ **官方 API / 无 root / 不抢焦点 / 自己就能发 AT** |
 * | 可获焦的 overlay 收键 | ⚠️ 会抢走焦点，破坏 TNT 操作 |
 *
 * ⇒ 选第三条。**代价**：只能**人手**在「设置 → 无障碍」里启用一次（adb 授不了）。
 *
 * ## 实测依据（2026-09-13，getevent 原始抓包）
 *
 * TNT GO 键盘上的两个亮度键**不发 F 键码**，而是走 **Consumer Control 设备**：
 * ```
 * MSC_SCAN 000c0070 → KEY_BRIGHTNESSDOWN    ← "调小"
 * MSC_SCAN 000c006f → KEY_BRIGHTNESSUP      ← "调大"
 * ```
 * 系统本来就会接它们，但 TNT GO 的背光链路（casthal / Boston）在本机是死的，
 * 所以只会弹「暂不支持该设备的亮度调节」（.paper/07 §2）。
 *
 * ## ⚠️ 这里有个**必须实测**的悬念
 *
 * `KEYCODE_BRIGHTNESS_*` 是**很早期就可能被策略层接走**的键
 * （`PhoneWindowManager.interceptKeyBeforeQueueing`）。
 * **本服务到底看不看得到它们，只能真机按了才知道** ——
 * 所以 [onKeyEvent] 对**每一个**按键都打日志，一次按键就能判定。
 */
class KeyFilterService : AccessibilityService() {

    companion object {
        private const val TAG = "ModeMod/Bright"

        /** ★ 亮度键（本任务的主角） */
        private const val KEY_BRIGHTNESS_DOWN = KeyEvent.KEYCODE_BRIGHTNESS_DOWN   // 220
        private const val KEY_BRIGHTNESS_UP = KeyEvent.KEYCODE_BRIGHTNESS_UP       // 221

        /** 用户口中的 "F10 / F12" —— 实测**不发这两个码**，留着当兼容兜底 */
        private const val KEY_F10 = KeyEvent.KEYCODE_F10    // 131
        private const val KEY_F12 = KeyEvent.KEYCODE_F12    // 133
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "★★★★ 无障碍服务已连接 —— 按键过滤器就绪")
        BrightnessCore.attach(this)
        BrightnessCore.setA11y(true)
    }

    override fun onDestroy() {
        Log.w(TAG, "无障碍服务已断开")
        BrightnessCore.setA11y(false)      // ★ 内部会收尾未结束的长按
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
        BrightnessCore.setA11y(false)
    }

    /** 本服务不看窗口内容 —— 只关心按键 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不用 */ }

    /**
     * ★ 全局硬件按键入口。
     *
     * ## 交互（任务 AJ，按用户规格）
     *
     * - **`ACTION_DOWN`（首次）** ⇒ [BrightnessCore.onKeyDown]：**立即短按步进**，并开始长按计时
     * - **`ACTION_DOWN`（`repeatCount>0`）** ⇒ ⚠️ **本机不会出现**（AJ1 实测 50 个事件 `repeat` 全为 0），
     *   但**防御性忽略** —— 真出现了也不能当成新的按下
     * - **`ACTION_UP`** ⇒ [BrightnessCore.onKeyUp]：结束短按 / 停掉无极调节
     *
     * @return `true` = **吃掉**这个键（不再往下发给应用）；`false` = 放行
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        val dir = when (code) {
            KEY_BRIGHTNESS_DOWN, KEY_F10 -> -1
            KEY_BRIGHTNESS_UP, KEY_F12 -> +1
            else -> 0
        }
        if (dir == 0) return false          // ★ 其它键一律放行，绝不干扰正常打字

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // ↓↓↓ 这一行是"到底看不看得到"的判据：按一下键，logcat 里必须有它 ↓↓↓
                Log.i(
                    TAG,
                    "收到按键 keyCode=$code(${KeyEvent.keyCodeToString(code)}) " +
                            "repeat=${event.repeatCount} scanCode=${event.scanCode} device=${event.deviceId}"
                )
                if (event.repeatCount > 0) {
                    // ★ 本机不该出现；出现了也只当"还按着"，绝不再跳一档
                    Log.d(TAG, "  （自动重复事件，忽略）")
                } else {
                    BrightnessCore.onKeyDown(this, dir)
                }
            }

            KeyEvent.ACTION_UP -> {
                Log.d(TAG, "松开 keyCode=$code")
                BrightnessCore.onKeyUp()
            }
        }
        return true          // ★ 吃掉，免得系统再去弹「暂不支持该设备的亮度调节」
    }
}
