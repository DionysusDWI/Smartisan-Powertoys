# 07 · TNT GO 独立亮度控制

> **版本**:**v2.2** ｜ **更新**:2026-09-13 ｜ **状态**:✅ **已打通并实机验证**（用户目视确认亮度真的变了）
> 变更记录见 [CHANGELOG.md](CHANGELOG.md)
> 来源:① CC 反编译 `TntManagerService` + `framework-res` 资源回查;
> ② **OP 反编译 `BrightnessPanel` / `BostonController`**;③ **OP + 用户真机实测**
> 相关:[01 §6 厂商控制传输](01-TNT-GO硬件基础.md) · [06 AT 命令全集](06-TNTGO-AT命令全集.md) · 计划书 [I](plans/I-BQ寄存器解读与健康度结案.md) · [AI](plans/AI-TNTGO亮度键mod.md)

---

## ✅ v2.2 裁决：量程就是 **9 ~ 2000** —— 本文件 §1/§3 的原始结论**成立**（曾一度被误判）

| 时间 | 说法 | 判定 |
|---|---|---|
| 2026-09-11 | **本文件 §1/§3**：OP 实测 + 官方公式 `calculateBrightnessMcuValue` ⇒ **9~2000** | ✅ **最终确认成立** |
| 2026-09-13 | 用户口述「`AT+BKL=1~1000` 可以切换亮度」 | 🟡 **不算错** —— 只是**试到 1000 为止**，**不是设备上限** |
| 2026-09-13 | 本 mod 量程对账（回读） | 1/100/500/1000/1500/2000 **全部原值返回** ⇒ MCU **接受**到 2000 |
| ★★★ **2026-09-13** | ★ **用户目视：「2000 明显更亮」** | ★★★ **决定性证据** |

### ★★ 中途我自己犯的一个错（**记下来，别再犯**）

一度采信"1~1000"，把出厂曲线**重映射**到 1000 ⇒
**结果 UI 100% 只给到 MCU 1000，等于把这块屏砍掉一半亮度**（相当于出厂 UI 的 ~78% 才是我们以为的"最亮"）。
用户目视确认后才纠正。

> ★★ **两条教训**：
> ① **参数只要取自官方公式，就不要"自作聪明"地缩放它** —— 缩放的动机是我以为"用户更懂这台机器"，但用户的 1~1000 是**使用范围**，不是**设备量程**。
> ② ★★★ **回读 `+BKL=<值>` 只能证明「MCU 收下了」，证明不了「灯真的变了」**（§4：背光不进 framebuffer）。
> 　⇒ **背光/面板类的验证，必须【回读】+【人眼】两条一起看，缺一不可。**

### ★ 顺带查到：这块屏的素质（[01 §1.1](../.ref/Search-Results/01-tnt-go-hardware.md)）

**392 nit 最大亮度 ／ 820:1 对比度 ／ 86.4% sRGB 覆盖**（第三方实测，单一来源）
⇒ ★ 392 nit 也解释了**为什么 1000→2000 的差异听感/观感这么明显**。

---


## 0. 一句话

**TNT GO 的背光有两条官方链路,在坚果 Pro 3 上【都是死的】;
OP 已用 CDC-ACM 的 `AT+BKL` 打通第三条路 —— 而它与官方 `casthal.setBacklight()` 同标度。**

```sh
AT+BKL          →  +BKL=<MCU值>      ← 查询
AT+BKL=<9~2000> →  +BKL=<值>         ← 设置（实测生效）
```

---

## 1. ★★★ 官方亮度公式(OP 反编译,已用实测值验证)

```java
// BrightnessPanel.setBrightness(uiBrightness, targetBrightness)
calculateBrightnessMcuValue(int b):        // b = UI 亮度 0~100
  clamp(b, 0, 100)
  r   = 0.0033·b³ − 0.1708·b² + 4.3464·b + 5.0814
  mcu = clamp((int)r, 9, 2000)             // 0x9 = 9, 0x7d0 = 2000
```

| UI | 0 | 25 | 50 | 75 | 100 |
|---|---|---|---|---|---|
| **MCU** | **9** | **58** | **207** | **762** | **2000** |

> ✅ **与 OP 的实测逐点吻合**:`AT+BKL=58` / `=207` / `=762` / `=2000` 全部读回一致。
> CC 已独立复算该多项式,**五个点全部精确命中**。

