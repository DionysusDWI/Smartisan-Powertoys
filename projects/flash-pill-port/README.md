# 闪念胶囊复刻（flash-pill-port）

> 目录：`projects/flash-pill-port/`
> 状态：素材已到位，待分析

## 目标

在小米手机上复刻 **闪念胶囊**：

- 形态：**悬浮窗应用 + 可打开界面的应用** 结合
- 入口：**侧边栏启动**
- 语音转写：硅基流动（SiliconFlow）/ DashScope 实时语音
- UI：学习原版交互，但重新绘制（不直接搬运资源）

## 素材来源

- 原始参考：`IdeaPills_闪念胶囊.apk`（SmartisanOS 生态应用，12MB）
- 打包日期：2026-09-06（由电脑端传入）

## 文件清单

| 文件 | 大小 | 内容 |
|---|---|---|
| `reference/FlashPill_package.tar.gz` | 14.7MB | `FlashPill_dev/`：原始 APK + apktool 反编译源码 + **接口分析报告.md**（核心）+ 可移植性分析.md + MANIFEST |
| `reference/Xiaomi_FlashPill_dev.tar.gz` | 14.7MB | `FlashPill_dev/src/`（小米适配版开发素材，smali 源码） |

## 已提取文档（`docs/`）

| 文档 | 说明 |
|---|---|
| `docs/技术方案.md` | **本项目技术方案 v0.1**（架构 / 侧边栏 / 转写 / 保活 / 计划） |
| `docs/接口分析报告.md` | **核心**：系统接口对照 + 替换映射 |
| `docs/可移植性分析.md` | 原包跨机型运行评估 |
| `docs/README.md` / `docs/MANIFEST.md` | 素材包原始说明与清单 |

## 参考项目

| 项目 | 说明 | 位置 |
|---|---|---|
| `whd-1999/flash-capsule` | 闪念胶囊复刻 + 增强（Kotlin / Compose / Room / whisper.cpp，已迭代 v0.18） | `reference/flash-capsule/`（ARCHITECTURE / DESIGN_FROM_VIDEO / CHANGELOG / README） |

## 关键结论（摘自包内 README）

- 原包**依赖 SmartisanOS 生态，不可直接运行在小米**；素材仅作参考与接口梳理
- 实施路径：按 `接口分析报告.md` 的「八、映射表」替换 **语音 / 入口 / 数据 / 悬浮窗** 等系统接口
- 参考流程：jadx 打开原 APK 看 Java 层，或读 `src/` smali；再按映射表重写

## 待办

- [x] 解压阅读 `接口分析报告.md`、`可移植性分析.md`
- [x] 技术方案落档（`docs/技术方案.md`）
- [x] 调研开源参考（`flash-capsule`）
- [x] 确认语音 API（SiliconFlow，已实测 TTS→ASR 闭环）
- [x] **转写层代码**（`src/`：WavRecorder + SiliconFlowTranscriber + 测试界面）
- [x] **M1 代码**（侧边栏把手 + 录音气泡 + Room + 列表界面）
- [x] **M2 代码**（按住说话 + 5 色标分类 + 详情编辑 + 原声回放）
- [x] **M3 / M5 / M6 代码**（搜索筛选置顶完成 + 分享 / 日历 / 提醒 + 小米自启动引导）
- [ ] 实机验证（构建 APK 后整体测试）
- [ ] M4：DashScope 实时流式（需 DashScope key）

## 注意

- 仅个人使用，勿传播