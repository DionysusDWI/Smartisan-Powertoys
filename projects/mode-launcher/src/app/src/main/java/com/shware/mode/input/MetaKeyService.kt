package com.shware.mode.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * ★ 全局按键观察器 —— 用来回答第 5 件事：
 * **`KEY_RIGHTMETA` 能不能被我们截到？**
 *
 * 为什么必须做：`.paper/03` §2.2 已确认 **TNT 快捷键的真机列是 `<win>` 列**
 * （铁证 `custom_key_desktop = KEYCODE_F11$META_META_ON+KEYCODE_D`），
 * 而 `.paper/04` §2.7 实测 **TNT GO 键盘上那两个锤子 logo 键 = `KEY_RIGHTMETA`**。
 * ⇒ **TNT 快捷键的修饰键就是它；截不到它，快捷键表就复刻不了。**
 *
 * 无 root 下的路径：`AccessibilityService` + `FLAG_REQUEST_FILTER_KEY_EVENTS`。
 * （`.paper/04` §2.5 已实测：NLS / 无障碍受限权限 **可授予**）
 *
 * ⚠️ 本版**只观察、不吞键**（`onKeyEvent` 恒返回 false）——
 * 最小框架阶段先证明「看得见」，别把用户按键搞坏。
 */
class MetaKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.eventTypes = 0
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 0
        serviceInfo = info
        emit("★ 无障碍服务已连接 flags=0x${info.flags.toString(16)}")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "?${event.action}"
        }
        val meta = describeMeta(event.metaState)
        val interesting = event.keyCode == KeyEvent.KEYCODE_META_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_META_LEFT ||
            event.isAltPressed || event.isCtrlPressed || event.isShiftPressed

        val line = "$action  $name${if (meta.isEmpty()) "" else "  +[$meta]"}"
        if (interesting) {
            emit("★ $line")
        } else {
            Log.v(TAG, line)   // 普通键只进 logcat，不刷屏
        }
        // ★ 恒返回 false = 只观察，不拦截（最小框架阶段不改变系统行为）
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        emit("无障碍服务已断开")
        super.onDestroy()
    }

    private fun describeMeta(metaState: Int): String {
        val sb = StringBuilder()
        if (metaState and KeyEvent.META_META_ON != 0) sb.append("META_ON ")
        if (metaState and KeyEvent.META_ALT_ON != 0) sb.append("ALT ")
        if (metaState and KeyEvent.META_CTRL_ON != 0) sb.append("CTRL ")
        if (metaState and KeyEvent.META_SHIFT_ON != 0) sb.append("SHIFT ")
        return sb.toString().trim()
    }

    private fun emit(s: String) {
        Log.i(TAG, s)
        onEvent?.invoke(s)
    }

    companion object {
        private const val TAG = "Mode/MetaKey"

        /** UI 挂这个回调就能实时看到按键 */
        @Volatile
        var onEvent: ((String) -> Unit)? = null
    }
}
