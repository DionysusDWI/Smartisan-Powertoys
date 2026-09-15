package com.shware.mode.mod

/**
 * ★★★ MODE 启动器 ⇄ mod 之间的【唯一契约面】。
 *
 * mod APK 只要在自己的 `AndroidManifest.xml` 里照抄下面这些字符串，
 * 就能被启动器**发现 / 拉起 / 停掉** ——
 * **不需要共享 AAR、不需要 AIDL、不需要任何编译期依赖。**
 *
 * ## 一、mod 侧模板（照抄即可）
 *
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 *
 * <service
 *     android:name=".MyModService"
 *     android:exported="true"
 *     android:foregroundServiceType="specialUse">
 *     <intent-filter>
 *         <action android:name="com.shware.mode.action.MOD" />
 *     </intent-filter>
 *     <meta-data android:name="com.shware.mode.MOD_API"    android:value="1" />
 *     <meta-data android:name="com.shware.mode.MOD_ID"     android:value="my.mod" />
 *     <meta-data android:name="com.shware.mode.MOD_NAME"   android:value="我的组件" />
 *     <meta-data android:name="com.shware.mode.MOD_DESC"   android:value="一句话说明" />
 *     <meta-data android:name="com.shware.mode.MOD_TARGET" android:value="tnt" />
 *     <!-- ★ 触摸行为：不写 = none。有按钮就必须写 self，否则按钮点不动 -->
 *     <meta-data android:name="com.shware.mode.MOD_TOUCH"  android:value="none" />
 *     <!-- ★ 可选：有设置界面就申报（宿主的管理器会多一个「设置」按钮） -->
 *     <meta-data android:name="com.shware.mode.MOD_SETTINGS" android:value=".SettingsActivity" />
 *     <!-- ★ 可选：图形化 UI 用 —— 分类 / 组内排序 / 图标键，不写也有合理默认 -->
 *     <meta-data android:name="com.shware.mode.MOD_CATEGORY" android:value="performance" />
 *     <meta-data android:name="com.shware.mode.MOD_ORDER"    android:value="10" />
 *     <meta-data android:name="com.shware.mode.MOD_ICON"     android:value="perfmode" />
 * </service>
 * ```
 *
 * ## 二、mod 侧在 `onStartCommand` 里【必须】做的事
 *
 * ```kotlin
 * // ① 5 秒内 startForeground —— 否则系统直接 ANR 掉本进程（A8+ 的硬规则）
 * startForeground(NOTIF_ID, buildNotification())
 *
 * // ② 目标屏：【优先】用启动器算好传进来的，拿不到再自己兜底
 * val displayId = intent?.getIntExtra(ModContract.EXTRA_DISPLAY_ID, -1) ?: -1
 *
 * // ③ 挂 window —— ★ 必须用 createDisplayContext，否则只会画在手机屏上
 * val display = getSystemService(DisplayManager::class.java).getDisplay(displayId)
 * val ctx = createDisplayContext(display)
 * ctx.getSystemService(WindowManager::class.java)
 *    .addView(view, WindowManager.LayoutParams(
 *        WRAP_CONTENT, WRAP_CONTENT,
 *        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, ...))
 * ```
 *
 * > ★ **`createDisplayContext(display)` 是这块的全部机关** ——
 * > 少了它，`addView` 只会落在默认屏（手机屏）上。
 * > 坚果实测（2026-09-12）：加在 `display 100000` 上**成功**，
 * > 层位落在「**所有应用窗口之上、TNT 系统装饰之下**」。见计划书 Q1 §5。
 *
 * ## 二·补 · ★★★ 通知 ID 必须【全包唯一】（2026-09-15 实测发现）
 *
 * ### 为什么（这不是风格问题，是会真的坏）
 *
 * `startForeground(id, n)` 最终走 `NotificationManager.notify(id, n)`，
 * 而 **Android 的通知槽位作用域是【包 / UID】，不是进程**。坚果 Pro 3 实测：
 *
 * ```
 * NotificationRecord(... id=2002 ... key=0|com.shware.mode|2002|null|10001)
 * NotificationRecord(... id=2003 ... key=0|com.shware.mode|2003|null|10001)
 *                                      ^^^^^^^^^^^^^^^ 包名，不是进程名
 * ```
 *
 * ⇒ ★★ **合并为单一 APK（任务 AL）之后 6 个 mod 共享一个包名** ——
 * 两个 mod 用同一个 ID，**后 `startForeground` 的会把前一个的通知顶掉**：
 * 用户少看到一条常驻通知；并且停掉其中一个服务时，
 * **会把共用的那条通知一起撤掉，而另一个服务其实还在前台跑**。
 *
 * ⚠️ **进程隔离救不了它** —— `android:process` 只隔离内存，**不隔离通知槽位**。
 *
 * ### ★★ 分配表（**唯一登记处**；新 mod 从 `2010` 往后取）
 *
 * | ID | 归属 |
 * |---|---|
 * | `1000` | `LauncherService`（宿主） |
 * | `2001` | `mod-hello`（模板，默认不装） |
 * | `2002` | `mod-tntgo-battery` |
 * | `2003` | `mod-perfmon` |
 * | `2004` | `mod-perfmode` |
 * | `2005` | `mod-tntgo-brightness` ← ★ 2026-09-15 **从 `2003` 改过来**（与 perfmon 撞了） |
 * | `2006` | `mod-livecaption` ← ★ 2026-09-15 **从 `2004` 改过来**（与 perfmode 撞了） |
 * | `2010+` | 新 mod |
 *
 * ⚠️ **为什么 mod 侧要【手抄】而不是 `import` 本类的常量**：
 * mod **刻意不依赖 `:app`**（契约在 APK 边界上，外部独立 mod 也走同一套）。
 * ⇒ 这张表是唯一登记处，但值必须各自硬编码 ——
 * ★ **只改这里不等于改好了，两边都要动。**
 *
 * > ★★ **这条是"两个 mod 同时跑"这一类缺陷里【真的会发作】的那一个** ——
 * > 相比之下串口争抢至今**零观测**（见 `scripts/measure_serial_contention.sh`）。
 *
 * ## 三、⚠️ mod 作者不用操心、但要知道的一件事
 *
 * `android:value="1"` / `"false"` 这种字面量会被 **aapt 推断成 `Int` / `Boolean`**
 * 存进 `Bundle`（不是 String）。启动器侧**已经按真实类型容错读取**（见
 * `ModRegistry.str`），所以你照上面那样写就行。
 *
 * 但如果你在别处也读这些 meta-data，**不要直接 `getString`** ——
 * 它在类型不符时**不抛异常、只返回 null**，会把 `1` 读成"没有"。
 *
 * ## 四、★★★ 触摸行为 —— **必须声明**（用户 2026-09-12 明确要求）
 *
 * > 「**没有点击按钮的 overlay 层 mod 组件需要不影响点击到后面的东西**；
 * > **有点击按钮的 overlay 层 mod 组件需要不会点击到后面的东西**。」
 *
 * ### 为什么这是个真问题
 *
 * 光加 `FLAG_NOT_FOCUSABLE` **不叫穿透**。`FLAG_NOT_FOCUSABLE` 只保证窗口**外**的点击
 * 传给下层（它隐含 `FLAG_NOT_TOUCH_MODAL`）；**落在窗口矩形内的点击仍然归本窗口**。
 * 矩形内的视图若不消费，事件就被**丢弃**，**不会**转给下层
 * ⇒ 一张不接收任何输入的展示卡片，照样会把它盖住的那块区域的点击全吃掉。
 *
 * ### 两种形态，两套标志（★ 不能混用）
 *
 * | 形态 | 声明 | 窗口标志 | 效果 |
 * |---|---|---|---|
 * | **纯展示**（无按钮） | `<meta-data … MOD_TOUCH android:value="none" />` | `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE` | ★ **整块矩形完全穿透** |
 * | **有按钮** | `<meta-data … MOD_TOUCH android:value="self" />` | `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL` | ★ **只吃自己矩形内**的点击，矩形外照常穿透 |
 *
 * **`FLAG_NOT_TOUCHABLE` 是"完全不挡"的唯一手段** ——
 * 它让本窗口**永不接收触摸**。代价是**连自己的按钮也点不了** ⇒
 * 所以它和"有按钮"是**互斥**的，这就是为什么必须由 mod 自己声明。
 *
 * ### ⚠️ 三个会让 mod 作者翻车的陷阱
 *
 * | # | 陷阱 | 后果 |
 * |---|---|---|
 * | **1** | ★★ **根布局用 `MATCH_PARENT`**（哪怕是全透明的） | 触摸区 = **整屏** ⇒ 交互型 mod **吃掉 TNT 上所有点击**，整个桌面废掉 |
 * | **2** | 以为加了 `FLAG_NOT_FOCUSABLE` 就"穿透了" | 矩形内的点击静默消失（第①条的病） |
 * | **3** | 想在"点空白处时收起自己" | 需要额外加 `FLAG_WATCH_OUTSIDE_TOUCH`，读 `MotionEvent.ACTION_OUTSIDE`（**不用**加也能收到窗口外的按下通知，但只在你主动监听时） |
 *
 * ⇒ **纯展示型一律 `WRAP_CONTENT` + `FLAG_NOT_TOUCHABLE`；交互型一律 `WRAP_CONTENT`。**
 *
 * > 实测（坚果 A10 / TNT，2026-09-12）：用 `input --ext-display tap` 往卡片上打点，
 * > `none` 时卡片**收不到**该触摸（穿过它落到了下面的计算器上，计算器被点关掉）；
 * > `self` 时卡片**收到了**、下面的计算器**纹丝不动**。见计划书 Q1 §10。
 */
