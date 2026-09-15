import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 从 local.properties 读取敏感配置（不入库）
// 注意：必须用 import 引入 Properties —— 脚本里裸写 java.util.Properties 会被
// Gradle 的 java 扩展名遮蔽，导致 "Unresolved reference: util"。
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val siliconFlowApiKey: String = localProperties.getProperty("SILICONFLOW_API_KEY") ?: ""

android {
    namespace = "com.shware.flashpill"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shware.flashpill"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-m1"

        // 语音转写配置（key 来自 local.properties，切勿硬编码）
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"$siliconFlowApiKey\"")
        buildConfigField(
            "String",
            "SILICONFLOW_ASR_ENDPOINT",
            "\"https://api.siliconflow.cn/v1/audio/transcriptions\""
        )
        buildConfigField("String", "SILICONFLOW_ASR_MODEL", "\"FunAudioLLM/SenseVoiceSmall\"")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}