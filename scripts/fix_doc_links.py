#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""保守修文档里的相对链接断链。

## 判据（只在**确定**时才动手）

对每个解析不到的相对链接，**依次尝试**加一层 `../`（最多 3 层），
**只有恰好有一个前缀能让它解析成功时**才改写。
⇒ 歧义的、指向不存在文件的、故意用省略号简写的，**一律不动**。

## 背景

`PROGRESS-STATE.MD` 是唯一主入口，它指向 `.paper/plans/*.md`；
而那些计划书之间互相引用时常写成**相对仓库根**的路径（如 `.paper/02-xxx.md`），
从 `.paper/plans/` 出发是**解析不到**的 —— 读者点一下就 404。

★ 只修**路径前缀**，不改文件名、不改文字。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 只处理这几类；★ 排除 file:// / http / 带省略号的
RX_LINK = re.compile(r"\]\((?!https?:|file:|mailto:|#)([^)\s]+)\)")

SKIP_SUBSTR = ("…", "|", " ", "<", ">")


def candidates(link):
    """给一个断链，列出"加 N 层 ../ "的候选。"""
    return [("../" * n) + link for n in (1, 2, 3)]


def main():
    targets = []
    for dirpath, _d, files in os.walk(os.path.join(ROOT, ".paper")):
        for f in files:
            if f.endswith(".md"):
                targets.append(os.path.join(dirpath, f))
    targets.append(os.path.join(ROOT, "PROGRESS-STATE.MD"))

    changed = 0
    for p in sorted(targets):
        base = os.path.dirname(p)
        t = io.open(p, encoding="utf-8").read()
        orig = t

        def fix(m):
            link = m.group(1)
            if any(s in link for s in SKIP_SUBSTR):
                return m.group(0)                       # 故意省略 / 非路径，别碰
            if os.path.exists(os.path.normpath(os.path.join(base, link))):
                return m.group(0)                       # 本来就好的
            hits = [c for c in candidates(link)
                    if os.path.exists(os.path.normpath(os.path.join(base, c)))]
            if len(hits) != 1:
                return m.group(0)                       # 0 个（真没了）或 >1 个（歧义）⇒ 不动
            print(f"  {os.path.relpath(p, ROOT)}")
            print(f"      {link}  →  {hits[0]}")
            return "](" + hits[0] + ")"

        t = RX_LINK.sub(fix, t)
        if t != orig:
            io.open(p, "w", encoding="utf-8").write(t)
            changed += 1

    print(f"\n改了 {changed} 个文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
