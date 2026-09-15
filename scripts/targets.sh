#!/usr/bin/env bash
# ============================================================================
#  构建 / 部署【目标表】—— build.sh 与 deploy.sh 的单一真源
#
#  由 scripts/build.sh、scripts/deploy.sh 在设好 $WORKSPACE 之后 source。
#
#  一个「目标」= 一次可独立构建/部署的东西。可以是单模块工程，
#  也可以是【多模块工程里的若干模块】—— MODE 启动器就是后者
#  （一个工程出 :app + :mod-hello + :mod-tntgo-battery 三个独立 APK）。
#
#  表结构（并行关联数组，靠 name 对齐）：
#    TNT_SRC[name]      源码根目录（含 gradlew）
#    TNT_MODULES[name]  gradle 模块，空格分隔（如 ":app" 或 ":app :mod-hello"）
#    TNT_PKGS[name]     与模块【一一对应】的 applicationId
#    TNT_FLAGS[name]    与模块【一一对应】的后置动作，空格分隔：
#                         overlay = 授 SYSTEM_ALERT_WINDOW appop
#                         usb     = 打印 USB 授权提示
#                         -       = 无
#
#  ★ 为什么包名/动作要跟模块一一对应：一个工程出多个 APK 时，
#    每个的包名和它需要的权限都不一样。
#
#  ★ APK 产物路径的统一规律（实测确认）：
#      <源目录>/<模块名不带冒号>/build/outputs/apk/debug/<模块名不带冒号>-debug.apk
#    ⇒ ":app" ⇒ app/build/outputs/apk/debug/app-debug.apk
#    ⇒ ":mod-hello" ⇒ mod-hello/build/outputs/apk/debug/mod-hello-debug.apk
# ============================================================================

#  ⚠️ 必须先 declare -A —— 调用方带着 `set -u`，未声明的关联数组赋值会
#     直接报 "unbound variable" 退出。
# ----------------------------------------------------------------------------

declare -A TNT_SRC TNT_MODULES TNT_PKGS TNT_FLAGS
# ★★★★ 任务 AL 新增：安装前要先卸载的旧独立包（只有 powertoys 用）
#   ⚠️ 必须在这里一起 declare —— 调用方带 `set -u`，
#      未声明的关联数组**取值**也会报 unbound variable（本任务踩过一次）
declare -A TNT_UNINSTALL

# ★★★★★ 任务 AL 新增：无障碍组件的全名 —— 有了它，无障碍可以**用 adb 自动登记**，
#   不必再让用户去「辅助功能 → 服务」里手点。
#   ★ 依据：任务 AK 实测 `settings put secure enabled_accessibility_services <comp>`
#     ＋ `accessibility_enabled 1` **确实生效**（dumpsys 里能看到 Bound services）。
#   ⚠️ 但 `am force-stop <pkg>` 会**清掉**这个登记 ⇒ 每次部署后都要重设。
declare -A TNT_A11Y

# --- MODE 启动器（多模块工程） ----------------------------------------------
# ★ 性能探针（任务 AK）—— **诊断工具，不是产品组件**（targetSdk=27 以豁免隐藏 API 限制）
TNT_SRC[perf-probe]="$WORKSPACE/projects/mode-launcher/src"
TNT_MODULES[perf-probe]=":perf-probe"
TNT_PKGS[perf-probe]="com.shware.perfprobe"
TNT_FLAGS[perf-probe]="-"

# ★★★★★ Smartisan Powertoys —— **一体化应用**（任务 AL）
#
# 把「启动器 + 6 个 mod」合并成【一个 APK】⇒ ★ **悬浮窗只需要授权一次**。
#
# ★ `applicationId` 保持 `com.shware.mode` —— 为了保住 Shizuku 授权与
#   Smartisan 悬浮窗授权（换包名 = 两道只能人点的门）。
#
# ⚠️ 安装前必须卸掉旧的独立 mod 包，否则同一 mod 会出现两份。
TNT_SRC[powertoys]="$WORKSPACE/projects/mode-launcher/src"
TNT_MODULES[powertoys]=":powertoys"
TNT_PKGS[powertoys]="com.shware.mode"
TNT_FLAGS[powertoys]="overlay,a11y"
TNT_A11Y[powertoys]="com.shware.mode/com.shware.mode.mod.brightness.KeyFilterService"
TNT_UNINSTALL[powertoys]="com.shware.mode.mod.hello com.shware.mode.mod.tntgo com.shware.mode.mod.perfmon com.shware.mode.mod.livecaption com.shware.mode.mod.brightness com.shware.mode.mod.perfmode"

