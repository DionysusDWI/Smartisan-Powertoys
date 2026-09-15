package com.shware.mode.mod

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * ★ mod 发现器 —— 扫全系统所有申报了 [ModContract.ACTION_MOD] 的 `<service>`。
 *
 * ⚠️ **A11+ 的包可见性**：启动器 manifest 里**必须**有
 * ```xml
 * <queries><intent><action android:name="com.shware.mode.action.MOD" /></intent></queries>
 * ```
 * 否则 [discover] **静默返回空**。
 * A10 的坚果没有这个限制 ⇒ 少了它在本机"看起来正常"，**换到 A16 就整个失效** ——
 * 属于最难查的那类问题。已写进 manifest。
 */
class ModRegistry(private val context: Context) {

    /** @return 全系统申报了的 mod，按名字排序 */
    fun discover(): List<ModSpec> {
        val pm = context.packageManager

        // ⚠️ 必须带 GET_META_DATA，否则 serviceInfo.metaData 是 null（拿不到申报信息）
        @Suppress("DEPRECATION")
        val found = pm.queryIntentServices(
            Intent(ModContract.ACTION_MOD), PackageManager.GET_META_DATA
        )

        val out = ArrayList<ModSpec>(found.size)
        for (ri in found) {
            val si = ri.serviceInfo ?: continue
            val spec = runCatching { toSpec(pm, si) }
                .getOrElse {
                    Log.w(TAG, "跳过 ${si.packageName}/${si.name}: ${it.javaClass.simpleName}: ${it.message}")
                    null
                } ?: continue
            out += spec
        }
        Log.i(TAG, "发现 ${out.size} 个 mod: ${out.joinToString { it.id }}")
        return out.sortedBy { it.name }
    }

    private fun toSpec(pm: PackageManager, si: android.content.pm.ServiceInfo): ModSpec? {
        val pkg = si.packageName
        val cls = si.name
        val md = si.metaData
        // metaData 理论上已随 GET_META_DATA 带出来；
        // 万一某厂商实现没带，再查一次（多一次 binder 调用，换的是确定性）
        val meta = md ?: runCatching {
            pm.getServiceInfo(ComponentName(pkg, cls), PackageManager.GET_META_DATA).metaData
        }.getOrNull()

        // ★ 没有 MOD_ID ⇒ 不是给我们用的。
        //   可能只是恰好用了同名 action ⇒ **静默跳过**，不要报错刷屏。
        val id = meta.str(ModContract.META_ID)?.trim().orEmpty()
        if (id.isEmpty()) {
            Log.i(TAG, "跳过 $pkg/$cls：没有 ${ModContract.META_ID}（不是 mod）")
            return null
        }

        val versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()

        return ModSpec(
            id = id,
            name = meta.str(ModContract.META_NAME)?.trim().takeUnless { it.isNullOrEmpty() } ?: id,
            desc = meta.str(ModContract.META_DESC)?.trim().orEmpty(),
            target = ModSpec.Target.of(meta.str(ModContract.META_TARGET)),
            touch = ModSpec.Touch.of(meta.str(ModContract.META_TOUCH)),
            api = meta.str(ModContract.META_API)?.trim()?.toIntOrNull() ?: 0,
            component = ComponentName(pkg, cls),
            versionName = versionName,
            enabledByDefault = meta.str(ModContract.META_ENABLED_BY_DEFAULT)?.trim()?.toBoolean() ?: false,
            settings = meta.str(ModContract.META_SETTINGS)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { resolveRelative(pkg, cls, it) },
            // ★ 三个「图形化 UI」用的可选申报 —— 拿不到就是 null / 0，UI 侧有默认行为
            categoryKey = meta.str(ModContract.META_CATEGORY)?.trim()
                ?.takeIf { it.isNotEmpty() },
            orderHint = meta.str(ModContract.META_ORDER)?.trim()?.toIntOrNull() ?: 0,
            iconKey = meta.str(ModContract.META_ICON)?.trim()
                ?.takeIf { it.isNotEmpty() },
            // ★ 进程名 —— 合并成一个 APK 后，这是把 overlay 窗口归属到具体 mod 的唯一凭据
            processName = si.processName ?: pkg,
            // ★ 无障碍组件（可选）—— 申报了宿主才能发现"这道门被关上了"
            a11y = meta.str(ModContract.META_A11Y)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { resolveRelative(pkg, cls, it) },
        )
    }

