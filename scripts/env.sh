#!/usr/bin/env bash
# ============================================================================
#  Smartisan-TNT-Secondary — portable toolchain activation
#
#  Usage:   source scripts/env.sh
#  Scope:   the CURRENT shell only. Nothing is written to the system
#           environment, the registry, or the machine PATH.
#  Undo:    close the shell (or `source scripts/env.sh --off`)
# ============================================================================

_TNT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export TNT_WORKSPACE="$_TNT_ROOT"
export TNT_TOOLCHAIN="$_TNT_ROOT/toolchain"

if [ "${1:-}" = "--off" ]; then
  unset JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT GRADLE_HOME GRADLE_USER_HOME TNT_WORKSPACE TNT_TOOLCHAIN
  echo "[env.sh] toolchain environment cleared"
  unset _TNT_ROOT
  return 0 2>/dev/null || exit 0
fi

# --- JDK 17 (portable Temurin) ----------------------------------------------
# Auto-detect the extracted JDK directory (the name carries the build number).
unset JAVA_HOME
for _d in "$TNT_TOOLCHAIN"/jdk-17*; do
  if [ -x "$_d/bin/java.exe" ] || [ -x "$_d/bin/java" ]; then
    export JAVA_HOME="$_d"
    break
  fi
done

# --- Android SDK (portable cmdline-tools + platform packages) ---------------
export ANDROID_HOME="$TNT_TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$TNT_TOOLCHAIN/.android"
mkdir -p "$ANDROID_USER_HOME"

# --- Gradle (portable distribution + isolated cache) ------------------------
unset GRADLE_HOME
for _d in "$TNT_TOOLCHAIN"/gradle-8.*; do
  if [ -x "$_d/bin/gradle" ] || [ -x "$_d/bin/gradle.bat" ]; then
    export GRADLE_HOME="$_d"
    break
  fi
done
export GRADLE_USER_HOME="$TNT_TOOLCHAIN/.gradle"
mkdir -p "$GRADLE_USER_HOME"

# --- PATH (prepend, current shell only) -------------------------------------
_TNT_PATH=""
[ -n "$JAVA_HOME" ]   && _TNT_PATH="$_TNT_PATH$JAVA_HOME/bin:"
[ -n "$GRADLE_HOME" ] && _TNT_PATH="$_TNT_PATH$GRADLE_HOME/bin:"
_TNT_PATH="$_TNT_PATH$ANDROID_HOME/cmdline-tools/latest/bin"
_TNT_PATH="$_TNT_PATH:$ANDROID_HOME/platform-tools"
_TNT_PATH="$_TNT_PATH:$TNT_TOOLCHAIN/bin"
# jadx (versioned folder, so glob for it)
for _j in "$TNT_TOOLCHAIN"/jadx-*/bin; do
  [ -d "$_j" ] && _TNT_PATH="$_TNT_PATH:$_j" && break
done
_TNT_PATH="$_TNT_PATH:$TNT_WORKSPACE/.venv/Scripts"
export PATH="$_TNT_PATH:$PATH"

# --- Report ------------------------------------------------------------------
echo "[env.sh] TNT_WORKSPACE    = $TNT_WORKSPACE"
echo "[env.sh] JAVA_HOME        = ${JAVA_HOME:-<MISSING>}"
echo "[env.sh] ANDROID_HOME     = $ANDROID_HOME"
echo "[env.sh] GRADLE_HOME      = ${GRADLE_HOME:-<MISSING>}"
echo "[env.sh] GRADLE_USER_HOME = $GRADLE_USER_HOME"
[ -n "$JAVA_HOME" ] && "$JAVA_HOME/bin/java" -version 2>&1 | head -1

unset _d _TNT_ROOT _TNT_PATH
