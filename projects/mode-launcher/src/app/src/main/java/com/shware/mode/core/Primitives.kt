package com.shware.mode.core

/**
 * ★ TNT 桌面壳的原语。
 *
 * 整个桌面（标题栏 / Dock / 吸附 / 拖拽 / **跨屏搬运**）都由这里拼出来。
 * 这一层是**纯接口** —— 不依赖 Android、不依赖 Shizuku，便于日后换实现或加单测。
 *
 * 当前实现：[com.shware.mode.shell.ShellGateway]（走 Shizuku → shell）。
 *
 * ## ★ 演进记录
 *
 * - 原本是「**四个原语**」：[identity] / [listTasks] / [launchToDisplay] / [resizeTask]
 * - ★ **2026-09-12 补上 [listStacks] + [moveStackToDisplay]** ——
 *   因为上面那句「跨屏搬运」**一直缺着**：能启动到指定屏、能改尺寸，
 *   但**搬不动【已存在】的窗口**。补齐后 PC 桌面的最后一块拼图才算有料。
 */
interface Primitives {

    /** 自检：返回 `id` 的输出，用来确认到底是不是 shell 身份 */
    fun identity(): Result<String>

    /**
     * 枚举当前所有任务。
     *
     * 当前实现解析 `dumpsys activity activities`——
     * 后续可换 binder `IActivityTaskManager.getTasks()`，更快更稳。
     */
    fun listTasks(): Result<List<TaskInfo>>

    /**
     * 把一个组件启动到指定显示器，并**直接进入自由窗口**。
     *
     * ⚠️ `windowingMode` 必须是 **5 = FREEFORM**（MIUI 小窗）。
     * 用 6 = MULTI_WINDOW 会被 HyperOS 当「分屏」，部分 App 会弹
     * 「当前页面不支持分屏」；只给 `--display` 不给模式则落 fullscreen，
     * 之后 `resizeTask` 会**静默失败**。（依据：`.paper/04` §2.5 实测）
     *
     * @return 启动后的 taskId（解析不到则为 null）
     */
    fun launchToDisplay(
        component: String,
        displayId: Int,
        windowingMode: Int = WINDOWING_MODE_FREEFORM,
    ): Result<Int?>

    /** 改窗口几何。仅对 FREEFORM / MULTI_WINDOW 任务生效。 */
    fun resizeTask(taskId: Int, geo: Geo): Result<Boolean>

    /** 查单个任务的当前状态 —— 用于给出**真实失败原因**而不是猜 */
    fun taskState(taskId: Int): Result<TaskInfo?>

    /** 把一次点击定向投到指定显示器 */
    fun injectTap(displayId: Int, x: Float, y: Float): Result<Boolean>

    // ---------------------------------------------------------------- ★ 跨屏搬运
    //
    // ★★★ 2026-09-12 补：本接口开头写着
    //     「整个桌面（标题栏 / Dock / 吸附 / 拖拽 / **跨屏搬运**）都由这四条拼出来」
    //     —— 而在这之前**恰恰缺了"跨屏搬运"**：能启动到指定屏、能改尺寸，
    //     但**搬不动【已存在】的窗口**。下面两条把这块补上。

    /**
     * 列出所有 **stack**（含 `displayId` 与它包含的 `taskIds`）。
     *
     * ★ 为什么要它：搬运吃的是 **stackId**，而 [listTasks] 只给 `taskId`
     * ⇒ **不先拿到这张映射就不知道往哪儿搬**。
     */
    fun listStacks(): Result<List<StackInfo>>

    /**
     * ★★★ **把整个 stack 搬到另一个显示** —— 「跨屏搬运」原语。
     *
     * 依据 `IActivityTaskManager.moveStackToDisplay(stackId, displayId)`（需 `MANAGE_ACTIVITY_STACKS`，shell 持有），
     * 已在真机实测有效（[AC §4.4](../../../../../../../../.paper/plans/AC-shell能力探针.md)）。
     *
     * ⚠️ **搬的是整个 stack**，里面的任务一起走 ⇒ 调用前确认里面只有你的目标窗口。
     * ⚠️ **"调用没报错" ≠ "真的搬过去了"** —— 要验下游行为请回读 [listStacks] 的 `displayId`
     * （同 [resizeTask] 那条纪律）。
     */
    fun moveStackToDisplay(stackId: Int, displayId: Int): Result<Boolean>

    // ---------------------------------------------------------------- ★ 音频输出
    //
    // ★★★ 2026-09-12 加。起因：用户现场发现「窗口搬到手机上了，但**播放音频依然在 TNT 上**」。
    //
    // ⚠️ 先说清语义：**Android 的音频路由按【设备/策略】走，与窗口在哪个显示器无关**
    //    （PC 上把窗口拖到副屏，声音也不会跟着走）⇒ **"音频没跟着窗口走"本身不是 bug**。
    //    真正要回答的是：「**能不能控制音频输出设备？**」—— 下面两条就是这个。

    /**
     * **当前媒体输出设备名**（`usb_headset`（TNT GO）/ `speaker`（手机）/ …）。
     *
     * 用途：把开关的**实际状态显示出来** —— 而不是让用户猜现在声音从哪儿出。
     */
    fun currentAudioOutput(): Result<String>

    /**
     * ★★★ **切换媒体音频的输出设备**：`"tnt"`（TNT GO）/ `"phone"`（手机扬声器）。
     *
     * 原理：把 USB 音频标记为**断开/接上**，系统随即重路由媒体
     * （`IAudioService.setWiredDeviceConnectionState`，实测**完全可逆**）。
     *
     * ⚠️ **这是【全局】路由，不是按 app** —— 会影响所有媒体播放。
     * ⚠️ **只动媒体**，不碰通话。
     * ⚠️ **"调用成功" ≠ "真的切过去了"** ⇒ 要回读 [currentAudioOutput] 确认。
     */
    fun setAudioOutput(target: String): Result<Boolean>

    // ---------------------------------------------------------------- 按键流
    //
    // ★ 第 5 件（截 KEY_RIGHTMETA）的正解：
    //   shell **在 input 组**（实测 groups 含 1004(input)）⇒ 能直接读 /dev/input/*。
    //   ⇒ 绕开无障碍框架，不受 MIUI 白名单 / 进程存活 / uiautomator 干扰。

    /**
     * 列出输入设备。
     *
     * ⚠️ **设备编号跨主机不同**（同一台 TNT GO 键盘：坚果 `event8` / 小米 `event11`）
     * ⇒ 一律用 [InputDevice.pickKeyboard] **按名字**挑，不要写死编号。
     */
    fun listInputDevices(): Result<List<InputDevice>>

    /**
     * 开始从 [devPath] 抓键。
     *
     * ⚠️ 回调**跑在 binder 线程**（AIDL 侧是 `oneway`），调用方须自己切主线程。
     * ⚠️ [onClosed] **保证被调用一次** —— 无论是正常停、出错、还是 binder 死掉。
     *    做 UI 状态机时可以只信它。
     */
    fun startKeyCapture(
        devPath: String,
        onLine: (String) -> Unit,
        onClosed: (String) -> Unit,
    ): Result<Boolean>

    /** 停止抓键。没在抓时是空操作。 */
    fun stopKeyCapture(): Result<Boolean>

    /** 当前是否在抓 */
    fun isCapturing(): Boolean

    companion object {
        const val WINDOWING_MODE_FREEFORM = 5
        const val WINDOWING_MODE_MULTI_WINDOW = 6
    }
}
