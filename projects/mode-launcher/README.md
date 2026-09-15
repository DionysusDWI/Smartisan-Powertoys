# Smartisan Powertoys（前 MODE 启动器）

> 给 **Smartisan TNT** 加装**功能组件**的宿主。
> **不替换 TNT 桌面** —— 是往 TNT 上**装东西**。
> 面向**无 root** 的坚果设备（Shizuku 作为特权通道）。

**目标设备**：坚果 Pro 3（DT1901A / Smartisan OS 8.0.4 / Android 10 / SDK 29 / arm64）+ TNT GO
**包名**：`com.shware.mode` ｜ **工程名**：`ModeLauncher` ｜ **不用 root**（这是项目的定义，不是妥协）
**显示名**：`Smartisan Powertoys`

> ⚠️ **包名没跟着改名走** —— 这是**刻意的**，别改：
> Shizuku 授权 与 **Smartisan 悬浮窗授权**都是**按包名**记的，
> 换包名 = **两道只能人点的门要重开一遍**。详见
> [`powertoys/build.gradle.kts`](src/powertoys/build.gradle.kts) 顶部。

> **想接着干？** 先看 [`PROGRESS-STATE.MD`](../../PROGRESS-STATE.MD)，再看
> [计划书 Q1 · Mod 框架](../../.paper/plans/Q1-Mod框架.md)（契约）
> 与 [计划书 AP · 图形化 UI 与对外 API](../../.paper/plans/AP-图形化UI与对外API.md)（界面 / 图层 / API）。
> 本 README 只讲**这个东西是什么、怎么写一个 mod**。

---

## 一、这是什么

| | |
|---|---|
| **宿主**（`:app`） | 发现 / 启停 / 保活各 mod，并给用户一个**图形化界面** |
| **mod**（`:mod-*`） | 跑在 **TNT 屏或手机屏**上的功能组件（电量卡片、性能窗口…） |
| **契约** | 两边唯一的耦合面 —— **`Intent` + `meta-data`，不共享代码、不需要 AIDL** |

### 形态：**一个 APK，多个进程**

任务 AL 之后，宿主与全部 mod **打包成一个 APK**（`powertoys`），
**一次授权覆盖全部功能** —— 以前每多一个 mod 就多一套人工授权。

但**进程隔离仍在**：每个 mod 的 `<service>` 带 `android:process=":mod_xxx"`
⇒ **同一个包（一份权限）＋ 不同进程（互不拖累）**。

★ **对 mod 作者的影响：几乎没有。** 契约面（`Intent` + `meta-data`）**一个字都没变**，
写法也完全一样。变的只是"打出来的包是哪一个"（见 §三）。

> ★ **两条路并存，不是二选一**：第三方仍然可以**独立发一个 mod APK**，
> 宿主照样能发现它（`ModRegistry` 扫的是 `action`，不关心包名）。

---

## 二、★ 两条架构铁律

### 铁律 1：**宿主跑手机屏**

> 用户原话：「mod 启动器最好放在手机屏上渲染，因为 **TNT UI 如果刷新的话，
> 可能会改渲染状态，也把所有 TNT UI 上面的应用刷新一次**。」

宿主 `MainActivity` 里有一段**自愈**：发现自己在 TNT 屏就**以 `display 0` 重新启动自己**。

```kotlin
val curDisplay = windowManager.defaultDisplay.displayId
if (curDisplay != Display.DEFAULT_DISPLAY) {
    val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
    startActivity(Intent(this, MainActivity::class.java), opts.toBundle())
    finish(); return
}
```

> **判据**：修好之后 `DisplayInfo` 报的从**窗口尺寸** `486x972` 变成**真实分辨率** `2160x1440`
> —— 这本身就证明它是原生应用、不是 TNT 的一个窗口。

### 铁律 2：**组件落在哪块屏，由 mod 自己申报**

`MOD_TARGET = tnt | phone` ⇒ 宿主把它投喂给 mod 的 `EXTRA_DISPLAY_ID`。

| 屏 | displayId | 尺寸 | 谁在上面 |
|---|---|---|---|
| 手机屏 | **`0`** | 1080×2340 竖屏 | Android 状态栏 / 导航栏 / 用户的 app |
| **TNT 屏** | ★ **`100000`** | 2160×1440 横屏 | TNT 装饰层（顶栏 / Dock / 任务栏） |
| （物理 HDMI 口） | `3` | — | **只是 TNT 虚拟屏的输出口，应用不要送这里** |

---

## 三、快速开始

```bash
source ../../scripts/env.sh            # 激活便携工具链（仅当前 shell）

bash ../../scripts/build.sh --list     # 看有哪些目标
bash ../../scripts/build.sh powertoys  # 只构建

# 构建 + 侧载 + 授 appop + 打印"手动待办"
ANDROID_SERIAL=<你的设备序列号> bash ../../scripts/deploy.sh powertoys
```