**⇒ AT 侧量程 `9~2000` 与 `calculateBrightnessMcuValue()` 的输出范围完全一致**
⇒ **`AT+BKL` 高度可能就是官方 `casthal.setBacklight()` 的等价物** ✅

---

## 2. ★★ 两条官方链路 —— 在本机【都是死的】

| 链路 | 设置键 / 入口 | 标度 | 落地通道 | 本机 |
|---|---|---|---|---|
| **① TNT 服务线** | `revone_screen_brightness`(Global,0–255) | 0–255 → **0–254** | `TntManagerService.controlTransfer(0x41,0x30,v,0xB0)` → **`29A9:9001`** | ❌ **死**(总线上无 `0x29A9`) |
| **② Boston/casthal 线** | `smartisan.boston.brightness.value`(Global,UI 0–100) | UI 0–100 → **MCU 9–2000** | `BostonController.setBrightness` → `CasthalManager.setBacklight` → **casthal HAL** | ❌ **死**(本机无 `casthal` 服务) |
| **③ AT 控制台(打通)** ★ | — | **MCU 9–2000** | `AT+BKL=<MCU值>` via **CDC-ACM** | ✅ **实测生效** |

> ### ★★★ 关键结论:`casthal` = **Boston(TNT GO)专用的 HAL**
>
> §7 电量那条勘误里写过:手机读 TNT GO 电量的生产者是 **`casthal` HAL**
> (`android.casthal.CasthalManager` / `BostonBatteryInfo`)—— 而 **`Boston` 正是 TNT GO 的内部代号**(`.paper/01` §2)。
>
> **⇒ `casthal` 同时管电量【和】背光。**
> **⇒ 在非 Smartisan 手机上(Pro 3 / 小米),`casthal` 整个缺失
> ⇒ 但它的两项功能【都能从 AT 控制台补回来】**:
>
> | casthal 的功能 | AT 替代 |
> |---|---|
> | `BostonBatteryInfo`(电量) | **`AT+BATCG`** ✅ 已闭环 |
> | `CasthalManager.setBacklight(9~2000)` | **`AT+BKL=<9~2000>`** ✅ 本轮打通 |
>
> **★ 这对小米端是决定性的**:小米 17 Pro Max 同样没有 `casthal`,
> **⇒ 用同一条 CDC-ACM 连接,就能同时补上电量和亮度** —— 不需要 root、不需要改系统。

### 2.1 链路 ① 的公式(保留,供追溯 —— 它是**另一条路**)

```java
// TntManagerService.setTntScreenBrightness   ← revone_screen_brightness（0–255）
int actual = x < 15 ? 0 : round( (x + 15)^1.8 × 0.00875 + 46 );
mConnection.controlTransfer(0x41, 0x30, actual, 0xB0, null, 0, 1000);
```
`x=185`(本机实测值) → `actual=167`。**实际下发范围 50–254。**

> ⚠️ **这条链路的标度(0–254)与 AT 侧(9–2000)【不同轴】—— 它们本来就是两条不同的路。**
> 初版 v1.0 曾猜「AT 侧可能与 50–254 同轴」—— **该猜测已被 OP 实测否定**,
> 但这**不影响**链路 ① 的解码(那是从反编译公式直接算出来的)。

**资源常量**(CC 从 smali 取真实 ID 后回查 `framework-res.apk` 解出):
`revone_config_screenBrightnessSettingDefault = 152`、`config_screenBrightnessSettingMinimum = 15`。

---

## 3. ✅ AT 命令族实测结果(OP,2026-09-11)

| 命令 | 响应 | 判读 |
|---|---|---|
| **`AT+BKL`** | `+BKL=<值>` | ✅ **查询型**(裸发安全) |
| **`AT+BKL=<v>`** | `+BKL=<v>` | ✅ **设置型,实测生效**,量程 **9~2000** |
| `AT+LIGHT` | `+LIGHT=20` | ✅ **环境光**读数 |
| `AT+SCREEN` | `+SCREEN=1` | ✅ 屏幕状态位 |
| `AT+BACKLIGHT` | **无响应** | ❌ 命令名存在但无实现 |

### ⚠️ 对 CC 初版三条推测的更正(OP 实测)

