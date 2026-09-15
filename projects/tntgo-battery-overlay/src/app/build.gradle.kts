plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shware.tntgo.battery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shware.tntgo.battery"
        // Android 10：覆盖坚果 Pro3 的 Smartisan OS 8.0.4（TNT 2.0）
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-at-expand"
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // USB CDC-ACM 串口（读取 TNT GO 电量）
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
}