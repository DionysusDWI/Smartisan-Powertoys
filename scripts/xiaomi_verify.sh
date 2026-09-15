#!/usr/bin/env bash
# ============================================================================
#  xiaomi_verify.sh — 小米端 TNT 复刻 可行性一次性验证
#
#  对应清单: .paper/04-小米端复刻方案.md §11「必须在小米真机上先验证的清单」(10 条)
#
#  用法:
#     bash scripts/xiaomi_verify.sh                 # ★ 默认:只读探测(绝对安全)
#     bash scripts/xiaomi_verify.sh --write         # 追加:受控写入测试(会改设置,自动回滚)
#     bash scripts/xiaomi_verify.sh --serial <SN>   # 指定设备
#
#  产出: .ref/xiaomi_verify/<时间戳>/
#          raw/     每个探针的原始输出(逐条一文件)
#          REPORT.md 汇总报告(★ 人读这个)
#
#  ★ 设计纪律(来自本项目踩过的坑):
#    1) 默认只读;写入必须显式 --write
#    2) 任何写入:先记原值 → 写 → 验【下游行为】(不是读回!)→ 回滚
#    3) 每条探测失败不中断,记录后继续
#    4) 背光/面板类变化截图看不出来 —— 本脚本不依赖截图
# ============================================================================

set -u

# ── 模式 ────────────────────────────────────────────────────────────
WRITE=0
SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --write)  WRITE=1 ;;
    --serial) shift; SERIAL="${1:-}" ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
TS="$(date '+%Y%m%d_%H%M%S')"
OUT="$ROOT/.ref/xiaomi_verify/$TS"
RAW="$OUT/raw"
mkdir -p "$RAW"

# ── adb 定位 ────────────────────────────────────────────────────────
ADB="adb"
if [ -f "$ROOT/scripts/env.sh" ]; then
  # shellcheck disable=SC1091
  . "$ROOT/scripts/env.sh" >/dev/null 2>&1 || true
  ADB="${ADB:-adb}"
fi

# ★ 已知的坚果 Pro 3 —— 绝不能把它当小米设备
#  ★ 公开镜像：这里原是开发者自己的内网地址。置空后
#    「跳过已知 Pro 3」那段会被 `[ -n "$KNOWN_PRO3_IP" ]` 短路 ——
#    ⚠️ **不能留一个空串去参与 glob**，那会匹配到所有设备。
KNOWN_PRO3_IP="${KNOWN_PRO3_IP:-}"
KNOWN_PRO3_MODEL="DT1901A"

say() { printf '%s\n' "$*"; }
hr()  { printf '%s\n' "----------------------------------------------------------------"; }

# ── 选设备 ──────────────────────────────────────────────────────────
DEVICES="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
  say "❌ 没有已连接的 adb 设备。"
  say "   请先: adb devices / 打开小米的 USB 调试并授权"
  exit 1
fi

PICK=""
if [ -n "$SERIAL" ]; then
  PICK="$SERIAL"
else
  # 自动排除坚果 Pro 3(按网络地址与型号)
  for d in $DEVICES; do
    #  ★★ 这个 glob 的前提是 KNOWN_PRO3_IP **非空**。
    #     ⚠️ 若留一个空串进去，`""*` 会匹配【所有】设备 ⇒ **全部被跳过**，
    #        最后报"没找到小米设备" —— 一个**由空变量造成的、指向错误方向的**报错。
    #     （公开镜像里这个值默认是空的 ⇒ 这个判断不是防御性代码，是必需的。）
    if [ -n "$KNOWN_PRO3_IP" ]; then
      case "$d" in "$KNOWN_PRO3_IP"*) continue ;; esac
    fi
    m="$(MSYS_NO_PATHCONV=1 adb -s "$d" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    [ "$m" = "$KNOWN_PRO3_MODEL" ] && continue
    PICK="$d"; break
  done
fi
[ -n "$PICK" ] || { say "❌ 只找到坚果 Pro 3,没有小米设备。用 --serial 显式指定。"; exit 1; }

D=(-s "$PICK")
say "▶ 目标设备: $PICK"
say "▶ 模式: $([ $WRITE -eq 1 ] && echo '只读 + 受控写入' || echo '只读(安全)')"
say "▶ 输出: $OUT"
hr

# ── 探针工具 ────────────────────────────────────────────────────────
N=0
EMPTY=0
RESULT_TXT="$OUT/SUMMARY.txt"
: > "$RESULT_TXT"

