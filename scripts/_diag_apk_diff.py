#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐条目比对两份 APK —— 判断"哈希不同"到底是【内容不同】还是【打包元数据不同】。

## 为什么需要它

同源码的**增量构建**与**clean 构建**产出的 APK **哈希不同**（实测）。
"哈希不同"有两种完全不同的含义，而**处置相反**：

| 含义 | 判据 | 后果 |
|---|---|---|
| **内容不同** | 有条目 CRC32 不同 | ⛔ 是两份**不同的产物**，不能互相替代 |
| **只有打包元数据不同** | 所有条目 CRC32 **全同**，只有 zip 头/时间戳不同 | ✅ **内容等价**，可互换（但**不许**说"逐字节相同"） |

★ 这与本项目纪律 ⑪ 同族：**解析失败与合法空值长得一样时，必须给它一个哨兵** ——
这里"哈希不同"就是那个含糊的信号，必须拆开。

用法：
    python scripts/_diag_apk_diff.py A.apk B.apk
"""

import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:                                                          # noqa: BLE001
    pass


def entries(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            out[i.filename] = (i.CRC, i.file_size, i.compress_size,
                               i.date_time, i.compress_type)
    return out


def main():
    if len(sys.argv) != 3:
        print("用法: python scripts/_diag_apk_diff.py A.apk B.apk")
        return 2
    a_path, b_path = sys.argv[1], sys.argv[2]
    a, b = entries(a_path), entries(b_path)

    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    both = sorted(set(a) & set(b))

    crc_diff = [n for n in both if a[n][0] != b[n][0]]
    size_diff = [n for n in both if a[n][1] != b[n][1]]
    time_diff = [n for n in both if a[n][3] != b[n][3]]

    print("A =", a_path)
    print("B =", b_path)
    print("条目数        : A={}  B={}".format(len(a), len(b)))
    print("只在 A / 只在 B: {} / {}".format(len(only_a), len(only_b)))
    for n in only_a[:10]:
        print("    只在 A:", n)
    for n in only_b[:10]:
        print("    只在 B:", n)
    print("同名不同 CRC  : {}".format(len(crc_diff)))
    for n in crc_diff[:10]:
        print("    {}  A.crc={}  B.crc={}".format(n, a[n][0], b[n][0]))
    print("同名不同 原始大小: {}".format(len(size_diff)))
    comp_diff = [n for n in both if a[n][2] != b[n][2]]
    print("同名不同 压缩后大小: {}".format(len(comp_diff)))
    for n in comp_diff[:5]:
        print("    {}  A={}  B={}".format(n, a[n][2], b[n][2]))
    print("同名不同 时间戳  : {}".format(len(time_diff)))
    for n in time_diff[:5]:
        print("    {}  A={}  B={}".format(n, a[n][3], b[n][3]))

    # ── 原始字节：差异落在哪里（不猜，直接数）
    with open(a_path, "rb") as f:
        da = f.read()
    with open(b_path, "rb") as f:
        db = f.read()
    print()
    print("文件字节数    : A={}  B={}".format(len(da), len(db)))
    if len(da) == len(db):
        d = [i for i in range(len(da)) if da[i] != db[i]]
        print("逐字节不同的位置数: {}".format(len(d)))
        if d:
            from collections import Counter
            c = Counter(i // 4096 for i in d)
            print("  首个不同偏移 = {}（文件尾往回 {} 字节）".format(d[0], len(da) - d[0]))
            print("  末个不同偏移 = {}（文件尾往回 {} 字节）".format(d[-1], len(da) - d[-1]))
            print("  差异最集中的 4KB 块（块号, 字节数）: {}".format(c.most_common(6)))
    else:
        print("⚠️ 两份字节数不同 ⇒ 不能做逐字节比对")

    print()
    if not only_a and not only_b and not crc_diff and not size_diff:
        print("★ 结论：**全部条目的内容（CRC32 ＋ 原始大小）完全相同**。")
        print("  哈希差异只可能来自 zip 打包层（时间戳/压缩/中央目录）。")
        print("  ⇒ 可以说「**内容等价**」；⛔ **不许**说「逐字节相同」。")
    else:
        print("★ 结论：**内容确实不同** ⇒ 这是两份不同的产物，不能互相替代。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
