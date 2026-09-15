# 06 · TNT GO AT 命令全集(CDC-ACM 命令台)

> **版本**:v1.2 ｜ **更新**:2026-09-15 ｜ **状态**:命令表已定;**三项待解已全部收口**
> 变更记录见 [CHANGELOG.md](CHANGELOG.md)
> **来源**:`at+help` 原始输出(129 条)+ CC/OP 双方实测 ｜ **整理**:OP 原始全集,CC 补实测与判读
> **v1.2**(任务 AQ 收尾 AQ10):★ **勘误 `+BATCG` 第 3 字段** —— 旧记「恒为 `2`,无信息量」**不成立**,
> 它是**充电状态**(`1`=充电 / `2`=放电);★ 顺带**加强**第 6 字段(两状态都是 `2` ⇒ 与充放电无关);
> ★ `+HRM` 第 3 字段由「未知 ADC」**改判为「疑似计数器」**(抓到连续 14 次恰好 `+2`)。
> 依据:[01 §7.4 / §7.5](01-TNT-GO硬件基础.md) ｜ [AR](plans/AR-审计整改.md)
> **v1.1**:§5 的 47 寄存器**已完整解码**(→ [08](08-TNTGO电池芯片规格与寄存器.md));
> §6.2 `+HRM` 第 2 字段**已确定为电池温度**;§6.3 电池健康度**已裁定**(数据在 `BQ27Z561` 标准命令空间)。
> 同轮修正:v1.0 的两条**形态学猜测被推翻**,已标勘误保留
> 相关:[01 §7.2](01-TNT-GO硬件基础.md) · [08](08-TNTGO电池芯片规格与寄存器.md) · 计划书 [G](plans/G-TNTGo电量探针APK.md) / [H](plans/H-探针APK-v2.md) / [I](plans/I-BQ寄存器解读与健康度结案.md)

---

## 0. 这是什么

TNT GO 主控(`31CE:5101`,全速 12 Mbps)的**第 7 号接口是 CDC-ACM 串口**(`2/2/1`),
接上后表现为一个 **115200 8N1 的 AT 命令台**。全部 129 条命令由 `at+help` 一次吐出。

**固件版本**:`+VER=MCU:1.1.1-20201104185843, BOOT:116, SCALER:0.0.0, WIFI:1.0.0-…, TP:B1.00.21-16, DP:1.8, AUDIO:2.1.3`

> ★ **主控是 STM32 系** —— `AT+CHECK` 回 `+CHECK: PB5: 0, PD15: 0, PA14: 0`,
> `PA13/PA14` 正是 STM32 的 SWDIO/SWCLK ⇒ 端口命名体系为 STM32。

---

## 1. ⚠️★★★ 铁律:AT 命令**不区分 get / set**

这是**用一次设备故障换来的教训**(2026-09-11,计划书 [H](plans/H-探针APK-v2.md) §事故报告)。

| 响应形态 | 类型 | 处置 |
|---|---|---|
| **返回数据**(如 `+BATCG=…`、`+HALL=…`) | **查询型** | ✅ 可安全批量试 |
| **返回裸 `OK`** | **设置型** | ⛔ **必须逐条人工确认才能发** |
| 回 `[ERROR] parameter error,use AT+XXX=…` | **带参命令** | ⚠️ 说明它**接受参数**,无参时走错误分支 |
| **无任何响应** | 未知 | ⚠️ 可能是被忽略,也可能是**已生效但不回显** —— 不要连续重试 |

**事故经过**:盲发 `at+dpdirect` / `at+dpscaler` / `at+hdmiscaler`(全部回裸 `OK`)
→ **DP 链路配置被改写** → DP 重新训练 → Type-C 重协商 → **USB 数据角色掉线**
→ TNT GO 从 `UsbManager` 消失、屏幕停在「连接屏」。

**恢复**:手机侧 `settings put secure pc_mode_enable 0→1` **无效**;
**只能物理重插 TNT GO 的 USB-C**。

**⇒ `AT+DPDIRECT` 未写入 NVRAM**(重插后 HDMI `uniqueId` 回到原值 `local:10652974438123012`),
最坏情况未发生 —— 但**不能假设下次也一样**。

---

## 2. 🚫 绝对禁区(APK 已硬编码黑名单,手输也拒绝)

