plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ★★★ 性能探针（任务 AK）—— **不是产品组件，是诊断工具**。
//
// ## 为什么单独一个模块、而且是 targetSdk 27
//
// 要验证的问题是：**从【app 身份】能不能调 QTI 的 perf HAL**。
// 而 `android.util.BoostFramework` / `com.qualcomm.qti.Performance` 都被标为
// **hidden API `blacklist`** —— targetSdk ≥ 28 的 app 反射它们会被拦。
//
// ★ **`targetSdkVersion ≤ 27` 的 app 【豁免】隐藏 API 限制**（Android 10 只对 ≥28 强制）
// ⇒ 本模块用 27 就是为了**用最小代价先验证"这条路本身通不通"**。
//
// ⚠️ 这只是**验证手段**；产品化要么上 JNI 直接调 `libqti-perfd-client.so` 的 C API
// （该库在 `public.libraries.txt` 里，app 可加载），要么就接受 targetSdk 27 的代价。
android {
    namespace = "com.shware.perfprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shware.perfprobe"
        minSdk = 27
        // ★★ 关键：27 ⇒ 豁免隐藏 API 限制（见文件头注释）
        targetSdk = 27
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        // ★ 任务 AP / AP7：本模块要当 Powertoys 对外 API 的**跨应用调用方**，
        //   因此需要编译 `IPowerToysApi.aidl`。
        //   那份 AIDL 是**照抄**过来的（不依赖对方的模块）——
        //   这正是"后续工程"接入时会做的事，顺带也就验证了"接口自包含"。
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
