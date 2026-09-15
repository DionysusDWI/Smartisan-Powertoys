# TNT UI 复刻 · 关键规格速查

> 从 TNT 桌面三件套的解码资源提取。用于在小米端自绘等价 UI。
> **屏参数**:2160×1440 @ **216dpi**,即 **1dp = 1.35px**。
> 完整数据:`refs/tnt_ui_spec/dimens.md`(1150 条)、`colors.md`、`window_policy_analysis.md`。

---

## 0. 首要结论:哪些能抄、哪些不能抄

| 组件 | 实现方式 | 复刻策略 |
|---|---|---|
| **TNT 桌面本体**(壁纸 + 图标网格) | **OpenGL 自绘**(`com.smartisanos.launcher.view.SMGLSurfaceView`) | ❌ **无布局可抄** —— `large_screen.xml` 仅 7 行(一个 GLSurfaceView + 一个重命名 EditText)。必须自行设计。 |
| **dock / 最近任务 / 通知面板 / 快捷开关** | 传统 XML 布局(SystemUI) | ✅ **可精确参考** —— 457 个布局已解码 |

> 这是复刻工作量的关键分野:**系统 UI 可照搬规格,桌面本体需原创设计**。

---

## 1. dock(底部任务栏)

窗口类:`NavigationBar`,`(0,0)(fill×81)`,frame `[0,1359][2160,1440]` → **高 81px**。

### 左侧功能区(`desktop_item_left_layout.xml`)

```
[搜索] [网络] [本地] [胶囊] [速览] ——— [拖动手柄]
```

| 图标 ID | drawable | 含义 |
|---|---|---|
| `function_search_all_btn` | `dock_search_all_list_normal` | 搜索 |
| `function_internet_btn` | `dock_internet_list_normal` | 网络 |
| `function_local_btn` | `dock_local_list_normal` | 本地 |
| `function_pills_btn` | `dock_pills_list_normal` | 闪念胶囊 |
| `function_bullet_btn` | `dock_bullet_list_normal` | 速览/子弹 |
| `handle_view` | `selector_dock_function_move_handle_left` | 拖动手柄 |

### 关键尺寸

| 资源 | dp | px @216dpi | 用途 |
|---|---|---|---|
| `desk_view_function_item_size` | **44** | **59.4** | dock 功能图标尺寸 |
| `desk_view_function_item_spread_size` | 42 | 56.7 | 展开态间距 |
| `desk_view_item_height` | 64 | 86.4 | 图标项高 |
| `desk_view_item_width` | 54 | 72.9 | 图标项宽 |
| `desk_view_height_big` | 72 | 97.2 | 大尺寸 |
| `desk_view_height_small` | 48 | 64.8 | 小尺寸 |
| `desk_view_app_dot_height/width` | 6 | 8.1 | 应用指示点 |
| 图标间距 | 8 | 10.8 | 布局内 marginLeft |
| `dock_margin` | 16 | 21.6 | dock 外边距 |
| `dock_panel_width` | 220 | 297 | dock 面板宽 |
| `dock_tools_bar_margin_bottom` | 26 | 35.1 | 工具条底距 |
| `dock_tools_bar_margin_horizontal` | 12 | 16.2 | 工具条横向边距 |

### 热区(实测,采样行 y ≈ 1400)

| x | 功能 |
|---|---|
| ≈100 | `quicksearch`(搜索) |
| **≈800** | **`launchpad`(应用抽屉,可开合)** |
| ≈900 | `whiteboard`(白板) |
| ≈1100 | `filemanager`(文件管理器) |
| **≈1950** | **通知/快捷面板**(`StatusBar_Sticky`) |
| ≈2100 | `ideapills`(闪念胶囊) |

**键盘等价**:`SEARCH` → quicksearch;`APP_SWITCH` → 最近任务。

---

## 2. 窗口标题栏(caption)

```
┌──────────────────────────────────────────┐
│ [图标] 标题 ……………………………………… [X] │
└──────────────────────────────────────────┘
   ↑ 最大化/还原 (+23, +30)      ↑ 关闭 (右缘−25, +30)
```