```
【电源】AT+SHUTDOWN  AT+PWROFF  AT+POWEROFF  AT+REBOOT  AT+RESET  AT+RECOVERY
        AT+SLEEP  AT+STARTUP  AT+SYSTEM  AT+SETDEVICERESET
【固件】AT+UPGRADE  AT+FLASHWRITE  AT+OTPWRITE  AT+SCALERUPDATE
        AT+SETFW*  AT+SETFWMODE  AT+SETFWTRANSFOR  AT+SETFWVERIFY  AT+SETFWWORKMODE
        AT+SETGPIO  AT+ERASE*  AT+DATACLR  AT+BKPCLR  AT+I2CERRORCLEAR
【★断链】AT+DPDIRECT  AT+DPSCALER  AT+DPDEBUG  AT+HDMISCALER  AT+USBSTATE  AT+TYPECUSB
        AT+LCDON / AT+LCDOFF   AT+DSPON / AT+DSPOFF   AT+CAMON / AT+CAMOFF
        AT+WIFI*   AT+TPSLEEP  AT+TYPEPIN / AT+TYPECPIN
```

---

## 3. 完整命令表(129 条)

> 分组与条目来自 OP 对 `at+help` 的整理(存档 `40_shared/docs/TNTGO_AT命令全集.md`)。
> **`★实测`** = CC 侧已实机验证;**`⚠️set`** = 已知设置型;**`🚫`** = 禁区。

### 3.1 电池 / 电源 ★核心
```
AT+BAT  AT+BATCG★  AT+BAT?  AT+BATT  AT+BATTERY
AT+BQ★  AT+BQ?★  AT+BQ25890★  AT+BQ25970★  AT+BQ27561★
AT+TEMP★  AT+UVLO★
```
| 命令 | 实测响应 | 判读 |
|---|---|---|
| `AT+BATCG` | `+BATCG=<电压mV>,<电量%>,<状态>,<电流mA>,<温度×0.1°C>,<?>` | ★★ **真实电量来源**(路线 C) |
| `AT+BAT` / `AT+BATT` | 同上格式(`+BAT=` / `+BATT=`) | 别名 |
| `AT+BATCG?` | 同上(**会连推两帧**) | 别名 |
| `AT+BQ` / `AT+BQ?` / `AT+BQ27561` | `+BQ27561=0x6115` / `+BQ2589X=0x03` / `+BQ2597X=0x10` | ★★ **三颗 TI 芯片 ID**,三者响应**完全相同** |
| `AT+BQ25970` | `[ERROR][CP] >> REG_00: 0x26` … `REG_2E: 0x00`(**47 个**) | ★ 另一颗芯片的**寄存器 dump**,原文见 §5 |
| `AT+BQ25890` | **(无响应)** | 命令存在但无输出 —— 见 §6 |
| `AT+TEMP` | `-> adc1 value= 0, adc1 value= 2836` / `-> tmp1= 0, tmp2= 304` | `tmp2` = **30.4°C** |
| `AT+UVLO` | `+UVLO=3000` | 欠压锁定阈值 3000 mV |

#### ★★ `+BATCG` 六个字段逐个判定（2026-09-15 更新）

| 字段 | 含义 | 依据 |
|---|---|---|
| **1** | 电压 mV（**电池侧**） | 实测；与 `BQ25970` 的 `VBAT` 交叉验证（4229 ↔ 4237 mV） |
| **2** | 电量 % | 实测；来自 `BQ27Z561` 电量计 |
| **3** | ★★ **充电状态**：`1` = **充电** / `2` = **放电** | ★ **勘误,见下** |
| **4** | 电流 mA（**正 = 充电 / 负 = 放电**） | 实测 |
| **5** | ★ **电池温度 = 值 ÷ 10 °C** | 两组合独立样本 ＋ 2026-09-15 **同帧 15 组复核**（[01 §7.4](01-TNT-GO硬件基础.md)） |
| **6** | ⚠️ **语义仍未定** —— 但**充放电两态都是 `2`** ⇒ **与充放电状态无关** | 跨状态样本 |

