plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★ MOD：性能模式（任务 AK）—— 把 QTI perf HAL 的 perfLockAcquire 封装成 Powertoys 的一个功能。
//   不依赖 usb-serial，也不需要 overlay ⇒ 依赖极简。
android {
    namespace = "com.shware.mode.mod.perfmode"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        // ⚠️ 本 mod **不需要** targetSdk=27 —— 它走的是手搓 binder（全程公开 API），
        //    不碰隐藏 API 黑名单。探针那个 targetSdk=27 只是当时的权宜之计。
        targetSdk = 35
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
