# 搜索结果库 · 总索引

> **用途**:把网上搜到的资料沉淀成**本地可检索**的文档,避免重复抓取同一个页面。
> **位置**:`.ref/Search-Results/`
> **维护规则**:先查本索引 → 再决定是否要搜;搜到新内容**追加到对应主题文件**,不要新建重复文件。

> ## ⚠️ 公开镜像说明:`raw/` 子目录**不在本仓库里**
>
> 本目录下的 `01`–`10` 各主题 `.md` 是**我们自己整理的结论**（含 URL 与提取出的事实），
> **已经入库**。而 `raw/` 里是**第三方文档原件** ——
> 芯片数据手册 PDF、AOSP 源码摘录、厂商内核驱动等。
>
> **它们被排除有两个理由**：
> ① ★ **版权**：那是别人的原件，本项目无权重分发（`raw/` 里的 `.txt` 多是从 PDF 抽出的正文）；
> ② **体积**：单是数据手册就有约 28 MB。
>
> ⇒ **下文凡是指向 `raw/...` 的链接，在公开镜像里都会是断链** ——
> 这是**已知且有意**的，不是仓库损坏。
> 需要原件时，请按各主题 `.md` 里记录的**原始 URL** 从发布方获取。

---

## 使用方式

```bash
# 1) 搜索前先查索引:某个 URL 或主题是否已经搜过
grep -i "关键词" .ref/Search-Results/INDEX.md

# 2) 全库全文检索
grep -ril "关键词" .ref/Search-Results/

# 3) 只看某个主题
cat .ref/Search-Results/01-tnt-go-hardware.md
```

---

## 主题分类

| # | 文件 | 覆盖范围 | 状态 |
|---|---|---|---|
| 01 | [01-tnt-go-hardware.md](01-tnt-go-hardware.md) | TNT GO 硬件规格、USB 复合结构、PD/DP 协议、拆解资料、串口 `+HRM=` 调试行 | ✅ **已填充**(真机实测 + §7.5 `+HRM=` 调研) |
| ★ **09** | [**09-performance-tuning.md**](09-performance-tuning.md) | ★ **性能调度侦察（任务 AK）** —— CPU 三簇频率表 / **可达性扫描（哪些节点 shell 能读能写）** / 温控 50 zone+26 cooling / **QTI perf HAL 完整解码** / Smartisan 游戏模式键 / **无 root 杠杆清单** | ✅ **已填充**（**全部本机实测**，2026-09-13） |
| ★ **10** | [**10-storage-trim-op.md**](10-storage-trim-op.md) | ★ **存储 TRIM 与 OP 检查** —— `/data` 带 `discard`（在线 TRIM 已启用）/ **57% 空闲 ⇒ OP 充足** / 顺序写 248 MB/s / ★ **冷启动"两边都没打满"的完整证据链** / 带缓存 `dd` 读数的陷阱 | ✅ **已填充**（本机实测，2026-09-13） |
| 02 | [02-smartisan-tnt-system.md](02-smartisan-tnt-system.md) | TNT 桌面机制、revone 架构、窗口管理、系统服务 | 待填充 |
| 03 | [03-smartisan-native-apps.md](03-smartisan-native-apps.md) | 闪念胶囊 / 大爆炸 / OneStep / Sara 等原生应用的社区复刻与移植 | 待填充 |
| 04 | [04-android-desktop-mode.md](04-android-desktop-mode.md) | Android 桌面模式、自由窗口、多屏、DeX/ReadyFor 类实现 | ✅ **已填充**(AOSP A16 源码核实 5 条) |
| 05 | [05-shizuku-and-shell.md](05-shizuku-and-shell.md) | Shizuku 能力边界、adb shell 编排、**权限模型** | ✅ **已归档**(2026-09-12) —— ★ **只能为自己请求；shell 读不到别的 app 的 data ⇒「替 mod 代授」不可能** |
| 06 | [06-usb-and-display.md](06-usb-and-display.md) | USB-C / PD / DP Alt Mode / 外接显示 / UVDM | 待填充 |
| 07 | [07-toolchain-and-tools.md](07-toolchain-and-tools.md) | 逆向与构建工具链(apktool/jadx/smali/androguard 等) | 待填充 |
| 08 | [08-realtime-asr.md](08-realtime-asr.md) | **实时语音识别云服务**（SiliconFlow vs DashScope） | ✅ **已归档**(2026-09-12) —— ★ **DashScope `qwen3-asr-flash-realtime` 有真流式，OpenAI Realtime 协议，已端到端跑通**；SiliconFlow **无流式端点** |