probe() {  # probe <编号> <标题> <shell 命令...>
  N=$((N+1))
  local id="$1"; shift
  local title="$1"; shift
  local f
  f="$(printf '%s/%02d_%s.txt' "$RAW" "$N" "$(echo "$id" | tr -c 'A-Za-z0-9_.-' '_')")"
  # ★ 注意:shell 命令整体作为一个字符串传给 adb shell
  MSYS_NO_PATHCONV=1 adb "${D[@]}" shell "$@" >"$f" 2>&1
  local rc=$?
  local sz
  sz=$(wc -c <"$f" | tr -d ' ')

  # 分类只看【输出内容】,不看退出码 —— 这类探测里退出码不可靠:
  #   grep 无匹配 → 1;而管道以 head 结尾时 → 永远 0。两者都不代表探测失败。
  # ★ 空输出【不是错误】—— 「本机没有该特性」正是我们要测的东西。
  local st
  if [ "$sz" -le 2 ]; then st="EMPTY"; else st="OK"; fi

  if [ "$st" = "OK" ]; then
    printf '  [%02d] ✅ %-44s %6s B\n' "$N" "$title" "$sz" >&2
  else
    EMPTY=$((EMPTY+1))
    printf '  [%02d] ∅  %-44s %6s B  ← 空输出(= 本机无此特性,非错误)\n' "$N" "$title" "$sz" >&2
  fi
  printf '%02d\t%s\t%s\t%s\t%s\n' "$N" "$st" "$id" "$title" "$sz" >> "$RESULT_TXT"
  echo "$f"
}

note() { printf '  ·  %s\n' "$*"; }

# ════════════════════════════════════════════════════════════════════
say "══ 1 · 设备与环境 ══"
# ════════════════════════════════════════════════════════════════════
probe dev_model      "设备型号/品牌" "getprop ro.product.model; getprop ro.product.brand; getprop ro.product.device" >/dev/null
probe dev_version    "系统版本"      "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.display.id" >/dev/null
probe dev_hyperos    "HyperOS 版本"  "getprop ro.mi.os.version.name; getprop ro.mi.os.version.incremental; getprop ro.miui.ui.version.name" >/dev/null
probe dev_abi        "ABI / 架构"    "getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist" >/dev/null
probe dev_buildtype  "构建类型"      "getprop ro.build.type; getprop ro.debuggable; getprop ro.secure" >/dev/null

# ════════════════════════════════════════════════════════════════════
say "══ 2 · 清单#1 Shizuku 状态 ══"
# ════════════════════════════════════════════════════════════════════
probe shizuku_pkg     "Shizuku 是否安装"  "pm list packages | grep -i shizuku || echo '(未安装)'" >/dev/null
probe shizuku_proc    "Shizuku 进程"      "ps -A | grep -i shizuku || echo '(无进程)'" >/dev/null
probe shizuku_server  "Shizuku server"    "ps -A | grep -iE 'shizuku_server|app_process' || echo '(无)'" >/dev/null
probe shell_uid       "shell 身份自检"    "id" >/dev/null
note "Shizuku 的【稳定性】需反复跑 id / 切 Wi-Fi 后重跑 / 熄屏 10 分钟 —— 脚本无法替代,见 .paper/04 §11.1"

# ════════════════════════════════════════════════════════════════════
say "══ 3 · 清单#4/#7 显示器与副屏 ══"
# ════════════════════════════════════════════════════════════════════
probe display_all     "dumpsys display 全文"  "dumpsys display" >/dev/null
probe display_mgr     "DisplayManager 摘要"   "dumpsys display | grep -E 'Display [0-9]+|mDisplayId=|mFlags=|uniqueId=|DisplayDeviceInfo' | head -120" >/dev/null
probe display_size    "wm size / density"     "wm size; wm density" >/dev/null
probe display_ids     "displayId 列表"        "dumpsys display | grep -oE 'mDisplayId=[0-9]+' | sort -u" >/dev/null

# ════════════════════════════════════════════════════════════════════
say "══ 4 · 清单#2/#5 freeform 与桌面模式 ══"
# ════════════════════════════════════════════════════════════════════
probe set_freeform    "键:enable_freeform_support"        "settings get global enable_freeform_support" >/dev/null
probe set_forcedesktop "键:force_desktop_mode_on_external_displays" "settings get global force_desktop_mode_on_external_displays" >/dev/null
probe set_devopts     "键:development_enable_freeform_windows_support(应为 null)" "settings get global development_enable_freeform_windows_support" >/dev/null
probe set_pc          "键:pc_mode_enable"                  "settings get secure pc_mode_enable" >/dev/null
probe set_allrel      "全部相关键"                          "settings list global | grep -iE 'freeform|desktop|window|display' ; echo '--- secure ---'; settings list secure | grep -iE 'freeform|desktop|window|display'" >/dev/null
probe prop_desktop    "桌面模式相关 prop"                   "getprop | grep -iE 'desktop|freeform|windowing|pc_mode'" >/dev/null

# ════════════════════════════════════════════════════════════════════
say "══ 5 · 清单#8 通知与无障碍 ══"
# ════════════════════════════════════════════════════════════════════
probe nls_enabled     "已启用的通知监听器"  "settings get secure enabled_notification_listeners" >/dev/null
probe a11y_enabled    "已启用的无障碍服务"  "settings get secure enabled_accessibility_services" >/dev/null
probe nls_policy      "NLS 策略"            "cmd notification get_notification_channels 2>&1 | head -5; cmd notification --help 2>&1 | head -20" >/dev/null