目标名（定义在 [`scripts/targets.sh`](../../scripts/targets.sh)，build/deploy 共用）：

| 目标 | 内容 |
|---|---|
| ★ **`powertoys`** | **全部东西打成一个 APK**（常用）—— 就是 `Smartisan Powertoys` |
| `mode-all` | `powertoys` 的**别名**（历史名，仍然可用） |
| `perf-probe` | ★ 诊断探针（**另一个应用** `com.shware.perfprobe`）—— 性能基准 + 对外 API 验收 |
| `flashpill` / `tntgo` | 别的项目，与本模块无关 |

> ⚠️⚠️ **没有 `mod-xxx` 这种目标了**。任务 AL 合并之后，
> 所有 mod 都改成了 `com.android.library`（产出 AAR，**不能单独安装**）。
> `mod-xxx/build/outputs/` 里若还留着 apk，那是**合并前**的过期产物，别用。
>
> ⇒ 想单独看某个 mod 的效果：**临时**在
> [`powertoys/build.gradle.kts`](src/powertoys/build.gradle.kts) 的 `dependencies` 里
> 把对应的 `implementation(project(":mod-xxx"))` 加回来，看完再删。

---

## 四、★★★ 怎么写一个 mod

**最快的办法：照抄 [`mod-hello`](src/mod-hello/)** —— 它是刻意做成的模板 + 活文档。
⚠️ 它**不在产品里**（用户觉得那张卡片没有信息量），但**代码保留**，正是给你照抄用的。

### 4.1 契约：manifest 里申报

```xml
<service
    android:name=".MyModService"
    android:exported="true"
    android:foregroundServiceType="specialUse">

    <!-- ① 锚点 action —— 宿主 queryIntentServices 找的就是它 -->
    <intent-filter>
        <action android:name="com.shware.mode.action.MOD" />
    </intent-filter>

    <!-- ② 申报信息 -->
    <meta-data android:name="com.shware.mode.MOD_API"    android:value="1" />
    <meta-data android:name="com.shware.mode.MOD_ID"     android:value="my.mod" />
    <meta-data android:name="com.shware.mode.MOD_NAME"   android:value="我的组件" />
    <meta-data android:name="com.shware.mode.MOD_DESC"   android:value="一句话说明" />
    <meta-data android:name="com.shware.mode.MOD_TARGET" android:value="tnt" />
    <meta-data android:name="com.shware.mode.MOD_TOUCH"  android:value="none" />
    <!-- ★ 可选：有设置界面就申报（管理器会多一个「设置」按钮） -->
    <meta-data android:name="com.shware.mode.MOD_SETTINGS" android:value=".SettingsActivity" />
</service>
```

| meta-data | 必填 | 说明 |
|---|---|---|
| `MOD_ID` | ★ **是** | 稳定标识，宿主用它记开关状态。**缺了宿主不认这个服务** |
| `MOD_API` | 建议 | 契约版本，缺省当 0 ⇒ **被判不兼容** |
| `MOD_NAME` / `MOD_DESC` | 否 | 显示名 / 说明；`NAME` 缺省退回 `ID` |
| `MOD_TARGET` | 否 | `tnt`（默认）/ `phone` |
| `MOD_TOUCH` | ★ **强烈建议** | `none`（默认）/ `self` —— 见 4.3 |
| `MOD_ENABLED_BY_DEFAULT` | 否 | 缺省 **false**（装完不会自己乱画） |
| `MOD_SETTINGS` | 否 | **设置界面的 Activity 类名**（`.Xxx` 或全限定名）。<br/>申报了 ⇒ 卡片上多一个 **「设置」按钮** |
| ★ `MOD_CATEGORY` | 否 | 图形化界面里的**分组**：`display` / `input` / `performance` / `media` / `system` / `other`。<br/>不写 ⇒ 宿主**按 ID 前缀猜**，再兜底 `other` |
| ★ `MOD_ORDER` | 否 | **同组内**排序，整数，**小的排前面**。缺省 `0`；同权重按名字（**永不会因扫描顺序抖动**） |
| ★ `MOD_ICON` | 否 | 图标键（见下）。不写 ⇒ 用**分类的默认图标** |
| ★★ `MOD_A11Y` | 否 | **本组件依赖的无障碍服务组件**（`.KeyFilterService`）。见 4.5 |

