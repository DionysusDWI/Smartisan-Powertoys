package com.shware.mode.api

import com.shware.mode.core.feature.Feature
import com.shware.mode.core.feature.LayerConflict
import com.shware.mode.core.feature.LayerInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★★★ **统一模型 → JSON**（任务 AP / AP7）。
 *
 * ## 为什么集中在一个文件
 *
 * 对外 API 与对外广播**必须给出同一套字段名** ——
 * 否则调用方要按"从哪条路进来的"记两套。放一处就永远不会分叉。
 *
 * ## ★★ 字段纪律：**只增，不改名，不删**
 *
 * 调用方是按版本各自升级的，我们控制不了。所以：
 *
 * | 允许 | 禁止 |
 * |---|---|
 * | 加新字段 | 改字段名（旧调用方直接读不到） |
 * | 加新枚举值 | 改字段的**类型**（`true` 变 `"true"`） |
 * | 补 `null` 字段 | 删字段 |
 *
 * 需要改语义时就**加一个新字段**（如 `state` → 将来加 `stateV2`），老字段留着。
 */
object FeatureJson {

    /** 一个功能 → JSON。字段见 `IPowerToysApi.listFeatures` 的文档。 */
    fun feature(f: Feature): JSONObject = JSONObject().apply {
        put("id", f.id)
        put("name", f.name)
        put("desc", f.desc)
        put("category", f.category.key)
        put("categoryLabel", f.category.label)
        put("target", f.target.key)
        put("targetLabel", f.target.label)
        put("touch", f.touch.key)
        put("touchLabel", f.touch.label)
        put("enabled", f.enabled)
        put("state", stateKey(f.state))
        put("stateLabel", stateLabel(f.state))
        put("source", if (f.source == Feature.Source.BUILTIN) "builtin" else "external")
        put("order", f.order)
        put("versionName", f.versionName ?: JSONObject.NULL)
        put("icon", f.icon)
        put("iconKey", f.iconKey ?: JSONObject.NULL)
        put("api", f.api)
        put("apiCompatible", f.apiCompatible)
        put("hasSettings", f.settings != null)
        put("settingsComponent", f.settings?.flattenToString() ?: JSONObject.NULL)
        put("processName", f.processName)
    }

    fun features(list: List<Feature>): String =
        JSONArray().apply { list.forEach { put(feature(it)) } }.toString()

    /** 一个图层 → JSON（实测值，见 `LayerManager`）。 */
    fun layer(l: LayerInfo): JSONObject = JSONObject().apply {
        put("featureId", l.featureId ?: JSONObject.NULL)
        put("featureName", l.featureName)
        put("displayId", l.displayId)
        put("displayLabel", l.displayLabel)
        put("x", l.x)
        put("y", l.y)
        put("w", l.w)
        put("h", l.h)
        put("area", l.area)
        put("touch", l.touch.key)
        put("visible", l.visible)
        put("packageName", l.packageName)
        put("processName", l.processName)
        put("pid", l.pid)
        put("windowTitle", l.windowTitle)
        put("windowType", l.windowType)
        /** ★ 是不是本应用能管的功能（`false` ⇒ 界面上不该给控制按钮） */
        put("own", l.featureId != null)
        put("thirdParty", l.thirdParty)
    }

    fun layers(list: List<LayerInfo>): String =
        JSONArray().apply { list.forEach { put(layer(it)) } }.toString()

    fun conflict(c: LayerConflict): JSONObject = JSONObject().apply {
        put("displayId", c.upper.displayId)
        put("displayLabel", c.displayLabel)
        put("overlapArea", c.overlapArea)
        put("upperId", c.upper.featureId ?: JSONObject.NULL)
        put("upperName", c.upper.featureName)
        put("lowerId", c.lower.featureId ?: JSONObject.NULL)
        put("lowerName", c.lower.featureName)
        put("note", c.oneLine())
    }

    fun conflicts(list: List<LayerConflict>): String =
        JSONArray().apply { list.forEach { put(conflict(it)) } }.toString()

    // ------------------------------------------------------------------ 枚举 → 稳定的字符串键

    /**
     * ⚠️ 这几个 key **是 API 的一部分**，改名等于破坏兼容。
     * 它们与 `Feature.State` 的枚举名**刻意解耦** ——
     * 枚举重构时不会悄悄改掉对外契约。
     */
    fun stateKey(s: Feature.State): String = when (s) {
        Feature.State.RUNNING -> "running"
        Feature.State.STOPPED -> "stopped"
        Feature.State.ERROR -> "error"
        Feature.State.UNKNOWN -> "unknown"
    }

    fun stateLabel(s: Feature.State): String = when (s) {
        Feature.State.RUNNING -> "运行中"
        Feature.State.STOPPED -> "未运行"
        Feature.State.ERROR -> "异常"
        Feature.State.UNKNOWN -> "状态未知"
    }

    /** `"running"` → [Feature.State]；认不出给 [Feature.State.UNKNOWN]（**不抛异常**）。 */
    fun stateOf(key: String?): Feature.State = when (key?.trim()?.lowercase()) {
        "running" -> Feature.State.RUNNING
        "stopped" -> Feature.State.STOPPED
        "error" -> Feature.State.ERROR
        else -> Feature.State.UNKNOWN
    }
}
