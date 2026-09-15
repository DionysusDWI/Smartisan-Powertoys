#!/usr/bin/env bash
# ============================================================================
#  构建并侧载 APK 到手机（**含 mod 的后置授权步骤**）
#
#  用法:
#     scripts/deploy.sh                      # 默认目标集
#     scripts/deploy.sh mode                 # MODE 启动器
#     scripts/deploy.sh mod-tntgo            # TNT GO 电量 mod
#     scripts/deploy.sh mode-all             # 启动器 + 全部 mod
#     scripts/deploy.sh mode-all -r          # 只重装已构建好的 APK，不重新构建
#     scripts/deploy.sh --list               # 列出目标
#
#  设备:
#     默认用 adb 当前唯一设备。无线 adb 或多设备时显式指定：
#         ANDROID_SERIAL=<你的设备序列号> bash scripts/deploy.sh mode-all
#
#  ★★★ 关于「装完之后还要人肉做一步」——这是本脚本存在的最大理由
#
#  Smartisan（坚果 A10）上，**overlay 有【两道独立的门】**：
#
#    ① Android 的 appops:  SYSTEM_ALERT_WINDOW
#       ⇒ 本脚本用 `appops set … allow` **自动**授掉
#    ② ★ Smartisan 自己的悬浮窗授权（手机管理 → 权限管理 → <app> → 悬浮窗）
#       ⇒ ★ **adb 完全授不了**，必须人在手机上点
#
#  只过①不过②的症状极具迷惑性：**窗口加得上、bounds 也对、logcat 一个错都没有，
#  但 `mPolicyVisibility=false` 永远不显示**。
#  （2026-09-12 为此排查了一整轮，详见 .paper/plans/Q2-TNTGO电量mod.md §5.3/§5.7）
#
#  ⇒ 所以脚本结尾会**明确印出**那条待办，不让它再变成"莫名其妙不显示"。
# ============================================================================
set -uo pipefail

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$WORKSPACE/scripts/env.sh" >/dev/null
# shellcheck source=./targets.sh
source "$WORKSPACE/scripts/targets.sh"

