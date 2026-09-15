package com.shware.mode.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.shware.mode.core.Geo
import com.shware.mode.core.InputDevice
import com.shware.mode.core.Primitives
import com.shware.mode.core.StackInfo
import com.shware.mode.core.TaskInfo
import rikka.shizuku.Shizuku

/**
 * ★ App 侧的特权门面：Shizuku 权限 → 绑定 UserService → 实现四个原语。
 *
 * 纪律（踩过坑，见 `PROGRESS-STATE` §6.5）：
 * - **`settings get` 读回 ≠ 生效** ⇒ 每个写操作都要**验下游行为**（`resizeTask` 会回读 bounds）
 * - **binder death 必须监听** ⇒ Shizuku 被杀后要能自动重连并降级
 */
class ShellGateway(private val context: Context) : Primitives {

    enum class State { NO_SHIZUKU, NO_PERMISSION, DISCONNECTED, CONNECTING, READY }

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    /** 状态变化回调（日志用） */
    var onState: ((State, String) -> Unit)? = null

    /**
     * ★★ **状态订阅者**（可以有多个）—— 与 [onState] 分开是**故意的**。
     *
     * [onState] 是一个**单槽**回调：谁最后设谁生效。
     * 而本进程里 `HomeActivity`、`MainActivity`、`LauncherService` **共享同一个**
     * [ShellGateway]（见 [SharedShell]）⇒ 用单槽会出现
     * 「开了主界面，工程台就再也收不到状态了」这种**互相踩**的问题。
     *
     * ## 为什么需要订阅（真实故障，2026-09-14）
     *
     * 主界面 `onCreate` 里立刻查一次 mod 存活状态，而那时 Shizuku 还在绑定：
     *
     * ```
     * 48.316  [CONNECTING] 正在绑定 UserService…
     * 48.497  probeAll 失败：UserService 未连接（CONNECTING）   ← 早了 180ms
     * 48.837  [READY] UserService 已连接
     * ```
     *
     * ⇒ 界面上**全部显示「状态未知」**，而且**再也不会自己恢复**
     * （因为没人告诉它"现在可以查了"）。
     *
     * ⇒ 修法：订阅 [State.READY]，到了就重查一次。
     * **单次查询 + 不重试**在异步通道上是必然出错的写法。
     */
    private val stateListeners = java.util.concurrent.CopyOnWriteArraySet<(State) -> Unit>()

    /** 订阅状态变化；**记得在 onDestroy 里 [removeStateListener]**（否则泄漏 Activity） */
    fun addStateListener(l: (State) -> Unit) { stateListeners += l }

    fun removeStateListener(l: (State) -> Unit) { stateListeners -= l }

    private var svc: IUserService? = null

    /**
     * ★ 按键流回调必须**强引用**。
     * 只传给 binder 而不在本地留一份的话会被 GC ——
     * 症状是「抓包启动成功，但一行都不来」，且**没有任何报错**，极难查。
     */
    private var keyCb: IKeyCallback? = null

    /**
     * 抓包结束的 App 侧通知。
     * 与 [keyCb] 分开是因为：**远端已经死了的话它没法回调我们**，
     * 这时只能由本类兜底调用（binder death / 解绑）。
     */
    private var keyOnClosed: ((String) -> Unit)? = null