---

## ★ 已覆盖 URL 清单(搜索前先核对,避免重复抓取)

> 抓取日期指首次成功获取内容的日期。**标记 ⛔ 的为不可达,不要重试。**

> ★ **2026-09-12 更正**：`raw.githubusercontent.com` **实测 HTTP 200 可达**（`curl` 直连，无代理）。
> 此前"不可达"的记录已过时。**`shizuku.rikka.app/guide/dev/` 是 404**（文档站只有 `/`）。

### 产品资料 / 硬件规格

| URL | 主题 | 抓取日期 | 归档位置 |
|---|---|---|---|
| ★ `21ic.com/article/880108.html` | ★ **坚果 TNT go 屏幕素质测评**（**392 nit / 820:1 / 86.4% sRGB 覆盖**） | 2026-09-13 | [01 §1.1](01-tnt-go-hardware.md) |
| ⛔ `panelook.cn` / `panelook.com` | 屏库：`P120ZDG-BF4` 参数页（**URL 有效但被滑块验证墙挡住**） | 2026-09-13 | ⛔ **不要重试**（`请拖动滑块完成验证后继续访问`） |
| ⛔ `souping.com`（搜屏） | 面板参数站 | 2026-09-13 | ⛔ **SSL 握手失败** |
| `smartisan.com` 商城「坚果TNT-技术规格」 | 厂商官方规格页 | ⛔ **取不到正文**（返回 1326 字节空壳） | — |
| `yesky.com` 天极网 参数页 | TNT go(无线版) 参数 | ⛔ **HTTP 405 Not Allowed** | — |
| `baike.baidu.com` 扩展本词条 | 百度百科 | ⛔ **只返回 17 字节**（JS 渲染） | — |
| `baidu.com/s?wd=…` | ★ **可用**：百度网页搜索**能返回结果标题**（但**外层是 302 跳转链**，真实 URL 要解 `Location`） | 2026-09-13 | 本条 |

> ★★★ **2026-09-13 的教训**：**面板参数最终不是从网上查到的，是从设备自己的 EDID 里解出来的。**
> `dmesg | grep "SINK EDID"` 有**整块 EDID 原始字节** —— 比任何二手参数页都权威。
> 见 [01 §1.2](01-tnt-go-hardware.md) 与 [`scripts/decode_edid.py`](../../scripts/decode_edid.py)，
> 结论：**面板 `P120ZDG-BF4` / 8bit / ≈99.2% sRGB / EDID 扩展块 = 0 ⇒ 不支持 HDR**。

### ⚠️ 2026-09-13 搜索引擎状态（**下次别再白试**）

| 通道 | 状态 |
|---|---|
| ★ **SearxNG（`localhost:8080`）** | ⚠️ **实例在线但引擎全挂**：`brave`＝Suspended(too many requests)／`duckduckgo`＝CAPTCHA／`google`＝Suspended(CAPTCHA)／`startpage`＝Suspended(CAPTCHA)／**只有 `wikipedia` 可用** |
| ★★ **`web_search` 工具** | ⛔ **本会话不可用** —— 它的 endpoint 被配到了 `http://localhost:8080//messages`，**指到了 SearxNG 上**（而 SearxNG 不是 Messages API）⇒ 必报 HTTP 404。<br>**修法**：设置 → 插件 → Plugin configuration → Web search 改 Endpoint（或用 `DEEPSEEK_SEARCH_BASE_URL`） |
| ★ **`bing.com/search`** | 🟡 HTTP 200 有内容，但 `<h2><a>` 结构**匹配不到**（改版） |
| `html.duckduckgo.com/html/` | ⛔ HTTP 202 空壳（CAPTCHA） |
| ★★ **`baidu.com/s?wd=`** | ✅ **唯一实测可用的检索入口**（标题可解析；`<h3><a href>` 拿到的是**跳转链**） |

