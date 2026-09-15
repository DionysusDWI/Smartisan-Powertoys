# 02 · TNT 系统结构规格(revone)

> **版本**:v1.7 ｜ **更新**:2026-09-11 ｜ **状态**:§8.9.3 **勘误** ——
> 用户指正「加速度计是给 R2 无线连接用的,Pro 3 有线连接用不到」;补三条代码印证,
> 并给出**对小米端的推论(标题栏自动翻转不需要复刻)**;原 `+HRM` 假设标注失效、保留追溯
> ｜ v1.6:§8.9.3 新增第三条候选传输通道(`AT+CHECK` 的 `+HRM=0,298,59`)
> ｜ v1.5:§8.9.4 **补实现细节** —— 标题栏 **`mTopMenu`/`mBottomMenu` 双容器**(鼠标/触摸二选一)+ 11 个私有按钮 ID + `smtMinimizeTask` 入口
> ｜ v1.4:快捷键列判定结案(真机走 `win` 列);§8.9.5 定论
> ｜ §8.7 `ActivityStackView`,§8.8 跨屏镜像搬运
> 变更记录见 [CHANGELOG.md](CHANGELOG.md)
> 来源:反编译 `smartisan-services-tnt.jar` / `framework.jar` + 真机实测
> 证据路径别名:`[WM]` = `12_反编译参考/java/services-tnt/com/android/server/wm/`、`[PC]` = `.../pc/`、`[DISP]` = `.../display/`、`[FRM]` = `12_反编译参考/java/framework/`、`[CFG]` = `03_配置资产/`

---

## 0. 定性

**TNT = 同一 `system_server` 上的「第二显示面 + 桌面式窗口管理」**,内部代号 `revone`。
不是虚拟机、不是独立系统、不是单独一层 —— 与 Android **同层**(同一 Linux 内核 + 同一 `system_server`)。136 个实现类全在 system_server。

**注入链**:

| 层 | 内容 | 证据 |
|---|---|---|
| BOOTCLASSPATH | `smartisanos.jar` | `/system/framework/arm64/boot-smartisanos.{art,oat,vdex}` |
| SYSTEMSERVERCLASSPATH | `smartisan-services-tnt.jar` | `/system/framework/oat/arm64/smartisan-services-tnt.odex` |
| 唯一入口 | `com.android.server.TntFeatureFactoryImpl.getTntManagerService(Context, AMS, WMS, IMS, PMS, DMS) → ITntManager` | smali 实证 |
| 对外 Binder | `android.pc.ISmtPCManager`(内部接口 `com.android.server.pc.ITntManager`) | `[PC]TntManagerService` 类签名 |
| 桌面 UI | `com.smartisanos.desktop` + `com.android.desktop.systemui` + `com.android.desktop.recentspsp` | 三件套 |
| 原生库 | `libsmartisan-tnt.so`(**仅 arm64/arm**) | — |

---

## 1. `SmtPCWindowManager` —— 窗口策略核心

`[WM]SmtPCWindowManager.java`(1447 行)。运行于 system_server,TNT 窗口的**一切几何决策中心**。

### 1.1 常量

| 常量 | 值 | 行号 |
|---|---|---|
| `ALLOW_DRAG_TNT_WINDOWS` | `"allow_drag_tnt_windows"`(Settings.Global) | L26 |
| `DP_WINDOW_OVERLAP_OFFSET` | 30 (dp) | L27 |
| `LANDSCAPE_MAX_WIDTH` | 1200 | L31 |
| `MAX_TIMES_TO_ADJUST_BOUNDS` | 30(避让循环上限) | L32 |
| `PORTRAIT_DEF_WIDTH` | 360 (dp) | L34 |
| `PORTRAIT_MAX_HEIGHT` | 900 (dp) | L35 |
| `mPortraitRatio` | 0.5f | L58 |
| `mLandscapeRatio` | 2.0f | L60 |
| `LANDSCAPE_DEF_HEIGHT` | 450 (dp) | L61 |

### 1.2 每屏初始化 `updateDisplayInfo(int displayId)`(L84–137)

```
L88  mCaptionBarHeightTouch  = 16dp
L89  mCaptionBarHeightMouse  = 16dp(sw600dp 为 24dp)
L101 mDeviceKey = "宽_高" + "@" + densityDpi      // 形如 "2160_1440@240"
L114 mScreenRect = (stableInsets.left, top, w - right, h - bottom)
L123 mWindowMarginTop   = 屏高 * 0.1
L124 mWindowMarginLeft  = 屏宽 * 0.1
L125/126 mMinWidth = mMinHeight = dp2px(220)
L127 mOffset = dp2px(30)            // 避让步长
L128 mPcWindowBoarder = dp2px(2.9)  // 窗口边框
L129 mMinSizeWidthForPortraitTask  = dp2px(360)
L130 mMinSizeHeightForPortraitTask = 360dp*2
L131 mMaxSizeWidthForPortraitTask  = dp2px(480)
L132 mMinSizeHeightForLandscapeTask= dp2px(450)
L133 mMinSizeWidthForLandscapeTask = dp2px(900)
```

### 1.3 ★ 默认放置算法(三步链)

`getDefaultBounds`(L336–403)+ `getBounds(boolean)`(L547–570):

```
getDefaultBounds(rec, mode):
  if mode==20: mode=0
  if mode==2: return getMaximizedBounds()
  if mode==4: return null                      // 全屏不设 bounds
  wDp = config.getWidth(pkg, activity)         // 查 revone_window_config.xml
  hDp = config.getHeight(pkg, activity)
  wPx = dp2px(wDp); hPx = dp2px(hDp)
  if 非法(w,h):                                 // 无配置/比例不符
     base = getBounds(isPortrait = (mode==0))
     if wPx>0: base.scale(wPx/base.width)       // 仅给单边则等比缩放
     else if hPx>0: base.scale(hPx/base.height)
  else:
     x = (屏宽 - wPx)/2
     y = (屏高 - hPx - mCaptionBarHeightMouse)/2   // 垂直居中扣除标题栏
     base = Rect(x, y, x+wPx, y+hPx)
  if base 非空: 套 minSize(小于则扩到 min 并重新居中)
  adjustBoundsForAvoidOverlap(base, rec)       // ★ 第 2 步
  if mode==19: return getMaximizedBounds(base, 19)  // ★ 第 3 步
  return base

getBounds(isPortrait):                          // 无配置时的兜底尺寸
  portrait : w=360dp, h=360/0.5=720dp
  landscape: h=450dp, w=450*2.0=900dp
  超出屏幕则按屏幕反推;居中同公式
```

### 1.4 最大化类模式的边界公式(L426–545)

基于 `getRealMaximizedBounds()` = `Rect(mScreenRect)` 右边界减 `smtGetSidebarWidth()`:

| windowMode | 操作(先切 half/quadrant,再 `inset(mPcWindowBoarder)`) |
|---|---|
| 2 | `getMaximizedBounds()` |
| 5(左半) | `right = centerX; if 宽<900dp: right=left+900dp` |
| 6(右半) | `left = centerX; if 宽<900dp: left=right-900dp` |
| 7(Q1 左上) | `right=centerX(<900dp 则 left+900dp); bottom=centerY(<450dp 则 top+450dp)` |
| 8(Q2 右上) | `left=centerX(右移保 900dp); bottom=centerY(保 450dp)` |
| 9(Q3 左下) | `left=centerX; top=centerY(below 保 450dp)` |
| 16(Q4 右下) | `right=centerX; top=centerY` |
| 17(上半) | `bottom=centerY(保 450dp)` |
| 18(下半) | `top=centerY(保 450dp)` |

### 1.5 `getMinSize`(L704–758)

```
min = (220dp, 220dp)
若 config.minWidth>0: min.x = dp2px(config.minWidth)
若 config.minHeight>0: min.y = dp2px(config.minHeight)
若 (mode,w,h) 非法: 回退 220dp;再用 config.width/height 合法性修正
min.y += mCaptionBarHeightMouse
横窗类: clamp 到 (>=900dp, >=450dp)
否则:   clamp 到 (>=360dp, >=640dp)
```

### 1.6 `keepBoundsInScreen`(L580–624)

```
if inScreen(rect): return
max = getMaximizedBounds()
if mCaptionTouchMode:                       // 触摸模式:标题栏在底部
    top溢出→offsetTo(left, max.top); bottom溢出→offsetTo(left, max.bottom-h)
else:                                       // 鼠标模式:标题栏在顶部
    bottom溢出→offsetTo(left, max.bottom-h); top溢出→offsetTo(left, max.top)
if considerLeftRight:
    left溢出→offsetTo(max.left, top); right溢出→offsetTo(max.right-w, top)
```

### 1.7 `adjustBoundsForAvoidOverlap`(L637–701)—— 窗口间避让

```
收集"可见且顶层任务 windowMode ∈ {0,1,19}"的 bounds 列表
循环 ≤30 次 while isWindowOverlapped(rect, list):
    向右移 mOffset(30dp):若出屏 → 撤回并转向左
        else 向下移 mOffset:若出屏 → 撤回并翻向上
        else 向上移 mOffset:若出屏 → 撤回并翻向下
    向左分支对称
最后 keepBoundsInScreen(rect, true, true)    // 保证标题栏可见
```

### 1.8 关键方法索引

**状态读取**:`getWindowState` L139/143/151、`getWindowBounds` L249、`getRestoreBounds` L274、`isTopNativeActivity` L288(读 `metaData["android.app.lib_name"]` 判原生)、`getLocalBounds` L299/303、`getDefaultBounds` L336、`getBounds` L426/L547、`getMinSize` L704/708、`getMaximizedBounds` L874/878/888、`isWindowOverlapped` L801、`inScreen` L794

