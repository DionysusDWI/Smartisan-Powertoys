package com.shware.mode.input

import android.os.Handler
import android.os.Looper
import com.shware.mode.core.InputDevice
import com.shware.mode.core.KeyCodec
import com.shware.mode.shell.ShellGateway

/**
 * ★ 按键流会话（App 侧）—— 把三层缝在一起：
 *
 * ```
 * UserService(shell)   getevent -lt <dev> 的原始行
 *      ↓ Binder oneway 回调（跑在 binder 线程，不阻塞 shell 侧读线程）
 * KeyCodec.Decoder     行 → KeyStroke（★ 以 HID usage 为主键，不是 Linux 键码）
 *      ↓ 切主线程
 * UI                   onStroke / onCombo
 * ```
 *
 * ## 为什么需要这样一个类
 *
 * 单看每一层都很简单，难的是**线程与生命周期**：
 * - AIDL 是 `oneway` ⇒ 回调在 **binder 线程**，碰 UI 必须切主线程
 * - 远端（shell 侧）可能**先死**（Shizuku 被杀）⇒ [onClosed] 由 `ShellGateway` 兜底触发，
 *   保证**恰好被调用一次**，UI 状态机可以只信它
 *
 * ## 用法
 *
 * ```kotlin
 * val stream = KeyStream(shell)
 * stream.onCombo = { log("组合键: $it") }
 * stream.start(InputDevice.pickKeyboard(shell.listInputDevices().getOrThrow())!!)
 * ```
 */
class KeyStream(private val shell: ShellGateway) {

    /** 每一次 `EV_KEY`（含修饰键本身的按下/抬起） */
    var onStroke: ((KeyCodec.KeyStroke, String) -> Unit)? = null

    /** ★ 只在「按下非修饰键且当时有修饰键按住」时触发，如 `Ctrl+Win+←` */
    var onCombo: ((String) -> Unit)? = null

    /** 结束通知 —— **保证被调用一次**（手动停 / 出错 / binder 死） */
    var onClosed: ((String) -> Unit)? = null

    /** 启动失败 */
    var onError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val decoder = KeyCodec.Decoder()

    var device: InputDevice? = null
        private set

    /** ⚠️ 以 ShellGateway 的**本地**引用为准 —— 远端死了就没人答了 */
    val isRunning: Boolean get() = shell.isCapturing()

    /** 当前按住的修饰键（供 UI 显示） */
    fun heldModifiers(): List<String> = decoder.heldModifiers()

    fun start(dev: InputDevice): Boolean {
        decoder.reset()

        val ok = shell.startKeyCapture(
            devPath = dev.path,
            onLine = { line ->
                // ⚠️ 此处是 **binder 线程**。解码是纯 CPU 操作，就地做掉；
                //    但任何 UI 接触都必须 post 回主线程。
                val stroke = runCatching { decoder.feed(line) }.getOrNull()
                if (stroke != null) {
                    main.post {
                        onStroke?.invoke(stroke, line)
                        val c = stroke.combo
                        if (c != null) onCombo?.invoke(c)
                    }
                }
            },
            onClosed = { reason -> main.post { onClosed?.invoke(reason) } },
        ).getOrDefault(false)

        device = if (ok) dev else null
        if (!ok) {
            val msg = "启动抓键失败（Shizuku 是否已连接？）"
            main.post { onError?.invoke(msg) }
        }
        return ok
    }

    fun stop() {
        shell.stopKeyCapture()
        device = null
    }
}
