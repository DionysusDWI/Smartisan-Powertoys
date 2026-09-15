#!/usr/bin/env bash
# ============================================================================
#  构建工作区内的 Android 项目（debug APK）
#
#  用法:
#     scripts/build.sh                  # 构建默认目标集
#     scripts/build.sh mode             # 只构建 MODE 启动器（:app）
#     scripts/build.sh mod-tntgo        # 只构建 TNT GO 电量 mod
#     scripts/build.sh mode-all         # 启动器 + 全部 mod
#     scripts/build.sh flashpill
#     scripts/build.sh --list           # 列出所有可构建目标
#
#  目标表在 scripts/targets.sh（build.sh / deploy.sh 共用单一真源）。
#
#  说明:
#     在 P: 盘上 Gradle 的 artifact transform 缓存偶尔会因 Windows 文件句柄
#     未释放而"移动失败"。本脚本在失败后调用 scripts/fix-gradle-transforms.py
#     补全遗留目录并自动重试，最多 3 轮。
# ============================================================================
set -uo pipefail

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$WORKSPACE/scripts/env.sh" >/dev/null
# shellcheck source=./targets.sh
source "$WORKSPACE/scripts/targets.sh"

PY="$WORKSPACE/.venv/Scripts/python.exe"
[ -x "$PY" ] || PY="python"

if [ "${1:-}" = "--list" ]; then
  echo "可构建目标："
  for n in $(tnt_target_names); do
    printf '  %-12s  %-56s  %s\n' "$n" "${TNT_SRC[$n]#$WORKSPACE/}" "$(tnt_gradle_tasks "$n")"
  done
  exit 0
fi

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=("${TNT_DEFAULT_TARGETS[@]}")
fi

rc=0
for name in "${targets[@]}"; do
  if ! tnt_has_target "$name"; then
    echo "[build] 未知目标: $name（跑 scripts/build.sh --list 看可选）" >&2
    rc=1
    continue
  fi

  src="${TNT_SRC[$name]}"
  tasks="$(tnt_gradle_tasks "$name")"
  echo "===== 构建 $name （$tasks）====="

  attempt=1
  while :; do
    # shellcheck disable=SC2086
    if (cd "$src" && ./gradlew $tasks --console=plain); then
      echo "[build] $name 构建成功"
      break
    fi
    if [ "$attempt" -ge 3 ]; then
      echo "[build] $name 构建失败（已重试 $attempt 次）" >&2
      rc=1
      break
    fi
    echo "[build] 构建失败，尝试补全 Gradle transform 缓存后重试 ($attempt/3)…"
    "$PY" "$WORKSPACE/scripts/fix-gradle-transforms.py" || true
    attempt=$((attempt + 1))
  done

  # 报告产物
  n="$(tnt_module_count "$name")"
  i=0
  while [ "$i" -lt "$n" ]; do
    apk="$(tnt_apk_path "$name" "$i")"
    if [ -f "$apk" ]; then
      echo "[build]   产物: ${apk#$WORKSPACE/}  ($(du -h "$apk" | cut -f1))"
    else
      echo "[build]   ⚠️ 没找到产物: ${apk#$WORKSPACE/}" >&2
    fi
    i=$((i + 1))
  done
done

exit $rc
