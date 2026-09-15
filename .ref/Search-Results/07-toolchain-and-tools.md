# 07 · 工具链与逆向工具

> 归档日期:2026-09-10
> 本工作区已实际安装并验证的版本。安装位置见 `toolchain/`。

---

## 1. 已安装并验证

| 组件 | 版本 | 来源 URL | 校验 | 位置 |
|---|---|---|---|---|
| **Temurin JDK** | 17.0.20.1+1 | `api.adoptium.net/v3/assets/latest/17/hotspot?os=windows&architecture=x64&image_type=jdk` | sha256 `e53a79c3…7cb0` ✅ 与 API 一致 | `toolchain/jdk-17.0.20.1+1/` |
| **Gradle** | 8.7 | `services.gradle.org/distributions/gradle-8.7-bin.zip` | sha256 `544c35d6…961d` ✅ 与官方一致 | `toolchain/gradle-8.7/` |
| **Android cmdline-tools** | 12.0(包 `11076708`) | `dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip` | — | `toolchain/android-sdk/cmdline-tools/latest/` |
| **Android SDK** | platforms 29 + 35、build-tools 35.0.0 / 34.0.0、platform-tools 37.0.1 | `sdkmanager` | — | `toolchain/android-sdk/` |
| **apktool** | 3.0.3 | `github.com/ibotpeaches/Apktool` releases | — | `toolchain/bin/apktool.jar` |
| **jadx** | 1.5.6 | `github.com/skylot/jadx` releases | — | `toolchain/jadx-1.5.6/` |
| **baksmali / smali** | 2.5.2 | `bitbucket.org/JesusFreke/smali/downloads/` ← **注意:在 Bitbucket,不在 GitHub releases** | — | `toolchain/bin/{baksmali,smali}.jar` |

### 关键版本约束(已对主源核实)

| 约束 | 说明 |
|---|---|
| AGP 8.5.2 → **min Gradle 8.7** | AGP/Gradle 兼容矩阵 |
| AGP 8.5.2 → **JDK 17** | AGP 8.x 全部要求 JDK 17+;JDK 21 亦可 |
| AGP 8.5.2 官方只支持到 **compileSdk 34** | 用 35 需 `android.suppressUnsupportedCompileSdk=35` |
| Kotlin 2.0.20 + AGP 8.5.2 | KGP 2.0.20 最高测试到 AGP 8.5 ✅ |
| KSP `2.0.20-1.0.25` | Maven Central 存在 ✅ |
| Room 2.6.1 | 是 KSP1 时代版本;Kotlin 2.0 默认 KSP2,**若注解处理出问题应升 2.7.2** |
| apktool 3.0.3 | 实际只需 Java 8+(字节码 major 52),JDK 17 亦可运行 |
| apktool 3.x | `--aapt <file>`,**不是** 2.x 的 `--use-aapt2` |

---

## 2. Python 分析环境

`.venv/`(Python 3.13.5),`requirements.txt` 已 pin。**全部为预编译 wheel 或纯 Python,无需 Visual Studio Build Tools**。

| 包 | 版本 | 用途 | 备注 |
|---|---|---|---|
| androguard | 4.1.4 | APK/DEX 分析、AXML、resources.arsc | 纯 Python |
| pyaxmlparser | 0.3.31 | 轻量读 AndroidManifest.xml | 纯 Python |
| lief | **1.0.0** | ELF/PE 解析 | **cp312-abi3 wheel**(0.17.6 无 cp313 wheel ❌) |
| capstone | 5.0.9 | ARM64 反汇编 | `py3-none-win_amd64`,自带 DLL |
| lxml | 6.1.3 | XML 解析 | cp313 wheel |
| networkx | 3.6.1 | 类引用图 / xref | 纯 Python |
| jinja2 / pytest / requests | — | 模板 / 测试 / HTTP | 纯 Python |

> **刻意不装 `apkutils`**:版本陈旧、与 androguard 的 pin 有冲突、功能被 androguard 覆盖。

---

## 3. 网络通道(此机器实测)

### 可达
`api.github.com`、`dl.google.com`、`repo1.maven.org`、`jitpack.io`、`services.gradle.org`、
`pypi.org`、`bitbucket.org`、`mirrors.tuna.tsinghua.edu.cn`、`mirrors.cloud.tencent.com`、
`cdn.jsdelivr.net`、`ghproxy.net`、`gh-proxy.com`、`localhost:8080`(SearxNG)

### 不可达 / 被阻断
| 目标 | 症状 | 替代 |
|---|---|---|
| `raw.githubusercontent.com` | 连接重置 | `cdn.jsdelivr.net/gh/OWNER/REPO@BRANCH/PATH` 或 `gh-proxy.com/` 前缀 |
| `developer.android.com` | 超时 | 用 AOSP 镜像 `cdn.jsdelivr.net/gh/aosp-mirror/...` |
| **WebFetch 工具** | 域名安全校验服务连不上,**完全不可用** | 一律改用 `curl` |
| google / duckduckgo / brave / startpage / baidu(经 SearxNG) | CAPTCHA | 用 `bing`、`quark`、`360search`、`github`、`stackoverflow` |

### 快速镜像(大文件)
| 目标 | 镜像 |
|---|---|
| Gradle 发行版 | `mirrors.cloud.tencent.com/gradle/` |
| JDK | `mirrors.tuna.tsinghua.edu.cn/Adoptium/` |
| Android SDK 包 | `mirrors.cloud.tencent.com/AndroidSDK/` |

---

## 4. Windows 环境注意事项

| 问题 | 处理 |
|---|---|
| **Git Bash 路径转换**会破坏 `adb shell /system/...` | 加 `MSYS_NO_PATHCONV=1` |
| **中文文件名**在 Git Bash 下传给 Windows 程序会乱码 | 先复制为 ASCII 名再操作 |
| **Gradle transforms 缓存**原子重命名失败 | 已加 Defender 排除项;兜底 `scripts/fix-gradle-transforms.py` |
| `source scripts/env.sh` 在子 shell(pipeline)中不生效 | 必须直接 `source`,不能管道 |
