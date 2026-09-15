#!/usr/bin/env bash
# measure_serial_contention.sh —— 量化 TNT GO 串口的**实际**争抢情况。
#
# ★★ 为什么需要它
# --------------
# AS 计划书 §0「理由 1」曾把「串口争抢」称为 **既存 bug**，
# 而它的全部依据是**代码注释里的机理陈述**（"claimInterface 会 EBUSY"）
# ＋ 一句**从未跑过的预测**（"`grep -c "openDevice 失败"` 应当 > 0"）。
#
# 2026-09-15 第一次真的跑了：
#
#     电量 mod 39 次读 —— 0 次失败；重试 / Busy / Rejected / openDevice 失败 全为 0
#
# ⇒ **"既存 bug" 降级为"潜在风险"。**
#
# 教训：**写进计划书的「既存 bug」，必须先有一条 grep 出来的实测计数。**
#      本脚本就是产出那条计数的工具。
#
# 用法：
#   bash scripts/measure_serial_contention.sh            # 分析当前 logcat 缓冲区
#   bash scripts/measure_serial_contention.sh --reset    # 先清缓冲区再分析（推荐：干净窗口）
#   ANDROID_SERIAL=<你的设备序列号> bash scripts/measure_serial_contention.sh
#
# ★ AS3 的验收判据（AS3b 后**改过一次**）：
#   · 改造前：`争抢迹象` 应为 **0**（当时"争抢"= 读失败）
#   · ★★ 改造后：判据拆成两行，因为 **AS3b 故意引入了一种"良性争抢"**：
#       `让路`（端口被占 ⇒ 电量侧立刻跳过）—— 这是**设计行为，应该 > 0**
#       而 `读失败 / openDevice 失败`（未处理的冲突）—— **必须 = 0**
#   ⚠️ 把「让路」也算进"争抢"会让这个脚本**永远红**，而且会诱导人去"消灭"正确行为。

set -u
cd "$(dirname "$0")/.." || exit 1
# shellcheck disable=SC1091
source scripts/env.sh >/dev/null 2>&1
export MSYS_NO_PATHCONV=1

SERIAL="${ANDROID_SERIAL:-}"
ADB="adb -s $SERIAL"
LOG="${TMPDIR:-/tmp}/tntgo_contention.log"

if [ "${1:-}" = "--reset" ]; then
    echo "→ 清空 logcat 缓冲区（先跑这个，等几分钟再分析）"
    $ADB logcat -c
    exit 0
fi

$ADB logcat -d -v time > "$LOG" 2>/dev/null

echo "════════ TNT GO 串口争抢实测 ════════"
echo "设备:       $SERIAL"
echo "logcat 行数: $(wc -l < "$LOG")"
echo "时间跨度:   $(head -1 "$LOG" | cut -c1-18)  →  $(tail -1 "$LOG" | cut -c1-18)"
echo

echo "──── 电量 mod（每 30 s 读一次）────"
# ★★★ AS3b 之后日志格式变了（TAG 从 `ModeMod/Tntgo` 统一成 `ModeMod/Serial`，
#     行里多了 `[at+batcg]`）。**旧模式会让"成功读到"永远显示 0** ——
#     2026-09-15 真的踩了一次：差点据此得出"mod 不再轮询"的错误结论。
#     ⇒ 判据必须跟着**被观测对象**一起演进；改格式就要回来改这里。
printf '  成功读到 +BATCG       %s\n' "$(grep -ac 'ModeMod/Serial.*\[at+batcg\].*+BATCG=' "$LOG")"
# ★★ 「让路」是 AS3b 的**设计行为**（端口被别人占 ⇒ 本轮跳过），**不是故障**。
printf '  ★ 让路（设计行为）     %s\n' "$(grep -ac '本轮让路' "$LOG")"
printf '  读失败（真故障）       %s\n' "$(grep -acE 'ModeMod/Tntgo.*读失败' "$LOG")"
# ★★★ 自嵌套（**必须 = 0**）—— 同一线程已持有端口（典型：`Stream` 还开着）又去调 `exec`。
#     2026-09-15 实机抓到：亮度 mod 长按结束后"补发"等了自己 **6.5 秒**。
#     现在 `TntgoSerial` 会**立刻拒绝并打这行日志** ⇒ 一旦 > 0 就是回归。
printf '  ★★ 自嵌套（必须 0）    %s\n' "$(grep -ac '拒绝自嵌套' "$LOG")"
printf '  采样断档事件           %s\n' "$(grep -ac '采样断档' "$LOG")"
printf '  容量估计产出           %s\n' "$(grep -ac '学到一个容量估计' "$LOG")"
echo

echo "──── 亮度 mod（只在按键/调节时开串口）────"
printf '  发出的 AT 命令         %s\n' "$(grep -acE 'ModeMod/Serial.*\[at\+bkl' "$LOG")"
printf '  补发（经同一条流）     %s\n' "$(grep -ac '经同一条流补发' "$LOG")"
echo "  最近活动："
grep -aE 'ModeMod/(Bright|Serial)' "$LOG" | grep -avE '启动|尺寸|overlay|位置|无障碍' | tail -5 | sed 's/^/    /'
echo

echo "──── ★ 判据行：**有害争抢**（真失败）────"
# ★★ 判据**刻意不含「让路」** —— 让路正是 AS3b 想要的行为。
#    真正要归零的是"读失败 / openDevice 失败"这一类**未被处理的**冲突。
# ★ 也刻意**不含「拒绝自嵌套」** —— 那是个**独立的回归信号**（上面单独计数），
#   把它混进"争抢"会让人以为是外部冲突，而它其实是**自己和自己**。
HITS="$(grep -aE 'ModeMod/(Serial|Tntgo)' "$LOG" \
        | grep -aE '读失败|openDevice 失败|次失败|Rejected' || true)"
if [ -z "$HITS" ]; then
    echo "  0  ── 未观测到有害争抢"
else
    echo "$HITS" | tail -20 | sed 's/^/    /'
fi
echo
echo "⚠️ 判读注意："
echo "  · 若【亮度 mod 发出的 AT 命令 = 0】，说明它根本没被使用过，"
echo "    此窗口**不能**用来判定争抢 —— 要让用户真的按几次亮度键再测。"
echo "  · 断档事件要逐条对照它当时的**起止时刻**，确认是不是部署/force-stop 造成的；"
echo "    ★ 本项目已犯过一次：把【监看脚本的读失败】当成了【mod 的读失败】。"
echo "════════════════════════════════════"
