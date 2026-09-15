#!/usr/bin/env python3
"""
修复 Gradle 在 Windows 上的 transforms 缓存问题。

症状（本项目在 P: 盘上必现）：
    Execution failed for task ':app:dataBindingMergeDependencyArtifactsDebug'.
    > Could not move temporary workspace (...transforms-4\<hash>-<uuid>)
      to immutable location (...transforms-4\<hash>)
    原因: java.nio.file.AccessDeniedException

成因：Gradle 把 artifact transform 的结果先写进 `<hash>-<uuid>` 临时目录，
再用原子重命名搬到 `<hash>`。Windows 上如果临时目录里还有句柄没释放
（Defender 实时扫描 / 目录树很深），重命名会被拒绝。Gradle 不会重试，
于是构建失败，而那个临时目录其实已经写完整了。

本脚本做的事：把遗留的 `<hash>-<uuid>` 补成 `<hash>`，让下次构建直接命中缓存。
配合 scripts/build.sh 使用（构建失败时自动调用并重试）。
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

TEMP_RE = re.compile(
    r"^([0-9a-f]{32})-"
    r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$"
)


def default_cache_dir() -> Path:
    root = Path(__file__).resolve().parent.parent
    return root / "toolchain" / ".gradle" / "caches"


def fix(transforms_dir: Path, dry_run: bool = False) -> tuple[int, int, int]:
    """返回 (补全数, 跳过数, 失败数)。"""
    if not transforms_dir.is_dir():
        print(f"[fix] 目录不存在，跳过: {transforms_dir}")
        return (0, 0, 0)

    done = skipped = failed = 0
    for name in sorted(os.listdir(transforms_dir)):
        m = TEMP_RE.match(name)
        if not m:
            continue
        target = transforms_dir / m.group(1)
        if target.exists():
            skipped += 1
            continue
        src = transforms_dir / name
        if dry_run:
            print(f"[fix] 将补全: {name} -> {m.group(1)}")
            done += 1
            continue
        try:
            os.rename(src, target)
            print(f"[fix] 已补全: {name} -> {m.group(1)}")
            done += 1
        except OSError as exc:
            print(f"[fix] 失败: {name}: {exc}", file=sys.stderr)
            failed += 1
    return (done, skipped, failed)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--cache-dir",
        type=Path,
        default=default_cache_dir(),
        help="Gradle caches 目录（默认: <workspace>/toolchain/.gradle/caches）",
    )
    ap.add_argument("--dry-run", action="store_true", help="只打印，不改动")
    args = ap.parse_args()

    total_done = total_skip = total_fail = 0
    for d in sorted(args.cache_dir.glob("transforms-*")):
        done, skipped, failed = fix(d, args.dry_run)
        if done or failed:
            print(f"[fix] {d.name}: 补全 {done}, 跳过 {skipped}, 失败 {failed}")
        total_done += done
        total_skip += skipped
        total_fail += failed

    print(f"[fix] 合计: 补全 {total_done}, 跳过 {total_skip}, 失败 {total_fail}")
    return 1 if total_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
