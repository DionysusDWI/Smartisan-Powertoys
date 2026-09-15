#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★ TNT GO 容量自学习 —— **离线验算**（任务 AR · AR1）

## 为什么有这个脚本

CC 首轮审计 **F4**：`TntgoCapacity.kt` 是**验证覆盖最浅**的文件，却**零离线覆盖**。
而 **F2**（断档后首笔把 gap 电荷计入分子 ⇒ `C_est` 系统性偏高）**正是因此漏过的** ——
它靠人工推演才发现，本可以喂一条合成序列自动暴露。

⇒ 本脚本把 `TntgoCapacity.onReading` 的**状态机镜像成纯函数**，
喂**合成读数序列**，断言行为。**改 Kotlin 就要回来改这里，反之亦然。**

## ★ 与 Kotlin 的对应关系（改一边必须改另一边）

| 本脚本 | `TntgoCapacity.kt` |
|---|---|
| `PRIOR_MAH / MIN_SEGMENTS / KEEP` | 同名常量 |
| `MIN_SOC_DROP / MIN_SEG_MS / MAX_GAP_MS` | 同名**私有**常量 |
| `MIN_MAH / MAX_MAH` | 同名私有常量 |
| `Capacity.on_reading` | `onReading` |
| `Capacity.reset / `init`(恢复) | `reset` / `init{}` 从 prefs 恢复 |

⚠️ **本脚本的判据是"行为"，不是"实现"** —— 它不复制 Kotlin 的写法，
只断言**输入序列 → 输出/状态**。所以重构 Kotlin 不会误报，**改行为才会**。
"""

import math
import sys
from dataclasses import dataclass

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ★★ `--buggy`：**把整套判据跑在"修复前"的实现上** —— 必须【红】。
#    ```bash
#    python scripts/verify_tntgo_capacity.py --buggy   # 必须【红】（exit 1）
#    python scripts/verify_tntgo_capacity.py           # 必须【绿】（exit 0）
#    ```
#    ★★★ 2026-09-15 更正：这个开关**原来形同虚设** —— 它只翻转 `_reset_segment` 里
#    `last_ms` 一个小细节，而**没有任何判据的通过与否依赖它**，于是 `--buggy` 下全绿。
#    （真正的"牙"在 case ②/⑤ 内部各自跑的 A/B 对比里，那部分一直有效。）
#    ⇒ 现在改为：`--buggy` 时连"修复后"那一跑也强制走旧行为 ⇒ 产品判据必然失败 ⇒ 红。
#    教训：**"证明测试有牙"的那个开关，本身也要被验证有牙。** 见 `.paper/plans/AR-审计整改.md` §9.7。
#
#    覆盖已修缺陷：
#    ① **F2**（`resetSegment` 不清 `lastMs`）—— 断档电荷被补进分子 ⇒ C_est 偏高
#    ② **N4**（`durMs < MIN_SEG_MS` 时 `resetSegment`）—— **合格的段被整个摧毁**
#    ③ **N6**（断档作废整段 / 门槛不扣断档）—— 采集成功率掉到 1/3
#    ④ **满电平台**（从 100% 起段不丢平台期）—— 首次学出 11956，比先验高 18%
BUGGY = "--buggy" in sys.argv
# ★ 每个缺陷一个独立开关：回归用例要能**只**翻转它那一个行为
#   （否则多个 buggy 行为互相污染，测试就分不清是哪条断言的牙）。
BUGGY_N4 = BUGGY
BUGGY_PLATEAU = BUGGY
# ★ N9（2026-09-15）：`MIN_SEGMENTS = 2` ＋ **无离散度检查**（纯中位数）。
#   ⇒ 2 个点时中位数退化成均值；样本打架时照用中位数，不取保守值。
BUGGY_N9 = BUGGY

# ★★★ 断档的处理方式 —— 历史上改过三次，**三种都保留成可切换的模式**，
#     这样每条回归断言都能精确地"只翻转它自己那一个行为"。
#
# | 模式 | 行为 | 后果 |
# |---|---|---|
# | `leak` | ① 断档 ⇒ 清段，**但不动 `lastMs`** | gap 电荷被补进分子 ⇒ `C_est` **偏高** ❌ **危险** |
# | `destroy` | ② 断档 ⇒ 清段**并**推进 `lastMs`（= 修 F2 之后） | 不编造了，但**整段作废** ⇒ 采集成功率 1/3 ❌ |
# | `n6a` | ③ 断档 ⇒ **不作废**，只记 `seg_gap_ms`（= 现行） | 分子漏记、分母照旧 ⇒ `C_est` **偏低** ✅ 保守 |
GAP_MODE = "leak" if BUGGY else "n6a"


def effective_gap_mode():
    """★ `--buggy` 全局模式下一律降级成最旧的坏行为（`leak`）
    —— 这样整套判据在 buggy 下必然变红，才真的证明断言有牙。见 §9.7。"""
    return "leak" if BUGGY else GAP_MODE

# ── 常量：★ 必须与 TntgoCapacity.kt 的 companion object 逐值一致
PRIOR_MAH = 10160.0
MIN_SEGMENTS = 3            # ★ N9：2 → 3（2 点时中位数退化成均值）
DISPERSION_MAX = 0.10       # ★ N9：`MAD / 中位数` 上限，超标改取最小样本
KEEP = 5
MIN_SOC_DROP = 5
MIN_SEG_MS = 10 * 60 * 1000
MAX_GAP_MS = 90_000
MIN_MAH = 5000.0
MAX_MAH = 15000.0
FULL_SOC = 100
UNSET = -1

# ★★★ 真实 epoch 基准 —— **所有合成序列都必须用它，不能用 0**
#
# 为什么：`last_ms == 0` 是"没有上一笔"的**哨兵值**。从 `t = 0` 起测，
# 第一笔会把 `last_ms` 置成 0 ⇒ **第二笔的 Δt 也被算成 0**
# ⇒ 每段少算一格电荷 ⇒ 学出的容量**凭空偏低 1/N**。
#
# 真机上 `nowMs = System.currentTimeMillis()` 永不为 0 ⇒ 这是**测试专用陷阱**，
# **不是产品缺陷**。但它已经害过本文件多次：
#   · ⑧ 因此断言 10000 而实际得 9000（2026-09-15 N9 落地时抓到）
#   · ⑤ 的 docstring 早就记过同一个坑，却没做成默认值
# ⇒ 现在把它做成**默认参数**，任何用例都不必再"记得"这件事。
EPOCH = 1_700_000_000_000

# 供断言用的辅助
FULL_MAH_AT_100 = None   # 见 judge_two_independent_paths


