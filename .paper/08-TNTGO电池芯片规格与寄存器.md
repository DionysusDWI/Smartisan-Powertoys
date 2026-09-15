# 08 · TNT GO 电池芯片规格与寄存器解码

> **版本**:v1.0 ｜ **更新**:2026-09-11 ｜ **状态**:三颗芯片全部识别;**BQ25970 的 47 寄存器已用实测值完成解码并交叉验证**
> 变更记录见 [CHANGELOG.md](CHANGELOG.md)
> 来源:① TI 官方 TRM / 手册(**BQ27Z561 与 BQ2589x 是官方来源**);
> ② **厂商 GPL 内核源码**(BQ25970 —— TI 公开手册被截断,无寄存器表);
> ③ **真机实测**(`AT+BQ` / `AT+BQ25970` 的 47 值)
> 原始资料归档:[`.ref/Search-Results/raw/bq_datasheets_20260911.md`](../.ref/Search-Results/raw/bq_datasheets_20260911.md)(993 行,含逐字引文与哈希校验)
> 相关:[01 §7 电量](01-TNT-GO硬件基础.md) · [06 §5/§6](06-TNTGO-AT命令全集.md) · 计划书 [I](plans/I-BQ寄存器解读与健康度结案.md)

---

## 0. 一句话结论

**TNT GO 用三颗 TI 芯片管电池 —— 其中电量计 `BQ27Z561` 的
`StateOfHealth()`(0x2E)/ `CycleCount()`(0x2A)/ `DesignCapacity()`(0x3C)
就在标准命令空间里、可直接读、无需解封。
⇒ 健康度数据【存在且可读】,只是 MCU 固件没开对应的 AT 口。**

---

## 1. 三颗芯片的身份

`at+bq` 回三行,每行是**该芯片的器件 ID**(不是一个寄存器值):

| 命令输出 | 芯片 | ID 含义 | 来源 |
|---|---|---|---|
| `+BQ27561=0x6115` | **BQ27Z561**(电量计) | `0x1561` 的**字节交换** = `DEVICE_TYPE_BQ27Z561` | 厂商内核常量 |
| `+BQ2589X=0x03` | **BQ25890**(充电管理) | `REG14[5:3] = PN[2:0] = 011 = 3` | TI 官方手册 ✅ |
| `+BQ2597X=0x10` | **BQ25970**(开关电容充电泵) | `REG13` 的实测值 = **`0x10`** ✅ | 见 §2.3 交叉验证 |

### ★★ 型号更正:不存在 `BQ27561`,是 `BQ27Z561` 漏了 "Z"

- `ti.com/product/BQ27561` → **HTTP 404**(datasheet 同理)
- Linux 内核 `bq27xxx_i2c_id_table[]`(33 个型号)**无 bq27561**
- `0x6115` 的来源已解开:厂商内核 `#define DEVICE_TYPE_BQ27Z561 0x1561`,
  读取代码 `device_type = (d[3] << 8) | d[2]` ⇒ `d[2]=0x61, d[3]=0x15` ⇒ **反序拼出 `0x6115`**
- 旁证:同一头文件里 `BQ28Z610 = 0xFFA5` 与 `ZY0603 = 0xA5FF` 是同一芯片的两种字节序

> ⚠️ `0x1561` / `0x6115` 是**厂商内核**来源,不是 TI 文档;TI TRM SLUUBO7 全文检索**零命中**。

---

## 2. BQ25970(开关电容充电泵,8A,单节)

> TI 官方描述:*"I2C Controlled Single Cell High Efficiency 8-A Switched Cap Fast Chargers With ADC"*

### 2.1 寄存器映射:**0x00–0x2E 直线映射,共 47 个,无空洞**

**★ 无任何间接访问** —— 驱动直接用 `i2c_smbus_read_byte_data(client, reg)`,
寄存器偏移**直接当 SMBus command byte**;检索 `indirect`/`bank`/`page`/`pointer` **零命中**。

**⚠️ ADC 是大端**:线上第一个字节是**高位**,驱动显式交换
(`t = v & 0xFF; t <<= 8; t |= (v >> 8) & 0xFF`)。