> ### ★★★ 后四个**全是可选的** —— 这就是「**加新 mod，UI 零改动**」的落点
>
> 一个 mod **只写 `MOD_ID` + `MOD_API`** 也能在图形化界面上**正常显示**
> （落进「其它」分组、用分类兜底图标）。上面那几个只是让它**排得更准、更像样**。
>
> | 你写的 | 界面上会怎样 |
> |---|---|
> | 什么都不写 | 进「其它」，用拼图兜底图标 |
> | 只写 `MOD_CATEGORY` | 进对应分组，**用该分类的扁平图标**（★ 一眼能看出是兜底） |
> | 再写 `MOD_ICON` | 用你的专属图标（拟物化那套） |
> | 再写 `MOD_ORDER` | 在组内排到你想要的位置 |
>
> **图标键**目前有：`feat_brightness` / `feat_battery` / `feat_caption` /
> `feat_perfmode` / `feat_perfmon` / `feat_hello`。
> 要加新图标 → [`scripts/gen_icons.py`](../../scripts/gen_icons.py)（DashScope 生图）
> ＋ [`scripts/ui_assets.py`](../../scripts/ui_assets.py)（切片进 `res/`）
> ＋ [`FeatureIcons.kt`](src/app/src/main/java/com/shware/mode/ui/FeatureIcons.kt)（加一行映射）。
>
> ⚠️ **图标键是"键名"，不是资源 id** —— mod 是独立 APK，它的 `R.drawable.x`
> 在宿主进程里**毫无意义**（资源 id 只在各自 APK 内唯一）。

> **⚠️ aapt 会按字面量推断 `meta-data` 的类型**：`android:value="1"` 存进 `Bundle` 是 **`Int`**，
> `"false"` 是 **`Boolean`**。宿主已按真实类型容错读取；
> **你自己在别处读时不要直接 `getString`** —— 它在类型不符时**不抛异常、只返回 null**。

> **⚠️⚠️ `.Xxx` 缩写是按【你这个 service 所在的包】补全的**，不是按应用包名。
> 例如 `MOD_SETTINGS = ".SettingsActivity"` 对
> `com.shware.mode.mod.mymod.MyModService` 会解成
> `com.shware.mode.mod.mymod.SettingsActivity`。
> ★ 写全限定名当然也可以，而且**更不容易看错**。
> （宿主 2026-09-14 修过一个 bug：原来按应用包名补，导致「设置」按钮点了打不开。）

### 4.2 mod 侧必须做对的四件事

| # | 事 | 不做会怎样 |
|---|---|---|
| **1** | `onStartCommand` 里 **5 秒内 `startForeground`** | 进程被系统直接 ANR 掉 |
| **2** | 用 **`createDisplayContext(display)`** 拿 WindowManager | `addView` 只会画在**手机屏**上 |
| **3** | `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` | 加不上窗口 |
| **4** | `onDestroy` 里 **`removeView`** | 关了开关，卡片还赖在屏上 |

```kotlin
// ② 的机关全在这一行 —— 少了它，窗口只会落在默认屏
val ctx = createDisplayContext(getSystemService(DisplayManager::class.java).getDisplay(displayId))
ctx.getSystemService(WindowManager::class.java).addView(view, params)
```

**目标屏从哪来**：宿主投喂的 `EXTRA_DISPLAY_ID` 优先（它已经算好 TNT 屏是 `100000`）；
拿不到再自己兜底。**别硬编码 100000** —— 换个机型就不是它了。

### 4.3 ★★★ 触摸行为（`MOD_TOUCH`）—— 两种组件，两套互斥的标志

> **用户定的两条规则**：
> 「**没有点击按钮的 overlay 组件需要不影响点击到后面的东西**；
> **有点击按钮的 overlay 组件需要不会点击到后面的东西**。」

| 形态 | `MOD_TOUCH` | 窗口标志 | 效果 |
|---|---|---|---|
| **纯展示**（默认） | `none` | `NOT_FOCUSABLE \| NOT_TOUCHABLE` = **`0x18`** | ★ **整块矩形完全穿透** |
| **交互**（有按钮/可拖） | `self` | `NOT_FOCUSABLE \| NOT_TOUCH_MODAL` = **`0x28`** | ★ **只吃自己矩形内**的点击，矩形外穿透 |

**为什么必须由 mod 申报**：两者**互斥** —— `FLAG_NOT_TOUCHABLE` 让窗口
**连自己的按钮也点不了**。所以"有没有交互"只有 mod 自己知道。

**★ 头号陷阱**：根布局用 **`MATCH_PARENT`（哪怕全透明）触摸区就是整屏**
⇒ 交互型 mod **吃掉 TNT 上所有点击，桌面直接废掉**。**宽度也要写死**
（`WRAP_CONTENT` + 长文本会把卡片撑到半个屏幕，实测踩过：`800×1426 px`）。

**其余两条**：
- 以为加了 `FLAG_NOT_FOCUSABLE` 就"穿透了" ❌ —— 它只让**矩形外**的点击传给下层
- 实现"点空白处收起自己"要加 `FLAG_WATCH_OUTSIDE_TOUCH` + 读 `MotionEvent.ACTION_OUTSIDE`

### 4.4 ★★★ 装完之后还有一步 adb 干不了

**坚果上 overlay 有【两道独立的门】**：

