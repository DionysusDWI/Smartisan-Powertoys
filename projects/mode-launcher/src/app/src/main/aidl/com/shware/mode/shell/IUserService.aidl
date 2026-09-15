// IUserService.aidl
//
// 特权通道的最小接口：跑在 shell(uid 2000) 身份的 UserService。
// 故意做得很薄 —— 只暴露一条通用 exec，四个原语在 Kotlin 侧（ShellGateway）拼装。
// 这样 AIDL 不用随原语增加而改，编译期也少一堆 stub。
package com.shware.mode.shell;

import com.shware.mode.shell.IKeyCallback;

interface IUserService {
    /** 以 shell 身份执行一条命令，返回 stdout + stderr（合并） */
    String exec(String cmd);

    /** 自检：本进程 uid —— 正常应为 2000(shell) */
    int uid();

    /** 自检：本进程 pid */
    long pid();

    // ------------------------------------------------------- ★ 跨屏搬运（2026-09-12 加）
    //
    // ⚠️ ★ 这两条【破了上面那条设计原则】，是故意的 —— 必须说清楚为什么：
    //
    //   原则是「只暴露 exec，原语在 Kotlin 侧拼装」，代价是 AIDL 不随原语增加而改。
    //   **但搬运窗口这条原语在这里行不通**：它【没有对应的 shell 命令】——
    //   实测 `cmd activity_task` 报 "No shell command implementation"，
    //   `cmd activity` 也没有任何任务搬移子命令。
    //   ⇒ exec("...") 拼不出来，只能走 binder。
    //
    //   ⇒ 与其造一个"通用 binder 调用"的万能口子（那更糟、更不安全），
    //     不如加两条【窄而明确】的方法。（先例：startKeyCapture 也已不是 exec。）

    /**
     * 列出所有 stack。
     *
     * 返回**文本**（每行一条 `stackId|displayId|topActivity|taskIds`），
     * 由 Kotlin 侧解析 —— 与 [exec] 同一套「返回文本、上层解析」的思路。
     *
     * ★ 为什么需要：`moveStackToDisplay` 吃的是 **stackId**，
     * 而 [exec] 出来的任务列表只给 taskId ⇒ **不先拿到映射就不知道往哪儿搬**。
     */
    String listStacks();

    /**
     * ★★★ 把一个 **stack** 搬到另一个显示 —— 「跨屏搬运」原语。
     *
     * 依据：`IActivityTaskManager.moveStackToDisplay(int stackId, int displayId)`，
     * 要求 `MANAGE_ACTIVITY_STACKS`（shell 持有）。
     * 已在真机实测有效（见 `.paper/plans/AC-shell能力探针.md` §4.4）。
     *
     * ⚠️ **搬的是【整个 stack】**，不只是某个任务 ——
     * 调用前请自行确认该 stack 里只有你的目标窗口。
     *
     * @return `"OK"` 表示调用成功；否则 `"ERR: <原因>"`（**不抛异常**，让上层能显示真实原因）
     */
    String moveStackToDisplay(int stackId, int displayId);

    // ------------------------------------------------------- ★ 媒体音频输出（2026-09-12 加）
    //
    // ⚠️ 同上，这也**没有 shell 命令**（`cmd audio` = "No shell command implementation"），
    //    只能走 binder。

    /**
     * ★★★ **切换媒体音频的输出设备**。
     *
     * 原理：`IAudioService.setWiredDeviceConnectionState(AUDIO_DEVICE_OUT_USB_HEADSET, state, ...)`
     * —— 把 USB 音频（TNT GO）**标记为断开/接上**，系统随即重路由媒体。
     *
     * 实测（2026-09-12）：`state=0` ⇒ `Devices: speaker`；`state=1` ⇒ `Devices: usb_headset`，**完全可逆**。
     *
     * ⚠️ **这是【全局】路由，不是按 app** —— 影响所有媒体播放。
     * ⚠️ **只动媒体**；`setSpeakerphoneOn` 那类**通话**相关的**绝不碰**。
     *
     * @param target `"tnt"` = 接上 USB 耳麦（TNT GO）；`"phone"` = 断开它（回落手机扬声器）
     * @return `"OK"` / `"ERR: <原因>"`
     */
    String setAudioOutput(String target);

    // ---------------------------------------------------------------- 按键流
    //
    // ★ 第 5 件（截 KEY_RIGHTMETA）的正解：
    //   shell **在 input 组**（实测 groups 含 1004(input)）⇒ 能直接读 /dev/input/*。
    //   ⇒ 绕开无障碍框架，不受 MIUI 白名单 / 进程存活 / uiautomator 干扰。

    /**
     * 开始从 devPath 抓键。
     * **幂等**：重复调用会先停掉上一次再起新的。
     * @return 0 = 已启动；-1 = devPath 为空；-2 = 回调为空；-3 = 起进程失败
     */
    int startKeyCapture(String devPath, IKeyCallback cb);

    /** 停止抓键。没在抓时是空操作。 */
    void stopKeyCapture();

    /** 当前是否在抓 */
    boolean isCapturing();

    /** 当前抓的设备路径；没在抓则为空串 */
    String captureDevice();
}
