# 03 · Smartisan 原生应用(社区复刻与移植)

> 归档日期:2026-09-10
> 来源:手机侧实测调研交接包(`handover/TNT_Handover/11_专项分析/`) + 本工作区检索

---

## 1. 官方开源(锤子)

| 项目 | URL | 内容 | 许可 |
|---|---|---|---|
| **SmartisanTech/android** | `github.com/SmartisanTech/android` | **锤子官方开源**:OneStep 全部框架/UI + Smartisan SDK,以及 **BigBang** | 官方开源 |

> **重要事实**:锤子**只开源了 BigBang 与 OneStep**。**闪念胶囊从未官方开源**。

---

## 2. 闪念胶囊(IdeaPills)复刻

| 名称 | 类型 | 说明 |
|---|---|---|
| **whd-1999/flash-capsule** | GitHub 开源 | 标注「闪念胶囊(复刻+增强)」,已迭代至 v0.18。Kotlin + Compose + Room + whisper.cpp。**⚠️ 仓库无 LICENSE → 只能净室参考(读架构、重写实现),不可复制代码** |
| **Idea Note 闪念胶囊** v3.5.8 | 商店 App(仿制) | 长按 Home 语音→转文字→生成胶囊悬浮,可转待办、拖拽到微信/日历/便签;社区公认复刻较全 |
| 红米用户自制版 | 什么值得买报道 | 已实现**长按音量键弹出闪念胶囊并开始录音**,后续补齐转文字 —— 与我们的思路一致 |
| 本地版(微信 Clawbot + TFO) | 开源/AI 组合 | 用 AI + 本地工具搭本地闪念胶囊,含语义检索/摘要 |
| iOS 快捷指令版 /「闪念」App | 跨平台 | 交互参考 |

### whd-1999/flash-capsule 技术栈(已确认)

- Kotlin **1.9.24** / AGP 8.5.2 / KSP 1.9.24-1.0.20 / compileSdk 34 / minSdk 26 / Java 17
- Compose BOM 2024.06.00、Room 2.6.1、coroutines 1.8.1
- 端上 **whisper.cpp v1.7.4**(arm64-v8a,CMake/NDK 26.3)
- **单 `:app` 模块**(ARCHITECTURE.md 里描述的多模块拆分**并未实现**)
- 侧边把手:26dp × 112dp 半透明竖条(`addView` + `gravity TOP|START`)
- **不做常驻悬浮球**(入口用语音助手/磁贴/分享)—— 与本项目需求不同

---

## 3. OneStep / 大爆炸(BigBang)

- **官方开源**(见 §1)
- 社区:有人用 AI 复刻了 OneStep 的「接住」能力;另有 `com.hyper.onestep` 等复刻版包名 —— **证明跨机型移植锤子系统功能已有先例**

---

## 4. Smartisan 应用整体移植

- **@People-11「Smartisan OS APP 移植计划」**:已移植计算器、日历、时钟、指南针、相册、短信、音乐、录音机、视频等
- ⚠️ 多为**轻量工具类**;深度绑定系统框架/语音服务的(如闪念胶囊)直接移植难度大

---

## 5. 语音引擎替换(讯飞 → 开源/云)

| 方案 | 类型 | 说明 |
|---|---|---|
| **Vosk** | 开源离线 ASR | 有 Android SDK 与 server(`alphacephei.com/vosk`) |
| **whisper.cpp** | 开源离线 | 端上转写,体积大、耗电 |
| **硅基流动 SiliconFlow** | 云 API | HTTP 一次性,`POST /v1/audio/transcriptions`,model `FunAudioLLM/SenseVoiceSmall` |
| **DashScope Paraformer-realtime** | 云 API(WebSocket 流式) | `wss://dashscope.aliyuncs.com/api-ws/v1/inference/` |
| 百度 / 讯飞开放平台 | 国内 SDK | 可联网时可用 |

> 原版闪念胶囊的语音链路:**离线唤醒(`com.audiolisten.sva`)+ 讯飞 MSC(`com.iflytek.speechsuite`)**,`msc.cfg` 默认离线为主。

---

## 6. 关键结论

1. **闪念胶囊不可直接移植** —— 深度依赖 `Lsmartisanos/api/*`(`WindowManagerSmt`、`ViewRootImplSmt`、`SettingsSmt`)、签名级权限、sara/sidebar/expandservice 等服务。**必须重写**。
2. **OneStep / BigBang 有官方开源代码可参考** —— 这是最可靠的一手资料。
3. **社区已有跨机型移植先例** —— 但集中在轻量工具类。
4. **许可证红线**:`whd-1999/flash-capsule` 无 LICENSE,只能净室参考。

---

## 7. ★ 2026-09-10 开源生态全面调研(补充)

### 7.1 最高价值发现:有人在做几乎相同的事

