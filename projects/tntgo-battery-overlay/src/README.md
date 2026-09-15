# TntgoBattery（MVP）

TNT GO 电量悬浮窗 —— Android App（Kotlin）。

## 功能

- 检测外接显示器（TNT GO），在其右上角显示电量卡片
- 显示 **手机电量**（含充电状态）+ **TNT GO 电量**（USB CDC 串口读取）
- 零权限渲染（`Presentation`），触摸穿透、不抢焦点

## 目录

```
src/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/shware/tntgo/battery/
        │   ├── MainActivity.kt          # 主界面：状态 / 授权 / 启停
        │   ├── OverlayService.kt        # 前台服务：显示监听 + 轮询
        │   ├── BatteryPresentation.kt   # 外接屏电量卡片
        │   └── TntgoSerial.kt           # USB CDC 串口读电量
        └── res/
            ├── layout/activity_main.xml
            ├── layout/presentation_battery.xml
            ├── drawable/bg_battery_card.xml
            └── values/strings.xml, themes.xml
```

## 构建

### 环境

- JDK 17
- Android SDK（compileSdk 35）
- Android Studio Ladybug+ 或命令行 Gradle 8.7+

### 步骤

```bash
cd src

# 首次：生成 gradle wrapper（或用本机 gradle）
gradle wrapper --gradle-version 8.7

# 构建 debug APK
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> `local.properties` 需自行创建并写入 `sdk.dir=...`（Android Studio 会自动生成）。

## 使用

1. 安装 APK，打开 App
2. 连接 TNT GO 后点「请求 USB 权限」→ 系统弹窗允许
3. 点「启动悬浮显示」→ 允许通知权限
4. 用全功能线连接 TNT GO（视频输入口）→ 屏幕右上角出现电量卡片

## 已知限制 / 待验证

- Pro3（TNT OS）上 `Presentation` 是否被窗口管理接管 —— 待实机验证
- 串口波特率 115200 / 9600 自动重试，具体哪个有效待实测
- 小米 HyperOS 需关闭「强制桌面模式」才能正常镜像（见 `../docs/05`）

## 路线图

- [ ] 实机验证（小米 17 Pro Max + TNT GO）
- [ ] TNT OS（Pro3）兼容性验证
- [ ] 备选渲染方案：display-context overlay（`TYPE_APPLICATION_OVERLAY`）
- [ ] 低电量提醒 / 电量趋势