```
0x00 BAT_OVP            0x01 BAT_OVP_ALM        0x02 BAT_OCP            0x03 BAT_OCP_ALM
0x04 BAT_UCP_ALM        0x05 AC_OVP / VDROP_*    0x06 VBUS_PD_EN/BUS_OVP 0x07 BUS_OVP_ALM
0x08 BUS_OCP/IBUS_UCP   0x09 BUS_OCP_ALM        0x0A TSHUT/VBUS_ERROR   0x0B REG_RST/FSW_SET/WATCHDOG
0x0C ★主控制            0x0D ALM_STAT           0x0E ALM_FLAG           0x0F ALM_MASK
0x10 FLT_STAT          0x11 FLT_FLAG           0x12 FLT_MASK           0x13 ★DEV_ID
0x14 ADC_CONTROL       0x15 ADC_SCAN_DISABLE   0x16 IBUS_ADC_MSB       0x17 IBUS_ADC_LSB
0x18 VBUS_ADC_MSB      0x19 VBUS_ADC_LSB       0x1A VAC_ADC_MSB        0x1B VAC_ADC_LSB
0x1C VOUT_ADC_MSB      0x1D VOUT_ADC_LSB       0x1E VBAT_ADC_MSB       0x1F VBAT_ADC_LSB
0x20 IBAT_ADC_MSB      0x21 IBAT_ADC_LSB       0x22 TSBUS_ADC_MSB      0x23 TSBUS_ADC_LSB
0x24 TSBAT_ADC_MSB     0x25 TSBAT_ADC_LSB      0x26 TDIE_ADC_MSB       0x27 TDIE_ADC_LSB
0x28 TSBUS_FLT1        0x29 TSBAT_FLT0          0x2A TDIE_ALM           0x2B SS_TIMEOUT/EN_REG/…
0x2C IBAT_REG/VBAT_REG 0x2D *_REG_ACTIVE_FLAG   0x2E VBUS_ERR_LOW_DG/IBUS_LOW_DG
```

**⚠️ 不可与 BQ25980 互套偏移** —— 两芯片从 `0x05` 起就分叉:
BQ25980 有 59 个寄存器(到 `0x3A`)、主控制在 `0x05`、器件 ID 在 `0x22`、ADC 从 `0x25` 起。

### 2.2 关键位域

```c
// 0x0C 主控制
CHG_EN      = 0x80   // 1 = 使能充电
MS          = 0x60   // bit[6:5]  0=standalone, 1=slave, 2=master
FREQ_SHIFT  = 0x18   // 0=nom, 1=+10%, 2=-10%, 3=spread spectrum
TSBUS_DIS   = 0x04   // ⚠️ 0 = ENABLE(反逻辑)
TSBAT_DIS   = 0x02   // ⚠️ 0 = ENABLE(反逻辑)
TDIE_DIS    = 0x01   // ⚠️ 0 = ENABLE(反逻辑)

// 0x0B 软复位 / 开关频率 / 看门狗
REG_RST     = 0x80   // 1 = 软复位
FSW_SET     = 0x70   // 0:187.5k 1:250k 2:300k 3:375k 4:500k 5:750k Hz
WATCHDOG_DIS= 0x04
WATCHDOG    = 0x03   // 0:0.5s 1:1s 2:5s 3:30s
```

**ADC 取值**(15 bit:`MSB[6:0]` 为高 7 位,`MSB[7]` 为符号位):
`value = (MSB & 0x7F) << 8 | LSB`,通道序 `IBUS,VBUS,VAC,VOUT,VBAT,IBAT,TSBUS,TSBAT,TDIE`。

### 2.3 ★★★ 实测 47 值 —— 已完整解码,并**两处交叉验证通过**

**原始数据**:`at+bq25970` @ 2026-09-11 13:56(`.ref/probe_raw/tntgo_serial_probe.txt` L40–134)

| 寄存器 | 实测 | **解码** |
|---|---|---|
| `0x0B` | `0x44` | `REG_RST=0` / **FSW_SET=4 → 500 kHz** / `WATCHDOG_DIS=1`(**看门狗已禁**) |
| **`0x0C`** | **`0x02`** | **`CHG_EN=0` ⇒ 充电泵未使能** / `MS=0` standalone / `FREQ_SHIFT=0` nom / `TSBUS_DIS=0`(使能) / **`TSBAT_DIS=1`(禁用)** / `TDIE_DIS=0`(使能) |
| **`0x13`** | **`0x10`** | **器件 ID** —— ✅ **与 `at+bq` 的 `+BQ2597X=0x10` 完全一致** |
| `0x14` | `0x80` | `ADC_EN(bit7)=1` ⇒ **ADC 已开** |
| `0x15` | `0x22` | ADC 扫描屏蔽字(**位定义未取得**) |
| `0x16–0x19` | `0x00` | IBUS / VBUS = **0** |
| `0x1A–0x1D` | `0x00` | VAC / VOUT = **0** |
| **`0x1E/0x1F`** | `0x10,0x85` | **VBAT = 4229 mV** ★ |
| `0x20/0x21` | `0x00,0x0A` | IBAT = 10 mA(≈0) |
| `0x22/0x23` | `0x01,0x3A` | TSBUS = 314 |
| **`0x24/0x25`** | `0x00,0x00` | **TSBAT = 0** ← 见下方验证 ② |
| `0x26/0x27` | `0x00,0x34` | TDIE = 52 |

