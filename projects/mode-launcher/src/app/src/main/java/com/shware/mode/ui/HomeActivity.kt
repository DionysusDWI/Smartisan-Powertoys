package com.shware.mode.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.shware.mode.LauncherService
import com.shware.mode.MainActivity
import com.shware.mode.R
import com.shware.mode.core.feature.Feature
import com.shware.mode.core.feature.FeatureRegistry
import com.shware.mode.platform.DisplayWatcher
import com.shware.mode.shell.SharedShell
import com.shware.mode.shell.ShellGateway
import java.util.concurrent.Executors

/**
 * ★★★★★ **主界面**（任务 AP / AP5）—— 把"工程台"变成"产品"。
 *
 * ## 它和 [MainActivity] 的分工
 *
 * | | 给谁看 | 内容 |
 * |---|---|---|
 * | ★ **本界面** | **用户** | 功能卡片列表：开关、状态、设置 |
 * | [MainActivity] | 开发者 | 940 行的自检台（显示枚举 / 权限 / 手势 / 原始 dump） |
 *
 * ⇒ [MainActivity] 被**降级到「开发者选项」入口**，但**一行都没删** ——
 * 它的自检能力是资产（见计划书 AP §7 风险 5）。
 *
 * ## ★★★ 可扩展性怎么落地的
 *
 * 这个 Activity **不知道任何具体功能的名字**。它只做三件事：
 * 1. 向 [FeatureRegistry] 要一份 `List<Feature>`
 * 2. 按 `Feature.category` **分组**（分组表是数据，不是 if-else）
 * 3. 每张卡片都渲染成**同一个** [R.layout.pt_item_feature]
 *
 * ⇒ **新增一个 mod，这个文件一行都不用改。**
 * 新增一个**分类**，也只要在 `Feature.Category` 里加一个枚举值。
 *
 * ## 为什么加载要放到线程池
 *
 * [FeatureRegistry.load] 会走一次 `dumpsys activity services`（约 2700 行）
 * ⇒ 主线程调它会直接 ANR。所以：**先渲染骨架（只用 PackageManager），
 * 再把实时状态补上**。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var registry: FeatureRegistry
    private lateinit var watcher: DisplayWatcher
    private lateinit var adapter: FeatureAdapter

    private lateinit var list: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var bannerStatus: TextView

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pt-home-load").apply { isDaemon = true }
    }

    private var features: List<Feature> = emptyList()
    private var loading = false

    /** Shizuku 状态订阅 —— 存一份是为了 onDestroy 能退订（不退会泄漏 Activity） */
    private var shellListener: ((ShellGateway.State) -> Unit)? = null

    /**
     * 有没有一次"补实时状态"因为正忙而没能立刻跑 —— 见 [refreshQuiet] 与 [refresh]。
     *
     * ★ 用"记下来等会儿补"而不是"丢弃"：调用它的时机（Shizuku 刚 READY）
     *   **恰好**常常撞在骨架那次刷新还没跑完的时候，丢掉就等于**订阅白订了**。
     */
    private var pendingQuiet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pt_activity_home)

        // ★ 共享 ShellGateway —— 不要在这里 new 一个，
        //   否则每开一次界面就多堆一个 :shellsvc 进程（见 SharedShell 的说明）
        val shell = SharedShell.get(this)
        registry = FeatureRegistry(this, shell)
        watcher = DisplayWatcher(this)

        list = findViewById(R.id.list)
        emptyBox = findViewById(R.id.emptyBox)
        bannerStatus = findViewById(R.id.bannerStatus)

        adapter = FeatureAdapter(
            onClickToggle = ::onToggle,
            onOpenSettings = ::onOpenSettings,
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.isNestedScrollingEnabled = false

        findViewById<MaterialButton>(R.id.btnLayers).setOnClickListener {
            startActivity(Intent(this, LayerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnDev).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 保活服务 —— 让看门狗按开关把掉队的组件补拉回来
        LauncherService.start(this)

        // ★ 横幅**同步**设置 —— 它只依赖 DisplayManager，不依赖 Shizuku。
        //   见 updateBanner() 里对那次回归的说明。
        updateBanner()

        if (shell.isShizukuAlive() && shell.hasPermission() && shell.state != ShellGateway.State.READY) {
            shell.bind()
        }

        /*
         * ★★★ 等 Shizuku 绑好再查状态 —— 否则必然查出一个假的"全部未知"。
         *
         * 实测（2026-09-14）：onCreate 里立刻查，比 UserService 绑定完成早约 180ms，
         * 于是每张卡片都显示「状态未知（Shizuku 未连接或查询失败）」，
         * 而且**再也不会自己恢复**。见 ShellGateway.addStateListener 的说明。
         *
         * ⚠️ 注册顺序**不能**和 refresh 抢 —— refreshQuiet() 自带合并逻辑，
         *    忙碌时会记一个 pending，等本次刷新收尾再补跑（见那里的说明）。
         */
        shellListener = { st ->
            if (st == ShellGateway.State.READY) runOnUiThread { refreshQuiet() }
        }
        shell.addStateListener(shellListener!!)
        // 已经 READY 的话上面的订阅不会补发，这里补一次
        if (shell.state == ShellGateway.State.READY) refreshQuiet()

        // ★ 先把骨架画出来（这一步只查 PackageManager，很快），再补实时状态
        refresh(full = false)
    }

    /**
     * ★★★ 修 N2（2026-09-15 AR7 实机抓到）：**回到前台 ⇒ 补一次实时状态。**
     *
     * ## 为什么非有不可
     *
     * 原来全工程 [refreshQuiet] 只有 4 个调用点：`onCreate`（若 shell 已 READY）、
     * shell READY 回调、`onDestroy` 收尾放行、**以及 toggle 之后**。
     * 而 `onResume` / `onNewIntent` **都没有覆写** ⇒
     *
     * - 从后台切回来：**不刷新**
     * - `am start` 到已在栈顶的实例：只走默认 `onNewIntent`（空实现）⇒ **不刷新**
     * - 也没有周期轮询（全文件只有 `LayerActivity` / `MainActivity` 有 `postDelayed`）
     *
     * ⇒ 卡片上的「运行中 / 未运行」和按钮文案会**冻结在上一次刷新那一刻**，
     * 可以陈旧任意久。
     *
     * ## 后果（实机复现，见 `.paper/plans/AR-审计整改.md` §9）
     *
     * 卡片显示「开关 ON ＋ 未运行 ＋ 按钮『启动』」，而
     * `dumpsys activity services com.shware.mode` 同时显示
     * `TntgoBatteryService isForeground=true` —— **服务其实正在跑**。
     * 用户照卡片点「启动」⇒ 写的是同一个值 ⇒ **Toast 说「已启用」、界面毫无变化**
     * ⇒ 被报成「按钮不写开关」，白白排查了一轮。
     *
     * ★ 实测佐证：点完开关触发一次 [refreshQuiet] 后，列表**立刻自洽**
     *   （`switchOn=true` ＋ `btnToggle='停止'` ＋ `stateLine='运行中'`）。
     */
    override fun onResume() {
        super.onResume()
        refreshQuiet()
    }

    /**
     * ★ `am start` 打到**已在栈顶**的实例时走这里 —— 此时 **`onResume` 不会被调**
     * （Activity 已经是 RESUMED 状态），所以这两条**必须都有**。
     *
     * ★ 脚本化调试（`am start` → dump → tap）整条链路都靠这条路，
     *   不覆写它 ⇒ 界面对脚本"装死"，而 logcat 一个错都不报。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshQuiet()
    }

    override fun onDestroy() {
        shellListener?.let { SharedShell.peek()?.removeStateListener(it) }
        shellListener = null
        io.shutdownNow()
        watcher.stop()
        super.onDestroy()
    }

    /**
     * 刷新功能列表。
     *
     * @param full `true` = 连实时运行状态一起查（会走 Shizuku 的 dumpsys，慢）；
     *             `false` = 只发现（快，首帧用）
     */
    private fun refresh(full: Boolean) {
        if (loading) return
        loading = true
        adapter.submit(emptyList(), loading = true)

        io.execute {
            val list0 = if (full) registry.load() else registry.discoverOnly()
            runOnUiThread {
                loading = false
                features = list0
                adapter.submit(FeatureRows.build(list0), loading = false)
                emptyBox.visibility = if (list0.isEmpty()) View.VISIBLE else View.GONE

                // ★ 收尾时把等待中的那次"补实时状态"放出去 —— 见 refreshQuiet()
                if (pendingQuiet) {
                    pendingQuiet = false
                    refreshQuiet()
                }
            }
        }
    }

    /**
     * 横幅（TNT 屏在不在）—— **同步**，不走线程池。
     *
     * ## ⚠️ 为什么必须和 [refresh] 解耦（2026-09-14 实测回归）
     *
     * 原本横幅文字是在 [refresh] 的 io 回调里设的。而 `onCreate` 里
     * 「已经 READY 就先补一次实时状态」会**先**调 [refreshQuiet]，
     * 它把 `loading` 置 true ⇒ 紧接着的 `refresh()` 撞上自己的
     * `if (loading) return` **直接返回** ⇒ **横幅永远是空的**。
     *
     * 截图证据：`.ref/ui_assets/shots/` 里出现过横幅只剩一个空色块的那张。
     *
     * ★ 根因是**一个 `loading` 布尔守两种不同的异步操作** ——
     *   这种竞态靠调整调用顺序是躲不掉的（换了顺序会在另一头炸）。
     *   ⇒ 真正解耦：横幅只依赖 `DisplayManager`（同步、便宜），
     *     根本不该等着功能列表那次 io。
     */
    private fun updateBanner() {
        val displays = runCatching { watcher.snapshot() }.getOrDefault(emptyList())
        val tnt = runCatching { watcher.defaultTarget() }.getOrNull()
        bannerStatus.text = if (tnt != null) {
            "TNT 屏已就绪  ·  display ${tnt.id}  ·  ${tnt.width}×${tnt.height}"
        } else {
            "未检测到 TNT 屏  ·  已发现 ${displays.size} 块屏"
        }
    }

    /**
     * 补一次实时状态（不闪骨架）。
     *
     * ★★ **忙碌时记 pending，而不是直接丢弃** ——
     * 因为调用它的时机（"Shizuku 刚 READY"）**恰好**常常撞在骨架那次刷新还没跑完的时候。
     * 早期版本直接 `return`，于是**订阅到了 READY 却什么也没发生**，
     * 界面就一直停在「状态未知」。见 [updateBanner] 里对同一个根因的说明。
     */
    private fun refreshQuiet() {
        if (loading) {
            pendingQuiet = true
            return
        }
        loading = true
        io.execute {
            val list0 = runCatching { registry.load() }.getOrElse { features }
            runOnUiThread {
                loading = false
                features = list0
                adapter.submit(FeatureRows.build(list0), loading = false)
                emptyBox.visibility = if (list0.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 开 / 关一个功能。
     *
     * ★ 语义刻意做成**一件事**：「开关 = 启用」，而"启用"包含
     * **立刻拉起** ＋ **交给看门狗保活**。
     *
     * ⚠️ 不做"开关只记状态、按钮才真正启停"那种拆分 ——
     * 因为看门狗每 30s 会把「已启用但没在跑」的补拉回来，
     * 于是「按钮停止」会在半分钟内被撤销，用户会觉得**开关是坏的**。
     */
    private fun onToggle(f: Feature, on: Boolean) {
        registry.setEnabled(f.id, on)
        io.execute {
            val r = if (on) {
                val tnt = runCatching { watcher.defaultTarget()?.id }.getOrNull() ?: DEFAULT_TNT
                registry.start(f, registry.targetDisplay(f, tnt))
            } else {
                registry.stop(f)
            }
            val msg = if (r.isSuccess) {
                if (on) "已启用「${f.name}」" else "已停用「${f.name}」"
            } else {
                "「${f.name}」${if (on) "启动" else "停止"}失败：${r.exceptionOrNull()?.message}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshQuiet()
            }
        }
    }

    private fun onOpenSettings(f: Feature) {
        val c = f.settings ?: return
        runCatching { startActivity(Intent().setComponent(c)) }
            .onFailure { Toast.makeText(this, "打不开设置界面：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        /** 找不到 TNT 屏时的兜底 —— 坚果的 `smt.tnt.virtual.display` 固定是它 */
        const val DEFAULT_TNT = 100000
    }
}

/**
 * ★ **卡片上那一行状态文字**怎么拼 —— 一处集中，UI 与 API 共用同一套说法。
 *
 * 这样「界面上看到的」与「API 报出去的」**永远不会是两套词**。
 */
internal object FeatureText {

    fun stateLabel(s: Feature.State): String = when (s) {
        Feature.State.RUNNING -> "运行中"
        Feature.State.STOPPED -> "未运行"
        Feature.State.UNKNOWN -> "状态未知"
        Feature.State.ERROR -> "异常"
    }

    /**
     * `运行中  ·  TNT 屏  ·  可交互  ·  v0.2.0`
     *
     * ⚠️ 状态是**未知**时要显式说明为什么（Shizuku 没连），
     * 而不是让用户以为"它就是没跑" —— 那是在撒谎。
     *
     * ★ 版本号**只取主三段**：`0.2.0-powertoys` → `v0.2.0`。
     *   实测（2026-09-14）写全会把这一行挤到放不下，被截成 `v0.2.0-powe…`；
     *   而后缀 `-powertoys` 本来也没有信息量（整个应用就叫这个名字）。
     *   完整版本号在对外 API 的 `describeFeature()` 里仍然给全。
     */
    fun stateLine(f: Feature): String = buildString {
        append(stateLabel(f.state))
        if (f.state == Feature.State.UNKNOWN) append("（Shizuku 未连接或查询失败）")
        append("  ·  ")
        append(f.target.label)
        append("  ·  ")
        append(f.touch.label)
        f.versionName?.substringBefore('-')?.takeIf { it.isNotBlank() }?.let { append("  ·  v$it") }
        if (!f.apiCompatible) append("  ·  ⚠ 契约 v${f.api} 不兼容")
    }

    /** 状态点颜色 —— 与文字**同源**，不会出现"绿点配未运行" */
    fun stateColor(s: Feature.State): Int = when (s) {
        Feature.State.RUNNING -> R.color.pt_state_running
        Feature.State.STOPPED -> R.color.pt_state_stopped
        Feature.State.UNKNOWN -> R.color.pt_state_unknown
        Feature.State.ERROR -> R.color.pt_state_error
    }
}
