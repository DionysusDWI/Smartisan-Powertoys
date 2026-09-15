# 04 · Android 桌面模式 / 自由窗口 / 多屏

> 状态:**已填充**(2026-09-11 起)
> 归档日期:2026-09-11

## 覆盖范围

Android 桌面模式(DeX/ReadyFor/PC 模式)开源实现、自由窗口(freeform)API 与命令演进、多屏渲染(Presentation/VirtualDisplay/overlay)

## 为什么需要

TNT 原生是 L0 实现,小米端只能做 L1+L3 等价物。本文件归档该路线的网络资料

---

## ★ AOSP A16 源码原文核实(2026-09-11)

> **背景**:任务 F 中,Operit(手机端)的《小米17PM窗口管理器_技术方案_v1》与本工作区
> `.paper/04-小米端复刻方案.md` 在 3 条命令/开关名上给出**互相矛盾**的说法。
> 由于目标平台是 **Android 16**,故直接拉取 **AOSP `android-16.0.0_r1` 源码原文**判定。

**抓取方式**(`raw.githubusercontent.com` 不可达,走 jsDelivr):

```bash
B="https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@android-16.0.0_r1"
curl -s -o f.java "$B/<path>"
```

**原始产物已落盘**:`.ref/Search-Results/raw/aosp16_*.java`(6 个文件 / 1.7 MB)

---

### ① `settings put global enable_freeform_support 1` —— ✅ **正确**

```java
// core/java/android/provider/Settings.java:13990-13995
/**
 * Whether to enable experimental freeform support for windows.
 * @hide
 */
@Readable
public static final String DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT
        = "enable_freeform_support";
```

- **常量名**是 `DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT`
- **常量的值**(= 实际写进 settings 的键)是 **`"enable_freeform_support"`**
- ⇒ 写 `development_enable_freeform_windows_support` = **写了一个不存在的键**,静默无效

**同一区域核实通过的其余键**(`.paper/04` §2.2 推荐序列,全部与源码逐字一致):

| 常量 | 值 | 行 |
|---|---|---|
| `DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES` | `force_resizable_activities` | :13986 |
| `DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT` | `enable_freeform_support` | :13994 |
| `DEVELOPMENT_OVERRIDE_DESKTOP_EXPERIENCE_FEATURES` | `override_desktop_experience_features` | :14005 |
| `DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES` | `override_desktop_mode_features` | :14014 |
| `DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS` | `force_desktop_mode_on_external_displays` | :14023 |
| `DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW` | `enable_non_resizable_multi_window` | :14035 |
| `DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR` | `render_shadows_in_compositor` | :14043 |
| `DEVELOPMENT_SHADE_DISPLAY_AWARENESS` | `shade_display_awareness` | :14050 |

> ★ **新发现 2 个键**(本工作区此前未记录,值得在小米端试):
> `override_desktop_experience_features`(强制开放「桌面体验」)与
> `override_desktop_mode_features`(强制允许把应用移到桌面 = freeform)。
> 二者注释明说是**覆盖设备能力开关**,可能是绕过 OEM 策略限制的入口。

---

### ② `am stack resize` —— ❌ **A16 已无此子命令**

```java
// services/core/java/com/android/server/am/ActivityManagerShellCommand.java:3401-3414
int runStack(PrintWriter pw) throws RemoteException {
    String op = getNextArgRequired();
    switch (op) {
        case "move-task": return runStackMoveTask(pw);
        case "list":      return runStackList(pw);
        case "info":      return runRootTaskInfo(pw);
        case "remove":    return runRootTaskRemove(pw);
        default:
            getErrPrintWriter().println("Error: unknown command '" + op + "'");
            return -1;
    }
}
```

**A16 的 `am stack` 只剩 `move-task / list / info / remove`,没有 `resize`。**

`am task` 的子命令表(:3499-3512):

```java
int runTask(PrintWriter pw) throws RemoteException {
    String op = getNextArgRequired();
    if (op.equals("lock"))            return runTaskLock(pw);
    else if (op.equals("resizeable")) return runTaskResizeable(pw);
    else if (op.equals("resize"))     return runTaskResize(pw);
    else if (op.equals("focus"))      return runTaskFocus(pw);
    ...
}
```

⇒ 正确的是 **`am task resize <taskId> <left> <top> <right> <bottom>`**(4 个**独立整数**参数,
由 `getBounds()` 逐个 `getNextArgRequired()` 读取,:3420-3445)。

> ⚠️ **注意**:Operit 的实测是在 **Android 10** 上做的,**当时 `am stack resize` 确实存在**。
> 这是**版本差异**,不是谁错了 —— 但**目标平台 A16 以上述判定为准**。

---

### ③ ★★★ `am task resize` 的**静默失败守卫**(本条最关键)

