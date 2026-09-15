# 03 · TNT GO 串口电量协议

> 核心突破点：TNT GO 的电量可通过 USB 串口指令读取，协议已被社区逆出并有开源实现。
> 整理时间：2026-09-09

## 协议原文

TNT GO 通过 USB-C 连接电脑后，会暴露一个 CDC-ACM 串口设备。向串口发送 `at+adb`，返回：

```
+BATCG=3855,60,2,-929,275,2
+HRM=0,276,1004
```

**解析规则**：`+BATCG=` 后的**第 2 个数字 = 电池剩余电量百分比**（上例 60%）。

字段推测（未验证）：`3855` 疑为电池电压 mV；`-929` 疑为电流 mA（负值 = 放电）。

## 串口参数

| 参数 | 值 | 备注 |
|---|---|---|
| 设备标识 | VID:PID `31ce:5101`（`deltainno Smartisan TNT go`） | 连接电脑时枚举 |
| 波特率 | **115200**（社区项目默认） | 另说 **9600**（早期开 ADB 教程）——两个都试 |
| 数据格式 | 文本 AT 响应，CR/LF 结尾 | |
| 连接端口 | 电脑接视频输入口出现串口；接充电口出现 `boston`（ADB 设备） | 两个口行为不同，需实测确认 |

## 参考开源实现

| 项目 | 平台 | 说明 |
|---|---|---|
| `SA-GIMA/TNT-Go-Battery-Monitor` | Python（CLI + GUI） | 串口读取 + 正则解析 `+BATCG=`；默认 COM3 / 115200 |
| `electrie00/TNTgo-Boom` | macOS | 状态栏显示 TNTgo 电量及充电状态；串口读取；同时提供 VoodooI2C 驱动方案 |
| `qiedd.com/875.html` | 教程 | TNT GO 开启 ADB（串口 `AT+ADB`，9600） |
| `qiedd.com/889.html` | 教程 | APK 逆向（`BostonScreenMirror.apk`） |

## 读取流程（伪代码）

```python
import serial, re

ser = serial.Serial(port, 115200, timeout=1)
ser.write(b"at+adb\r\n")
data = ser.read(256).decode(errors="ignore")

m = re.search(r"\+BATCG=\d+,(\d+),", data)
level = int(m.group(1)) if m else None   # 电量百分比
```

## 官方行为佐证

- 砍柴网 TNT go 评测：TNT 2.0 右侧快捷开关 / 通知栏「显示电量（TNT go 和手机）」——说明官方在 R2 无线场景下已有该数据通道，很可能就是同一串口协议

## 待验证点

1. ~~有线 DP 模式下，手机（USB Host）能否枚举到 TNT GO 的 CDC 串口接口~~ ✅ **已验证（2026-09-09）**
   - 实测小米 17 Pro Max 作为 Host 连接 TNT GO（有线 DP + 镜像模式）时，USB 接口列表包含：
     - `id=6 class=2 subclass=2 protocol=1`（CDC-ACM）
     - `id=7 class=10 subclass=0`（CDC-Data）
   - 命令：`dumpsys usb | sed -n '/host_manager/,$p'`
2. 波特率到底 115200 还是 9600（或两者皆可）
3. `AT+HELP` 是否还有其他电量 / 状态指令（如专用查询、充电状态）
4. 连接手机时 TNT GO 的 USB 描述符是否与连接电脑时一致（HID + CDC？仅 HID？）
5. 若手机端 CDC 不可见，退路：TNT GO 装 App 经 BLE / 局域网推送电量（需确认有线时其安卓系统是否仍运行、蓝牙是否可用）

## 风险与注意

- Android 内核 `cdc_acm` 可能抢先绑定串口，App 需 `detachKernelDriver` 或强制 claim
- 首次连接会弹 USB 授权框
- 该串口亦用于开启 ADB（`AT+ADB`），操作时注意不要误触发模式切换