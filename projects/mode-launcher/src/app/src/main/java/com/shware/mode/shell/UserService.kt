package com.shware.mode.shell

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// ⚠️ `android.os.Process` 会把 `java.lang.Process` **遮蔽掉** ——
//    不取别名的话，`ProcessBuilder().start()` 的返回值类型解析不到，
//    报一堆莫名其妙的 `Unresolved reference 'destroy' / 'waitFor' / 'exitValue'`。
import java.lang.Process as JavaProcess

/**
 * ★ 特权通道的另一端：本类由 **Shizuku 以 shell(uid 2000) 身份**加载并运行，
 * 不在 App 进程里。因此它可以执行 `am` / `input` / `dumpsys` 等需要 shell 权限的命令。
 *
 * 设计取舍：**只暴露一条通用 `exec`**，而不是给每个原语写一个 AIDL 方法。
 * 理由：我们全部 recipe 都已在 `adb shell` 上实测通过（见 `.paper/04` §2.5），
 * 直接复用字符串命令**风险最低、改动最小**；等边界稳定后再换成 binder 直调。
 *
 * 唯一的例外是**按键流**（[startKeyCapture] / [stopKeyCapture]）——
 * `getevent` 是无限流，`exec` 是「等进程退出才返回」的一次性调用，套不上去。
 *
 * ⚠️ 本类名会被 [ShellGateway] 通过 [rikka.shizuku.Shizuku.UserServiceArgs] 引用，
 * 改名时两处必须同步。
 */
class UserService : IUserService.Stub() {

    init {
        // ★★ 启动即收尸 —— 见 [reapOrphanShellSvcs]
        reapOrphanShellSvcs()
    }

    /**
     * ★★★ **清理遗留的 `:shellsvc` 孤儿进程**（2026-09-12 修泄漏）。
     *
     * ## 为什么会有孤儿
     *
     * Shizuku 的 `bindUserService` **每绑一次就起一个新进程**（`com.shware.mode:shellsvc`）。
     * 而 [SharedShell] 那个单例是**进程内**的 —— 它只能保证"同一个 app 进程里不重复绑"，
     * **挡不住 app 进程被杀**：
     *
     * ```
     * app 进程被杀（force-stop / 换进程 / 崩溃）
     *   ⇒ 它起的 :shellsvc 没人调 unbindUserService
     *   ⇒ 变成孤儿，永远挂在那儿（实测堆到 14 个，每个 ~90MB，且都是 shell 身份）
     * ```
     *
     * ## 为什么这条修法成立
     *
     * ★ 本类**跑在 shell 身份里**（uid 2000）⇒ **有权 kill 那些孤儿**。
     * 每次新服务起来时顺手把**除自己以外**的同类进程收掉 ⇒ **自愈**。
     * 不需要 app 侧配合，也**不依赖"能不能优雅退出"**（那种前提本身就不成立）。
     *
     * ⚠️ **只杀同名同包的 `:shellsvc`** —— 绝不用通配，免得误伤别的 app 的 shell 进程。
     */
    private fun reapOrphanShellSvcs() {
        val myPid = Process.myPid()
        runCatching {
            // `pidof` 比解析 `ps` 稳（列宽会变）；本机 toybox 有它
            val pids = exec("pidof $SELF_PROC").trim().split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .filter { it != myPid }
            if (pids.isEmpty()) return

            pids.forEach { exec("kill $it") }
            Log.i(TAG, "★ 收掉 ${pids.size} 个遗留 :shellsvc 孤儿：$pids（自己是 $myPid）")
        }.onFailure { Log.w(TAG, "收孤儿失败（不影响主流程）：${it.message}") }
    }

    override fun exec(cmd: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .directory(File("/"))
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }

    override fun uid(): Int = Process.myUid()

    override fun pid(): Long = Process.myPid().toLong()

    // ------------------------------------------------------- ★ 跨屏搬运（2026-09-12 加）
    //
    // ⚠️ 这两条**破了本类开头那条设计原则**（"只暴露 exec"），理由见 [IUserService]：
    //    搬运窗口**没有 shell 命令**（`cmd activity_task` = "No shell command implementation"），
    //    exec 拼不出来 ⇒ 只能走 binder。