**持久化**:`saveTaskFullscreenState` L822、`saveTaskSettings` L842、`TaskRecordEntry` L932、`putEntryForCurrentDevice` L951/955、`getTaskEntry` L976、`removeEntry` L994、`clearAllEntries` L1000

**工具**:`dp2px` L1005、`getRotateRect` L1065、`getDensityScale` L1213、`adjustBoundWithScale` L1269/1273、`exitPCMode` L1337、`setDesktopMode` L1362

---

## 2. `SmtPCWindowDefaultConfig` —— 策略表解析

数据文件:`[CFG]revone_window_config.xml`

### 2.1 加载路径与优先级(L31–51)

```
首选 /data/system/revone_window_config.xml   (可 OTA 覆盖)
次选 /system/etc/revone_window_config.xml
都不存在 ⇒ 全部走启发式默认
```

### 2.2 XML Schema(解析器**大小写不敏感**)

```xml
<application package="..." version="int">          <!-- version 缺失按 0 -->
    <windowMode>int</windowMode>
    <width>int</width>      <height>int</height>    <!-- dp -->
    <minWidth>int</minWidth> <minHeight>int</minHeight>
    <resizeMode>int</resizeMode>                    <!-- 位掩码见 §6 -->
    <forceResizeMode>int</forceResizeMode>          <!-- 1=强制可缩放 -->
    <special-activity name="Activity 全名">
        <activity-windowMode>..</activity-windowMode>
        <activity-width>..</activity-width> <activity-height>..</activity-height>
        <activity-resizeMode>..</activity-resizeMode>
        <activity-forceResizeMode>..</activity-forceResizeMode>
        <activity-minWidth>..</activity-minWidth> <activity-minHeight>..</activity-minHeight>
    </special-activity>
</application>
<special-video packageName="..." type="1|2"/>       <!-- 1=普通视频 2=全局配置 -->
```

解析细节:activity 级配置存在且合法则优先,否则回退 package 级(L342–424)。

### 2.3 配置生效优先级(L168–182)

```
fileVersion = XML 里 application/@version(缺=-1)
appVersion  = SmtWindowParams[0].mVersion(PackageManager 侧)
appVersion >= 0 且 >= fileVersion ⇒ 用「App 声明配置」
fileVersion >= 0 且 >= appVersion ⇒ 用「XML 文件配置」
否则 ⇒ 无配置
```

---

## 3. ★ 吸附与对齐

### 3.1 屏幕边缘吸附 —— `[WM]SmtTaskPositioner.java`

> **注意**:任务书常说的「SmtWindowAlignmentController 吸附」实际是**标题栏-标题栏对齐辅助线**;**屏幕边缘吸附**在 `SmtTaskPositioner`。

| 常量 | 值 | 行号 |
|---|---|---|
| `MIN_ASPECT` | 1.2f | L43 |
| `SIDE_MARGIN_DIP` | **50 (dp)** | L46 |
| `THRESHOLD_DP` | **6** | L601 |
| `SLOPE` / `SLOPE_LONG_PRESS` / `SLOPE_FOR_RESIZE` | 15 / 3 / 5 | L606–608 |
| 吸附总开关 | `Settings.Global "desktop_edge_adsorption_switch"` 默认 1 | L636 |
| 竖窗最小高 | dipToPixel(640) | L705 |
| `mPixThreshold` | density * 6.0 * densityScale | L769 |

**触发**(L1108–1121):
```
performEdgeAdsorptionIfNeeded():
    if mCurrentDimSide == 0: return false
    calculateDimLayerBounds()                 // 生成吸附目标 bounds
    mWindowDragBounds.set(mTmpRect)
    task.mTNT.setCollisionInfo(FLAG_USE_RESIZE_FRAME)
    activityManager.resizeTask(taskId, mWindowDragBounds, 9)   // ★ 9 = 1|8(8=吸附标记位)
```

**方向判定 `getDimSide(x,y)`**(L1252–1290):
```
前置: 任务必须 fillsParent() && orientation==2 && (resizeMode & 1)!=0
工作矩形:
   desktop_mode==1 → Rect(0, mSideMargin, screenW, screenH - mSideMargin)  // 留 50dp
   否则            → 全屏减 stableInsets
x <= left+mSideMargin 且 y 在中段  → 1 (左)
x+mSideMargin >= right 且 y 在中段 → 2 (右)
y <= top              且 x 在中段  → 4 (上,整宽)
y >= bottom           且 x 在中段  → 8 (下)
四角阈值 2*SIDE_MARGIN 交叠        → 5/6/9/10 (左上/右上/左下/右下)
```
**方向码**:1=L、2=R、4=T、8=B;组合 5=TL、6=TR、9=BL、10=BR。

**视觉反馈**:`showDimLayer` L1467,`mDimLayer.show(dragLayer, 1.0f, mTmpRect, 300L)`(300ms 渐显)。

### 3.2 窗口-窗口标题栏对齐 —— `[WM]SmtWindowAlignmentController.java`

- 常量:`ALIGN_RANGE = 15`(dp,L19)、`LINE_SHIFT_Y = 2`(dp,L20)
- 目标发现 `computeTargetAlignmentTask`(L199–231):触摸模式比对 `alignWin.top` 与 `task.top`(±15dp);鼠标模式比对 `bottom`;命中则给出以该边为中心的 ±15dp 对齐带
- 标题栏可见性 `isCaptionBarVisible`(L131–178):取顶窗 visibleBounds 的标题栏条带,扫描同屏其他可见窗口(排除 `FLAG_NOT_TOUCHABLE(16)`),做**区间合并**判断是否被遮挡

---

## 4. ★ 标题栏(装饰视图)

> **修正**:`SmtWCFView` **不是**标题栏 —— 它是 `SmtWindowCollisionFrame` 的碰撞/拖拽动画视图。
> **真正的 TNT 标题栏 = `com.android.internal.widget.SmtDecorCaptionView`**(framework.jar,app 进程内实现)。

证据:`[FSM]com/android/internal/widget/SmtDecorCaptionView.smali`(9398 行,继承 FrameLayout);
布局 `framework-res.apk`:`res/layout/revone_extend_screen_decor_caption.xml`

### 4.1 尺寸(framework-res.apk)

| 资源 | 值 |
|---|---|
| `revone_caption_bar_height` / `_mouse_height` | **27 dp** |
| `revone_caption_bar_btn_width` | **40 dp** |
| `revone_caption_view_padding` | 2 dp;描边 8 dp;裁剪圆角 **7 dp** |
| 标题字号 | 10 dp bold,左距 6 dp |
| 弹出菜单 | 宽 235dp、item 高 30dp、字号 11.25dp、图标 18dp、圆角 7.7dp |

### 4.2 按钮 id 映射(`initBtnsMap()` smali L3366–3486,可直接抄)

```
"pin" → 0x1020436    "min" → 0x1020434    "max" → 0x1020433
"close" → 0x1020430  "more" → 0x1020435   "title" → 0x1020438
"fullScreen" → 0x1020431   "portraitDensityScale" → 0x1020437
```

### 4.3 行为

- `getCaptionHeight()` L7153:`isCaptionShowing() ? mCaption.getMeasuredHeight() : 0`
- **拖拽** `onTouch` L7536–7968:MOVE 且 `passedSlop`(L3893,|Δ| > mDragSlop)⇒ **`startMovingTask(getRealRawX(), getRealRawY())`** —— 标题栏拖拽**直接交给 WM 的 task positioning 管道**
- **UP 时若已最大化 ⇒ `maximizeWindow(false)`(拖拽即还原)**
- 点击 `onClick` L7292–7432:max → `maximizeWindow(!isMaximized())`;close → `dispatchOnWindowDismissed(true,true)`;min → `minimizeWindow()`;more → `showPopupMenu()`;pin → `pinWindow()`
- 双击间隔 500ms;返回动画 240ms
- 模式枚举 `SmtDecorCaptionView$Mode`:INVALID=0, MOUSEMODE=1, TOUCHMODE=2

> **耦合注意**:`SmtPCWindowManager` 只持有**几何**高度(16dp),而装饰**实际**高度是 27dp。
> 复刻时建议几何高度取 27dp 或与装饰一致。

---

## 5. `SmtPCWindowSettingsWriter` —— 窗口几何持久化

| 项 | 值/行号 |
|---|---|
| 异步写 | `MSG_WRITE=1`;`WRITE_DELAY=300000`(5 分钟,L46) |
| 当前版本 | `mCurrentVersion=4`(L47) |
| 文件 | `AtomicFile(<data>/system/app_window_settings.xml)`(L77) |
| device key | `W_H@dpi`,如 `2160_1440@240` |
| 调度 | `scheduleWriteLocked()` L290(合并抖动)、`writeImmediatelyLocked()` L297 |

**落盘 schema**(L222–241):
```xml
<app_window_settings version="4" device="2160_1440@240">
    <package pkgName="..." version="N" windowState="501"
             densityScale="1.0" densityScaleForMeetingMode="1.0"
             bounds_left=".." bounds_top=".." bounds_right=".." bounds_bottom=".."
             need_fullscreen="false"/>
</app_window_settings>
```

**版本迁移**:`mLastVersion != 4` ⇒ 清空全部 entries;
并移除「同 resolution 前缀但 deviceKey 不同」的旧设备数据(L92–99)。

> `bounds_*_landscape`、`windowStateLandscape` 三个 tag 已声明但**当前写路径不写** —— 复刻可忽略。

