#!/system/bin/sh
# ============================================================
# at_send.sh · TNT GO AT 命令「先校验后发送」批量工具
#
#   取代 at_batch.sh 的 input text 直发做法。
#   根因：input text 注入长字符串会被截断
#        （实测 AT+I2CERRORNUM → AT+I，见 HND_OP_0008 §4）
#   对策：分段输入 → uiautomator dump 回读输入框 → 逐字比对
#        → 只有完全一致才点「发送」；否则清空重试，最多 3 次
#
#   坐标不硬编码：全部从 uiautomator dump 里读 bounds 现算。
#
# 用法：
#   1) 让探针 APK 位于前台（手机屏上能看到输入框）
#   2) sh /sdcard/at_send.sh
#   3) 结果：/sdcard/at_send_result.txt     日志：/sdcard/at_send.log
#
#   ★ 改命令只需改下面 CMDS= 一行（空格分隔）
#   ★ 只放「查询型」命令！设置型（返回裸 OK）必须逐条人工发。
# ============================================================

PKG=com.shware.tntgo.battery
CONSOLE=/sdcard/Android/data/$PKG/files/probe/tntgo_console.txt
DUMP=/sdcard/_at_dump.xml
OUT=/sdcard/at_send_result.txt
LOG=/sdcard/at_send.log

# ---- 命令表（★ 只放查询型）----------------------------------
CMDS="AT+STATE AT+ST AT+CHECK AT+REPORT AT+OBD AT+GETGPIO AT+I2CERRORNUM"
# -------------------------------------------------------------

# ---- 禁区：与探针 APK 的黑名单一致，双保险 -------------------
FORBIDDEN="SHUTDOWN PWROFF POWEROFF REBOOT RESET RECOVERY UPGRADE FLASHWRITE
OTPWRITE SCALERUPDATE SETFW SETFWMODE ERASE SETGPIO SLEEP STARTUP SYSTEM
DATACLR BKPCLR I2CERRORCLEAR SETDEVICERESET DPDIRECT DPSCALER DPDEBUG
HDMISCALER USBSTATE TYPECUSB LCDON LCDOFF DSPON DSPOFF CAMON CAMOFF WIFI
TPSLEEP TYPEPIN TYPECPIN ADB"
# -------------------------------------------------------------

MAX_TRY=3
CHUNK=6          # 每段字符数
TAP_DELAY=1      # 点击后等待
TYPE_DELAY=1     # 每段输入后等待
SEND_WAIT=3      # 点发送后等待取结果

: > "$OUT"
echo "start $(date '+%F %T')" > "$LOG"

log() { echo "$1" >> "$LOG"; }
die() { echo "FATAL: $1" >> "$LOG"; echo "FATAL: $1"; exit 1; }

# ---- 工具：把当前 UI dump 到 $DUMP ---------------------------
ui_dump() {
  rm -f "$DUMP"
  uiautomator dump "$DUMP" >/dev/null 2>&1
  [ -s "$DUMP" ] || return 1
  return 0
}

# ---- 工具：取某个 resource-id 的节点行 -----------------------
# uiautomator 的属性顺序固定为 ... text ... resource-id ... bounds
# 所以整段节点一起取，才能同时拿到 text 与 bounds
# （用 tr 而不是 sed 切分：Android 的 sed 对替换串里的 \n 支持不稳）
node_of() {
  tr '<' '\n' < "$DUMP" | grep "resource-id=\"$PKG:id/$1\"" | head -1
}

# ---- 工具：从节点行里取属性值 --------------------------------
attr() {  # attr <节点行> <属性名>
  echo "$1" | sed -n "s/.*$2=\"\([^\"]*\)\".*/\1/p"
}

# ---- 工具：bounds="[x1,y1][x2,y2]" -> "cx cy" ---------------
center_of() {  # center_of <bounds串>
  echo "$1" | sed -n 's/\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]/\1 \2 \3 \4/p' \
  | while read x1 y1 x2 y2; do
      echo $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    done
}

