package com.shware.mode.mod

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.shware.mode.shell.ShellGateway

/**
 * ★ mod 的启停与存活探测。
 *
 * ## 为什么"存活"要走 Shizuku 的 `dumpsys`，而不是 mod 反向回报
 *
 * | 手段 | 结论 |
 * |---|---|
 * | `ActivityManager.getRunningServices()` | ❌ A8+ 只返回**自己**的服务 |
 * | mod 主动绑定启动器回调（AIDL） | 🟡 更干净，但要两端共享 `.aidl` ⇒ **留 v2** |
 * | **Shizuku → `dumpsys activity services`** | ★ **本版选它**：只读、已有通道、一次调用覆盖全部 mod |
 *
 * ⚠️ 一次 `dumpsys activity services` 的输出在本机约 **2700 行** ——
 * 所以 [probeAll] 刻意**只调一次**（而不是每个 mod 一次），mod 再多也是这个成本。
 */
class ModRuntime(
    private val context: Context,
    private val shell: ShellGateway,
) {

    /**
     * ★ 拉起一个 mod。
     *
     * 用 `startForegroundService` 而不是 `startService` —— 因为 mod 必然是前台服务
     * （它要常驻画 window），而**后台启动普通服务**在 A8+ 会被系统拒掉。
     *
     * 投喂三个 extra：目标屏 id / 目标类别 / 启动器包名（见 [ModContract]）。
     */
    fun start(spec: ModSpec, displayId: Int): Result<Unit> = runCatching {
        require(spec.apiCompatible) {
            "契约版本不兼容：mod 报 api=${spec.api}，启动器只支持 ≤${ModContract.API_VERSION}"
        }
        val i = Intent(ModContract.ACTION_MOD)
            .setComponent(spec.component)
            .putExtra(ModContract.EXTRA_DISPLAY_ID, displayId)
            .putExtra(ModContract.EXTRA_TARGET, spec.target.key)
            .putExtra(ModContract.EXTRA_LAUNCHER_PACKAGE, context.packageName)
        ContextCompat.startForegroundService(context, i)
        Log.i(TAG, "→ 拉起 ${spec.id} @ display $displayId")
    }

    /**
     * 停掉一个 mod。
     *
     * mod 的 `onDestroy` 负责撤掉自己的 window —— 这条路比"启动器去删别人的窗口"干净。
     */
    fun stop(spec: ModSpec): Result<Unit> = runCatching {
        val killed = context.stopService(Intent(ModContract.ACTION_MOD).setComponent(spec.component))
        Log.i(TAG, "→ 停止 ${spec.id}（stopService 返回 $killed）")
    }

    /**
     * ★ 一次 `dumpsys` 拿到**所有** mod 的存活状态。
     *
     * ## ★★★ 为什么必须在设备侧先 `grep` 过滤（2026-09-14 实测）
     *
     * 完整输出在本机是 **334 KB / 4297 行**，而它**会随系统里服务数量增长**
     * （装个新应用就可能涨）。超过 binder 事务缓冲时抛：
     *
     * ```
     * Transaction failed on small parcel; remote process probably died
     * ```
     *
     * ⇒ `probeAll` 失败 ⇒ `FeatureRegistry` 退化成**全部"状态未知"**，
     *   而且**失败是静默的**（界面只会说"Shizuku 未连接"，而它明明连着）。
     *
     * ### 修法
     *
     * 解析只需要两类行：`ServiceRecord{…}` 与 `isForeground=true`。
     * 在**设备侧**先筛掉其余 97%：
     *
     * | | 字节 | 行数 |
     * |---|---|---|
     * | 完整输出 | 333 919 | 4297 |
     * | **只留两类行** | **10 947** | **121** |
     *
     * ★ **30 倍余量**，而且顺带快得多（少传 320 KB）。
     *
     * ⚠️ 过滤**不改变解析语义** —— [parseServiceRecords] 本来就只看这两类行，
     * 而 `grep` 保持原顺序 ⇒ `isForeground` 仍然紧跟它自己的 `ServiceRecord`。
     *
     * @return 失败 = 查不了（通常是 Shizuku 没连）⇒ 调用方应显示 [ModState.UNKNOWN]
     */
    fun probeAll(specs: List<ModSpec>): Result<Map<String, ModState>> {
        if (specs.isEmpty()) return Result.success(emptyMap())

        val dump = shell.exec(DUMP_CMD).getOrElse { return Result.failure(it) }
        val recs = parseServiceRecords(dump)

        return Result.success(
            specs.associate { s ->
                s.id to when (recs["${s.packageName}/${s.serviceName}"]) {
                    null -> ModState.ABSENT
                    true -> ModState.RUNNING
                    false -> ModState.CREATED
                }
            }
        )
    }

    /**
     * 解析 `dumpsys activity services` 的 `* ServiceRecord{…}` 块。
     *
     * ### 本机（坚果 A10）真实格式 —— 实测于 2026-09-12
     *
     * ```
     *   * ServiceRecord{8b5fdce u0 com.fbclient.app/com.follow.clash.service.VpnService}
     *     intent={cmp=com.fbclient.app/com.follow.clash.service.VpnService}
     *     packageName=com.fbclient.app
     *     processName=com.fbclient.app:remote
     *     isForeground=true foregroundId=1 foregroundNoti=…
     * ```
     *
     * ### 两条必须处理的坑
     *
     * 1. ★ 类名**有时是缩写形态**（实测：`com.qualcomm.location/.izatprovider.NetworkLocationService`）
     *    ⇒ 见到 `.` 开头要**补上包名**，否则和 `ServiceInfo.name`（全名）对不上。
     * 2. ★ 本机**没有** `started=true` 这一行（A10 不打印）
     *    ⇒ 判据只能用 **`isForeground=true`**。
     *
     * @return 键 `包名/全类名` → 值 = 该服务块里有没有 `isForeground=true`
     */
    fun parseServiceRecords(dump: String): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        var pkg: String? = null
        var cls: String? = null
        var fg = false

        fun flush() {
            val p = pkg
            val c = cls
            if (p != null && c != null) out["$p/$c"] = fg
            pkg = null; cls = null; fg = false
        }

        for (line in dump.lineSequence()) {
            val m = RX_SVC.find(line)
            if (m != null) {
                flush()
                pkg = m.groupValues[1]
                cls = fullClass(pkg!!, m.groupValues[2])
                continue
            }
            // 只认"当前这个服务块"里的行 —— 不能全局朴素搜索，否则会被别的服务串味
            if (pkg != null && line.contains("isForeground=true")) fg = true
        }
        flush()
        return out
    }

    private fun fullClass(pkg: String, cls: String): String =
        if (cls.startsWith(".")) pkg + cls else cls

    private companion object {
        const val TAG = "Mode/ModRuntime"

        /**
         * ★★ **设备侧先过滤** —— 见 [probeAll] 的说明。
         *
         * ⚠️ 别改成不带 `grep` 的版本：完整输出 334 KB，会撑爆 binder 事务，
         * 症状是**静默地**把所有 mod 报成"状态未知"。
         *
         * ⚠️ 模式里**不要写 `\{`** —— POSIX ERE 对 `\{` 的行为未定义，
         * 不同 toybox 版本表现不一致。实测本机上
         * `ServiceRecord` 与 `ServiceRecord\{` 筛出的行数**完全相同（121）**，
         * 且含 `ServiceRecord` 的行**全都是块首**（非块首的 0 行）⇒ 用前者更稳。
         *
         * ⚠️ `grep` 在本机（坚果 A10 / toybox）可用；但 `join` / `paste` **不可用**，
         * 所以这里只用 `grep`。
         */
        private const val DUMP_CMD =
            "dumpsys activity services | grep -E 'ServiceRecord|isForeground=true'"

        /**
         * `* ServiceRecord{<hash> u0 <pkg>/<cls>}`
         *
         * `.*?` 吃掉中间的 hash 与 userId；类名允许 `.` 开头（缩写）与 `$`（内部类）。
         * **不锚定行首** —— 实测行首是两个空格，锚死了反而脆。
         */
        private val RX_SVC = Regex("""ServiceRecord\{.*?\s([\w.]+)/([\w.$]+)\}""")
    }
}

/** mod 的存活状态（由 [ModRuntime.probeAll] 判定）。 */
enum class ModState(val label: String) {
    /** 有 `ServiceRecord` **且** `isForeground=true` —— 真正在跑 */
    RUNNING("运行中"),

    /**
     * 有 `ServiceRecord` 但**不在前台**。
     * 通常等于「拉起来了但没 `startForeground`」或「已降级」—— **是个要查的信号**，不是正常态。
     */
    CREATED("已创建·非前台"),

    /** 没有 `ServiceRecord` —— 没跑 */
    ABSENT("未运行"),

    /** 查不了（Shizuku 没连 / 命令失败）—— **不要显示成"未运行"**，那是撒谎 */
    UNKNOWN("未知"),
}
