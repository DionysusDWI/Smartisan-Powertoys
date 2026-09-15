# 04 · 可行性推算与方案架构

> 结论：软件方案成立；唯一硬性未知数是「有线 DP 模式下手机能否枚举 TNT GO 的 CDC 串口」。
> 整理时间：2026-09-09

## 1. 需求与技术事实

**需求**：Pro3 有线连接 TNT GO 时，在 TNT 屏上常驻显示电量（Pro3 / TNT GO）。

**事实**：
- 有线模式下 TNT GO 屏幕内容 100% 由手机渲染（DP Alt 直通），其内置安卓系统不参与画面
- 因此「在屏上显示」= **在手机的 secondary display 上渲染窗口**
- TNT GO 电量只能通过 USB 串口读取（见 `03-battery-serial-protocol.md`）

## 2. 可行性矩阵

| 目标 | 数据获取 | 渲染到 TNT 屏 | 综合 |
|---|---|---|---|
| Pro3 电量 | 极简单（系统 API） | 中高 | **高（~80%）** |
| TNT GO 电量 | 中高（串口） | 中高 | **中高（~65%）** |
| 两者同显 | 中高 | 中高 | 中高 |

## 3. 方案架构

### 3.1 数据层

| 数据 | 来源 | 难度 |
|---|---|---|
| Pro3 电量 | `BatteryManager` / `ACTION_BATTERY_CHANGED`（可附带「反向供电中」状态） | 极低 |
| TNT GO 电量 | `UsbManager`（VID `31ce:5101`）+ `usb-serial-for-android` → 发 `at+adb` → 正则 `\+BATCG=\d+,(\d+),` | 中高 |

**数据层备选路径**（若手机枚举不到 CDC）：
1. TNT GO 上装 App 读本机电量 → BLE / 局域网推送到 Pro3（需确认有线时其系统是否运行）
2. PD 协议 / sysfs 节点（Android 不暴露 Source 电池信息，可行性 <20%，不建议）

### 3.2 渲染层（按优先级）

1. **Presentation**：`createDisplayContext(display)` + `Presentation.show()`，零权限 —— 首选
2. **display-context overlay**：`WindowManager.addView(TYPE_APPLICATION_OVERLAY)` + `SYSTEM_ALERT_WINDOW`，可拖动 / 置顶 / 不抢焦点
3. **Activity 启动到 TNT 屏**：`ActivityOptions.setLaunchDisplayId()`，做成小面板
4. **LSPosed 注入 TNT 桌面**：需 Root，兜底

关键代码骨架：

```kotlin
val dm = getSystemService(DisplayManager::class.java)
dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
  .forEach { d ->
      val ctx = createDisplayContext(d)
      val p = Presentation(ctx, d)
      p.setContentView(R.layout.battery_card)   // 半透明圆角卡片
      p.show()
  }
```

### 3.3 MVP 设计

一个前台服务型 App，三件事：

1. **数据层**：手机电量 + 每 30s 串口查询 TNT GO 电量（失败降级显示「—」）
2. **显示层**：监听 `DisplayManager.DisplayListener`，TNT 屏出现即挂半透明卡片：`Pro3 78% ｜ TNT GO 60%`；置顶不抢焦点、可拖动、单击隐藏
3. **工程细节**：USB 授权弹窗、Smartisan OS 省电白名单、TNT 模式下保活

预估工作量：**1~2 天**（协议与串口库均现成）。

## 4. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 手机枚举不到 CDC 串口 | 数据层失效 | TNT GO 装 App 经 BLE / 局域网推送 |
| TNT OS 把外接屏标记为 private | 渲染层失效 | Shizuku / LSPosed 注入 |
| TNT 窗口管理器接管第三方窗口 | 悬浮效果打折 | 改用 Activity 面板或注入方案 |
| USB 权限弹窗 / 后台被杀 | 体验中断 | 前台服务 + 省电白名单 |
| 波特率 / 指令不确定 | 数据读取失败 | 115200 / 9600 双试，`AT+HELP` 枚举 |
| Pro3 版本差异（7.x / 8.0.4） | TNT 1.0 / 2.0 行为不同 | 两个版本分别验证 |

## 5. 验证清单

### 不需要 Pro3（当前可做）

- [ ] **串口协议**：TNT GO + 电脑 → LLCOM / 串口助手，115200 与 9600 各试一次，发 `at+adb`，确认 `+BATCG=` 返回
- [x] **手机枚举 CDC** ✅ 已验证（2026-09-09）：小米 17 Pro Max + 原装线接 TNT GO 视频口，`dumpsys usb` 确认存在 CDC-ACM（class 2/2/1）+ CDC-Data（class 10）
- [ ] **多屏渲染**：任意安卓机执行
      `adb shell settings put global overlay_display_devices "1280x720/213"`
      验证 Presentation / overlay 代码能否在虚拟副屏显示；测完清除：
      `adb shell settings put global overlay_display_devices ""`
- [ ] **有线时 TNT GO 系统状态**：从电脑 ADB 连 TNT GO，确认其系统是否仍在运行（决定蓝牙推送备选方案可行性）

### 需要 Pro3（后续）

- [ ] `adb shell dumpsys display | grep -i presentation` 查看 TNT 屏 flags
- [ ] `dumpsys window displays` 查看 TNT 屏窗口层级（决定选 Presentation 还是 overlay）
- [ ] 有线连接下用 USB Device Info / `dmesg | grep -i cdc` 确认手机端是否枚举出串口

## 6. 实现路线建议

1. 先用虚拟副屏验证渲染层（1 天）
2. 再用电脑验证串口协议（半天）
3. 最后用 Pro3 实机验证 TNT OS 的显示 / 窗口行为，并接入串口数据（1~2 天）