| 门 | 怎么过 |
|---|---|
| ① Android `SYSTEM_ALERT_WINDOW` appop | **`deploy.sh` 自动授**（也可手敲 `appops set <pkg> SYSTEM_ALERT_WINDOW allow`） |
| ② ★ **Smartisan 自己的悬浮窗授权** | ★ **只能人点**：`手机管理 → 权限管理 → <应用> → 悬浮窗` |

**只过①不过②的症状极具迷惑性**：
**窗口加得上、`bounds` 也对、logcat 一个错都没有，但 `mPolicyVisibility=false` 永远不显示。**

> `deploy.sh` 结尾会把待办**逐条打印**出来。
> **好消息**：这两道授权都**按包名记，重装 APK 不会弄丢**（前提：包名不变）。

**需要 USB 的 mod**（如 `mod-tntgo`）还有第三个：首次启动会弹
「允许应用…访问该 USB 设备吗？」—— **建议勾「默认情况下用于该 USB 设备」**，以后插上就不再弹。

### 4.5 ★★ 如果你的 mod 要收【全局硬件按键】：无障碍那道门

> 2026-09-13（任务 AI）实测走通。**`AccessibilityService` + `canRequestFilterKeyEvents`
> 是 app 拿全局按键的唯一正路** —— 官方 API、**无 root**、**不抢焦点**、可选放行或吞掉。
> 样例见 [`mod-tntgo-brightness`](src/mod-tntgo-brightness/)（收 TNT GO 的亮度键）。

**代价：第四道人工门。** 装完要去 `辅助功能 → 服务` 里打开 ——
★ **它在列表最底部**（要往下滚），不在首屏。命名是 `<你的 android:label>`。

```xml
<service android:name=".MyKeyService" android:exported="true"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
    <meta-data android:name="android.accessibilityservice" android:resource="@xml/my_keyfilter" />
</service>
```

```xml
<!-- res/xml/my_keyfilter.xml —— ★ 机关全在这两个属性上 -->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/acc_desc"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true" />
```

**四条实测教训（都是坑）：**

| # | 事实 |
|---|---|
| **1** | ★★ **`adb shell input keyevent <N>` 到不了无障碍按键过滤器**（logcat 零命中）<br/>⇒ **这类 mod 只能真人按键验证**，没有 adb 自测路径 |
| **2** | ⚠️ **无障碍列表在「辅助功能 → 服务」区的最底部** —— 首屏看不到（用户会以为"没列出来"） |
| **3** | ✅ **坚果 A10 上 `uiautomator dump` 不会把服务挤掉**（★ 小米会，见 [04 §2.10](../../.paper/04-小米端复刻方案.md)）<br/>⇒ 取 UI 坐标可以放心用它 |
| **4** | ★★★ **`am force-stop <你的包名>` 会把登记整个抹掉**（`enabled_accessibility_services` → `null`）<br/>⇒ **每次冷启动调试之后功能就死了** |

> ### ★★★★ 所以：**依赖无障碍的 mod 一定要申报 `MOD_A11Y`**（2026-09-14 新增）
>
> 教训 4 的后果不是"功能没了"这么简单，而是 —— **它坏得毫无声息**：
>
> | 现象 | 真相 |
> |---|---|
> | 你的 mod 服务 | `isForeground=true`，**跑得好好的** |
> | 宿主的界面状态点 | ★ **绿色的「运行中」** |
> | 按键通路 | ★ **断了，根本没人在听** |
>
> **用户只能靠"感觉不对"发现。** 2026-09-14 真实发生过：
> 开发者（我）为了做冷启动测试 force-stop 了十几次，
> 把用户已经在用的 **TNT GO 亮度键**弄坏了，而界面上完全看不出来。
>
> **申报一行就能解决：**
>
> ```xml
> <meta-data android:name="com.shware.mode.MOD_A11Y"
>            android:value=".MyKeyService" />
> ```
>
> 宿主拿到它之后会做两件事：
>
> | # | 宿主行为 |
> |---|---|
> | **1** | ★ **卡片上打一条琥珀告警** —— `⚠ 无障碍服务未开启 —— 按键不会生效`，<br/>并给出开启路径（`设置 → 辅助功能 → 服务 → 「<你的 label>」`） |
> | **2** | ★ **自愈** —— 宿主有 Shizuku（shell 身份），看门狗每 30s 顺带把门重新打开。<br/>⚠️ 是**追加式**的（读 → 合并 → 再写），**不会覆盖掉用户其它应用的无障碍服务** |
>
> ⚠️ **不申报 ⇒ 宿主不会去猜**，也不会做任何自动重设（现有 mod 一个都不用改）。

> ★ **一个反直觉的设计点**：如果 mod 的**核心功能**放在无障碍服务里，
> 那它**不依赖宿主是否拉起自己**（无障碍服务是**系统绑定**的）。
> 启动器的开关这时只管 **overlay 那部分**。见 `mod-tntgo-brightness` 的类注释。