---

## 6. ★ `windowState` 打包格式与全枚举

来源:`[FRM]SmtPCWindowPolicy.java`

### 打包公式(L97–99)
```
windowState = (forceResizeMode<<28) | (restoreMode<<24) | (captionMode<<20)
            | (touchMode<<16) | (resizeMode<<8) | windowMode

掩码:FORCE=0xF0000000  RESTORE=0x0F000000  CAPTION=0x00F00000
     TOUCH=0x000F0000  RESIZE=0x0000FF00    WINDOW=0x000000FF
```
> **`windowState: 501` = 0x501 = windowMode 1(横窗) + resizeMode 5(可缩放+可全屏)**

### windowMode(L50–66)

| 值 | 常量 | 语义 |
|---|---|---|
| 0 | WINDOW_MODE_PORTRAIT | 竖窗 |
| 1 | WINDOW_MODE_LANDSCAPE | 横窗 |
| 2 | WINDOW_MODE_MAXIMIZED | 最大化 |
| 4 | WINDOW_MODE_FULLSCREEN | 全屏 |
| 5 | WINDOW_LEFT_HALF | 左半 |
| 6 | WINDOW_RIGHT_HALF | 右半 |
| 7 | WINDOW_FIRST_QUADRANT | 左上 |
| 8 | WINDOW_SECOND_QUADRANT | 右上 |
| 9 | WINDOW_THIRD_QUADRANT | 左下 |
| 16 | WINDOW_FOURTH_QUADRANT | 右下 |
| 17 | WINDOW_TOP_HALF | 上半 |
| 18 | WINDOW_BOTTOM_HALF | 下半 |
| 19 | WINDOW_PORTRAIT_MAXIMIZED | 竖窗拉满高 |
| 20 | WINDOW_PORTRAIT_DENSITY_SCALED | 竖窗密度缩放 |
| 4096 | WINDOW_CANNOT_RESIZE | 不可缩放(带外标记) |
| 8192 | WINDOW_CANNOT_RESTORE | 不可恢复(带外标记) |

### resizeMode 位(L37–42)

| 位 | 常量 | 语义 |
|---|---|---|
| 1 | RESIZE_MODE_FREE | 自由缩放 |
| 2 | RESIZE_MODE_HEIGHT | 仅高度 |
| 4 | RESIZE_MODE_FULLSCREEN | 可全屏 |
| 8 | RESIZE_MODE_RATIO | 等比缩放 |
| 0 | RESIZE_MODE_NONE | 不可缩放 |

### 其余字段
- `forceResizeMode`:0=不支持、1=支持(支持时 `needRestore()` 恒 false)
- `restoreMode`:0=UNRESTORE、1=RESTORE
- `captionMode`:0=NORMAL、1=SEARCH_WINDOW、2=WITHOUT_CAPTION、3=SMART_SIDEBAR、4=POPUP_ACTIVITY、5=POPUP_ACTIVITY_APPLICATION_CENTER
- `touchMode`:0=DISABLE(鼠标)、1=ENABLE(触摸)
- 隐藏按钮位:CLOSE=1、MAX=2、MIN=4、MORE=8、PIN=16、RETURN=32、MULTI_WIN=64、VOICE=16384、SPLIT_LINE=32768、FULLSCREEN=65536

### 尺寸常量(L27–36)
竖窗 min 360×640dp / max 宽 480dp;横窗 min 900×450dp;边框 2.9dp;
鼠标 resize 热区 6dp、触摸 30dp。

---

## 7. `TntManagerService` —— 常量与 Binder API

### 7.1 关键常量

| 常量 | 值 |
|---|---|
| `ACTION_DESKTOP_READY` | `com.smartisanos.desktop.ready` |
| `ACTION_LOCKSCREEN_READY` | `com.android.desktop.systemui.lock_ready` |
| `ALLOW_DRAG_TNT_WINDOWS` | `allow_drag_tnt_windows` |
| `GLOBAL_DOCK_HEIGHT_SETTINGS` | `smartisanos_dock_height` |
| `PC_IM_METHODID` | `com.smartisanos.ime/.SmartisanIME` |
| `PERMISSION_PC_MANAGER_API` | `com.android.permission.PC_MANAGER_API` |

### 7.2 Settings 键表

| 键 | 域 | 说明 |
|---|---|---|
| `global_pc_mode_settings` | Global | PC 模式总开关(1=开) |
| `global_pc_version` | Global | — |
| `back_with_esc_enabled` | Global | ESC 映射返回 |
| `tablet_enabled_shortcut_buttons` / `tablet_shortcut_button_switch` / `tablet_shortcut_buttons_with_f_keys` | Global | 快捷按钮 |
| `TNT_screen_color` | Global | 屏幕颜色 |
| `TNT_protect_eyes_enable` | Global | 护眼 |
| `revone_dp_lane_num` | Global | DP lane 数 |
| `tablet_dpi_settings` | Global | DPI |
| `ocean_2k_resolution_display_switch` | Global | 分辨率开关 |
| **`pc_mode_enable`** | **Secure** | **PC 模式启用** |
| `font_scale_pcdisplay` | System(float) | 字体缩放 |
| `revone_screen_brightness` | Global | 亮度 |
| `tablet_title_bar_mode` / `tablet_title_bar_location` | Global | 标题栏模式/位置 |
| `tnt_display_connected` | Global | TNT 屏已连接 |

### 7.3 对外 API(节选)

**窗口几何/状态**:`getDefaultWindowBounds` L2612、`smtGetDefaultTaskBounds` L3111、`getAppFrame` L3138、`getTouchMode` L3045、`getCaptionBarHeight(pkg)` L3053、`getDefaultWindowMode(token)` L4264、`getWindowPackageByPoint` L2649、`isMirrored` L4278、`isTntDisplay` L3037

**窗口操作**:`smtRestoreTask(taskId,x,y)` L2084、`smtResizeTask(taskId,bounds)` L2088、`resizeTaskToTargetWindowMode` L2095、`smtRotateTask` L2102、`smtPinTask` L2109、`smtCloseTask` L2134、`smtMinimizeTask` L2155、`smtMinimizeAllTasks` L2176、`smtFullscreenWindow` L2183、`smtMaximizeWindow` L2203、`smtMaximizeOrNot` L2241、`smtCloseAllTasks` L2248、`toggleHome` L2127

**桌面/显示**:`smtSetDesktopMode` L3261、`setPackageDensity` L3257、`setTntScreenBrightness` L3611、`enterPCModeInSwitchDisplay` L4028、`preExitPcMode` L3962、`smtGetSidebarWidth` L2586

> **权限**:几乎每个 API 首行 `checkCallingPermission(PERMISSION_PC_MANAGER_API)` —— 第三方接入必须持有该权限(protectionLevel = **signature**)。

---

## 8. 虚拟显示与扩展屏

`[DISP]TntDisplayManagerServiceImpl.java`

### 8.1 虚拟显示创建(L487–521)

```
addVirtualDisplayLocked(device):
  if !isValidExtDisplayType(...) 或 name == "smt.tnt.virtual.display": return false  // 防自映射
  mTntVirtualDisplayId = createVirtualDisplayInternal(
        mVirtualCallback, null, -1,
        "smt.tnt.virtual.display", "smt.tnt.virtual.display",
        info.width, info.height, info.densityDpi, null,
        65801, "smt.tnt.virtual.display")                       // L517
```

**flags = 65801 = 0x10109** = `0x10000 | 0x100 | 0x8 | 0x1`
其中 **`0x10000 = FLAG_MIRROR_PC`**(实证:`services.jar → DisplayDeviceInfo` 中 `FLAG_MIRROR_PC = 0x10000`)

**displayId 分配**(L580–586):仅当设备名 == `smt.tnt.virtual.display` 时从 `mNextTntVirtualDisplayId`(初值 **100000**,L52)自增 ⇒ **TNT 虚拟屏 id 恒 ≥ 100000**

**镜像前提** `canMirrorTntVirtualDisplayLocked`(L559–578):排除 displayId 0、排除 ≥100000、排除 keepAlive,且
`isValidExtDisplayType(...) || (info.flags & 0x10000) != 0` —— **物理屏必须带 `FLAG_MIRROR_PC` 才允许被镜像/接管**

### 8.2 `TntExtendDisplayManager`

- `rebuildDisplayList` L142–160:只接受 `displayId >= 100000`(L152)
- `preEnterPCMode` L226–248:按 `w*h` 分档 `≥6144000→2(4K)、≥2592000→1(2K)、≥2073600→0(1080P)、否则 -1`
- `postEnterPCMode` L250–279:广播 **`com.smartisanos.pcmode.ENTER_PCMODE`**;`exitPCMode` L281–296 广播 `EXIT_PCMODE`
- DPI:`updateResolutionDpiMap` L328、`verifyDpi` L335(不匹配则写 sysfs 强制重启)、`getPreferredDpi` L359

---

## 8.5 ★★ `WindowConfigurationSmtEx` —— Smartisan 的窗口配置扩展

> 2026-09-10 反编译自 `host-framework/framework.jar`(classes.dex)。
> **这是 TNT 多屏 + per-window 缩放/密度的真实数据载体**,交接包未记录。

### 8.5.1 承载关系

```
android.app.WindowConfiguration
    └── private WindowConfigurationSmtEx mSmt;    // L70
            └── 参与 writeToParcel / readFromParcel   // 跨进程传递
```

