pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ★ usb-serial-for-android（TNT GO 电量 mod 用）只发在 jitpack 上。
        //   ⚠️ jitpack 在国内可能很慢/不通 —— 但 3.8.0 的 AAR **已在本工作区
        //   Gradle 缓存在**（toolchain/.gradle/caches/…/usb-serial-for-android-3.8.0.aar），
        //   所以必要时可以 `--offline` 构建。
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "ModeLauncher"

// ★★★★ 任务 AL：**合并为单一 APK（Smartisan Powertoys）**
//
// 下面这些模块【全部降级为 library】，由 :powertoys 汇总成一个 APK。
// 各 mod 的 manifest / 契约 / 代码都**没改**，只是从"出 APK"变成"出 AAR"。
//
// ★ `applicationId` 保持 `com.shware.mode` —— 为了保住 Shizuku 授权与
//   Smartisan 悬浮窗授权（换包名 = 两道只能人点的门）。详见 :powertoys 的 build.gradle.kts。
//
// ★ 外部独立 mod APK 仍然可以被发现（`queryIntentServices` 扫全系统，
//   不区分"内置"还是"外装"）⇒ **契约这条路没有被合并牺牲掉**。
//
// ⚠️ `:perf-probe` **不合并** —— 它是诊断工具，而且 `targetSdk=27`（豁免隐藏 API 限制）
//    与主包的 35 冲突，必须是独立模块。
include(":powertoys")

include(":app")

// ★ mod 与启动器同属一个 Gradle 构建，但各自产出【独立 APK】——
//   契约仍在 APK 边界上（mod 不依赖 :app 的代码）。
//   这么组织只为少一套工具链；将来外部 mod 独立建工程照样能被加载。
include(":mod-hello")

// ★ TNT GO 精准电量（任务 Q2）—— 第一个「真·功能组件」
include(":mod-tntgo-battery")

// ★ 手机侧系统性能数据窗口（任务 T）—— 第一个 MOD_TARGET=phone 的组件
include(":mod-perfmon")

// ★ 实时字幕（任务 W）—— 抓音源 → 云端实时 ASR → 自己画字幕
include(":mod-livecaption")

// ★ TNT GO 亮度键（任务 AI）—— 拦 Consumer Control 的亮度键 → 发 AT+BKL
include(":mod-tntgo-brightness")

// ★ 性能探针（任务 AK）—— 从【app 身份】验证能不能调 QTI perf HAL
//   ⚠️ 它是一个**探针**，不是产品组件；用完可以删。
//   为什么单独开模块：它需要 `targetSdkVersion = 27`（该版本豁免隐藏 API 限制），
//   和其它模块的 35 不一致 ⇒ 必须是独立模块。
include(":perf-probe")

// ★ 性能模式（任务 AK）—— 把 QTI perf HAL 的 perfLockAcquire 封装成**正式功能**
//   （探针验证通过后产品化的那个；走手搓 binder，全程公开 API）
include(":mod-perfmode")

// ★★★★ 任务 AS3b：TNT GO 串口 **唯一 owner**（共享库，不是 mod）
//
// `mod-tntgo-battery` 与 `mod-tntgo-brightness` 共用**同一条 CDC-ACM**，
// 实测会撞（用户按键时电量侧失败率 67%）。
// ⇒ 把"开设备 / 取锁 / 白名单"收到这一处，两边都改成调它。
//
// ★ 做成 library 而不是 mod：它不是一个功能，是两个 mod 的**底层**。
include(":tntgo-serial")