### 4.6 ★★ 要区分**短按 / 长按**？（任务 AJ 实测，三条可直接抄）

| # | 事实 |
|---|---|
| **1** | ★★★ **按住时设备【一个事件都不发】** —— 只有按下那一刻一个 `ACTION_DOWN`、松开那一刻一个 `ACTION_UP`。<br/>Android 侧 **`repeatCount` 恒为 0，没有自动重复**。<br/>⇒ ★ **存在真正的「持续按下」状态**，`DOWN → 等 N ms → 仍是按下?` 这套判定**可以直接用** |
| **2** | ★★ **`adb shell input keyevent` 到不了无障碍按键过滤器** ⇒ **这类逻辑没法用 adb 自测**。<br/>⇒ ★ **给自己留一个「模拟按下/模拟松开」的按钮**（直接调你的状态机，和真按键同一条路径）——<br/>否则**整条状态机既不能自动化验证、也不能回归**（`mod-tntgo-brightness` 自检台 ⑥ 区就是这么做的） |
| **3** | ★★★ **连续调节要"按时间反算"，不要"每次加固定值"**：<br/>一次串口写是 **开→写→读→关（~43ms，会抖动）** ⇒ 按固定值加会让**速度随抖动变化**，那就不是线性了。<br/>每次发送时用 `起点 + 方向·速度·(now − 起点时刻)` 反算 ⇒ **发送快慢只影响平滑度** |
| **4** | ★★★★ **要平滑，就得把帧率提上去 —— 而帧率会被两样东西分别焊死**（实测 23 → 116 Hz）：<br/>**① 每次开关设备** ⇒ 整段调节期间**把串口一直开着**，只 `write()`（→41 Hz）；<br/>**② 串口 `read()` 有最小超时（2ms）** ⇒ 回显排空**按时间节流**，别每次写都排（→61 Hz）；<br/>**③ ★ 别把中间量先取整**：`uiToMcu(round(ui))` 会让目标值每秒最多只变 `uiRate` 次，<br/>　　**帧率被"整数化"焊死**（77 UI/s ⇒ ≤77 Hz 上限）⇒ **在浮点量上做映射，只在最终值上取整**（→116 Hz） |
| **5** | ★★ **流式期间没有逐次校验 ⇒ 结束时回读一次 + 不符则补发**。<br/>好在 `AT+BKL` 是**绝对值**不是增量 ⇒ **补发即纠正**（自愈）。<br/>另外「**目标不变就不发**」⇒ 调到头之后**零流量** |

> ★★ **还有个坑值得记**：**同一个"速度"数字在两个量纲里可能差一个数量级。**
> `mod-tntgo-brightness` 的 BKL 满量程是 `9→2000`（1991 个单位），UI 满量程是 `0→100`。
> 同一个 `600`，前者是"3.3 秒走完"，后者是"**每秒走 6 遍**"。
> ⇒ **凡可调参数跨量纲复用，先把"尺子"统一**（例如统一成"满量程用时"）。

### 4.7 ⚠️ 关于 Shizuku：**每个 mod 得自己授权，代授做不到**

> **2026-09-12 查清并归档**：见 [`.ref/Search-Results/05-shizuku-and-shell.md`](../../.ref/Search-Results/05-shizuku-and-shell.md)

若要写一个**需要特权**的 mod（读 `/dev/input`、调 `am`/`wm` 等）：

| 事实 | 出处 |
|---|---|
| 权限 API **只有 `Shizuku.requestPermission()`** —— **只为自己请求**，没有"替别的包授权"的接口 | 官方开发指南 |
| ★ **官方原文**：「**Shell (ADB) cannot access other apps' data files `/data/user/0/<package>`**」<br/>⇒ 权限库在 Shizuku 应用私有 data，**shell 读写皆不可**（本机实测 `Permission denied` 一致） | 同上 |
| `bindUserService` 也**要求调用方自己已授权** ⇒ UserService 绕不过 | 同上 |

**⇒ 现状：每个需要特权的 mod 各自去 Shizuku 里授一次。**
（唯一不破坏安全模型的替代是"宿主中转白名单能力"——**但还没有 mod 需要它，暂未实现**。）

---

## 五、★★ 地基约束：`/proc` 整类对 app 关闭，`/sys` 开着

> 2026-09-12 实测（坚果 A10）。**要读系统数据的 mod 先看这条，能省一整轮试错。**

| ✅ 能读 | ❌ 读不到 |
|---|---|
| `ActivityManager.MemoryInfo` / `StatFs` / `BatteryManager` / `TrafficStats` | **`/proc/stat`**（CPU 使用率） |
| `/sys/devices/system/cpu/cpu<N>/cpufreq/`（频率、驻留时间） | **`/proc/loadavg`** |
| `/sys/class/thermal/thermal_zone<N>/temp`（温度） | `/proc` 下的整类节点 |