# --- 参数 --------------------------------------------------------------------
if [ "${1:-}" = "--list" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  echo "可部署目标："
  for n in $(tnt_target_names); do
    printf '  %-12s  %s\n' "$n" "$(tnt_gradle_tasks "$n")"
  done
  [ "${1:-}" = "--list" ] && exit 0
  echo
  echo "用法: scripts/deploy.sh [目标…] [-r]"
  echo "      -r  只重装已构建好的 APK，不重新构建"
  exit 0
fi

skip_build=""
targets=()
for arg in "$@"; do
  if [ "$arg" = "-r" ]; then skip_build="-r"; else targets+=("$arg"); fi
done
if [ ${#targets[@]} -eq 0 ]; then
  targets=("${TNT_DEFAULT_TARGETS[@]}")
fi

# --- 设备检查 ----------------------------------------------------------------
count="$(adb devices | sed '1d' | grep -c 'device$')"
if [ "$count" -eq 0 ]; then
  echo "[deploy] 没有检测到设备。" >&2
  echo "         坚果走无线 adb 时：" >&2
  echo "           ANDROID_SERIAL=<adb devices 里的序列号> bash scripts/deploy.sh …" >&2
  adb devices -l >&2
  exit 1
fi
if [ "$count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "[deploy] ⚠️ 有 $count 台设备，未指定 ANDROID_SERIAL —— adb 可能选错。" >&2
fi
echo "[deploy] 设备: ${ANDROID_SERIAL:-（adb 默认）}  共 $count 台"

# --- 构建 --------------------------------------------------------------------
if [ "$skip_build" != "-r" ]; then
  bash "$WORKSPACE/scripts/build.sh" "${targets[@]}" || exit 1
fi

# --- 安装 + 后置 -------------------------------------------------------------
need_manual_overlay=()   # 需要人去手机管理开悬浮窗的包
need_usb=()              # 需要人授 USB 的包
need_a11y=()             # 需要人去「无障碍」里启用的包（★ 任务 AI 新增）

# ★★★★ 任务 AL：合并后要【先卸掉旧的独立 mod 包】，
#   否则同一个 mod 会出现两份（新旧各一个 service）⇒ 启动器列表里重复、
#   而且"两个都开"时两个进程抢同一份资源（串口 / overlay / 按键过滤器）。
for name in "${targets[@]}"; do
  legacy="$(tnt_uninstall "$name")"
  [ -z "$legacy" ] && continue
  echo "[deploy] ★ 迁移：卸载旧的独立 mod 包"
  for p in $legacy; do
    if adb shell pm path "$p" >/dev/null 2>&1; then
      adb uninstall "$p" >/dev/null 2>&1 && echo "[deploy]   ✓ 已卸 $p" || echo "[deploy]   ⚠ 卸 $p 失败"
    else
      echo "[deploy]   - $p 本来就没装"
    fi
  done
done

for name in "${targets[@]}"; do
  if ! tnt_has_target "$name"; then
    echo "[deploy] 未知目标: $name" >&2
    exit 1
  fi

  n="$(tnt_module_count "$name")"
  i=0
  while [ "$i" -lt "$n" ]; do
    mod="$(echo "${TNT_MODULES[$name]}" | tr ' ' '\n' | sed -n "$((i + 1))p")"
    apk="$(tnt_apk_path "$name" "$i")"
    pkg="$(tnt_pkg "$name" "$i")"
    flags="$(tnt_flags "$name" "$i")"

    if [ ! -f "$apk" ]; then
      echo "[deploy] ✗ 找不到 APK: ${apk#$WORKSPACE/}（先跑一次构建）" >&2
      exit 1
    fi

    echo "[deploy] 安装 ${mod#:}  →  $pkg"
    adb install -r -t "$apk" 2>&1 | tail -3

    case ",$flags," in
      *,overlay,*)
        # ⚠️ sleep 不能省：install -r 之后 appop 是【异步】重置的，
        #    立刻 set 会被随后的重置覆盖（见计划书 Q1 §0.2）
        sleep 6
        adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
          && echo "[deploy]   ✓ appops SYSTEM_ALERT_WINDOW → allow"
        need_manual_overlay+=("$pkg")
        ;;
    esac

    case ",$flags," in
      *,usb,*) need_usb+=("$pkg") ;;
    esac

    case ",$flags," in
      *,a11y,*)
        # ★★★★ 任务 AL：无障碍现在**可以自动登记**（以前只打印待办）。
        #   ★ 依据：实测 `settings put secure enabled_accessibility_services <comp>`
        #     ＋ `accessibility_enabled 1` 确实生效（dumpsys 里能看到 Bound services）。
        #   ⚠️ `am force-stop <pkg>` 会清掉这个登记 ⇒ 每次部署都要重设。
        comp="$(tnt_a11y "$name")"
        if [ -n "$comp" ]; then
          adb shell settings put secure enabled_accessibility_services "$comp" >/dev/null 2>&1
          adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
          sleep 2
          if adb shell dumpsys accessibility 2>/dev/null | grep -q "$comp"; then
            echo "[deploy]   ✓ 无障碍已自动登记: $comp"
          else
            echo "[deploy]   ⚠ 无障碍自动登记没生效，请手动启用: $comp"
            need_a11y+=("$pkg")
          fi
        else
          need_a11y+=("$pkg")
        fi
        ;;
    esac

    i=$((i + 1))
  done
done

# --- ★ 待办清单（脚本干不了的那些）------------------------------------------
echo
if [ ${#need_manual_overlay[@]} -gt 0 ]; then
  echo "════════════════════════════════════════════════════════════════════"
  echo "★ 还需要你在【手机上】做一步 —— adb 授不了这一道"
  echo
  echo "  Smartisan 的 overlay 有【两道门】，脚本只过了第一道："
  echo "    ① Android appops  SYSTEM_ALERT_WINDOW   ← ✅ 上面已自动授"
  echo "    ② Smartisan 悬浮窗（手机管理→权限管理）  ← ⛔ 只能人点"
  echo
  echo "  只过①不过②的症状：窗口加得上、logcat 无错，但【永远不显示】。"
  echo
  echo "  请对下面每个包打开一次："
  echo "        手机管理 → 权限管理 → <应用> → 悬浮窗 → 打开"
  for p in "${need_manual_overlay[@]}"; do
    echo "          · $p"
  done
  echo "════════════════════════════════════════════════════════════════════"
fi

if [ ${#need_usb[@]} -gt 0 ]; then
  echo
  echo "★ USB：首次启动时系统会弹「允许应用…访问该 USB 设备吗？」"
  echo "  建议勾上「默认情况下用于该 USB 设备」—— 以后插 TNT GO 就不再弹。"
  echo "  （重装 APK 后可能需要重授一次）"
  for p in "${need_usb[@]}"; do
    echo "          · $p"
  done
fi

if [ ${#need_a11y[@]} -gt 0 ]; then
  echo
  echo "════════════════════════════════════════════════════════════════════"
  echo "★ 无障碍服务 —— 这一道 adb 也授不了,必须人手开"
  echo
  echo "  设置 → 无障碍 → 已下载的服务 → 「TNT GO 亮度键」→ 打开"
  echo
  echo "  ★ 为什么必须开：这个 mod 靠 AccessibilityService 的【按键过滤器】"
  echo "    抢先拿到键盘上的亮度键（系统本来会吃掉它们,去弹「暂不支持该设备的亮度调节」）。"
  echo "    不开的症状：按键完全没反应 —— 和「没插键盘」长得一模一样。"
  echo "  ⚠️ 重装 APK 之后这一项有时会被系统关掉,回这一页复查一眼。"
  for p in "${need_a11y[@]}"; do
    echo "          · $p"
  done
  echo "════════════════════════════════════════════════════════════════════"
fi

echo
echo "[deploy] 完成。"