#### ★★ 交叉验证 ①(跨芯片):**VBAT(4229 mV) ↔ `+BATCG`(4237/4235/4234 mV)**

同一时刻,`BQ25970` 的 ADC 与**电量计 `BQ27Z561`** 经 `+BATCG` 报出的电压
**相差 ≤8 mV(0.2%)** —— 两条**完全独立**的读取路径互证。

#### ★★ 交叉验证 ②(自洽):**`TSBAT_ADC = 0` ↔ `REG_0C` 的 `TSBAT_DIS=1`

`0x0C` 里明明把 TSBAT 通道**禁用了**,`0x24/0x25` 就**恰好读回 0**
—— 寄存器之间的因果自洽。

#### 交叉验证 ③(自洽):**`CHG_EN=0` ↔ `IBAT ≈ 0`**

充电泵未使能 ⇒ 电池电流**不流经开关电容** ⇒ `IBAT` 只有 10 mA 的本底
(真实电流约 −1.3 A 走的是 `BQ2589X` 那条充电通路)。

> **⇒ 以上三点合起来,足以判定这份 47 寄存器 dump 确实是 BQ25970,且映射解读正确。**

#### ★★ 交叉验证 ④(充电态对照,OP 实测,2026-09-11 15:50)

**条件**:TNT GO **正在充电**(`+BATCG` → `current=+3138mA`、`state=1`、`mV=4276`、`level=81%`)
**结果:47 个里有 11 个变了**

| REG | 未充电 13:56 | **充电中 15:50** | 解码对应 |
|---|---|---|---|
| `0A` | `0x00` | `0x20` | `TSHUT/VBUS_ERROR` |
| `0D` | `0x02` | `0x06` | `ALM_STAT` |
| `11` | `0x80` | `0x00` | `FLT_FLAG` |
| **`18`/`19`** | `0x00`,`0x00` | **`0x20`,`0x26`** | **VBUS ADC = 8230** |
| **`1A`/`1B`** | `0x00`,`0x00` | **`0x20`,`0x32`** | **VAC ADC = 8242** |
| `1F` | `0x85` | `0xC8` | VBAT LSB |
| `21` | `0x0A` | `0x08` | IBAT LSB |
| `23` | `0x3A` | `0x30` | TSBUS LSB |
| `27` | `0x34` | `0x3D` | TDIE LSB |

**★★★ 三条印证(全部支持 §2.3 的解码)**:

| # | 印证 |
|---|---|
| ① | **`REG_18~1B` 从全 `0x00` 变成有值** ⇒ **确证它们是 ADC 数据寄存器,不是「保留区」** —— 未充电时充电泵不看 VBUS/VAC,所以读 0 |
| ② | **`REG_1E/1F` 的 VBAT**:`0x1085`=**4229** → `0x10C8`=**4296**(充电 +67 mV)⇒ **双点验证成立** |
| ③ | **`REG_0C` 仍是 `0x02`** ⇒ **`CHG_EN` 仍为 0** —— 充电走的是 **BQ25890**,**BQ25970 全程闲置** |

> **⇒ 推论(对小米端充电策略复刻很重要)**:TNT GO 的**日常充电由 `BQ25890` 完成**,
> **`BQ25970` 这颗 8A 充电泵在本机根本没有被启用**。
> ⇒ 所以即便拿不到 BQ25970 的实时数据,**也不影响充电行为**;
> 真正要找的是 **`BQ25890` 的 `VREG`/`ICHG`/`IINLIM`** —— 而 `AT+BQ25890` **两次复测均无响应** ❌(仍是死路)。

> ⚠️ **未取得**:BQ25970 的**复位值、I2C 从机地址、ADC 物理单位**
> —— 任何来源(含 TI 公开手册)都没有。**不要假设** BQ25960 的地址(0x65/0x66/0x67)适用。
> 「ADC 单位是 mV」是**由 VBAT 交叉验证反推的推断**,不是文档结论。

### 2.4 来源与置信度(重要)

| 项 | 状态 |
|---|---|
| TI 官方寄存器表 | ❌ **不存在** —— `bq25970.pdf`(SLUSD72B)四个 URL(含中文镜像)**全是截断版**,寄存器表被截掉 |
| 本表来源 | **厂商 GPL 内核源码** —— 小米 POCO X3 NFC `surya` / 小米平板 6 `pipa` 内核头文件 |
| 双源校验 | ✅ 小米内核版 与 独立作者的 Windows 驱动版:**寄存器集合相同、归一化后 22,507 字节内容完全相同、MD5 一致** |