`WindowConfiguration.toString()` 的输出片段:
```java
sb.append(" smtConfig=");  sb.append(this.mSmt);
sb.append(" mScreenMode="); sb.append(WindowConfigurationSmtEx.screenModeToString(this.mSmt.mScreenMode));
sb.append(" mDisplayId=");  sb.append(this.mSmt.mDisplayId);
```

> **注意**:`smtConfig` **不是** `android.content.res.Configuration` 的字段 ——
> 它属于 **`android.app.WindowConfiguration`**(即 dumpsys 里 `winConfig={...}` 的内容)。

### 8.5.2 ★ 显示名 ↔ 实际字段(由 `toString()` 实证)

| dumpsys 显示 | **实际字段** | 含义 |
|---|---|---|
| `mAsvId` | **`mActivityStackViewId`** | **Activity Stack View ID**(初始 -1) |
| `mTypeExt` | **`mFlag`**(十六进制输出) | **标志位** |
| `mRotationExt` | **`mLaunchRotation`** | **启动时**的旋转(初始 -1) |
| `mDensityDpi` | **`mLaunchDensityDpi`** | **启动时**的密度 DPI |
| `mBoundsExt` | **`mLaunchBounds`** | **启动时**的 bounds |
| `mAppBoundsExt` | **`mLaunchAppBounds`** | **启动时**的 app bounds |
| `mParentBounds` | `mParentBounds` | 父容器 bounds |
| `mZoomType` | `mZoomType` | 缩放类型(初始 -1) |
| `mZoomBounds` | `mZoomBounds` | 缩放区域 |

> ⚠️ **勘误**:早前按字段名推测 `mBoundsExt` = 「扩展屏 bounds」、`mAsvId` = 「App Scale Variant ID」,
> **均错误**。实证:`mBoundsExt` 实为 **`mLaunchBounds`(启动时 bounds)**,
> `mAsvId` 实为 **`mActivityStackViewId`(Activity Stack View ID)**。

### 8.5.3 常量语义

**ScreenMode**
| 值 | 常量 | `screenModeToString` |
|---|---|---|
| 0 | `SCREEN_MODE_UNDEFINED` | `"undefined"` |
| 1 | `SCREEN_MODE_DEFAULT` | `"default"` |
| **2** | **`SCREEN_MODE_PC`** | **`"pc"`** ← TNT |

**DisplayId**
| 值 | 常量 |
|---|---|
| 0 | `DISPLAY_ID_UNDEFINED` |
| 1 | `DISPLAY_ID_DEFAULT` |
| **2** | **`DISPLAY_ID_PC`** |

**Flag 位(`mFlag`)**
| 值 | 常量 | 含义 |
|---|---|---|
| 1 | `FLAG_FREE_FORM` | **自由窗口** |
| 2 | `FLAG_FOCUSABLE` | 可获焦点 |
| 4 | **`FLAG_ENABLE_IME`** | **启用输入法** |
| 8 | `FLAG_PAUSING_BACK` | — |
| 16 | `FLAG_OVERLAP_KEYGUARD` | 覆盖锁屏 |
| 65536 | `FLAG_NO_STATUS_BAR` | **无状态栏** |
| 131072 | `FLAG_NO_NAVIGATION_BAR` | **无导航栏** |
| 262144 | **`FLAG_NOT_TOUCHABLE`** | **不可触摸** |
| 524288 | `FLAG_DISABLE_PREVIEW` | 禁用预览 |
| 1048576 | `FLAG_FIXED_ROTATION` | 固定旋转 |
| 2097152 | `FLAG_IN_ZOOM_SCREEN_MODE` | 缩放屏模式 |

**变更位掩码**(`updateFrom` 返回的 `changed`,WMS 据此判断配置变化)
| 值 | 常量 |
|---|---|
| 65536 | `WINDOW_ASV_ID_FLAG` |
| 131072 | `WINDOW_CONFIG_FLAG` |
| 262144 | `WINDOW_CONFIG_ROTATION` |
| 524288 | `WINDOW_CONFIG_DENSITY` |
| 1048576 | `WINDOW_CONFIG_BOUNDS` |
| 2097152 | `WINDOW_CONFIG_APP_BOUNDS` |
| 4194304 | `WINDOW_CONFIG_PARENT_BOUNDS` |
| 8388608 | `WINDOW_CONFIG_ZOOM_DISPLAY` |

**其它**
- **`WINDOWING_MODE_SM_FREEFORM_SIDE_BAR = 11`** —— Smartisan 扩展的「侧边栏自由窗口」模式
- `INVALID_STACK_VIEW_ID = -1`、`ROTATION_UNDEFINED = -1`、`DENSITY_DPI_UNDEFINED = 0`

### 8.5.4 方法

| 方法 | 作用 |
|---|---|
| `setToDefaults()` | 重置全部字段 |
| `setTo(other)` | 深拷贝 |
| `updateFrom(delta)` | **增量合并**,返回变更位掩码 |
| `diff(other, compareUndefined)` | 比较差异 |
| `compareTo(that)` | 按字段顺序比较(用于排序) |
| `screenModeToString(mode)` | `0→undefined / 1→default / 2→pc` |

> **对复刻的意义**:小米端**无法照搬**(A16 不允许改 `Configuration` / `WindowConfiguration` 结构),
> 只能以公开 API 组合近似。但这份字段表说明了 TNT 需要表达的状态维度:
> **per-window 密度、启动 bounds、父 bounds、缩放区、屏幕模式(PC/default)、displayId 语义**
> —— 这些在设计自研 WM 的数据模型时**一个都不能少**。

---

## 8.6 ★★ 跨屏镜像输入(`CrossDisplayController`)

> 2026-09-10 反编译自 `services-tnt/.../wm/CrossDisplayController.java`(237 行)。
> 设置键:**`cross_display_enable`**(设备实测为 `null`,未设置)。

### 8.6.1 输入拦截

```java
public void interceptInputWindowHandle(WindowState win, InputWindowHandle inputWindowHandle) {
    if ((!featureEnable() && !featureReleased()) || (mirrorMatrix = getMirrorMatrix(win)) == null) return;
    float[] result = {frameLeft, frameTop, frameRight, frameBottom};
    mirrorMatrix.mapPoints(result, new float[]{frameLeft, frameTop, frameRight, frameBottom});
    inputWindowHandle.frameLeft = (int) result[0]; /* …top/right/bottom 同理… */
    inputWindowHandle.isMirrored = true;
}
```

**关键**:**`featureReleased()` 恒返回 `true`** ⇒ 拦截条件**只取决于 `getMirrorMatrix(win) == null`**,
即**窗口是否在 `mMirrorMap` 中**(被镜像)。

### 8.6.2 API 一览

| 方法 | 作用 |
|---|---|
| `reparentTaskStackSurfaceLocked(atm, taskId, mirrorClient, rmtCb, outSurfaceControl)` | **重设 task stack 的父 Surface**(跨屏搬运) |
| `releaseTaskStackMirrorLocked(mirrorClient)` | 释放镜像 |
| `detachTaskMirror(taskId)` | 解除某 task 的镜像 |
| `updateMirrorMatrix(mirrorClient, matrixValues)` | 更新镜像矩阵 |
| **`interceptInputWindowHandle(win, handle)`** | **变换输入窗口坐标并标记 `isMirrored`** |
| `onTaskBoundChange` / `onMoveTaskToFront` / `onTntStackVisibleChange` | 生命周期回调 |
| `onActivityRequestedOrientationChanged` | **方向变化时自动解除镜像** |

配套类:`smartisanos/crossdisplay/CrossMirrorHelper`、`TaskMirrorHolder`

> **意义**:这是 TNT 把 display 100000 的窗口「投」到物理屏、并让输入正确落点的机制,
> 与 `FLAG_MIRROR_PC` 配套。
> **也是「`input` 打不中窗口」的候选解释** —— 若窗口在 `mMirrorMap` 中,
> 其 `InputWindowHandle.frame` 会被矩阵变换,注入坐标需相应换算。

### 8.6.3 更正与待查

> ⚠️ **勘误(2026-09-10 任务 C)**:本节早前写「与 `FLAG_MIRROR_PC` 配套」**不准确**。
> 二者是**两套独立机制**:`FLAG_MIRROR_PC` 是**虚拟显示**的标志位(整屏),
> `CrossDisplayController` 是**单个 task 的 Surface 搬运**(见 §8.8)。

> ⚠️ **补充**:守卫是 `!featureReleased() && !featureEnable()`,而 `featureReleased()` **硬编码 `true`**,
> 故该表达式**恒为 `false`** ⇒ **永不提前返回**。即
> **跨屏镜像功能不受设置键 `cross_display_enable` 控制,恒定可用**。
> (`interceptInputWindowHandle` 同一模式,见 §8.6.1)

**已由 §8.7–§8.9 解答:**

- ~~`mActivityStackViewId`(ASV)驱动什么~~ → **见 §8.7**
- ~~`mZoomType` 取值枚举~~ → **见 §8.7.5**
- ~~各 Flag 位在何处被设置~~ → **见 §8.7.4**(由客户端 `setLaunchXxx()` 下发)

**仍未实测:**

- 当前测试窗口是否真在 `mMirrorMap` 中 —— 该特性**仅在特定交互触发**
  (`TaskCrossMirrorContainerActivity` 启动时),`logcat` 中 `ASV_TEST` / `CrossDisplays` 标签**零痕迹**,
  说明本次 TNT 会话**从未触发**过这两个机制(与「`input` 打不中窗口」**无因果关系**,
  见 §8.8.7 的否定性结论)

---

## 8.7 ★★★ ActivityStackView(ASV)—— TNT 的「任务内嵌」机制

