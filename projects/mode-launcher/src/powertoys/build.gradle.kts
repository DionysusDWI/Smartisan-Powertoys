plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * ★★★★★ **Smartisan Powertoys** —— 一体化应用（任务 AL）。
 *
 * ## 这个模块只做一件事：**把所有东西装进一个 APK**
 *
 * 它自己几乎没有代码 —— 全部功能来自依赖的 library 模块：
 * ```
 * :app                    启动器宿主 + MOD 契约
 * :mod-tntgo-battery      TNT GO 电量
 * :mod-perfmon            手机侧性能窗口
 * :mod-livecaption        实时字幕
 * :mod-tntgo-brightness   TNT GO 亮度键
 * :mod-perfmode           性能模式
 * ```
 *
 * ⚠️ `:mod-hello`（示例组件）**曾经在这个列表里，现已移出** ——
 *   见下面 `dependencies` 里的说明。它作为**模板 ＋ 测试夹具**保留在仓库中。
 *
 * ## ★★★★★ `applicationId` 为什么是 `com.shware.mode` 而不是 `com.shware.powertoys`
 *
 * **这是本任务最要紧的一个决定，不要改**：
 *
 * | 理由 | 说明 |
 * |---|---|
 * | ★★ **保住 Shizuku 授权** | Shizuku 按**包名**授权 —— 换包名要**重新手动授权** |
 * | ★★ **保住 Smartisan 悬浮窗授权** | 同上 —— 换包名 = **又一道只能人点的门** |
 * | ★ **保住宿主已记住的 mod 开关** | 状态存在 `com.shware.mode` 的 SharedPreferences 里 |
 *
 * ⇒ ★ **「Smartisan Powertoys」是【显示名（label）】，不是包名。**
 *   （和 Windows 的 PowerToys 一样 —— 用户看到的是名字，不是 GUID。）
 *
 * ## ★ 进程隔离
 *
 * 各 mod 的 `<service>` 带 `android:process=":xxx"`（在各 mod 的 manifest 里）——
 * **同一个包（一份权限）＋ 不同进程（互不拖累）**。
 */
android {
    namespace = "com.shware.powertoys"
    compileSdk = 35

    defaultConfig {
        // ★★★ 保持不变 —— 见文件头的说明
        applicationId = "com.shware.mode"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-powertoys"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // ★ 启动器宿主（含 MOD 契约）
    implementation(project(":app"))

    // ★ 产品内建的 5 个功能组件
    //
    // ⚠️ **`mod-hello` 不在这个列表里** —— 用户 2026-09-14 决定把它从产品中移除：
    //    「那个最早的 MODE 组件的悬浮窗……并没有比系统 UI 提供更多信息，但很占位置」
    //
    // ★ 但**模块本身保留在仓库里**，因为它是两样东西：
    //    ① 写新 mod 时照抄的**模板**（契约用法全在里面）
    //    ② 验证契约的**测试夹具**（图层归属、屏选择、图层控制都是拿它测的）
    //
    // ⚠️⚠️ 它现在**不能单独构建成可安装的 APK** ——
    //    任务 AL 合并之后所有 mod 都成了 `com.android.library`（产出 AAR），
    //    而且 `scripts/build.sh --list` 里**也没有** `mod-hello` 这个目标
    //    （只有 flashpill / mode-all / perf-probe / powertoys / tntgo）。
    //    `mod-hello/build/outputs/` 里那个 apk 是**合并前**的遗留，已过期
    //    （没有图层控制监听），只能用来验证"外装 mod 能被发现"，别当功能夹具用。
    //
    // ⇒ 要把它跑起来看效果，**临时**把下面这行加回本文件，看完再删：
    //      implementation(project(":mod-hello"))
    //    然后 `bash scripts/deploy.sh powertoys`
    implementation(project(":mod-tntgo-battery"))
    implementation(project(":mod-perfmon"))
    implementation(project(":mod-livecaption"))
    implementation(project(":mod-tntgo-brightness"))
    implementation(project(":mod-perfmode"))
}