    /**
     * ★★★★★ **把 `meta-data` 里的组件名解析成 [ComponentName]。**
     *
     * ## ⚠️⚠️ 相对类名（`.Xxx`）的基准是【声明它的那个 service 所在的包】，不是应用包
     *
     * 这是 2026-09-14 踩出来的**真 bug**，而且它同时弄坏了两处：
     *
     * 合并成一个 APK 之后，各 mod 的类都在**自己的命名空间**里，例如
     * 亮度 mod 的服务是 `com.shware.mode.mod.brightness.BrightnessModService`，
     * 它的 `android:name=".SettingsActivity"` 在 manifest 里指的是
     * `com.shware.mode.mod.brightness.SettingsActivity`（**相对 manifest 的 package**）。
     *
     * 但 `MOD_SETTINGS` / `MOD_A11Y` 的**值是字符串** `".SettingsActivity"`，
     * 旧实现按**应用包名** `com.shware.mode` 去拼 ⇒
     *
     * | 申报 | 旧实现解析成 | 实际应该是 |
     * |---|---|---|
     * | `.SettingsActivity` | `com.shware.mode.SettingsActivity` ❌ | `com.shware.mode.mod.brightness.SettingsActivity` |
     * | `.KeyFilterService` | `com.shware.mode.KeyFilterService` ❌ | `com.shware.mode.mod.brightness.KeyFilterService` |
     *
     * ### 症状（都很有迷惑性）
     *
     * - **`MOD_SETTINGS`**：卡片上「设置」按钮**照常显示**，点了**打不开任何东西**
     *   （`ActivityNotFoundException`）。★ 因为按钮的显示只看"申报了没有"
     * - **`MOD_A11Y`**：写到 `Settings.Secure` 里的组件名是错的 ⇒
     *   `dumpsys accessibility` 里**永远不会绑上**，但 `settings get` 看起来"已经设了"。
     *   实测到的错值：`com.shware.mode/com.shware.mode.KeyFilterService`
     *
     * ## 判据
     *
     * | 值长什么样 | 怎么解析 |
     * |---|---|
     * | `.Xxx` | ★ **包名仍是应用包**，只有**类名**用"声明者所在包"补全 |
     * | `a.b.C` | 绝对类名，配应用包 |
     * | `a.b.C/D` 或 `a.b/C` | 已经是扁平组件名，原样用 |
     *
     * ⚠️⚠️ **别把"补类名"写成"换包名"** —— 2026-09-14 修这个 bug 时我自己又踩了一次：
     * `ComponentName(base, base + v)` 会把包名也换成 `com.shware.mode.mod.brightness`
     * ⇒ 写进 `Settings.Secure` 的值成了
     * `com.shware.mode.mod.brightness/…KeyFilterService`，
     * `settings get` 看着"设好了"，但 **`dumpsys accessibility` 里 `Bound services:{}` 是空的**
     * —— 组件名不对就**永远绑不上**。
     * ★ 教训：**必须验下游**（`dumpsys accessibility`），只读回自己写的那条设置是**假阳性**。
     */
    private fun resolveRelative(pkg: String, declaringCls: String, value: String): ComponentName {
        val v = value.trim()
        return when {
            v.startsWith(".") -> {
                // ★ 类名用「声明者所在包」补全；**包名保持应用包不变**
                val base = declaringCls.substringBeforeLast('.', pkg)
                ComponentName(pkg, base + v)
            }
            v.contains('/') ->
                ComponentName.unflattenFromString(v) ?: ComponentName(pkg, v)
            else -> ComponentName(pkg, v)
        }
    }

    /**
     * ★★★ **读 `meta-data` 必须容错** —— 这是 2026-09-12 真机踩出来的坑。
     *
     * **aapt 会按【字面量】推断 `android:value` 的类型**，存进 `Bundle` 时：
     *
     * | manifest 里写的 | Bundle 里实际是 |
     * |---|---|
     * | `android:value="1"` | ★ **`Int`** |
     * | `android:value="false"` | ★ **`Boolean`** |
     * | `android:value="tnt"` | `String` |
     *
     * 而 `Bundle.getString()` 遇到类型不符时**不抛异常，只打一条 warning 然后返回 `null`**
     * ⇒ 直接 `getString(META_API)` 拿到 null ⇒ `api` 落到 0
     * ⇒ **一个完全正常的 mod 被判成"契约版本不兼容"、开关变灰**。
     * （实测：示例 mod 报 `api=0 > 1`，而它 manifest 里明明写的是 `1`。）
     *
     * ⇒ 按 [get] 的真实类型取值，而不是赌它是 String。
     *
     * ⚠️ `containsKey` 不能省：`Bundle.getInt(缺失键)` 会**返回 0 而不是 null**，
     * 不先判存在的话，缺 `MOD_ID` 的服务会被误认成 mod。
     */
    private fun Bundle?.str(key: String): String? {
        if (this == null || !containsKey(key)) return null
        return when (val v = get(key)) {
            null -> null
            is String -> v
            is Int -> v.toString()
            is Long -> v.toString()
            is Boolean -> v.toString()
            else -> v.toString()
        }
    }

    private companion object {
        const val TAG = "Mode/ModRegistry"
    }
}