# ════════════════════════════════════════════════════════════════════
say "══ 6 · 清单#3 任务与窗口模式 ══"
# ════════════════════════════════════════════════════════════════════
probe act_tasks       "现有任务"            "dumpsys activity activities | grep -E 'ActivityRecord|mResumedActivity|windowingMode|mWindowingMode' | head -80" >/dev/null
probe act_am_help     "am 可用子命令"       "am help | grep -iE 'task|display|stack|start' | head -40" >/dev/null
probe task_resize_ok  "am task resize 是否存在" "am task resize 2>&1 | head -5" >/dev/null
probe win_displays    "窗口与显示"          "dumpsys window displays | grep -E 'Display: |mCurrentFocus|mFocusedApp' | head -40" >/dev/null
probe wm_policy       "WM 策略属性"         "dumpsys window | grep -iE 'mSupportsFreeform|mSupportsMultiWindow|mSupportsPictureInPicture|forceDesktop' | head -20" >/dev/null

# ════════════════════════════════════════════════════════════════════
say "══ 7 · 清单#6/#9 输入与热插拔 ══"
# ════════════════════════════════════════════════════════════════════
probe input_help      "input 命令行签名"    "input 2>&1 | head -30" >/dev/null
probe input_devices   "输入设备"            "getevent -pl 2>/dev/null | grep -E 'add device|name:' | head -40" >/dev/null
probe disp_listeners  "显示热插拔监听(日志)" "logcat -d -t 200 2>/dev/null | grep -iE 'DisplayListener|onDisplayAdded|onDisplayRemoved|display.*changed' | tail -20 || echo '(无)'" >/dev/null

# ════════════════════════════════════════════════════════════════════
say "══ 8 · 清单#10 wm size/density 能力(只读探查) ══"
# ════════════════════════════════════════════════════════════════════
probe wm_help         "wm 子命令"           "wm 2>&1 | head -30" >/dev/null
note "wm size/density -d 的写入测试会改显示配置 ⇒ 默认不跑;需 --write 且人工确认"

# ════════════════════════════════════════════════════════════════════
if [ $WRITE -eq 1 ]; then
  hr
  say "══ 9 · 受控写入测试(会改设置,自动回滚)══"
  hr
  MSYS_NO_PATHCONV=1 adb "${D[@]}" shell "sh -s" <<'EOS' 2>&1 | tee "$RAW/90_write_tests.txt"
set -u
echo "### 受控写入测试 (每个键:记原值 → 写 → 读回 → 验下游 → 回滚)"
echo

KEY=enable_freeform_support
OLD=$(settings get global $KEY)
echo "--- $KEY ---"
echo "原值: [$OLD]"
settings put global $KEY 1
echo "写入后读回: [$(settings get global $KEY)]"
echo "★ 下游行为(dumpsys window):"
dumpsys window 2>/dev/null | grep -iE 'mSupportsFreeformWindowManagement|freeform' | head -6
echo "★ 提示: 读回 ≠ 生效 —— 必须看上面这行的下游值"
# 回滚
if [ "$OLD" = "null" ]; then settings delete global $KEY; else settings put global $KEY "$OLD"; fi
echo "已回滚为: [$(settings get global $KEY)]"
echo

echo "--- 说明 ---"
echo "其余写入项(freeform 启动 / resize / force_desktop_mode)需交互确认,"
echo "见 .paper/plans/J1-小米真机验证.md 的 P2 步骤 —— 脚本不自动执行。"
EOS
fi

# ════════════════════════════════════════════════════════════════════
# 汇总报告
# ════════════════════════════════════════════════════════════════════
{
  echo "# 小米端验证 · 原始报告"
  echo
  echo "> 设备: \`$PICK\` ｜ 时间: $TS ｜ 模式: $([ $WRITE -eq 1 ] && echo '只读+受控写入' || echo '只读')"
  echo "> 探针 $N 条;其中**空输出 $EMPTY 条**(空输出 = 本机无此特性,**不是错误**) "
  echo
  echo "## 探针清单"
  echo
  echo "| # | 输出 | 编号 | 标题 | 字节 |"
  echo "|---|---|---|---|---|"
  while IFS=$'\t' read -r id st key title sz; do
    [ -n "${id:-}" ] || continue
    echo "| $id | $([ "$st" = OK ] && echo '✅' || echo '∅') | \`$key\` | $title | $sz |"
  done < "$RESULT_TXT"
  echo
  echo "## ★ 判读指引"
  echo
  echo "逐条原始输出见 \`raw/\`。**关键项请人工判读**,对照"
  echo "\`.paper/04-小米端复刻方案.md\` §11 的十条清单。"
  echo
  echo "⚠️ **空输出 ≠ 探测失败** —— 例如「桌面模式相关 prop」为空,"
  echo "正是我们想知道的「HyperOS 没有暴露该 prop」。**判读要看内容,不看字节数。**"
} > "$OUT/REPORT.md"

hr
say "✅ 完成:探针 $N 条(空输出 $EMPTY 条 —— 属正常)"
say "   报告: $OUT/REPORT.md"
say "   原始: $RAW/"
if [ $WRITE -eq 0 ]; then
  say ""
  say "   ⚠️ 本次为【只读】。下一步(需人工在场):"
  say "      bash scripts/xiaomi_verify.sh --write"
fi
exit 0