> **一句话**:ASV = **把一个 App 的整个 ActivityStack,嵌进另一个 App 的某个 View 里**。
> 宿主 View 提供一条 SurfaceControl **leash**,系统把被启动 stack 的 Surface **reparent** 进去。
>
> 证据:`refs/asv/out_server/`(服务端 1866 行)、`refs/asv/out_client/`(客户端)、
> `refs/asv/out_mirror/`(跨屏,见 §8.8)、`refs/asv/consumers/`(消费者)
> 全部由 `jadx --single-class` 反编译自 `services.jar` / `framework.jar` / `smartisanos.jar`

### 8.7.1 双层结构

| 层 | 类 | 位置 |
|---|---|---|
| **服务端** | `com.android.server.wm.ActivityStackViewController`(+ 内部类 `StackViewClient`、`H`) | `services.jar` classes2.dex |
| **桥接** | `android.view.ActivityStackViewUtils`(静态 AIDL 门面) | `framework.jar` classes3.dex |
| **客户端** | `smartisanos.view.ActivityStackView extends FrameLayout` | `framework.jar` classes3.dex |
| **客户端** | `smartisanos.view.ActivityStackViewAdapter` / `ActivityStackViewTarget` | 同上 |

> ★ 注意:**`smartisanos.view` / `android.view.ActivityStackViewUtils` 位于 boot classpath(`framework.jar`)**,
> 因此**任意 App 都可直接引用,无需自带代码**。

### 8.7.2 服务端 `ActivityStackViewController` 关键成员

| 成员 | 作用 |
|---|---|
| `NEXT_STACK_VIEW_ID` / `getNextActivityStackViewId()` | **全局自增计数器**(`++` 后返回)→ **ASV id 由服务端分配** |
| `mViewClients: HashMap<Integer, StackViewClient>` | **ASV id → 宿主**注册表(ASV 的运行时真相) |
| `registerStateChangeListener(asvId, outLeash, IWindow, IRemoteCallback)` | **宿主注册入口**:用自己的 IWindow 作 `mBindingWindow`,拿回 leash |
| `applyActivityStackViewConfiguration(options, launchStack, newStack)` | **启动 Activity 时**接管:读 `ActivityOptions` 的 ASV id → `client.init()` |
| `startTaskInActivityStackView(taskId, bOptions)` | **把一个已存在的 task** 搬进 StackView |
| `clearActivityStackView(asvId, moveToBack)` | 解绑 |
| `onTaskBeforeResizeByTnt(taskRecord, bound)` | ★ task **在 StackView 中**时**拒绝改 bounds**,强制回 `mLaunchBounds` |
| `getParentTaskTnt / getParentTaskIdTnt(taskId)` | ★ 返回**宿主窗口所在 task**;`-1` = 不在任何 StackView 中 |
| `getParentBoundsTnt(win)` / `getParentWindowTnt(win)` | 宿主 task 的 bounds / 宿主的 WindowState |
| `isPackageInActivityStackView(pkg)` | 该包是否正作为被嵌入方 |
| `computeImeTarget` / `beginLayoutLw` / `offsetInputMethodWindowLw` | ★ StackView 内窗口**参与 IME 与布局的独立计算** |
| `hideStatusBarTemporarily` / `hideInputMethodTemporarily` | 用 **alpha=0** 临时隐藏(而非真正移除) |

**`StackViewClient` 构造**(L1044–1071)是机制的核心:

```java
SurfaceControl parent = bindingWin.getSurfaceControl();
this.mStackViewLeash = new SurfaceControl.Builder(new SurfaceSession())
        .setContainerLayer().setParent(parent)
        .setName("ActivityStackView_" + activityStackViewId).build();
if (bindingWin.isPcMode()) { leash.show(); leash.setLayer(1); leash.setPosition(surfaceInsets.left, surfaceInsets.top); }
((WindowStateSmtEx) mBindingWindow.getSmtEx()).bindActivityStackView(activityStackViewId);
mRemoteCallback.asBinder().linkToDeath(this, 0);      // 宿主进程死亡 → 自动清理
```

另有 `getPopUpWindowLeash()` —— **被嵌 stack 的弹窗被单独抓到一个 layer=1000 的容器层里**。

### 8.7.3 客户端 `ActivityStackView` API

`ActivityStackView extends FrameLayout`,提供:

`startActivity(Intent)` / `startActivityAsUser(...)` / `startTask(taskId)` / `clearContent([moveToBack])` /
`updateTaskSnapshot()` / `registerStatusListener(StatusListener)` / `setNeedSync(bool)` /
`setCornerRadius(float)` / `setContentRotation(int)` / `setContentMatrix(Matrix)` / `setViewBound(Rect)`

**4 个状态回调**(`StatusListener`):`onActivityStackLoaded(stackId)` /
`onActivityStackExited()` / `onActivityStackReopenedByOthers(pkg)` / `onActivityStackFirstWindowShown()`

### 8.7.4 ★★★ 12 个 launch 参数 ↔ `WindowConfigurationSmtEx`

**这就闭合了 §8.5.3 的遗留问题「各 Flag 位在何处被设置」** ——
**全部由客户端 setter 设置**,经 `ActivityOptions.getActivityOptionsSmt()` 下发:

| 客户端 setter | 下发到 `ActivityOptionsSmt` | 落到 `WindowConfigurationSmtEx` |
|---|---|---|
| `setLaunchBound(Rect)` | `setLaunchBounds` | `mLaunchBounds` |
| `setLaunchAppBound(Rect)` | `setLaunchAppBounds` | `mLaunchAppBounds` |
| `setViewBound(Rect)` | `setViewBounds` | (客户端几何,非 Flag) |
| `setLaunchRotation(int)` | `setLaunchRotation` | `mLaunchRotation` |
| `setLaunchDensityDpi(int)` | `setLaunchDensityDpi` | `mLaunchDensityDpi` |
| `setLaunchFocusable(bool)` | `setFocusable` | `FLAG_FOCUSABLE`(2) |
| `setLaunchPausingBack(bool)` | `setPausingBack` | `FLAG_PAUSING_BACK`(8) |
| `setLaunchOverlapKeyguard(bool)` | `setOverlapKeyguard` | `FLAG_OVERLAP_KEYGUARD`(16) |
| `setLaunchWithoutStatusBar(bool)` | `setWithoutStatusBar` | `FLAG_NO_STATUS_BAR`(65536) |
| `setLaunchWithoutNavigationBar(bool)` | `setWithoutNavigationBar` | `FLAG_NO_NAVIGATION_BAR`(131072) |
| `setLaunchNotTouchable(bool)` | `setNotTouchable` | `FLAG_NOT_TOUCHABLE`(262144) |
| `setLaunchDisablePreview(bool)` | `setDisablePreview` | `FLAG_DISABLE_PREVIEW`(524288) |
| (`prepareActivityOptions` 内固定) | `setIsFreeForm(true)` | `FLAG_FREE_FORM`(1) |

> **注意**:`ActivityStackViewAdapter` 中各 Flag 的**默认值**:
> `WithoutStatusBar=true`、`WithoutNavigationBar=true`、`NotTouchable=false`、
> `LaunchRotation=-1`、`DensityDpi=0`、`CornerRadius=0`。
> ⇒ **嵌入窗口默认无状态栏/无导航栏**,与 TNT 桌面窗口一致。

**服务端 `WindowConfigurationSmtEx.updateFrom()` 的写入点**(`refs/smtconfig/out3`):
`mActivityStackViewId` 仅在 `delta.mActivityStackViewId != -1` 时才覆盖;
`FLAG_CLEAR(268435456)` 会把它重置为 `-1`。

### 8.7.5 `mZoomType` 已解答

`StackViewClient` 持有 **`mZoomType`(初值 `-1`)** 与 **`mZoomMatrix`** ——
即 **`WindowConfigurationSmtEx.mZoomType` 是「StackView 宿主的缩放类型」**,
尺寸/位置由**客户端算好矩阵**后经 `updateMirrorMatrix` 类接口回传(§8.8.5),服务端只做消费。

### 8.7.6 ★★ 消费者 = **`Sidebar`(侧边栏)**

随包反编译的 3 个 TNT app **均不引用** ASV。设备侧全量扫描(`/system/app` + `/system/priv-app`)
命中 **7 个** APK,其中决定性的是 **`/system/priv-app/Sidebar/Sidebar.apk`**:

```java
// refs/asv/consumers/Sidebar-java/.../sidearea/widget/TaskView.java
public class TaskView extends ActivityStackView
        implements ActivityStackView.StatusListener, View.OnDragListener {
    // R.id.view1 / view2 / view3  →  mIndex = 0 / 1 / 2
    setLaunchBound(Rect(0,0,Constants.WINDOW_WIDTH, Constants.WINDOW_HEIGHT));
    setLaunchNotTouchable(true);  setSyncLoad(false);  setLaunchWithoutStatusBar(false);
    registerStatusListener(this);
    // bindTaskWithRotation(taskId, rotation) → setContentRotation + setLaunchBound + startTask(taskId)
    // unbindTask(moveToBack)                → clearContent(moveToBack)
    // updateTaskSnapshotIfNeed()            → updateTaskSnapshot()
}
```

**⇒ 锤子侧边栏最多同时挂着 3 个「活」应用窗口**(`view1/2/3`),可拖动、侧滑删除、拖拽换位;
`SPECIAL_PACKAGE_NAME = ["com.smartisanos.securitycenter"]` 为**被他人重开时静默忽略**的白名单。

**跨模式渲染差异**(`ActivityStackViewAdapter.updateSizeAndPosition` / `checkLaunchBound`):