**⚠️ 现象是"节点不存在"而不是 "Permission denied"** —— shell（uid 2000）能读 ≠ app 能读。

**⇒ 要 CPU 使用率只有两条路**：① 认了均频代理（`time_in_state` 加权均频 ÷ 最高频，
**但要标注清楚不冒充使用率**）② 上 Shizuku（**每个 mod 单独授权，多一道人工门槛，下策**）。

**两个"数据看着对、其实错"的坑**：
- `/sys/class/thermal` 里**混着非温度节点**（`soc = 93` 不是温度）⇒ **按量纲过滤**（只收 1000–200000）
- ★ **`lmh-*` 是【限值】不是传感器** —— 恒定 75000，**把真实温度全盖掉**，
  卡片会显示一个**永不变化的假 75°C**。**不报错、不越界、看着完全合理** —— 最难查的那种

---

## 六、现有 mod

> ★ **`powertoys` 这一个 APK 里内置了 5 个**（下表除 `mod-hello` 外）。
> ⚠️ **`mod-hello` 不在产品里** —— 用户 2026-09-14 决定移除
> （「并没有比系统 UI 提供更多信息，但很占位置」），但**代码保留为模板**。

| mod | 目标屏 | 触摸 | 是什么 | 备注 |
|---|---|---|---|---|
| `mod-hello` | TNT | `none` | 示例状态卡片 | ★ **模板，照着抄**；⚠️ **不在产品里**，要跑得临时加回依赖（见 §三） |
| `mod-tntgo` | TNT | `none` | **TNT GO 精准电量**（手机 + TNT GO 双电量） | 走 USB CDC-ACM 串口读 `+BATCG=` |
| `mod-perfmon` | **手机** | **`self`** | 手机侧性能窗口（内存/存储/电量/CPU/温度/网络/频率） | **可长按拖动 / 双击设置**；只在 TNT 运行时出现 |
| `mod-livecaption` | TNT | `none` | **实时字幕**（抓音源 → 云端实时 ASR → 滚字幕） | 走 DashScope `qwen3-asr-flash-realtime`；**有设置界面**（音源/模型/key） |
| `mod-brightness` | TNT | **`self`** | ★ **TNT GO 亮度键**（键盘上两个太阳键 → `AT+BKL`） | 走 **`AccessibilityService` 按键过滤器**＋CDC-ACM 串口；<br/>★★ **第一个申报 `MOD_A11Y` 的 mod**（§4.5）⇒ 门没开时界面会**告警 + 自愈**；<br/>★ **短按跳一档 / 长按无极调节**（阈值·速度·线性域可调）；<br/>★ **OSD 圆角 + 长按拖动 + 双击设置**（照 `mod-perfmon`）；<br/>★ **默认 INVISIBLE** ⇒ 虽申报 `self` 但**平时完全不挡** |
| ★ `mod-perfmode` | phone | `none` | ★★ **性能模式**（抬起 CPU 频率下限） | ★ **免 root**：手搓 binder 事务打 QTI perf HAL 的 `perfLockAcquire`<br/>（`Parcel` + `IBinder.transact` 全是公开 API，**只有 `ServiceManager.getService` 一处反射**）<br/>★ 实测：三簇全到 `cpuinfo_max`（大核 710400→**2419200**）<br/>★★ **AV1 软解 25.8 → 44.4 fps（+71%）**（最贴近真实重负载）<br/>★ 功耗代价 **≈ 0**（空闲在 ±40 mA 噪声内）<br/>★ 三道闸门：**自动过期 / 过热兜底 / 崩溃自愈**（45 秒短锁 + 15 秒续期）<br/>★ **一个 uses-permission 都没有**（除前台服务）<br/>⚠️ **opcode 顺序是「大核→小核→超大核」，别按常识猜**（曾读反 ⇒ 收益减半）<br/>⚠️ **机理不明**：三个假说（热降频/运行队列/深 C-state）**都被直接测量排除** ⇒ 代码与界面**只陈述事实，不写机理断言** |

---

## 六·五、★★★ 对外 API（给别的工程用）

除了"被宿主拉起"，Powertoys 还**对外暴露一个 AIDL 接口** —— 别的应用可以直接调它。

```kotlin
val conn = object : ServiceConnection {
    override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
        val api = IPowerToysApi.Stub.asInterface(b)
        Log.i(TAG, "v${api.apiVersion}  ${api.status}")
        val arr = JSONArray(api.listFeaturesFast())   // 6 个功能，JSON 数组
    }
    override fun onServiceDisconnected(n: ComponentName?) {}
}
bindService(Intent("com.shware.mode.action.API").setPackage("com.shware.mode"),
            conn, Context.BIND_AUTO_CREATE)
```

