package com.shware.perfprobe

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shware.mode.api.IPowerToysApi
import org.json.JSONArray

/**
 * ★★★★★ **Smartisan Powertoys 对外 API 的验收测试**（任务 AP / AP7）。
 *
 * ## 为什么这个测试放在 perf-probe 里
 *
 * `perf-probe` 的包名是 **`com.shware.perfprobe`** —— 和 Powertoys
 * （`com.shware.mode`）**不是同一个应用**。
 *
 * ⇒ 从这里调用，走的才是**真正的跨进程 binder**：
 * 序列化、`asInterface`、权限、包可见性，一个都跑不掉。
 *
 * ★ 如果放在 Powertoys 自己的工程台里测，`bindService` 会拿到**本地 stub**，
 * **根本不经过 Parcel** —— 那种"测试"什么也没验证到。
 *
 * ## 它同时是**给后续工程的样例代码**
 *
 * 三步：
 * 1. 把 `IPowerToysApi.aidl` **照抄**到自己的工程（不需要依赖对方的 AAR）
 * 2. manifest 里声明包可见性（A11+ 必需，见下）
 * 3. `bindService` + `Stub.asInterface` + 调方法
 *
 * ```xml
 * <queries><package android:name="com.shware.mode" /></queries>
 * ```
 */
class ApiClientActivity : Activity() {

    private lateinit var out: TextView
    private val sb = StringBuilder()

    private var api: IPowerToysApi? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // ★ 这一行是整条链路的关口 —— 能拿到非 null 就说明 binder 通了
            api = IPowerToysApi.Stub.asInterface(binder)
            say("✓ onServiceConnected  binder=${binder != null}  api=${api != null}")
            runAll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            say("✗ onServiceDisconnected —— 对方进程没了")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(Button(this).apply {
            text = "① 绑定 Powertoys API 并全量自检"
            setOnClickListener { bind() }
        })
        root.addView(Button(this).apply {
            text = "② 只调 setEnabled(tntgo.battery, true) 再回读"
            setOnClickListener { toggleHello() }
        })
        root.addView(Button(this).apply {
            text = "清屏"
            setOnClickListener { sb.setLength(0); out.text = "" }
        })

        out = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#E8EAED"))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.START
        }
        root.addView(ScrollView(this).apply { addView(out) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))

        setContentView(root)
        say("准备就绪。目标：com.shware.mode / action com.shware.mode.action.API")
    }

    override fun onDestroy() {
        runCatching { unbindService(conn) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 动作

    private fun bind() {
        val i = Intent(ACTION_API).setPackage(PKG)
        say("→ bindService($i)")
        val ok = runCatching { bindService(i, conn, Context.BIND_AUTO_CREATE) }
            .getOrElse { say("✗ bindService 抛异常: ${it.message}"); false }
        say("  bindService 返回 $ok" + if (!ok) "（false ⇒ 包可见性没配对，或服务不存在）" else "")
    }

    private fun toggleHello() {
        val a = api ?: run { say("✗ 还没绑定"); return }
        runCatching {
            say("→ setEnabled(\"tntgo.battery\", true)")
            val ok = a.setEnabled("tntgo.battery", true)
            say("  setEnabled 返回 $ok   错误=${a.lastError}")
            say("  isEnabled = ${a.isEnabled("tntgo.battery")}")
            say("  getState  = ${a.getState("tntgo.battery")}")
        }.onFailure { say("✗ 调用抛异常: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** ★ 全量自检：把对外 API 的每个方法都真正调一遍 */
    private fun runAll() {
        val a = api ?: return
        try {
            say("getApiVersion() = ${a.apiVersion}")
            say("getStatus()      = ${a.status}")

            val fast = a.listFeaturesFast()
            say("listFeaturesFast() → ${fast.length} 字符")
            showFeatures(fast, "  [Fast]")

            val full = a.listFeatures()
            say("listFeatures()     → ${full.length} 字符")
            showFeatures(full, "  [Full]")

            val one = a.describeFeature("tntgo.battery")
            say("describeFeature(tntgo.battery) = ${one?.take(200) ?: "null"}")

            val layers = a.listLayers()
            val la = JSONArray(layers)
            say("listLayers() → ${la.length()} 个图层")
            for (i in 0 until la.length()) {
                val o = la.getJSONObject(i)
                say("  · ${o.optString("featureName")}  disp=${o.optInt("displayId")}  " +
                    "${o.optInt("w")}×${o.optInt("h")} @ (${o.optInt("x")},${o.optInt("y")})  " +
                    "touch=${o.optString("touch")}  vis=${o.optBoolean("visible")}  " +
                    "own=${o.optBoolean("own")}")
            }

            val conf = a.listLayerConflicts()
            say("listLayerConflicts() → $conf")

            say("getLastError() = ${a.lastError ?: "(无)"}")
            say("═══ 全部方法调用完毕，无异常穿过 binder ═══")
        } catch (t: Throwable) {
            // ★ 这一步本身就是结论：真出问题的话，异常会以 RemoteException 的形式到这里
            say("✗✗ 有异常穿过 binder: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "API 自检失败", t)
        }
    }

    private fun showFeatures(json: String, tag: String) {
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                say("$tag ${o.optString("id").padEnd(18)} ${o.optString("state").padEnd(8)} " +
                    "${o.optString("category").padEnd(12)} icon=${o.optString("icon").padEnd(16)} " +
                    "${o.optString("name")}")
            }
        }.onFailure { say("$tag 解析失败: ${it.message}") }
    }

    private fun say(s: String) {
        Log.i(TAG, s)
        sb.append(s).append('\n')
        out.text = sb.toString()
    }

    private companion object {
        const val TAG = "ApiClient"
        const val PKG = "com.shware.mode"
        const val ACTION_API = "com.shware.mode.action.API"
    }
}
