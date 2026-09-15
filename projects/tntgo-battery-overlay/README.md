# TNT GO 电量悬浮窗

> 目录：`projects/tntgo-battery-overlay/`

## 目标

坚果 Pro3 **有线连接** Smartisan TNT GO 时，在 TNT 屏上常驻显示电量：

- 优先形式：悬浮窗（`Presentation` 或 display-context overlay）
- 内容：Pro3 电量 + TNT GO 电量（可切换 / 同显）
- 场景：Pro3 经 USB-C 连接 TNT GO 视频输入口，运行 TNT 大屏系统

## 背景速览

- TNT GO 无线版内置精简 Android 9.0（Amlogic S905Y2 / 1GB RAM / 8GB eMMC）；有线模式下屏幕内容 100% 由手机渲染
- 官方仅在坚果 R2 + 无线场景下，于 TNT 2.0 状态栏显示「TNT GO + 手机」双电量
- TNT GO 电量可通过 USB 串口指令读取：`at+adb` → `+BATCG=` 第二个字段，已有开源实现
- 当前**无 Pro3 实机**；验证机为小米 17 Pro Max（有线投屏待排查，见 `docs/05-test-environment-xiaomi17pm.md`）

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/01-hardware-specs.md` | TNT GO 无线版硬件参数（含内部 SoC / 内存 / 存储） |
| `docs/02-pro3-interface-access.md` | Pro3 调用 TNT GO 全部功能接口：有线 / 无线 / ADB / 破解 / 显示器模式 |
| `docs/03-battery-serial-protocol.md` | TNT GO 串口电量协议与开源实现 |
| `docs/04-feasibility-and-architecture.md` | 可行性推算、方案架构、MVP 设计、验证清单 |
| `docs/05-test-environment-xiaomi17pm.md` | 小米 17 Pro Max 有线投屏（已解决）与验证路径影响 |
| `docs/06-references.md` | 参考资料与链接汇总 |
| `docs/07-debug-session-2026-09-09.md` | 实机调试记录：镜像问题定位与 CDC 串口验证 |

## 代码

| 路径 | 内容 |
|---|---|
| `src/` | Android App（Kotlin，MVP）—— 见 `src/README.md` |

## 当前状态

- [x] 资料调研与可行性推算
- [x] 手机端 CDC 枚举验证（小米 17 Pro Max，已确认 CDC-ACM + CDC-Data）
- [x] 小米 17 Pro Max ↔ TNT GO 有线镜像打通（关闭强制桌面模式，见 `docs/05`、`docs/07`）
- [x] MVP 代码框架（`src/`：Presentation 卡片 + 串口读电量 + 前台服务）
- [ ] MVP 实机验证（小米 17 Pro Max + TNT GO）
- [ ] TNT OS（Pro3）兼容性验证（待实机）

## 关键结论

- 显示 **Pro3 电量**：可行性高（~80%），纯手机侧软件问题
- 显示 **TNT GO 电量**：可行性中高（~65%），唯一硬性未知数是手机（USB Host）能否枚举到 TNT GO 的 CDC 串口
- 渲染层首选 `Presentation`（零权限），备选 display-context overlay（`TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`）

## 更新记录

- 2026-09-09：项目初始化，落档调研与可行性资料