> ★ **解百度跳转链的方法**（本次实测）：对 `http://www.baidu.com/link?url=…` 发
> `-MaximumRedirection 0` 的请求，读 **302 的 `Location`** 头 —— 本次据此解出
> `www.21ic.com/article/880108.html`。

### 社区 / 开源项目

| URL | 主题 | 抓取日期 | 归档位置 |
|---|---|---|---|
| `github.com/SmartisanTech/android` | 锤子官方开源(BigBang / OneStep / Smartisan SDK) | 2026-09-10 | 03 |
| `github.com/whd-1999/flash-capsule` | 闪念胶囊开源复刻(**⚠️ 无 LICENSE,仅净室参考**) | 2026-09-10 | 03 |
| `github.com/mik3y/usb-serial-for-android` | Android USB 串口库 | 2026-09-10 | 01 |
| `github.com/RikkaApps/Shizuku` | Shizuku 总览 / 开发指南入口 | 2026-09-12 | 05 |
| ★ `github.com/RikkaApps/Shizuku-API` | ★ **Shizuku 开发指南**（权限模型 / UserService） | 2026-09-12 | 05 |
| `github.com/rkRk/Shizuku` | ~~Shizuku(adb 权限编排)~~ → 已并入上面两条 | — | 05 |

### 云服务 API（**直接问 API 自己**，不走搜索引擎）

| 端点 | 主题 | 抓取日期 | 归档位置 |
|---|---|---|---|
| ★ `wss://dashscope.aliyuncs.com/api-ws/v1/realtime` | ★ **DashScope 实时 ASR**（OpenAI Realtime 协议） | 2026-09-12 | 08 |
| `api.siliconflow.cn/v1/models` ｜ `/v1/audio/transcriptions` | SiliconFlow 模型与 ASR（**无流式**） | 2026-09-12 | 08 |
| `github.com/aosp-mirror/platform_frameworks_base` | AOSP 框架源码镜像 | 2026-09-10 | 04 |
| `bitbucket.org/JesusFreke/smali` | baksmali / smali | 2026-09-10 | 07 |
| `github.com/ibotpeaches/Apktool` | apktool | 2026-09-10 | 07 |
| `github.com/skylot/jadx` | jadx | 2026-09-10 | 07 |

### 官方文档

| URL | 主题 | 抓取日期 | 归档位置 |
|---|---|---|---|
| `developer.android.com` | Android 官方文档 | ⛔ **不可达(超时)** | — |
| `dl.google.com/android/repository/repository2-3.xml` | Android SDK 包清单 | 2026-09-10 | 07 |
| `services.gradle.org/distributions/` | Gradle 发行版 | 2026-09-10 | 07 |
| `api.adoptium.net` | Temurin JDK | 2026-09-10 | 07 |
| `pypi.org/pypi/<pkg>/json` | Python 包元数据 | 2026-09-10 | 07 |

### ★ AOSP A16 源码原文(2026-09-11,任务 F 核实用)

> 抓取方式:`cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@android-16.0.0_r1/<path>`
> **原始文件已落盘** `.ref/Search-Results/raw/aosp16_*.java`(6 个 / 1.7 MB),**结论归档在 [04](04-android-desktop-mode.md)**

