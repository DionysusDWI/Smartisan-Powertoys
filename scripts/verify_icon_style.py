#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立校验：切进 res 的图标**到底来自哪一套风格**。

不信任 `pick()` 的自述 —— 直接从**落盘的 png** 反推：
把 res 里的图与两套源图各自缩到 64×64 比像素，谁近就是谁。

★ 判据可行是因为两套风格差异极大：扁平是纯色平涂（色彩方差小），
  拟物化是摄影棚背景 + 金属反光（方差大）。
"""
import glob
import io
import os
import sys

from PIL import Image, ImageStat

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, ".ref", "ui_assets")
RES = os.path.join(ROOT, "projects", "mode-launcher", "src", "app", "src", "main", "res")

SIZE = 64


def sig(path):
    im = Image.open(path).convert("RGB").resize((SIZE, SIZE), Image.LANCZOS)
    st = ImageStat.Stat(im)
    return st.mean, st.stddev


def dist(a, b):
    return sum((x - y) ** 2 for x, y in zip(a[0], b[0])) + \
           sum((x - y) ** 2 for x, y in zip(a[1], b[1]))


def main():
    # 两套源图：基名 → 特征
    ref = {"flat": {}, "skeuo": {}}
    for f in glob.glob(os.path.join(SRC, "*.jpg")):
        b = os.path.splitext(os.path.basename(f))[0]
        if b.startswith("_"):
            continue
        if b.startswith("skeuo_"):
            ref["skeuo"][b[6:]] = sig(f)
        else:
            ref["flat"][b] = sig(f)

    bad = 0
    checked = 0
    for png in sorted(glob.glob(os.path.join(RES, "drawable-xxxhdpi", "ic_*.png"))):
        base = os.path.basename(png)[len("ic_"):-len(".png")]
        # ic_feat_x → feat_x ; ic_cat_x → cat_x
        for pre in ("feat_", "cat_"):
            if base.startswith(pre):
                key = base
                break
        else:
            continue

        s = sig(png)
        scores = {k: dist(s, v[key]) for k, v in ref.items() if key in v}
        if not scores:
            print(f"  ? {key}: 源里找不到")
            continue
        got = min(scores, key=scores.get)
        want = "flat" if key.startswith("cat_") else "skeuo"
        mark = "✓" if got == want else "✗"
        if got != want:
            bad += 1
        checked += 1
        print(f"  {mark} {key:18} 实际={got:6} 期望={want:6} "
              f"(距离 flat={scores.get('flat', -1):.0f} skeuo={scores.get('skeuo', -1):.0f})")

    print(f"\n检查 {checked} 个，不符 {bad} 个")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
