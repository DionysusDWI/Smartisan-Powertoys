// 根构建脚本：只声明插件版本，不在此处应用
// 版本与工作区现有工程（tntgo-battery-overlay）保持一致 —— 该组合已验证可用
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
