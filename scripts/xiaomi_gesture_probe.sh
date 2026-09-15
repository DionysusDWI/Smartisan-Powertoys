#!/usr/bin/env bash
# ============================================================================
#  xiaomi_gesture_probe.sh —— 小米 17 Pro Max 上滑手势失效 · H-A 实验探针
# ----------------------------------------------------------------------------
#  背景：手势模式下 GestureStubHome 的 touchableRegion 疑似被裁到右半
#        ([652,2534][1200,2608]，而 frame 是全宽 [0,2534][1200,2608])
#  目的：验证「从底部左/中/右侧上滑」的成功率差异，判定 H-A 是否成立
#
#  用法：
#     bash scripts/xiaomi_gesture_probe.sh                # 默认设备，交互式
#     bash scripts/xiaomi_gesture_probe.sh <serial>       # 指定设备
#     bash scripts/xiaomi_gesture_probe.sh <serial> restore  # 只切回三键
#
#  ★ 安全：结束时【自动切回三键导航】(navigation_mode=0)
#  ★ 只读 + 一次可逆设置写入，不碰用户数据
# ============================================================================
set -u

D="${1:-${ANDROID_SERIAL:-}}"
MODE="${2:-run}"
ADB="adb -s $D"
OUTDIR=".ref/_diag"
SF_ID="4630946457447247251"     # 主屏 SurfaceFlinger display id（⚠️ 非逻辑 displayId）

log() { printf '\033[1;36m[probe]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn ]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[fatal]\033[0m %s\n' "$*" >&2; exit 1; }

set_mode() { $ADB shell settings put secure navigation_mode "$1" >/dev/null; }
get_mode() { $ADB shell settings get secure navigation_mode | tr -d '\r'; }

RESTORED=0
restore() {
  [ "$RESTORED" = "1" ] && return 0
  RESTORED=1
  set_mode 0
  log "已切回三键导航 (navigation_mode=$(get_mode))"
}

# --- 退出/中断时一定切回，避免把用户晾在手势模式 -------------------------
trap 'restore' EXIT INT TERM

mode_name() {
  case "$1" in
    0) echo "三键导航" ;;
    1) echo "两键导航" ;;
    2) echo "全面屏手势" ;;
    *) echo "未知($1)" ;;
  esac
}

dump_stubs() {
  # 抓 GestureStub* 窗口的 frame / touchableRegion
  $ADB shell dumpsys input 2>/dev/null \
    | grep -E 'name=[0-9a-f]+ GestureStub' \
    | sed -E 's/.*name=[0-9a-f]+ (GestureStub[A-Za-z]+).*frame=(\[[^]]*\]\[[^]]*\]).*touchableRegion=(\[[^]]*\]\[[^]]*\]).*/\1|\2|\3/' \
    | while IFS='|' read -r name frame touch; do
        printf '  %-18s frame=%-24s touchableRegion=%s\n' "$name" "$frame" "$touch"
      done
}

dump_navbars() {
  $ADB shell dumpsys input 2>/dev/null \
    | grep -E "name=[0-9a-f]+ NavigationBar[0-9]*,.*touchableRegion=" \
    | sed -E 's/.*name=[0-9a-f]+ (NavigationBar[0-9]+), id=[0-9]+, displayId=([0-9]+).*frame=(\[[^]]*\]\[[^]]*\]).*touchableRegion=(\[[^]]*\]\[[^]]*\]).*/  \1 (display \2)  frame=\3  touchable=\4/' \
    | grep -v '^\s*$' | sort -u
}

snapshot() {
  local tag="$1"
  mkdir -p "$OUTDIR"
  $ADB shell dumpsys input > "$OUTDIR/d_input_${tag}.txt" 2>&1
  $ADB exec-out screencap -d "$SF_ID" -p > "$OUTDIR/scr_${tag}.png" 2>/dev/null
  log "快照 → $OUTDIR/d_input_${tag}.txt  +  scr_${tag}.png"
}

# ---------------------------------------------------------------------------
case "$MODE" in
  restore)
    restore
    exit 0
    ;;

  run)
    # --- 0. 连通性 ---
    $ADB get-state >/dev/null 2>&1 || die "设备 $D 不在线。先 adb connect $D"
    log "设备 $D 在线"

    CUR="$(get_mode)"
    log "当前导航模式 = $CUR ($(mode_name "$CUR"))"

    # --- 1. 先抓三键 baseline（对照组的 NavigationBar） ---
    if [ "$CUR" != "0" ]; then
      warn "当前不是三键模式，先切回三键取对照组"
      set_mode 0; sleep 2
    fi
    snapshot 3btn_probe
    log "── 三键模式 NavigationBar（应有【非空】touchableRegion）──"
    dump_navbars

    # --- 2. 切手势模式 ---
    echo
    log "切换到【全面屏手势】模式…"
    set_mode 2; sleep 3

    NEW="$(get_mode)"
    [ "$NEW" = "2" ] || die "切换失败（navigation_mode 仍为 $NEW）"
    log "已进入手势模式 (navigation_mode=$NEW)"

    snapshot gesture_probe
    log "── 手势模式 GestureStub 窗口 ──"
    log "（★ 重点看 GestureStubHome 的 frame 与 touchableRegion 是否【宽度不一致】）"
    dump_stubs
    echo
    log "── 手势模式 NavigationBar（手势下 touchableRegion 通常为空）──"
    dump_navbars

    # --- 3. 引导手动测试 ---
    cat <<'EOF'

================================================================
 ★ 现在请手动做实验（手机在您手上）
----------------------------------------------------------------
 在【屏幕最底部】上滑，每个位置做 3 次，记录成功次数：

   A. 底部【偏左】   x ≈ 200   （对应 1200 宽屏的左侧）
   B. 底部【正中】   x ≈ 600
   C. 底部【偏右】   x ≈ 1000

 手势：① 快速上滑 = 期望「回主屏」
       ② 上滑并【悬停】一下 = 期望「进多任务」

 判读：
   • 若【只有 C 成功】  ⇒ ★ H-A 成立（手势桩触摸区被裁到右半）
   • 若【A/B/C 都不稳定】⇒ 排除 H-A，转查 H-B（外接屏/触控板串扰）
   • 若【A/B/C 都正常】  ⇒ H-A 不成立，需重新取证（可能已自愈）
================================================================

按回车结束实验并【自动切回三键导航】…
EOF
    read -r _

    # --- 4. 收尾 ---
    echo
    log "实验结束。"
    ;;

  *)
    die "未知模式 '$MODE'（可用：run / restore）"
    ;;
esac

# trap 会在退出时自动 restore
