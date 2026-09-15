# 05 · 测试环境：小米 17 Pro Max 有线投屏（已解决）

> 状态：✅ **已解决**（2026-09-09）—— 镜像、触控、键盘、音量键映射均正常
> 详细调试过程见 `07-debug-session-2026-09-09.md`

## 设备规格（与本问题相关）

| 项目 | 参数 |
|---|---|
| 机型 | 小米 17 Pro Max（popsicle，HyperOS 3 / Android 16） |
| 接口 | USB 3.2 Gen1 全功能 Type-C |
| 官方备注 | **「USB3 数据线需单独采购，整机包装盒中的数据线不支持 USB3 传输」** |
| 视频输出 | 小米 13 Ultra / 14 系列起支持 DP Alt Mode；17 Pro Max 硬件具备条件 |

## 问题现象

1. 用小米原装线连接：完全无画面（线材无 USB3/DP 通道）
2. 换 TNT GO 原装线：画面**一闪而过**，键盘/触控可用但无画面输出
3. 系统「镜像到外部显示屏」选项点击无效

## 根因

| # | 根因 | 说明 |
|---|---|---|
| 1 | **线材** | 小米包装线不支持 USB3，物理上没有 DP 通道 |
| 2 | **系统开关** | 开发者选项「强制在外接显示器上启用桌面模式」被打开（`force_desktop_mode_on_external_displays=1`），系统在 TNT GO 上启动 `SecondaryDisplayLauncher`（小米副屏桌面）→ 扩展模式而非镜像 |

## 解决步骤（已验证）

1. 换 **TNT GO 原装全功能 C-C 线**，插 **视频输入口**
2. 关闭强制桌面模式：

   ```bash
   settings put global force_desktop_mode_on_external_displays 0
   ```

   （对应 UI：设置 → 更多设置 → 开发者选项 → 关闭「强制桌面模式」）
3. **拔插线重新触发**（该开关仅在显示器接入时读取）
4. 结果：镜像正常，触控 / 键盘 / 音量键映射均正常

## 验证结果

- `dumpsys SurfaceFlinger` 可见大量 `Layer [xxx mirrored from 806]`（镜像层）
- display2 `mFocusedApp=`（镜像模式不承载独立任务）
- `dumpsys display`：Display 2「HDMI 屏幕」2160×1440@60，state ON

## 对项目验证路径的影响

### 已验证

| 验证项 | 结论 |
|---|---|
| 手机 DP 输出 → TNT GO 显示 | ✅ 通（2160×1440@60 镜像） |
| 触控 / 键盘 / 音量键 | ✅ 通 |
| **手机枚举 TNT GO 的 CDC 串口** | ✅ 通（CDC-ACM class 2/2/1 + CDC-Data class 10） |

### 仍不能验证

- 小米跑的是 HyperOS，**不是 TNT OS**，无法复现「TNT 窗口管理器是否接管第三方悬浮窗」这一核心风险
- 无法验证 TNT 1.0 / 2.0 对 secondary display 的 flags 与层级处理

## 待办

- [x] 换全功能线复测有线投屏
- [x] 用 USB Device Info 记录 TNT GO 在手机 Host 下的接口列表（已确认含 CDC-ACM）
- [ ] 手机端串口实测：发送 `at+adb` 并解析 `+BATCG=`
- [ ] 虚拟副屏 / 镜像环境验证悬浮窗渲染代码