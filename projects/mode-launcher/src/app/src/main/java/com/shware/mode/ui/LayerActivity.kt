package com.shware.mode.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.shware.mode.R
import com.shware.mode.core.feature.Feature
import com.shware.mode.core.feature.FeatureRegistry
import com.shware.mode.core.feature.LayerConflict
import com.shware.mode.core.feature.LayerInfo
import com.shware.mode.core.feature.LayerManager
import com.shware.mode.platform.DisplayWatcher
import com.shware.mode.shell.SharedShell
import com.shware.mode.shell.ShellGateway
import java.util.concurrent.Executors

/**
 * ★★★★★ **图层管理**（任务 AP / AP6）—— 用户点名要的「**多层同屏管理**」。
 *
 * ## 一、这里显示的是**实测**，不是申报
 *
 * 每一行都来自 [LayerManager] 对 `dumpsys window` 的解析：
 * 真实的大小、位置、在哪块屏、带不带 `FLAG_NOT_TOUCHABLE`、是不是真的可见。
 * ⇒ **mod 申报错了，这里会如实显示错的那个样子。**
 *
 * ## 二、能做 / 不能做（★ 必须对用户讲清楚）
 *
 * | | |
 * |---|---|
 * | ✅ **看**：谁在哪块屏上画了多大一块、叠没叠 | 全是实测 |
 * | ✅ **判**：两个"自吃"图层是否重叠（会互相抢点击） | 实测几何算的 |
 * | 🟡 **控**：隐藏 / 显示 —— 走 [LayerManager.ACTION_LAYER_CONTROL] 广播 | ★ **是请求不是命令** |
 *
 * ★★ **宿主不拥有别人的窗口** —— 这是 Android 的硬边界，不是没实现。
 * 宿主能做的只有"登记 ＋ 协调"：把指令用契约广播出去，由 mod 自己决定听不听。
 * ⇒ **没实现广播的 mod，隐藏按钮不会有任何反应**，界面必须**如实说明**这一点，
 * 而不是假装成功（那会让用户以为功能坏了却查不出原因）。
 */
class LayerActivity : AppCompatActivity() {

    private lateinit var registry: FeatureRegistry
    private lateinit var layers: LayerManager
    private lateinit var watcher: DisplayWatcher
    private lateinit var adapter: LayerAdapter

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var conflictBox: View
    private lateinit var conflictLine: TextView
    private lateinit var statLine: TextView

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pt-layer-load").apply { isDaemon = true }
    }

    private var loading = false

    /** Shizuku 状态订阅 —— 存一份是为了 onDestroy 能退订（不退会泄漏 Activity） */
    private var shellListener: ((ShellGateway.State) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pt_activity_layer)

        val shell = SharedShell.get(this)
        registry = FeatureRegistry(this, shell)
        layers = LayerManager(this, shell)
        watcher = DisplayWatcher(this)

        list = findViewById(R.id.layerList)
        empty = findViewById(R.id.layerEmpty)
        conflictBox = findViewById(R.id.conflictBox)
        conflictLine = findViewById(R.id.conflictLine)
        statLine = findViewById(R.id.layerStat)

        adapter = LayerAdapter { l, visible -> onSetVisible(l, visible) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnLayerRefresh).setOnClickListener { reload() }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.layerToolbar)
            .setNavigationOnClickListener { finish() }

        // ★ 同样要等 Shizuku 绑好 —— 否则冷启动后立刻进来会看到"读取失败"
        //   （见 ShellGateway.addStateListener 里记的那次真实故障）
        shellListener = { st ->
            if (st == ShellGateway.State.READY) runOnUiThread { reload() }
        }
        shell.addStateListener(shellListener!!)
        if (shell.state == ShellGateway.State.READY) reload()

        reload()
    }

    override fun onDestroy() {
        shellListener?.let { SharedShell.peek()?.removeStateListener(it) }
        shellListener = null
        io.shutdownNow()
        watcher.stop()
        super.onDestroy()
    }

    private fun reload() {
        if (loading) return
        loading = true
        statLine.text = "读取中…"

        io.execute {
            val feats = runCatching { registry.load() }.getOrElse { emptyList() }
            val result = layers.probe(feats)
            val displays = runCatching { watcher.snapshot() }.getOrDefault(emptyList())

            runOnUiThread {
                loading = false
                val all = result.getOrElse {
                    // ★ 查不了就明说 —— **不要**显示成"没有任何图层"（那是撒谎）
                    statLine.text = "读取失败：${it.message}\n（需要 Shizuku 已连接并授权）"
                    adapter.submit(emptyList(), emptyList())
                    empty.visibility = View.GONE
                    conflictBox.visibility = View.GONE
                    return@runOnUiThread
                }

                val mine = all.filter { it.featureId != null }
                val others = all.filter { it.featureId == null }
                val conflicts = layers.findConflicts(all)

                adapter.submit(mine, others)
                empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

                conflictBox.visibility = if (conflicts.isEmpty()) View.GONE else View.VISIBLE
                conflictLine.text = conflicts.take(3).joinToString("\n") { it.oneLine() }

                statLine.text = buildString {
                    append("共 ${all.size} 个悬浮图层：本应用 ${mine.size} 个")
                    if (others.isNotEmpty()) append("，其它应用 ${others.size} 个")
                    append("\n显示器 ${displays.size} 块：")
                    append(displays.joinToString("、") { "${it.id}(${it.width}×${it.height})" })
                }
            }
        }
    }

    private fun onSetVisible(l: LayerInfo, visible: Boolean) {
        val id = l.featureId ?: return
        val r = layers.setVisible(id, visible)
        val msg = if (r.isSuccess) {
            "已发出「${if (visible) "显示" else "隐藏"}」指令。\n" +
                "⚠️ 这只是广播 —— 该组件若未实现图层控制，不会有任何变化。"
        } else {
            "指令发送失败：${r.exceptionOrNull()?.message}"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        // 广播是异步的，等一拍再回读真实状态
        list.postDelayed({ reload() }, 600)
    }
}

