plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ★ MOD：实时字幕 —— 抓音源 → 云端实时 ASR → 自己画字幕。
android {
    namespace = "com.shware.mode.mod.livecaption"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        targetSdk = 35

        // ⚠️ API Key 在【构建时】从环境变量注入。
        //
        // 为什么这么做：mod 跑在手机上，运行时拿不到桌面 shell 的环境变量。
        // 代价：★ **key 会被编进 APK** —— 本项目不对外发布、只装自己的设备，可接受；
        //       但**别把这个 APK 发给别人**。设置界面里可以覆盖（存 SharedPreferences）。
        val dsKey = System.getenv("DASHSCOPE_API_KEY") ?: ""
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"$dsKey\"")
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

    buildFeatures {
        buildConfig = true     // 上面 buildConfigField 要用
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // WebSocket 客户端（DashScope realtime ASR 是 WS 协议）
    // OkHttp 在 Maven Central，构建机可达
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
