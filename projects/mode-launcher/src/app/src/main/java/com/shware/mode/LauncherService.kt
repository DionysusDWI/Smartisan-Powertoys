package com.shware.mode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shware.mode.mod.ModRegistry
import com.shware.mode.mod.ModRuntime
import com.shware.mode.mod.ModSpec
import com.shware.mode.mod.ModState
import com.shware.mode.mod.ModStore
import com.shware.mode.platform.A11yGate
import com.shware.mode.platform.DisplayWatcher
import com.shware.mode.shell.SharedShell
import com.shware.mode.shell.ShellGateway
import java.util.concurrent.Executors

/**
 * ★★ 启动器的**保活前台服务** —— 用户要求的那句
 * 「**然后用那个启动器来保持它的运行**」的另一半。
 *
 * ## 它干三件事
 *
 * 1. **保活** —— `startForeground` 把宿主进程钉住（UI 被回收也不影响 mod 在跑）
 * 2. **拉起** —— 把 [ModStore] 里标记为"开"的 mod 逐个 `startForegroundService`
 * 3. ★ **看门狗** —— 每 [WATCHDOG_MS] 查一次，**掉队的补拉**
 *
 * ## 为什么保活要放在"服务"而不是"Activity"
 *
 * 宿主特意跑在手机屏（见计划书 Q §0.1），但**窗口在不在**与**进程活不活**是两件事。
 * mod 是**别的进程**，它的存活不依赖宿主 UI。所以：
 * 关掉 UI ⇒ mod 照样跑；UI 再打开 ⇒ 从服务读到实时状态。
 */
class LauncherService : Service() {

