package com.shware.mode.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.shware.mode.R
import com.shware.mode.core.feature.Feature

/**
 * 列表里的一行 —— 要么是**分组标题**，要么是**一张功能卡片**。
 *
 * ★ 用"一个列表两种行"而不是"每个分类一个 RecyclerView"：
 * 后者要在代码里为每个分类写一段（分类变了就得改代码），
 * 前者**分组表是数据**（[FeatureRows.build] 算出来的）。
 */
sealed interface FeatureRow {
    data class Header(val category: Feature.Category, val count: Int) : FeatureRow
    data class Card(val feature: Feature) : FeatureRow
}

/**
 * ★★ **分组逻辑** —— 全部集中在这一个函数里。
 *
 * 规则：
 * 1. 按 [Feature.Category.order] 排分类
 * 2. 分类内按 `Feature.order`，同权重按名字（**永远有确定顺序，不会因扫描顺序抖动**）
 * 3. **空分类不出现** —— 没有"显示"类组件时不显示「显示」这个空标题
 */
object FeatureRows {

    fun build(features: List<Feature>): List<FeatureRow> {
        val out = ArrayList<FeatureRow>(features.size + Feature.Category.entries.size)
        features.groupBy { it.category }
            .toSortedMap(compareBy { it.order })
            .forEach { (cat, items) ->
                if (items.isEmpty()) return@forEach
                out += FeatureRow.Header(cat, items.size)
                items.sortedWith(compareBy({ it.order }, { it.name }))
                    .forEach { out += FeatureRow.Card(it) }
            }
        return out
    }
}

/**
 * 功能卡片列表的适配器。
 *
 * ★★★ **它不认识任何具体功能** —— 所有差异都来自 [Feature] 的字段。
 * 这是"新增 mod 时 UI 零改动"能成立的原因。
 */