> #### ⚠️★ 勘误（2026-09-15,任务 AQ 收尾）：**第 3 字段不是「无信息量」**
>
> v1.0 起本表把第 3 字段记为「⚠️ 全部 5 个样本恒为 `2`,无信息量,**不要猜**」。**该判定不成立。**
>
> | 状态 | 样本 | 字段 3 | 字段 4 |
> |---|---|---|---|
> | **充电中** | `+BATCG=4398,100,`**`1`**`,1514,330,2` | **1** | **+1514** |
> | **放电中** | `+BATCG=4144,95,`**`2`**`,-1351,303,2` | **2** | **−1351** |
>
> ★★ **反例 2026-09-11 就已经在归档里** —— 见 [`_serial_probe.txt`](../refs/ccop_bridge/_serial_probe.txt)
> L8 / L20 / L24 / L28 / L32（全是 `,100,1,` 开头的**充电态**）。
> 当时举证的那「5 个样本」**全是放电态**，于是归纳出了"恒为 2"。
>
> ⇒ ★★ **教训：「样本恰好全在同一状态」不能读成「该字段恒定」。**
> 这与 §7 的 `casthal`（**没搜到 ≠ 不存在**）是同一个模式 —— 这次是 **没采到 ≠ 不存在**。
> **纪律**：归纳某字段"恒定 / 无信息量"之前，**先确认样本覆盖了该字段可能取的所有状态**。
>
> ★ **第 6 字段的判定反而被加强**：现在充电态与放电态都有了，而它**两态都是 `2`**
> ⇒ 「与充放电无关」这条现在是**有跨状态证据**的（但**语义仍未定**，仍不要猜）。

### 3.2 状态 / 身份
```
AT+INFO★  AT+VER  AT+STATE★  AT+ST★  AT+STATUS★  AT+CHECK★
AT+REPORT★  AT+SYSMSG★  AT+SN★  AT+SID★  AT+LOG
AT+GETDEVICESTATUS  AT+GETDEVICEDESCRIPTOR  AT+GETREPORTDESCRIPTOR  AT+GETFWINFO
```
| 命令 | 实测响应 |
|---|---|
| `AT+VER` | `+VER=MCU:1.1.1-20201104185843, BOOT:116, …` |
| `AT+STATE` | `+STATE=INIT` |
| `AT+ST` / `AT+STATUS` | `+ST=P` / `+STATUS=P` |
| `AT+SN` | `+SN=CN00688D0A400260` ★ 序列号 |
| `AT+SID` | `+SID=0266263843088520` |
| `AT+SYSMSG` | `+SYSMSG=0` |
| `AT+REPORT` | `+REPORT=0` |
| `AT+INFO` | 只回了一帧 `+BATCG=…`(疑为**别名/空实现**) |
| `AT+CHECK` | `+BATCG=…` + `+CHECK: PB5: 0, PD15: 0, PA14: 0` + **`+HRM=0,298,59`** ← 见 §6.2 |
| `AT+OBD` | `WDG TASK ALL = 0x00003FEF` / `WDG TASK FEED= 0x00003FEF` / `HARDFAULT CNT= -1` / `WATCHDOG CNT = -1` / `====== DEAD LOG ======` + 一串 `0xFFFD` |
| `AT+GETDEVICESTATUS` / `…DESCRIPTOR` / `…REPORTDESCRIPTOR` / `AT+GETFWINFO` | **(全部无响应)** |

### 3.3 Type-C / USB
```
AT+TYPEPIN  AT+TYPE  AT+TYPECUSB⚠️set  AT+USBSTATE  AT+WIFIUSB  AT+HUBSET
```
| 命令 | 实测响应 |
|---|---|
| `AT+TYPECPIN` | `+TYPECPIN=DT2003C` ★ Type-C 控制器型号 |
| `AT+TYPECUSB` | 裸 `OK` ⛔ 设置型 |
| `AT+USBSTATE` | `+USBSTATE=1,0`(读取型;**但 `AT+DPDIRECT` 也会回这一行**) |

### 3.4 键盘盖 / 霍尔 ★(对 `Tlid` 研究有用)
```
AT+HALL★  AT+GETKBFOLD★  AT+GETKBCONNECT★  AT+GETKBPEN★  AT+KBSYSKEY★
AT+SETHIDSLEEP⚠️set  AT+SETHIDWAKEUP⚠️set
```
| 命令 | 实测响应 |
|---|---|
| `AT+HALL` | **`+HALL=0,1,1`** ★★ 三路霍尔数字状态 |
| `AT+GETKBFOLD` / `AT+GETKBCONNECT` | **裸 `OK`** ⛔ **是设置型,不是查询**(命名有误导性!) |
| `AT+GETKBPEN` / `AT+KBSYSKEY` | (无响应) |

