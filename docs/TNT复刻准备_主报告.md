# TNT 复刻准备 · 主报告

> 目的:为「在小米 17 Pro Max(HyperOS 3 / Android 16 / 无 root)上通过自研窗口管理器复刻
> Smartisan TNT 桌面 OS 界面与 TNT GO 适配」做技术准备。
> 素材来源:① `.resource/TransportZIP-260910-2116/TNT_Handover.zip`(手机侧实测交接包,#01–#13);
> ② 本工作区 2026-09-10 通过 adb 对坚果 Pro 3(DT1901A)的**直接实测**。
> 状态:实测部分已完成;开源参考与技术方案见配套文档。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| TNT 是什么 | **同一 `system_server` 上的「第二显示面 + 桌面式窗口管理」**,内部代号 `revone`。不是虚拟机、不是独立系统。136 个实现类全在 system_server。 |
| 能否在小米上原生复刻 | **不能**。窗口管理 100% 在 L0(ROM/框架层),无 root 不可及。只能做 **L1(Shizuku)+ L3(桌面壳)的等价物**。 |
| TNT GO 黑屏原因 | **线材**。dmesg 已取证:USB 2.0 数据链路正常(键鼠/音频/摄像头可用),但 **PD 协商未建立**(`PD=0` + `SOURCE_DEFAULT`),DP Alt Mode 因此点不亮。 |
| TNT 桌面当前状态 | **已驻留运行**,在 `displayId=100000` 上,ProcessRecord 带 `pc:true` 标记;仅因虚拟屏 `state=OFF` 而无渲染。 |
| 可直接复用的资产 | 窗口策略表(`revone_window_config.xml`,744 App)、窗口放置三步算法、吸附几何参数、1150 条 UI 尺寸、权限集成接口定义。 |
| 最大未知数 | HyperOS 对 `am`/`settings` 的限制(见验证清单 V1–V5)、TNT GO 在小米上的 DP 协商行为。 |

---

## 1. TNT GO 硬件基础

### 1.1 设备身份

| 项 | 值 |
|---|---|
| 型号 | Smartisan TNT GO(deltainno) |
| USB VID/PID | `0x31CE` / `0x5101`(12750 / 20737) |
| Manufacturer / Product | `deltainno` / `Smartisan TNT go` |
| Serial | 形如 `207238A74152` |
| 内部代号 | **`boston`**(见 §1.4) |
| 原生分辨率 | 2160×1440 @ 60fps,24bpp,4 lane DP,面板 mfg_id `INX` |
| 面板密度 | 216 dpi(即 **1dp = 1.35px**) |

### 1.2 USB 拓扑(真机 dmesg 实证)

TNT GO **不是单一 USB 设备,而是一个内置 HUB 的复合体**:

```
usb 1-1    0424:2514   Microchip/SMSC USB2514 —— HUB 芯片
 ├─ usb 1-1.1  1f29:0000  Analogix "USB Type-C Digital AV Adapter"  ← DP→显示转换
 ├─ usb 1-1.2  0bda:0567  Realtek "ICT Camera"                      ← 摄像头
 └─ usb 1-1.3  31ce:5101  Smartisan TNT go (full-speed)             ← 主控
```

主控提供的功能接口:HID 键鼠(`1/1/0`、`1/2/0`、`3/1/2`、`3/0/0`)、CDC-ACM(`2/2/1` + `10/0`)、
**厂商自定义接口(`255/255/0`)**、音频(`17/0`)。

> 这解释了「键盘/声卡/摄像头等硬件输入可用」——它们全部走 USB 2.0 数据链路,与 DP 无关。

### 1.3 显示链路与黑屏根因

**成功时序**(历史记录,112246s):

```
typec: Type-C Sink (powered) connected
usbpd: dfp_send_uvdm                     ← 厂商自定义 PD 消息
Type-C Source (medium - 1.5A) → (high - 3.0A)   ← PD 协商成功,PD=1
[drm-dp] hpd_high:1
[drm-dp] dp_panel_resolution_info: 2160x1440@60fps 24bpp 4Ln
[drm-dp] dp_display_post_enable: DP module is ready to transfer display data
```

**当前状态**(故障):

```
pm8150b_charger: smblib_update_usb_type: APSD=CDP PD=0     ← 未走 PD!
Type-C SOURCE_DEFAULT detected                              ← 非 SOURCE_HIGH
（此后无 hpd_high、无新的 dp_panel_resolution_info）
```

期间还记录到一次完整的 DP 关闭:

```
usbpd0: USB Type-C disconnect
[drm-dp] dp_display_unprepare: [OK]
[drm-dp] dp_power_clk_enable: core:off link:off strm0:off strm1:off
```

**根因:线材**。普通充电线/数据线只有 4 根线芯(VBUS/GND/D+/D−),没有 SuperSpeed 差分对、没有 DP lane,
也无法承载 PD 协商所需的完整 CC 链路 → **USB 2.0 功能全通,高速/显示功能全无**。

**选线规格**:

| 要求 | 说明 |
|---|---|
| 全功能 Full-Featured USB-C 公对公 | 必须明确标注,不能是「充电线」 |
| USB 3.2 Gen2 (10Gbps) / USB4 / 雷电 3·4 | 保证有 SS 差分对;**雷电4/USB4 线兼容性最好** |
| 支持 DP Alt Mode(4 lane) | 点亮屏幕的关键 |
| 5A / 100W PD | TNT GO 自身宣告 Source 3A,线材需留余量 |
| 长度 | **被动全功能线超过 2m 信号劣化**;要长线优先 1.5–2m 认证线,或走主动式/光纤 |
| 拓扑 | **禁止经过 HUB/扩展坞**,DP Alt Mode 需直连 |

### 1.4 `boston`:TNT GO 的内部代号

在 `framework-res.apk` 中发现一组 **boston 专用受保护广播**(protected-broadcast):

```
smartisan.intent.action.BOSTON_CONNECTION_STATE            ← 连接状态
smartisan.intent.action.BOSTON_KEYBOARD_CONNECTION_STATE   ← 键盘盖状态
smartisan.intent.action.BOSTON_SCREEN_STATE                ← 屏幕状态
smartisan.intent.action.BOSTON_RECEIVED_TRANSFER_STRING    ← 传输内容
```

配套还有 `com.smartisanos.boston.permission.SETTING` 权限、`BostonReceiver`(在 DesktopSystemUI 中)。

> **对复刻的意义**:这组广播是 TNT GO 状态变化的官方通知通道。小米端自研 WM 没有这套广播,
> 需自行用 `UsbManager` + `DisplayManager.DisplayListener` 构造等价的状态检测。

### 1.5 厂商私有控制传输

Android 侧调用形态:`UsbDeviceConnection.controlTransfer(requestType, request, value, index, buffer, length, timeout)`

| 方向 | requestType | request | value | index | len | 功能 |
|---|---|---|---|---|---|---|
| OUT | `0x41` | `0x30` | `0xb0` | 0 | 0 | 设屏幕亮度 |
| OUT | `0x41` | `0x64` | `0xb0` | 0 | 0 | 设待机模式 |
| OUT | `0x41` | `0x60` | `0xb0` | ? | 8 | 设 LED 状态 |
| OUT | `0x41` | `0x50` | `0xa0` | 0 | 0 | 设 Dplane 数 |
| OUT | `0x41` | `0x32` | ? | ? | ? | 设屏幕颜色 |
| **IN** | **`0xC1`** | **`0x51`** | 0 | **`0xa0`** | **2** | **读 DP lane 数(唯一读取)** |

`0x41` = OUT|VENDOR|INTERFACE,`0xC1` = IN|VENDOR|INTERFACE。
实现位置:`com.android.server.pc.TntManagerService` 的 `doSetTntScreenBrightness` / `doSetTntStandByMode` /
`doSetTntLedState` / `setDplane` / `doSetTntScreenColor` / `getDplaneNum`。

**第二条私有通道**:USB-PD 的 `dfp_send_uvdm`(UVDM,厂商自定义 PD 消息),由手机主动发出。

### 1.6 电量:真机缺失,需自行定义

**手机从不询问 TNT GO 电量** —— `smartisan-services-tnt.jar` 全库无任何 battery 字符串,
7 个 `controlTransfer` 全是写。所以 TNT 界面只显示手机电量,不是 UI 漏做。

复刻建议(沿用 `req=0x51`,只换 `index`):

```
IN (0xC1) req=0x51 idx=0xA1 len=2  → 电量百分比 (uint16, 0–100)
IN (0xC1) req=0x51 idx=0xA2 len=2  → 电压 mV
IN (0xC1) req=0x51 idx=0xA3 len=2  → 电流 mA
IN (0xC1) req=0x51 idx=0xA4 len=2  → 温度 (0.1℃)
```

`controlTransfer` 走端点 0,**无需 claim interface**,不会抢走键鼠/串口。

---

## 2. Nut Pro 3 / TNT 系统结构

### 2.1 分层架构

| 层 | 内容 | 证据 |
|---|---|---|
| BOOTCLASSPATH | `smartisanos.jar` | `/system/framework/arm64/boot-smartisanos.{art,oat,vdex}` |
| SYSTEMSERVERCLASSPATH | `smartisan-services-tnt.jar` | `/system/framework/oat/arm64/smartisan-services-tnt.odex` |
| 唯一入口 | `com.android.server.TntFeatureFactoryImpl.getTntManagerService(Context, AMS, WMS, IMS, PMS, DMS) → ITntManager` | smali 实证 |
| 宿主 | 同一 `system_server`(非新进程/非 VM) | **TNT 服务开机即创建,不依赖插 TNT GO** |
| 桌面 UI | `com.smartisanos.desktop` + `com.android.desktop.systemui` + `com.android.desktop.recentspsp` | 三件套 |
| 原生库 | `libsmartisan-tnt.so`(**仅 arm64/arm**) | — |

### 2.2 显示面拓扑(本次实测)

```
mViewports=[
  DisplayViewport{type=INTERNAL, displayId=0, uniqueId='local:19260388476191617',
                  1080x2340, densityDpi=480},
  DisplayViewport{type=VIRTUAL,  displayId=100000,
                  uniqueId='virtual:smt.tnt.virtual.display:smt.tnt.virtual.display',
                  2160x1440, densityDpi=216}
]

DisplayDeviceInfo{"smt.tnt.virtual.display", 2160x1440, density 216,
    type VIRTUAL, state OFF, owner smt.tnt.virtual.display (uid -1),
    FLAG_OWN_CONTENT_ONLY, FLAG_MIRROR_PC}          ← Smartisan 自造 flag
```

**关键点**:

1. **TNT 桌面固定 `displayId=100000`**(Smartisan 自造,稳定),通过 `FLAG_MIRROR_PC` 镜像到物理屏。
2. **物理外接屏 displayId 不固定** —— 历史记录 1 → 3,本次实测为 **15**。→ **禁止硬编码**,按 `type=HDMI` / `uniqueId` / `FLAG_PRESENTATION` 识别。
3. **`mOverrideDisplayInfo` 伪装机制**:display 100000 的 override 信息被替换为物理 HDMI 屏的
   `DisplayInfo`(`type=HDMI`、`state ON`、`FLAG_PRESENTATION`、`uniqueId local:10652974438123012`)。
   这是「物理屏 id 变化但桌面始终在 100000」的实现手法,**对自研 WM 有直接借鉴价值**。

> ⚠️ **勘误**:本工作区此前的 tntgo-battery-overlay 工程引用了 `Display.FLAG_OWN_CONTENT_ONLY`,
> 当时判定为「AOSP 中不存在」而编译失败。**实测证明该常量在真机上真实存在** —— 它是 Smartisan
> 在自家 framework 里新增的 Display flag,AOSP 公开 SDK 中没有。工程里改用 `FLAG_PRIVATE` 是权宜之计,
> **在 TNT 真机上会改变语义**,需复核。

### 2.3 TNT 桌面当前驻留状态(本次实测)

```
Display #100000 (activities from top to bottom):
  Stack #38: type=home mode=freeform
  isSleeping=true                          ← 屏 OFF 导致
  mBounds=Rect(0, 0 - 0, 0)

    Task id #523  A=com.smartisanos.desktop
    mActivityComponent=com.smartisanos.desktop/.Desktop
    mMinWidth=486  mMinHeight=972
    isPersistable=true  numFullscreen=1  activityType=2

    mRootProcess=ProcessRecord{... com.smartisanos.desktop/u0a56
                               d100000 pc:true ...}      ← Smartisan 自定义字段!
```

**`d100000` + `pc:true`** 是 Smartisan 在 `ProcessRecord` 上扩展的字段,标记「该进程运行在 PC 模式的 100000 屏上」。

**运行中的 TNT 进程**(本次实测):

```
u0_a31  31646  com.android.desktop.systemui
u0_a31  31904  com.android.desktop.systemui:screenshot
u0_a56  31669  com.smartisanos.desktop
system  31709  com.android.desktop.recentspsp
```

> **意义**:TNT 桌面**已经驻留**,只等屏亮。物理屏修复后即刻可见,无需重新拉起。

### 2.4 关键全局设置(本次实测)

| 键 | 当前值 | 含义 |
|---|---|---|
| `tnt_display_connected` | **1** | 系统认为 TNT 屏已连接 |
| `global_pc_mode_settings` | 0 | PC 模式当前**未开启** |
| `global_pre_exit_pc_mode_settings` | 1 | 退出 PC 模式前的状态(曾进入过) |
| `revone_desktop_mode_switch` | 0 | 桌面模式开关 |
| `custom_key_desktop` | `KEYCODE_F11$META_META_ON+KEYCODE_D` | **桌面模式切换快捷键** |
| `desktop_wallpaper_uri` | `content://media/external/file/514191` | TNT 桌面壁纸 |
| `TNT_protect_eyes_enable` | 0 | 护眼模式 |

### 2.5 权限与第三方集成接口(从 framework-res 提取)

| 权限 | protectionLevel | 用途 |
|---|---|---|
| **`com.android.permission.PC_MANAGER_API`** | `0x3`(**signature**) | **PC 模式核心接口**,三件套均持有 |
| `com.android.desktop.permission.PROVIDER_CALL` | signature | 桌面 Provider 调用 |
| `com.android.desktop.recentspsp.ACCESS_CALL_METHOD` | signature | 最近任务接口 |
| `com.android.desktop.systemui.TAKE_SCREEN_SHOT` | signature | 截图 |
| `com.android.desktop.systemui.permission.SELF` | signature | 内部 |
| `com.smartisanos.desktop.LAUNCH_SERVICE` | — | 桌面服务拉起 |
| `com.smartisanos.boston.permission.SETTING` | — | TNT GO 设置 |

> **对复刻的意义**:TNT 通过 `PC_MANAGER_API` 这类**签名级权限 + Provider** 向自家应用开放能力。
> 小米端自研 WM 无平台签名,必须改用 **AIDL/ContentProvider + 运行时授权** 的自定义集成层。

### 2.6 三件套组件结构

| 应用 | Activity | Service | 关键权限 |
|---|---|---|---|
| `com.smartisanos.desktop` | 2(`.Desktop`、`ConfirmPasswordActivity`) | 2 | `MANAGE_ACTIVITY_STACKS`、`REMOVE_TASKS`、`REORDER_TASKS`、`START_ANY_ACTIVITY`、`INTERNAL_SYSTEM_WINDOW`、`PC_MANAGER_API` |
| `com.android.desktop.systemui` | **21**(含 `recents.RecentsActivity`、`tuner.TunerActivity`) | 12 | `INJECT_EVENTS`、`MANAGE_ACTIVITY_STACKS`、`STATUS_BAR_SERVICE`、`TABLET_MODE`、`MANAGE_MEDIA_PROJECTION`、`BIND_QUICK_SETTINGS_TILE` |
| `com.android.desktop.recentspsp` | 0 | 1(`com.android.base.TouchInteractionService`,`QUICKSTEP_SERVICE`) | `ACCESS_CALL_METHOD` |

三件套**均无 launcher 入口**(`aapt2 dump badging` 无 `launchable-activity`),纯系统服务型应用,由 system_server 拉起。

### 2.7 窗口策略表

`/system/etc/revone_window_config.xml`(197,872 字节,744 个 App)。
**本次实测已 `adb pull` 并与交接包比对:MD5 `d54449a88a195ad6f9919eec3b56cc54` 完全一致** → 资产可信。

Schema:

```xml
<Configuration>
  <special-video packageName="..." type="1|2" />
  <application package="...">
    <windowMode>0|1|2|4</windowMode>     <!-- 0-竖屏 1-横屏 2-max 4-全屏 -->
    <width>900</width>                    <!-- 默认宽度(dp) -->
    <height>694</height>
    <minWidth>900</minWidth> <minHeight>480</minHeight>
    <resizeMode>5</resizeMode>            <!-- 0-不可缩放 1-可缩放 2-仅高度 4-可全屏 8-等比 -->
    <forceResizeMode>0</forceResizeMode>
    <special-activity name=".xxx">
      <activity-windowMode>0</activity-windowMode>
      <activity-resizeMode>0</activity-resizeMode>
    </special-activity>
  </application>
</Configuration>
```

分布:`windowMode` 横屏 352 / 竖屏 199 / 全屏 174 / max 15;
`resizeMode` 5(=1|4) 264 / 1: 193 / 0: 190 / 4: 90 / 13(=1|4|8) 2。

**换算验证**:`com.smartisanos.notes` 的 `width=900dp` × 1.35(216dpi) = **1215px ≈ 实测 1219px** ✅

### 2.8 窗口放置三步算法

```
① getDefaultBounds            → 查策略表得默认 width/height + minWidth/minHeight
② adjustBoundsForAvoidOverlap → 避让屏幕上已有窗口,避免完全重叠
③ keepBoundsInScreen          → 钳制在屏内(considerLeftRight = true)
```

真机日志(`com.android.settings`):

```
getDefaultBounds: windowMode: 1, pkgName: com.android.settings
getDefaultBounds: server config width: 1040, height: 716
getDefaultBounds: min width: 1215, min height: 900
adjustBoundsForAvoidOverlap: before: Rect(378, 178 - 1782, 1145)
keepBoundsInScreen: bounds before adjust: Rect(378, 178 - 1782, 1145), considerLeftRight: true
getDefaultBounds: return bounds: Rect(378, 178 - 1782, 1145)
```

### 2.9 吸附几何

| 拖到 | 结果 bounds | 含义 |
|---|---|---|
| 左边缘 | `[4,4][1211,1355]` | 左半屏 |
| 右边缘 | `[949,4][2156,1355]` | 右半屏 |
| 顶边 | `[4,4][2156,1355]` | 全宽 |
| 左上角 | `[4,4][1211,675]` | 左上 1/4 |
| 右上角 | `[949,4][2156,675]` | 右上 1/4 |
| 左下角 | `[-131,683][1076,1355]` | 左下 1/4 |

参数:**边距 4px**;**"半屏"宽 1207px**(> 1080,故左右半屏略重叠);可用区高 1351px。

### 2.10 TNT 专用应用清单(83 个 Smartisan 包中的核心)

| 应用 | 包名 | TNT 证据 | 迁移方式 |
|---|---|---|---|
| 桌面三件套 | desktop / desktop.systemui / desktop.recentspsp | 本体 | **③ 不可移植 → 自研等价物** |
| 闪念胶囊 | `com.smartisanos.ideapills` | 策略表窄窗 360×694 | ② 需重做布局 |
| 大爆炸 | `com.smartisanos.textboom` | — | ③ |
| One Step 侧边栏 | `com.smartisanos.sidebar` | `.revone.sticky.*` | ③ |
| Sara 语音 | `com.smartisanos.sara` | `.bubble.revone.*` | ③ |
| 搜索 | `com.smartisanos.quicksearch` | `quicksearchbox.tnt.TNTSearchActivity` | ② |
| 文件管理器 | `com.smartisanos.filemanager` | `.tablet.TabletActivity` + `layout-land` | **① 可直接跑** |
| 时钟 / 计算器 | clock / calculator | `layout-land` | **① 可直接跑** |
| 音乐 | `com.smartisanos.music` | `.tablet.activities.*` | ② |
| 便签 / 写作 | `com.smartisanos.notes` / `.writer` | 策略表单独配置 | ② |
| 白板 | `com.smartisanos.whiteboard` | dock 项 | ② |

> **重要**:TNT 的「独立界面」主要是**代码级按窗口模式切换**,不是资源适配 → **不能只拷资源**。

---

## 3. 可直接复用的资产(本工作区已归档)

| 资产 | 路径 | 说明 |
|---|---|---|
| 窗口策略表 | `refs/device/revone_window_config.xml` | 744 App,MD5 与设备一致 ✅ |
| TNT UI 尺寸规格 | `refs/tnt_ui_spec/dimens.md` | **1150 条** TNT 专有 dimension,含 dp→px 换算 |
| TNT UI 布局 | `vendor/smartisan-tnt/.../SmartisanDesktopSystemUI/res/layout/` | **457 个**布局(已解码为 XML) |
| 桌面布局 | `vendor/smartisan-tnt/.../Desktop/res/layout/` | 45 个 |
| TNT 框架 smali | `vendor/smartisan-tnt/.../src/framework/` | `smartisan-services-tnt` 220 + `smartisanos` 1288 |
| TNT 接口定义 | `refs/device/framework-res-manifest.txt` | 权限/广播全量 dump |
| 兼容白名单 | `refs/device/tnt_compatibility_apps.json` | 8 个第三方 App |

---

## 4. 待补充(研究进行中)

- **开源社区参考资料**:锤子官方已开源 BigBang 与 OneStep(`github.com/SmartisanTech/android`);
  `whd-1999/flash-capsule`(**无 LICENSE,仅可净室参考**);社区 `com.hyper.onestep` 复刻版。
  完整清单待专项调研补充。
- **小米端自研 WM 技术方案**:Shizuku 能力边界、Android 16 freeform 现状、多屏渲染选型。
  完整方案待专项调研补充。

---

## 5. 附:换线后的自检

**换用全功能 USB-C 线后,先跑自检脚本**:

```bash
source scripts/env.sh
bash scripts/tntgo_check.sh
```

脚本按四层判定(PD 协商 → DP 链路 → USB 枚举 → TNT 桌面驻留),并附带外设检查。
**期望结果**:`PD=1` + `SOURCE_HIGH` + 出现 `hpd_high` + 虚拟屏 `state ON`。

若判定仍是「显示链路未建立」,说明线材仍不满足要求(需 USB3.2 Gen2 / USB4 / 雷电3·4,
支持 DP Alt Mode 4 lane,且不经过 HUB)。

---

## 6. 附:实测命令速查

```bash
# 显示面
adb shell dumpsys display | grep -E 'DisplayDeviceInfo\{|mDisplayId='
adb shell dumpsys display | grep -A2 'mOverrideDisplayInfo'      # 看伪装机制

# TNT 桌面驻留
adb shell dumpsys activity activities | grep -B3 -A12 'Display #100000'
adb shell ps -A | grep -E 'desktop|smartisanos.desktop'

# 关键设置
adb shell settings list global | grep -iE 'pc_mode|tnt|desktop|revone'

# USB / DP 链路
adb shell dmesg | grep -iE 'typec|drm-dp|TNT go|uvdm|hpd'
adb shell dumpsys usb | sed -n '/host_manager/,$p'

# 策略表(注意 Git Bash 需要 MSYS_NO_PATHCONV=1)
MSYS_NO_PATHCONV=1 adb pull /system/etc/revone_window_config.xml .
```
