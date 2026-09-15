plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★★★★ 任务 AS3b：TNT GO 串口 **唯一 owner**（共享库，两个 mod 都用它）。
//
// ## 为什么是 library 而不是又一个 mod
//
// 它**不是一个功能**，是 `mod-tntgo-battery` 与 `mod-tntgo-brightness`
// **共用的底层**。做成 mod 会带来"它自己也要被拉起/保活"的一堆无关问题。
//
// ## 为什么它能做到"唯一 owner"
//
// 两个 mod 已经是**同一个 APK**（任务 AL 把 library 合进 `:powertoys`）
// ⇒ 同一个 `filesDir` ⇒ **跨进程文件锁可用** ⇒
//    ★ 不需要把两个 mod 并到同一进程（见 AS 计划书 §3.4 的偏离说明）。
android {
    namespace = "com.shware.mode.tntgoserial"
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

    // ★ USB CDC-ACM 串口（纯用户空间，不需要内核 cdc_acm、不需要 root）。
    // ⚠️ **`implementation` 而不是 `api`** —— 调用方**不该**看得到
    //    `UsbSerialPort` / `UsbDeviceConnection`。把它们挡在模块里，
    //    "谁都不许再 openDevice"这条约束才是**编译期**的，而不是靠自觉。
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
}