| 要点 | 说明 |
|---|---|
| **接口定义** | [`IPowerToysApi.aidl`](src/app/src/main/aidl/com/shware/mode/api/IPowerToysApi.aidl) |
| **拿 AIDL** | ★ **照抄到你自己的工程**（不需要依赖对方的 AAR）—— 这也顺带验证了"接口自包含" |
| ⚠️ **A11+ 包可见性** | 你自己 manifest 里要加 `<queries><package android:name="com.shware.mode"/></queries>`，<br/>否则 `bindService` **静默失败** |
| ★★ **载荷全是 JSON 字符串** | **不用 Parcelable** —— 那样加一个字段就要两端同时重编，否则**旧调用方直接崩**。<br/>JSON **加字段对旧调用方无害** |
| **字段纪律** | ★ **只增、不改名、不删**。需要改语义时**加新字段**，老字段留着 |
| **能力边界** | 只能读写**本应用自己的**功能开关与图层。碰不到系统设置，<br/>也**不能**代替 Shizuku 授权（Shizuku 的授权只对本应用生效）⇒ 被调用**不构成提权** |
| ★ **样例代码** | [`perf-probe` 的 `ApiClientActivity`](src/perf-probe/src/main/java/com/shware/perfprobe/ApiClientActivity.kt)<br/>★ 它在**另一个应用**（`com.shware.perfprobe`）里 ⇒ **真跨进程验收** |

> ⚠️ **测这个 API 一定要用另一个应用。**
> 放在 Powertoys 自己的工程台里测，`bindService` 会拿到**本地 stub**，
> **根本不经过 Parcel** —— 那种"测试"什么也没验证到。
> （2026-09-14 用真跨应用测试才暴露出 AIDL 漏写 `package`、binder 事务上限两个真问题。）

---

## 七、已知坑速查

| 坑 | 症状 | 怎么办 |
|---|---|---|
| ★★★ **aapt 推断 meta-data 类型** | `getString` 静默返回 null ⇒ 正常 mod 被判"契约不兼容" | 按 `get(key)` 的真实类型读 |
| ★★★ **Smartisan 第二道门** | 窗口加得上、无报错，**永不显示** | 去手机管理开悬浮窗（§4.4） |
| ★★ **`dumpsys activity services` 类名是缩写** `pkg/.Cls` | 与全名对不上 ⇒ 永远判"未运行" | 见到 `.` 开头补包名 |
| ★★ **A11+ 包可见性** | `queryIntentServices` 静默返回空 | manifest 里加 `<queries><intent><action …/></intent></queries>`（**已加在 `:app`**） |
| ★★★ **`am force-stop` 抹掉无障碍登记** | 功能**静默失效**：服务在跑、界面绿着、按键没人听 | ★ **申报 `MOD_A11Y`**（§4.5）⇒ 界面告警 + 看门狗自愈 |
| ★★★★ **binder 事务有 ≈1 MB 上限** | `Transaction failed on small parcel; remote process probably died`<br/>★ **那句话是错的**，进程没死 | 大 dump **在设备侧先 `grep` 过滤**（`dumpsys activity services` **334 KB → 11 KB**） |
| ★★ **`exec` 失败 ≠ 连接断了** | UI 谎报「Shizuku 未连接」＋ 重绑循环 | 只有 `binder.pingBinder()` 真失败才算断 |
| ★**`/proc` 对 app 关闭** | 读系统数据拿不到 | §五 |
| ★★★★ **拿不到【目标屏】的尺寸** | 在 TNT 屏/副屏上摆窗口时算错位置（卡片跑到屏外） | ★ **`resources.displayMetrics` 与 `createDisplayContext(display).resources.displayMetrics` **都**返回【默认屏】**<br/>⇒ **只有 `display.getRealMetrics(dm)` 对**（实测 `2160×1440` vs 错的 `1080×2142`） |
| ★★★ **`gravity` 会改变 `params.x/y` 的含义** | 存下来的位置下次恢复后卡片跑偏/出屏 | `BOTTOM\|CENTER_HORIZONTAL` 下 `x` = **居中偏移**；`TOP\|START` 下 `x` = **距左边缘**<br/>⇒ **持久化位置时全程只用一种 gravity**，恢复后再夹一次 |
| ★ **`input tap` 用【错误屏】的坐标会静默打空** | 点了没反应，没有任何报错 | 跨屏点击要用 `input -d <displayId> tap` |
| ★★ **`am start` 对 `exported="false"` 的 Activity** | **打印 `Starting:` 却什么都不做**（无报错） | 走 UI 路径，或临时设 `exported="true"` |
| ★★ **`setChecked` 会触发 `onCheckedChange`** | RecyclerView 复用卡片时把没动过的开关当成一次真实操作 | 先 `setOnCheckedChangeListener(null)` 再设值 |
| ★★ **无障碍服务收不到注入键** | `input keyevent` 测不出来 | §4.5 教训 1 —— **只能真人按**（但**触摸**可以用 `input --ext-display` 注入） |
| ★★ **TNT 模式下新启动的 Activity 会跑到外接屏** | 截图看手机屏 ⇒ 以为"没启动" | `TntActivityStarterImpl: hasStartedOnOtherDisplay … displayId: 100000`<br/>⇒ ★ **截图要显式 `-d 100000`**（任务 AK 误判过一次） |
| ★ **无障碍列表"看不见"** | 以为没注册上 | §4.5 教训 2 —— **在「辅助功能 → 服务」区最底部** |
| ★ **`input tap` 打不中开关** | 从截图目测的坐标是错的 | 用 `uiautomator dump` 取真实 `bounds`（坚果上**不会**弄掉无障碍服务） |
| ★ **Kotlin 块注释可嵌套** | 路径里出现"星号紧跟斜杠"会**提前闭合注释**，报一堆 `Expecting a top level declaration` | 写路径用 `<N>` 代替通配符 |
| **多 mod 位置会撞** | 两个 mod 都写 `TOP\|END, 40,40` 就叠在一起 | 位置由 mod 自己定，**约定好各占一处** |
| **窗口 `params.x/y` 相对【内容区】** | 拖到屏幕底部会钻到导航栏下 | 用 `getLocationOnScreen()` 现算偏移，别硬编码 |