```java
// services/core/java/com/android/server/wm/ActivityTaskManagerService.java:2951-2968
public void resizeTask(int taskId, Rect bounds, int resizeMode) {
    enforceTaskPermission("resizeTask()");
    ...
    final Task task = mRootWindowContainer.anyTaskForId(taskId, MATCH_ATTACHED_TASK_ONLY);
    if (task == null) {
        Slog.w(TAG, "resizeTask: taskId=" + taskId + " not found");
        return;                                     // ← 静默返回
    }
    if (!task.getWindowConfiguration().canResizeTask()) {
        Slog.w(TAG, "resizeTask not allowed on task=" + task);
        return;                                     // ← ★ 静默返回,无异常
    }
    ...
}
```

守卫的判据:

```java
// core/java/android/app/WindowConfiguration.java:788-791
public boolean canResizeTask() {
    return mWindowingMode == WINDOWING_MODE_FREEFORM
            || mWindowingMode == WINDOWING_MODE_MULTI_WINDOW;
}
```

**⇒ `am task resize` 只对「已经在 FREEFORM 或 MULTI_WINDOW 里的 task」生效;
对全屏(默认)task 调用会 `Slog.w` 后 `return` —— 命令返回 0、无报错、bounds 不变。**

**推翻** Operit 方案 §2 的「连 `resizeMode=0` 的 App 也能改」——
在 A16 上不成立;必须**先把 task 推进 freeform**(如 `am start --windowingMode 5`),
再 resize。命令执行者需排查 `logcat | grep "resizeTask not allowed"` 才能发现失败。

---

### ④ `input -d DISPLAY_ID` —— ✅ **正确**;`input --ext-display` 在 A16 不存在

```java
// services/core/java/com/android/server/input/InputShellCommand.java:281-302
// Get displayId (optional).
int displayId = INVALID_DISPLAY;
if ("-d".equals(arg)) {
    displayId = getDisplayId();
    ...
}
...
switch (command) {
    case "text":         runText(inputSource, displayId);         break;
    case "keyevent":     runKeyEvent(inputSource, displayId);     break;
    case "tap":          runTap(inputSource, displayId);          break;
    case "swipe":        runSwipe(inputSource, displayId);        break;
    case "draganddrop":  runDragAndDrop(inputSource, displayId);  break;
    case "press":        runPress(inputSource, displayId);        break;
    case "roll":         runRoll(inputSource, displayId);         break;
}
```

⇒ A16 正确签名:**`input [<source>] [-d DISPLAY_ID] <command> ...`**
支持的 command 全集:`text / keyevent / tap / swipe / draganddrop / press / roll`。

> ⚠️ 同样是**版本差异**:Operit 在 Android 10 上用的 `input --ext-display` 当时可用。

---

### ⑤ 附带确认:`am display move-stack` 是 **AOSP 原生命令**

```java
// ActivityManagerShellCommand.java:3447-3455
int runDisplayMoveStack(PrintWriter pw) throws RemoteException {
    String rootTaskIdStr = getNextArgRequired();
    int rootTaskId = Integer.parseInt(rootTaskIdStr);
    String displayIdStr = getNextArgRequired();
    int displayId = Integer.parseInt(displayIdStr);
    mTaskInterface.moveRootTaskToDisplay(rootTaskId, displayId);
    return 0;
}
```

⇒ 2026-09-11 手机屏黑屏事故的元凶 `am display move-stack` **不是 Smartisan 特有命令,是 AOSP 原生**。
危险点在于它会把 root task **搬离当前 display**,而在 TNT 的 display0 上,
**搬走唯一的栈 = 无栈可合成 = 纯黑**(见 [PROGRESS-STATE §6 绝对禁区](../../../PROGRESS-STATE.MD))。

---

## 来源

| URL | 主题 | 抓取日期 | 关键信息 |
|---|---|---|---|
| `cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@android-16.0.0_r1/core/java/android/provider/Settings.java` | AOSP A16 `Settings.java` | 2026-09-11 | freeform/desktop 全部 `Settings.Global` 键的**常量名 ↔ 实际值**;见 ① |
| `.../services/core/java/com/android/server/am/ActivityManagerShellCommand.java` | AOSP A16 `am` 命令实现 | 2026-09-11 | `am stack` / `am task` / `am display` 子命令表;见 ②⑤ |
| `.../services/core/java/com/android/server/wm/ActivityTaskManagerService.java` | AOSP A16 ATMS | 2026-09-11 | `resizeTask()` 的 `canResizeTask()` 静默失败守卫;见 ③ |
| `.../core/java/android/app/WindowConfiguration.java` | AOSP A16 `WindowConfiguration` | 2026-09-11 | `canResizeTask()` 判据 = FREEFORM\|MULTI_WINDOW;见 ③ |
| `.../services/core/java/com/android/server/input/InputShellCommand.java` | AOSP A16 `input` 命令实现 | 2026-09-11 | `-d DISPLAY_ID` 参数与 command 全集;见 ④ |
| `.../services/core/java/com/android/server/wm/Task.java` | AOSP A16 `Task` | 2026-09-11 | `Task.resize()` 实现(备用核对) |

> **本地原始产物**:`.ref/Search-Results/raw/aosp16_*.java`(6 文件)
> **复现命令**:
> ```bash
> B="https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@android-16.0.0_r1"
> curl -s -o Settings.java "$B/core/java/android/provider/Settings.java"
> ```
