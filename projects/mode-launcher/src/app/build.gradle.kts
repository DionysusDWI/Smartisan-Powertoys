plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shware.mode"
    compileSdk = 35

    defaultConfig {
        // 29 = Android 10（坚果 Pro 3）—— 便于在开发机上先冒烟，再上小米 A16
        minSdk = 29
        targetSdk = 35
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // ★ Shizuku UserService 走 AIDL
        aidl = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // ★ 图形化 UI（任务 AP）—— 功能卡片列表与图层列表都用它。
    //   Material 虽然会传递依赖进来，但**显式写出来**：
    //   哪天 Material 换了内部依赖，编译期就能发现，而不是运行时 NoClassDefFoundError。
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ★ 特权通道 —— Shizuku
    // ⚠️ 13.1.5 是 Maven Central 上**库**的最新版；
    //    App 侧装的 13.6.0.r1086 是 **Shizuku 管理器**的版本号，两套体系不同。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