    /** ⚠️ 必须是同一个实例 —— bind / unbind 都靠它匹配 */
    private val args: Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, UserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shellsvc")
        .debuggable(false)
        .version(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = IUserService.Stub.asInterface(binder)
            transition(State.READY, "UserService 已连接")
            // 立刻自检：确认对面真的是 shell 身份
            runCatching { "身份自检: id => " + svc?.exec("id")?.trim() }
                .onSuccess { Log.i(TAG, it); onState?.invoke(State.READY, it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            fireKeyClosed("UserService 已断开")
            transition(State.DISCONNECTED, "UserService 已断开")
        }
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        transition(if (hasPermission()) State.DISCONNECTED else State.NO_PERMISSION, "Shizuku binder 已就绪")
        if (hasPermission()) bind()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        svc = null
        fireKeyClosed("Shizuku binder 已死")
        transition(State.NO_SHIZUKU, "★ Shizuku binder 已死 —— 需重新启动 Shizuku server")
    }

    private val onPermResult = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        val ok = grantResult == PackageManager.PERMISSION_GRANTED
        transition(if (ok) State.DISCONNECTED else State.NO_PERMISSION, "权限请求结果: $ok (code=$code)")
        if (ok) bind()
    }

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
            Shizuku.addRequestPermissionResultListener(onPermResult)
        }.onFailure {
            transition(State.NO_SHIZUKU, "Shizuku 不可用: ${it.message}")
            return
        }
        // 已经就绪的场合，listener 是 sticky 的会立刻回调；这里再兜一次
        if (Shizuku.pingBinder()) {
            transition(if (hasPermission()) State.DISCONNECTED else State.NO_PERMISSION, "Shizuku 在线")
            if (hasPermission()) bind()
        } else {
            transition(State.NO_SHIZUKU, "Shizuku 未运行 —— 请在 Shizuku App 内启动 server")
        }
    }

    fun stop() {
        runCatching {
            Shizuku.removeBinderReceivedListener(onBinderReceived)
            Shizuku.removeBinderDeadListener(onBinderDead)
            Shizuku.removeRequestPermissionResultListener(onPermResult)
            unbind()
        }
    }

    fun isShizukuAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** 请求权限（会弹 Shizuku 的授权框） */
    fun requestPermission(requestCode: Int = REQ_SHIZUKU) {
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { transition(State.NO_SHIZUKU, "请求权限失败: ${it.message}") }
    }

    fun shouldShowRationale(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    fun bind() {
        if (svc != null) { transition(State.READY, "已绑定，跳过"); return }
        transition(State.CONNECTING, "正在绑定 UserService…")
        runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure { transition(State.DISCONNECTED, "绑定失败: ${it.message}") }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        svc = null
        fireKeyClosed("已解绑 UserService")
        transition(State.DISCONNECTED, "已解绑")
    }

    /** 把「抓包结束」通知 App 侧一次，然后清干净（幂等） */
    private fun fireKeyClosed(reason: String) {
        val cb = keyOnClosed
        keyCb = null
        keyOnClosed = null
        if (cb != null) runCatching { cb.invoke(reason) }
    }

    private fun transition(s: State, msg: String) {
        state = s
        Log.i(TAG, "[$s] $msg")
        onState?.invoke(s, msg)
        // ★ 订阅者各自 try —— 一个抛异常不能把别的订阅者带走
        for (l in stateListeners) runCatching { l(s) }
    }

    // ---------------------------------------------------------------- 命令执行

    /**
     * 直接执行一条 shell 命令。失败时返回 [Result.failure]。
     *
     * ## ⚠️⚠️ 命令失败 ≠ 连接断了（2026-09-14 实测踩到）
     *
     * `dumpsys activity services` 的输出涨到 **334 KB** 时，binder 事务写不下，
     * 抛的是：
     *
     * ```
     * Transaction failed on small parcel; remote process probably died
     *                                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
     *                                        ★ 这句话是错的，进程活得好好的
     * ```
     *
     * 旧实现在这里**无条件** `transition(DISCONNECTED)` ⇒ 两个后果：
     * 1. ★ UI **谎报**「Shizuku 未连接」—— 而它明明连着，只是这一条命令太大
     * 2. ★ 触发**无意义的重绑循环**（每次失败都把状态打回，别处又去 bind）
     *
     * ⇒ 改成：**只有 binder 真的 ping 不通才标 DISCONNECTED**；
     *   否则如实记一条日志，状态保持不变。
     */
    fun exec(cmd: String): Result<String> {
        val s = svc ?: return Result.failure(IllegalStateException("UserService 未连接（$state）"))
        return runCatching { s.exec(cmd) }
            .onFailure { e ->
                val alive = runCatching { s.asBinder().pingBinder() }.getOrDefault(false)
                if (alive) {
                    Log.w(TAG, "exec 失败（连接仍在，不是断了）: ${e.message}")
                } else {
                    transition(State.DISCONNECTED, "exec 失败且 binder 已死: ${e.message}")
                }
            }
    }

    // ---------------------------------------------------------------- 四个原语

    override fun identity(): Result<String> = exec("id").map { it.trim() }

    override fun listTasks(): Result<List<TaskInfo>> = exec("dumpsys activity activities").map { dump ->
        parseTasks(dump)
    }

    override fun launchToDisplay(
        component: String,
        displayId: Int,
        windowingMode: Int,
    ): Result<Int?> {
        val cmd = "am start --display $displayId --windowingMode $windowingMode -n $component"
        val out = exec(cmd).getOrElse { return Result.failure(it) }
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) {
            return Result.failure(IllegalStateException("am start 报错: ${out.trim().take(300)}"))
        }
        // `am start` 不打印 taskId ⇒ 回读任务表，取该显示器上该组件【最大 taskId】（最新）
        val taskId = listTasks().getOrNull()
            ?.filter { it.displayId == displayId && component.startsWith(it.component) }
            ?.maxByOrNull { it.taskId }
            ?.taskId
        return Result.success(taskId)
    }

    override fun resizeTask(taskId: Int, geo: Geo): Result<Boolean> {
        exec("am task resize $taskId ${geo.toShellArgs()}").getOrElse { return Result.failure(it) }
        // ★ 验下游行为：`am task resize` 对非 freeform 任务【静默失败】——
        //   只看输出会假阳性，必须回读 bounds。
        //   ⚠️ 且**写入是异步的** —— 实测立刻回读会读到旧值（误报"未生效"）。
        //   这里轮询几次等它落定。
        //   ⚠️ 本函数在 UI 线程被调用时会阻塞 ~0.7s；正式版应移到后台线程。
        repeat(8) {
            Thread.sleep(120)
            val now = listTasks().getOrNull()?.firstOrNull { it.taskId == taskId }
            if (now?.bounds == geo) return Result.success(true)
        }
        val now = listTasks().getOrNull()?.firstOrNull { it.taskId == taskId }
        Log.w(TAG, "resize 未生效: 期望 $geo, 实得 ${now?.bounds ?: "任务不存在"} (mode=${now?.mode})")
        return Result.success(false)
    }

    /** 查一个任务的当前状态 —— 供 UI 在 resize 失败时给出**真实原因**而非猜测 */
    override fun taskState(taskId: Int): Result<TaskInfo?> =
        listTasks().map { list -> list.firstOrNull { it.taskId == taskId } }

    // ------------------------------------------------------- ★ 跨屏搬运（2026-09-12 加）
    //
    // ★★★ 这是 [Primitives] 注释里那句"整个桌面（…拖拽 / **跨屏搬运**）都由这四条拼出来"
    //     里【一直缺着】的那一条。走 binder（`IActivityTaskManager.moveStackToDisplay`），
    //     因为**没有等价的 shell 命令**（`cmd activity_task` = "No shell command implementation"）。

    /**
     * 列出所有 stack（含 `displayId` 与 `taskIds`）。
     *
     * ★ 为什么要它：搬运吃的是 **stackId**，而 [listTasks] 只给 taskId
     * ⇒ **不先拿到映射就不知道往哪儿搬**。
     */
    override fun listStacks(): Result<List<StackInfo>> {
        val s = svc ?: return Result.failure(IllegalStateException("UserService 未连接（$state）"))
        return runCatching { parseStacks(s.listStacks()) }
    }

    /**
     * ★★★ 把整个 **stack** 搬到另一个显示 —— 「跨屏搬运」。
     *
     * ⚠️ 搬的是**整个 stack**（里面的任务一起走）⇒ 调用前确认里面只有你的目标窗口。
     *
     * ⚠️ 和 [resizeTask] 一样，**"调用没报错" ≠ "真的搬过去了"** ——
     * 要验下游行为请调 [listStacks] 回读 `displayId`。
     */
    override fun moveStackToDisplay(stackId: Int, displayId: Int): Result<Boolean> {
        val s = svc ?: return Result.failure(IllegalStateException("UserService 未连接（$state）"))
        val out = runCatching { s.moveStackToDisplay(stackId, displayId) }
            .getOrElse { return Result.failure(it) }
        // ★ UserService 用 "OK" / "ERR: ..." 表达结果 —— 翻译成 Result，**并把真实原因带上来**
        return if (out == "OK") Result.success(true)
        else Result.failure(IllegalStateException(out.removePrefix("ERR: ")))
    }

    // ------------------------------------------------------- ★ 音频输出（2026-09-12 加）

    /**
     * **当前媒体输出设备名**（`usb_headset` / `speaker` / …）—— 从 `dumpsys audio` 读。
     *
     * ★ 用 `exec` 就够（读是 shell 命令能干的）—— 只有**写**才需要 binder。
     */
    override fun currentAudioOutput(): Result<String> = exec("dumpsys audio").map { dump ->
        dump.lineSequence()
            .dropWhile { !it.trimStart().startsWith("- STREAM_MUSIC:") }
            .firstOrNull { it.contains("Devices:") }
            ?.substringAfter("Devices:")?.trim()
            ?: "?"
    }

    /**
     * ★★★ **切换媒体音频输出**。
     *
     * ⚠️ **"调用成功" ≠ "真的切过去了"** —— 设备地址（`card=1;…`）在别的 ROM 上可能不同，
     * 对不存在的设备调用是**空操作**。⇒ 调用方**必须回读 [currentAudioOutput]** 确认
     * （同 [resizeTask] / [moveStackToDisplay] 那条纪律）。
     */
    override fun setAudioOutput(target: String): Result<Boolean> {
        val s = svc ?: return Result.failure(IllegalStateException("UserService 未连接（$state）"))
        val out = runCatching { s.setAudioOutput(target) }.getOrElse { return Result.failure(it) }
        return if (out == "OK") Result.success(true)
        else Result.failure(IllegalStateException(out.removePrefix("ERR: ")))
    }

    /**
     * 解析 [UserService.listStacks] 的文本：`stackId|displayId|topActivity|taskIds|bounds`。
     *
     * ⚠️ **逐行容错** —— 某行格式不对就跳过那一行，不要让整次调用失败。
     */
    private fun parseStacks(text: String): List<StackInfo> {
        if (text.startsWith("ERROR")) throw IllegalStateException(text)
        return text.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 5) return@mapNotNull null
            val id = p[0].trim().toIntOrNull() ?: return@mapNotNull null
            val disp = p[1].trim().toIntOrNull() ?: return@mapNotNull null
            val tasks = p[3].split(',').mapNotNull { it.trim().toIntOrNull() }
            val b = p[4].split(',').mapNotNull { it.trim().toIntOrNull() }
            StackInfo(
                stackId = id,
                displayId = disp,
                topActivity = p[2],
                taskIds = tasks,
                bounds = if (b.size == 4) Geo(b[0], b[1], b[2], b[3]) else null,
            )
        }.toList()
    }

    /**
     * 定向点击。
     *
     * ⚠️⚠️ **两端的 `input` 方言完全不同**（坚果真机实测 2026-09-12）：
     *
     * | 平台 | 语法 | 备注 |
     * |---|---|---|
     * | **坚果 A10**（Smartisan 定制） | `input --ext-display tap …` | ★ **`-d` 不被识别**（直接打 usage）<br/>★ `--ext-display` **写死 TNT 屏**，不能指定任意 id |
     * | 小米 A16 | `input -d <displayId> tap …` | A11+ 的标准形态 |
     *
     * ⇒ 本工程只跑坚果，故按「是不是虚拟屏（TNT 屏）」翻译：
     * 虚拟屏 id 一律 ≥ 100000（坚果 TNT 屏 = `100000`）。
     *
     * ⚠️ 若不改这一处：命令会**打 usage 但不报错**，看起来像"执行了"，
     *    实际**什么都没点到** —— 典型的静默失败。
     */
    override fun injectTap(displayId: Int, x: Float, y: Float): Result<Boolean> {
        val cmd = if (displayId >= FIRST_VIRTUAL_DISPLAY_ID) {
            "input --ext-display tap ${x.toInt()} ${y.toInt()}"
        } else {
            "input tap ${x.toInt()} ${y.toInt()}"
        }
        val out = exec(cmd).getOrElse { return Result.failure(it) }
        return Result.success(!out.contains("Error", ignoreCase = true))
    }

    // ---------------------------------------------------------------- 按键流

    /**
     * 列出输入设备（`getevent -pl`）。
     *
     * ⚠️ **设备编号是【主机相关】的，绝不能写死**：
     * 同一台 TNT GO 键盘，坚果 Pro 3 上是 `/dev/input/event8`，小米上是 `event11`。
     * ⇒ 调用方要用 [InputDevice.pickKeyboard] **按名字**挑。
     */
    override fun listInputDevices(): Result<List<InputDevice>> =
        exec("getevent -pl").map { InputDevice.parse(it) }

    /**
     * 开始抓键。回调**跑在 binder 线程**，调用方须自己切主线程。
     *
     * 幂等：内部会先停掉上一次。
     */
    override fun startKeyCapture(
        devPath: String,
        onLine: (String) -> Unit,
        onClosed: (String) -> Unit,
    ): Result<Boolean> {
        val s = svc ?: return Result.failure(IllegalStateException("UserService 未连接（$state）"))
        stopKeyCapture()

        val cb = object : IKeyCallback.Stub() {
            override fun onKeyLine(line: String) {
                runCatching { onLine(line) }
            }

            override fun onCaptureClosed(reason: String) {
                // 远端主动报的结束 —— 清掉本地引用，免得下次 start 以为还在抓
                keyCb = null
                keyOnClosed = null
                runCatching { onClosed(reason) }
            }
        }
        keyCb = cb          // ★ 强引用，见字段注释
        keyOnClosed = onClosed

        return runCatching { s.startKeyCapture(devPath, cb) }
            .map { rc -> rc == 0 }
            .onFailure {
                fireKeyClosed("startKeyCapture 抛异常: ${it.message}")
                transition(State.DISCONNECTED, "startKeyCapture 失败: ${it.message}")
            }
    }

    override fun stopKeyCapture(): Result<Boolean> {
        val s = svc ?: return Result.success(false)
        return runCatching {
            s.stopKeyCapture()
            fireKeyClosed("已手动停止")
            true
        }.onFailure { fireKeyClosed("stopKeyCapture 失败: ${it.message}") }
    }

    /** 当前是否在抓（以**本地**引用为准，不去问远端 —— 远端死了就没人答了） */
    override fun isCapturing(): Boolean = keyCb != null

    // ---------------------------------------------------------------- 已移除：MIUI 小窗诊断

    // ★★ 已删除 `freeformScale()` / `mFreeformScale` 诊断 ——
    //    那是 **MIUI 自由窗口专有**字段（小米端「命令成功但画面只有一小块」的元凶），
    //    **坚果上不存在这个字段**（实测）。
    //    ⇒ 一并删掉，免得留下永远返回 null 的死代码。
    //    依据：计划书 Q §2.1 方言对照表第 10 条。

    // ---------------------------------------------------------------- 解析

    companion object {
        private const val TAG = "Mode/ShellGateway"
        const val REQ_SHIZUKU = 0x7A01

        /**
         * 虚拟显示器的 id 下界 —— 坚果的 TNT 屏是 `Display #100000`。
         * ⚠️ 小米端 TNT 屏是逻辑 id `2`（A16 用 `-d 2`）；**两端的目标屏 id 不同**。
         */
        const val FIRST_VIRTUAL_DISPLAY_ID = 100000

        private val RX_DISPLAY = Regex("""Display #(\d+)""")

        // ------------------------------------------------ 坚果 A10 的格式（2026-09-12 实测）
        //
        // ★★ Android 10（Smartisan）与 Android 16（MIUI）的 dump 结构**完全不同**：
        //
        //     Display #100000 (activities from top to bottom):
        //       Stack #12: type=standard mode=freeform      ← ① mode 在【Stack 头】上
        //         mBounds=Rect(0, 0 - 0, 0)                   （Stack 自己的，无意义）
        //         Task id #14                                 ← ② 任务 id（独占一行）
        //         mBounds=Rect(587, 308 - 1073, 1280)         ← ③ ★ 真实窗口 bounds
        //         * TaskRecord{4475823 #14 A=pkg U=0 StackId=12 sz=1 …}   ← ④ 任务行
        //
        // ⚠️ 与 A16 的三处差异（**每一处都会造成"静默失败"**）：
        //   ① 任务是 `* TaskRecord{`，**不是** `* Task{`
        //      ⇒ 用旧正则 ⇒ `listTasks()` 返回**空列表** ⇒ 后续所有窗口操作假阴性
        //   ② `mode=` **不在任务行上**，要从 **Stack 头**继承
        //   ③ bounds 出现在 `Task id #T` 之后、`TaskRecord` 之前（**不是**任务行之后）
        //
        // 依据：计划书 Q §2.1「坚果平台方言实测」对照表（真机实测，非推断）。

        /** `Stack #12: type=standard mode=freeform` —— 提供 mode */
        private val RX_STACK = Regex("""^\s*Stack #(\d+):\s*type=\S+\s+mode=(\S+)""")

        /** `    Task id #14`（独占一行）—— 提供任务 id，紧跟其后是它的 bounds */
        private val RX_TASK_ID = Regex("""^\s*Task id #(\d+)\s*$""")

        /** `* TaskRecord{4475823 #14 A=com.x.y U=0 StackId=12 …}` —— 提供 id + 包名 */
        private val RX_TASK_RECORD = Regex("""\* TaskRecord\{[^}#]*#(\d+)\s+A=([\w.]+)""")

        /**
         * bounds 格式：`mBounds=Rect(l, t - r, b)`。
         *
         * ★ 实测：**A10 与 A16 都是这个格式**（两端都不是 `bounds=[l,t][r,b]`）。
         * ⚠️ 我最初只写了方括号那种 ⇒ bounds 恒为 null ⇒ 把一条实测可用的 recipe
         *    误报成「静默失败」（小米端踩过，见归档 HANDOVER）。
         */
        private val RX_BOUNDS_RECT = Regex("""mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

        /** 旧格式兜底：`bounds=[l,t][r,b]`（两端都未见过，留着防格式回退） */
        private val RX_BOUNDS_BRACKET = Regex("""bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        /**
         * 从 `dumpsys activity activities` 里抠任务表（**坚果 Android 10 格式**）。
         *
         * 解析是**有状态的四步链**（顺序不能乱，见上方结构注释）：
         *   ① `Display #N`      → 之后的任务归属 display N
         *   ② `Stack #S: … mode=M` → 记下 M（**任务行上没有 mode**）
         *   ③ `Task id #T`      → 记下 T，并清空它的 bounds 槽
         *   ④ `mBounds=Rect(…)` → 配给最近的 T
         *   ⑤ `* TaskRecord{…#T A=pkg …}` → **到这里才组装** TaskInfo
         *
         * ⚠️ 这是**文本解析**，厂商改 dump 格式就会失效。
         *   后续应换 binder `IActivityTaskManager.getTasks()`（见 [Primitives] 注释）。
         */
        fun parseTasks(dump: String): List<TaskInfo> {
            val out = mutableListOf<TaskInfo>()
            val seen = mutableSetOf<Int>()   // ★ 去重：同一个 task 会在多段里重复出现

            var displayId = -1
            var stackMode = "?"
            var pendingId = -1
            var pendingBounds: Geo? = null

            for (line in dump.lineSequence()) {
                // ① 显示器分段
                // ⚠️ 不能用 `?.let { …; continue }` —— Kotlin 的 inline lambda 里
                //    `continue` 是实验特性，编译会报错（踩过）。一律用普通 if。
                val dm = RX_DISPLAY.find(line)
                if (dm != null) {
                    displayId = dm.groupValues[1].toIntOrNull() ?: -1
                    pendingId = -1
                    pendingBounds = null
                    continue
                }

                // ② Stack 头 —— mode 的唯一来源
                val sm = RX_STACK.find(line)
                if (sm != null) {
                    stackMode = sm.groupValues[2]
                    continue
                }

                // ③ 任务 id —— bounds 紧随其后
                val tm = RX_TASK_ID.find(line)
                if (tm != null) {
                    pendingId = tm.groupValues[1].toIntOrNull() ?: -1
                    pendingBounds = null
                    continue
                }

                // ④ bounds —— 配给最近的 Task id（只认第一条；后面还有
                //    `RequestedOverrideConfiguration` 里的 `mBounds=Rect(0,0-0,0)`，取了会被 0 覆盖）
                if (pendingId >= 0 && pendingBounds == null) {
                    val bm = RX_BOUNDS_RECT.find(line) ?: RX_BOUNDS_BRACKET.find(line)
                    if (bm != null) {
                        pendingBounds = Geo(
                            bm.groupValues[1].toInt(), bm.groupValues[2].toInt(),
                            bm.groupValues[3].toInt(), bm.groupValues[4].toInt(),
                        )
                        continue
                    }
                }

                // ⑤ 任务行 —— 到这一步才把 ②③④ 攒的东西组装起来
                val rm = RX_TASK_RECORD.find(line)
                if (rm != null) {
                    val id = rm.groupValues[1].toIntOrNull()
                    // ⚠️ 实测：会**分段重复列出同一个 task** ⇒ 只认第一次出现的
                    //    （那一次才落在它自己的 display 段里，displayId 才是对的）
                    if (id != null && id == pendingId && seen.add(id)) {
                        out += TaskInfo(
                            taskId = id,
                            displayId = displayId,
                            component = rm.groupValues[2],
                            mode = stackMode,
                            bounds = pendingBounds,
                        )
                    }
                    pendingId = -1
                    pendingBounds = null
                }
            }
            return out
        }
    }
}
