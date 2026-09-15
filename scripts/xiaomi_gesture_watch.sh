#!/usr/bin/env bash
# ============================================================================
#  xiaomi_gesture_watch.sh —— 盯着 GestureStubHome 的 touchableRegion
# ----------------------------------------------------------------------------
#  背景（2026-09-11 实测坐实）：
#    故障态  GestureStubHome touchableRegion=[652,2534][1200,2608]  ← 被裁到右半
#    正常态  GestureStubHome touchableRegion=[0,2533][1200,2608]    ← 全宽
#    两者 frame 都是 [0,2533][1200,2608]（全宽）
#    ⇒ 触摸区被裁 = 底部中/左侧上滑落不到手势桩 = 上拉手势失效
#
#  本脚本：轮询该值，【只在变化时】记一行日志（带时间戳）。
#         用户照常使用手机；一旦复发，日志里就有【精确到 15 秒的复发时刻】，
#         再拿那个时刻去 logcat 里找凶手。
#
#  用法：
#     bash scripts/xiaomi_gesture_watch.sh [serial] [interval_sec]
#     # 默认用 ANDROID_SERIAL（或 adb 唯一设备），15 秒一轮
#
#  停止：Ctrl-C（或 kill 掉后台任务）
# ============================================================================
set -u

D="${1:-${ANDROID_SERIAL:-}}"
INTERVAL="${2:-15}"
LOGDIR=".ref/_diag"
LOG="$LOGDIR/gesture_watch.log"

mkdir -p "$LOGDIR"

logline() { printf '%s\n' "$*" | tee -a "$LOG"; }

# 取 GestureStubHome 的 frame 与 touchableRegion
# ⚠️ 三键模式下窗口不存在；手势模式下也可能出现 touchableRegion=<empty>
#    ⇒ 必须分开提取并各自兜底，不能一把 sed 到底（否则整行原样漏出来）
probe() {
  local raw frame touch
  raw="$(adb -s "$D" shell dumpsys input 2>/dev/null \
          | grep -E 'name=[0-9a-f]+ GestureStubHome,' | head -1)"
  [ -z "$raw" ] && { echo ""; return; }

  frame="$(printf '%s' "$raw" | grep -oE 'frame=\[[^]]*\]\[[^]]*\]' | head -1 | cut -d= -f2)"
  touch="$(printf '%s' "$raw" | grep -oE 'touchableRegion=\[[^]]*\]\[[^]]*\]' | head -1 | cut -d= -f2)"

  [ -z "$frame" ] && frame="?"
  [ -z "$touch" ] && touch="<非矩形/空>"
  echo "$frame | $touch"
}

# 判断：触摸区是否 = 全宽（正常）还是被裁（异常）
verdict() {
  local region="$2"
  case "$region" in
    "[0,2533][1200,2608]"|"[0,2534][1200,2608]") echo "OK  全宽" ;;
    "") echo "?? 未找到窗口（可能不在手势模式）" ;;
    *) echo "★★ 异常！触摸区被裁" ;;
  esac
}

logline "================================================================"
logline " 手势桩监视启动 $(date '+%Y-%m-%d %H:%M:%S')  device=$D  每 ${INTERVAL}s"
logline " 正常 = [0,2533][1200,2608]（全宽）｜被裁 = 上拉手势会失效"
logline "================================================================"

PREV=""
while true; do
  RAW="$(probe)"
  REGION="${RAW##* | }"
  FRAME="${RAW%% | *}"
  MODE="$(adb -s "$D" shell settings get secure navigation_mode 2>/dev/null | tr -d '\r')"

  if [ "$REGION" != "$PREV" ]; then
    TS="$(date '+%Y-%m-%d %H:%M:%S')"
    if [ -z "$PREV" ]; then
      logline "$TS  基线 nav_mode=$MODE  frame=$FRAME  touchable=$REGION   [$(verdict "$FRAME" "$REGION")]"
    else
      logline "$TS  ★变化 nav_mode=$MODE  frame=$FRAME  touchable=$REGION   [$(verdict "$FRAME" "$REGION")]"
      logline "         （上一状态：$PREV）"
    fi
    PREV="$REGION"
  fi

  sleep "$INTERVAL"
done