# ---- 工具：命令是否在禁区 -----------------------------------
is_forbidden() {  # is_forbidden <命令>
  c=$(echo "$1" | tr 'a-z' 'A-Z' | sed 's/^AT+*//; s/^+*//')
  for f in $FORBIDDEN; do
    case "$c" in "$f"*) return 0 ;; esac
  done
  return 1
}

# ============================================================
#  主流程
# ============================================================

log "--- 定位 UI ---"
ui_dump || die "uiautomator dump 失败（探针 APK 是否在前台？）"

ET_NODE=$(node_of etCmd)
SEND_NODE=$(node_of btnSend)
[ -n "$ET_NODE" ] || die "找不到输入框 etCmd"
[ -n "$SEND_NODE" ] || die "找不到发送键 btnSend"

ET_B=$(attr "$ET_NODE" bounds)
SEND_B=$(attr "$SEND_NODE" bounds)
[ -n "$ET_B" ] || die "取不到 etCmd 的 bounds"
[ -n "$SEND_B" ] || die "取不到 btnSend 的 bounds"

set -- $(center_of "$ET_B");   ET_X=$1;   ET_Y=$2
set -- $(center_of "$SEND_B"); SEND_X=$1; SEND_Y=$2
log "输入框 ($ET_X,$ET_Y)  发送键 ($SEND_X,$SEND_Y)"

for c in $CMDS; do
  echo "===== $c =====" >> "$OUT"

  # --- 禁区双保险 ---
  if is_forbidden "$c"; then
    echo "  跳过：命中禁区黑名单（设置型/危险命令，必须人工逐条发）" >> "$OUT"
    log "FORBIDDEN skip: $c"
    continue
  fi

  ok=0
  try=1
  while [ $try -le $MAX_TRY ]; do
    # 1) 聚焦 + 清空
    input tap "$ET_X" "$ET_Y"; sleep $TAP_DELAY
    i=0
    while [ $i -lt 30 ]; do input keyevent 67; i=$((i+1)); done
    sleep $TAP_DELAY

    # 2) 分段输入（★ 规避长串截断）
    rest="$c"
    while [ -n "$rest" ]; do
      part=$(echo "$rest" | cut -c1-$CHUNK)
      input text "$part"
      rest=$(echo "$rest" | cut -c$((CHUNK+1))-)
      sleep 0.4
    done
    sleep $TYPE_DELAY

    # 3) ★★ 回读校验 —— 不一致就不发
    ui_dump
    NOW=$(attr "$(node_of etCmd)" text)
    log "try$try want=[$c] got=[$NOW]"

    if [ "$NOW" = "$c" ]; then
      ok=1
      break
    fi
    echo "  (第 $try 次输入不符：期望 [$c] 实得 [$NOW]，重试)" >> "$OUT"
    try=$((try+1))
  done

  if [ $ok -ne 1 ]; then
    echo "  ⛔ 放弃：$MAX_TRY 次输入均无法还原成 [$c]" >> "$OUT"
    log "GIVE UP: $c"
    echo >> "$OUT"
    continue
  fi

  # 4) 校验通过，才点发送
  #    先记下 console 已有长度，之后只取「新增」部分（否则每轮都把历史全抄一遍）
  [ -f "$CONSOLE" ] && BEFORE=$(wc -c < "$CONSOLE") || BEFORE=0

  input tap "$SEND_X" "$SEND_Y"
  sleep $SEND_WAIT

  # 5) 只取新增的输出
  if [ -f "$CONSOLE" ]; then
    tail -c +$((BEFORE + 1)) "$CONSOLE" >> "$OUT" 2>/dev/null
  fi
  echo >> "$OUT"
  log "sent OK: $c at $(date '+%T')"
done

echo "finished $(date '+%F %T')" >> "$LOG"
echo "完成。结果 -> $OUT   日志 -> $LOG"