### 3.5 显示 / 背光 / LED ★★★（**已打通**）
```
AT+SCREEN  AT+BKL  AT+BACKLIGHT  AT+LIGHT  AT+LCDON🚫  AT+LCDOFF🚫  AT+LCDBLINK
AT+LED  AT+LEDON  AT+LEDOFF  AT+LEDBLINK  AT+LEDEFFECT
AT+GETSATHUE  AT+SETSATHUE  AT+DPDIRECT🚫  AT+DPSCALER🚫  AT+DPDEBUG🚫  AT+HDMISCALER🚫
```
| 命令 | 实测响应 | 判读 |
|---|---|---|
| **`AT+BKL`** | **`+BKL=<值>`** | ✅ **查询型（裸发安全）** —— 量程 **9~2000** |
| **`AT+BKL=<v>`，v ∈ 1~2000** | **`+BKL=<v>`** | ✅✅ **设置型，实测生效**（用户目视确认亮度真的变了） |
| ★★ **`AT+BKL=<v>`，v ≥ 2001** | ★★ **`+ERROR=100`** | ★★ **设备【明确拒绝】** —— 精确边界见下 |
| `AT+LIGHT` | `+LIGHT=20` | ✅ **环境光**读数 |
| `AT+SCREEN` | `+SCREEN=1` | ✅ 屏幕状态位 |
| `AT+BACKLIGHT` | **无响应** | ❌ 命令名存在但无实现 |
| `AT+LCDBLINK` | 未测 | 厂测用(屏幕闪烁);不入批量表 |
| `AT+LED*` | 未测 | 是**键盘灯 / 指示灯**，**不是屏幕背光** |
| `AT+GETSATHUE` / `AT+SETSATHUE` | 未测 | 色温(对应设置键 `TNT_screen_color`?) |

#### ★★★★ `AT+BKL` 量程的**精确边界**（2026-09-13 实测，任务 AI）

```
at+bkl=1     →  +BKL=1        ✓        at+bkl=1999  →  +BKL=1999   ✓
at+bkl=9     →  +BKL=9        ✓        at+bkl=2000  →  +BKL=2000   ✓  ← ★ 上限
at+bkl=1000  →  +BKL=1000     ✓        at+bkl=2001  →  +ERROR=100  ★★ 拒收
                                       at+bkl=2048  →  +ERROR=100  ★★
                                       at+bkl=2500  →  +ERROR=100  ★★
```

| 结论 | |
|---|---|
| **上限** | ★ **正好 `2000`**（不是 2047/2048）—— 与出厂公式 `clamp(…, 0x9, 0x7d0)` 的 `0x7d0 = 2000` **一字不差** |
| **下限** | ○ **`1` 与 `9` 都可用** ⇒ 实际下限 **≤ 1**；出厂公式的 `9` 是**保守值**（`0` 未测，没必要） |
| ★★ **新协议事实** | **越界时设备回 `+ERROR=<code>`，而不是静默夹取** ⇒ ★ **这是一个可以当判据用的确定性回应**<br/>⚠️ 别把它当成"没收到回应"—— 两者含义完全相反 |

> ⚠️ **一条差点被骗过去的经历**：越界回 `+ERROR=100` 时，
> 我们的代码最初把它归到"没等到 `+BKL`"⇒ 报成 **`Busy`（串口被占）** 并**白重试 3 次**。
> ⇒ ★ **「设备明确拒绝」与「串口资源冲突」必须分开报** —— 混在一起会把一个**确定性的量程事实**
> 伪装成一个**偶发的资源问题**（详见 [AI §6.6](plans/AI-TNTGO亮度键mod.md)）。

