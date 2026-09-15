#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_doc_links.py —— 全仓 Markdown 内部链接断链检查。

★ 为什么需要它
--------------
本会话已经**两次**出现「文档里的相对链接指向不存在的东西」——
而这类错误**没人会发现**，直到有人真的去点它。

★★ 三个必须避开的坑（本脚本的判据就是照着它们写的）
--------------------------------------------------
1. **`.ref/` 与 `refs/` 下的文档是【冻结的归档快照】**
   —— 它们当年是 `.paper/` 里的文件，被整目录拷贝出去当交接包。
   相对链接（`02-TNT系统结构规格.md` / `../.ref/...`）**天然不解析**，
   ★ **不是缺陷，不要修**。默认跳过。

2. **行内 code span 里的方括号不是链接**
   例：`` `手机性能 [perf.mon] … [设置](802,1188)` ``
   —— 这是 uiautomator dump 的坐标文本，**不是** markdown 链接。
   ⇒ 先把围栏代码块与行内 code span 全部剥掉再匹配。

3. **`file://` / `http://` 等带 scheme 的 URI 不是相对路径**
   ⇒ 用 `^[a-zA-Z][a-zA-Z0-9+.-]*:` 排除，而不是只认 `http`。

★ 退出码：0 = 无断链，1 = 有断链（可直接用作判据）。
"""

from __future__ import annotations

import io
import os
import re
import sys

# ★ 这些目录里的 markdown 不参与检查（见文件头坑 1 与第三方工具）
SKIP_DIRS = {
    "node_modules", ".venv", "vendor", "handover", ".git",
    "build", ".gradle", "toolchain", ".ref", "refs", "archive",
}

FENCE = re.compile(r"```.*?```", re.S)
CODE_SPAN = re.compile(r"`[^`]*`")
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:")


def strip_code(text: str) -> str:
    """剥掉围栏代码块与行内 code span（坑 2）。"""
    return CODE_SPAN.sub("", FENCE.sub("", text))


def check(root: str = ".") -> list[tuple[str, str]]:
    bad: list[tuple[str, str]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if not name.endswith(".md"):
                continue
            path = os.path.join(dirpath, name).replace(os.sep, "/")
            try:
                text = io.open(path, encoding="utf-8").read()
            except (OSError, UnicodeDecodeError) as exc:
                print(f"  ⚠ 读不了 {path}: {exc}")
                continue
            for match in LINK.finditer(strip_code(text)):
                target = match.group(1).split("#")[0]
                if not target or SCHEME.match(target):   # 坑 3
                    continue
                resolved = os.path.normpath(
                    os.path.join(os.path.dirname(path), target)
                )
                if not os.path.exists(resolved):
                    bad.append((path, target))
    return bad


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    bad = check()
    print(f"活文档内部链接断链：{len(bad)}")
    for path, target in bad:
        print(f"  X {path} -> {target}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
