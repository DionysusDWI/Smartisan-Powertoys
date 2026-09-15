package com.shware.mode.ui

import com.shware.mode.R
import com.shware.mode.core.feature.Feature

/**
 * ★ **图标键 → 资源** 的唯一映射点（任务 AP）。
 *
 * ## 为什么是"键"而不是资源 id
 *
 * mod 是**独立 APK**，它的 `R.drawable.foo` 在宿主进程里**毫无意义**
 * （资源 id 只在各自 APK 内唯一）⇒ 跨包传资源 id 必然错乱。
 * 所以契约里传的是**键名**（`MOD_ICON = "perfmode"`），由宿主查自己这张表。
 *
 * ## 三层兜底（★ 保证"永远不会是空白"）
 *
 * ```
 * mod 申报的 iconKey          e.g. "feat_perfmode"
 *      ↓ 查不到
 * 分类的默认图标               e.g. "cat_performance"
 *      ↓ 还查不到（不可能，但防手滑）
 * 通用拼图图标                 R.drawable.ic_pt_puzzle
 * ```
 *
 * ## ★ 扩展方式
 *
 * 新增图标 = 在 `scripts/gen_icons.py` 的 `JOBS` 里加一项 → 生成 → `ui_assets.py --slice`
 * → **在下面这张表里加一行**。
 *
 * ⚠️ 注意边界：**加这张表的一行是"加图标"，不是"加功能"**。
 * 加一个新 mod **不需要**动这里 —— 它会自动落到分类图标上。
 * 这正是 [Feature.icon] 那三层兜底存在的意义。
 */
object FeatureIcons {

    /** 键 → drawable。键名与 `gen_icons.py` 的 `JOBS` 一一对应。 */
    private val MAP: Map<String, Int> = mapOf(
        // 功能图标
        "feat_brightness" to R.drawable.ic_feat_brightness,
        "feat_battery" to R.drawable.ic_feat_battery,
        "feat_caption" to R.drawable.ic_feat_caption,
        "feat_perfmode" to R.drawable.ic_feat_perfmode,
        "feat_perfmon" to R.drawable.ic_feat_perfmon,
        "feat_hello" to R.drawable.ic_feat_hello,
        // 分类图标
        "cat_display" to R.drawable.ic_cat_display,
        "cat_input" to R.drawable.ic_cat_input,
        "cat_performance" to R.drawable.ic_cat_performance,
        "cat_media" to R.drawable.ic_cat_media,
        "cat_system" to R.drawable.ic_cat_system,
        "cat_other" to R.drawable.ic_cat_other,
        // 装饰
        "header" to R.drawable.ui_header,
    )

    /**
     * 取一个功能的图标。
     *
     * @return **一定**是一个有效的资源 id（最差是通用拼图）
     */
    fun of(f: Feature): Int = ofKey(f.icon)

    /** 按键取图；查不到按分类兜底，再查不到给通用图 */
    fun ofKey(key: String?): Int {
        if (key != null) MAP[key]?.let { return it }
        // 契约里可能直接报分类键（`MOD_ICON = "cat_performance"`），上面那张表已经覆盖
        return R.drawable.ic_pt_puzzle
    }

    /** 分类色 —— 图标之外的第二种"分类线索"（用在卡片的强调线上） */
    fun colorOf(c: Feature.Category): Int = when (c) {
        Feature.Category.DISPLAY -> R.color.pt_cat_display
        Feature.Category.INPUT -> R.color.pt_cat_input
        Feature.Category.PERFORMANCE -> R.color.pt_cat_performance
        Feature.Category.MEDIA -> R.color.pt_cat_media
        Feature.Category.SYSTEM -> R.color.pt_cat_system
        Feature.Category.OTHER -> R.color.pt_cat_other
    }
}