---

## 3. BQ25890(充电管理)

来源:**TI 官方手册**(SLUSC86D 等,已落盘)+ Linux 内核 `bq25890_charger.c` 位域,**逐位交叉核对一致**。

**★ 器件识别就是 `REG14`**:
`PN[2:0] = REG14[5:3]`,`DEV_REV[1:0] = REG14[1:0]`

| 器件 | I2C 7 位地址 | PN[2:0] | DEV_REV | 最大充电电流 |
|---|---|---|---|---|
| **BQ25890** | `6AH` | **`011` = 3** ✅ | `01` | 5 A |
| BQ25892 | `6BH` | `000` | — | 5 A |
| BQ25895 | `6AH` | `111` | — | 5 A |
| BQ25896 | `6BH` | `000` | `10` | 3 A |
| BQ25898 | `6BH` | `000` | `01` | 4 A |
| BQ25898D | `6AH` | `010` | `01` | 4 A |

**⇒ `+BQ2589X=0x03` 与 `PN=3`(BQ25890)完全吻合** ✅
—— 也解释了为什么内核常量是 `BQ25890_ID 3`。

**寄存器表 0x00–0x14**(15 个;超出 `0x14` 的读**无定义**),
关键项:

| 地址 | 名称 | 要点 |
|---|---|---|
| `0x00` | IINLIM | 输入限流:偏 100 mA,步 50 mA |
| `0x02` | ADC/输入检测控制 | `HVDCP_EN`/`MAXC_EN` **仅 BQ25890 有** |
| `0x04` | ICHG | **充电电流**:0–5056 mA,步 64 mA |
| `0x06` | VREG | **充电电压**:3840–4608 mV,步 16 mV |
| `0x07` | 终止/看门狗/定时器 | `WATCHDOG`:**00=禁用** / 01=40s / 10=80s / 11=160s |
| `0x0B` | **状态**(只读) | `VBUS_STAT`:001=SDP 010=CDP 011=DCP 100=MaxCharge 111=OTG;`CHRG_STAT`:01=预充 10=快充 11=完成 |
| `0x0C` | **故障**(只读) | 需**读两次**才清 |
| `0x0E` | BATV ADC | 偏 2.304 V,步 20 mV |
| `0x14` | **器件识别** | `PN[2:0]` / `DEV_REV[1:0]`,`REG_RST` |

> ⚠️ 手册自相矛盾一处:`REG00[6] EN_ILIM` 正文标 default=1,Figure 复位行为 0 —— **原样保留。**

---

## 4. ★★★ BQ27Z561(电量计)—— 健康度数据的藏身处

> **来源:TI 官方 TRM `SLUUBO7`(BQ27Z561 Technical Reference Manual)第 13.1 节 Table 13-1**
> —— 这份是**官方文档**,置信度高于 §2 的 BQ25970(那个只有厂商内核来源)。

**标准命令均为 2 字节命令码对**(LSB 在低地址)。

### 4.1 ★ 直接回答「电池健康度到底有没有」

| 指标 | **地址** | 单位 | 是否需要解封 |
|---|---|---|---|
| **StateOfHealth()** | **`0x2E / 0x2F`** | % | **❌ 不需要** ✅ |
| **CycleCount()** | **`0x2A / 0x2B`** | 次 | **❌ 不需要** ✅ |
| **DesignCapacity()** | **`0x3C / 0x3D`** | mAh | **❌ 不需要**(SEALED 下也可读) ✅ |
| FullChargeCapacity() | `0x12 / 0x13` | mAh | ❌ 不需要 |
| RemainingCapacity() | `0x10 / 0x11` | mAh | ❌ 不需要 |
| Temperature() | `0x06 / 0x07` | 0.1 K | ❌ 不需要 |
| Voltage() | `0x08 / 0x09` | mV | ❌ 不需要 |
| Current() | `0x0C / 0x0D` | mA(有符号) | ❌ 不需要 |
| BatteryStatus() | `0x0A / 0x0B` | 位域 | ❌ 不需要 |

> **★ 只有「数据闪存(DF)参数」和 MAC 子命令**才需要走 `AltManufacturerAccess()`(`0x3E/0x3F`)。
> **SOH / CycleCount / DesignCapacity 不在其列。**
>
> `StateOfHealth()` 的定义(TRM 逐字):**25 °C 下按 SOH Load Rate 模拟的预测 FCC ÷ DesignCapacity(),0–100%**。

