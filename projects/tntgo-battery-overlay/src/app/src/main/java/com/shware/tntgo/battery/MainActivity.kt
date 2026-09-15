package com.shware.tntgo.battery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etCmd: EditText

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnUsb).setOnClickListener {
            TntgoSerial(this).requestPermissionIfNeeded()
            tvStatus.postDelayed({ refresh() }, 500)
        }

        // ---- 路线 B：厂商寄存器只读扫描 ----
        findViewById<Button>(R.id.btnProbe).setOnClickListener {
            runVendorProbe()
        }

        // ---- 路线 C 第二轮：串口 AT 探测 ----
        findViewById<Button>(R.id.btnSerialProbe).setOnClickListener {
            runSerialProbe()
        }

        // ---- ★ 自由输入：手输任意 AT 命令（禁区硬拒绝） ----
        etCmd = findViewById(R.id.etCmd)
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendTypedCommand() }
        etCmd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedCommand(); true
            } else false
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ensureNotificationPermission()
            TntgoSerial(this).requestPermissionIfNeeded()
            OverlayService.start(this)
            refresh()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            OverlayService.stop(this)
            refresh()
        }

        // ★ 任务 AQ：允许 `am start --es cmd "at+xxx"` 直接发一条（走同一道 SerialGuard）
        handleIntentCommand(intent)
    }

    /**
     * ★★ **必须同时处理 `onNewIntent`**（2026-09-15 实测踩到）。
     *
     * 本 ROM 上 MainActivity 即便 manifest 没写 `launchMode`，行为也**等同 `singleTop`**：
     *
     * ```
     * adb shell am start -n …/.MainActivity --es cmd at+batcg
     * Warning: Activity not started, intent has been delivered to currently running top-most instance.
     * ```
     *
     * ⇒ 第二次起走的是 **`onNewIntent`，`onCreate` 根本不再跑**
     * ⇒ 只写在 `onCreate` 里的话，**只有第一次能发，之后全部静默无效**。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentCommand(intent)
    }

    /** 从 Intent 里取 `cmd` extra 并发出去（两个入口共用） */
    private fun handleIntentCommand(intent: Intent?) {
        intent?.getStringExtra(EXTRA_CMD)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runIntentCommand(it)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * ★★ **从 Intent 发一条 AT 命令**（任务 AQ · AQ2 用）。
     *
     * ## 为什么加这个
     *
     * AQ2 要做**三组条件对照**实验（正常负载 / 压低负载 / 拔掉），
     * 每组都要发同样的命令、取回同样的响应。
     * 手敲输入框做不到**可重复**，所以：
     *
     * ```bash
     * adb shell am start -n com.shware.tntgo.battery/.MainActivity \
     *     --es cmd "at+bq25970"
     * ```
     *
     * ## ⚠️ 安全边界**没有放宽**
     *
     * 这个入口**不绕过** [SerialGuard] —— 它调的是**同一个** [SerialConsole.send]，
     * 禁区命令照样被硬拒绝（而且拒绝原因会如实显示出来）。
     * ★ 也就是说：**这里能发的命令，手输也能发；手输发不了的，这里也发不了。**
     *
     * 结果同时进 **logcat**（`>>> 命令` + 响应）与
     * [`SerialConsole.reportFile`] ⇒ 两种取法都行。
     */
    private fun runIntentCommand(cmd: String) {
        val blocked = SerialGuard.blockedReason(cmd)
        if (blocked != null) {
            tvLog.text = getString(R.string.cmd_blocked, blocked)
            return
        }
        tvLog.text = ">>> $cmd\n（来自 Intent，发送中…）"

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SerialConsole.send(this@MainActivity, cmd) }
            tvLog.text = buildString {
                append(">>> ").append(cmd).append('\n')
                when {
                    r.blockedBy != null -> append(getString(R.string.cmd_blocked, r.blockedBy))
                    r.error != null -> append(getString(R.string.cmd_refused, r.error))
                    r.response.isBlank() -> append("(无响应)")
                    else -> append(r.response)
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 执行厂商寄存器扫描（阻塞，放到 IO 线程）。
     *
     * ★ 只发 IN 控制传输，不 claim 接口，不干扰键鼠。
     */
    private fun runVendorProbe() {
        val probe = VendorProbe(this)
        if (probe.findDevice() == null) {
            tvLog.text = getString(R.string.probe_no_device)
            return
        }
        // 若还没授权，先请求（结果通过系统弹窗返回，用户需再点一次按钮）
        if (!probe.hasPermission()) {
            TntgoSerial(this).requestPermissionIfNeeded()
            tvLog.text = getString(R.string.probe_no_device) +
                "\n\n（已弹出授权对话框，允许后请再点一次「厂商寄存器只读扫描」）"
            return
        }

        tvLog.text = getString(R.string.probe_running)

        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                probe.execute { done, total, hit ->
                    if (hit != null) {
                        runOnUiThread {
                            tvLog.append("\n★ hit 0x%02X/0x%02X len=%d ret=%d [%s]"
                                .format(hit.request, hit.index, hit.length, hit.ret, hit.hex))
                        }
                    } else if (done % 16 == 0) {
                        runOnUiThread { tvStatus.text = "扫描进度 $done / $total" }
                    }
                }
            }

            if (report == null) {
                tvLog.text = getString(R.string.probe_no_device)
            } else {
                tvLog.text = report
            }
            refresh()
        }
    }

    private fun refresh() {
        val serial = TntgoSerial(this)
        val device = serial.findDevice()
        val granted = device != null && serial.hasPermission()

        tvStatus.text = buildString {
            append("TNT GO：")
            append(if (device == null) "未连接" else "已连接")
            append("\nUSB 权限：")
            append(if (granted) "已授权" else "未授权")
        }
    }

    /**
     * 路线 C 第二轮：串口 AT 探测。
     *
     * 先被动监听 ~12s，再逐条试候选电量命令。总耗时约 60s。
     */
    private fun runSerialProbe() {
        val probe = SerialProbe(this)
        if (probe.findDevice() == null) {
            tvLog.text = getString(R.string.probe_no_device)
            return
        }
        if (!probe.hasPermission()) {
            TntgoSerial(this).requestPermissionIfNeeded()
            tvLog.text = "未授权。已弹出授权对话框，允许后请再点一次「② 串口 AT 探测」。"
            return
        }

        tvLog.text = "串口探测开始（约 60 秒）…\n"

        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                probe.execute { msg ->
                    runOnUiThread {
                        tvStatus.text = msg
                        tvLog.append("\n$msg")
                    }
                }
            }
            tvLog.text = report ?: "串口探测失败（端口打不开或驱动不匹配）"
            refresh()
        }
    }

    /**
     * ★ 自由输入：发送用户手输的 AT 命令。
     *
     * **禁区命令在 [SerialConsole] 内硬拒绝**（不打开串口），这里只负责展示结果。
     */
    private fun sendTypedCommand() {
        val raw = etCmd.text.toString().trim()
        if (raw.isEmpty()) return

        // 先在 UI 层给一次即时反馈（真正拦截在 SerialConsole）
        val blocked = SerialGuard.blockedReason(raw)
        if (blocked != null) {
            tvLog.text = getString(R.string.cmd_blocked, blocked)
            return
        }

        tvLog.text = ">>> $raw\n（发送中…）"

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SerialConsole.send(this@MainActivity, raw) }
            tvLog.text = buildString {
                append(">>> ").append(raw).append('\n')
                when {
                    r.blockedBy != null -> append(getString(R.string.cmd_blocked, r.blockedBy))
                    r.error != null -> append(getString(R.string.cmd_refused, r.error))
                    r.response.isBlank() -> append("(无响应)")
                    else -> append(r.response)
                }
            }
            if (r.accepted) etCmd.setText("")
            refresh()
        }
    }

    companion object {
        /**
         * `am start … --es cmd "at+bq25970"` 用的 extra 名（任务 AQ · AQ2）。
         *
         * ⚠️ 这个入口**不绕过** [SerialGuard] —— 禁区命令照样被拒。
         */
        const val EXTRA_CMD = "cmd"
    }
}