| 项 | 规格 |
|---|---|
| 左端按钮 | **最大化/还原**;点击位置 ≈ (窗口左缘 + 23, 窗口顶 + 30) |
| 右端按钮 | **关闭**;点击位置 ≈ (窗口右缘 − 25, 窗口顶 + 30) |
| 拖动 | 抓标题栏(窗口顶 + 30px)拖动,**触屏源/鼠标源均可** |
| 最大化结果 | bounds → `[0,0][2160,1440]`,**标题栏隐藏** |
| 最小化 | ❌ **TNT 没有最小化按钮** |

> 由**框架层 WMS decoration** 提供(`SmtWCFView`),非应用自绘。
> 小米端只能做 **overlay 覆盖**,需自行跟随窗口 bounds 更新。

**键盘快捷键**(来自全局设置 `custom_key_*`):

| 全局设置键 | 绑定 |
|---|---|
| `custom_key_minimize_current_window` | `META_ON + KEYCODE_M` |
| `custom_key_close_window` | `META_ALT_ON + KEYCODE_F4` |
| `custom_key_hide_app_all_windows` | `META_ON + KEYCODE_H` |
| `custom_key_hide_other_app_window` | `META_ON + META_ALT_ON + KEYCODE_H` |
| `custom_key_open_new_file_manager_window` | `META_ON + KEYCODE_E` |
| `custom_key_desktop`(桌面模式切换) | `KEYCODE_F11$META_META_ON+KEYCODE_D` |

---

## 3. 最近任务(`desktop_recents_task_view.xml`)

卡片布局:

```
┌────────────────────┐
│   app_thumbnail    │  ← 高度 desktop_thumbnail_view_max_height = 212dp (286px)
│   (缩略图)          │
│                    │
│   [app_icon 52dp]  │  ← 应用图标,居中偏上
│  [关闭][固定]       │  ← 底部居中,各 48dp
└────────────────────┘
```

| 资源 | dp | px @216dpi |
|---|---|---|
| `desktop_thumbnail_view_max_height/width` | 212 | 286.2 |
| `desktop_recents_view_height` | 260 | 351 |
| `desktop_recents_panel_height` | 360 | 486 |
| `desktop_recents_view_margin_top` | 24 | 32.4 |
| `desktop_task_view_radius` | **8** | **10.8** ← 卡片圆角 |
| `app_icon`(布局内硬编码) | 52 | 70.2 |
| `btn_close_task` / `btn_pin_task`(硬编码) | 48 | 64.8 |

**交互**:卡片左右并排;**拖动卡片 = 关闭任务**;有"固定"(pin)按钮。

颜色:

| 资源 | 值 |
|---|---|
| `recents_task_view_default_background_color` | `#fff3f3f3` |
| `recents_task_bar_default_background_color` | `#ffe6e6e6` |
| `recents_task_bar_disabled_background_color` | `#ff676767` |
| `recents_task_bar_light_icon_color` | `#ccffffff` |
| `recents_task_bar_dark_icon_color` | `#99000000` |
| `recents_task_bar_light_text_color` | `#ffeeeeee` |
| `recents_task_bar_dark_text_color` | `#cc000000` |
| `recents_freeform_workspace_bg_color` | `#33ffffff` |
| `recents_empty_tips_color` | `#26000000` |

---

## 4. 通知 / 快捷面板

| 项 | 规格 |
|---|---|
| 窗口 | `StatusBar_Sticky`,**右侧 505×1439** |
| 开合 | 点 dock 时间区(x≈1950, y≈1400) |
| 收起 | ⚠️ `BACK` **不能收**,需点空白处 |
| 内容 | **16 个快捷开关**(含 TNT 专属「无线TNT」「虚拟触控板」)+ 通知列表 + 天气/日历 |