| 模式 | 行为 |
|---|---|
| `SmtPCUtilsInner.isPcMode(ctx)` = **true**(TNT) | **1:1 直投**;bounds 变化走 `updateLaunchBound(asvId, rect)` |
| **false**(手机) | **等比缩放 + 圆角** —— `setMatrix` + `setWindowCrop` + `setCornerRadius` ⇒ **活体缩略图** |

其余命中 APK:`SettingsSmartisan`、`ContactsSmartisan`、`SecurityCenter`、`KeyguardSmartisan`、
`SmartisanUpdater`、`FilePreviewerSmartisan`(未逐个反编译,推断为**应用内嵌套子页面**用途 —— ⚠️ **推断**)

> ★ **Sidebar 同时承载 TNT 侧边栏**:其 `AndroidManifest` 含 `com.smartisanos.sidebar.revone.
> {ClipBordActivity, sticky.ClipboardStickyActivity, sticky.RecentFileStickyActivity,
> sticky.RecentPhotoStickyActivity, sticky.QuickSnippetStickActivity}` 与 `RevOneSettingActivity`;
> 快捷协议 URI `vnd.desktop.sidebar/{clipboard,recent_file,recent_photo,quick_snippet}`。
> 服务端对应 `services-tnt/.../pc/SmtSideBarManagerService.java`(657 行)。

### 8.7.7 与 AOSP 的对应关系(小米端复刻意义)

| TNT | AOSP 近似 | 差距 |
|---|---|---|
| `ActivityStackView` | **`android.app.ActivityView`**(API 24+) | AOSP 版**要求目标 Activity `android:allowEmbedded="true"`**,跨 uid 还需 `EMBED_ACTIVITY` 权限;TNT 版**无此限制**(系统签名直通) |
| `SurfaceControl` leash + `reparent` | 同 API 存在,但**第三方 App 无权拿到别的 task 的 SurfaceControl** | A16 上必须系统权限(Shizuku 不够) |

> **结论(对 §04 小米方案)**:ASV 这类「任意应用互相内嵌」的能力,
> **在无 root 的 Android 16 上无法复刻**;可近似的是
> ① 自家应用之间用 `ActivityView`+`allowEmbedded`;
> ② 系统级用 `Shell`/`Shizuku` 调 `am` 只能做窗口摆放,**做不到 Surface 级内嵌**。

---

## 8.8 ★★★ 跨屏镜像搬运 —— `CrossDisplayController` 完整机制

> 证据:`refs/asv/out_mirror/`(8 个类,共 619 行,反编译自 `smartisanos.jar`)
> + 服务端 `CrossDisplayController.java`(237 行)

### 8.8.1 定性 —— **「把 TNT 上的 task 搬到手机屏显示」**

```java
// smartisanos/crossdisplay/CrossMirrorHelper.java
private static final boolean FEATURE_ONLY_MIRROR_TO_PHONE = true;   // ★ 只允许镜像到手机
if (showMirrorInDisplay != 0) { loge("...FEATURE_ONLY_MIRROR_TO_PHONE..."); return; }
```

⇒ **方向固定:TNT 屏(100000/外接) → 手机屏(display 0)**。
**不是**把手机内容投到 TNT,而是反过来 —— **把 TNT 上跑着的应用「拉回」手机屏**,且是**活的**,不是截图。

### 8.8.2 完整链路

1. 调用方 `CrossMirrorHelper.reqMirrorMustCross(ctx, taskId | componentName, 0)`
2. 构造 `Intent(ACTION_CROSS_MIRROR_TASK = "smartisanos.crossdisplay.ACTION_CROSS_MIRROR_TASK")`,
   带 extras `SHOW_MIRROR_IN_DISPLAY` / `TARGET_TASK_ID` / `TARGET_COMPONENT`,
   flags `805306368`(=`NEW_TASK|CLEAR_TASK`),`options.setLaunchDisplayId(0)`
3. 启动 **`TaskCrossMirrorContainerActivity`**(手机屏上一个透明容器 Activity)
   —— 布局 `33947800`,内含 **`TaskMirrorView`**(id `34079146`)
4. `onCreate` 校验:`CrossMirrorHelper.isTaskCrossMirrorable(displayId, taskId)`
   → `ActivityManager.getSmtEx().isTaskVisible(excludeDisplayId, taskId)`;
   `componentName` 形态则走 `resolveVisibleTask(excludeDisplayId, cmp)`
5. **`TaskMirrorView.setFrame()`** → `getViewBoundRelativeGlobal(this, rect)`
   (`transformFromViewToWindowSpace` + `windowAttributes.surfaceInsets` 偏移)
   → `updateMirrorGlobalBound(rect)` ⇒ **镜像目标矩形(全局坐标)**
6. `TaskMirrorHolder.start()`:
   - `CrossMirrorHelper.getTaskGlobalBound(taskId)` = `ATMS.getTaskBounds(taskId)`
   - `computeTaskContentCrop(bound)` —— ★ **裁掉标题栏**(见 §8.8.4)
   - `ActivityManager.getSmtEx().reparentTaskStackSurface(taskId,
     getViewRootImpl().getSmtEx().getWindowClient(), listener, mMirrorContent)`
7. **服务端 `reparentTaskStackSurfaceLocked`**(§8.6):
   ```java
   SurfaceControl surfOfMirror = new SurfaceControl.Builder(new SurfaceSession())
       .setContainerLayer().setParent(mirrorClientWin.getSurfaceControl())
       .setName("CrossMirror_" + taskId).build();
   outSurfaceControl.copyFrom(surfOfMirror);
   taskTobeMirrored.getStack().getTaskStack().getSurfaceControl().reparent(surfOfMirror);  // ★ 搬运
   taskTobeMirrored.getStack().getTaskStack().getSmtExt().onMirroredToDisplay(surfOfMirror, mirrorClientWin.getDisplayId());
   ```
8. 回调 `MirroredTaskStateListener`:成功 / `MIRROR_FAILED(reason)` / `TASK_MIRROR_DETACH`
9. **每帧** `onFrameDraw` → `showMirror()`(§8.8.3),并 `onNewMirrorMatrix()` 把矩阵回传服务端

### 8.8.3 每帧合成(`TaskMirrorHolder.showMirror`,L159–184)

```java
transaction.deferTransactionUntilSurface(mMirrorContent, getViewRootImpl().mSurface, frame);  // vsync 同步
mMirrorTmp.setRectToRect(new RectF(mTaskContentBound), new RectF(mMirrorHolderGlobalBound), ScaleToFit.FILL);
transaction.setWindowCrop(mMirrorContent, mTaskContentBound);
transaction.setMatrix(mMirrorContent, mMirrorTmp, mTmpFloat9);
transaction.setLayer(mMirrorContent, (int)(getZ() + 1.0f));
transaction.setAlpha(mMirrorContent, getAlpha());
transaction.setCornerRadius(mMirrorContent, 10.0f);      // ★ 统一 10px 圆角
transaction.setEarlyWakeup();
```

**矩阵是「客户端算、服务端用」**:`onNewMirrorMatrix()` → `updateMirrorMatrix(windowClient, values)`,
服务端存进 `OneMirror.matrix`,**仅用于 `interceptInputWindowHandle` 做输入坐标反变换**。
⇒ **渲染用一套矩阵(客户端),输入用同一套矩阵(服务端)** —— 这就是「镜像后触摸仍然准确」的原因。

### 8.8.4 ★ `computeTaskContentCrop` —— 顺便解开「触摸/鼠标模式」

```java
int barH = SmtPCUtils.getCaptionBarHeight();
boolean barAtTop = SmtPCUtils.getCaptionTouchMode() != 1;
Rect result = new Rect(taskBound);
if (barAtTop) result.top += barH; else result.bottom -= barH;
```

⇒ **镜像内容一定裁掉 TNT 标题栏**(标题栏由 TNT 侧保留)。完整判定链见 §8.9。

### 8.8.5 自动解除镜像(6 条)

| 触发 | 位置 |
|---|---|
| task 被移除 | `onRemoveTask` |
| **task bounds 变化** | `onTaskBoundChange` |
| task 被移到前台 | `onMoveTaskToFront` |
| TNT stack 不可见 | `onTntStackVisibleChange(visible=false)` |
| **请求方向 ≠ 竖屏**(`requestedOrientation != 1`) | `onActivityRequestedOrientationChanged` |
| 宿主进程死亡 / 容器 Activity `onStop` | `OneMirror.binderDied` / `TaskMirrorHolder.destroy()` |

**释放动作**:把 task stack 的 Surface `reparent` 回
`displayContentSmtExt.getTaskStackContainers().getSurfaceControl()` + `onDetachMirror()`,
然后 `surfOfMirror.reparent(null); release()`。

### 8.8.6 AIDL 表面(`ActivityManager.getSmtEx()`,非 AOSP)

`reparentTaskStackSurface(taskId, IWindow, IRemoteCallback, outSurfaceControl)` /
`releaseTaskStackMirror(IWindow)` / `updateMirrorMatrix(IWindow, float[9])` /
`isTaskVisible(displayId, taskId)` / `resolveVisibleTask(displayId, ComponentName)`

### 8.8.7 对「`input` 打不中窗口」的**否定性结论**

`logcat` 全量检索 **`CrossDisplays` / `ASV_TEST` 标签零命中**,
且镜像/内嵌**只在特定交互启动**容器的瞬间才会发生。
⇒ 测试期间窗口**不在 `mMirrorMap` 中**,`interceptInputWindowHandle` **未被触发**。
**§03 v1.3 的「跨屏残留 / 镜像矩阵致 input 失效」推断应被否定**,
真因回到 `mFullConfiguration.mDisplayId=0` 与 display 100000 不一致(或 `input` 注入路径本身)。