| # | CC v1.0 的推测 | **实测** |
|---|---|---|
| 1 | 「AT 侧可能与原厂 50~254 同轴」 | ❌ **实际 9~2000**(见 §2.1 —— 那是另一条链路) |
| 2 | 「裸发 0 可能灭屏且救不回,只能物理重插」 | ✅ **可恢复** —— 重设有效值即可,**无需重插** |
| 3 | 「`AT+BKL` 裸发有风险」 | ✅ **它是查询,安全** |

> **⇒ 教训**:v1.0 把「`AT+DPDIRECT` 裸发会执行」的教训**过度泛化**到了整族亮度命令。
> **正确的姿势是先查后设** —— 而 `AT+BKL` 恰好是**查询**。
> **get/set 铁律依然成立**(返回数据=查询 / 返回裸 `OK`=设置),
> 但**具体哪条命令属于哪类,只能实测,不能类推。**

> **⚠️ 另更正**:OP 早期记录的「`AT+BKL=100` → `+BKL=0`,低值被 clamp」**是错的** ——
> 真相是**输入乱序伪影**(实际发成了 `AT+BKL1=00`)⇒ **不存在低值 clamp,量程就是完整 9~2000**。

---

## 4. ★★ 重要方法论:背光变化【不会进 framebuffer】

> ### ⚠️ **截图 / 录屏永远看不出背光变化。**
>
> 背光由 MCU 独立控制,不经过手机的显示合成管线
> ⇒ `screencap` / 录屏拿到的像素**完全不含背光信息**。
>
> **⇒ 背光功能的验证只能靠两条路**:
> ① **人眼**(本轮就是靠用户目视确认「变了」)
> ② **回读 `+BKL`**(只能证明 MCU 收下了值,不能证明灯真的变了)
>
> **★ 这条推翻了 `.paper/05` 里「用截图做观测」的一类做法** ——
> 凡涉及**背光/面板**的验证,都不适用截图法。
> (注:`.paper/05` 早已记过「不要用截图哈希做观测」,那是**时钟污染**问题;这是**第二条独立原因**。)

---

## 5. 恢复手段(已实测更新)

| 手段 | 有效性 |
|---|---|
| **重设 `AT+BKL=<有效值>`** | ✅ **有效**(实测 `AT+BKL=1000` 即恢复) |
| 物理重插 USB-C | ✅ 有效(但**背光场景不需要**) |
| `settings put global revone_screen_brightness` | ❌ 无效(链路 ① 是死的) |
| `settings put global smartisan.boston.brightness.value` | ❓ **未测** —— 推测**无效**(apply 走 casthal,本机没有) |

**⇒ 背光值不持久化**(OP 实测),所以**最坏情况也可自救**。

---

## 6. 现状与下一步

| 项 | 状态 |
|---|---|
| 亮度查询/设置 | ✅ **已打通**(`AT+BKL` / `AT+BKL=<9~2000>`) |
| 官方公式 | ✅ **已解码并逐点验证** |
| OP 侧工具 | ✅ `tntgo_brightness.sh`(`tntgo_brightness` v001,sha256 `4263b164811e`) |
| **探针 APK 亮度滑块** | 🔵 **可做** —— 用 `AT+BKL=<9~2000>`,UI% 走官方公式换算 |
| 写 `smartisan.boston.brightness.value` 是否生效 | ❓ 未测(推测不能) |
| 亮度接入 TNT 任务栏 | ❓ 需一个常驻 app |

**⇒ 架构**:探针 APK 已经在用 `usb-serial-for-android` 打开同一条 CDC-ACM(路线 C 电量),
**在同一条连接上多发一条 `AT+BKL=<v>` 即可** —— 与 `SerialGuard` 互斥锁天然兼容。

---

## 7. 未确认项

- `AT+LIGHT=20`(环境光)的**单位与量程**未知
- `AT+SCREEN=1` 的**位含义**未知
- `smartisan.boston.brightness.value` 写入是否有效(未测)
- 链路 ①(`revone_screen_brightness` → `29A9:9001`)与链路 ②(`casthal` → Boston)
  是否**指向同一个物理背光** —— **推断是**,但标度不同(0–254 vs 9–2000),无法直接证实
- `AT+BKL` 是否在 MCU 侧对**不同输入**做二次映射(未逐值验证全域)