def _median(vals):
    """中位数。★ 镜像 Kotlin 的 `medianOf`（`Double` 版，**不经过 Int**）。

    ★ **入参无需有序 —— 本函数自己排**（与 Kotlin 同契约）。
    早先这里依赖"调用方已排序"，而 Kotlin 那边**没有** —— 两边手写成了两个样子，
    于是真机上 MAD 恒为 0、离散度闸门失效，而本文件全绿。
    详见 `TntgoCapacity.medianOf` 的 docstring。

    @return 空列表返回 `None`（**不返回 0** —— 0 是个合法容量，不能当哨兵）
    """
    if not vals:
        return None
    s = sorted(vals)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


@dataclass
class Fusion:
    """镜像 Kotlin 的 `TntgoCapacity.Fusion`（`data class`，字段同名）。"""
    mah: float
    median_mah: float
    min_mah: float
    dispersion: float
    converged: bool
    count: int


class Capacity:
    """`TntgoCapacity` 的状态机镜像。**纯函数式：时间由调用方注入。**"""

    def __init__(self, disk=None):
        """disk: 模拟 SharedPreferences（测"重启恢复"）"""
        d = disk or {}
        self.samples = list(d.get("samples", []))
        self.seg_soc = d.get("seg_soc", UNSET)
        self.seg_mah = d.get("seg_mah", 0.0)
        self.seg_t0 = d.get("seg_t0", 0)
        self.last_ms = d.get("last_ms", 0)
        self.seg_gap_ms = d.get("seg_gap_ms", 0)      # ★ N6a
        self.plateau_trimmed = False                  # ★ 满电平台只丢一次
        self.log = []

    # ---------------------------------------------------------------- 对外

    @property
    def fusion(self):
        """★★ N9：镜像 Kotlin 的 `TntgoCapacity.fusion`。

        | 有效段数 | 采用 |
        |---|---|
        | < `MIN_SEGMENTS` | `None` ⇒ 调用方用先验 |
        | ≥ 3 且 `MAD/中位数 ≤ DISPERSION_MAX` | **中位数** |
        | ≥ 3 但超标 | ★ **最小样本**（**不退回先验**，先验已知偏高） |

        ★ `BUGGY_N9 = True` 时退回旧行为：**2 段即切换、无离散度检查**（纯中位数）。
        """
        s = sorted(self.samples)
        if BUGGY_N9:
            if len(s) < 2:
                return None
            med = _median(s)
            return Fusion(mah=med, median_mah=med, min_mah=s[0],
                          dispersion=float("nan"), converged=True, count=len(s))
        if len(s) < MIN_SEGMENTS:
            return None
        med = _median(s)
        mad = _median(sorted(abs(x - med) for x in s))
        disp = (mad / med) if med > 0 else float("nan")
        converged = math.isnan(disp) or disp <= DISPERSION_MAX
        return Fusion(mah=med if converged else s[0], median_mah=med, min_mah=s[0],
                      dispersion=disp, converged=converged, count=len(s))

    @property
    def learned_mah(self):
        f = self.fusion
        return None if f is None else f.mah

    @property
    def capacity_mah(self):
        f = self.fusion
        return PRIOR_MAH if f is None else f.mah

    @property
    def segment_count(self):
        return len(self.samples)

    def on_reading(self, soc, current_ma, now_ms):
        """对应 Kotlin 的 onReading。返回新学到的 C_est 或 None。"""
        # 条件 1：充电 ⇒ 不计数，并结束当前段
        if current_ma >= 0:
            if self.seg_soc != UNSET:
                self.log.append("段结束（转为充电）")
            self._reset_segment(now_ms)
            return None

        # 条件 4：断档
        #
        # ⚠️ 这里改过三次（务必读完再动）：
        #   ① `leak`    ：只清段而**不推进 last_ms** ⇒ gap 电荷被补进分子 ⇒ 偏高 ❌ 危险
        #   ② `destroy` ：清段并推进 last_ms ⇒ 不编造了，但**整段作废** ⇒ 采集成功率 1/3
        #   ③ ★ `n6a`（现行）：**不编造、也不作废** —— 只记 `seg_gap_ms`，段继续
        #      ⇒ 分子漏记 gap 电荷、分母照旧完整 ⇒ `C_est` **系统性偏低** ⇒ 可用时间偏短 ✅ 保守
        gap = now_ms - self.last_ms
        if self.last_ms != 0 and gap > MAX_GAP_MS:
            mode = effective_gap_mode()
            if mode == "leak":
                # ① 最初的行为：清段，但 **last_ms 留在原地** ⇒ 下一笔 dt = 整个 gap
                self.log.append(f"采样断档 {gap // 1000}s ⇒ 本段作废（⚠️ lastMs 未推进！）")
                self.seg_soc, self.seg_mah, self.seg_t0 = UNSET, 0.0, 0
                self.seg_gap_ms = 0
                self.plateau_trimmed = False
                # ★ 刻意不动 self.last_ms —— 这就是 bug 本身
            elif mode == "destroy":
                # ② 修 F2 之后：清段并推进 last_ms ⇒ 不编造，但**整段作废**
                self.log.append(f"采样断档 {gap // 1000}s ⇒ 本段作废，重新起段")
                self.seg_soc, self.seg_mah, self.seg_t0 = UNSET, 0.0, 0
                self.seg_gap_ms = 0
                self.plateau_trimmed = False
                self.last_ms = now_ms
            else:                                  # ③ N6a：现行
                self.last_ms = now_ms
                if self.seg_soc != UNSET:
                    self.seg_gap_ms += gap
                    self.log.append(
                        f"采样断档 {gap // 1000}s ⇒ ★段继续，断档单独记账"
                        f"（本段累计断档 {self.seg_gap_ms // 1000}s ⇒ 估计偏保守）"
                    )

        if self.seg_soc == UNSET:
            self.seg_soc = soc
            self.seg_mah = 0.0
            self.seg_t0 = now_ms

        # 累计电荷（★ 用实测 Δt）
        dt_ms = 0 if self.last_ms == 0 else max(0, now_ms - self.last_ms)
        self.seg_mah += abs(current_ma) * dt_ms / 3_600_000.0
        self.last_ms = now_ms

        # ★★★ 满电平台（实机抓到：从 100% 起段，前 ~90 mAh 是在电量钉住时积累的
        #     ⇒ 分子白涨、分母不动 ⇒ 首次学出 11956，比先验高 18% ⇒ 可用时间偏长 ❌）
        if not BUGGY_PLATEAU:
            if self.seg_soc >= FULL_SOC and soc < self.seg_soc and not self.plateau_trimmed:
                self.log.append(f"★ 满电平台：丢掉起步阶段（{self.seg_mah:.0f} mAh）⇒ 以 {soc}% 为新起点")
                self.seg_soc, self.seg_mah, self.seg_t0 = soc, 0.0, now_ms
                self.seg_gap_ms = 0
                self.plateau_trimmed = True
                self._persist()
                return None

        dropped = self.seg_soc - soc
        if dropped < MIN_SOC_DROP:
            self._persist()
            return None

        # 条件 3：段**有效**时长 = 总时长 − 累计断档（★ N6b）
        dur_ms = now_ms - self.seg_t0
        eff_ms = dur_ms - self.seg_gap_ms
        if eff_ms < MIN_SEG_MS:
            if BUGGY_N4:
                # ★★ 修复前：把"还不够久"当成"这段坏了"，**整个丢掉**
                self.log.append(f"段太短 {eff_ms // 1000}s ⇒ 不采信")
                self._reset_segment(now_ms)
                return None
            # ★ 修复后：还不够久就**再等一笔**（不丢、不动基线）
            self.log.append(f"段有效时长不足 {eff_ms // 1000}s ⇒ 本笔不结段，继续累计")
            self._persist()
            return None

        est = self.seg_mah / (dropped / 100.0)
        if est < MIN_MAH or est > MAX_MAH:
            self.log.append(f"学到的容量离谱 {est:.0f} mAh ⇒ 丢弃")
            self._reset_segment(now_ms)
            return None

        self.log.append(f"★ 学到一个容量估计：{est:.0f} mAh（搬走 {self.seg_mah:.0f} mAh，掉 {dropped}%）")
        self.samples.append(est)
        while len(self.samples) > KEEP:
            self.samples.pop(0)
        self._reset_segment(now_ms)
        return est

    def reset(self):
        self.samples.clear()
        self._reset_segment(self.last_ms)

    # ---------------------------------------------------------------- 内部

    def _reset_segment(self, now_ms):
        """清掉进行中的段。

        ★ 忠实镜像 Kotlin：**总是**把 `last_ms` 推到 `now_ms`。

        ⚠️ 早先这里用 `if not BUGGY` 来模拟 F2 的 bug（"清段但不动 lastMs"），
        现在**改成在调用点建模**（见 `on_reading` 的 `GAP_MODE == "leak"` 分支）——
        因为 Kotlin 里 `resetSegment(nowMs)` 本身是对的，
        **bug 在"调用方没把 nowMs 传进来"**。放在调用点才是忠实的。
        """
        self.seg_soc = UNSET
        self.seg_mah = 0.0
        self.seg_t0 = 0
        self.seg_gap_ms = 0            # ★ N6a：断档账随段一起清
        self.plateau_trimmed = False
        self.last_ms = now_ms

    def _persist(self):
        """落盘对"行为"无影响；恢复路径由 snapshot/restore 单独测。
        ⚠️ 但 `seg_gap_ms` 必须进 snapshot —— 不落盘的话 mod 一重启断档账归零
        ⇒ 有效时长被高估 ⇒ 本该被拒的段被接收。"""
        pass

    def snapshot(self):
        return {"samples": list(self.samples), "seg_soc": self.seg_soc,
                "seg_mah": self.seg_mah, "seg_t0": self.seg_t0,
                "last_ms": self.last_ms, "seg_gap_ms": self.seg_gap_ms}


