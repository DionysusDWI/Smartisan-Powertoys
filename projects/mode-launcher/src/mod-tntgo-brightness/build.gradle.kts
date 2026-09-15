plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★ MOD：TNT GO 亮度键（任务 AI）。
//   与其它 mod 同属一个 Gradle 构建，但产出【独立 APK】—— 契约仍在 APK 边界上。
android {
    namespace = "com.shware.mode.mod.brightness"
    compileSdk = 35

    defaultConfig {
        minSdk = 29          // 坚果 Pro 3 / Android 10
        targetSdk = 35
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // ★★★★ 任务 AS3b：串口**唯一 owner**（与 mod-tntgo-battery 共用同一个）。
    //   ⚠️ 本模块**不再直接依赖 usb-serial-for-android** ——
    //      那是"唯一 owner"这条约束的**编译期表达**。
    implementation(project(":tntgo-serial"))
}