| 文件 | 存为 | 关键结论 |
|---|---|---|
| `core/java/android/provider/Settings.java` | `aosp16_Settings.java` | freeform/desktop 全部 `Settings.Global` 键的**常量名 ↔ 实际值**;`DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT` 的值 = **`enable_freeform_support`** |
| `services/core/java/com/android/server/am/ActivityManagerShellCommand.java` | `aosp16_ActivityManagerShellCommand.java` | `am stack` 在 A16 **无 resize**;`am task` 有 `lock/resizeable/resize/focus`;`am display move-stack` 是 AOSP 原生命令 |
| `services/core/java/com/android/server/wm/ActivityTaskManagerService.java` | `aosp16_ActivityTaskManagerService.java` | `resizeTask()` 的 `canResizeTask()` **静默失败守卫** |
| `core/java/android/app/WindowConfiguration.java` | `aosp16_WindowConfiguration.java` | `canResizeTask()` = `FREEFORM \| MULTI_WINDOW` |
| `services/core/java/com/android/server/input/InputShellCommand.java` | `aosp16_InputShellCommand.java` | `input -d DISPLAY_ID`;支持 `text/keyevent/tap/swipe/draganddrop/press/roll` |
| `services/core/java/com/android/server/wm/Task.java` | `aosp16_Task.java` | `Task.resize()`(备用核对) |

### ★ TI BQ 电池管理 IC 寄存器表(2026-09-11,硬件逆向用)

> **原始资料落盘** [`.ref/Search-Results/raw/bq_datasheets_20260911.md`](raw/bq_datasheets_20260911.md)
> 抓取方式:`curl` 直连 `ti.com` / `cdn.jsdelivr.net/gh/torvalds/linux@master/...`
> **结论**:BQ2589x 全寄存器表(0x00–0x14)已核实;TI 官方 TRM `SLUUBO7` 含 BQ27Z561 完整标准命令表;
> **BQ25970 完整寄存器表(0x00–0x2E,47 个)已从厂商内核源码取得并双源校验**(TI 官方手册确实被截断为 13 页,该否定结论仍成立)