    /**
     * 列出所有 stack。每行：`stackId|displayId|topActivity|taskIds|bounds`。
     */
    override fun listStacks(): String {
        val atm = runCatching { activityTaskManager() }.getOrNull()
            ?: return "ERROR: 拿不到 activity_task 服务"
        return runCatching {
            val infos = atm.javaClass.getMethod("getAllStackInfos").invoke(atm) as? List<*>
                ?: return@runCatching "ERROR: getAllStackInfos 返回 null"
            infos.joinToString("\n") { s -> stackLine(s!!) }
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
    }

    /**
     * ★★★ 把整个 stack 搬到另一个显示 —— 「跨屏搬运」原语。
     *
     * @return `"OK"` / `"ERR: ..."` —— **不抛异常**，让上层能显示真实原因
     */
    override fun moveStackToDisplay(stackId: Int, displayId: Int): String {
        val atm = runCatching { activityTaskManager() }.getOrNull()
            ?: return "ERR: 拿不到 activity_task 服务"
        return runCatching {
            atm.javaClass.getMethod(
                "moveStackToDisplay",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(atm, stackId, displayId)
            "OK"
        }.getOrElse { e ->
            // ⚠️ 反射调用失败时**真原因在 cause 里**（InvocationTargetException 只是外壳）
            val cause = e.cause
            "ERR: ${e.javaClass.simpleName}: ${e.message}" +
                if (cause != null) " / ${cause.javaClass.simpleName}: ${cause.message}" else ""
        }
    }

    /**
     * ★★★ 切换**媒体音频输出设备** —— 见 [IUserService.setAudioOutput]。
     *
     * 实测（2026-09-12）：`state=0` ⇒ `Devices: speaker`；`state=1` ⇒ `Devices: usb_headset`，
     * **完全可逆**。详见 `.paper/plans/AH-搬运后的音频路由.md`。
     *
     * ⚠️ 这是**全局**路由（不是按 app）；且**只动媒体**，不碰通话。
     */
    override fun setAudioOutput(target: String): String {
        val svc = runCatching { audioService() }.getOrNull()
            ?: return "ERR: 拿不到 audio 服务"
        val state = when (target) {
            "tnt" -> 1        // 接上 ⇒ 走 TNT GO
            "phone" -> 0      // 断开 ⇒ 回落手机扬声器
            else -> return "ERR: 未知目标 '$target'（只能是 tnt / phone）"
        }
        return runCatching {
            svc.javaClass.getMethod(
                "setWiredDeviceConnectionState",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, String::class.java, String::class.java,
            ).invoke(svc, AUDIO_DEVICE_OUT_USB_HEADSET, state, USB_ADDR, USB_NAME, SELF_PROC)
            "OK"
        }.getOrElse { e ->
            // ⚠️ 真原因在 cause 里（InvocationTargetException 只是外壳）
            val cause = e.cause
            "ERR: ${e.javaClass.simpleName}: ${e.message}" +
                if (cause != null) " / ${cause.javaClass.simpleName}: ${cause.message}" else ""
        }
    }

    /** 反射拿 `IAudioService`（同样是隐藏类，只能反射） */
    private fun audioService(): Any? {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "audio")
            ?: return null
        val stub = Class.forName("android.media.IAudioService\$Stub")
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder)
    }