---

## 八、目录结构

```
projects/mode-launcher/
├── src/
│   ├── settings.gradle.kts        # ★ 一个工程，多个模块 → 【合并成一个 APK】
│   ├── powertoys/                 # ★★ 应用壳：把下面所有东西打进一个 APK
│   ├── app/                       # ★ 宿主（library）：UI + 契约 + mod 管理
│   │   └── .../com/shware/mode/
│   │       ├── ui/                    # ★★ 图形化界面（任务 AP）
│   │       │   ├── HomeActivity.kt    #   ★ 主界面：功能卡片列表（用户入口）
│   │       │   ├── LayerActivity.kt   #   ★ 多层同屏管理（实测 dumpsys window）
│   │       │   ├── FeatureAdapter.kt  #   卡片渲染（**不认识任何具体功能**）
│   │       │   └── FeatureIcons.kt    #   图标键 → 资源（三层兜底）
│   │       ├── core/feature/          # ★★ 统一模型层
│   │       │   ├── Feature.kt         #   统一功能模型（UI 与 API 都只认它）
│   │       │   ├── FeatureRegistry.kt #   唯一的"来源适配"点（mod → Feature）
│   │       │   └── LayerManager.kt    #   ★ 图层登记表（读**实测**而非申报）
│   │       ├── api/                   # ★★ 对外 API（任务 AP）
│   │       │   ├── IPowerToysApi.aidl #   AIDL 接口（载荷全是 JSON 字符串）
│   │       │   ├── PowerToysApiService.kt
│   │       │   └── FeatureJson.kt     #   模型 → JSON（字段**只增不改不删**）
│   │       ├── MainActivity.kt        # ★ 工程验证台（940 行）—— **降级到「开发者选项」入口**
│   │       ├── LauncherService.kt     # 前台服务保活 + 30s 看门狗（★ 含无障碍自愈）
│   │       ├── mod/                   # ★★ 契约与 mod 管理
│   │       │   ├── ModContract.kt     #   契约定义（**mod 作者必读**）
│   │       │   ├── ModSpec.kt         #   申报信息 + Target/Touch 枚举
│   │       │   ├── ModRegistry.kt     #   发现（queryIntentServices）
│   │       │   ├── ModRuntime.kt      #   启停 + 存活探测
│   │       │   └── ModStore.kt        #   开关持久化
│   │       ├── shell/                 # Shizuku 特权通道
│   │       └── platform/              # 显示器监听 / overlay 探针 / ★ A11yGate
│   ├── mod-hello/                 # ★ 模板 mod（⚠️ **不在产品里**，见 §三）
│   ├── mod-tntgo-battery/         # TNT GO 电量
│   ├── mod-perfmon/               # 手机性能窗口
│   ├── mod-livecaption/           # 实时字幕
│   ├── mod-tntgo-brightness/      # ★ TNT GO 亮度键（★ **`MOD_A11Y` 的样例**）
│   ├── mod-perfmode/              # ★ 性能模式（QTI perf HAL）
│   └── perf-probe/                # ★ 诊断探针（**另一个应用** com.shware.perfprobe）
│                                  #   └ ApiClientActivity = **对外 API 的跨应用验收**
└── tools/
    └── verify_parsetasks.py       # parseTasks 的离线镜像（对真实 dump 验证）
```

> ⚠️ **各 mod 是 `com.android.library`，不是独立应用**（任务 AL 合并的结果）。
> 它们**不能单独安装**；想单独跑见 §三 的说明。
```
