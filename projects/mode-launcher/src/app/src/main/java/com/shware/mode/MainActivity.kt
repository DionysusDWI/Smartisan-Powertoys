package com.shware.mode

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.shware.mode.core.Geo
import com.shware.mode.core.InputDevice
import com.shware.mode.core.Shortcuts
import com.shware.mode.input.KeyStream
import com.shware.mode.input.MetaKeyService
import com.shware.mode.mod.ModContract
import com.shware.mode.mod.ModRegistry
import com.shware.mode.mod.ModRuntime
import com.shware.mode.mod.ModSpec
import com.shware.mode.mod.ModState
import com.shware.mode.mod.ModStore
import com.shware.mode.platform.DisplayWatcher
import com.shware.mode.platform.OverlayProbe
import com.shware.mode.shell.SharedShell
import com.shware.mode.shell.ShellGateway
import com.shware.mode.shell.ShortcutEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最小框架的控制台。
 *
 * 刻意做得很土 —— 它是**工程验证台**，不是产品 UI。
 * 目标是把「五件事」逐条点出来、看到结果：
 *   1. 装得上跑得起来（本页即是）
 *   2. TNT 屏枚举 + 热插拔监听
 *   3. ★ TNT 屏 overlay + z-order
 *   4. ★ Shizuku 通 + 四个原语
 *   5. ★ KEY_RIGHTMETA 能被截到
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var primitivesRow: LinearLayout

    /** ★ 窗口搬运：动态任务列表（每次刷新重建） */
    private lateinit var moveTasks: LinearLayout
    private lateinit var keyStatus: TextView
    private lateinit var keyView: TextView
    private lateinit var modStatus: TextView
    private lateinit var modListRow: LinearLayout

    private val watcher by lazy { DisplayWatcher(this) }
    private val overlayProbe by lazy { OverlayProbe(this) }

    /**
     * ★ 与 [LauncherService] **共享**同一个 gateway。
     * 各建各的会让 Shizuku 每绑一次就多起一个 `com.shware.mode:shellsvc` 进程
     * （实测已堆到 3 个）。见 [SharedShell]。
     */
    private val shell by lazy { SharedShell.get(this) }

    // ★★ Mod 框架（见 [com.shware.mode.mod.ModContract]）
    private val modRegistry by lazy { ModRegistry(this) }
    private val modStore by lazy { ModStore(this) }
    private val modRuntime by lazy { ModRuntime(this, shell) }
    private var modSpecs: List<ModSpec> = emptyList()
    private var modStates: Map<String, ModState> = emptyMap()

    /** 只用于"给它 1.5s 起前台再回读状态"这类短延迟 */
    private val handler = Handler(Looper.getMainLooper())

    /** 播放测试音用（验证播放捕获） */
    private var testTone: android.media.ToneGenerator? = null

    /** ★ 按键流（第 5 件的正解：Shizuku shell + `getevent`，不用无障碍） */
    private val keyStream by lazy { KeyStream(shell) }

    /** ★ 快捷键引擎 —— 把读到的组合键变成真动作（任务 N） */
    private val shortcutEngine by lazy { ShortcutEngine(shell, watcher, packageName) }

    private var inputDevices: List<InputDevice> = emptyList()
    private var pickedDevice: InputDevice? = null
    private val keyLines = ArrayDeque<String>()

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    private var overlayOnDisplay: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★★★ 强制宿主留在【手机屏】—— 用户 2026-09-12 明确要求。
        //
        // 背景：Smartisan 的 TNT（PC 模式）会把 App **自动纳管**到 TNT 屏
        // （logcat 实证：`SmartisanLaunch: … reason: pc mode`）。
        // 此时窗口会被**拉伸投射到手机屏显示、文字发糊** ——
        // 那不是「原生手机应用」，用户明确否定了这个状态。
        //
        // ⇒ 检测到自己不在 display 0 时，**以 DEFAULT_DISPLAY 重新启动自己**，再结束旧实例。
        // ⚠️ 若 TNT 的接管逻辑（system_server 侧）会再次拉走，这条路就不成立 ——
        //    那时改走 Manifest（`resizeableActivity=false` + 竖屏）。见计划书 Q §0.1。
        val curDisplay = windowManager.defaultDisplay.displayId
        if (curDisplay != Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "★ 宿主被 TNT 纳管到 display $curDisplay —— 重新以手机屏启动")
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(Intent(this, MainActivity::class.java), opts.toBundle())
            finish()
            return
        }

        setContentView(buildUi())

        // TNT 屏变化 → 刷新状态行
        watcher.onChanged = { snaps, what ->
            log(what)
            log(snaps.joinToString("\n") { "    " + it.oneLine() })
            refreshStatus()
        }

        // Shizuku 状态
        shell.onState = { st, msg ->
            runOnUiThread {
                log("[Shizuku/$st] $msg")
                refreshStatus()
                // ★ 就绪时顺手把「窗口搬运」列表填上（否则要手动点刷新）
                if (st == ShellGateway.State.READY) runCatching { refreshMoveList() }
            }
        }

        // 无障碍按键（★ 已弃用路径，保留只为留证据）
        MetaKeyService.onEvent = { s -> runOnUiThread { log("[无障碍] $s") } }

        // ★ 按键流（Shizuku + getevent）—— 每条都进按键区，组合键额外进主日志
        keyStream.onStroke = { stroke, _ ->
            appendKey(stroke.oneLine())
            if (stroke.isModifier) log("[按键] ${stroke.oneLine()}")
        }
        keyStream.onCombo = { combo ->
            appendKey("  ⇒ ★★ 组合键: $combo")
            // ★ 交给快捷键引擎。返回 true = 这个组合在 TNT 表里，已被接管。
            if (shortcutEngine.handle(combo)) log("★★ 组合键命中: $combo → 快捷键引擎")
        }
        // 引擎跑在后台线程，回报要切回主线程
        shortcutEngine.onResult = { ok, msg ->
            runOnUiThread {
                appendKey("      $msg")
                log(if (ok) msg else "✗ $msg")
            }
        }
        keyStream.onClosed = { reason ->
            appendKey("──── 抓键结束: $reason")
            log("──── 抓键结束: $reason")
            refreshKeyStatus()
        }
        keyStream.onError = { msg -> log("✗ $msg") }

        watcher.start()
        shell.start()
        log("=== MODE 启动器 · 工程台启动 ===")
        log("包名: $packageName   版本: ${BuildConfigCompat.VERSION}")
        refreshStatus()
        refreshKeyStatus()

        // ★ 保活服务的巡查成果 —— 服务是常驻的，UI 只是订阅者（可以随时退订重订）
        LauncherService.onReport = { rep ->
            runOnUiThread {
                modSpecs = rep.mods
                modStates = rep.states
                log("⟳ 巡查：${rep.mods.size} 个 mod，已启用 ${rep.enabled.size} 个" +
                        (if (rep.note.isEmpty()) "" else "  ⚠️ ${rep.note}"))
                rep.restarted.forEach { log("   ↑ 补拉 $it") }
                renderMods()
                refreshModStatus()
            }
        }
        // UI 后开 ⇒ 立刻显示上一轮结果，不用干等 30s
        LauncherService.lastReport?.let {
            modSpecs = it.mods; modStates = it.states; renderMods(); refreshModStatus()
        }
        // Mod 分区先摆上"还没扫"的空态，别留一条空白条
        renderMods()
        refreshModStatus()
    }

    override fun onDestroy() {
        watcher.stop()
        keyStream.stop()
        shortcutEngine.shutdown()
        MetaKeyService.onEvent = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): ScrollView {
        val pad = dp(14)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#1B5E20"))
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root += statusView

        root += sectionTitle("① TNT 屏探测（无需权限）")
        root += row(
            button("刷新显示拓扑") { dumpDisplays() },
            button("看TNT 屏候选") { dumpExternal() },
        )

        root += sectionTitle("② ★ TNT 屏 overlay（清单 #7：z-order）")
        root += row(
            button("显示 overlay") { showOverlay() },
            button("隐藏") { hideOverlay() },
        )
        root += row(
            button("★ 查 z-order") { probeZOrder() },
            button("授权 overlay") { requestOverlayPermission() },
        )
        root += row(
            // ★ 验证 mod-livecaption 的【播放捕获】：本 app 是 targetSdk 35
            //   ⇒ 按 Android 规则「允许被普通 app 捕获」，且 uid 与 mod 不同。
            button("♪ 播放测试音 8s") { playTestTone() },
            button("停止") { stopTestTone() },
        )

        root += sectionTitle("③ ★ Shizuku / 原语")
        root += row(
            button("连接 / 授权") { connectShizuku() },
            button("身份自检") { checkIdentity() },
        )
        root += row(
            button("列任务") { listTasks() },
            button("列TNT 屏任务") { listExternalTasks() },
        )
        primitivesRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        primitivesRow += row(
            button("启动计算器→TNT 屏") { quickLaunch() },
            button("改几何") { quickResize() },
        )
        primitivesRow += row(
            button("点一下TNT 屏") { quickTap() },
            button("全部自测") { runAllPrimitives() },
        )
        // ★★★ 2026-09-12 新增：跨屏搬运（补齐 Primitives 里一直缺的那一条）
        primitivesRow += row(
            button("列 stack") { showStacks() },
            button("★ 搬运自测") { runMoveSelfTest() },
        )
        root += primitivesRow

        // ★★★ 2026-09-12 新增：把「跨屏搬运」接到真业务上 —— 不再只有自测
        root += sectionTitle("★ 窗口搬运（搬到另一屏）")
        root += row(
            button("刷新列表") { refreshMoveList() },
            button("★ TNT顶窗→手机") { moveTopTntToPhone() },
        )
        moveTasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root += moveTasks

        // ★★★ 2026-09-12 新增：音频输出切换（起因：搬运后音频不跟着走）
        root += sectionTitle("★ 音频输出（全局媒体路由）")
        root += row(
            button("★ 音频→TNT GO") { setAudio("tnt") },
            button("★ 音频→手机") { setAudio("phone") },
        )

        root += sectionTitle("④ ★ 按键捕获（Shizuku shell + getevent）")
        keyStatus = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#4A148C"))
            setBackgroundColor(Color.parseColor("#F3E5F5"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root += keyStatus
        root += row(
            button("列输入设备") { dumpInputDevices() },
            button("★ 抓 TNT GO 键盘") { pickAndStartKeys() },
        )
        root += row(
            button("停止抓键") { stopKeys() },
            button("清空按键") { keyLines.clear(); renderKeys() },
        )
        keyView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#1A237E"))
            setBackgroundColor(Color.parseColor("#E8EAF6"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setTextIsSelectable(true)
            text = "（还没抓到按键）"
        }
        root += keyView

        root += sectionTitle("④b ★ TNT 快捷键表（.paper/03 §2.2 win 列 —— 真机生效的那列）")
        root += TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#004D40"))
            setBackgroundColor(Color.parseColor("#E0F2F1"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
            text = buildString {
                Shortcuts.TABLE.forEach {
                    appendLine("  ${it.combo.padEnd(14)} ${it.tntName}")
                }
                append("  ⛔ Win+M / Win+Q / Win+H 在 win 列是 NONE，本机无效 —— 刻意不做")
            }
        }

        root += sectionTitle("⑥ ★★ Mod 管理器（mod = 独立 APK，契约见 ModContract）")
        root += row(
            button("扫描 mod") { scanMods() },
            button("刷新状态") { refreshModStates() },
        )
        root += row(
            button("★ 启动保活服务") { startKeepAlive() },
            button("停止保活服务") { stopKeepAlive() },
        )
        modStatus = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#E65100"))
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root += modStatus
        modListRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root += modListRow

        root += sectionTitle("⑤ 旧路径 · 已弃用（留作证据）")
        root += row(button("打开无障碍设置") { openAccessibilitySettings() })
        root += row(button("清空日志") { clearLog() })

        root += sectionTitle("日志")
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#212121"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setTextIsSelectable(true)
        }
        root += logView

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun row(vararg v: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { lp -> lp.marginEnd = dp(4) }
            addView(it)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { runCatching { onClick() }.onFailure { log("✗ 异常: ${it.stackTraceToString().take(500)}") } }
    }

    private fun dp(n: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics
    ).toInt()

    private operator fun LinearLayout.plusAssign(v: android.view.View) { addView(v) }

    // ------------------------------------------------------------------ 动作

    private fun dumpDisplays() {
        val snaps = watcher.snapshot()
        log("── 显示拓扑（${snaps.size} 个）──")
        snaps.forEach { log("    " + it.oneLine() + "  state=${DisplayWatcher.stateName(it.state)}") }
        watcher.defaultTarget()?.let { log("★ 默认目标TNT 屏 = display ${it.id}（${it.width}x${it.height}）") }
        refreshStatus()
    }

    private fun dumpExternal() {
        val ext = watcher.externalCandidates()
        if (ext.isEmpty()) log("⚠️ 没有TNT 屏候选（isPresentation && !isPrivate && !default）")
        else ext.forEach { log("★ TNT 屏候选: " + it.oneLine()) }
    }

    private fun showOverlay() {
        val ext = watcher.defaultTarget()
        if (ext == null) { log("✗ 没有TNT 屏候选，改画在主屏"); return }
        val err = overlayProbe.show(ext.id, "TNT-WM overlay\ndisplay ${ext.id}")
        if (err == null) {
            overlayOnDisplay = ext.id
            log("✓ overlay 已显示在 display ${ext.id}（${ext.width}x${ext.height}）")
        } else {
            log("✗ overlay 失败: $err")
        }
    }

    private fun hideOverlay() {
        val err = overlayProbe.hide()
        if (err == null) { overlayOnDisplay = -1; log("✓ overlay 已移除") } else log("✗ $err")
    }

    /**
     * ★ 清单 #7 的核心动作：把TNT 屏上的窗口按 z-order 列出来，
     * 看我们的 overlay 落在**应用窗口之上还是之下**、**系统栏之上还是之下**。
     *
     * `dumpsys window windows` 里的 `Window #N` 是**从底到顶**排的。
     */
    private fun probeZOrder() {
        if (overlayOnDisplay < 0) { log("⚠️ 先点「显示 overlay」"); return }
        log("── display $overlayOnDisplay 的窗口 z-order（底 → 顶）──")
        // ⚠️ 不用 `grep -B1` —— 坚果 toybox 的 grep 对 `-B` 支持存疑（子代理提示，未实测）。
        //    改成一次抓两类行，在 Kotlin 里按顺序配对（只依赖 `grep -E`，实测可靠）。
        val out = shell.exec(
            "dumpsys window windows | grep -E 'Window #[0-9]+ Window|mDisplayId='"
        ).getOrElse {
            log("✗ 需要 Shizuku：${it.message}"); return
        }

        // 配对：窗口标题行 + 紧随其后的 mDisplayId 行
        val want = Regex("""\bmDisplayId=$overlayOnDisplay\b""")
        var title: String? = null
        var hit = 0
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("Window #")) {
                title = t
            } else if (title != null && want.containsMatchIn(t)) {
                log("    " + title.take(140))
                title = null
                hit++
            }
        }
        if (hit == 0) log("    （display $overlayOnDisplay 上没找到窗口）")
        log("    ↑ 越靠后越在上层；我们的是含 'com.shware.mode' 的那行")
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    // ------------------------------------------------------------------ 播放测试音

    /**
     * ★★ 播放一段测试音 —— **用来验证 `mod-livecaption` 的【播放捕获】**。
     *
     * 为什么要放在**启动器**里而不是 livecaption 自己里：
     * **捕获方抓不到自己的声音**（uid 相同会被排除）。
     * 启动器是**另一个 app、另一个 uid**，而且 `targetSdk=35`
     * ⇒ 按 Android 规则【允许被普通 app 捕获】。
     *
     * 对照：坚果自带浏览器 `targetSdk=28` ⇒ 默认 `ALLOW_CAPTURE_BY_SYSTEM`
     * ⇒ **普通 app 抓不到**（这正是 livecaption 抓浏览器抓不到的原因）。
     */
    private fun playTestTone() {
        stopTestTone()
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            testTone = tg
            log("♪ 播放测试音（STREAM_MUSIC = USAGE_MEDIA），8 秒…")
            // DTMF 音序列，持续 8 秒
            val seq = intArrayOf(
                ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
                ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5,
            )
            var i = 0
            val h = Handler(Looper.getMainLooper())
            val step = object : Runnable {
                override fun run() {
                    if (testTone == null || i >= 8) { stopTestTone(); return }
                    tg.startTone(seq[i % seq.size], 900)
                    i++
                    h.postDelayed(this, 1000)
                }
            }
            h.post(step)
        }.onFailure { log("✗ 播放失败：${it.message}") }
    }

    private fun stopTestTone() {
        runCatching { testTone?.release() }
        testTone = null
    }

    private fun connectShizuku() {
        if (!shell.isShizukuAlive()) {
            log("✗ Shizuku server 没在跑 —— 请先在 Shizuku App 内启动")
            return
        }
        if (shell.hasPermission()) shell.bind() else shell.requestPermission()
    }

    private fun checkIdentity() {
        shell.identity()
            .onSuccess { log("✓ 身份: $it") }
            .onFailure { log("✗ ${it.message}") }
    }

    private fun listTasks() {
        shell.listTasks()
            .onSuccess { ts ->
                log("── 任务表（${ts.size}）──")
                ts.forEach { log("    " + it.oneLine()) }
            }
            .onFailure { log("✗ ${it.message}") }
    }

    private fun listExternalTasks() {
        val extIds = watcher.externalCandidates().map { it.id }.toSet()
        shell.listTasks()
            .onSuccess { ts ->
                val on = ts.filter { it.displayId in extIds }
                log("── TNT 屏（$extIds）上的任务：${on.size} ──")
                on.forEach { log("    " + it.oneLine()) }
            }
            .onFailure { log("✗ ${it.message}") }
    }

    private fun quickLaunch() {
        val ext = watcher.defaultTarget() ?: run { log("✗ 无TNT 屏"); return }
        val comp = CALC
        log("→ am start --display ${ext.id} --windowingMode 5 -n $comp")
        shell.launchToDisplay(comp, ext.id)
            .onSuccess { log(if (it != null) "✓ 已启动，taskId=$it" else "⚠️ 启动了但没解析到 taskId") }
            .onFailure { log("✗ ${it.message}") }
    }

    private fun quickResize() {
        val ext = watcher.defaultTarget() ?: run { log("✗ 无TNT 屏"); return }
        val task = shell.listTasks().getOrNull()
            // ⚠️ 必须**排除宿主自己** —— 我们的 App 也在 TNT 屏上，而且 taskId 通常是最大的，
            //    不排除的话「改几何」会把宿主自己的窗口改掉（2026-09-12 真机实测踩到）。
            ?.filter { it.displayId == ext.id && !it.component.startsWith(packageName) }
            ?.maxByOrNull { it.taskId }
            ?: run { log("✗ TNT 屏上没有任务，先点「启动计算器→TNT 屏」"); return }
        val geo = Geo(80, 80, 900, 760)
        log("→ am task resize ${task.taskId} ${geo.toShellArgs()}")
        shell.resizeTask(task.taskId, geo)
            .onSuccess { ok ->
                if (ok) {
                    log("✓ 几何已生效 → ${geo.oneLine()}")
                } else {
                    // ★ 不猜原因：回读真实状态再报
                    val now = shell.taskState(task.taskId).getOrNull()
                    log("✗ resize 未生效（期望 ${geo.oneLine()}）")
                    if (now == null) {
                        // ⚠️ 必须写 ${task.taskId} —— `$task.taskId` 只会替换 task，
                        //    属性名会原样打出来
                        log("   任务 ${task.taskId} 已不在任务表里")
                    } else {
                        log("   实际: mode=${now.mode}  bounds=${now.bounds?.oneLine() ?: "解析不到（格式可能变了）"}")
                        if (!now.isFreeform && !now.mode.contains("multi", true)) {
                            log("   ⇒ mode 不是 freeform/multi-window，`am task resize` 会静默失败")
                        }
                    }
                }
            }
            .onFailure { log("✗ ${it.message}") }
    }

    private fun quickTap() {
        val ext = watcher.defaultTarget() ?: run { log("✗ 无TNT 屏"); return }
        log("→ 定向点击 display ${ext.id} @ (800,400)  [${ext.id} >= 100000 ⇒ 走 --ext-display]")
        shell.injectTap(ext.id, 800f, 400f)
            .onSuccess { log(if (it) "✓ 已注入" else "✗ 命令报错") }
            .onFailure { log("✗ ${it.message}") }
    }

    private fun runAllPrimitives() {
        log("════ 四原语自测 ════")
        checkIdentity(); listTasks(); quickLaunch(); quickResize(); quickTap()
        log("════ 自测结束 ════")
    }

    // ------------------------------------------------------------------ Mod 管理器

    /** 扫描全系统申报了 `com.shware.mode.action.MOD` 的服务（见 [ModRegistry]）。 */
    private fun scanMods() {
        modSpecs = modRegistry.discover()
        log("── 发现 ${modSpecs.size} 个 mod ──")
        if (modSpecs.isEmpty()) {
            log("    （一个都没有 —— mod APK 装了吗？它的 <service> 里有 MOD_ID 吗？）")
        }
        modSpecs.forEach {
            log("    " + it.oneLine() + if (it.apiCompatible) "" else "   ⛔ 契约版本不兼容")
        }
        renderMods()
        refreshModStates()
    }

    /** 一次 `dumpsys activity services` 查全部 mod 的存活（只读，走 Shizuku）。 */
    private fun refreshModStates() {
        if (modSpecs.isEmpty()) { log("⚠️ 先点「扫描 mod」"); return }
        modStates = modRuntime.probeAll(modSpecs).getOrElse {
            log("✗ 查存活失败：${it.message}")
            modSpecs.associate { m -> m.id to ModState.UNKNOWN }
        }
        modSpecs.forEach { log("    状态 ${it.name}: ${modStates[it.id]?.label ?: "?"}") }
        renderMods()
        refreshModStatus()
    }

    private fun renderMods() {
        if (!::modListRow.isInitialized) return
        modListRow.removeAllViews()

        if (modSpecs.isEmpty()) {
            modListRow += TextView(this).apply {
                text = "（还没扫描到 mod —— 点上面「扫描 mod」）"
                textSize = 11f
                setTextColor(Color.GRAY)
            }
            return
        }

        for (m in modSpecs) {
            val st = modStates[m.id]
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }

            val label = TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(
                    when (st) {
                        ModState.RUNNING -> Color.parseColor("#1B5E20")   // 绿 = 真在跑
                        ModState.CREATED -> Color.parseColor("#E65100")   // 橙 = 起了但没上前台，要查
                        else -> Color.parseColor("#424242")
                    }
                )
                text = buildString {
                    appendLine("${m.name}   [${m.id}]")
                    appendLine("  → ${m.target.label}   ${m.touch.label}   ${m.component.flattenToShortString()}")
                    append("  状态 ${st?.label ?: "（未查）"}")
                    if (!m.apiCompatible) append("   ⛔ api=${m.api} > ${ModContract.API_VERSION}")
                    if (m.desc.isNotEmpty()) append("\n  ${m.desc}")
                }
            }
            label.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            line += label

            // ★ 有设置界面才给按钮（见 [ModContract.META_SETTINGS]）
            m.settings?.let { cn ->
                line += Button(this).apply {
                    text = "设置"
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                    setOnClickListener { openModSettings(m, cn) }
                }
            }

            val sw = SwitchCompat(this).apply {
                isChecked = modStore.isEnabled(m)
                isEnabled = m.apiCompatible
                setOnCheckedChangeListener { _, checked -> onToggleMod(m, checked) }
            }
            line += sw

            modListRow += line
        }
    }

    private fun refreshModStatus() {
        if (!::modStatus.isInitialized) return
        modStatus.text = if (modSpecs.isEmpty()) {
            "没扫到 mod。已加载 mod 组件需装成独立 APK（契约见 ModContract）"
        } else {
            val run = modSpecs.count { modStates[it.id] == ModState.RUNNING }
            val on = modSpecs.count { modStore.isEnabled(it) }
            "已扫 ${modSpecs.size} 个 mod ｜ 开关打开 $on 个 ｜ 实际在跑 $run 个"
        }
    }

    /**
     * 打开某个 mod 的**设置界面**（它自己在 manifest 里申报的，见 [ModContract.META_SETTINGS]）。
     *
     * ⚠️ 这是**跨应用显式启动**：A11+ 上要求目标包对本应用**可见** ——
     * 已由 manifest 里的 `<queries>`（`MOD` action）满足，不用额外声明。
     */
    private fun openModSettings(m: ModSpec, cn: android.content.ComponentName) {
        log("→ 打开「${m.name}」的设置：${cn.flattenToShortString()}")
        runCatching {
            startActivity(Intent().setComponent(cn))
        }.onFailure {
            // 不猜原因：把异常原样报出来（多数是 Activity 不存在 / 未导出 / 包不可见）
            log("✗ 打不开：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun onToggleMod(m: ModSpec, on: Boolean) {        modStore.setEnabled(m.id, on)
        log("${if (on) "▶ 打开" else "■ 关闭"} ${m.name}")
        if (on) {
            val d = targetDisplayId(m)
            if (d == null) { log("✗ 找不到目标屏 —— TNT 屏接上了吗"); return }
            modRuntime.start(m, d)
                .onSuccess { log("✓ 已拉起 → display $d") }
                .onFailure { log("✗ 拉起失败：${it.message}") }
        } else {
            modRuntime.stop(m)
                .onSuccess { log("✓ 已停 ${m.name}") }
                .onFailure { log("✗ 停止失败：${it.message}") }
        }
        // 给它 1.5s 起前台，再回读真实状态（不猜）
        handler.postDelayed({ refreshModStates() }, 1500)
    }

    /** [ModSpec.Target] → 真实 displayId。TNT 屏走 [DisplayWatcher.defaultTarget]，手机屏恒为 0。 */
    private fun targetDisplayId(m: ModSpec): Int? = when (m.target) {
        ModSpec.Target.TNT -> watcher.defaultTarget()?.id
        ModSpec.Target.PHONE -> Display.DEFAULT_DISPLAY
    }

    private fun startKeepAlive() {
        LauncherService.start(this)
        toast("保活服务已启动（通知栏会出现一条静默通知）")
        log("✓ 保活服务已启动 —— 每 30s 巡查一次已启用的 mod")
    }

    private fun stopKeepAlive() {
        LauncherService.stop(this)
        log("■ 保活服务已停止（已拉起的 mod 不会被停掉）")
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        toast("请开启「MODE 按键观察」")
    }

    // ------------------------------------------------------------------ 按键流

    private fun dumpInputDevices() {
        shell.listInputDevices()
            .onSuccess { devs ->
                inputDevices = devs
                log("── 输入设备（${devs.size}）──")
                devs.forEach { log("    ${it.oneLine()}") }
                val pick = InputDevice.pickKeyboard(devs) ?: pickedDevice
                if (pick != null) {
                    pickedDevice = pick
                    log("★ 自动选中键盘: ${pick.path}  ${pick.name}")
                } else {
                    log("⚠️ 没找到像键盘的设备")
                }
                refreshKeyStatus()
            }
            .onFailure { log("✗ 列设备失败: ${it.message}（Shizuku 连上了吗？）") }
    }

    private fun pickAndStartKeys() {
        // 没列过就先列一次 —— 不要硬编码 event 编号（跨主机不同！）
        if (inputDevices.isEmpty()) {
            shell.listInputDevices().onSuccess { inputDevices = it }.onFailure {
                log("✗ 列设备失败: ${it.message}"); return
            }
        }
        val pick = pickedDevice ?: InputDevice.pickKeyboard(inputDevices)
        if (pick == null) {
            log("✗ 没有可抓的键盘设备 —— 先点「列输入设备」看看")
            return
        }
        pickedDevice = pick
        keyLines.clear()
        renderKeys()
        log("→ getevent -lt ${pick.path}   （${pick.name}）")
        if (keyStream.start(pick)) {
            log("✓ 已开始抓键 —— 现在按 TNT GO 键盘，锤子键 / 组合键都会实时显示在按键区")
        }
        refreshKeyStatus()
    }

    private fun stopKeys() {
        keyStream.stop()
        refreshKeyStatus()
    }

    private fun refreshKeyStatus() {
        val d = keyStream.device
        val held = keyStream.heldModifiers().joinToString("+").ifEmpty { "—" }
        keyStatus.text = buildString {
            appendLine("抓着      ${if (keyStream.isRunning) "是 ★" else "否"}")
            appendLine("设备      ${d?.path ?: "（未选）"}")
            appendLine("名称      ${d?.name ?: "—"}")
            append("按住修饰  $held")
        }
    }

    private fun appendKey(s: String) {
        keyLines.addLast(s)
        while (keyLines.size > 120) keyLines.removeFirst()
        renderKeys()
        refreshKeyStatus()
    }

    private fun renderKeys() {
        keyView.text = if (keyLines.isEmpty()) "（还没抓到按键）" else keyLines.joinToString("\n")
    }

    private fun clearLog() { lines.clear(); renderLog() }

    // ------------------------------------------------------------------ 状态 & 日志

    private fun refreshStatus() {
        val ext = watcher.externalCandidates()
        val sb = StringBuilder()
        sb.appendLine("包名      $packageName")
        sb.appendLine("显示器    ${watcher.snapshot().size} 个（TNT 屏候选 ${ext.size}）")
        ext.forEach { sb.appendLine("  TNT 屏    id=${it.id} ${it.width}x${it.height} ${it.name}") }
        watcher.defaultTarget()?.let { sb.appendLine("  ★目标   id=${it.id}（TNT 屏：displayId 最大者）") }
        sb.appendLine("显示器事件 +${watcher.addedCount} / -${watcher.removedCount} / ~${watcher.changedCount}")
        sb.appendLine("overlay   ${if (overlayProbe.isShowing) "显示中 @ display $overlayOnDisplay" else "未显示"}  可绘制=${overlayProbe.canDrawOverlays()}")
        sb.appendLine("Shizuku   ${shell.state}  在线=${shell.isShizukuAlive()}  已授权=${shell.hasPermission()}")
        statusView.text = sb.toString().trimEnd()
    }

    // ------------------------------------------------------- ★ 跨屏搬运（2026-09-12 加）

    /** 列出所有 stack —— 含 `displayId` 与 `taskIds`。**搬运前必须先看这个**（搬的是 stackId）。 */
    private fun showStacks() {
        shell.listStacks()
            .onSuccess { list ->
                log("✓ ${list.size} 个 stack：")
                list.forEach { log("   ${it.oneLine()}") }
            }
            .onFailure { log("✗ ${it.message}") }
    }

    /**
     * ★★★ **跨屏搬运自测：搬过去、再搬回来。**
     *
     * 用**我们自己启动的计算器**当靶子（不碰用户的任何窗口），跑完 `force-stop` 清掉。
     *
     * ⚠️ 判据是**回读 `displayId`**，不是"调用没报错" ——
     * 同 [ShellGateway.resizeTask] 那条纪律（`settings get` 读回 ≠ 生效）。
     */
    private fun runMoveSelfTest() {
        val ext = watcher.defaultTarget() ?: run { log("✗ 无TNT 屏"); return }
        log("=== ★ 跨屏搬运自测：TNT(${ext.id}) ↔ 手机屏(0) ===")

        // ① 把靶子启动到 TNT 屏
        val launched = shell.launchToDisplay(CALC, ext.id)
        if (launched.isFailure) {
            log("✗ 启动靶子失败：${launched.exceptionOrNull()?.message}")
            return
        }
        val taskId = launched.getOrNull()
        log("① 靶子已启动到 TNT 屏  taskId=$taskId")
        Thread.sleep(1200)

        // ② 找到它所在的 stack（搬运吃的是 stackId）
        val stacks = shell.listStacks().getOrElse {
            log("✗ 列 stack 失败：${it.message}")
            return
        }
        val target = stacks.firstOrNull { taskId != null && it.taskIds.contains(taskId) }
            ?: stacks.firstOrNull { it.displayId == ext.id && it.topActivity.contains("calculator") }
        if (target == null) {
            log("✗ 找不到靶子所在的 stack")
            return
        }
        log("② 靶子所在：${target.oneLine()}")

        // ③ 搬到手机屏
        val down = shell.moveStackToDisplay(target.stackId, 0)
        Thread.sleep(800)
        val afterDown = shell.listStacks().getOrNull()?.firstOrNull { it.stackId == target.stackId }
        log(
            if (afterDown?.displayId == 0) "③ ✓ 已搬到手机屏（displayId=${afterDown.displayId}）"
            else "③ ✗ 没搬过去：${down.exceptionOrNull()?.message ?: "displayId=${afterDown?.displayId}"}"
        )

        // ④ 搬回 TNT 屏 —— ★ 不验这一步就等于把窗口卡死，必须回得来
        val up = shell.moveStackToDisplay(target.stackId, ext.id)
        Thread.sleep(800)
        val afterUp = shell.listStacks().getOrNull()?.firstOrNull { it.stackId == target.stackId }
        log(
            if (afterUp?.displayId == ext.id) "④ ✓ 已搬回 TNT 屏（displayId=${afterUp.displayId}）"
            else "④ ✗ 没搬回来：${up.exceptionOrNull()?.message ?: "displayId=${afterUp?.displayId}"}"
        )

        // ⑤ 清场
        shell.exec("am force-stop com.smartisanos.calculator")
        log("⑤ 已清掉靶子")
        log("=== 自测结束 ===")
    }

    // ------------------------------------------------------- ★ 窗口搬运（真业务，2026-09-12 加）

    /**
     * 列出**可搬运的**任务，每个一个按钮。
     *
     * ⚠️ 搬的是**整个 stack** ⇒ 按钮上标出该 stack 里有几个任务（搬一个可能带走别的）。
     * ⚠️ **滤掉宿主自己** —— 把自己搬走等于自杀（界面会跑到别的屏上去）。
     */
    private fun refreshMoveList() {
        val tasks = shell.listTasks().getOrElse {
            log("✗ 列任务失败：${it.message}")
            return
        }
        val stacks = shell.listStacks().getOrNull().orEmpty()
        val tntId = watcher.defaultTarget()?.id ?: 100000
        val shown = tasks.filterNot { it.component.startsWith(packageName) }

        moveTasks.removeAllViews()
        log("→ 可搬运 ${shown.size} 个任务（共 ${tasks.size} 个，已滤掉宿主自己）")

        shown.forEach { t ->
            val st = stacks.firstOrNull { it.taskIds.contains(t.taskId) }
            val onTnt = t.displayId != 0
            val stackNote = st?.let { "  stack${it.stackId}(${it.taskIds.size}个)" } ?: "  ⚠️无stack"
            moveTasks += button(
                "${if (onTnt) "TNT" else "手机"} ${t.displayId}  ${shortComp(t.component)}$stackNote  " +
                        if (onTnt) "→ 手机屏" else "→ TNT屏"
            ) { moveStack(st?.stackId, if (onTnt) 0 else tntId, shortComp(t.component)) }
        }
    }

    /** ★ 一键：把 TNT 屏上最上层的（非宿主）窗口搬回手机屏 —— 最常见的那一个动作 */
    private fun moveTopTntToPhone() {
        val tasks = shell.listTasks().getOrElse {
            log("✗ 列任务失败：${it.message}")
            return
        }
        val tntId = watcher.defaultTarget()?.id ?: 100000
        val top = tasks.firstOrNull { it.displayId == tntId && !it.component.startsWith(packageName) }
        if (top == null) {
            log("✗ TNT 屏上没有可搬的窗口")
            return
        }
        val st = shell.listStacks().getOrNull().orEmpty()
            .firstOrNull { it.taskIds.contains(top.taskId) }
        if (st == null) {
            log("✗ 找不到 ${shortComp(top.component)} 所在的 stack")
            return
        }
        moveStack(st.stackId, 0, shortComp(top.component))
    }

    /**
     * 搬 + ★ **回读确认**。
     *
     * ⚠️ `moveStackToDisplay` 返回 `OK` **≠ 真搬过去了** ——
     * 同 [ShellGateway.resizeTask] 那条纪律（`settings get` 读回 ≠ 生效）。
     */
    private fun moveStack(stackId: Int?, target: Int, who: String) {
        if (stackId == null) {
            log("✗ $who 不在任何 stack 里，无法搬（先点「刷新列表」）")
            return
        }
        val r = shell.moveStackToDisplay(stackId, target)
        if (r.isFailure) {
            log("✗ 搬 $who 失败：${r.exceptionOrNull()?.message}")
            return
        }
        Thread.sleep(500)   // 搬运是异步的，立刻回读会读到旧值
        val now = shell.listStacks().getOrNull()?.firstOrNull { it.stackId == stackId }
        log(
            if (now?.displayId == target) "✓ 已把 $who 搬到 display $target"
            else "✗ 调用成功但没搬过去：displayId=${now?.displayId ?: "stack 没了"}"
        )
        refreshMoveList()
    }

    // ------------------------------------------------------- ★ 音频输出（2026-09-12 加）

    /**
     * ★★★ 切换媒体音频输出 —— **切完必须回读确认**。
     *
     * ⚠️ `setWiredDeviceConnectionState` 对**不存在的设备地址是空操作**（不报错）⇒
     * 只看返回值会**假阳性**。同 [moveStack] 那条纪律。
     */
    private fun setAudio(target: String) {
        val before = shell.currentAudioOutput().getOrElse { "?" }
        val want = if (target == "tnt") "usb_headset" else "speaker"
        val r = shell.setAudioOutput(target)
        if (r.isFailure) {
            log("✗ 切音频失败：${r.exceptionOrNull()?.message}")
            return
        }
        Thread.sleep(600)   // 重路由是异步的
        val after = shell.currentAudioOutput().getOrElse { "?" }
        log(
            if (after.contains(want))
                "✓ 音频已切到 ${if (target == "tnt") "TNT GO" else "手机扬声器"}（$before → $after）"
            else
                "⚠️ 调用成功但设备没变：$before → $after（期望含 $want）"
        )
    }

    /** `com.foo/org.bar.Baz` → `Baz` */
    private fun shortComp(c: String): String =
        c.substringAfterLast('/').substringAfterLast('.')

    private fun log(s: String) {
        lines.addLast("${ts.format(Date())}  $s")
        while (lines.size > 400) lines.removeFirst()
        renderLog()
    }

    private fun renderLog() {
        logView.text = lines.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "Mode/MainActivity"
        private const val CALC = "com.smartisanos.calculator/.Calculator"
    }
}

/** 只为了在日志里打版本号，免得再引 BuildConfig 依赖 */
private object BuildConfigCompat {
    const val VERSION = "0.1.0-skeleton"
}