// ---------------------------------------------------------------------------- 适配器

sealed interface LayerRow {
    data class DisplayHeader(val displayId: Int, val count: Int) : LayerRow
    data class One(val layer: LayerInfo) : LayerRow
    data class OtherHeader(val count: Int) : LayerRow
}

/**
 * 图层列表适配器。
 *
 * ★ 按**屏**分组 —— 这是「多层同屏管理」的字面要求：
 * 用户要先知道"这块屏上有几层"，再谈管理。
 */
class LayerAdapter(
    private val onSetVisible: (LayerInfo, Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<LayerRow> = emptyList()

    fun submit(mine: List<LayerInfo>, others: List<LayerInfo>) {
        val out = ArrayList<LayerRow>()
        mine.groupBy { it.displayId }.toSortedMap().forEach { (disp, items) ->
            out += LayerRow.DisplayHeader(disp, items.size)
            items.forEach { out += LayerRow.One(it) }
        }
        if (others.isNotEmpty()) {
            out += LayerRow.OtherHeader(others.size)
            others.forEach { out += LayerRow.One(it) }
        }
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is LayerRow.One) TYPE_LAYER else TYPE_HEADER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LAYER) {
            LayerVH(inf.inflate(R.layout.pt_item_layer, parent, false))
        } else {
            HeadVH(inf.inflate(R.layout.pt_item_section, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            // ⚠️ 必须写 ${r.displayId} —— 实测踩过：写成 `$r.displayId` 时
            //    Kotlin 只替换 `$r`，渲染出来是
            //    `TNT 屏 (DisplayHeader(displayId=100000, count=1).displayId)`
            is LayerRow.DisplayHeader -> (holder as HeadVH).title.text =
                (if (r.displayId >= LayerInfo.FIRST_VIRTUAL_DISPLAY_ID) {
                    "TNT 屏 (${r.displayId})"
                } else {
                    "手机屏 (${r.displayId})"
                }) + "   ${r.count} 层"

            is LayerRow.OtherHeader -> (holder as HeadVH).title.text = "其它应用的悬浮窗   ${r.count}"

            is LayerRow.One -> (holder as LayerVH).bind(r.layer)
        }
    }

    class HeadVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
    }

    inner class LayerVH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.layerName)
        private val proc: TextView = v.findViewById(R.id.layerProc)
        private val geo: TextView = v.findViewById(R.id.layerGeo)
        private val chips: TextView = v.findViewById(R.id.layerChips)
        private val btn: MaterialButton = v.findViewById(R.id.layerToggle)

        fun bind(l: LayerInfo) {
            name.text = l.featureName

            // ★ 第二行要**补充**信息，而不是重复第一行：
            //   名称已经用了窗口标题时，这里就不再打一遍
            proc.text = buildString {
                if (l.processName.isNotEmpty()) {
                    // ★ 本应用的进程名形如 `com.shware.mode:mod_hello` ——
                    //   前面那截包名每行都一样，挤掉的是**唯一有区分度的后缀**。
                    //   实测：写全名会被 ellipsize 成 `com.shware.mode:mod_…hware.mode`，
                    //   反而看不出是哪个组件。⇒ 本包的进程只显示 `:mod_xxx`。
                    append(l.processName.removePrefix(l.packageName).ifEmpty { l.processName })
                }
                // ★ 窗口标题只在**真的有额外信息**时才显示。
                //   本应用自己的窗口标题就是包名（`com.shware.mode`）⇒ 纯噪音；
                //   认不出归属的窗口，标题已经被上面当**名字**用了 ⇒ 重复。
                if (l.windowTitle.isNotEmpty() &&
                    l.windowTitle != l.featureName &&
                    l.windowTitle != l.processName &&
                    l.windowTitle != l.packageName
                ) {
                    append("   ·   窗口 ").append(l.windowTitle)
                }
                if (l.pid > 0) append("   ·   pid ").append(l.pid)
            }

            geo.text = "${l.geometry}   面积 ${l.area / 1000} 千像素²"

            chips.text = buildString {
                append(if (l.visible) "● 显示中" else "○ 未显示")
                append("   ·   ")
                append(if (l.touch == Feature.Touch.NONE) "穿透（不挡点击）" else "自吃（挡自己那一块）")
                if (l.featureId == null) append("   ·   非本应用")
            }

            // ★ 认不出归属的窗口不给控制按钮 —— 我们本来就管不着它，
            //   给个按不动的按钮只会误导
            val controllable = l.featureId != null
            btn.visibility = if (controllable) View.VISIBLE else View.GONE
            btn.text = if (l.visible) "隐藏" else "显示"
            btn.setOnClickListener { onSetVisible(l, !l.visible) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_LAYER = 1
    }
}