object ModContract {

    /** ★ 锚点：启动器靠这个 action `queryIntentServices` 发现全系统的 mod。 */
    const val ACTION_MOD = "com.shware.mode.action.MOD"

    // ---------------------------------------------------------------- 申报用 meta-data 的键

    /** 契约版本（整数）。缺省当 0 ⇒ 不兼容。 */
    const val META_API = "com.shware.mode.MOD_API"

    /** ★ 必填。稳定标识，启动器用它记住开关状态。缺了它 ⇒ 启动器**不认**这个服务。 */
    const val META_ID = "com.shware.mode.MOD_ID"

    /** 显示名。缺省退回 [META_ID]。 */
    const val META_NAME = "com.shware.mode.MOD_NAME"

    /** 一句话说明，显示在管理器列表里。 */
    const val META_DESC = "com.shware.mode.MOD_DESC"

    /** `tnt`（默认）/ `phone` —— 见 [ModSpec.Target]。 */
    const val META_TARGET = "com.shware.mode.MOD_TARGET"

    /**
     * ★★★ **触摸行为** —— `none`（默认）/ `self`，见 [ModSpec.Touch] 与本文档 §四。
     *
     * **不写 = `none`**（纯展示、完全穿透）。
     *
     * ⚠️ 默认值刻意取 `none` 而不是 `self`：
     * 忘写时"卡片不挡点击"（TNT 桌面照常好用，问题温和），
     * 而不是"卡片吃掉一片区域的点击"（用户会觉得桌面坏了，却查不出是谁干的）。
     * 反过来若忘写又加了按钮 ⇒ 按钮不响应，**作者自己一眼就发现** —— 失败更响。
     */
    const val META_TOUCH = "com.shware.mode.MOD_TOUCH"

