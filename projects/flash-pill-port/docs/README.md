# 闪念胶囊 二次开发素材包

目标：以闪念胶囊为参考，重写为一个兼容小米(Android16 / 不root)的个人版应用。
语音转写：硅基流动(SiliconFlow)/DashScope 实时语音；UI 学习原版交互但重新绘制。

## 目录
- `IdeaPills_闪念胶囊.apk`       原始安装包(参考蓝本)
- `src/`                        apktool 反编译源码(smali+资源+AndroidManifest)
- `接口分析报告.md`             **系统接口对照+替换映射(核心)**
- `可移植性分析.md`             原包能否在其他机型运行的评估

## 用法
1. 用 jadx(推荐) 打开 `IdeaPills_闪念胶囊.apk` 看 Java 层；或在 `src/` 看 smali。
2. 读 `接口分析报告.md`，据“八、映射表”替换语音/入口/数据/悬浮窗等。
3. 重写 UI(学习原交互)，接硅基流动/DashScope SDK 做实时转写。

## 注意
- 仅个人使用，勿传播。
- 原包依赖 SmartisanOS 生态，不可直接运行在小米；本包仅作参考与接口梳理。