---

## 8.9 ★★ 触摸 / 鼠标模式的判定链(解开长期待办)

> 结论来源:代码三角验证 + 截图实测;`settings` 实测 `tablet_title_bar_mode = null`(未设)、
> `tablet_title_bar_location = 0`

### 8.9.1 取值

```java
// TntManagerService.getTouchMode()  →  ISmtPCManager.getTouchMode()  →  SmtPCUtils.getCaptionTouchMode()
public int getTouchMode() { synchronized (mLock) { return this.mCurrentOperatingMode; } }
```

| 值 | 含义 | `SmtPCWindowManager.mCaptionTouchMode` |
|---|---|---|
| **1** | **触摸模式** | `true` |
| **0** | **鼠标模式** | `false` |

### 8.9.2 决策优先级(`TntManagerService.updateCaptionOperatingModeLocked`)

```
mDecorCaptionOperatingMode          = Settings.Global "tablet_title_bar_location"  (默认 0)
mCaptionModeSwitchMode              = Settings.Global "tablet_title_bar_mode"      (默认 0)

mode = (isTntDisplay() && mCaptionModeSwitchMode == 0) ? mDecorCaptionOperatingModeBySensor   // ★ 自动
                                                       : mDecorCaptionOperatingMode;          //   手动
if (mDecorCaptionOperatingModeTemporary != -1) mode = mDecorCaptionOperatingModeTemporary;    // 最高
mCurrentOperatingMode = mode;
if (mode == 1) setCaptionTouchMode(displayIdInPcMode, true);
```

⇒ **`tablet_title_bar_mode`**:
`0`(默认,含未设) = **由加速度传感器自动判定**;`≠0` = **固定,读 `tablet_title_bar_location`**。

### 8.9.3 ★ 加速度传感器算法(带滞回)

```java
// MonitorThread, 50ms 轮询
float[] d = getExtendScreenAccelSensor();                       // = nativeGetExtendScreenAccelSensor()
double angle = Math.toDegrees(Math.atan(d[1] / Math.sqrt(d[0]*d[0] + d[2]*d[2])));   // 屏幕倾角
```

| 当前 `BySensor` | 条件 | 新值 |
|---|---|---|
| `-1`(未初始化) | `angle < SWITCH_ANGLE`(**70.0°**) | **1**(触摸) |
| 否则 | — | 0(鼠标) |
| `0`(鼠标) | `angle < 65.0°` | **1**(触摸) |
| `1`(触摸) | `angle > 75.0°` | **0**(鼠标) |

⇒ **滞回窗口 `[65°, 75°]`,切换阈值 `70°`**(`SWITCH_ANGLE = 70.0`,`BUFFER_ANGLE = 5.0`)。
**直立(笔记本姿态,大倾角)→ 鼠标模式;放平(平板姿态,小倾角)→ 触摸模式。**

> ★★ **传感器来自「外接屏」** —— 方法名 `getExtendScreenAccelSensor`,
> 经 **JNI `nativeAccelSensorInit/Close/GetExtendScreenAccelSensor`** 直读,
> 即 **TNT GO 内置的加速度计由手机通过原生层读取** —— 这是 TNT GO 硬件适配的关键一环。
> ⚠️ **推断**:传输通道可能是 DP AUX / USB HID,未验证。
>
> ### ★★★ 勘误(2026-09-11,用户指正):**本机型用不到加速度计**
>
> **用户告知(硬件事实)**:**加速度计是给 R2 的「无线连接」用的;
> 坚果 Pro 3 只能有线连接,因此用不到加速度计。**
>
> **⇒ 本节这条链路在「有线 + Pro 3」场景下根本不激活**,原先标为「硬件适配的关键一环」言过其实。
>
> **代码三条印证**:
> 1. **有显式放弃路径** —— `tryOpenAccelSensor()`:
>    ```java
>    int i = this.mAccelSensorTry + 1;  this.mAccelSensorTry = i;
>    if (i >= 10) { return; }            // MAX_TRY_FIND_ACC_SENSOR = 10 ⇒ 永久停手
>    boolean accelSensorFind = nativeAccelSensorInit() != 0 ? false : true;
>    if (!accelSensorFind) { …500ms 后重试… return; }
>    ```
>    **找不到就试 10 次后永久放弃** —— 正是「本机型没有该传感器」的设计。
> 2. **手机自己是有加速度计的**(`dumpsys sensorservice` 实测:`icm4x6xx Accelerometer`,TDK-Invensense)
>    —— 而代码找的是**「外接屏的」** ⇒ 是**另一条通道**,与手机本体无关。
> 3. 本机 `tablet_title_bar_mode = null`(⇒ 默认 `0` = 自动),但传感器找不到时
>    `mDecorCaptionOperatingModeBySensor` **恒为 `-1`**;消费端 `getCaptionTouchMode() != 1`
>    ⇒ **固定落到「鼠标模式」**(标题栏在顶)。**⇒ 有线场景的结果是确定的,不依赖传感器。**
>
> **⇒ 对小米端的推论**:小米 17 Pro Max 同样是**有线**接 TNT GO ⇒
> **「标题栏随姿态自动翻转」不需要复刻**(按固定「鼠标模式 / 标题栏在顶」实现即可)。
>
> ~~### ★★ 第三条候选通道:`+HRM`(2026-09-11 任务 I 新增)~~ —— **该假设已失效,保留供追溯**
>
> ~~`AT+CHECK` 的响应里有一行 `+HRM=0,298,59` —— 三个值,正好是 x/y/z 的形状。~~
> **理由不成立**(加速度计在本机型不激活)。`+HRM` 更可能是**三路霍尔的原始 ADC**
> (取阈值 ~50 时恰好复现 `+HALL=0,1,1`)。详见 [06 §6.2](06-TNTGO-AT命令全集.md)。

相关开关:`updateAccelSensorState()` —— `tablet_title_bar_mode == 0` → `tryOpenAccelSensor()`,否则 `closeAccelSensor()`。

### 8.9.4 标题栏位置(两处独立印证)

| 依据 | 代码 | 结论 |
|---|---|---|
| 镜像裁剪 | `barAtTop = getCaptionTouchMode() != 1` | 鼠标(0)→**顶**;触摸(1)→**底** |
| 窗口夹取 | `if (taskBound.top < maxBounds.top && !mCaptionTouchMode)` | **非触摸(鼠标)**→判 top ⇒ 鼠标**顶** ✅ 一致 |

**高度也分两套**:`getCaptionBarHeight(touchMode)` → `touchMode ? mCaptionBarHeightTouch : mCaptionBarHeightMouse`。

> ### ★★ 实现细节(2026-09-11 任务 F 补):**两套容器,不是同一个容器换位置**
>
> 反编译 `SmtDecorCaptionView.smali` 发现 —— 标题栏有**两个独立的 ViewGroup 成员**:
>
> | 资源 ID | 字段 | 含义 |
> |---|---|---|
> | `0x1020432` | **`mTopMenu`** (ViewGroup) | **顶部菜单容器**(鼠标模式用) |
> | `0x1020439` | **`mBottomMenu`** (ViewGroup) | **底部菜单容器**(触摸模式用) |
>
> （`setPhoneWindow()` L455–456 处 `findViewById` 赋值）
>
> ⇒ **「鼠标标题栏在顶 / 触摸在底」的真实实现是两套容器按模式二选一显示**,
> **不是**同一个容器改坐标。**小米端 overlay 复刻时应准备上下两套 chrome。**
>
> **★ 附带拿到完整的标题栏按钮清单** —— `mShowHideBtns: HashMap<String,Integer>`
> (`initBtnsMap()` L3366)含 **11 个 Smartisan 私有 `com.android.internal.R.id`**
> (`0x102042f`–`0x1020439`),含此前未知的 **`pin`(钉住/置顶)**、
> **`fullScreen`(全屏)**、**`portraitDensityScale`(竖屏密度缩放)**
> —— 完整表见 [03 §2.0](03-UI复刻规格.md)。
>
> **★ 最小化的框架级 API 入口**:`SmtPCUtilsInner.smtMinimizeTask(IBinder token, boolean)`
> (由 `SmtDecorCaptionView.minimizeWindow()` L3750 → `PhoneWindow.getSmtEx().getAppToken()` 调用)。

### 8.9.5 ✅ 与快捷键的关系:**两套独立机制**(已定论,2026-09-10 任务 D)

> **结论:触摸/鼠标模式与快捷键列的选择毫无关系。**
> 早前记录的「冲突」是**伪冲突**,由两个错误叠加而成:

| 早前的错误 | 更正 |
|---|---|
| 把 `custom_key_*` 的 `<A>$<B>` 读作 `<触摸>$<鼠标>` | **实为 `<mac>$<win>`**(Mac / Windows 键盘布局)。铁证:`SmtPcPhoneWindowManager` 的日志语句**直接命名**为 `mac mode key:` / `win mode key:`;选择依据是**逐事件的** `KeyEvent.FLAG_MAC_MODE`(0x800),与系统模式无关 |
| 把「Win+M/D/H 生效」当作触摸模式的证据 | **该实测方法本身无效** —— `input` 注入的按键**到不了**策略层;`sendevent` 被 SELinux 拒绝。见 [计划书 D](plans/D-已知模式重测TNT快捷键.md) §3.5 |

**两套机制的完整对照:**

