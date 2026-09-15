# 01 · TNT GO 硬件 / USB / 显示协议

> 归档日期:2026-09-10
> 来源:① 真机 dmesg/内核日志实测;② 手机侧交接包 `07_TNTGo复刻规格/`;③ 网络检索

---

## 1. 设备身份

| 项 | 值 |
|---|---|
| 型号 | Smartisan TNT GO(deltainno) |
| 发布 | 2020-10-20,随坚果 R2 |
| USB VID/PID | `0x31CE` / `0x5101`(12750 / 20737) |
| Manufacturer / Product | `deltainno` / `Smartisan TNT go` |
| Serial | 形如 `207238A74152` |
| **内部代号** | **`boston`** ← 由 framework-res 受保护广播反推 |
| 原生分辨率 | 2160×1440 @ 60fps,24bpp,4 lane DP,10LR |
| ★ **面板型号** | ★★★ **`P120ZDG-BF4`**(群创 Innolux)—— **2026-09-13 从 EDID 解出,见 §1.2** |
| 面板 mfg_id | `INX` |
| 面板密度 | 216 dpi(1dp = 1.35px) |
| 屏幕尺寸 | 12 英寸(EDID 实测 254×169 mm ⇒ 对角线 **11.90"**) |
| ★ **面板色深** | **8 bit/基色 / 16.7M**(EDID **+ 屏库规格双证**)—— ★ **不是 10 bit** |
| ★ **色域** | 屏库：**sRGB 88% / DCI-P3 71% / NTSC 68% / AdobeRGB 68% / Rec.2020 51% 覆盖率**<br/>★ 本工作区从 EDID 独立算得 **sRGB 88.0% / DCI-P3 72.1%** ⇒ **精确吻合**(§1.3) |
| ★★ **最大亮度** | ★ **400 cd/m² (Typ.)**（屏库规格）＋ **实测 392 nit**（第三方）⇒ **两源吻合 98%** |
| ★ **对比度** | **1000:1 (Typ.)**（规格）／ **820:1**（第三方实测，整机值通常更低） |
| ★ **背光** | **WLED `9S6P` 6 串【侧入式】**，25.65/28.8 V，**3.42/3.65 W** ⇒ ★ **无分区控光** |
| ★ **面板接口** | **eDP 4 Lanes / eDP 1.3**（`20655-040E-01`）⇒ ★ **TNT GO 内有 DP→eDP 桥**（Analogix `1f29:0000`） |
| 响应时间 | **25 ms (Tr+Td)** —— ★ 慢，办公够用 |
| 白点 / 色温 | **Wx 0.300 / Wy 0.315** ／ **7435 K**（★ 本工作区从 EDID 独立算得 ≈7505 K，吻合） |
| ★★ **HDR** | ❌ **不支持** —— **EDID 扩展块 = 0** ＋ **8 bit** ＋ **400 nit** ＋ **侧入式无分区**（四重否定，§1.3 ⑤） |

### 1.1 ★ 屏幕素质实测数据(2026-09-13 网络检索,来源:21ic 测评)

> **来源**:<https://www.21ic.com/article/880108.html>(《坚果 Smartisan TNT go 扩展本屏幕素质测评》)
> **抓取日期**:2026-09-13 ｜ **数据性质**:第三方**实测**(非厂商标称) ｜ **可信度**:中(单一来源,**未做第二源交叉验证**)
> ⚠️ **厂商官方参数页没抓到** —— `smartisan.com` 商城页与天极网参数页都取不到正文(JS 渲染 / HTTP 405)。

**原文引用(逐字)**:

> 「虽然 TNT go 有着 **106% 的 sRGB 色域容积**,不过仅有 **86.4% 的 sRGB 色域覆盖值**,
> 　考虑到有更差的 45% NTSC 色域的屏幕存在,只能说 TNT go 的色域属于**良好的水准**,
> 　要是能够达到 100% sRGB 色域覆盖就更好了」

> 「**最大亮度可以达到 392 nit**,不过**对比度稍低,仅有 820 : 1**」

**判读**:

| 项 | 值 | 评价 |
|---|---|---|
| **亮度** | **392 nit** | 便携屏里算**良好**。★ 对本项目有意义:**这解释了为什么 2000 档（最亮）和 1000 档差异明显可感** |
| **色域** | 106% 容积 / **86.4% 覆盖** | "良好"但不专业级 —— 覆盖不满 100% sRGB |
| **对比度** | **820:1** | ★ **偏低**(IPS 常见 1000:1 起);测评也这么判 |

> ⚠️ **仍未查到**:**是否 8bit/FRC**、**响应时间**、**触控方案**、**官方标称亮度**。
> ⚠️ **厂商官方参数页取不到**（smartisan 商城页返回 1326 字节空壳 / 天极网 **HTTP 405** / 百度百科只返回 17 字节）。

### 1.2 ★★★★★ EDID 完整解码（2026-09-13）—— **面板型号 + 色深 + HDR 三个问题一次结清**

#### 怎么拿到 EDID 的（★ 这条方法本身可复用）

| 手段 | 结果 |
|---|---|
| `/sys/class/drm/card0-DP-1/edid` | ⛔ **Permission denied**（SELinux `u:r:shell:s0` 对 `sysfs` 无 read；**要 root**） |
| ★ `dumpsys SurfaceFlinger` | ✅ **能拿到面板型号**：`Display 10652974438123012 (HWC display 1): port=4 pnpId=INX displayName="P120ZDG-BF4"` |
| ★★★ **`dmesg \| grep "SINK EDID"`** | ✅✅ **内核把整块 EDID 的 128 字节原样打出来了** ⇒ **这才是拿到原始字节的路** |

```bash
adb shell 'dmesg | grep "SINK EDID"' > edid_raw.txt
python scripts/decode_edid.py edid_raw.txt      # ★ 新写的解码器（任务 AI 副产）
```

#### 解码结果（[`scripts/decode_edid.py`](../../scripts/decode_edid.py)，原文 `20260913_edid_decoded.txt`）

```
厂商            : INX (群创)          产品码: 0x7801        生产: 2020 年 第 25 周
EDID 版本       : 1.4
输入            : 数字 / DisplayPort / ★ 每基色 8 bit
物理尺寸        : 25 cm × 17 cm  ⇒ 对角线 11.90 英寸
Gamma           : 2.20
特性位(byte24)  : 0x02  [sRGB默认=False  首选时序=原生=True]
色度 R(0.6396,0.3525)  G(0.3350,0.6191)  B(0.1553,0.0469)  W(0.2988,0.3154)
★ 色域面积      : 0.11113 vs sRGB 0.11205  ⇒ 约 99.2% sRGB（≈71% NTSC）
描述符 #1       : ★ 2160×1440 @ 206.02 MHz（blank 160×40，画面 254×169 mm）
描述符 #4       : ★ 显示器名称 = "P120ZDG-BF4"
★★ EDID 扩展块数量 : 0
```

#### ★★★ 结论 ①：**不支持 HDR**（EDID 层面就没申报）

| 证据 | |
|---|---|
| ★★★ **扩展块数 = 0** | **完全没有 CTA-861 扩展块** ⇒ **不可能存在 HDR Static Metadata Data Block** |
| **8 bit/基色** | HDR10 要 10 bit；DP 链路实测也是 `24bpp`（=8bit RGB） |
| **色域 ≈99.2% sRGB** | **标准色域**，不是广色域（与 §1.1 第三方"86.4% 覆盖"互相印证） |
| **只有一个时序** | 2160×1440@60，没有任何 HDR 相关模式 |
| 实测 392 nit / 820:1 | 与 HDR10 的门槛（高亮度 + 高对比）差得远 |

#### ★★★★ 结论 ②：**系统报的「HDR10/HLG + 500 nit」是平台默认回落值，不是屏的能力**

任务 AI 里给 mod 加了个探针（`Display.getHdrCapabilities()`），读出：

```
· id=4「HDMI 屏幕」(TNT GO)   HDR=true  类型=[HDR10/HLG]   广色域=false
    EDID 申报亮度(nit)：峰值=500.0   平均上限=250.0   黑场=0.0（未申报）
· id=0「内置屏幕」(手机自己)   HDR=true  类型=[HDR10/HLG]   广色域=false
    EDID 申报亮度(nit)：峰值=500.0   平均上限=500.0   黑场=0.0（未申报）
```

**⇒ 差点被它骗过去。两条独立的反证：**

| # | 反证 |
|---|---|
| **1** | ★★★ **`500.0` 和 `250.0` 在 CTA-861 的编码里【根本无法表示】** —— 该块用 `50 · 2^(code/32)` cd/m²，<br/>解 `50·2^(c/32)=500` 得 `c≈106.3`，**不是整数** ⇒ **不可能来自真实 EDID** ⇒ **必是硬编码默认值** |
| **2** | ★ **两块完全不同的面板（手机 AMOLED + TNT GO 的 INX）报的峰值是同一个 `500.0`**，黑场都是 `0.0`（未申报） |

> ★★ **方法论教训（本项目第 N 次同型）**：
> **「系统说它支持 X」≠「硬件真的支持 X」。**
> 平台的**默认回落值**会让一个**根本不存在的 HDR 能力**看起来板上钉钉。
> **⇒ 凡是能力类结论，要追到【申报方】的原始字节（这里就是 EDID 的 byte 126）。**

#### ★★ 结论 ③：**尼特范围在软件里查不到**

| 来源 | 有没有尼特数据 |
|---|---|
| **EDID** | ❌ **base block 不含亮度**；亮度只在 CTA HDR 块里，而它**不存在** |
| **表面数值（HdrCapabilities）** | ❌ 是平台默认值（见上） |
| **AT 命令台** | ❌ 只有 `AT+BKL` 的控制值 `9~2000` —— ★ **那是控制量,不是尼特** |
| ★ **第三方实测** | ✅ **最大 392 nit**（§1.1，单一来源，**未交叉验证**） |
| **最小亮度** | ❌ **软件拿不到** —— 要照度计/色度计实测 |

> ★ 若 820:1 对比度成立，**黑场 ≈ 392/820 ≈ 0.48 nit** ——
> ⚠️ 但那是**黑场**，**不是"最低白点亮度"**，两者别混。

### 1.3 ★★★★★ 屏库(panelook)数据手册对质 —— **三个独立来源互相验证**（2026-09-13）

> **来源**：用户提供的**屏库 `P120ZDG-BF4` 参数页截图**（2026-09-13）
> ⚠️ 网页本身抓不到（滑块验证墙），**截图是唯一入口**；数据性质 = **面板厂规格（Typ. 值）**

#### ① ★★★ 交叉验证表 —— **吻合得出奇地好**

| 项 | **屏库数据手册** | **本工作区独立来源** | 吻合 |
|---|---|---|---|
| **显示亮度** | **400 cd/m² (Typ.)** | §1.1 第三方**实测 392 nit** | ✅ **98%** |
| **支持颜色** | **16.7M (8-bit)** | §1.2 EDID `byte20` ⇒ **8 bit/基色** | ✅✅ |
| ★ **sRGB** | **88% 覆盖率**(CIE1931) | §1.2 从 EDID 三原色算 **88.0%** | ✅✅✅ **精确命中** |
| **DCI-P3** | **71% 覆盖率** | 同上算 **72.1%** | ✅ 差 1% |
| **NTSC** | **68%**(CIE1931) | 同上：面积 70% ／ 覆盖 66% | ✅ 落在中间 |
| **白点** | **Wx:0.300  Wy:0.315** | EDID 解出 **W(0.2988, 0.3154)** | ✅✅ |
| **色温** | **7435 K** | McCamy 近似算 **≈7505 K** | ✅ 差 1% |
| 对比度 | **1000:1 (Typ.)** | 第三方实测 **820:1** | 🟡 82% —— **整机实测通常低于面板标称**，合理 |
| 面板类型 | **IPS，常黑，透射式** | 广视角 | ✅ |

> ★★ **方法论价值**：**「我从设备原始字节推出来的」与「厂商规格书」对上了 —— 这同时证明了三件事**：
> ① 我的 EDID 解码**正确**；② 屏库这份规格**确实是这台机器用的面板**；
> ③ **EDID 里报的就是面板自己的参数**（桥接芯片把面板 EDID 透传了）。

#### ② ★★ 数据手册答了、也答不了的（**"亮度范围"这题的真相**）

| 问题 | 数据手册怎么说 |
|---|---|
| **最大亮度** | ✅ **400 cd/m² (Typ.)** —— ★ 但这是**典型值单点，不是"最大值"** |
| **亮度"范围"** | ❌ **面板规格不提供** —— 可调下限由**背光驱动**决定，规格书里没有 |
| **调光是否 PWM / 频率** | ❌ 未提供 |

**⇒ 结论：`AT+BKL` 的 `9~2000` 是【控制量】，它的尼特对应关系在**任何软件里都查不到**；
　但上限现在**可信地锚定在 ≈400 nit**（数据手册 400 Typ. + 实测 392，两个独立来源）。**

#### ③ ★★★ 两条**新的结构性事实**（数据手册独有）

| # | 事实 | 意义 |
|---|---|---|
| **1** | ★★ **面板原生接口 = `eDP (4 Lanes), eDP 1.3`**（接口型号 `20655-040E-01`）<br/>而**手机侧走的是 DP Alt Mode**（内核 `[drm-dp]`） | ⇒ ★ **TNT GO 内部必有一颗 DP→eDP 桥接芯片** ——<br/>就是 USB 树里那颗 **Analogix「USB Type-C Digital AV Adapter」`1f29:0000`**<br/>⇒ **我们读到的 EDID 是【桥】报的**（桥的固件把面板型号/参数写了进去）<br/>⇒ ★ 也解释了**为什么调亮度走 AT 命令台而不是标准 DP 亮度控制** |
| **2** | ★★ **背光 = WLED，`9S6P`，6 串，【侧入式光源】**，灯管 25.65/28.8 V、**3.42/3.65 W** | ★ **侧入式 ⇒ 没有分区控光（FALD）** ⇒ **HDR 的第三条路也堵死了**<br/>（9 串 × ~2.85 V = 25.65 V ✓，6 并 ⇒ 共 54 颗 LED） |

#### ④ 其余规格（留档）

```
帧频率        60Hz          翻转扫描  No        驱动 IC  COG 已邦定 TC2016
信号电压      3.3V (Typ.)   电流      320/350mA (Typ./Max.)
可视角度      80/80/80/80 (Typ.)(CR≥10)          推荐视角  全视角
响应时间      25 (Typ.)(Tr+Td) ms                ← ★ 慢，办公够用
亮度均匀度    1.25/1.33 (Typ./Max.)(9 points)    ← 侧入式典型（角落最暗约为中心的 75%）
Adobe RGB     68% 覆盖率      Rec.2020  51% 覆盖率
```

#### ⑤ ★★★★ HDR 结论 —— **三重否定，现在有面板规格直接佐证**

| 要件 | HDR10 需要 | 本面板 | 判 |
|---|---|---|---|
| **色深** | 10 bit | **8 bit**（数据手册 + EDID **双证**） | ❌ |
| **峰值亮度** | 高（DisplayHDR 400 是**最低档**，且公认"不算真 HDR"） | **400 nit Typ.** | ❌ |
| **对比度 / 控光** | 高对比 或 **分区控光** | **1000:1，侧入式 WLED，无分区** | ❌ |
| **接口申报** | EDID 里要有 CTA-861 HDR 静态元数据块 | ★ **EDID 扩展块 = 0**（§1.2） | ❌ |

**⇒ 四条全部不满足。这块屏是【标准 SDR 办公/影音屏】，不支持 HDR。**
（系统报的 `HDR10/HLG + 500 nit` 是平台默认回落值，§1.2 已用两条反证否掉。）

#### ⑥ ★★ 用户 2026-09-13 的定性：「能支持 HDR 信号，但最多是一块支持 HDR 信号的屏」

> 用户原话：「**亮度范围我们暂时不管了**，这块屏**能支持 HDR 信号**，但其亮度估计也就
> 在 **400-500 nit** 左右，其**最多是一块支持 HDR 信号的屏幕**」

★ **这个定性里有一半是已证的、一半目前【没有证据】。分开记，别整句当定论：**

| 分句 | 判定 | 依据 |
|---|---|---|
| **亮度 ≈400 nit** | ✅ **已证（两源吻合）** | 屏库 **400 cd/m² (Typ.)** ＋ 第三方实测 **392 nit** |
| ⚠️ **"到 500 nit"** | ❌ **没有支撑** —— ★ **500 这个数极可能来自那个【平台默认值】** | §1.2：`HdrCapabilities` 报的 `500.0` 在 CTA-861 编码里**根本无法表示**，是硬编码默认值 |
| ★★ **"能支持 HDR 信号"** | ⚠️ **本工作区【没有证据】支持，且现有证据倾向否定** | 见下 |
| ★ **"最多是一块支持 HDR 信号的屏幕"** | ✅ **这个结论方向是对的** —— 无论如何都**不是真 HDR** | §1.3 ⑤ 的四重否定 |

**为什么说"能支持 HDR 信号"目前没证据（三条都是反向的）：**

| # | 反向证据 |
|---|---|
| **1** | ★★ **EDID 扩展块 = 0** ⇒ 接收端**没有向源端申报 HDR**（CTA-861 HDR 静态元数据块不存在）<br/>⇒ ★ **合规的源端不会给它发 HDR** |
| **2** | ★ **EDID `byte20` 声明 8 bit/基色** ⇒ 内核按此把 DP 链路配成 **`24bpp`**<br/>⇒ ★ **接收端自己说"我只吃 8 bit"**，10-bit HDR 信号不在此列 |
| **3** | ★ **DPCD 里报的是 `DP 1.1`**（`SINK DPCD: 11 0a 84 …`，`0x0000 = 0x11`）<br/>⇒ DP 侧 HDR 元数据的标准化传输（CTA-861.3 / DP 1.4）**在 1.1 上根本没有** |

> ★★★ **但有一条【反向的、值得警惕】的事实**：
> **平台的 `HdrCapabilities` 默认回落值说它支持 HDR10/HLG**（§1.2）——
> 而 **Android 正是拿这个值决定"要不要给这块屏发 HDR 信号"**。
> ⇒ ★★ **所以系统【有可能真的给它发 HDR10】**，而接收端并不认（8 bit + 无 HDR 申报）
> ⇒ **推测后果：HDR 内容在 TNT GO 上可能表现为颜色发灰/过曝（色调映射缺失或错误）。**
> ⚠️ **这一条是【推断，未实测】** —— 要证实得放一段 HDR10 内容再看颜色与链路状态。

**⇒ 本项目上的实用结论（不依赖上面那条推断）：**
**不要为 TNT GO 规划任何 HDR 功能**；HDR 相关的事属于**小米端**（那边才是目标平台的显示管线）。

### 1.4 ★★★★★ 实测：**手机发给 TNT GO 的到底是不是 HDR 信号？** → **不是，始终是 SDR 8-bit**

> **上游**：用户 2026-09-13「**8bit 看起来像是 sdr 信号**」＋「试着搞清楚手机向 tntgo 传输的信号是 sdr 还是完整 hdr 信号」

#### ① 先说清判据（★ **"8bit"本身不是决定性证据**）

| 指标 | 为什么 |
|---|---|
| **传输函数** | PQ/HLG ⇒ HDR；Gamma2.2/sRGB ⇒ SDR ← ★ **这才是主判据** |
| **原色** | BT.2020 ⇒ HDR 常见；BT.709/sRGB ⇒ SDR |
| **HDR 静态元数据** | 有没有 CTA-861.3 块 / 动态范围 InfoFrame |
| 链路色深 | 10bit 常见于 HDR、**但非必需**（8bit 也能标 PQ）—— ★ **只能当旁证** |

#### ② 实验方法（**造真 HDR 片源，播到 TNT 屏，看整条链路**）

```bash
# 造一段真 HDR10：HEVC Main10 / yuv420p10le / bt2020nc / smpte2084(PQ) + 母版显示元数据
ffmpeg -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=10" \
  -vf format=yuv420p10le -c:v libx265 -tag:v hvc1 \
  -x265-params "hdr-opt=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:\
master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=1000,400" \
  -pix_fmt yuv420p10le -an hdr_test.mp4
```
（成品经 `ffprobe` 确认：`profile=Main 10 / pix_fmt=yuv420p10le / color_space=bt2020nc / color_transfer=smpte2084 / color_primaries=bt2020`）

推送 → MediaStore 扫描 → **点名锤子播放器播到 TNT 屏**：
```bash
adb push hdr_test.mp4 /sdcard/Movies/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/hdr_test.mp4
adb shell am start --display 100000 -n com.smartisanos.videoplayerproject/.MainActivity \
        -a android.intent.action.VIEW -d content://media/external/video/media/<id> -t video/mp4
```
> ⚠️ 坑：`am start -a VIEW -t video/mp4` **不带 `-n` 会弹 ResolverActivity**（无默认播放器）；
> ★ **必须点名 `com.smartisanos.videoplayerproject/.MainActivity`**。

#### ③ ★★★★ 结果：**HDR 层确实出现了，但输出链路【从未变成 HDR】**

| 环节 | 观测 | 判 |
|---|---|---|
| **解码器** | `E OMX-VDEC-1080P: Extension: OMX.google.android.index.describeHDR10PlusInfo not implemented` | ★ 解码器**认出了 HDR**（在查 HDR 扩展） |
| ★★ **SurfaceFlinger 层** | `compositionengine::Layer (SurfaceView - …videoplayerproject…)`<br/>`dataspace=BT2020_ITU_PQ (298188800)`　`hdr metadata types=3` | ★★★ **这是一个真 HDR10 层**（BT.2020 原色 ＋ **ST2084/PQ** 传输函数） |
| ★★ **合成器(SDM)** | `I SDM: HWCDisplay::GetColorModeCount: Supported color mode count = 1`<br/>`I SDM: HWCDisplay::GetColorModes: Color mode = 0 is supported` | ★★ **该屏只支持 1 种 color mode（NATIVE）** —— 没有 HDR 模式可用 |
| ★★★ **DP 链路** | 播放前后 `dmesg` 里 `dp_panel_resolution_info` **仍然只有连接时那一条**：<br/>`2160(80\|48\|32\|1)x1440(27\|3\|10\|1)@60fps **24bpp** 206020Khz 10LR 4Ln`<br/>`_dp_panel_calc_tu` / `dp_ctrl_link_train` **全部时间戳都在 t≈44780s（连接那一刻）** | ★★★ **链路一次都没重配** ⇒ **输出永远是 8-bit RGB** |

```
HDR10 文件(HEVC Main10 / PQ / BT.2020)
   ↓ 解码      OMX-VDEC —— ★ 认出 HDR
   ↓ 图层      dataspace = BT2020_ITU_PQ   ← ★ 真 HDR 层
   ↓ 合成      SDM：该屏只有 1 种 color mode (NATIVE)
   ↓ 链路      24bpp (8-bit RGB) —— ★ 从连接到播放【一次都没重配】
   ↓
TNT GO
```

#### ④ ★★★★ 结论：**手机发给 TNT GO 的【始终是 SDR 8-bit 信号】**

**即使播放真 HDR10 片源，输出端也没有变成 HDR** —— HDR 内容在手机侧被**转换/色调映射成 SDR** 后才发出去。

**四层结构性原因（任何一层都足以否掉"发 HDR"）：**

| # | 原因 |
|---|---|
| **1** | **接收端没申报 HDR** —— EDID 扩展块 = 0，没有 CTA-861 HDR 静态元数据块（§1.2） |
| **2** | **接收端申报 8-bit** —— EDID `byte20` ⇒ 内核把链路配成 `24bpp`；要变 10-bit **必须重新训练链路**，而实测**没有任何链路重配** |
| **3** | **接收端是 DP 1.1**（DPCD `0x0000 = 0x11`）—— DP 侧 HDR 元数据的标准化传输是 **DP 1.4 / CTA-861.3** |
| **4** | **合成器只有一种 color mode** —— SDM 亲口报 `Color mode count = 1` |

> ★★★ **⇒ 这实测【否定了】"这块屏能支持 HDR 信号"的假设**：
> 至少在本机这条通路上，**手机根本没有向它发出过 HDR 信号**。

#### ⑤ ⚠️ 本实验的**边界**（别过度解读）

| 能证明 | 不能证明 |
|---|---|
| ✅ **信令链**会走通（解码器认 HDR → SF 层是 PQ/BT.2020 → 带 HDR 元数据） | ❌ **色调映射的质量** —— 测试片的像素是 `testsrc2`（SDR 码值）**只打了 PQ 标签**，<br/>不是光度学意义上的 HDR ⇒ **画面观感说明不了转换正确与否** |
| ✅ **输出链路不会变成 HDR**（四层结构性原因） | ❌ 若想验证"转换质量"，需要**用 `zscale` 把码值真正转成 PQ**，再与 SDR 版逐帧对比 |

> ★ **实用含义**：**在 TNT GO 上看 HDR 片源 = 走一遍 "HDR→SDR" 转换**，
> 画质不会比直接看 SDR 片源更好，**反而可能更差**（取决于转换质量）。
> ⇒ 与 §1.3 ⑥ 的结论一致：**不要为 TNT GO 规划 HDR 功能**。

---

---

## 2. USB 复合结构(真机 dmesg 实测)

**关键认知**:TNT GO **不是单一 USB 设备**,而是**内置 USB HUB 的复合体**。

```
usb 1-1    0424:2514   Microchip/SMSC USB2514  ── HUB 芯片
 ├─ usb 1-1.1  1f29:0000  Analogix "USB Type-C Digital AV Adapter"  ← DP→显示转换
 ├─ usb 1-1.2  0bda:0567  Realtek "ICT Camera"                      ← 摄像头
 └─ usb 1-1.3  31ce:5101  Smartisan TNT go (full-speed)             ← 主控
```

主控提供的接口(交接包实测):

| 接口 | class/sub/proto | 说明 |
|---|---|---|
| 0 | 1/1/0 | HID 键盘 |
| 1 | 1/2/0 | HID 鼠标/触控板 |
| 2 | 1/2/0 | 第二 HID(多点触控) |
| 3 | 3/1/2 | HID(Boot) |
| 4、5 | 3/0/0 | HID generic |
| — | **255/255/0** | **★ 厂商自定义接口**(私有控制通道) |
| — | 2/2/1 + 10/0 | CDC-ACM 串口 + CDC-Data |
| — | 6/1/1 | PTP(相机类) |
| — | 14/1、14/2 | 视频类 |
| — | 17/0 | 音频类 |
| — | 239/2/1 | IAD(复合设备标志) |

内核注册的输入设备:
```
input: deltainno Smartisan TNT go Keyboard
input: deltainno Smartisan TNT go Touchpad
input: deltainno Smartisan TNT go Wireless Radio Control
input: deltainno Smartisan TNT go Consumer Control
hid-multitouch → hidraw0 / hidraw1 / hidraw2
```

> **这解释了「键盘/声卡/摄像头可用但屏幕不亮」**:全部外设走 USB 2.0 数据链路,与 DP 无关。
> 音频在 Android 侧被识别为 **`usb_headset`**(`dumpsys audio` 实测)。

---

## 3. ★ 显示链路与黑屏根因(本次实测)

### 成功时序(历史记录)
```
typec: Type-C Sink (powered) connected
usbpd: dfp_send_uvdm                     ← 厂商自定义 PD 消息
Type-C Source (medium - 1.5A) → (high - 3.0A)   ← PD=1,协商成功
[drm-dp] hpd_high:1
[drm-dp] dp_panel_resolution_info: 2160x1440@60fps 24bpp 4Ln
[drm-dp] dp_display_post_enable: DP module is ready to transfer display data
usb 1-1.3: Product: Smartisan TNT go
```

### 故障时序
```
pm8150b_charger: smblib_update_usb_type: APSD=CDP PD=0     ← 未走 PD
Type-C SOURCE_DEFAULT detected                              ← 非 SOURCE_HIGH
（此后无 hpd_high、无新的 dp_panel_resolution_info）
```

### 根因
**线材**。普通充电线只有 4 根线芯(VBUS/GND/D+/D−),**没有 SuperSpeed 差分对、没有 DP lane**,
也无法承载 PD 协商的完整 CC 链路 → USB 2.0 功能全通,高速/显示功能全无。

### 选线规格
| 要求 | 说明 |
|---|---|
| 全功能 Full-Featured USB-C 公对公 | 必须明确标注,不能是「充电线」 |
| USB 3.2 Gen2 (10Gbps) / USB4 / 雷电 3·4 | 保证有 SS 差分对;**雷电4/USB4 兼容性最好** |
| 支持 DP Alt Mode(4 lane) | 点亮屏幕的关键 |
| 5A / 100W PD | TNT GO 宣告 Source 3A,线材需留余量 |
| 长度 | **被动全功能线超过 2m 信号劣化**;长线优先 1.5–2m 认证线或主动式 |
| 拓扑 | **禁止经过 HUB/扩展坞**(DP Alt Mode 需直连) |

> 自检工具:`scripts/tntgo_check.sh`(本工作区)

---

## 4. 供电 / PD

| 项 | 真机表现 |
|---|---|
| 角色 | TNT GO = **Source(供电方)**,手机 = Sink |
| 能力宣告 | `Type-C Source (high - 3.0A)`,协商中出现 `medium - 1.5A` |
| 协议 | `smblib_update_usb_type: APSD=UNKNOWN **PD=1**`(走 PD,非 QC) |
| **厂商 PD 消息** | **`usbpd usbpd0: dfp_send_uvdm`**(手机主动发 UVDM,真机出现 2 次) |
| 手机侧观测 | `dumpsys battery` → `AC powered: true`;输入约 4.9–5.1V / 0.3–1.2A |

> **复刻要点**:必须做 PD Source(不是 5V 直供),且**支持 UVDM 收发**。

---

## 5. 厂商控制传输协议

Android 侧:`UsbDeviceConnection.controlTransfer(requestType, request, value, index, buffer, length, timeout)`

| 方向 | requestType | request | value | index | len | 功能 |
|---|---|---|---|---|---|---|
| OUT | `0x41` | `0x30` | `0xb0` | 0 | 0 | 设屏幕亮度 |
| OUT | `0x41` | `0x64` | `0xb0` | 0 | 0 | 设待机模式 |
| OUT | `0x41` | `0x60` | `0xb0` | ? | 8 | 设 LED 状态 |
| OUT | `0x41` | `0x50` | `0xa0` | 0 | 0 | 设 Dplane 数 |
| OUT | `0x41` | `0x32` | ? | ? | 0 | 设屏幕颜色 |
| OUT | `0x41` | ? | `0xb0` | ? | 0 | 护眼模式 |
| **IN** | **`0xC1`** | **`0x51`** | 0 | **`0xa0`** | **2** | **读 DP lane 数(唯一读取)** |

`0x41` = OUT|VENDOR|INTERFACE,`0xC1` = IN|VENDOR|INTERFACE。
寄存器地址用 `value`(0xa0/0xb0)或 `index` 传递,数据走 buffer。

**实现位置**:`com.android.server.pc.TntManagerService` 的
`connectTntScreen()` / `doSetTntScreenBrightness(I)` / `doSetTntStandByMode(I)` /
`doSetTntLedState()` / `setDplane(I)` / `doSetTntScreenColor()` / `getDplaneNum()`

---

## 6. ★ 电量:真机缺失,需自行定义

**手机从不询问 TNT GO 电量** —— `smartisan-services-tnt.jar` 全库无任何 battery 字符串,
7 个 `controlTransfer` 全是写。

复刻建议(沿用 `req=0x51`,只换 `index`):
```
IN (0xC1) req=0x51 idx=0xA1 len=2  → 电量百分比 (uint16, 0–100)
IN (0xC1) req=0x51 idx=0xA2 len=2  → 电压 mV
IN (0xC1) req=0x51 idx=0xA3 len=2  → 电流 mA
IN (0xC1) req=0x51 idx=0xA4 len=2  → 温度 (0.1℃)
```
`controlTransfer` 走**端点 0**,**无需 claim interface**,不会抢走键鼠/串口。

主机侧读取(无 root 亦可):
```kotlin
val conn = usbManager.openDevice(dev) ?: return
val buf = ByteArray(2)
val ret = conn.controlTransfer(0xC1, 0x51, 0x0000, 0x00A1, buf, 2, 1000)
```

---

## 7. 接入时序(真机 4.2 秒)

```
T+0.00s  typec: Sink(powered) connected
T+0.60s  USB host(xhci) 上线 / PD 协商 + dfp_send_uvdm
T+1.50s  typec 重协商 medium→high
T+3.40s  DP HPD 拉高
T+3.57s  DP 链路协商完成 2160x1440@60 4Ln
T+3.66s  DP ready
T+3.80s  USB 枚举:TNT Go (0x31CE:0x5101)
T+4.00s  HID 输入设备注册(hidraw0-2)
T+4.2s   TntManagerService: onUsbDeviceAttached → connectTntScreen()
```

> **关键**:DP 画面链路**先于** USB 枚举就绪。

---

## 7.5 ★ 串口调试行 `+HRM=` 调研结论(2026-09-11)

> 起因:抓包中出现 `+HRM=0,298,59`。本次做了全渠道检索,**结论是校准过的否定结论**。
> 详细分析见 [`.paper/06-TNTGO-AT命令全集.md`](../../.paper/06-TNTGO-AT命令全集.md) §6.2。

### 7.5.1 已核实

1. **开源固件中查无 `+HRM=` 逐字命中。** GitHub Code Search API(经 `gh-proxy.com` 转发)
   对 `"+HRM="` 返回 37 条**全部无关**的结果(nuget 缓存、`.ppk` 私钥、`.travis.yml`、类图 `.mdj`、
   Minecraft 资源清单、邮件 mbox);`"+HRM" language:C` 的 153 条被心率传感器驱动淹没。
   **TNT GO 主控固件闭源,未泄露。**
   - **通道校准实验**:先查 `"+BATCG="` → `total_count = 2`,且两条都是真·TNT GO 项目
     (`electrie00/TNTgo-Boom`、`SkYFly2233/TNTGO-battery`)⇒ 该通道**具备精确短语匹配能力**,
     因此上面对 `+HRM=` 的零命中是**有意义的**。

2. **`+HRM` 不在 129 条 AT 命令里**(见 `.paper/06` 的 `at+help` 全量输出)⇒
   **它不是任何命令的响应**。而 `+BATCG` 已证实是**约每 15 秒的异步推送**,可插在任何响应之后
   ⇒ `+HRM` 很可能同样是一帧**独立的周期性/自检推送**,只是恰好落在 `AT+CHECK:` 与下一个 `>` 之间。

3. **本固件的两种语法约定**(与 `AT+CHECK` 一致):
   `+NAME=a,b,c` = 参数型信息响应;`+NAME: a, b, c` = 信息型响应行。

4. **`AT+HRM*` 的两处野生命中均与本设备无关**:
   - `atc1441/D6Emulator`(心率手环模拟器)有 `AT+HRMONITOR=` ⇒ `HRM` = **HR MONITOR**,
     证明 `AT+<TAG>=` 约定在 Arduino 级固件里也用于**自定义调试/透传**;设备无心率传感器 ⇒ 不适用。
   - 三星 `SM-A217F` AT 命令集有 `AT+HRMOSENS=*` / `AT+HRMTEST=0`,但同区块全是
     `AT+HRF2CALSTART` / `AT+HRFCALSTART` 等**射频校准**命令 ⇒ 该处 `HRM` 不是心率。与本设备无关。

5. ⚠️ **假阳性排除**:`bq25890/92/95/96/98.pdf` 字节级 `grep "HRM"` 有命中,但对照文本抽取版
   (`*.txt`)后 **HRM count = 0** ⇒ PDF 里的命中是**压缩流随机字节**,非真实内容。

### 7.5.2 ★ 关键数据修正:并非只有一组样本

| 来源 | `+BATCG` | `+HRM` |
|---|---|---|
| `.ref/probe_raw/at_batch_result.txt` L27–29(本机实测,充电中 95%) | `4144,95,2,-1351,303,2` | **`0,298,59`** |
| `.ref/probe_raw/tntgo_serial_probe.txt`(同机) | `4235,100,2,-1136,309,2` … | (无 HRM) |
| `projects/tntgo-battery-overlay/docs/03-battery-serial-protocol.md` L10–12 + `docs/_workflow_cache/research__usb-cdc-tntgo.json`(社区样本,放电 60%) | `3855,60,2,-929,275,2` | **`0,276,1004`** |

由此得到三条硬约束:

1. **字段 1 恒为 `0`** ⇒ 若是三轴传感器则三轴不可能同时恒为 0 ⇒ 更像**状态码/通道号**。
2. **字段 2 与 `+BATCG` 字段 5 同步**:`298 ↔ 303`、`276 ↔ 275`。而 `+BATCG` 字段 5 取值
   309/309/309/308/308/308/308 —— **缓慢漂移,典型的 0.1 °C 温度** ⇒ **字段 2 ≈ 电池包温度(0.1 °C)的重复上报**。
3. **字段 3 跨样本跳变 17 倍**(`59` → `1004`);**1004 ≈ 10-bit ADC 满量程(1023)的 98%**
   ⇒ 强烈指向**一路会接近饱和的原始 ADC 通道**(光照 or 磁场)。

**❌ 加速度计假说已可判死**:两组模长 `|(298,59)| = 303.8` vs `|(276,1004)| = 1041`,**相差 3.4 倍**;
静止加速度计的模长必须恒定 ≈1 g。

### 7.5.3 候选展开(排名为推断,非事实)

| 排名 | 展开 | 支持 | 反对 |
|---|---|---|---|
| 1 | **H**all **R**aw **M**easurement(霍尔原始 ADC) | 固件确有 `AT+HALL` → `+HALL=0,1,1`(**同为三值**);阈值化后两组样本都得到 `0,1,1`,与数字态自洽 | ⚠️ `HRM` 这个缩写**解释不通**;字段 1 恒 0 |
| 2 | 环境光 ALS 原始通道 | 1004 逼近 10-bit 满量程、59 = 弱光,教科书式 ALS 量程 | 字段 2 是温度样,与光无关;固件另有 `AT+LIGHT` |
| 3 | **H**ardware **R**eadout **M**onitor(自检读数) | 只出现在 `AT+CHECK` 自检语境 | 无法证实,属兜底解释 |
| 4 | **H**ardware **R**esource **M**anager | 嵌入式常见模块名 | 不会输出这种量纲混杂的三元组 |
| 5 | **H**igh **R**efresh **M**ode/Rate | 显示器语境直觉像 | 面板固定 60 Hz;模式标志不会产生两个连续变化的量 |
| — | Heart Rate Monitor | 是 `HRM` 在嵌入式里的**压倒性**默认含义 | **设备无心率传感器,不适用** |

> **另一种可能**:`HRM` 只是**发出该行代码的模块/结构体名**(很多固件的调试标签就是模块名)。
> 这种情况下追溯缩写在方法论上是死路,**只能靠实验定语义**。

### 7.5.4 建议的决定性实验(零风险,`AT+CHECK` 已确认是查询型)

1. **同一帧内三方对照**:连跑 `AT+CHECK` + `AT+TEMP` + `AT+BATCG`,看 `+HRM` 字段 2 是否**恒等于**
   `+BATCG` 字段 5 / `+TEMP` 的 `tmp2` ⇒ 若是,**字段 2 = 温度**直接坐实。**本步最省力、信息量最大。**
2. **遮住环境光窗 + 开/关房间灯**(键盘盖状态固定)→ 字段 3 随光照大幅变 ⇒ **ALS**。
3. **开合键盘盖 / 移动磁铁**(光照固定)→ 字段 3 随磁铁位置变 ⇒ **霍尔**。
4. **连跑 5 次 `AT+CHECK`**:字段 2/3 是否逐位稳定 ⇒ 判断是滤波后的工程量还是裸 ADC(裸 ADC 末位会抖)。
5. ~~倾斜/翻转~~ —— **已可跳过**(模长差 3.4 倍已否掉加速度计)。

### 7.5.5 本次新增的可用抓取通道

| 通道 | 状态 |
|---|---|
| `https://gh-proxy.com/https://api.github.com/search/code?q=...` | ✅ **可用,未认证也可**(~10 req/min,超限返回 `429 try again in Ns`) |
| `https://gh-proxy.com/https://api.github.com/repos/{o}/{r}/readme` | ✅ 返回 base64,可绕开被封的 raw 域(含中文路径) |
| `https://gh-proxy.com/https://api.github.com/repos/{o}/{r}/contents/{path}` | ✅ 同上 |
| `https://cdn.jsdelivr.net/gh/{o}/{r}@HEAD/{path}` | ✅ 对源码有效 |
| `https://cdn.jsdelivr.net/gh/{o}/{r}@HEAD/README.md` | ⛔ **301 跳转到 `raw.githubusercontent.com`(被封)** ⇒ 改用 API `/readme` |
| `https://grep.app/api/search?q=` | ⛔ Vercel Security Checkpoint 拦截 |
| `https://searchcode.com/api/codesearch_I/?q=` | ⛔ 404(API 已下线) |
| `https://search.gitee.com/?q=` | ⛔ 301,无结果 |
| `gh-proxy.com/https://github.com/search?q=...&type=code`(HTML 版) | ⛔ 30 s 超时 |

---

## 8. 相关开源项目

| 项目 | URL | 说明 |
|---|---|---|
| usb-serial-for-android | `github.com/mik3y/usb-serial-for-android` | Android USB 串口库;v3.5.0 起**按接口类型自动识别 CDC/ACM**,无需自定义 prober。最新 3.11.0 |
| Vosk | `alphacephei.com/vosk` | 离线 ASR(非显示相关,见 03) |

---

## 9. 待补充(研究中)

- TNT GO 内部 SoC(Amlogic S905Y2?)、RAM/存储、电池容量的**权威来源**
- 拆解报告与面板型号
- `dfp_send_uvdm` 的具体载荷格式
- Analogix `1f29:0000` / Realtek `0bda:0567` 的公开资料
- 无线版 vs 有线版的硬件差异
