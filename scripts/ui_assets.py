#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UI 资源后处理工具（任务 AP）。

## 干什么

1. `--sheet`    把 `.ref/ui_assets/*.jpg` 拼成一张联络表（一眼看风格是否统一）
2. `--slice`    把生成的 1024×1024 图标切好、缩放到 Android 各密度，落到 app 的 res 里
3. `--ref`      从设备截图里裁出锤子原生图标（**拟物化风格的参考**）

## 用法

    python scripts/ui_assets.py --sheet
    python scripts/ui_assets.py --ref <截图> --crop x,y,w,h --out name
    python scripts/ui_assets.py --slice
"""
import argparse
import glob
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, ".ref", "ui_assets")


def _index():
    """扫出两套图：`{"flat": {基名: 路径}, "skeuo": {基名: 路径}}`。

    扁平那套没有前缀（`app_icon.jpg`）；拟物化那套统一 `skeuo_` 前缀
    （两套要并存，不能互相覆盖 `.ref/ui_assets/` 里的源图）。
    """
    idx = {"flat": {}, "skeuo": {}}
    for f in sorted(glob.glob(os.path.join(SRC, "*.jpg"))):
        b = os.path.splitext(os.path.basename(f))[0]
        if b.startswith("_"):
            continue
        if b.startswith("skeuo_"):
            idx["skeuo"][b[len("skeuo_"):]] = f
        else:
            idx["flat"][b] = f
    return idx


# ★★★ 混用规则（用户 2026-09-14 定）
#
# | 用在哪 | 取哪套 | 为什么 |
# |---|---|---|
# | **功能图标**（`feat_*`）＋ 应用图标 ＋ 装饰带 | **拟物化** | 它们是"一个个具体的物件"，拟物化才给得出身份感 |
# | **分类图标**（`cat_*`） | **扁平** | 它们是**结构性**的分组标识，扁平更克制、不跟功能图标抢注意力 |
#
# ★ 顺带得到一个**有用的副作用**：
#   分类图标是"mod 没申报 `MOD_ICON` 时的兜底"。
#   两套风格不同 ⇒ **一眼就能看出这一项用的是兜底图标**，而不是某个专属图标。
MIXED_FLAT_PREFIXES = ("cat_",)


def style_of(base, set_name):
    """某个基名该取哪一套风格。"""
    if set_name != "mixed":
        return set_name
    return "flat" if base.startswith(MIXED_FLAT_PREFIXES) else "skeuo"


def pick(set_name):
    """按风格集挑图，并**把前缀剥掉**。

    ★★★ 剥掉前缀是为了让各套风格切出来的**资源名完全一样**
    （都是 `ic_feat_x.png`）⇒ **换风格只要重跑本脚本，Kotlin 与布局零改动**。

    @return [(绝对路径, 去前缀的基名)]
    """
    idx = _index()
    if set_name == "mixed":
        bases = sorted(set(idx["flat"]) | set(idx["skeuo"]))
        out = []
        for b in bases:
            want = style_of(b, "mixed")
            f = idx[want].get(b) or idx["flat"].get(b) or idx["skeuo"].get(b)
            if f:
                out.append((f, b))
            else:
                print(f"  ⚠ {b}: 两套里都没有")
        return out
    return [(f, b) for b, f in sorted(idx[set_name].items())]


def sheet(args):
    items = pick(args.set)
    if not items:
        print(f"✗ .ref/ui_assets 里没有「{args.set}」这套图")
        return
    cell, cols = 300, 4
    rows = (len(items) + cols - 1) // cols
    pad, label_h = 8, 22
    W = cols * (cell + pad) + pad
    H = rows * (cell + label_h + pad) + pad
    out = Image.new("RGB", (W, H), (24, 28, 34))
    d = ImageDraw.Draw(out)
    for i, (f, base) in enumerate(items):
        r, c = divmod(i, cols)
        x = pad + c * (cell + pad)
        y = pad + r * (cell + label_h + pad)
        try:
            im = Image.open(f).convert("RGB").resize((cell, cell), Image.LANCZOS)
        except Exception as e:
            print(f"  ⚠ {os.path.basename(f)} 打不开: {e}")
            continue
        out.paste(im, (x, y))
        d.text((x + 4, y + cell + 5), base[:34], fill=(220, 220, 220))
    # 扁平那套沿用老名字（兼容既有引用）；其它风格带后缀
    fn = "_contact_sheet.jpg" if args.set == "flat" else f"_contact_sheet_{args.set}.jpg"
    dst = os.path.join(SRC, fn)
    out.save(dst, quality=88)
    print(f"✓ 联络表[{args.set}]: {os.path.relpath(dst, ROOT)}  ({len(items)} 张, {W}×{H})")


def ref(args):
    """从截图里裁一块出来 —— 用来取锤子原生图标做拟物化风格参考"""
    im = Image.open(args.ref).convert("RGB")
    x, y, w, h = [int(v) for v in args.crop.split(",")]
    crop = im.crop((x, y, x + w, y + h))
    name = args.out or "ref_crop"
    dst = os.path.join(SRC, f"_{name}.jpg")
    crop.save(dst, quality=95)
    print(f"✓ 裁出 {os.path.relpath(dst, ROOT)}  ({crop.width}×{crop.height})")


# Android 密度 → 图标边长（dp）
DENSITIES = {
    "mdpi": (1.0, 48),
    "hdpi": (1.5, 72),
    "xhdpi": (2.0, 96),
    "xxhdpi": (3.0, 144),
    "xxxhdpi": (4.0, 192),
}


def slice_icons(args):
    """把生成的图落成 Android 资源。

    ★ 命名约定（与 Feature.Category / mod id 对应）：
        app_icon.jpg          → mipmap-*/ic_powertoys.png
        feat_<x>.jpg          → drawable-*/ic_feat_<x>.png
        cat_<x>.jpg           → drawable-*/ic_cat_<x>.png
        header.jpg            → drawable-*/ui_header.png
    """
    res = os.path.join(ROOT, "projects", "mode-launcher", "src", "app", "src", "main", "res")
    if not os.path.isdir(res):
        print(f"✗ 找不到 res 目录: {res}")
        return

    files = pick(args.set)
    if not files:
        print(f"✗ 没有可切的图（风格集 = {args.set}）")
        return

    # 应用图标走 mipmap（launcher 用），其余走 drawable
    n = 0
    for f, base in files:
        if base == "app_icon":
            kind, name, bucket = "mipmap", "ic_powertoys", "ic_launcher"
        elif base.startswith("feat_"):
            kind, name = "drawable", f"ic_feat_{base[5:]}"
        elif base.startswith("cat_"):
            kind, name = "drawable", f"ic_cat_{base[4:]}"
        elif base == "header":
            kind, name = "drawable", "ui_header"
        else:
            kind, name = "drawable", f"ui_{base}"

        im = Image.open(f).convert("RGB")
        for dens, (scale, _) in DENSITIES.items():
            size = int(round(48 * scale))
            if base == "app_icon":
                size = int(round(48 * scale))
            d = os.path.join(res, f"{kind}-{dens}")
            os.makedirs(d, exist_ok=True)
            im.resize((size, size), Image.LANCZOS).save(os.path.join(d, f"{name}.png"), "PNG")
        n += 1
        print(f"  ✓ {base:18} → {kind}-*/{name}.png")
    print(f"\n完成：{n} 个资源 × {len(DENSITIES)} 个密度")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("--slice", action="store_true")
    ap.add_argument("--set", dest="set", default="flat",
                    choices=["flat", "skeuo", "mixed"],
                    help="风格集：flat=扁平矢量；skeuo=拟物化（仿锤子原生）；"
                         "mixed=★ 混用（功能图标拟物化 ＋ 分类图标扁平，见 MIXED_FLAT_PREFIXES）")
    ap.add_argument("--ref", help="参考截图路径")
    ap.add_argument("--crop", help="x,y,w,h")
    ap.add_argument("--out", help="裁切结果的名字")
    a = ap.parse_args()
    if a.sheet:
        sheet(a)
    elif a.ref and a.crop:
        ref(a)
    elif a.slice:
        slice_icons(a)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