> ### ★★ `AT+BKL` ≈ 官方 `casthal.setBacklight()` 的等价物
>
> AT 侧量程 **9~2000** 与官方 `calculateBrightnessMcuValue()` 的**输出范围完全一致**
> (UI 0~100 → MCU 9/58/207/762/2000,公式见 [07](07-TNTGO亮度独立控制.md) §1)。
> **⇒ `casthal`(Boston/TNT GO 专用 HAL)缺失时,`AT+BKL` 是它的替代品。**
>
> ### ⚠️ 背光变化**不会进 framebuffer** —— 截图/录屏**永远看不出**
> 只能靠 ① **人眼** ② 回读 `+BKL`。详见 [07](07-TNTGO亮度独立控制.md) §4。

### 3.6 触摸板
```
AT+TPGETVER  AT+TPCONFIG  AT+TPTEST  AT+TPS  AT+TPS?  AT+TPSLEEP🚫
```

### 3.7 DSP / 音频
```
AT+AUD  AT+AEC  AT+AEC?  AT+MICMODE  AT+MICMODE?
AT+DSPINI  AT+DSPREAD  AT+DSPREQ  AT+DSPPACTRL  AT+DSPPAREAD
AT+DSPROUTE  AT+DSPTEST  AT+DSPOFF🚫  AT+DSPON🚫  AT+DSPSPOFF
```

### 3.8 WiFi(无线版才有)
```
AT+WIFI🚫  AT+WIFION🚫  AT+WIFIOFF🚫  AT+WIFIRESET🚫  AT+WIFISLEEP🚫  AT+WIFIWAKEUP🚫
AT+WIFICHECK  AT+WIFIMAC1  AT+WIFIMAC2  AT+WIFIINFO  AT+WIFICON🚫
```

### 3.9 蓝牙
```
AT+BT  AT+BTMAC  AT+BLEMAC
```

### 3.10 Flash / OTP / 固件(⚠️ 高危,全部按禁区对待)
```
AT+FLASHID  AT+FLASHREAD  AT+FLASHEB  AT+FLASHEC  AT+FLASHES  AT+FLASHTEST
AT+SCALEFLASHID  AT+SCALEFLASHREAD  AT+SCALEFLASHEB
AT+OTPREAD  AT+WDGTEST
```

### 3.11 GPIO / I2C / 调试
```
AT+GETGPIO★  AT+SETGPIO🚫  AT+I2CERRORNUM  AT+I2CERRORCLEAR🚫  AT+I2CRELEASE
AT+RUN  AT+TEST  AT+TESTSCALER  AT+PT  AT+OBD★  AT+WT  AT+MC  AT+CW  AT+SFT
AT+ANT  AT+CLOCK  AT+DBC  AT+MMI1  AT+MMI2  AT+CM1  AT+SWDIO  AT+FDPSCALERTEST
AT+DATACLR🚫  AT+ADB  AT+AMADB  AT+BKP  AT+BKPCLR🚫  AT+BKPTEST
AT+SETDEVICERESET🚫  AT+SLEEP🚫  AT+STARTUP🚫  AT+SYSTEM🚫  AT+REBOOT🚫
```
| 命令 | 实测响应 |
|---|---|
| `AT+GETGPIO` | `[ERROR] parameter error,use AT+GETGPIO=1~7,0~15` ⇒ **可单读 GPIO**(端口 1~7 / 引脚 0~15) |
| `AT+ADB` | `ADB ENABLE` + `OK` ⚠️ **设置型**(打开 ADB 通道) |
| `AT+I2CERRORNUM` | **尚未测到**(上次被 `input text` 截断成 `AT+I`) |

---

## 4. ★★ 判读要点(踩过的坑)

| # | 要点 |
|---|---|
| 1 | **裸 `OK` = 设置型** —— 见 §1,这是唯一判据 |
| 2 | **命名有误导**:`AT+GETKBFOLD` / `AT+GETKBCONNECT` **叫 GET 但回裸 `OK`**,是设置型 |
| 3 | **命令会互相串台**:`AT+DPDIRECT` 的响应里带了 `+USBSTATE=1,0`,不是它自己的输出 |
| 4 | **异步推送**:任何命令之后都可能插入一帧 `+BATCG=…`(`+BATCG` 是周期性上报的)⇒ **不要把 `+BATCG` 当成命令的返回值** |
| 5 | **无响应 ≠ 无此命令** —— 真未知命令回 `UNKOWN COMMAND`(原文拼写如此);**静默**才是「命令存在但走空分支」 |
| 6 | **带参命令存在** —— `AT+DPDEBUG` 回 `[ERROR] parameter error,use AT+DPDEBUG=0~2,reg,val`;`AT+GETGPIO` 回 `=1~7,0~15` ⇒ **本固件 AT 命令支持 `AT+XXX=参数` 形式** |