| 项目 | URL | 说明 |
|---|---|---|
| **CashewTeam/TNT-Anywhere** | `github.com/CashewTeam/TNT-Anywhere` | 37★,**GPL-3.0**。「补完 TNT Anywhere」:Shizuku + VirtualDisplay + MediaCodec + Moonlight/Sunshine,免 root 的 TNT 启动与远程串流 |

**它自带的四份逆向文档**(最高价值,可直接引用):
- `TNT_ACTIVATION_INVESTIGATION.md` — 验证 `displayId=100000`、`mNextTntVirtualDisplayId=100000`、`scheduleDisplayAdded()` **忽略 <100000**、**`global_pc_mode_settings` 是结果标志不是触发器**
- `SMARTISANOS_PRIVATE_API_SUMMARY.md` — `TNT_ANYWHERE_DISPLAY_PKG = "com.smartisanos.tntanywhere"`、`setProcessRunningCpuset`、隐藏的 `createVirtualDisplay(name,w,h,displayIdToMirror,surface)` 重载
- `TNT_OVERLAY_DISPLAY_DEBUG_GUIDE.md` — 无硬件时用 `overlay_display_devices` + `persist.easycast.show_overlay_display=1` 伪造外接屏
- `TNT_ANYWHERE_NUT_PRO2S_ADAPTATION.md` — 分版本降级

> **对工作区的意义**:它独立验证了本工作区关于 `displayId=100000` / `smt.tnt.virtual.display` / `FLAG_MIRROR_PC` 的判断 —— 两条独立路径结论一致。

### 7.2 锤子官方开源(SmartisanTech org)

| 项目 | URL | 星 | 许可 | 说明 |
|---|---|---|---|---|
| SmartisanTech/android | `github.com/SmartisanTech/android` | 2715★ | Apache-2.0 | 全量构建 repo manifest,2023-01 停更 |
| packages_apps_OneStep | `github.com/SmartisanTech/packages_apps_OneStep` | 471★ | Apache-2.0 | One Step 官方实现 |
| packages_apps_BigBang | `github.com/SmartisanTech/packages_apps_BigBang` | 265★ | Apache-2.0 | 大爆炸官方实现 |
| SmartisanOS-SDK | `github.com/SmartisanTech/SmartisanOS-SDK` | 116★ | Apache-2.0 | 第三方接入 API |
| SmartisanOS_Kernel_Source | `github.com/SmartisanTech/SmartisanOS_Kernel_Source` | 464★ | GPL-2.0 | T1/T2/U1/M1 内核 |
| **Wrench** | `github.com/SmartisanTech/Wrench` | 153★ | **⚠️ 无 LICENSE** | 可 Lua 脚本化的桌面端 Android 控制工具 |

> **★ 关键结论:锤子官方开源里完全没有 TNT 相关代码。** org 下 24 个仓库无任何 `Tnt*` / `revone` / TNT 显示服务。
> TNT 的 system_server 注入代码**从未开源**,只能从 ROM 逆向。

### 7.3 社区重建 org(极早期)

- **OpenSmartisanOS** — `github.com/OpenSmartisanOS`(2026-08 新建,全部 <7★)
  - `android_frameworks_libs_smartisanui`(6★)— **从 Smartisan OS 8.5.3 R2 ROM 提取的 UI 组件库**;带 `audit_r2_resources.py`(参数接收 `smartisanos.jar`、`framework-smartisanos-res.apk`、`SettingsSmartisan.apk`)
- **CashewTeam/awesome-smartisanOS** — `github.com/CashewTeam/awesome-smartisanOS`(37★,无 LICENSE)— 锤子生态项目总索引

### 7.4 系统移植 / 应用提取

| 项目 | URL | 星 | 许可 |
|---|---|---|---|
| People-11/SmartisanOS_APP_Port | `github.com/People-11/SmartisanOS_APP_Port` | 218★ | ⚠️ 无 LICENSE |
| rianlu/smartisan-launcher-maintained | `github.com/rianlu/smartisan-launcher-maintained` | 726★ | ⚠️ 无 LICENSE(反编译 smali 工程) |
| RANH-F/Smartisan-original-launcher | `github.com/RANH-F/Smartisan-original-launcher` | 252★ | ⚠️ 无 LICENSE |
| rianlu/handshaker-android-maintained | `github.com/rianlu/handshaker-android-maintained` | — | ⚠️ 无 LICENSE |

> **GSI**:未找到任何针对锤子机型的 GSI 适配项目。

### 7.5 大爆炸(Big Bang)复刻

| 项目 | URL | 星 | 许可 |
|---|---|---|---|
| **CashewTeam/BigBang_NovaText** | `github.com/CashewTeam/BigBang_NovaText` | **206★** | **GPL-3.0**(注:awesome 列表误标 Apache-2.0) |
| baoyongzhang/BigBang | `github.com/baoyongzhang/BigBang` | 988★ | MIT(2018 停更) |
| penglu20/Bigbang | `github.com/penglu20/Bigbang` | 716★ | WTFPL |
| Levi-Ackerman/BigBang | `github.com/Levi-Ackerman/BigBang` | 79★ | Apache-2.0 |

