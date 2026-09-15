# 02 · 坚果 Pro3 调用 TNT GO 全部功能接口

> 目标：以坚果 Pro3 为信号源，尽可能调用 TNT GO（无线版）的显示、触控、键盘、手写笔、无线接收与本体系统接口。
> 整理时间：2026-09-09

## 前提事实

- Pro3 支持 TNT：Smartisan OS 7.x（Android 9）为 TNT 1.0；升级到 **Smartisan OS 8.0.4（Android 10）** 可体验 TNT 2.0
- 官方无线连接仅认证坚果 R2（蓝牙握手 + 热点 + TNT Link）
- TNT GO 无线版同时保留全功能 USB-C 视频输入，可当有线屏使用
- 有线模式下 Pro3 是 USB Host（枚举 TNT GO 的触控 / 键盘 HID）

---

## 第一层：有线全连接（官方支持，最可靠）

1. 确认 Pro3 系统版本（7.x → TNT 1.0；8.0.4 → TNT 2.0）
2. 全功能 USB-C 线连接 Pro3 与 **TNT GO 的视频输入口**（不要插仅供电的充电口）
3. 自动进入 TNT 大屏系统，触控 / 磁吸键盘 / 触控板 / 手写笔压感均可用
4. TNT GO 可反向为 Pro3 供电

> 顺带：TNT GO 视频口也兼容 PC / 掌机（USB 3.1 Gen1 以上）；HDMI 设备需转接器。

## 第二层：开启 TNT GO 的 ADB（拿到本体控制权）

1. USB-C 线插 **视频输入口** 连电脑 → 设备管理器出现 COM 串口
2. 串口工具（Windows：友善串口调试助手；Linux：`sudo cutecom`），**波特率 9600、CR/LF 换行**
3. 发送 `AT+ADB`（`AT+HELP` 可枚举全部指令）→ 返回 `ADB ENABLE`
4. 线换插 **充电口** → 设备管理器出现 `boston` 设备 → 安装 Google USB 驱动（VID `31ce:5101`）
5. `adb devices` / `adb shell` 可用：
   - `adb shell pm list packages`
   - `adb pull /system/app/BostonScreenMirror/BostonScreenMirror.apk`
   - `adb install` 第三方 App（如 Nova Launcher）→ 屏幕上**连按两下锤子键 + 退格键** → 弹出桌面选择

## 第三层：让 Pro3 无线连上（官方仅 R2，三条曲线路线）

**路线 A｜改装接收端解除限制（最彻底）**
- 逆向 `BostonScreenMirror.apk`：核心逻辑在 `com.smartisanos.boston.base`（蓝牙配对、热点、机型白名单）
- 去除 R2 机型 / 签名校验 → 重签名 → adb 或 recovery 刷回
- 注意：工程版 1.0.0 与正式版 1.0.2 签名不同，不能直接覆盖
- 社区（酷安 twobigbowl）已实现「任意设备 WiFi 投屏」；APK 备份：`https://dl.qiedd.com/android/TNT_go/apk/`

**路线 B｜装通用投屏接收端（最省事）**
- Pro3 自 Smartisan OS 7.0 起同时兼容 **Miracast + 乐播投屏**
- 在 TNT GO 上装乐播投屏 TV 版 / Miracast 接收器 → Pro3 走系统无线投屏

**路线 C｜激活后走官方流程（碰运气）**
- 首次需有线插一次坚果手机激活配对；之后蓝牙发现 → 热点 → 接收投屏
- 蓝牙握手大概率有 R2 白名单，失败则回到 A / B

## 第四层：进阶玩法（开源社区）

- **TNT-Anywhere**（GitHub `CashewTeam/TNT-Anywhere`）：Shizuku + VirtualDisplay + MediaCodec + Moonlight，把 Pro3 变成 TNT 串流主机；**Pro3（8.0.4 / Android 10）有适配分支**；仓库含 TNT 启动逆向、私有 API 汇总等文档
- **Linux 手写笔**：知乎 p/539458130，用 libevdev 将 TNT GO 手写笔输入转为虚拟设备（坑点：`BTN_TOUCH/BTN_STYLUS/BTN_TOOL_PEN` 为 `EV_KEY` 类型）
- **刷机方向**：TNT GO = S905Y2 盒子，社区在研究刷 Radxa Zero（同 SoC）固件；贴吧有 Recovery / 刷机教程（tieba 8015413444、8317551470）

## 当纯显示器用

- HDMI 转 Type-C 转接器接 PC / Xbox / PS5 / Switch（Switch 需转接器激活主机模式）
- Windows 下触控可用；iPad 显示 + 触控；Mac 仅显示

## 风险提醒

- 动手前先 `adb pull` 备份 `BostonScreenMirror.apk`、`BostonCastHalService.apk` 等系统应用
- TNT GO 无官方固件可回恢复，刷坏基本只能靠同 SoC 固件救砖
- TNT OS 2.0 已停止维护，竖屏模式缺失，无线串流有二次压缩画质损失