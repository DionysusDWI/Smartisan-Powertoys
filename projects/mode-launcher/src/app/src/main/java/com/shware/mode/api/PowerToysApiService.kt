package com.shware.mode.api

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.shware.mode.core.feature.Feature
import com.shware.mode.core.feature.FeatureRegistry
import com.shware.mode.core.feature.LayerManager
import com.shware.mode.platform.DisplayWatcher
import com.shware.mode.shell.SharedShell
import com.shware.mode.shell.ShellGateway
import java.util.concurrent.Executors

/**
 * ★★★★★ **对外 API 服务**（任务 AP / AP7）—— 给后续工程调用的入口。
 *
 * ## 怎么调
 *
 * ```kotlin
 * val conn = object : ServiceConnection {
 *     override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
 *         val api = IPowerToysApi.Stub.asInterface(b)
 *         Log.i(TAG, "版本 ${api.apiVersion}  状态 ${api.status}")
 *         val arr = JSONArray(api.listFeaturesFast())
 *     }
 *     override fun onServiceDisconnected(n: ComponentName?) {}
 * }
 * bindService(
 *     Intent(ACTION_API).setPackage("com.shware.mode"),
 *     conn, Context.BIND_AUTO_CREATE
 * )
 * ```
 *
 * ⚠️ A11+ 调用方要在自己的 manifest 里声明包可见性，否则 `bindService` **静默失败**：
 * ```xml
 * <queries><package android:name="com.shware.mode" /></queries>
 * ```
 *
 * ## ★★ 为什么这些方法会阻塞几百毫秒，以及为什么这是可接受的
 *
 * `listFeatures()` / `listLayers()` 都要走一次 `dumpsys`（经 Shizuku）。
 * 但 AIDL 调用**天然就在 binder 线程**上执行，不占调用方主线程 ——
 * 调用方只要别在自己主线程上直接调就行（这是 AIDL 的常识）。
 *
 * ★ 想快就用 `listFeaturesFast()`：只查 PackageManager，不查运行状态。
 *
 * ## ★ 刻意**不**做的事
 *
 * | 不做 | 为什么 |
 * |---|---|
 * | 不暴露"改窗口位置/大小" | 宿主**没有**别人窗口的所有权，那是 Android 的硬边界，不是没实现 |
 * | 不暴露"锁定/解锁" | 同上 |
 * | 不返回 Parcelable | 见 `IPowerToysApi` 顶部对 JSON 取舍的完整说明 |
 *
 * ## ⚠️ 安全
 *
 * `android:exported="true"` —— 这是**故意**的（"给后续工程调用"就是这个意思）。
 * 它**只能读写本应用自己的开关**，碰不到系统设置，
 * 也**不能**代替 Shizuku 权限（Shizuku 的授权仍只对本应用生效）。
 * ⇒ 被恶意应用调用最多是"帮你开关一个悬浮组件"，不构成提权。
 */
class PowerToysApiService : Service() {

    private lateinit var registry: FeatureRegistry
    private lateinit var layers: LayerManager
    private lateinit var watcher: DisplayWatcher

    /** 串行执行 —— 免得两个调用同时 `dumpsys` 把 ShellGateway 压垮 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pt-api").apply { isDaemon = true }
    }

    /** 最近一次失败的说明；成功时清空 */
    @Volatile
    private var lastErrorText: String? = null

    override fun onCreate() {
        super.onCreate()
        val shell = SharedShell.get(this)
        registry = FeatureRegistry(this, shell)
        layers = LayerManager(this, shell)
        watcher = DisplayWatcher(this)

        // 尽量把通道连上 —— 否则所有查询都会返回"未知"
        if (shell.isShizukuAlive() && shell.hasPermission()) shell.bind()
    }

