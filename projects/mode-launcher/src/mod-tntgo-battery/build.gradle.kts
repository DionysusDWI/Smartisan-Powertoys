plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★ MOD：TNT GO 精准电量 —— 第一个「真·功能组件」。
//   与 :app / :mod-hello 同属一个 Gradle 构建，但产出【独立 APK】。
android {
    namespace = "com.shware.mode.mod.tntgo"
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

    // ★★★★ 任务 AS3b：串口**唯一 owner**。
    //   ⚠️ 本模块**不再直接依赖 usb-serial-for-android** ——
    //      那是"唯一 owner"这条约束的**编译期表达**：
    //      拿不到 `UsbSerialPort`，就不可能再有人偷偷 `openDevice`。
    implementation(project(":tntgo-serial"))
}