    companion object {
        private const val TAG = "Mode/LauncherSvc"
        private const val CHANNEL_ID = "mode_launcher"
        private const val NOTIF_ID = 1000
        private const val WATCHDOG_MS = 30_000L

        /** 找不到 TNT 屏时的兜底 —— 坚果的 `smt.tnt.virtual.display` 固定是它 */
        private const val DEFAULT_TNT_DISPLAY_ID = 100000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LauncherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LauncherService::class.java))
        }

        /** UI 订阅看门狗成果。`null` 表示 UI 没在看（服务照跑）。 */
        @Volatile
        var onReport: ((Report) -> Unit)? = null

        /** 最近一次的巡查结果 —— UI 后开也能立刻显示，不用等下一个 30s */
        @Volatile
        var lastReport: Report? = null
    }

    /** 一次巡查的快照。 */
    data class Report(
        val at: Long,
        val mods: List<ModSpec>,
        val states: Map<String, ModState>,
        val enabled: Set<String>,
        val restarted: List<String>,
        val note: String,
    )

    private lateinit var shell: ShellGateway
    private lateinit var store: ModStore
    private lateinit var runtime: ModRuntime
    private lateinit var registry: ModRegistry
    private lateinit var watcher: DisplayWatcher

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mode-watchdog").apply { isDaemon = true }
    }

    private var alive = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            io.execute { syncOnce() }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        shell = SharedShell.get(this)
        store = ModStore(this)
        runtime = ModRuntime(this, shell)
        registry = ModRegistry(this)
        watcher = DisplayWatcher(this)
        watcher.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!alive) {
            alive = true
            // ★ 服务自己也要把 Shizuku 连上 —— 否则重启后没人连它，mod 状态永远"未知"
            if (shell.isShizukuAlive() && shell.hasPermission()) shell.bind()
            handler.post(ticker)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        handler.removeCallbacks(ticker)
        watcher.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 巡查

    /**
     * ★ 一次完整巡查：发现 → 读开关 → 查存活 → **补拉掉队的**。
     *
     * ⚠️ **只在 `ABSENT` 时补拉**。
     * `CREATED`（有服务但非前台）说明它已经被拉起来过、只是没 `startForeground` ——
     * 那种情况再拉一次会**开出第二个实例**，把问题搞得更糊。
     * 这种情况应当报给用户看，而不是自作主张。
     */
    private fun syncOnce() {
        val mods = registry.discover()
        val enabled = store.explicitEnabledIds()
        val restarted = ArrayList<String>()
        val notes = ArrayList<String>()

        // ★★ ① 先把「人工门」修好 —— 必须排在补拉之前
        //    因为一个靠无障碍收按键的 mod，即使服务被拉起来了，门没开也是白搭。
        healA11y(mods, enabled)?.let { notes += it }

        // ⚠️ 补拉**不能**用 notes 是否为空来把关 ——
        //    上面那句自愈一旦有话说就会把 notes 撑非空，于是**补拉被整个跳过**。
        //    ★ 这正是本项目反复踩的"一个变量兼两种语义"的坑（见 AP §7.13）。
        //    ⇒ 用**显式标志**表示"存活状态查到了没有"。
        var probeOk = true
        val states: Map<String, ModState> = runtime.probeAll(mods).getOrElse {
            probeOk = false
            notes += "查不了存活状态：${it.message}（Shizuku 连上了吗）"
            mods.associate { m -> m.id to ModState.UNKNOWN }
        }

        if (probeOk) {
            for (m in mods) {
                if (m.id !in enabled) continue
                if (!m.apiCompatible) continue
                if (states[m.id] != ModState.ABSENT) continue
                val displayId = targetDisplay(m)
                val r = runtime.start(m, displayId)
                if (r.isSuccess) restarted += m.id
                else notes += "拉 ${m.id} 失败：${r.exceptionOrNull()?.message}"
            }
            if (restarted.isNotEmpty()) {
                notes += "补拉了 ${restarted.joinToString()}"
                Log.i(TAG, "补拉: $restarted")
            }
        }

        val rep = Report(
            at = System.currentTimeMillis(),
            mods = mods,
            states = states,
            enabled = enabled,
            restarted = restarted,
            note = notes.joinToString("；"),
        )
        lastReport = rep
        handler.post { onReport?.invoke(rep) }
    }

    /**
     * ★★★★★ **自愈「无障碍」这道人工门**（2026-09-14 新增）。
     *
     * ## 为什么需要
     *
     * 无障碍服务必须用户手动开，而 **`am force-stop <包名>` 会把它整个抹掉**
     * ⇒ 功能**静默失效**：mod 服务还在前台跑、界面绿点还亮着，
     * **可按键根本没人在听**。
     *
     * ★ 实测事故：2026-09-14 我为做冷启动测试 force-stop 了十几次，
     *   把用户已经在用的 **TNT GO 亮度键**弄坏了，而界面上完全看不出来。
     *
     * ## 为什么宿主能修
     *
     * 宿主有 **Shizuku**（`shell` 身份），而 `settings put secure …` 正是 shell 的权限
     * （`deploy.sh` 一直就是用 `adb shell` 干这件事的）。
     *
     * ## ⚠️⚠️ 必须是「追加」而不是「覆盖」
     *
     * `settings put secure enabled_accessibility_services <值>` 是**整体替换**。
     * 直接写我们自己的组件，会把**用户其它应用的无障碍服务全部关掉**
     * ——读屏、手势、自动化工具全废。那是比原问题严重得多的副作用。
     *
     * ⇒ 一律走 [A11yGate.ensureEnabledCommand]：**先读 → 合并 → 再写**，
     *   全程在**同一个 shell 进程**里（读-改-写之间不留窗口）。
     *
     * ## 边界
     *
     * - 只处理**已启用**且**申报了 `MOD_A11Y`** 的 mod（没申报的一律不碰，**向后兼容**）
     * - 只在**缺**的时候动手（已经是开的就一次命令都不发）
     * - Shizuku 没连 ⇒ 什么都不做，返回一句说明（**不报错**，因为它不是错误）
     *
     * @return 需要写进巡查报告的一句话；**什么都没做时返回 `null`**
     *         （★ 刻意不用空串 —— 空串和"有话说但内容为空"分不开，正是上一个坑的成因）
     */
    private fun healA11y(mods: List<ModSpec>, enabled: Set<String>): String? {
        val want = mods.filter { it.id in enabled }
            .mapNotNull { it.a11y }
            .distinct()
        if (want.isEmpty()) return null

        // 已经是开的就别动 —— 一次 shell 都不发
        val missing = want.filter { !A11yGate.isOpen(this, it) }
        if (missing.isEmpty()) return null

        val err = missing.mapNotNull { comp ->
            shell.exec(A11yGate.ensureEnabledCommand(comp.flattenToString())).exceptionOrNull()
                ?.let { "重设无障碍失败(${comp.className.substringAfterLast('.')})：${it.message}" }
        }
        if (err.isNotEmpty()) return err.joinToString("；")

        // ★ 复核 —— 不验下游就报"已修复"是假阳性（本项目的老教训）
        val still = missing.filter { !A11yGate.isOpen(this, it) }
        return if (still.isEmpty()) {
            val names = missing.joinToString { it.className.substringAfterLast('.') }
            Log.i(TAG, "无障碍已自愈: $names")
            "自愈无障碍：$names"
        } else {
            "无障碍自愈没生效，请手动开启：${still.joinToString { it.flattenToString() }}"
        }
    }

    /** mod 申报的 [ModSpec.Target] → 真实 displayId。 */
    private fun targetDisplay(spec: ModSpec): Int = when (spec.target) {
        ModSpec.Target.TNT -> watcher.defaultTarget()?.id ?: DEFAULT_TNT_DISPLAY_ID
        ModSpec.Target.PHONE -> android.view.Display.DEFAULT_DISPLAY
    }

    // ------------------------------------------------------------------ 通知

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MODE 启动器", NotificationManager.IMPORTANCE_MIN)
        )

        val pi = PendingIntent.getActivity(
            this, 0,
            // ★ 点通知进**主界面**（任务 AP 起用户入口从工程台换成了 HomeActivity）
            Intent(this, com.shware.mode.ui.HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Smartisan Powertoys 运行中")
            .setContentText("保持已启用的组件常驻")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()

        startForeground(NOTIF_ID, notif)
    }
}