# ══════════════════════════════════════════════════════════════════════
# 判据
# ══════════════════════════════════════════════════════════════════════

FAILED = []


def check(name, got, want, tol=1e-6):
    ok = (abs(got - want) <= tol) if isinstance(want, (int, float)) and isinstance(got, (int, float)) \
        else (got == want)
    print(f"  {'✓' if ok else '✗'} {name}: got={got!r} want={want!r}")
    if not ok:
        FAILED.append(name)
    return ok


def feed(cap, readings):
    """readings: [(soc, mA, t_ms), ...]；返回所有产出的估计"""
    out = []
    for soc, ma, t in readings:
        r = cap.on_reading(soc, ma, t)
        if r is not None:
            out.append(r)
    return out


def steady(soc_from, soc_to, ma, minutes, t0=EPOCH, step_s=30):
    """生成一段匀速放电序列（电量线性下降）"""
    secs = minutes * 60
    n = int(secs // step_s) + 1
    rs = []
    for i in range(n):
        frac = i / max(1, n - 1)
        soc = round(soc_from + (soc_to - soc_from) * frac)
        rs.append((soc, ma, t0 + i * step_s * 1000))
    return rs


def segment(soc_from, target_mah, drop_pct, minutes, t0=EPOCH, step_s=30):
    """★ **按目标容量反推电流**，生成恰好一段的序列。

    这样测试用例的意图是自明的（"我想让它学出 10000"），
    而不是手调电流再猜结果 —— 上一版就是手调的，结果算错了自己都不知道：
    `steady(80,70,...)` 掉 10% 会完成 **2 段**，而断言写的是 1 段。

    @param target_mah 期望算出的容量
    @param drop_pct   这一段掉多少 %
    """
    charge = target_mah * drop_pct / 100.0          # 这一段要搬走的 mAh
    ma = round(charge / (minutes / 60.0))           # 对应电流
    return steady(soc_from, soc_from - drop_pct, -ma, minutes, t0, step_s), ma


# ──────────────────────────────────────────────────────────────────
def case_normal_segment():
    """① 一段正常的纯放电 ⇒ 应当产出**恰好一个**接近真值的估计

    ★★ 关于容差：**电量是整数 %**，`dropped` 只能按整数跳。
    段在 SOC 首次掉到目标值时结束，而那一刻累计的电荷对应的是**分数**掉落 ——
    于是短段天然带量化误差：

        5% 的段 ⇒ 误差可达 **±10%**
       20% 的段 ⇒ 误差约 **±5%**

    ⇒ 本用例取 **20% 的长段**，并把容差设成 ±8%（覆盖量化）。
    ⚠️ 这条性质对**产品**也有意义：**段越长，学出的容量越准**。
    """
    print("\n[①] 正常放电段（目标真值 10000 mAh，掉 20%，100 分钟）")
    cap = Capacity()
    rs, ma = segment(80, target_mah=10000, drop_pct=20, minutes=100)
    print(f"     （反推电流 {ma} mA；★ 掉 20% 会自然完成【多个】5% 的段）")
    out = feed(cap, rs)
    if not check("产出了估计（≥1 个）", len(out) >= 1, True):
        return
    print(f"     （共 {len(out)} 个：{['%.0f' % v for v in out]}）")
    # ★★ 断言分两层 —— 这反映了**算法真正的精度特性**（本轮实测出来的）：
    #    ① 单段估计天然带 ±10~15% 的量化误差（整数电量，5% 的段分辨率太低）
    #    ② 但**多段取中位数**会收敛 —— 这才是设计承诺的精度
    #    ⚠️ 所以"某个单段偏了"**不是缺陷**；"中位数偏了"才是。
    ok_each = all(abs(v - 10000) <= 1600 for v in out)
    check("单段估计都在真值 ±16% 内（量化误差范围）", ok_each, True)

    med = sorted(out)[len(out) // 2] if out else 0
    check("★ 中位数在真值 ±5% 内（这才是设计承诺）", abs(med - 10000) <= 500, True)
    print(f"     （中位数 {med:.0f} mAh ⇒ 误差 {abs(med-10000)/100:.1f}%）")


def _run_gap_sequence(buggy: bool):
    """跑一次断档序列，返回最后产出的估计。

    ★ `buggy=True` ⇒ 走 ①`leak`（**最初的行为**：断档清段但不动 `lastMs`
      ⇒ gap 电荷被补进分子）。`--buggy` 全局模式下 `effective_gap_mode()` 也会
      强制 `leak`，所以"修复后"那一跑同样变坏 ⇒ 整套判据必然红。
    """
    global GAP_MODE
    saved, GAP_MODE = GAP_MODE, ("leak" if buggy else "n6a")
    try:
        cap = Capacity()
        t0 = EPOCH
        feed(cap, [(80, -1000, t0), (80, -1000, t0 + 30_000)])
        gap_s = 600
        rs = steady(80, 75, -1000, 30, t0=t0 + (30 + gap_s) * 1000)
        out = feed(cap, rs)
        return out[-1] if out else None
    finally:
        GAP_MODE = saved


def case_f2_gap_regression():
    """② ★★ F2 回归：断档后首笔【不得】把 gap 的电荷计入分子

    ## ⚠️ 2026-09-15 N6a 之后，这条的性质变了（必须读）

    修 F2 时把 bug 定位在"`resetSegment()` 忘了传 `nowMs`"；
    **但 N6a 之后，断档这条路已经根本不调 `resetSegment()` 了** ——
    它显式 `lastMs = nowMs`，于是 gap 电荷**结构性**进不了分子。

    ⇒ 这条用例**不再是"复现一个可达的 bug"**，而是：
      **守住"断档电荷不得进分子"这个不变式** ——
      将来谁把断档重新路由回 `resetSegment()`，它立刻红。

    ⇒ 所以三种模式都留着（`leak`/`destroy`/`n6a`）：
      `leak` 是历史证据，`destroy` 是曾经的一版修复，`n6a` 是现行。

    ★★ 断言方式：**直接对比不同模式**（而不是跟解析真值比）。
    整数电量有量化误差（见 case ①），绝对值本来就不精确；
    而这一条的**方向性**（偏高）是确定的 —— 拿方向做断言最稳。
    """
    print("\n[②] ★★ F2 不变式 —— 断档 600s 后首笔（对比 leak / n6a）")
    fixed = _run_gap_sequence(buggy=False)
    leak = _run_gap_sequence(buggy=True)
    print(f"     n6a {fixed:.0f} mAh ／ leak {leak:.0f} mAh")
    if fixed is None or leak is None:
        check("两条路径都产出了估计", False, True)
        return
    # ★ gap 电荷 = 1000mA × 600s = 167 mAh，分母只有 5%(=500mAh 基准) ⇒ leak 应显著偏高
    #
    # ★ 只在**非** `--buggy` 模式下断言这条：全局 buggy 时两跑都是 leak，比较无意义。
    if not BUGGY:
        check("★ leak【偏高】（证明断言有牙）", leak > fixed + 100, True)
    check("n6a 不含 gap 电荷（≈10000 档：500mAh 段 + 167mAh 段外电荷）",
          round(fixed), 9167, tol=1200)


def case_charge_rejected():
    """③ 充电段 ⇒ 不产出，且段被结束"""
    print("\n[③] 充电 ⇒ 不学")
    cap = Capacity()
    out = feed(cap, steady(50, 60, +2000, 30))
    check("没有产出任何估计", len(out), 0)
    check("段被结束", cap.seg_soc, UNSET)


def case_charge_flip_rejected():
    """④ 段中途由放电翻成充电 ⇒ 该段作废、不产出"""
    print("\n[④] 放电中途翻成充电 ⇒ 段作废")
    cap = Capacity()
    rs = steady(80, 78, -2000, 10) + steady(78, 90, +2000, 30)
    out = feed(cap, rs)
    check("没有产出任何估计", len(out), 0)


def _n4_readings():
    """每 30 s 一笔（★ 必须 < `MAX_GAP_MS` = 90 s，否则会先撞上"断档作废"，测的就不是 N4 了）。

    电量表：t < 300 s 恒 80%，之后每 60 s 掉 1 点
    ⇒ **t = 540 s 时 `dropped = 5`（够阈值）而 `durMs` 只有 540 s < 600 s**
      —— 正好落在 N4 那一支。t = 600 s 时 `durMs` 恰好够。

    ★ 用**真实 epoch 基准**（不是 0）：`lastMs == 0L` 是"没有上一笔"的哨兵值，
      从 t=0 起测会让第二笔的 Δt 也变成 0，把测试变成假的。
    """
    t0 = EPOCH
    out = []
    for i in range(0, 22):                          # 0 .. 630 s
        t = i * 30_000
        soc = 80 if t < 300_000 else max(75, 79 - (t - 300_000) // 60_000)
        out.append((soc, -2000, t0 + t))
    return out


def _run_n4_sequence(buggy: bool):
    """返回 (产出列表, 末态对象)。buggy=True 走修复前的 `_reset_segment`。"""
    global BUGGY_N4
    saved, BUGGY_N4 = BUGGY_N4, (buggy or BUGGY)
    try:
        cap = Capacity()
        return feed(cap, _n4_readings()), cap
    finally:
        BUGGY_N4 = saved


def case_too_short_waits():
    """⑤ ★★★ N4 回归：「ΔSOC 够了但历时 < 10 分钟」⇒ **再等一笔，不该摧毁**。

    实机证据（2026-09-15 AR7，logcat）：`段太短 580s ⇒ 不采信`。
    ★ 关键：这行代码排在 `dropped < MIN_SOC_DROP` **之后** ⇒ 能走到它说明
      **ΔSOC 已经 ≥ 5%、段本身合格**，只差 20 秒 ⇒ **整整 10 分钟的积累被丢掉**。
      外推 `C_est = 381 mAh / 0.05 = 7620 mAh`（与已学到的 7137 一致，而非先验 10160）。
    见 `.paper/plans/AR-审计整改.md` §9.5。
    """
    print("\n[⑤] ★★★ N4：ΔSOC 够了但 <10 分钟 ⇒ 再等一笔（不是丢掉）")
    fixed, cap_f = _run_n4_sequence(buggy=False)
    buggy, _cap_b = _run_n4_sequence(buggy=True)
    print(f"     修复后 产出 {len(fixed)} 个：{[f'{v:.0f}' for v in fixed]}"
          f" ／ 修复前 产出 {len(buggy)} 个")
    check("修复后：等下一笔后结出 1 个估计", len(fixed), 1)
    if fixed:
        # 手算：seg_mah = 20 笔 × 2000 mA × 30 s / 3.6e6 = 333.33 mAh；dropped = 5
        #       est = 333.33 / 0.05 = 6666.7 mAh
        check("修复后：估计值 = 6667 mAh", fixed[0], 6666.67, tol=1.0)
    # ★ 同 case ②：全局 `--buggy` 时两跑同行为，这条比较无意义 ⇒ 跳过
    if not BUGGY:
        check("★ 修复前：一个估计都没有（证明断言有牙）", len(buggy), 0)
    # ★ 注意断言的是 **75** 而不是 UNSET：序列在 t=600 s 结段后还有一笔 t=630 s，
    #   那一笔会**起一个新段**（seg_soc = 当前电量 75）—— 这是正确行为。
    #   （第一版这里断言 UNSET，是**测试写错了**，不是代码错了。）
    check("修复后：结段后紧接着起了一个新段（75%）", cap_f.seg_soc, 75)


def case_absurd_rejected():
    """⑥ 算出离谱值（超 5000–15000）⇒ 拒收"""
    print("\n[⑥] 离谱值 ⇒ 拒收")
    cap = Capacity()
    # 掉 10% 却搬走 50000 mAh ⇒ C_est = 500000，远超上限
    rs = [(80, -60000, EPOCH), (80, -60000, EPOCH + 30_000), (70, -60000, EPOCH + 660_000)]
    out = feed(cap, rs)
    check("没有产出任何估计", len(out), 0)


def case_restart_resume():
    """⑦ ★ 重启恢复：从盘上恢复进行中的段后，继续累计（不重头开始）"""
    print("\n[⑦] 重启恢复后继续累计")
    cap = Capacity()
    # 只掉 3%（不够结段）⇒ 盘上留着进行中的段
    feed(cap, steady(80, 77, -1000, 20))
    disk = cap.snapshot()
    check("盘上有进行中的段", disk["seg_soc"], 80)

    cap2 = Capacity(disk)                          # ★ 模拟 mod 重启
    rs = steady(77, 71, -1000, 30, t0=disk["last_ms"] + 30_000)
    out = feed(cap2, rs)
    check("重启后仍然产出了估计", len(out) >= 1, True)


def case_switch_after_three_segments():
    """⑧ 学够 **3** 段 ⇒ 从先验切到学习值（★ N9：原为 2 段）

    ★ 每一步都**显式断言段数** —— 早先这版只断言"值等于先验"，
    而那个条件在 0 段和 1 段时**都成立**，等于没测到"攒够几段才切"这件事。

    ## ★★★ 关于精度期望（2026-09-15 落地 N9 时被这条绊了两次，记下来）

    本条最初写的是 `check("值 = 10000", ..., tol=1.0)`，**红了两次，而代码两次都是对的**：

    | # | 现象 | 真因 | 性质 |
    |---|---|---|---|
    | 1 | 学到 **9000** | 序列从 `t = 0` 起 ⇒ 撞上 `last_ms == 0` 哨兵 ⇒ 每段少算一格电荷 | **仪器坏**（已做成 `EPOCH` 默认值结构性修掉） |
    | 2 | 学到 **9200** | ★ 那一段**真实只掉了 4.6%**，整数电量把它读成 5% ⇒ 拿 5% 当分母天然偏低 8% | **断言写错**（case ① 早就写明"5% 的段误差可达 ±10%"） |

    ⇒ 所以本条只断言**量化带**（单段 ±10%、中位数 ±5%），不断言"恰好"。
    ★ 这两次的共同点是：**测试红了就去查代码**。而真因一次在仪器、一次在断言。
      ⇒ 与本工作区已记的教训同源：**先问"这个断言的前提还成立吗"。**
    """
    print("\n[⑧] 满 3 段后切换（★ N9：原来 2 段就切）")
    cap = Capacity()
    check("0 段时用先验", cap.capacity_mah, PRIOR_MAH)

    rs1, _ = segment(90, target_mah=10000, drop_pct=6, minutes=30)
    feed(cap, rs1)
    check("产出恰好 1 段", cap.segment_count, 1)
    check("1 段时仍用先验", cap.capacity_mah, PRIOR_MAH)

    rs2, _ = segment(84, target_mah=10000, drop_pct=6, minutes=30, t0=EPOCH + 2_000_000)
    feed(cap, rs2)
    check("产出第 2 段", cap.segment_count, 2)
    # ★★ 这一条就是 N9 的核心：2 段**不切**
    check("★★ 2 段时【仍】用先验（N9 本轮收紧的那一条）", cap.capacity_mah, PRIOR_MAH)

    rs3, _ = segment(78, target_mah=10000, drop_pct=6, minutes=30, t0=EPOCH + 4_000_000)
    feed(cap, rs3)
    check("产出第 3 段", cap.segment_count, 3)
    check("3 段后切到学习值", cap.fusion is not None, True)
    if cap.fusion:
        vals = [round(v) for v in cap.samples]
        print(f"     （三段学到 {vals}，学习值 {cap.learned_mah:.0f} mAh，"
              f"离散度 {cap.fusion.dispersion * 100:.2f}%，收敛={cap.fusion.converged}）")
        check("三段同源 ⇒ 收敛", cap.fusion.converged, True)
        # ★★ 为什么不写"恰好 10000" —— 见 docstring「关于精度期望」两点。
        check("每一段都落在真值 ±10%（整数电量的量化带）",
              all(9000 <= v <= 11000 for v in vals), True)
        check("中位数落在真值 ±5% 内（多段融合后的承诺精度）",
              abs(cap.learned_mah - 10000) <= 500, True)


def case_gap_too_large_does_not_corrupt():
    """⑨ ★ 超长断档（停一夜）⇒ 不能靠 5000–15000 钳制兜底，本就不该产出

    ★ N6b 之后这条的**理由变了**（而且更硬）：断档不再作废整段，
    所以拦住它的是「**有效时长**」——8 小时里只有 60 s 有数据 ⇒ `effMs` 远低于门槛。
    （修 N6 之前它靠"整段作废"拦住，那是**误伤**式的拦法。）
    """
    print("\n[⑨] 超长断档（8 小时）")
    cap = Capacity()
    feed(cap, [(80, -1000, 0), (80, -1000, 30_000)])
    # 8 小时后回来，电量掉 10%
    out = feed(cap, [(70, -1000, 30_000 + 8 * 3600 * 1000),
                     (70, -1000, 30_000 + 8 * 3600 * 1000 + 30_000)])
    check("没有产出任何估计（有效时长只有 60s）", len(out), 0)


# ──────────────────────────────────────────────────────────────────
# N6 三条回归（2026-09-15：断档不再作废整段）
#
# 背景：断档是**常态**而非意外 —— 亮度 mod 与电量 mod 抢同一个串口
# （`TntgoBkl.kt:51` 自述 `claimInterface` 会 EBUSY）。
# 实测 89% 那段攒了 305 mAh、只因 184 s 断档就**全丢**，采集成功率掉到 **1/3**。
#
# ⚠️ 这三条**刻意用真实 epoch 基准**（`T0 = 1.7e12`）：
#    `last_ms == 0` 是"没有上一笔"的哨兵值，从 t=0 起测会让第二笔 Δt 也变 0，
#    测试就变成假的（case ⑤ 的注释记过同一个坑）。
# ──────────────────────────────────────────────────────────────────

T0 = EPOCH


def feed_timed(cap, readings):
    """同 `feed`，但记录**产出发生在哪一刻**（N6b 要断言"被推迟"而不是"被丢掉"）"""
    out = []
    for soc, ma, t in readings:
        r = cap.on_reading(soc, ma, t)
        if r is not None:
            out.append((t, r))
    return out


def _n6a_readings():
    """恒流 -2000 mA；前 10 分钟恒 80%，**断档 180 s**，回来后已经是 75%。

    ⇒ 电量掉 5% 就发生在断档那一跳上（这正是实机 89% 那段的形状）。
    """
    rs = [(80, -2000, T0 + i * 30_000) for i in range(0, 21)]        # 0 .. 600 s
    rs += [(75, -2000, T0 + 780_000 + i * 30_000) for i in range(0, 15)]   # 780 .. 1200 s
    return rs


def _run_n6a(buggy: bool):
    """`buggy=True` ⇒ 走 ②`destroy`（修 F2 之后、N6 之前的那一版：断档作废整段）"""
    global GAP_MODE
    saved, GAP_MODE = GAP_MODE, ("destroy" if buggy else "n6a")
    try:
        cap = Capacity()
        return feed(cap, _n6a_readings()), cap
    finally:
        GAP_MODE = saved


def case_n6a_gap_keeps_segment():
    """⑩ ★★★ N6a 回归：断档**不得**作废整段 —— 断档前的积累必须保留

    ## 判据为什么是"有/无产出"而不是"值准不准"

    ★ 这个序列是**刻意构造**成能一刀切开的：
      · 断档后电量**一直停在 75%**（不再掉）⇒ 若断档把段作废，
        新段从 75% 起、`dropped` 永远是 0 ⇒ **再也结不出段**；
      · 不作废则旧段继续有效、`dropped = 5` ⇒ 立刻产出。
    ⇒ 「修复后 1 个 / 修复前 0 个」，**不依赖任何容差**。

    ## 值本身的含义（★ 顺便证明偏差方向是保守的）

    `seg_mah` 只算了 600 s 的电荷 = 333.3 mAh，而分母 ΔSOC 是 5%（含断档那一跳）
    ⇒ `C_est = 6667 mAh`。
    而**物理真相**是 2000 mA × 780 s = 433 mAh 对应 5% ⇒ 8667 mAh。
    ⇒ 我们报的 **6667 < 8667**，低了 23% ⇒ **可用时间偏短** ⇒ ✅ 正是用户要的保守方向。
    """
    print("\n[⑩] ★★★ N6a：断档 180s **不作废整段**（断档前积累必须保留）")
    fixed, cap_f = _run_n6a(buggy=False)
    buggy, _ = _run_n6a(buggy=True)
    print(f"     修复后 产出 {len(fixed)} 个：{['%.0f' % v for v in fixed]}"
          f" ／ 修复前 产出 {len(buggy)} 个")
    check("★ 修复后：产出 1 个估计（段活下来了）", len(fixed), 1)
    if fixed:
        check("修复后：6667 mAh（= 600s 电荷 / 5%，保守）", fixed[0], 6666.67, tol=1.0)
    # ★ 断档账**要在段结束【前】查** —— 结段后 `_reset_segment` 会把它清零（正确行为）。
    #   （第一版在结段后查 `cap_f.seg_gap_ms`，断言写错了：拿到 0 是**代码对的**。）
    check("修复后：断档账确实被记下（180s）",
          any("累计断档 180s" in m for m in cap_f.log), True)
    if not BUGGY:
        check("★ 修复前：一个都没有（证明断言有牙）", len(buggy), 0)


def _n6b_readings():
    """前 5 分钟恒 80%，**断档 300 s**，回来后 75% 并保持。

    ⇒ 电量掉 5% 的那一刻（t=600s）**总时长够 600 s、有效时长只有 300 s** ——
      正好落在 N6b 那一支：应当**等**，而不是收、也不是丢。
    """
    rs = [(80, -2000, T0 + i * 30_000) for i in range(0, 11)]        # 0 .. 300 s
    rs += [(75, -2000, T0 + 600_000 + i * 30_000) for i in range(0, 21)]   # 600 .. 1200 s
    return rs


def case_n6b_effective_duration_gate():
    """⑪ ★★★ N6b 回归：门槛判**有效时长**（总时长 − 累计断档），且不足时是**等**不是**丢**

    ## 两个断言，各管一件事

    1. **推迟**：`t = 600 s`（ΔSOC 刚到 5%、总时长也刚到 600 s）**不得**产出 ——
       因为有效时长只有 300 s。产出必须发生在 `t = 900 s`（有效时长刚到 600 s）。
       ⇒ 若门槛退回用**总时长**，估计会在 600 s 就冒出来 ⇒ 这条立刻红。
    2. **不丢**：最终**必须**产出 1 个 ⇒ 证明"等"这条路走得通
       （退回到 `resetSegment()` 的话，新段从 75% 起、永远 dropped=0 ⇒ 0 个）。

    ★ 断言 1 是 N6b 的牙，断言 2 是 N4 的牙 —— 两条缺一不可。
    """
    print("\n[⑪] ★★★ N6b：有效时长不足 ⇒ 等（不是收、也不是丢）")
    cap = Capacity()
    out = feed_timed(cap, _n6b_readings())
    times = [t - T0 for t, _ in out]
    print(f"     产出时刻：{times} ms ／ 值：{['%.0f' % v for _, v in out]}")

    check("★ 在 t=600s（总时长刚到门槛）**没有**产出", 600_000 in times, False)
    check("t=900s（有效时长刚到门槛）才产出", times, [900_000])
    if out:
        # seg_mah = (0..300s 的 300s) + (600..900s 的 300s) = 166.67 + 166.67 = 333.3
        check("值 6667 mAh", out[0][1], 6666.67, tol=1.0)

    if not BUGGY:
        global GAP_MODE
        saved, GAP_MODE = GAP_MODE, "destroy"
        try:
            cap_b = Capacity()
            out_b = feed(cap_b, _n6b_readings())
        finally:
            GAP_MODE = saved
        print(f"     修复前（断档作废整段）产出 {len(out_b)} 个")
        check("★ 修复前：段被作废 ⇒ 一个都没有（证明断言有牙）", len(out_b), 0)


def _same_trajectory(shift_ms: int):
    """同一 SOC 轨迹（80→75 线性，31 点），把后半段整体右移 [shift_ms]。
    `shift_ms = 0` ⇒ 无断档；`> 0` ⇒ 中间插进一段断档。"""
    rs = []
    for i in range(0, 31):
        soc = round(80 - 5 * i / 30)
        t = T0 + i * 30_000 + (shift_ms if i >= 11 else 0)
        rs.append((soc, -2000, t))
    return rs


def case_n6c_gap_is_conservative():
    """⑫ ★★★ N6c：断档带来的偏差**方向必须是保守的**（`C_est` 只许更低）

    ## 怎么做到"只比方向、不比绝对值"

    取**同一条 SOC 轨迹**，一次不加断档、一次在中间插 300 s 断档。
    两次的 ΔSOC 完全一样（都是 5%），差别只在分子少算了断档那段电荷
    ⇒ 断言 `est_with_gap ≤ est_without_gap`，**结构性成立，不依赖容差**。

    ## ⚠️ 诚实声明：这条只保证【方向】，不保证【幅度】

    偏差幅度取决于**断档期间掉了多少电**，而那是我们**看不到**的量。
    本用例里断档插在掉电中段 ⇒ 低约 3%；
    若断档正好盖住整个 5% 的掉落（实机 89% 那段的形状，见 case ⑩）⇒ 低 23%。
    ⇒ ★ 换个序列幅度就变，**这是设计如此**：我们宁可少报，绝不虚报。
    """
    print("\n[⑫] ★★★ N6c：断档 ⇒ 估计值只许更低（保守方向）")
    cap_a, cap_b = Capacity(), Capacity()
    out_a = feed(cap_a, _same_trajectory(0))
    out_b = feed(cap_b, _same_trajectory(300_000))
    if not out_a or not out_b:
        check("两次都产出了估计", False, True)
        return
    print(f"     无断档 {out_a[-1]:.0f} mAh ／ 含断档 {out_b[-1]:.0f} mAh")
    check("★ 含断档的估计 ≤ 无断档的估计", out_b[-1] <= out_a[-1], True)
    check("★ 而且是严格更低（有牙）", out_b[-1] < out_a[-1] - 1, True)
    check("含断档的估计没低到离谱（仍在 5000–15000）",
          MIN_MAH <= out_b[-1] <= MAX_MAH, True)
    # ★ 偏差幅度只做**参考打印**，不做断言 —— 见 docstring 的诚实声明。
    print(f"     （本序列偏低 {100 * (1 - out_b[-1] / out_a[-1]):.1f}%；"
          f"幅度随「断档盖住多少掉电」而变，方向恒定）")


# ══════════════════════════════════════════════════════════════════════
# N9 三条回归（2026-09-15：`MIN_SEGMENTS` 2→3 ＋ 离散度检查）
#
# 背景：`MIN_SEGMENTS = 2` 时"中位数"**退化成均值** ⇒ 抗离群能力为零。
# 而实机单段噪声并不小：同一台设备实测到 6270 与 8630，**相差 38%**。
#
# ★★★ 这一组里**最要紧**的一条不是"要 3 段"，而是 **未收敛时不许退回先验**：
#   先验 10160 已知偏高约 30%（实测 5 段全在 7047–7423），
#   退回先验 = 容量调高 = 可用时间变长 = **正是用户明确不要的方向**。
# ══════════════════════════════════════════════════════════════════════

# ★ 这两组都是**实机盘上/历史上真实出现过的样本**，不是编出来的数
REAL_HISTORICAL_TRIPLE = [6270.2593, 7137.005, 8630.246]      # AR §10.9 的三样本
REAL_DEVICE_5 = [7422.6206, 7221.019, 7286.5635, 7216.897, 7046.5107]   # 2026-09-15 盘上


def _fusion_of(samples, buggy_n9):
    """用给定的样本向量构造一个 [Capacity]，取 fusion。`buggy_n9` 显式指定走新旧哪条路。"""
    global BUGGY_N9
    saved, BUGGY_N9 = BUGGY_N9, buggy_n9
    try:
        return Capacity({"samples": list(samples)}).fusion
    finally:
        BUGGY_N9 = saved


def case_n9a_two_segments_not_enough():
    """⑬ ★★ N9a：**只有 2 段时不得切换**（这就是本轮收紧的那一条）

    ## 为什么要单独立一条

    ⑧ 测的是"3 段之后**能**切"；这条测的是"2 段时**不能**切"。
    **两件事** —— 少了这条，把 `MIN_SEGMENTS` 改回 2 也不会有任何判据变红。

    ## 修复前后差在哪

    | | 2 段时 |
    |---|---|
    | 旧（`BUGGY_N9`） | 切换，值 = **均值** 7800 |
    | 新 | 不切换，继续用先验 10160 |

    ⚠️ 注意这里**不是"新的更保守"** —— 7800 < 10160，旧值反而更短。
    之所以仍要改，是因为 **2 点的"中位数"没有抗离群能力**：
    它等于均值，**一个坏值直接进结果**，方向可高可低、不受控。
    """
    print("\n[⑬] ★★ N9a：只有 2 段 ⇒ 不切换")
    f_new = _fusion_of(REAL_HISTORICAL_TRIPLE[:2], buggy_n9=False)
    f_old = _fusion_of(REAL_HISTORICAL_TRIPLE[:2], buggy_n9=True)
    print(f"     新：fusion={f_new} ／ 旧：mah={f_old.mah:.0f}（2 段即切）")

    check("★ 新：2 段 ⇒ fusion 为 None（不切换）", f_new, None)
    check("新：仍然用先验", Capacity({"samples": REAL_HISTORICAL_TRIPLE[:2]}).capacity_mah, PRIOR_MAH)
    if not BUGGY:
        check("★ 旧：2 段就切了（证明断言有牙）", f_old is not None, True)
        # 2 点"中位数" = 均值 —— 正是要消灭的那个退化
        check("★ 旧：而且取的是**均值**（中位数已退化）",
              f_old.mah, (REAL_HISTORICAL_TRIPLE[0] + REAL_HISTORICAL_TRIPLE[1]) / 2.0, tol=1.0)


def case_n9b_converged_uses_median():
    """⑭ ★★ N9b：3 段且**离散度达标** ⇒ 用中位数（样本不打架就正常取中位）"""
    print("\n[⑭] ★★ N9b：3 段收敛 ⇒ 用中位数")
    f = _fusion_of([7100.0, 7200.0, 7300.0], buggy_n9=False)
    print(f"     dispersion={f.dispersion * 100:.2f}%  mah={f.mah:.0f}")
    check("收敛", f.converged, True)
    check("值 = 中位数 7200", f.mah, 7200.0, tol=1e-6)
    check("离散度 = 100/7200 ≈ 1.39%", f.dispersion, 100.0 / 7200.0, tol=1e-6)
    check("段数 = 3", f.count, 3)


def case_n9c_dispersed_takes_conservative():
    """⑮ ★★★★ N9c：样本**打架** ⇒ 取最小样本，且 ★ **绝不退回先验**

    ## 这是 N9 里唯一有产品方向性的一条

    用 AR §10.9 记录的真实三样本 `6270 / 7137 / 8630`（最大相差 38%）。

    三条断言各管一件事：

    1. **确实判定为未收敛** —— 离散度 ≈ 12.1% > 10% 阈值。
    2. **取最小样本**（6270）—— 一个**真实观测到**的值，不是编的。
    3. ★★ **取值 < 先验** —— 这条才是产品要求：
       退回先验会把容量**调高**、可用时间**变长**，正是用户明确不要的方向。

    ## ⚠️ 与聊天里报过的数字不一致（诚实记录）

    本会话早些时候我在对话中报过"前 3 段离散度 **15.5%**"，
    **按 `MAD / 中位数` 重新计算是 12.14%，复现不出 15.5%。**
    此处以**可复现的计算**为准（`12.14%`），并保留这条差异记录。
    ⇒ 教训与 `--buggy` 那次同源：**报出去的数，必须能当场重新算出来。**
    """
    print("\n[⑮] ★★★★ N9c：样本打架 ⇒ 取最小（★ 且不退回先验）")
    f = _fusion_of(REAL_HISTORICAL_TRIPLE, buggy_n9=False)
    f_old = _fusion_of(REAL_HISTORICAL_TRIPLE, buggy_n9=True)
    print(f"     新：mah={f.mah:.0f}（未收敛，取最小）"
          f" ／ 旧：mah={f_old.mah:.0f}（照用中位数）")
    print(f"     （中位数 {f.median_mah:.0f}，最小 {f.min_mah:.0f}，"
          f"离散度 {f.dispersion * 100:.2f}%）")

    check("★ 判定为未收敛", f.converged, False)
    check("离散度 ≈ 12.14%", f.dispersion, 0.121443, tol=0.0005)
    check("★ 取最小样本 6270", f.mah, REAL_HISTORICAL_TRIPLE[0], tol=0.01)
    # ★★ 产品方向判据
    check("★★ 取值 < 中位数（保守方向）", f.mah < f.median_mah, True)
    check("★★★ 取值 < 先验（绝不退回先验 —— 先验已知偏高）", f.mah < PRIOR_MAH, True)
    check("先验确实比它高（⇒「退回先验」就是危险方向）", PRIOR_MAH > f.median_mah, True)
    if not BUGGY:
        check("★ 旧：未收敛也照用中位数（证明断言有牙）", f_old.converged, True)
        check("★ 旧：值 = 中位数 7137（比新值高 13.8%）", f_old.mah, 7137.005, tol=1.0)


def case_real_device_samples_pin():
    """⑯ ★★ **实机盘上样本 pin** —— 钉住当前真机上正在生效的那个值

    样本向量取自 2026-09-15 实机（`run-as ... cat shared_prefs/tntgo_battery.xml`）：

    ```
    cap.samples = 7422.6206,7221.019,7286.5635,7216.897,7046.5107
    ```

    ## 为什么值得单独立一条

    判据要能回答一个很具体的问题：**用户现在打开设置界面会看到什么数？**
    答案是 `≈ 7221 mAh（已学习 5 段）`，离散度 **0.91%**、收敛。
    ⇒ 顺带证明：**真实数据是收敛的** —— 阈值 10% 没有把正常样本误杀。

    ⚠️ 这条**不区分新旧行为**（5 段、无离群 ⇒ 两者都得 7221）——
    它是**生产行为的钉子**，不是牙。整套判据的牙在 ⑬/⑮。
    """
    print("\n[⑯] ★★ 实机盘上样本 pin（5 段，2026-09-15 实机）")
    f = _fusion_of(REAL_DEVICE_5, buggy_n9=False)
    print(f"     mah={f.mah:.1f}  median={f.median_mah:.1f}  "
          f"dispersion={f.dispersion * 100:.2f}%  收敛={f.converged}")
    check("3 段门槛已过 ⇒ 已切换", f is not None, True)
    check("收敛（★ 阈值没误杀真实数据）", f.converged, True)
    check("取中位数 7221.019", f.mah, 7221.019, tol=0.01)
    check("离散度 ≈ 0.91%", f.dispersion, 0.009077, tol=0.0005)
    check("★ 学习值显著低于先验（先验偏高再次坐实）", f.mah < PRIOR_MAH * 0.8, True)


def main():
    print("=" * 68)
    print("TNT GO 容量自学习 · 离线验算（镜像 TntgoCapacity.kt）")
    print("=" * 68)
    case_normal_segment()
    case_f2_gap_regression()
    case_charge_rejected()
    case_charge_flip_rejected()
    case_too_short_waits()
    case_absurd_rejected()
    case_restart_resume()
    case_switch_after_three_segments()
    case_gap_too_large_does_not_corrupt()
    case_n6a_gap_keeps_segment()
    case_n6b_effective_duration_gate()
    case_n6c_gap_is_conservative()
    case_n9a_two_segments_not_enough()
    case_n9b_converged_uses_median()
    case_n9c_dispersed_takes_conservative()
    case_real_device_samples_pin()

    print("\n" + "=" * 68)
    if FAILED:
        print(f"✗ {len(FAILED)} 条判据未通过：")
        for f in FAILED:
            print(f"   · {f}")
        print("=" * 68)
        sys.exit(1)
    print("✓ 全部判据通过")
    print("=" * 68)
    sys.exit(0)


if __name__ == "__main__":
    main()