快捷开关名(来自 `expanded_widget_buttons` 实测):
`toggleAirplane` `toggleWifi` `toggleMobileData` **`toggleWirelessTNT`** `toggleWifiAp` `toggleBluetooth`
`toggleDisableButtons` `toggleGPS` `toggleFlashlight` `toggleAutoRotate` `togglerrecordscreen` `togglepowersave`
`toggleRealtimeSubtitle` `toggleVibrate` `toggleMute` `toggleKeepScreenOn` `toggleLockScreen`
`toggleProtectEyes` `toggleReadingMode` `toggleAutoBrightness`

---

## 5. 其它面板

| 面板 | 窗口 | 规格 |
|---|---|---|
| 顶栏 | `RevTopBarView` | `(0,0)(fill×0)` —— **常态收拢,高 0** |
| 应用抽屉 | `launchpad` | **全屏叠加窗**(`fill×fill`, `ty=NAVIGATION_BAR_PANEL`);多列分类;点图标 → 自由窗口打开 + **抽屉自动关闭** |
| 遗留 | `DockedStackDivider` | `(0,0)(65×fill)`,**始终 INVISIBLE**,**不要实现** |
| SnapWindow | `desktop_snap_window_panel_root` | 面板高 348dp、stack view 208dp、margin top 140dp;位于 `statusbar.phone.snapwindow` 包 → 属**状态栏模块**,非吸附预览 |

---

## 6. 窗口几何

### 放置三步算法

```
① getDefaultBounds            → 查策略表(revone_window_config.xml)得默认尺寸
② adjustBoundsForAvoidOverlap → 避让已有窗口
③ keepBoundsInScreen          → 钳制屏内(considerLeftRight = true)
```

### 吸附几何

| 拖到 | bounds | 含义 |
|---|---|---|
| 左边缘 | `[4,4][1211,1355]` | 左半屏 |
| 右边缘 | `[949,4][2156,1355]` | 右半屏 |
| 顶边 | `[4,4][2156,1355]` | 全宽 |
| 左上角 | `[4,4][1211,675]` | 左上 1/4 |
| 右上角 | `[949,4][2156,675]` | 右上 1/4 |
| 左下角 | `[-131,683][1076,1355]` | 左下 1/4 |

**参数**:边距 **4px**;"半屏"宽 **1207px**(> 1080,左右略有重叠);可用区高 1351px。

### 默认窗口尺寸(策略表主力档位)

| width | 应用数 | px @216dpi | 典型用途 |
|---|---|---|---|
| **900dp** | 248 | **1215** | 横屏类(notes / chrome / 文件管理器 / 相册) |
| **360dp** | 197 | **486** | 竖屏类(设置 / 闪念胶囊) |
| **1095dp** | 100 | **1478** | 宽屏类(浏览器) |
| 960dp | 6 | 1296 | — |

高度几乎统一 `694dp`(937px)。详见 `window_policy_analysis.md`。

---

## 7. 颜色速查

| 资源 | 值 | 用途 |
|---|---|---|
| `navigation_bar_icon_color` | `#e5ffffff` | dock 图标 |
| `dock_hover_tips_color` | `#ccffffff` | dock 悬停提示 |
| `docked_divider_background` | `#ff000000` | 分隔条背景 |
| `docked_divider_handle` | `#ffffffff` | 分隔条手柄 |
| `desktop_notification_icon_tint_color` | `#9a000000` | 通知图标着色 |

---

## 8. 资源位置

| 资源 | 路径 |
|---|---|
| SystemUI 布局(457) | `vendor/smartisan-tnt/Smartisan_TNT_pkg/src/apps/SmartisanDesktopSystemUI/res/layout/` |
| Desktop 布局(45) | `vendor/smartisan-tnt/Smartisan_TNT_pkg/src/apps/Desktop/res/layout/` |
| RecentsPsp 布局(12) | `vendor/smartisan-tnt/Smartisan_TNT_pkg/src/apps/DesktopRecentsPsp/res/layout/` |
| 图标(drawable) | 各 APK 的 `res/drawable-xxhdpi-v4/`(SystemUI 有 2737 个) |
| 尺寸全量 | `refs/tnt_ui_spec/dimens.md` |
| 颜色 | `refs/tnt_ui_spec/colors.md` |
| 策略表分析 | `refs/tnt_ui_spec/window_policy_analysis.md` |