---

## 5. 附录 A:`AT+BQ25970` 完整寄存器 dump(47 个,2026-09-11 13:56 实测)

> **原文出处**:`.ref/probe_raw/tntgo_serial_probe.txt` L40–134 ｜ **原始前缀**:每条都带 `[ERROR] [CP] >>`
> ⚠️ 这是**某一时刻的快照** —— 充电中/放电中/接不同电源时值会变。

```
REG_00:0x26  REG_01:0x24  REG_02:0xBD  REG_03:0xBC  REG_04:0xA8
REG_05:0x1D  REG_06:0x5A  REG_07:0x50  REG_08:0x0D  REG_09:0x50
REG_0A:0x00  REG_0B:0x44  REG_0C:0x02  REG_0D:0x02  REG_0E:0x06
REG_0F:0x81  REG_10:0x00  REG_11:0x80  REG_12:0x02  REG_13:0x10
REG_14:0x80  REG_15:0x22  REG_16:0x00  REG_17:0x00  REG_18:0x00
REG_19:0x00  REG_1A:0x00  REG_1B:0x00  REG_1C:0x00  REG_1D:0x00
REG_1E:0x10  REG_1F:0x85  REG_20:0x00  REG_21:0x0A  REG_22:0x01
REG_23:0x3A  REG_24:0x00  REG_25:0x00  REG_26:0x00  REG_27:0x34
REG_28:0x40  REG_29:0x15  REG_2A:0xBE  REG_2B:0xE0  REG_2C:0x50
REG_2D:0x00  REG_2E:0x00
```

**结构观察 —— ⚠️ 部分已被推翻,原判定保留供追溯:**

> **v1.0 的形态学猜测(有对有错)**:
> - ~~`0x16`–`0x1D` 连续 8 个 `0x00` —— 典型的未实现/保留区~~ ❌ **错**
>   实际是 **ADC 数据寄存器**(`IBUS/VBUS/VAC/VOUT` 的 MSB/LSB 对),读 0 是因为
>   **当时充电泵未使能 + 扫描被屏蔽**,不是「保留」
> - ~~`0x00=0x26`、`0x01=0x24` 像两字节配置字~~ ❌ **错**
>   是两个**独立的**保护阈值寄存器(`BAT_OVP` / `BAT_OVP_ALM`)
> - ✅ **对的那条**:`0x00`–`0x2E` 是**线性寄存器窗口**,不是间接寻址 ——
>   已由厂商内核驱动**双源校验**证实

**★★ 完整的解码结果见 → [08-TNTGO电池芯片规格与寄存器.md](08-TNTGO电池芯片规格与寄存器.md) §2.3**
(含三处交叉验证:`VBAT=4229 mV` ↔ `+BATCG` 4237 mV;`TSBAT_DIS=1` ↔ `TSBAT ADC=0`;
`CHG_EN=0` ↔ `IBAT≈0`;以及 `0x13=0x10` ↔ `at+bq` 的 `+BQ2597X=0x10`)

---

## 6. ★ 三项待解(截至 v1.0)

### 6.1 `AT+BQ25970` 的 47 个寄存器是什么? → ✅ **已解出(2026-09-11 任务 I)**
**是 TI `BQ25970` 开关电容充电泵的完整寄存器映射**(`0x00`–`0x2E`,直线映射,无间接访问)。
**已用实测值完成解码并通过三处交叉验证** → 全部见 **[08](08-TNTGO电池芯片规格与寄存器.md) §2**。

**附带确认**:日志标签 `[CP]` = **C**harge **P**ump ✅(BQ25970 就是充电泵);
`AT+BQ25970` 带 `[ERROR]` 前缀却仍完成 dump ⇒ **无参调用走错误分支**。

**仍未决**:`AT+BQ25970=<reg>` 能否单读?`AT+BQ25890` 为何静默?
`AT+BQ27561=<reg>` 能否打开电量计窗口?(⚠️ 且 `=参数` 可能是**写**语义 —— 见 §1 铁律)

