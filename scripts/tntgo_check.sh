#!/usr/bin/env bash
# ============================================================================
#  TNT GO 接入自检(坚果 Pro 3 / DT1901A)
#
#  用途:换用全功能 USB-C 线后,一键判断 TNT GO 的显示链路是否恢复正常。
#
#  用法:
#      source scripts/env.sh
#      bash scripts/tntgo_check.sh
#
#  原理:TNT GO 黑屏 = USB 2.0 数据链路正常但 DP Alt Mode 未建立。
#        本脚本按「PD 协商 → DP 链路 → USB 枚举 → TNT 桌面」四层逐项判定。
# ============================================================================
set -uo pipefail

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$WORKSPACE/scripts/env.sh" >/dev/null 2>&1

sh() { MSYS_NO_PATHCONV=1 adb shell "$@"; }

echo "=============================================="
echo " TNT GO 接入自检"
echo "=============================================="

# ---------- 0. 设备连接 ----------
echo
echo "[0] 检查 adb 设备"
devs=$(adb devices | sed '1d' | grep -c 'device$')
if [ "$devs" -eq 0 ]; then
  echo "  ✗ 没有检测到设备。请插上手机、开 USB 调试。"
  adb devices -l
  exit 1
fi
model=$(sh getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "  ✓ 已连接:$model"

# ---------- 1. PD 协商 ----------
echo
echo "[1] Type-C / PD 协商  (关键:PD=1 且 SOURCE_HIGH)"
pd_line=$(sh "dmesg 2>/dev/null | grep -iE 'smblib_update_usb_type' | tail -1" 2>/dev/null | tr -d '\r')
echo "  最后一条 USB 类型:${pd_line:-<无>}"
case "$pd_line" in
  *"PD=1"*) echo "  ✓ PD 协商已建立" ;;
  *"PD=0"*) echo "  ✗ PD 未协商(PD=0) —— 线材很可能仍不满足全功能要求" ;;
  *)        echo "  ? 无法判定" ;;
esac
src_line=$(sh "dmesg 2>/dev/null | grep -iE 'Type-C Source' | tail -1" 2>/dev/null | tr -d '\r')
echo "  供电能力:${src_line:-<无>}"
case "$src_line" in
  *high*) echo "  ✓ 已到 SOURCE_HIGH (3.0A)" ;;
  *medium*|*default*) echo "  ! 仅 medium/default —— 期望 high" ;;
esac

# ---------- 2. DP 链路 ----------
echo
echo "[2] DP 显示链路  (关键:出现 hpd_high 与 dp_display_post_enable)"
dp_ready=$(sh "dmesg 2>/dev/null | grep -c 'dp_display_post_enable: DP module is ready'" 2>/dev/null | tr -d '\r')
dp_res=$(sh "dmesg 2>/dev/null | grep 'dp_panel_resolution_info' | tail -1" 2>/dev/null | tr -d '\r')
dp_hpd=$(sh "dmesg 2>/dev/null | grep -iE 'hpd_high' | tail -1" 2>/dev/null | tr -d '\r')
echo "  DP ready 次数:$dp_ready"
echo "  最后一次分辨率:${dp_res:-<无>}"
echo "  最后 HPD:${dp_hpd:-<无>}"
if [ "${dp_ready:-0}" -gt 0 ]; then
  echo "  ✓ DP 链路曾建立(查看是否为最近一次接入)"
else
  echo "  ✗ DP 链路从未建立 —— 显示不通"
fi

# ---------- 3. USB 枚举 ----------
echo
echo "[3] USB 枚举  (期望:31ce:5101 Smartisan TNT go)"
sh "dmesg 2>/dev/null | grep -iE 'Product: Smartisan TNT go|Manufacturer: deltainno' | tail -2" 2>/dev/null | sed 's/^/  /'
tnt_usb=$(sh "dmesg 2>/dev/null | grep -c 'Product: Smartisan TNT go'" 2>/dev/null | tr -d '\r')
[ "${tnt_usb:-0}" -gt 0 ] && echo "  ✓ TNT GO 已枚举" || echo "  ✗ 未发现 TNT GO"

# ---------- 4. 显示面状态 ----------
echo
echo "[4] 显示面  (关键:displayId=100000 的 state 应为 ON)"
sh "dumpsys display 2>/dev/null | grep -E 'smt.tnt.virtual.display.*state' | head -1" 2>/dev/null | sed 's/^/  /'
vd_state=$(sh "dumpsys display 2>/dev/null | grep -E 'smt.tnt.virtual.display' | grep -oE 'state (ON|OFF)' | head -1" 2>/dev/null | tr -d '\r')
case "$vd_state" in
  *ON*)  echo "  ✓ 虚拟屏已点亮" ;;
  *OFF*) echo "  ✗ 虚拟屏仍为 OFF —— 桌面无法渲染" ;;
esac
echo "  物理外接屏:"
sh "dumpsys display 2>/dev/null | grep -oE 'DisplayDeviceInfo\{\"[^\"]*\", .*type HDMI' | head -1" 2>/dev/null | sed 's/^/    /'

# ---------- 5. TNT 桌面驻留 ----------
echo
echo "[5] TNT 桌面进程"
sh "ps -A 2>/dev/null | grep -E 'com.smartisanos.desktop$|com.android.desktop'" 2>/dev/null | awk '{printf "  %s %s\n", $2, $9}'
echo "  PC 模式设置:"
sh "settings list global 2>/dev/null | grep -E 'tnt_display_connected|global_pc_mode_settings'" 2>/dev/null | sed 's/^/    /'

# ---------- 6. 外设 ----------
echo
echo "[6] TNT GO 外设(线材不影响这些,应始终可用)"
audio=$(sh "dumpsys audio 2>/dev/null | grep -c 'usb_headset'" 2>/dev/null | tr -d '\r')
echo "  usb_headset 出现次数:$audio"
sh "dmesg 2>/dev/null | grep -oE 'input: deltainno Smartisan TNT go [A-Za-z ]+' | sort -u | sed 's/^/    /'" 2>/dev/null

# ---------- 总结 ----------
echo
echo "=============================================="
echo " 判定"
echo "=============================================="
if [ "${dp_ready:-0}" -gt 0 ] && [ "$vd_state" = "state ON" ]; then
  echo " ✓ 显示链路正常,TNT 桌面应已在屏上"
elif echo "$pd_line" | grep -q 'PD=1'; then
  echo " 部分正常:PD 已协商,但 DP 或显示面未就绪"
else
  echo " ✗ 显示链路未建立 —— 优先更换全功能 USB-C 线(USB3.2 Gen2 / USB4 / 雷电3·4,支持 DP Alt Mode,不经过 HUB)"
fi
echo
echo "提示:若刚插上,请等待约 5 秒让协商完成后再跑一次。"