| 资料 | 主题 / 关键结论 | 抓取日期 | 归档位置 |
|---|---|---|---|
| `ti.com/lit/ds/symlink/bq25890.pdf` | **TI官方** BQ25890/92 手册 SLUSC86D(69 页,含完整寄存器表 + 复位值) | 2026-09-11 | raw §1 |
| `ti.com/lit/ds/symlink/bq25895.pdf` | **TI官方** BQ25895(65 页)。I2C=6AH,REG14 PN=`111` | 2026-09-11 | raw §1 |
| `ti.com/lit/ds/symlink/bq25896.pdf` | **TI官方** BQ25896 SLUSC76C(65 页)。I2C=6BH,PN=`000`,DEV_REV=`10` | 2026-09-11 | raw §1 |
| `ti.com/lit/ds/symlink/bq25898.pdf` | **TI官方** BQ25898/98D SLUSCA6B(69 页)。PN=`000`/`010`(D) | 2026-09-11 | raw §1 |
| `ti.com/lit/pdf/SLUUBO7` | **TI官方** ★ **BQ27Z561 Technical Reference Manual(91 页)**,含完整 Standard Commands 表(0x00–0x6E)与全部 MAC 子命令 | 2026-09-11 | raw §2.3 |
| `ti.com/lit/pdf/SLUUC54` | **TI官方** BQ27Z561-R2 / BQ27Z558 TRM(123 页) | 2026-09-11 | raw §4 |
| `ti.com/lit/pdf/SLUUBU0` | **TI官方** BQ27Z561EVM-011 用户指南(24 页) | 2026-09-11 | raw §4 |
| `cdn.jsdelivr.net/gh/torvalds/linux@master/drivers/power/supply/bq25890_charger.c` | **Linux内核** BQ25890 regmap 位域定义(权威位域来源) | 2026-09-11 | raw §1.2 |
| `cdn.jsdelivr.net/gh/torvalds/linux@master/drivers/power/supply/bq25980_charger.{c,h}` | **Linux内核** BQ25960/975/980 驱动;寄存器 **0x00–0x3A 直线映射,无间接访问** | 2026-09-11 | raw §3.1 |
| `cdn.jsdelivr.net/gh/torvalds/linux@master/drivers/power/supply/bq27xxx_battery.c` | **Linux内核** bq27xxx 通用电量计驱动(含 33 型号 I2C 表,**无 bq27561**) | 2026-09-11 | raw §2.1 |
| `cdn.jsdelivr.net/gh/pjgowtham/android_kernel_oneplus_sm8450@bf96b52/.../oplus_hal_bq27z561.{h,c}` | **厂商内核(OPPO/OnePlus)** ★ 定义 `DEVICE_TYPE_BQ27Z561 = 0x1561`。**`0x6115` = `0x1561` 的字节交换** | 2026-09-11 | raw §2.2 |
| `ti.com/lit/ds/symlink/bq25970.pdf` | ⚠️ **TI官方但被截断**:SLUSD72B 仅 **13 页,无寄存器表** | 2026-09-11 | raw §3 |
| `cdn.jsdelivr.net/gh/stormbreaker-project/linux-xiaomi-surya@main/drivers/power/supply/ti/bq25970_reg.h` | **厂商内核(小米 POCO X3)** ★ **BQ25970 完整寄存器表 0x00–0x2E,47 个,直线映射无间接访问** | 2026-09-11 | raw §3.2 |
| `cdn.jsdelivr.net/gh/YumeMichi/kernel_xiaomi_pipa@calcite/drivers/power/supply/ti/bq25970_reg.h` | **厂商内核(小米平板 6)** 同表超集版,含 `BQ25970_DEVICE_ID 0x10` 等常量 | 2026-09-11 | raw §3.2 |
| `cdn.jsdelivr.net/gh/WaLoVayu/bq2597x_win@main/include/registers.h` | **第三方(Windows 驱动)** ★ 与小米内核版**逐字相同** → 交叉验证 | 2026-09-11 | raw §3.2 |
| `ti.com/lit/pdf/sluaa33` / `ti.com.cn/cn/lit/pdf/zhcabu1` | **TI官方** BQ25970 应用笔记 —— ⚠️ **无寄存器内容** | 2026-09-11 | raw §3.2 |
| `ti.com/product/BQ27561` | ❌ **HTTP 404 —— 该型号不存在**(疑为 BQ27Z561 漏写 Z 的笔误) | 2026-09-11 | raw §2.1 |

> **器件级结论速查**
> - **BQ25970** = 单节(1-cell)、开关电容 charge pump、8 A、DSBGA-56、带 12-bit ADC、支持外部 OVP FET(仅 25970)。寄存器 **0x00–0x2E 直线映射,47 个,无间接访问**;`0x13[3:0]` = DEV_ID;`0x0C` = 主控制寄存器
> - ⚠️ **BQ25970 与 BQ25980 是两个不同的映射** —— 从 0x05 起分叉,不可互套偏移。BQ25980/975/960 才是 0x00–0x3A
> - **BQ25890 族** = 5 A(25896 为 3 A)开关式 buck 充电器 + NVDC 电源路径,寄存器 **0x00–0x14**,`REG14[5:3]` = PN,`REG14[1:0]` = DEV_REV
> - **BQ27Z561** 标准命令空间直读:`CycleCount()`=**0x2A/0x2B**、`StateOfHealth()`=**0x2E/0x2F**、`DesignCapacity()`=**0x3C/0x3D**、`FullChargeCapacity()`=**0x12/0x13** —— **均在标准命令空间,无需 Control() 子命令或解封**
>
> ⚠️ **BQ25970 的复位值、I2C 从机地址、ADC 物理单位仍未核实**(任何来源都没有;详见 raw §3.2.7)。

### ★ TNT GO 串口 `+HRM=` 调试行(2026-09-11,否定性结论)

> **结论归档在 [01](01-tnt-go-hardware.md) §7.5**。摘要:**开源固件中查无 `+HRM=` 逐字命中**;
> `+HRM` 不在固件 129 条 AT 命令里 ⇒ 很可能是**独立异步推送帧**,非 `AT+CHECK` 的响应。
> ⚠️ **重要修正**:`+HRM` 并非恒定 —— 存在第二组样本 `0,276,1004`(另有 `0,298,59`),
> 字段 3 跳变 17 倍 ⇒ **否掉加速度计假说**。字段 2 疑为温度(0.1 ℃),字段 3 疑为一路近饱和的 10-bit ADC。