    override fun onDestroy() {
        io.shutdownNow()
        watcher.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ------------------------------------------------------------------ 实现

    private val binder = object : IPowerToysApi.Stub() {

        override fun getApiVersion(): Int = API_VERSION

        override fun getStatus(): String = runCatching {
            val shell = SharedShell.peek()
            val tnt = watcher.defaultTarget()
            val displays = watcher.snapshot()
            buildString {
                append("Smartisan Powertoys API v$API_VERSION")
                append("  ·  Shizuku: ")
                append(shell?.state?.name ?: "未初始化")
                append("  ·  显示器 ${displays.size} 块")
                if (tnt != null) append("，TNT 屏 display ${tnt.id} (${tnt.width}×${tnt.height})")
                else append("，未检测到 TNT 屏")
            }
        }.getOrElse { fail(it); "读取状态失败：${it.message}" }

        // ★ 失败时给 `[]` 而不是 null —— AIDL 这几个返回值是**非空**的，
        //   而且对调用方来说"一个空数组"比"null 要特判"好处理得多。
        //   ⚠️ 但失败**不是**"没有功能"：调用方要结合 getLastError() 判断，
        //      这正是 FireJson 之外还得有 getLastError() 的原因。
        override fun listFeatures(): String =
            call { awaitShell(); FeatureJson.features(registry.load()) } ?: "[]"

        override fun listFeaturesFast(): String =
            call { FeatureJson.features(registry.discoverOnly()) } ?: "[]"

        override fun describeFeature(id: String): String? {
            val f = call { registry.find(id) }
                ?: run { lastErrorText = "找不到功能 $id"; return null }
            return FeatureJson.feature(f).toString()
        }

        override fun isEnabled(id: String): Boolean =
            call { registry.find(id)?.enabled } ?: false

        override fun setEnabled(id: String, on: Boolean): Boolean = call {
            val f = registry.find(id) ?: throw IllegalArgumentException("找不到功能 $id")
            // ★ 与主界面开关走的是同一条路（同一个 ModStore + 同一个 ModRuntime）
            registry.setEnabled(id, on)
            val r = if (on) {
                val tnt = watcher.defaultTarget()?.id ?: DEFAULT_TNT
                registry.start(f, registry.targetDisplay(f, tnt))
            } else {
                registry.stop(f)
            }
            if (r.isFailure) throw (r.exceptionOrNull() ?: IllegalStateException("未知失败"))
            true
        } ?: false

        override fun start(id: String): Boolean = call {
            val f = registry.find(id) ?: throw IllegalArgumentException("找不到功能 $id")
            val tnt = watcher.defaultTarget()?.id ?: DEFAULT_TNT
            val r = registry.start(f, registry.targetDisplay(f, tnt))
            if (r.isFailure) throw (r.exceptionOrNull() ?: IllegalStateException("未知失败"))
            true
        } ?: false

        override fun stop(id: String): Boolean = call {
            val f = registry.find(id) ?: throw IllegalArgumentException("找不到功能 $id")
            val r = registry.stop(f)
            if (r.isFailure) throw (r.exceptionOrNull() ?: IllegalStateException("未知失败"))
            true
        } ?: false

        override fun getState(id: String): String = call {
            awaitShell()
            val f = registry.load().firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("找不到功能 $id")
            FeatureJson.stateKey(f.state)
        } ?: "unknown"

        override fun listLayers(): String = call {
            awaitShell()
            val feats = registry.load()
            val r = layers.probe(feats)
            if (r.isFailure) throw (r.exceptionOrNull() ?: IllegalStateException("未知失败"))
            FeatureJson.layers(r.getOrThrow())
        } ?: "[]"

        override fun listLayerConflicts(): String = call {
            awaitShell()
            val feats = registry.load()
            val r = layers.probe(feats)
            if (r.isFailure) throw (r.exceptionOrNull() ?: IllegalStateException("未知失败"))
            FeatureJson.conflicts(layers.findConflicts(r.getOrThrow()))
        } ?: "[]"

        override fun setLayerVisible(featureId: String, visible: Boolean) {
            // 广播是**异步**的，这里只能报"发出去没有"
            val r = layers.setVisible(featureId.ifBlank { null }, visible)
            if (r.isFailure) fail(r.exceptionOrNull() ?: IllegalStateException("未知失败"))
            else lastErrorText = null
        }

        override fun getLastError(): String? = lastErrorText
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * ★★★ **有界等待 Shizuku 就绪** —— 不这样做，API 会在冷启动后必然失败一次。
     *
     * ## 为什么必须有
     *
     * 2026-09-14 实测（跨应用调用，`perf-probe` → 本服务）：
     *
     * ```
     * getStatus()   = … Shizuku: CONNECTING …
     * listLayers()  → 0 个图层                    ← 看起来像"没有图层"
     * getLastError()= UserService 未连接（CONNECTING）  ← 真因在这里
     * ```
     *
     * 调用方（尤其是自动化的）**多半只看返回值**，于是会把"查不了"
     * 当成"没有" —— 这正是最难查的那类错误结论。
     *
     * ## 取舍
     *
     * 会在 **binder 线程**上最多阻塞 [SHELL_WAIT_MS]。
     * binder 线程池默认 16 条，对本应用的调用量来说完全够用；
     * 而"给调用方一个错的答案"比"多等两秒"贵得多。
     *
     * ⚠️ 已经在 `READY` 时**立即返回**，不产生任何额外延迟。
     */
    private fun awaitShell(): ShellGateway.State {
        val shell = SharedShell.peek() ?: SharedShell.get(this)
        if (shell.state == ShellGateway.State.READY) return shell.state

        val latch = java.util.concurrent.CountDownLatch(1)
        val listener: (ShellGateway.State) -> Unit = {
            if (it == ShellGateway.State.READY) latch.countDown()
        }
        shell.addStateListener(listener)
        try {
            if (shell.isShizukuAlive() && shell.hasPermission()) shell.bind()
            latch.await(SHELL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            shell.removeStateListener(listener)
        }
        return shell.state
    }

    /**
     * 统一的调用外壳：**绝不让异常穿过 binder**。
     *
     * ⚠️ 跨进程抛异常在不同 Android 版本上行为不一致，
     * 而且会把本进程的堆栈塞进调用方的 `RemoteException` —— 对调用方没用的噪音。
     * ⇒ 一律吞掉、记进 [lastErrorText]、返回 null / 默认值。
     *
     * ⚠️ 字段名叫 `lastErrorText` 而**不是** `lastError` 是**必须的**：
     * Kotlin 会把下面的 `override fun getLastError()` 合成为一个**只读属性** `lastError`，
     * 于是本类里任何 `lastError = …` 都会被解析成"给那个只读属性赋值"，
     * 报 `'val' cannot be reassigned` —— 2026-09-14 实测踩到，报错位置还指在别处。
     */
    private fun <T> call(block: () -> T): T? = try {
        lastErrorText = null
        block()
    } catch (t: Throwable) {
        fail(t)
        null
    }

    private fun fail(t: Throwable) {
        lastErrorText = "${t.javaClass.simpleName}: ${t.message}"
        Log.w(TAG, "API 调用失败：$lastErrorText", t)
    }

    companion object {
        private const val TAG = "Mode/PowerToysApi"

        /** ★ 对外契约版本 —— **只增不改**，见 [FeatureJson] 的字段纪律。 */
        const val API_VERSION = 1

        /** 绑定用的 action（调用方 `Intent(ACTION_API).setPackage("com.shware.mode")`）。 */
        const val ACTION_API = "com.shware.mode.action.API"

        /** 找不到 TNT 屏时的兜底 */
        private const val DEFAULT_TNT = 100000

        /**
         * 等 Shizuku 就绪的上限（毫秒）。
         *
         * 实测绑定耗时约 **520ms**（CONNECTING → READY），取 4s 有足够余量，
         * 又不会让"Shizuku 真的没装"的调用方白等太久。
         */
        private const val SHELL_WAIT_MS = 4000L
    }
}