### 6.2 `+HRM=0,298,59` 是什么?

出自 `AT+CHECK`:
```
+BATCG=4144,95,2,-1351,303,2
+CHECK: PB5: 0, PD15: 0, PA14: 0     ← STM32 风格 GPIO
+HRM=0,298,59                        ← 3 个值
```

> ### ⚠️ 勘误(2026-09-11,用户指正):**把「加速度计」这条候选撤下**
>
> 初版我把「加速度计 x/y/z」列为**首要候选**,理由是 `.paper/02` §8.9.3 的
> `getExtendScreenAccelSensor` 传输通道未验证。**该理由不成立。**
>
> **用户告知(硬件事实)**:**加速度计是给 R2 的「无线连接」用的;
> 坚果 Pro 3 只能有线连接,因此用不到加速度计。**
>
> **代码三条印证**:
> 1. `tryOpenAccelSensor()` 里有**显式放弃路径** ——
>    `if (i >= 10) return;`(`MAX_TRY_FIND_ACC_SENSOR = 10`)⇒ **找不到就永久停手**,
>    这正是一个「本机型没有该传感器」的设计
> 2. **手机自己是有加速度计的**(`dumpsys sensorservice` 实测:`icm4x6xx Accelerometer`,TDK-Invensice)
>    —— 而代码找的是**「外接屏的」** ⇒ 是**另一条通道**,与手机本体无关
> 3. 本机 `tablet_title_bar_mode = null`(⇒ 默认 0 = 自动),
>    但传感器找不到时 `mDecorCaptionOperatingModeBySensor` 恒为 `-1`
>    ⇒ 消费端 `getCaptionTouchMode() != 1` ⇒ **固定落到「鼠标模式」**(标题栏在顶)
>
> **⇒ 结论**:在有线场景下**这条链路根本不激活**,`+HRM` 是它的可能性**大幅下调**。
> **原始判定保留在上方,不删除** —— 以防日后无线场景需要回溯。

**修正后的候选:**

| 候选 | 支持 | 反对 |
|---|---|---|
| **A · 三路霍尔的原始 ADC** ★**现为首选** | ① 正好 3 个值 = `+HALL` 的 3 路;② **数字/模拟自洽** —— 取阈值 ~50:`0→0, 298→1, 59→1` **恰好复现 `+HALL=0,1,1`**;③ `AT+CHECK` 是**自检**命令,与 GPIO 检查同批输出很自然;④ 模长无需 =1 g,与实测无矛盾 | 「HRM」这个缩写解释不通 |
| B · 加速度计 x/y/z | ~~`getExtendScreenAccelSensor` 悬案~~(**理由已失效,见上方勘误**) | **量值对不上** —— 静止加速度计模长**必须** ≈1 g,实测 `√(0²+298²+59²) = 303.8` 在常见标度下只有 **0.037~0.304 g** |

### ★★★ 2026-09-11 任务 I 补充:**`+HRM` 第 2 字段已确定 = 电池温度**

**两组合独立样本**(字段 2 vs `+BATCG` 第 5 字段):

| 来源 | `+BATCG` | `+HRM` | 字段2 − 字段5 |
|---|---|---|---|
| 本机实测(`at_batch_result.txt`) | `4144,95,2,-1351,`**`303`**`,2` | `0,`**`298`**`,59` | **−5(0.5 °C)** |
| 社区样本(`03-battery-serial-protocol.md`) | `3855,60,2,-929,`**`275`**`,2` | `0,`**`276`**`,1004` | **+1(0.1 °C)** |

**⇒ 判定:`+HRM` 字段 2 = 电池温度,与 `+BATCG` 第 5 字段同源。**
**⇒ 同时确认 `+BATCG` 第 5 字段就是温度(0.1 °C)** —— 与 `AT+TEMP` 的 `tmp2=304` 也吻合。

**⇒ 加速度计假说被数据本身判死**(两组模长 `303.8` vs `1041.2`,**相差 3.4 倍**;
静止加速度计模长必须恒 ≈1 g)—— **这与用户「有线用不到加速度计」的指正相互独立、结论一致。**
**⇒ 候选 A(霍尔原始值)仍不成立** —— 因为字段 2 已被温度占掉,只剩字段 3 一个未知量。