| URL | 主题 | 抓取日期 | 归档位置 |
|---|---|---|---|
| `github.com/electrie00/TNTgo-Boom` `serialread.py` | 社区 TNT GO 电量串口读取项目(校准实验命中) | 2026-09-11 | 01 §7.5 |
| `github.com/SkYFly2233/TNTGO-battery` `main.py` | 同上(校准实验命中) | 2026-09-11 | 01 §7.5 |
| `github.com/atc1441/D6Emulator` `D6Emulator.ino` | 心率手环模拟器,含 `AT+HRMONITOR=`;证明 `AT+<TAG>=` 可作自定义调试命令 | 2026-09-11 | 01 §7.5 |
| `github.com/Tomiwa-Ot/SM-A217F_forensics` `at commands/AP.md` | 三星 AT 命令集,`AT+HRM*` 实为**射频校准**家族 | 2026-09-11 | 01 §7.5 |

### ★ GitHub API code search 通道(2026-09-11 新发现)

```bash
curl -sS "https://gh-proxy.com/https://api.github.com/search/code?q=QUERY&per_page=15" \
  -H "Accept: application/vnd.github+json"
```
- ✅ **可用**,能搜全 GitHub 代码(比 SearxNG 的 `github` 引擎强得多)
- ⚠️ **限流 5000/小时,且所有工具/代理共享** —— 批量使用时务必节流

### 行不可达(勿重试)

| URL / 域名 | 症状 |
|---|---|
| `raw.githubusercontent.com` | 连接重置 |
| `developer.android.com` | 超时 |
| `google.com` 系(google / duckduckgo / brave / startpage / baidu 搜索) | SearxNG 报 CAPTCHA |
| **WebFetch 工具(全部域名)** | 一律返回 `Unable to verify if domain is safe` —— **完全不可用,勿尝试** |
| `github.com/search`(网页版代码搜索) | 需登录,未登录不可用 → 改用上面的 **gh-proxy + GitHub API** |
| SearxNG `quark` / `360search` 引擎 | 对英文技术查询**返回完全无关的中文结果**;`bing` 对冷门长查询也会返回无关兜底结果 → 冷门查询建议直接 `curl` 已知 URL |
| `ti.com/product/BQ27561`、`bq27541`、`bq27421`、`bq25898` 的 `/lit/ds/symlink/*.pdf` | HTTP 404(旧型号已下架;新产品页正常) |
| `cdn.jsdelivr.net/gh/{o}/{r}@HEAD/README.md` | **301 跳转到被封的 `raw.githubusercontent.com`** → 改用 GitHub API `/repos/{o}/{r}/readme`(返回 base64) |
| `grep.app/api/search` | Vercel Security Checkpoint 拦截 |
| `searchcode.com/api/codesearch_I/` | 404(API 已下线) |
| `search.gitee.com`、`sourcegraph.com`、`elixir.bootlin.com`、`kkgithub.com`、`bgithub.xyz` | 分别:301 无结果 / 防火墙 / Anubis PoW 墙 / 证书过期 / 不可达 |

> 替代方案见 [Windows 网络与搜索通道](../../docs/) 及 memory。GitHub raw 用 `cdn.jsdelivr.net/gh/` 或 `gh-proxy.com` 前缀。

---

## 待归档(研究中)

当前有 3 路调研进行中,完成后其来源将并入对应主题文件:
TNT GO 硬件逆向、开源社区参考、小米端工程栈。

> **02(TNT 系统结构)** 的来源以**本机反编译**为主,不是网络抓取 ——
> 详见 [`.paper/02`](../../.paper/02-TNT系统结构规格.md) 与 `handover/TNT_Handover/12_反编译参考/`。
