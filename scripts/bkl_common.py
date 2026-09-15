#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★★ **AR12 台账的共用读取层**（亮度-功耗样本）。

## 为什么单独一个文件

台账格式在 **AR12b 改过一次**，而盘上有**两种格式混存**的样本。
如果每个判读脚本各自写一遍解析，就会出现"**两边各写一遍**"的经典陷阱 ——
一个脚本读得对、另一个读错了，而**两个都报绿**。

⇒ ★ 解析只写一次（本文件），判读脚本一律从这里取数。

## 台账格式（`bkl.samples`，逗号分隔，旧→新）

| 格式 | 含义 | 字段 |
|---|---|---|
| `ui:mcu:mA:chg` | ★ **AR12b 之后**（MCU 是**实测直读量**） | 4 |
| `ui:mA:chg` | AR12c 时期的旧格式 | 3 |

- `chg = 1` ⇒ 充电（`mA > 0`）；`0` ⇒ 放电
- ★ 旧格式样本 `mcu = None`：**能用于 UI 域判读，不能用于曲线拟合** ——
  ⛔ **绝不用出厂曲线反解补一个 MCU 出来**（那是推算值，且曲线一改就会篡改历史样本）

## 用法

```python
from bkl_common import read_ledger, median, mad
samples = read_ledger()          # 直接读真机
samples = read_ledger(text=xml)  # 从已 dump 的 XML 文本读（离线复算/自检）
```
"""

import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(WS, "toolchain", "android-sdk", "platform-tools", "adb.exe")
SERIAL = os.environ.get("ANDROID_SERIAL", "")
PKG = "com.shware.mode"
PREFS = f"/data/data/{PKG}/shared_prefs/tntgo_battery.xml"
STATE = f"/data/data/{PKG}/files/tntgo_brightness.state"

#: ★ 曲线落盘格式版本 —— **必须与 `TntgoBklProfile.CURVE_FORMAT_V` 一致**
#: `1` = 嵌套数组（已弃用）／`2` = 扁平串 `mcu:ma:n;mcu:ma:n`
CURVE_FORMAT_V = 2


def adb(*args, timeout=40):
    """★ 只设了 `ANDROID_SERIAL` 才加 `-s`。

    ⚠️ `["-s", ""]` **不是"用默认设备"** —— adb 会报 `device '' not found`。
    （2026-09-15 清理脚本里写死的开发者内网 IP 时踩到：把默认值改成空串之后，
      不设环境变量就直接连不上，而报错信息看起来像"设备掉了"。）
    """
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def prefs_xml():
    """把 `tntgo_battery.xml` 整个 dump 出来（`run-as`，不需要 root）。"""
    return adb("shell", f"run-as {PKG} cat {PREFS}").stdout


def read_brightness_now():
    """读共享状态通道（`files/tntgo_brightness.state`）⇒ `{'mcu':..,'ui':..,'ts':..}`"""
    r = adb("shell", f"run-as {PKG} cat {STATE}")
    kv = {}
    for line in r.stdout.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            kv[k.strip()] = v.strip()
    return kv


def parse_samples(raw):
    """解析台账正文（`100:2000:-2420:0,66:497:-1251:0,...`）。

    ★ 两种格式都收；解析不了的条目**跳过并计数**（不静默吞掉）。
    返回 `(samples, stats)`，`samples` 是 dict 列表，字段见模块注释。
    """
    out = []
    bad = 0
    legacy = 0
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        p = item.split(":")
        try:
            if len(p) == 4:
                ui, mcu, ma, chg = (int(x) for x in p)
                out.append({"ui": ui, "mcu": mcu, "ma": ma, "chg": chg == 1})
            elif len(p) == 3:
                ui, ma, chg = (int(x) for x in p)
                out.append({"ui": ui, "mcu": None, "ma": ma, "chg": chg == 1})
                legacy += 1
            else:
                bad += 1
        except ValueError:
            bad += 1
    return out, {"bad": bad, "legacy_no_mcu": legacy, "total": len(out) + bad}


def xml_unescape(s):
    """★★★ **SharedPreferences 的 XML 会把 `"` 转义成 `&quot;`** —— 必须解码。

    ## 这个坑的形态（2026-09-15 实际踩到）

    `bkl.curve` 存的是 JSON，落进 XML 后长这样：

    ```
    <string name="bkl.curve">{&quot;v&quot;:1,...&quot;dis&quot;:[[497,1394.0,12]],...}</string>
    ```

    而按 `"dis"` 去找表头 ⇒ **一个都找不到** ⇒ 曲线被读成空。

    ⚠️⚠️ **它极难发现**：所有键同时 miss ⇒ 结果就是"空"，
    而"空"恰好是**合法状态**（mod 还没算过曲线）⇒ ★ **看起来完全正常**。

    ⇒ 凡是**解析失败与合法空值长得一样**的地方，都必须能自证。
      这里用 [read_curve] 的 `legacy` 项当**哨兵**：它与档位表同源同格式，
      若表为空而 legacy 有值 ⇒ **一定是解码/解析坏了，而不是"还没有曲线"**。
    """
    return (s.replace("&quot;", '"')
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&amp;", "&")
             .replace("&apos;", "'"))


def extract_samples(xml_text):
    """从 prefs XML 里取出 `bkl.samples` 的**正文**（String 型存的是元素文本，不是 value 属性）。"""
    m = re.search(r'name="bkl\.samples">([^<]*)<', xml_text)
    return xml_unescape(m.group(1)).strip() if m else ""


def read_ledger(text=None):
    """读台账并解析。

    @param text 给定时**不连设备**，直接从这段 XML 文本解析（离线复算用）
    @return `(samples, stats)`
    """
    xml_text = text if text is not None else prefs_xml()
    return parse_samples(extract_samples(xml_text))


def read_curve(text=None):
    """读盘上由 Kotlin 侧算好的曲线（`bkl.curve`）—— ★ 只用于**对账**，不用于判读。

    判读脚本必须**自己从原始样本算**（那才是独立复算）；这里读到的值是用来
    验证"Kotlin 侧算的"与"Python 侧算的"**是否一致** —— 即"两边各写一遍"的对账。

    ## ★★★ 为什么格式是**扁平串**而不是嵌套数组（v2）

    2026-09-15 对账时抓到：**Kotlin 与 Python 两份解析器各自独立犯了同一个
    off-by-one** —— 靠括号配平找嵌套数组结尾时，把结束的 `]` 排除在切片之外，
    于是表被读成**空**。

    ⚠️⚠️ 而**空表恰好是合法状态**（"还没有曲线"）⇒ 两份实现都错时
    **它们仍然是一致的** —— 「两边各写一遍，镜像全绿而实现是错的」的教科书形态。

    ⇒ ★ 修法是**把结构难点删掉**：`mcu:ma:n;mcu:ma:n` 用 `split` 就读完，
      没有配平、没有嵌套、没有正则。

    ⚠️ 也必须**先做 XML 实体解码**（见 [xml_unescape]）—— 否则 `&quot;`
    会让两个表头同时 miss，同样退化成"空表"。
    """
    xml_text = text if text is not None else prefs_xml()
    m = re.search(r'name="bkl\.curve">([^<]*)<', xml_text)
    if not m:
        return None
    body = xml_unescape(m.group(1))

    vm = re.search(r'"v":(\d+)', body)
    ver = int(vm.group(1)) if vm else None
    ts = re.search(r'"ts":(\d+)', body)
    legacy = re.search(r'"legacy":(\d+)', body)

    def levels(key):
        """`""` = 键在、表为空（合法）；`None` = **键都不在** ⇒ 格式不对（解析坏了）"""
        mm = re.search(r'"%s":"([^"]*)"' % key, body)
        if not mm:
            return None
        s = mm.group(1)
        if s == "":
            return []
        out = []
        for item in s.split(";"):
            p = item.split(":")
            if len(p) != 3:
                return None                      # ★ 表项解析不了 ⇒ 也算坏了，别静默丢
            try:
                out.append([int(p[0]), float(p[1]), int(p[2])])
            except ValueError:
                return None
        return out

    chg, dis = levels("chg"), levels("dis")
    return {
        "chg": chg, "dis": dis,
        # ★★ 自证：JSON 在盘上却解不出表 ⇒ **解析/格式不对**，不是"没有曲线"
        "parse_broken": bool(chg is None or dis is None or (ver is not None and ver != CURVE_FORMAT_V)),
        "v": ver,
        "ts": int(ts.group(1)) if ts else None,
        "legacy": int(legacy.group(1)) if legacy else None,
    }


# --------------------------------------------------------------------- 统计口径

def median(v):
    """★ 与 AR12 / N9 / Kotlin 侧**同一个口径**：先排序，奇数取中、偶数取中间两个的均值。"""
    if not v:
        return None
    s = sorted(v)
    n = len(s)
    return float(s[n // 2]) if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def mad(v):
    """`median(|x − median(x)|)` —— 稳健离散度（不用标准差：它被离群值主导）。"""
    m = median(v)
    if m is None:
        return None
    return median([abs(x - m) for x in v])