| | **触摸 / 鼠标模式**(本节 §8.9.1–8.9.4) | **快捷键列 mac / win** |
|---|---|---|
| 取值来源 | `TntManagerService.mCurrentOperatingMode`(全局) | `event.getFlags() & KeyEvent.FLAG_MAC_MODE`(**逐事件**) |
| 决定因素 | **TNT GO 内置加速度计倾角**(阈值 70°、滞回 [65°,75°]) | **原生输入层按输入设备置位**(`libinputreader.so` / `libinputflinger.so` 内的 `macMode`) |
| 影响 | **标题栏位置**(鼠标→顶、触摸→底)、标题栏高度 | **`custom_key_*` 走哪一列**、部分硬编码分支的 `!isMacMode` 守卫 |
| 观测法 | 截图看标题栏位置 | **只能真机手按**(注入无效) |

> ✅ **已结案(2026-09-11,真机实测)**:真实 TNT GO 键盘**不置 `FLAG_MAC_MODE`** ⇒ **走 `win` 列**。
>
> | 证据 | 内容 |
> |---|---|
> | **① 标志位(直接)** | 物理按键日志 `flags = 8` ⇒ `isMacMode = (8 & 2048) != 0` = **`false`** |
> | **② 行为** | `Win+D` ✅ 生效(win 列) / `Win+M` ❌ 无效(win 列 = `NONE`) |
> | **③ `result` 语义** | `-1` = TNT 已命中执行;`0` = 未命中(`handleByCustomShortcuts` 的 `default: return 0`) |
>
> ⚠️ **CC 早前的推断(「`boston_keyboard` 带 `FLAG_MAC_MODE` ⇒ mac 列」)被证伪。**
> 原生层的 `macMode` 概念确实存在,但**不在 TNT GO 键盘这条路径上生效**。
>
> **生效的快捷键表**见 [03 §2.2 实测结案](03-UI复刻规格.md)。
>
> ✅ **附带更正**:`SmtPcShortcutKeyManager` **不是**第二个处理器 ——
> 它是 `SmtPcPhoneWindowManager` 自己的**日志 TAG**(该类 L134;`processForDoubleClick` 在 L1166,同类内)。

---


---

## 9. 第三方集成接口

### 9.1 权限清单

| 权限 | 级别 |
|---|---|
| **`com.android.permission.PC_MANAGER_API`** | **signature** |
| `com.android.desktop.permission.PROVIDER_CALL` | signatureOrSystem |
| `com.android.desktop.recentspsp.ACCESS_CALL_METHOD` | signatureOrSystem |
| `com.android.desktop.systemui.TAKE_SCREEN_SHOT` | normal |
| `com.android.desktop.systemui.permission.SELF` | signature |
| `android.permission.smartisanos.desktop.LAUNCH_SERVICE` / `.call` | signatureOrSystem |

### 9.2 Provider 协议

三个 Provider 都用 **`ContentProvider.call(method, arg, extras)`**,参数走 `extras` Bundle。

**A. `com.smartisanos.desktop.provider.call`**(readPermission `android.permission.smartisanos.desktop.call`)

| method | extras | 行为 |
|---|---|---|
| `METHOD_REGISTER_REMOTE` | `"remote"`(RemoteCallback) | 注册回调 |
| `METHOD_REQUEST_START_ACTIVITY` | `"intent"`、`"requestCode"`、`"USE_START_ACTIVITY"` | startActivity/ForResult |
| `METHOD_ADD_APP_OR_SHORTCUT` | — | 加图标/快捷方式 |
| `METHOD_DO_ZOOM_ANIM_DESKTOP` | — | 桌面缩放动画 |

**B. `com.android.desktop.recentspsp.provider.call`**:`"add_task_to_sidebar"` → `requestAddTaskToSidebar`

**C. `com.android.desktop.systemui`**(authority L200/204;表 `meeting_dock_table`、`bottom_bar_name`)

| method | extras |
|---|---|
| `METHOD_LAUNCHPAD_DRAG` | `action`、`loc`、`items` |
| `METHOD_LAUNCHPAD_REMOVE_ITEM` | `pkg`、`userId`、`name`、`itemType`、`cmp`、`shortcutId` |
| `LISTEN_LOCATION_CHANGED` | `location_listener` |
| `METHOD_DESKTOP_DRAG` | `icon` |
| `METHOD_FILEMANAGER_DRAG` | `isDelete` |
| `METHOD_LAUNCHPAD_DISMISS` / `_SHOW` | — |
| `METHOD_FILEMANAGER_FRONT_TRASH` | `taskID`、`isTrash` |
| `METHOD_DESKTOP_ANIM_END` | `MODE` |
| `METHOD_CLICK_BLANK_IN_MEETING_MODE` | `type` |
| `METHOD_SMDISPLAY_BUTTON_CHANGE_STATE` | — |
| `METHOD_SHOW/HIDE_IDEAPILLS`、`METHOD_SHOW/HIDE_SARA` | — |

### 9.3 截图 AIDL

`IGlobalScreenshot` / `IScreenshotTool` / `IScreenshotToolCallback` / `ISaveImageService` / `ISaveImageServiceCallback`
Service:`SystemUIService`(Self 权限)、`TakeScreenshotService`(TAKE_SCREEN_SHOT,进程 `:screenshot`)

---

## 10. 策略表统计(`revone_window_config.xml`)

**743 个 `<application>` + 88 个 `<special-video>`**(MD5 与设备一致 ✅)

| 字段 | 分布 |
|---|---|
| windowMode | 1→351、0→199、4→174、2→15、缺失→4 |
| resizeMode | 5→263、1→193、0→190、4→90、13→2、缺失→5 |
| **width** | **900→251**、**360→197**、缺失→186、**1095→100**、960→6 |
| (mode,resize) | (1,5)=245、(0,1)=190、(4,0)=174、(1,4)=89、(2,5)=15 |

**代表性配置**:

| 包 | 配置 |
|---|---|
| Chrome | wm=1, 900×694, min 360×694, rm=5 |
| 设置 | wm=0, rm=0, 360×694 |
| 便签 | wm=1, 900×694, min 900×480, rm=5 |
| 浏览器 | wm=1, 1095×694, min 900×360 |
| smartdisplay | wm=1, 960×540, rm=13(1\|4\|8) |
| **微信/抖音/淘宝** | **不在表中** → 走启发式(360dp 或 900dp、可自由缩放) |

---

## 11. 可直接移植的资产

1. **策略表 XML** —— schema 见 §2.2,744 条逐包配置,现成的「应用窗口行为表」
2. **常量表** —— windowState 打包掩码/位移、windowMode 全枚举、resizeMode/captionMode/touchMode、隐藏按钮位、尺寸常量、方向码、`FLAG_MIRROR_PC = 0x10000`
3. **算法伪代码** —— 默认放置三步链、最小尺寸、避让、回弹、边缘吸附方向判定、标题栏对齐(均为纯几何,可 1:1 重写)
4. **持久化 schema** —— `app_window_settings.xml` + `deviceKey = W_H@dpi`
5. **标题栏规格** —— 按钮 id/顺序、27dp 高、40dp 按钮宽、拖拽=startMovingTask
6. **Provider 协议表** —— §9.2 的 method 字符串 + extras key 全表
7. **Settings 键表** —— §7.2

---

## 12. 未确认项

1. **Binder 事务号未提取**(`ITntManager` / `ISmtPCManager`)—— 若走 AIDL 重写则不阻塞
2. `mNextTntVirtualDisplayId` 多次插拔后的 id 复用语义未测
3. 虚拟显示 `0x10000` 位在 `VIRTUAL_DISPLAY_FLAG` 命名空间的定义未找到(与 `DisplayDeviceInfo.FLAG_MIRROR_PC` 同值,语义高置信但常量名待证)
4. **小米 17 Pro Max 平台限制**:Android 15/16 对 `createVirtualDisplay`(尤其带 OWN_CONTENT_ONLY + 自定义位)与 `DisplayDeviceInfo.flags` 的签名要求远严于 Android 9。上表 flags 与 ≥100000 的 displayId 分配是 **system_server 内部行为,第三方 WM 无法照搬**,只能以「外部显示器 + freeform/desktop mode」近似
5. 吸附的 dim layer 具体裁剪公式(L1125–1250)未逐行核对
6. `framework.jar` 全量反编译未完成(jadx 对 29MB 停在 disk cache);结论来自 baksmali 单类反汇编 + aapt2 dump
7. **§8.9.5 触摸/鼠标模式冲突未定论** —— 需在**已知模式**下重测快捷键(最高优先级)
8. **`ActivityStackView` 的其余 6 个消费者**(`SettingsSmartisan` / `ContactsSmartisan` /
   `SecurityCenter` / `KeyguardSmartisan` / `SmartisanUpdater` / `FilePreviewerSmartisan`)
   未逐个反编译 —— 用途为**推断**,待证
9. **跨屏镜像的触发入口未找到** —— `CrossMirrorHelper.reqMirrorMustCross` 的**调用方**不在
   已反编译语料中(`Sidebar` 也未引用);需在设备侧进一步定位
10. **TNT GO 加速度计的传输通道未验证** —— `nativeGetExtendScreenAccelSensor` 的 JNI 实现对
   `libandroid_servers` 或 TNT GO 的 MCU 依赖不明(DP AUX?USB HID?专有协议?)
11. `mLaunchBounds` 的「启动时」确切语义仍待确认(§8.7.4 已确认它由
    `ActivityStackView.setLaunchBound` 下发,但**窗口首次显示 vs 启动动画起始**未区分)
12. `TabletTitleBarMode` 的**枚举全表**未取全(已知 `0` = 自动;`≠0` 的具体档位待查)