    /**
     * 反射拿 `IActivityTaskManager`。
     *
     * ⚠️ 它是**隐藏类**，而本模块对着**公开** android.jar 编译 ⇒ 只能反射。
     * （同一套手法见 `projects/shell-probe/ShellProbe.java`。）
     */
    private fun activityTaskManager(): Any? {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity_task")
            ?: return null
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder)
    }

    /** 把一个 `StackInfo` 压成一行文本；**每个字段单独容错**，缺一个不至于整行失败 */
    private fun stackLine(s: Any): String {
        val c = s.javaClass
        fun f(name: String): Any? = runCatching { c.getField(name).get(s) }.getOrNull()
        val tasks = (f("taskIds") as? IntArray)?.joinToString(",") ?: ""
        val b = f("bounds")
        val bounds = if (b is android.graphics.Rect) "${b.left},${b.top},${b.right},${b.bottom}" else ""
        return "${f("stackId")}|${f("displayId")}|${f("topActivity") ?: "?"}|$tasks|$bounds"
    }

    // ---------------------------------------------------------------- 按键流

    private val capLock = Any()
    private var capProc: JavaProcess? = null
    private var capThread: Thread? = null

    @Volatile
    private var capDev: String = ""

    override fun startKeyCapture(devPath: String, cb: IKeyCallback?): Int {
        if (devPath.isBlank()) return -1
        if (cb == null) return -2

        stopKeyCapture()   // ★ 幂等：先停旧的，免得两条流同时往 App 灌

        val proc = try {
            ProcessBuilder(GETEVENT, "-lt", devPath)
                .redirectErrorStream(true)
                .directory(File("/"))
                .start()
        } catch (t: Throwable) {
            return -3
        }

        synchronized(capLock) {
            capProc = proc
            capDev = devPath
            val t = Thread({ pump(proc, cb) }, "tntwm-keycap")
            t.isDaemon = true
            capThread = t
            t.start()
        }
        return 0
    }

    override fun stopKeyCapture() {
        val proc = synchronized(capLock) {
            val p = capProc
            capProc = null
            capThread = null
            capDev = ""
            p
        }
        runCatching { proc?.destroy() }
    }

    override fun isCapturing(): Boolean = synchronized(capLock) { capProc != null }

    override fun captureDevice(): String = capDev

    /**
     * 读线程：把 `getevent` 输出过滤后逐行回调。
     *
     * ★ **无论怎么退出，都必须回调一次 [IKeyCallback.onCaptureClosed]** ——
     * 否则 App 侧会一直以为「还在抓」，而实际上进程早死了（这类"假活着"最难查）。
     */
    private fun pump(proc: JavaProcess, cb: IKeyCallback) {
        var reason: String? = null
        var events = 0
        var lastOther = ""

        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!isInteresting(line)) {
                        lastOther = line.trim()
                        continue
                    }
                    events++
                    try {
                        cb.onKeyLine(line)
                    } catch (t: Throwable) {
                        reason = "回调失败（App 侧可能已退出）: ${t.javaClass.simpleName}"
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            reason = "读取异常: ${t.javaClass.simpleName}: ${t.message}"
        }

        if (reason == null) {
            runCatching { proc.waitFor() }
            val rc = runCatching { proc.exitValue() }.getOrDefault(-1)
            reason = when {
                rc == 0 -> "getevent 正常结束"
                // ★ 一行事件都没有 + 有非事件输出 ⇒ 那就是 getevent 的报错原文，别丢
                events == 0 && lastOther.isNotEmpty() -> "getevent 报错: $lastOther"
                else -> "getevent 退出码 $rc"
            }
        }

        synchronized(capLock) {
            if (capProc === proc) {
                capProc = null
                capThread = null
                capDev = ""
            }
        }
        runCatching { cb.onCaptureClosed(reason) }
    }

    /**
     * ★ 过滤：**只留 `EV_KEY` 与 `MSC_SCAN`**。
     *
     * **不能只留 `EV_KEY`** —— TNT GO 那 7 个专用键在 Linux 层全是 `KEY_UNKNOWN(240)`，
     * **只有 `MSC_SCAN` 里的 HID usage 能区分它们**（依据 `.paper/03` §2.3④，
     * 原始日志 `refs/raw_keylog.txt`）。丢掉 MSC_SCAN ⇒ 7 个键退化成 7 个一样的码。
     *
     * 过滤放在**这一侧**（而不是 App 侧）的理由：鼠标 125Hz 的 `EV_REL` / `EV_SYN`
     * 没必要上跨进程总线 —— Binder 每次事务都有成本。
     *
     * 两种格式都认：带标签（`EV_KEY` / `MSC_SCAN`）与裸十六进制（`0001` / `0004 0004`）。
     */
    private fun isInteresting(line: String): Boolean =
        line.contains("EV_KEY") || line.contains("MSC_SCAN") ||
            RAW_KEY.containsMatchIn(line) || RAW_MSC.containsMatchIn(line)

    companion object {
        /** ⚠️ 用绝对路径 —— UserService 进程的 PATH 不保证有 `/system/bin` */
        private const val GETEVENT = "/system/bin/getevent"

        private const val TAG = "ModeShell"

        /**
         * **本进程名**（形如 `com.shware.mode:shellsvc`）。
         *
         * ⚠️ 从 `/proc/self/cmdline` 读，**不写死包名** ——
         * 写死的话将来改包名/改进程名就会**静默失效**（收尸收了个寂寞）。
         */
        private val SELF_PROC: String by lazy {
            runCatching {
                val raw = File("/proc/self/cmdline").readBytes()
                val end = raw.indexOf(0.toByte())
                raw.copyOfRange(0, if (end >= 0) end else raw.size).decodeToString()
            }.getOrDefault("com.shware.mode:shellsvc")
        }

        /** 裸格式的 `EV_KEY`：`[ts] 0001 <code> <value>` */
        private val RAW_KEY = Regex("""]\s+0001\s""")

        /** 裸格式的 `MSC_SCAN`：`[ts] 0004 0004 <usage>` */
        private val RAW_MSC = Regex("""]\s+0004\s+0004\s""")

        // ------------------------------------------------------- ★ 音频路由（2026-09-12 加）

        /**
         * `AUDIO_DEVICE_OUT_USB_HEADSET`（AOSP `system/audio.h` 的位掩码）。
         *
         * ⚠️ **公开 SDK 里没有这个常量**（`AudioDeviceInfo.TYPE_USB_HEADSET = 22` 是另一套编号）
         * ⇒ 只能写死。**换 ROM 要复验。**
         */
        private const val AUDIO_DEVICE_OUT_USB_HEADSET = 0x4000000

        /**
         * TNT GO 的 USB 音频标识 —— **实测值**（`dumpsys media.audio_policy` 的 `Device 7`）。
         *
         * ⚠️ `card=1` 这个编号**不保证永远不变**（USB 枚举顺序可能变）。
         * 变了的话这个功能会**静默失效**（`setWiredDeviceConnectionState` 对不存在的设备是空操作）
         * ⇒ 所以调用后**必须回读 `dumpsys audio` 的 `Devices:` 确认**。
         */
        private const val USB_ADDR = "card=1;device=0;"
        private const val USB_NAME = "USB-Audio - Smartisan TNT go"
    }
}
