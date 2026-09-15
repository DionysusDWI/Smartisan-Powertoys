plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★ MOD：手机侧系统性能数据窗口 —— 第一个 MOD_TARGET=phone 的组件。
android {
    namespace = "com.shware.mode.mod.perfmon"
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
    // 只用 NotificationCompat / ContextCompat —— ★ 刻意保持极轻
    // 数据全走 /proc + 系统公开 API，**不引任何三方库**
    implementation("androidx.core:core-ktx:1.13.1")
}