class FeatureAdapter(
    private val onClickToggle: (Feature, Boolean) -> Unit,
    private val onOpenSettings: (Feature) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<FeatureRow> = emptyList()
    private var skeleton = false

    fun submit(newRows: List<FeatureRow>, loading: Boolean) {
        rows = newRows
        skeleton = loading
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size + if (skeleton && rows.isEmpty()) SKELETON else 0

    override fun getItemViewType(position: Int): Int {
        if (position >= rows.size) return TYPE_SKELETON
        return when (rows[position]) {
            is FeatureRow.Header -> TYPE_HEADER
            is FeatureRow.Card -> TYPE_CARD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.pt_item_section, parent, false))
            TYPE_SKELETON -> SkeletonVH(inf.inflate(R.layout.pt_item_skeleton, parent, false))
            else -> CardVH(inf.inflate(R.layout.pt_item_feature, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> {
                val h = rows[position] as FeatureRow.Header
                holder.bind(h.category, h.count)
            }
            is CardVH -> {
                val c = rows[position] as FeatureRow.Card
                holder.bind(c.feature)
            }
        }
    }

    // ------------------------------------------------------------------ 分组标题

    /** 分组标题 —— ★ 带**分类图标**（扁平那套，见 `pt_item_section.xml` 的说明） */
    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
        private val icon: ImageView = v.findViewById(R.id.sectionIcon)

        fun bind(category: Feature.Category, count: Int) {
            title.text = "${category.label}   $count"
            icon.setImageResource(FeatureIcons.ofKey(category.iconKey))
            icon.visibility = View.VISIBLE
        }
    }

    /** 加载中的占位 —— 让首帧不是一片空白 */
    inner class SkeletonVH(v: View) : RecyclerView.ViewHolder(v)

    // ------------------------------------------------------------------ 功能卡片

    inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.icon)
        private val name: TextView = v.findViewById(R.id.name)
        private val desc: TextView = v.findViewById(R.id.desc)
        private val badgeExternal: TextView = v.findViewById(R.id.badgeExternal)
        private val stateDot: View = v.findViewById(R.id.stateDot)
        private val stateLine: TextView = v.findViewById(R.id.stateLine)
        private val switchOn: MaterialSwitch = v.findViewById(R.id.switchOn)
        private val btnSettings: MaterialButton = v.findViewById(R.id.btnSettings)
        private val btnToggle: MaterialButton = v.findViewById(R.id.btnToggle)
        private val gateBox: View = v.findViewById(R.id.gateBox)
        private val gateLabel: TextView = v.findViewById(R.id.gateLabel)
        private val gateHint: TextView = v.findViewById(R.id.gateHint)

        fun bind(f: Feature) {
            val ctx = itemView.context

            icon.setImageResource(FeatureIcons.of(f))
            name.text = f.name
            desc.text = f.desc.ifBlank { "（这个组件没有写说明）" }
            desc.visibility = View.VISIBLE

            badgeExternal.visibility =
                if (f.source == Feature.Source.EXTERNAL) View.VISIBLE else View.GONE

            stateLine.text = FeatureText.stateLine(f)

            // 状态点：底色用 drawable，颜色用 tint —— 一个 drawable 服务全部状态
            val dot = ContextCompat.getDrawable(ctx, R.drawable.pt_dot)!!.mutate()
            DrawableCompat.setTint(dot, ContextCompat.getColor(ctx, FeatureText.stateColor(f.state)))
            stateDot.background = dot

            // ⚠️ 先摘监听再设值 —— 否则 setChecked 会触发 onCheckedChange，
            //    在滚动复用时把用户没动过的开关当成一次真实操作，**把功能误启停**
            switchOn.setOnCheckedChangeListener(null)
            switchOn.isChecked = f.enabled
            switchOn.isEnabled = f.apiCompatible
            switchOn.setOnCheckedChangeListener { _, on -> onClickToggle(f, on) }

            // ★ 「停止」= 关掉开关（同一件事，两个视图）—— 见 HomeActivity.onToggle 的说明
            btnToggle.isEnabled = f.apiCompatible
            // ★★★ 修 N1（2026-09-15 AR7 实机复现）：**方向必须与开关同源。**
            //
            // 原来这里用 `f.state`（绑定时刻的运行时快照），而开关（`:158`）用 `f.enabled`（prefs）
            // —— **两个视图读两个可以不一致的源**，而 `:169` 的注释却声称它们是"同一件事"。
            //
            // 实测坏状态：`switchOn checked=true` ＋ `btnToggle text='启动'` ＋ `stateLine='未运行'`，
            // 而 `dumpsys` 同时证 `TntgoBatteryService isForeground=true`（**其实在跑**）。
            // 点这个「启动」⇒ `onClickToggle(f, true)` ⇒ `setEnabled(id, true)` ⇒ **写的还是 true**
            // ⇒ Toast 说「已启用」但 prefs 一字未改 ⇒ **正是用户报的「按钮不写开关」**。
            // 三路证据（bounds / prefs 前后值 / logcat）见 `.paper/plans/AR-审计整改.md` §9。
            //
            // ⇒ 开关与按钮**都**以 `f.enabled` 为准：这样两者在**任何**时刻都不可能互相矛盾。
            //   `f.state` 仍然只用于 [stateLine]/状态点 —— 它报告的是另一个事实
            //   （"服务在不在跑"），本身有用，但**不该拿来决定动作方向**。
            btnToggle.text = when {
                !f.apiCompatible -> "不可用"
                f.enabled -> "停止"
                else -> "启动"
            }
            // ★ 按钮与开关是**同一件事的两个视图**（都走 onClickToggle）——
            //   见 HomeActivity.onToggle 里对"为什么不能拆成两套语义"的说明。
            btnToggle.setOnClickListener {
                onClickToggle(f, !f.enabled)
            }

            btnSettings.visibility = if (f.settings != null) View.VISIBLE else View.GONE
            btnSettings.setOnClickListener { onOpenSettings(f) }

            // ★★★ 人工门告警 —— 「服务在跑」≠「功能可用」，这条必须显式打出来。
            //   见 pt_item_feature.xml 里 gateBox 的说明（以及那次真实事故）。
            gateBox.visibility = if (f.gate != null) View.VISIBLE else View.GONE
            if (f.gate != null) {
                gateLabel.text = "⚠ " + f.gate.label
                gateHint.text = f.gate.hint
            }

            // ★ 状态未知时整张卡片略微降饱和 —— 视觉上就能看出"这条不可信"
            itemView.alpha = if (f.state == Feature.State.UNKNOWN) 0.72f else 1f
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CARD = 1
        const val TYPE_SKELETON = 2
        const val SKELETON = 3
    }
}