### 4.2 ⇒ 对 OP 那个问题的**分层最终裁定**

| 层 | 结论 |
|---|---|
| **AT 命令层** | ✅ **OP 对** —— 129 条 + 追加实测,确实**没有**能直读健康度/循环次数/设计容量的命令 |
| **芯片层** | ❌ **不等于没有** —— 数据就在 `BQ27Z561` 的标准命令空间(`0x2E`/`0x2A`/`0x3C`),**直接可读、无需解封** |
| **可及性** | **MCU 固件没开对应的 AT 口**把这些导出来 |

**⇒ 不是「结案」,而是「换入口」**:`AT+BQ25970` 已证明 **MCU 具备寄存器直读窗口的能力**
(它能 dump 另一颗芯片的 47 个寄存器)⇒ **`AT+BQ27561=?` 之类值得按纪律探**。

> ### ❌ **2026-09-11 实测结论:该入口探过了 —— 不通**
>
> OP 按纪律用**非法参数**探语法:发 `AT+BQ27561=?`
> ⇒ 响应与**裸发完全相同**(`+BQ27561=0x6115 / +BQ2589X=0x03 / +BQ2597X=0x10`)
> ⇒ **MCU 完全忽略该参数,它是固定查询** ⇒ **BQ27Z561 没有寄存器直读窗口。**
>
> | 层 | 结论 |
> |---|---|
> | AT 命令层 | ❌ 无命令 |
> | **MCU 寄存器窗口** | ❌ **`AT+BQ27561` 忽略参数(实测)** |
> | 芯片层 | ✅ 数据在 `BQ27Z561`(`SOH`=0x2E / `CycleCount`=0x2A / `DesignCapacity`=0x3C) |
> | **可及性** | ❌ **本机无任何通路** |
>
> **⇒ 最终裁定:健康度只能沿用 #26 的能量积分估算(≈97%),并【标注为「估算」而非「实测」。】**

> ⚠️ **`AT+XXX=参数` 极可能是「写」语义**(`AT+DPDEBUG` 报错原文 `use AT+DPDEBUG=0~2,reg,val`)
> ⇒ **探测一律先用非法参数问语法,不要猜寄存器号。**（这一次正是靠这条纪律**零风险**地拿到了否定结论）

---

## 5. 对小米端(充电策略复刻)的意义

| 项 | 可复刻内容 |
|---|---|
| **充电电压/电流设定** | `BQ25890` 的 `VREG`(0x06,3840–4608 mV 步 16 mV)与 `ICHG`(0x04,步 64 mA)是**原厂设定值** —— 但**需要真机读一次**才知道设成了多少 |
| **输入限流** | `IINLIM`(0x00),偏 100 mA 步 50 mA |
| **充电泵配置** | `BQ25970` 的 `FSW_SET`(实测 **500 kHz**)、`MS` 模式(实测 **standalone**) |
| **看门狗** | `BQ25970` 实测**已禁**;`BQ25890` 的 `WATCHDOG` 需真机读 |
| **健康度** | `BQ27Z561` 的 SOH / CycleCount / DesignCapacity —— 若 AT 口打不开,**只能沿用 #26 的能量积分估算(≈97%)** |

> ⚠️ 本机 TNT GO 的充电泵处于 **`CHG_EN=0`(未使能)** 状态 —— 即**当时未在充电**。
> **寄存器值随充/放电状态变化 ⇒ 要拿「充电中」的设定,必须在充电时再 dump 一次。**

---

## 6. 未确认项

- **BQ25970**:复位值、I2C 从机地址、ADC 物理单位 —— **无任何来源**
- **BQ25970**:`0x15 ADC_SCAN_DISABLE = 0x22` 的位定义未取得
  (**推断**:这是 IBUS/VBUS/VAC/VOUT 读回 0 的原因 —— 扫描被屏蔽,而非电压真为 0)
- **BQ25970**:`0x1B`–`0x2E` 中部分寄存器的完整位域未逐字取得(仅顶级名)
- **BQ27Z561**:`0x1561` / `0x6115` 仅有厂商内核来源,TI 文档中无
- **`+BATCG` 字段 5 的确认**:现已有两样本支持它是**电池温度(0.1 °C)**,见 [06 §6.2](06-TNTGO-AT命令全集.md)
- **`+HRM` 第 3 字段**(实测 `59` 与社区样本 `1004`):含义未定
- BQ25971 是否具备 `0x2B`/`0x2C`/`0x2E`;`bq25980.pdf` 亦被截断
  (但 BQ25960 的 78 页手册完整,可覆盖同布局)