### 7.6 Android 桌面模式 / 自由窗口开源实现

| 项目 | URL | 星 | 许可 | 定位 |
|---|---|---|---|---|
| **mekhontsev/magicdesk** | `github.com/mekhontsev/magicdesk` | 43★ | **MIT** | **最接近目标**;真实 Android task + WMShell 窗口;跨 vendor 单代码库 |
| **Aypex/android-tiling-wm** | `github.com/Aypex/android-tiling-wm` | 8★ | **Apache-2.0** | 平铺 WM,Shizuku + 无障碍 |
| **bravoyush/FreeformShell** | `github.com/bravoyush/FreeformShell` | 15★ | **Apache-2.0** | 给缺装饰的 ROM 注入标题栏/拖拽/吸附 |
| jqssun/android-display-extend | `github.com/jqssun/android-display-extend` | 157★ | GPL-3.0 | DeX 全开源替代 |
| jqssun/android-display-mirror | `github.com/jqssun/android-display-mirror` | 166★ | GPL-3.0 | 虚拟显示投到 AirPlay/DisplayLink |
| axel358/smartdock | `github.com/axel358/smartdock` | 1.4k★ | GPL-3.0 | 桌面模式启动器 |
| mrYouki/YoukiDex | `github.com/mrYouki/YoukiDex-Android-Desktop` | 566★ | GPL-3.0 | 完整安卓桌面层 |
| NarYuki/Dextop | `github.com/NarYuki/Dextop` | 221★ | GPL-3.0 | 自建 VirtualDisplay 桌面 |
| legendsayantan/Extendroid | `github.com/legendsayantan/Extendroid` | 684★ | GPL-3.0 | 任意 app 弹窗化 |
| farmerbb/Taskbar | `github.com/farmerbb/Taskbar` | 1.3k★ | Apache-2.0 | 2024-11 停更 |
| sunshine0523/Mi-Freeform | `github.com/sunshine0523/Mi-Freeform` | 787★ | GPL-3.0 | 小米平台自由窗口,2024-03 停更 |
| **Katsuyamaki/DroidOS** | `github.com/Katsuyamaki/DroidOS` | 394★ | ⚠️ **Proprietary** | **功能高度重叠,禁止复制** |
| SangLuoCN/OneStep4 | `github.com/SangLuoCN/OneStep4` | 358★ | — | 不用 freeform,需 root |
| farmerbb/SecondScreen | `github.com/farmerbb/SecondScreen` | 515★ | — | README 明确「厂商深度定制 ROM 不保证」 |
| prespic/android-desktop-touchpad | `github.com/prespic/android-desktop-touchpad` | — | GPL-3.0 | Shizuku + `injectInputEvent` 注入副屏 |

### 7.7 Shizuku 生态

| 项目 | URL | 星 | 许可 |
|---|---|---|---|
| RikkaApps/Shizuku | `github.com/RikkaApps/Shizuku` | 30k★ | Apache-2.0 |
| RikkaApps/Shizuku-API | `github.com/RikkaApps/Shizuku-API` | 2.5k★ | MIT |
| **rish**(在 Shizuku-API 内) | `github.com/RikkaApps/Shizuku-API/tree/master/rish` | — | MIT | **把命令丢给高权限守护进程的 shell;「L1 层跑 am 命令」最省事的通道** |
| timschneeb/awesome-shizuku | `github.com/timschneeb/awesome-shizuku` | 10k★ | 无明确 LICENSE(清单) |
| thejaustin/ShizukuPlus | `github.com/thejaustin/ShizukuPlus` | 951★ | Apache-2.0 |

### 7.8 中文社区实践文章

| 主题 | URL |
|---|---|
| **TNT 投屏到电脑 display1/display100000** | `bilibili.com/opus/919013277851189300`(**直接印证背景**:TNT 对应 display1 与 display100000,断开后 ID 进位) |
| 小米 HyperOS 全局自由窗口适配(官方) | `dev.mi.com/xiaomihyperos/documentation/detail?pId=1593` |
| 小米自由窗口 Freeform 实现方案剖析 | `blog.csdn.net/learnframework/article/details/163298470` |
| 小米 freeform + Desktop 模式使能 | `blog.csdn.net/luoqingyan/article/details/128207787` |
| Android 强制开启 freeform / Desktop | `jianshu.com/p/c78746fd567b` |
| 锤子 OS 被社区复活综述 | `post.smzdm.com/p/am9xev74/` |

> **酷安**:360search 与 bing 均未检索到,酷安内容对外部搜索引擎基本不开放 —— 需在 App 内搜。

---

## 8. 待补充(研究中)

- SmartisanTech/android 仓库的实际内容与许可证
- 更多 GSI / 移植项目的细节
- 小米 HyperOS 3 上的悬浮窗与后台保活实践
