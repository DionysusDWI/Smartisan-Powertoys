# 07 · 调试记录：小米 17 Pro Max ↔ TNT GO（2026-09-09）

> 记录一次实机调试：从「无法投屏」到「镜像正常」，并顺带验证了 CDC 串口。
> 结果：✅ 已解决

## 环境

- 主机：小米 17 Pro Max（popsicle，Android 16 / HyperOS 3，SDK 36）
- 屏幕：TNT GO 无线版（原装全功能 C-C 线，插视频输入口）
- 调试通道：Shizuku + shell
- 工具：`dumpsys display` / `dumpsys usb` / `dumpsys window` / `dumpsys SurfaceFlinger` / `settings`

## 现象

1. 原装小米线：完全无法投屏（线材无 USB3/DP 通道）
2. 换 TNT GO 原装线：画面**一闪而过**，键盘 / 触控可用，但无画面输出
3. 系统中存在「镜像到外部显示屏」选项，点击无效

## 诊断过程

### Step 1 · 确认 DP 链路（`dumpsys display`）

- 发现 `Display 2 "HDMI 屏幕"`：2160×1440@60，state ON，带 `FLAG_PRESENTATION`
- 设备信息：`DeviceProductInfo{name=RTK FHD, manufacturerPnpId=RTK, productId=447, manufactureDate=2020 年第 12 周}` → TNT GO 的 DP 接收端

### Step 2 · 确认 USB 链路（`dumpsys usb`）

- `host_manager` 枚举到 `deltainno Smartisan TNT go`（VID:PID = `31ce:5101`）
- 接口：HID ×6 + **CDC-ACM（class 2 / sub 2 / proto 1）** + **CDC-Data（class 10）**
- → 手机端串口路径成立（项目核心验证通过）

### Step 3 · 定位「为什么不是镜像」（`dumpsys window displays`）

- display2 上运行 `com.miui.home/.launcher.SecondaryDisplayLauncher`（小米副屏桌面）→ **扩展模式**
- `mFocusedApp=ActivityRecord{...SecondaryDisplayLauncher}`

### Step 4 · 找到开关（`settings list global`）

- `force_desktop_mode_on_external_displays=1` ← 开发者选项「强制在外接显示器上启用桌面模式」
- `mirror_switch=0`
- 镜像服务 `com.xiaomi.mirror/.MainService` 在运行，但被桌面模式接管

### Step 5 · 修复

```bash
settings put global force_desktop_mode_on_external_displays 0
```

→ 拔插线重新触发 → **镜像正常**

## 结果

- ✅ 画面镜像正常
- ✅ 触控、键盘、音量键映射正常
- ✅ `dumpsys SurfaceFlinger` 可见大量 `mirrored from` 层
- ✅ CDC 串口接口可见

## 关键命令备忘

```bash
# 看显示器列表与状态
dumpsys display | grep -A4 "Display 2:"
# 看 USB 设备与接口（含 CDC）
dumpsys usb | sed -n '/host_manager/,$p'
# 看某块屏上跑什么
dumpsys window displays | grep -E "mDisplayId=2|mFocusedApp"
# 看镜像层
dumpsys SurfaceFlinger | grep -i mirror
# 相关设置键
settings get global force_desktop_mode_on_external_displays
settings get global mirror_switch
```

## 经验

1. **线材是第一个要排除的**：手机原装线多数不支持 USB3/DP
2. **「一闪而过」** = 镜像短暂生效后被桌面模式接管，不是硬件故障
3. **小米的「副屏」逻辑会接管外接显示器**（SecondaryDisplayLauncher），关闭强制桌面模式后恢复正常镜像
4. 该设置仅在显示器接入时读取，**修改后必须拔插线**