**⇒ 修正后的解读**:`+HRM = <状态码恒0>, <电池温度 0.1°C>, <某路未知 ADC>`
字段 3(实测 `59` vs 社区 `1004`)语义**未定**。

**判定实验(零风险)**:同一帧连跑 `AT+CHECK` + `AT+TEMP` + `AT+BATCG`,
看 `+HRM` 字段 2 是否恒等于电池温度;并**开合键盘盖**观察**字段 3** 是否响应。

> #### ✅ 前半已实测完成（2026-09-15）
>
> **字段 2 = 电池温度** —— ✅ **已用同帧连续 15 组样本复核**
> （`+HRM` 字段 2 与 `+BATCG` 字段 5 逐对相差 ≤ 0.1 °C）。
> 完整数据 → [01 §7.4](01-TNT-GO硬件基础.md)。
>
> #### ⚠️★ 字段 3：**由「未知 ADC」改判为「疑似计数器」**
>
> 同批样本抓到一个此前没有的现象：
>
> ```
> 18:22:59  +HRM=0,335,1255
> 18:23:29  +HRM=0,335,1257     ← +2
> 18:23:59  +HRM=0,336,1259     ← +2
>    ⋮          （连续 14 次，每次恰好 +2，跨 7 分钟）
> 18:29:59  +HRM=0,337,1283     ← +2
> ```
>
> **一个静态物理量的 ADC 不可能连续 7 分钟精确线性递增。**
> ⇒ 本字段**性质是「计数器」而非「ADC 采样值」** —— 原「某路未知 ADC」的说法**据此修正**。
>
> ⚠️ **仍未确定**：按**时间**递增 还是 按**读取次数**递增
> （本批轮询间隔恰好固定 30 s，**两者无法区分**）。
> ★ **零风险判别**：相隔 ~2 s 连发两次 `AT+CHECK` —— 仍 `+2` ⇒ 按命令次数；不变 ⇒ 按时间。
> **未做**（串口被产品 mod 占着）。
>
> ★ 这也**顺带削弱了候选 A（三路霍尔原始 ADC）**：霍尔是静态量，其 ADC 不会这样计数。

### 6.3 电池健康度到底有没有? → ✅ **已裁定(2026-09-11 任务 I)**
**分层结论**:

| 层 | 结论 |
|---|---|
| **AT 命令层** | ✅ **确认没有** —— 129 条 + 追加实测,没有能直读「健康度 / 循环次数 / 设计容量」的命令 |
| **芯片层** | ❌ **不等于没有** —— 电量计 **`BQ27Z561`**(⚠️ **不是 `BQ27561`,是漏了 "Z",已更正**)的标准命令空间里**直接就有**:<br>**`StateOfHealth()` = `0x2E/0x2F`(%)**、**`CycleCount()` = `0x2A/0x2B`**、**`DesignCapacity()` = `0x3C/0x3D`**<br>**且 SEALED 状态下即可读,无需解封、无需 Control() 子命令** |
| **可及性** | MCU 固件**没开对应的 AT 口**把它们导出来 |

**⇒ 不是「结案」而是「换入口」**:**`AT+BQ25970` 已证明 MCU 具备「寄存器直读窗口」能力**
(它能 dump 另一颗芯片的全部 47 个寄存器)⇒ `AT+BQ27561=?` 一类值得按纪律探
(⚠️ **先用非法参数问语法** —— `=参数` 可能是**写**语义,见 §1 铁律)。

**完整证据与被推翻的中间结论** → **[08-TNTGO电池芯片规格与寄存器.md](08-TNTGO电池芯片规格与寄存器.md) §4**

若仍不可及,**健康度只能沿用 #26 的能量积分估算(≈97%)**。

---

## 7. 复刻要点(对小米端)

- 若要在小米端复刻 TNT GO 的**副屏电量显示** ⇒ 只需 `AT+BATCG` 一条命令 + 广播注入,无需 root
- **充电策略**:`AT+BQ25970` 的寄存器里若含充电电压/电流限制 ⇒ 是可参考的原厂设定(见 §6.1)
- **键盘盖/铰链**:`AT+HALL` 是三路数字霍尔,可用于「合盖熄屏」类逻辑