# ★★★★★ `mode-all` = **一体化应用**（任务 AL 之后，它就是 powertoys）
#
# ⚠️ 合并前的 `mode-all` 是"启动器 + 6 个 mod 各出一个 APK"；
#    现在各 mod 都是 **library**，不再单独出 APK ⇒ 那条老路已废。
#    这里保留目标名（很多文档/肌肉记忆都用它），指向同一个包。
TNT_SRC[mode-all]="$WORKSPACE/projects/mode-launcher/src"
TNT_MODULES[mode-all]=":powertoys"
TNT_PKGS[mode-all]="com.shware.mode"
TNT_FLAGS[mode-all]="overlay,a11y"
TNT_A11Y[mode-all]="com.shware.mode/com.shware.mode.mod.brightness.KeyFilterService"
TNT_UNINSTALL[mode-all]="com.shware.mode.mod.hello com.shware.mode.mod.tntgo com.shware.mode.mod.perfmon com.shware.mode.mod.livecaption com.shware.mode.mod.brightness com.shware.mode.mod.perfmode"
# --- 旧的 TNT GO 电量探针（**已被 mod-tntgo 取代，用户 2026-09-12 卸载**）-----
TNT_SRC[tntgo]="$WORKSPACE/projects/tntgo-battery-overlay/src"
TNT_MODULES[tntgo]=":app"
TNT_PKGS[tntgo]="com.shware.tntgo.battery"
TNT_FLAGS[tntgo]="overlay"

# --- 闪念胶囊（小米线）--------------------------------------------------------
TNT_SRC[flashpill]="$WORKSPACE/projects/flash-pill-port/src"
TNT_MODULES[flashpill]=":app"
TNT_PKGS[flashpill]="com.shware.flashpill"
TNT_FLAGS[flashpill]="-"

# --- 默认要构建的目标（不带参数时）-------------------------------------------
TNT_DEFAULT_TARGETS=(mode-all flashpill)

# ----------------------------------------------------------------------------
#  工具函数
# ----------------------------------------------------------------------------

# 目标存在吗
tnt_has_target() { [ -n "${TNT_SRC[$1]:-}" ]; }

# 列出所有目标名（给用法提示用）
tnt_target_names() { printf '%s\n' "${!TNT_SRC[@]}" | sort; }

# 目标的模块列表
tnt_modules() { echo "${TNT_MODULES[$1]}"; }

# 第 n 个模块（0-based）的 APK 路径
tnt_apk_path() {
  local name="$1" idx="$2"
  local src="${TNT_SRC[$name]}"
  local mod
  mod="$(echo "${TNT_MODULES[$name]}" | tr ' ' '\n' | sed -n "$((idx + 1))p")"
  mod="${mod#:}"
  echo "$src/$mod/build/outputs/apk/debug/$mod-debug.apk"
}

# 第 n 个模块的包名
tnt_pkg() {
  echo "${TNT_PKGS[$1]}" | tr ' ' '\n' | sed -n "$(($2 + 1))p"
}

# 第 n 个模块的动作 flags
tnt_flags() {
  echo "${TNT_FLAGS[$1]}" | tr ' ' '\n' | sed -n "$(($2 + 1))p"
}

# 模块数量
tnt_module_count() {
  echo "${TNT_MODULES[$1]}" | wc -w | tr -d ' '
}

# ★★★★ 任务 AL：该目标安装前要先【卸载】的旧独立包（空 = 不卸）
#   合并后同一 mod 会同时存在"旧独立 APK"和"新合并包"两份 ⇒ 必须卸掉旧的。
tnt_uninstall() {
  echo "${TNT_UNINSTALL[$1]:-}"
}

# ★★★★ 该目标的无障碍组件全名（空 = 不适用）
#   ★ 有了它，无障碍可以**用 adb 自动登记**，不必让用户去「辅助功能 → 服务」里手点
tnt_a11y() {
  echo "${TNT_A11Y[$1]:-}"
}

# 拼出 gradle 任务串，如 ":app:assembleDebug :mod-hello:assembleDebug"
tnt_gradle_tasks() {
  local t=""
  for m in ${TNT_MODULES[$1]}; do t="$t ${m}:assembleDebug"; done
  echo "${t# }"
}
