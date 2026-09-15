#!/usr/bin/env bash
# 构建并运行 shell 能力探针。
#
#   bash build.sh          —— 只构建（javac + d8）
#   bash build.sh run      —— 构建 + 推送 + 以 shell 身份运行
#
# ★ 两个坑（都踩过）：
#   1. javac 默认按 Windows GBK 读源码 ⇒ 中文注释报错 ⇒ 必须 `-encoding UTF-8`
#   2. d8 需要 Java 11+，而 shell 里默认 java 是 8 ⇒ 显式用工具链的 JDK 17 跑 d8.jar
set -e
cd "$(dirname "$0")"

JDK="../../toolchain/jdk-17.0.20.1+1"
BT="../../toolchain/android-sdk/build-tools/34.0.0"
AJ="../../toolchain/android-sdk/platforms/android-29/android.jar"
MAIN="com.shware.probe.ShellProbe"
DEX=/data/local/tmp/probe.dex

rm -rf out
mkdir -p out/classes out/dex

echo "[probe] javac ..."
"$JDK/bin/javac" --release 8 -nowarn -encoding UTF-8 -cp "$AJ" -d out/classes ShellProbe.java

echo "[probe] d8 ..."
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 29 --lib "$AJ" --output out/dex out/classes/com/shware/probe/ShellProbe.class

echo "[probe] 构建完成：out/dex/classes.dex"

# ★ 总是推送 —— 这个坑踩过：只 build 不 push ⇒ 设备上跑的是旧 dex，
#   于是"改了代码没生效"，白排查半天。
export MSYS_NO_PATHCONV=1
echo "[probe] 推送 ..."
adb push out/dex/classes.dex "$DEX" >/dev/null

if [ "$1" = "run" ]; then
    echo "[probe] 运行 ..."
    adb shell "CLASSPATH=$DEX app_process /system/bin $MAIN"
fi
