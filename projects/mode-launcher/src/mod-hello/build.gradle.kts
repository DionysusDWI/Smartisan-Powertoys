plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★ 一个 MOD：与启动器（:app）同属一个 Gradle 构建，但产出【独立 APK】。
//   这么组织只是为了少一套工具链；**契约仍然是 APK 边界** ——
//   本模块不依赖 :app 的任何代码，将来别处独立建工程照样能被启动器加载。
android {
    namespace = "com.shware.mode.mod.hello"
    compileSdk = 35

    defaultConfig {
        minSdk = 29          // 坚果 A10
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
    // 只用 ContextCompat / NotificationCompat —— ★ 刻意不引 appcompat，
    //   证明「一个 mod 可以是极轻的」
    implementation("androidx.core:core-ktx:1.13.1")
}
