#!/usr/bin/env bash
# 构建 / 运行 QTI perf 探针（任务 AK）。
#
#   bash build.sh             —— 只构建（javac + d8）并推送
#   bash build.sh run         —— 构建 + 前台跑一次（dump 模式）
#   bash build.sh hint        —— ★ 后台跑：施加 perfHint 后【保持存活】
#
# ★ 两个坑（从 projects/shell-probe 继承，都踩过）：
#   1. javac 默认按 Windows GBK 读源码 ⇒ 中文注释报错 ⇒ 必须 `-encoding UTF-8`
#   2. d8 需要 Java 11+，而 shell 里的 java 可能是 8 ⇒ 显式用工具链的 JDK 17
set -e
cd "$(dirname "$0")"

JDK="../../toolchain/jdk-17.0.20.1+1"
BT="../../toolchain/android-sdk/build-tools/34.0.0"
AJ="../../toolchain/android-sdk/platforms/android-29/android.jar"
MAIN="com.shware.perf.PerfProbe"
DEX=/data/local/tmp/perf.dex
LOG=/data/local/tmp/perf.log

rm -rf out
mkdir -p out/classes out/dex

echo "[perf-probe] javac ..."
"$JDK/bin/javac" --release 8 -nowarn -encoding UTF-8 -cp "$AJ" -d out/classes PerfProbe.java

echo "[perf-probe] d8 ..."
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 29 --lib "$AJ" --output out/dex out/classes/com/shware/perf/PerfProbe.class

echo "[perf-probe] 构建完成：out/dex/classes.dex"

# ★ 总是推送 —— 只 build 不 push ⇒ 设备上跑的是旧 dex（这个坑踩过）
export MSYS_NO_PATHCONV=1
adb push out/dex/classes.dex "$DEX" >/dev/null

run_fg() {
    echo "[perf-probe] 前台运行 ..."
    adb shell "CLASSPATH=$DEX app_process /system/bin $MAIN"
}

run_bg() {
    echo "[perf-probe] ★ 后台运行（施加 boost 后保持存活）..."
    adb shell "rm -f $LOG"
    adb shell "setsid nohup env CLASSPATH=$DEX PROBE_MODE=${PROBE_MODE:-hint} \
        PROBE_HINT=${PROBE_HINT:-0x1081} PROBE_DUR=${PROBE_DUR:-20000} \
        PROBE_HOLD_MS=${PROBE_HOLD_MS:-15000} \
        app_process /system/bin $MAIN > $LOG 2>&1 < /dev/null &"
    sleep 2
    echo "--- 探针日志 ---"
    adb shell "cat $LOG"
}

case "${1:-build}" in
    run)  run_fg ;;
    bg)   run_bg ;;
    *)    echo "[perf-probe] 构建 + 推送完成（加 run / bg 直接跑）" ;;
esac