    /** `true` = 启动器首次发现它时默认打开。缺省 `false`（**默认关**，免得装了就乱画）。 */
    const val META_ENABLED_BY_DEFAULT = "com.shware.mode.MOD_ENABLED_BY_DEFAULT"

    /**
     * ★ **可选的设置界面** —— 值是 Activity 的类名（`.Xxx` 缩写或全限定名都行）。
     *
     * 申报了 ⇒ 宿主的管理器会在该 mod 那一行多给一个 **「设置」按钮**；
     * 没申报 ⇒ 不显示按钮（**向后兼容**，现有 mod 不受影响）。
     *
     * **为什么用 service 上的 meta-data，而不是给 Activity 挂 action**：
     * 宿主**已经**在解析这个 service 的 meta-data 了，
     * 而挂 action 要再 `queryIntentActivities` 一遍、还要按包名过滤 —— 多一次 IPC、多一处会失败。
     *
     * > 用法见 [ModSpec.settings]，实现样例见 `mod-perfmon`。
     */
    const val META_SETTINGS = "com.shware.mode.MOD_SETTINGS"

    /**
     * ★ **可选的分类** —— `display` / `input` / `performance` / `media` / `system` / `other`。
     *
     * 值见 `Feature.Category` 的 `key`。认不出来或没写 ⇒ 宿主**按 MOD_ID 前缀猜**，
     * 再猜不中就是 `other`（见 `FeatureRegistry.guessCategory`）。
     *
     * **为什么要有它**：图形化 UI 是按分类分组展示的。没有这个键时，
     * 一个 id 叫 `mymod.thing` 的第三方 mod 只会落进「其它」——
     * 功能完全正常，但**永远排不进它该在的那一组**。
     */
    const val META_CATEGORY = "com.shware.mode.MOD_CATEGORY"

    /**
     * ★ **可选的排序权重** —— 整数，**小的排前面**。缺省 `0`。
     *
     * 只在**同一分类内部**比较；跨分类的顺序由分类自己的先后决定。
     * 同权重时按显示名排 ⇒ 不写也永远有确定顺序（**不会因扫描顺序而抖动**）。
     */
    const val META_ORDER = "com.shware.mode.MOD_ORDER"

    /**
     * ★ **可选的图标键** —— 见 `core.ui.FeatureIcons` 的键名表。
     *
     * 认不出来或没写 ⇒ 退回到**按分类**的默认图标（绝不空着）。
     *
     * ⚠️ **这里放的是"键"，不是资源 id**：mod 是独立 APK，
     * 它的 `R.drawable.xxx` 在宿主进程里**毫无意义**（资源 id 只在各自 APK 内唯一）。
     * 宿主只认自己内置的那套键名 ⇒ 零跨包资源耦合。
     */
    const val META_ICON = "com.shware.mode.MOD_ICON"

    /**
     * ★★★★ **可选**：这个 mod 依赖的**无障碍服务**组件（值形如 `.KeyFilterService`）。
     *
     * ## 为什么它必须存在（2026-09-14 的血教训）
     *
     * 有的 mod 靠 `AccessibilityService` + `canRequestFilterKeyEvents` 拿全局硬件按键
     * （**TNT GO 亮度键是这条路的唯一走法**）。而无障碍服务要**用户手动在系统设置里开**。
     *
     * ⚠️⚠️ **这道门会无声无息地关上**：`am force-stop <包名>` 会把
     * `enabled_accessibility_services` 抹成 `null`。
     *
     * ⇒ 后果是**功能静默失效**：mod 服务**还在前台跑**（`isForeground=true`），
     *   界面上绿点写着「运行中」，**可按键根本没人在听**。
     *   用户只能靠"感觉不对"发现 —— 这正是 2026-09-14 发生的事。
     *
     * ## 申报之后宿主能做的两件事
     *
     * | # | 宿主行为 |
     * |---|---|
     * | **1** | ★ **界面显式告警** —— 门没开就在卡片上标出来，**不再静默** |
     * | **2** | ★ **自愈** —— 宿主有 Shizuku（shell 身份），可**追加式**重设（见 `A11yGate`） |
     *
     * ⚠️ 不申报 ⇒ 宿主**不会**去猜这个 mod 需不需要无障碍，也不做任何自动重设
     * （**向后兼容**：现有 mod 一个都不用改）。
     */
    const val META_A11Y = "com.shware.mode.MOD_A11Y"

    // ---------------------------------------------------------------- 拉起时投喂给 mod 的 extra

    /**
     * ★★ 启动器算好的目标显示器 id（TNT 虚拟屏 = `100000`）。mod 应【优先】用它。
     *
     * ### ⚠️⚠️ 判"有没有给"必须用 `>= 0`，**不能用 `> 0`**
     *
     * ★ **手机屏的 id 就是 `0`** —— `EXTRA_DISPLAY_ID` = 0 是一个**完全合法的目标**
     * （"请画在手机屏上"），不是"没给"。
     *
     * 2026-09-14 实测发现：`mod-hello` / `mod-tntgo-battery` / `mod-tntgo-brightness`
     * 三个 mod 都写成 `if (fromLauncher > 0) fromLauncher else fallbackDisplayId()`，
     * ⇒ 传 `0` 时被当成"没给"，**静默落到兜底逻辑**（`maxByOrNull { displayId }`）
     * ⇒ 在坚果上**永远落到 TNT 虚拟屏 100000**。
     *
     * **症状极具迷惑性**：手机屏上什么都不出现，logcat 里那句
     * `启动器给的 display=0，实际用 100000` 看着还挺正常 ——
     * 直到你要做「多层同屏管理」、按屏归属图层时才会发现**归属是错的**。
     *
     * ### 正确写法
     *
     * ```kotlin
     * val fromLauncher = intent?.getIntExtra(ModContract.EXTRA_DISPLAY_ID, DISPLAY_ID_UNSPECIFIED)
     *     ?: DISPLAY_ID_UNSPECIFIED
     * val displayId = if (fromLauncher >= 0) fromLauncher else fallbackDisplayId()
     * ```
     */
    const val EXTRA_DISPLAY_ID = "com.shware.mode.extra.DISPLAY_ID"

    /**
     * [EXTRA_DISPLAY_ID] 的「**没指定**」哨兵值。
     *
     * ⚠️ 必须是**负数** —— 因为 `0` 是手机屏的合法 id（见 [EXTRA_DISPLAY_ID] 的说明）。
     */
    const val DISPLAY_ID_UNSPECIFIED = -1

    /** 目标类别（`tnt` / `phone`），冗余一份，便于 mod 自己兜底。 */
    const val EXTRA_TARGET = "com.shware.mode.extra.TARGET"

    /** 启动器自己的包名 —— mod 若要"别画在自己身上"可用。 */
    const val EXTRA_LAUNCHER_PACKAGE = "com.shware.mode.extra.LAUNCHER_PACKAGE"

    /**
     * 当前契约版本。
     *
     * mod 报的 [META_API] **大于**此值 ⇒ 启动器拒绝加载（并在 UI 上说明原因），
     * 而不是"拉起来看看会不会炸"。
     */
    const val API_VERSION = 1

    /** 当前契约版本对应的展示串。 */
    const val API_LABEL = "v1 (2026-09-12)"
}
