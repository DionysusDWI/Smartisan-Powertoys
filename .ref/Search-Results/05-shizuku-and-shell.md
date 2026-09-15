# 05 · Shizuku 与 adb shell 编排

> 状态:**已归档**(2026-09-12 填充)
> 归档日期:2026-09-12 ｜ 抓取方式:`curl` 直连 `raw.githubusercontent.com`(**当时可达**)
> ★ **背景**:任务 V 要回答「启动器能不能替 mod 自动授 Shizuku 权限」

## 覆盖范围

Shizuku 的**权限模型**、能力边界、API 面、UserService 机制

## 为什么需要

MODE 启动器的 mod 若要读 `/dev/input`、调 `am`/`wm` 等特权动作，需要 Shizuku。
本文件回答：**这些授权能不能由启动器代劳？**

---

## ★★★ 核心结论（**对本案的决定性**）

> **不能。**「启动器替 mod 自动授 Shizuku 权限」在 **无 root** 下**做不到**，
> 且这是 **API 设计上就封死的**，不是我们没找对方法。

### 三条依据

| # | 依据 | 出处 |
|---|---|---|
| **1** | 权限 API 只有 **`Shizuku.requestPermission(int code)`** —— **只为自己请求**。第 3 方包名/uid 参数**不存在** | 官方开发指南 §Request permission |
| **2** | ★ **文档原文**：「**Shell (ADB) cannot access other apps' data files `/data/user/0/<package>`**」<br/>⇒ 权限库在 Shizuku 应用私有 data 里，**shell 读不到也写不了** | 同上 §Differents of the privilege betweent ADB and ROOT |
| **3** | `bindUserService` 是 **Shizuku API 调用** ⇒ **要求调用方自己已获授权**<br/>⇒ **UserService 也绕不过**「每个 app 各自授权」 | 同上 §UserService |

**⇒ 唯一不破坏安全模型的替代路**：**宿主中转** ——
mod **不碰 Shizuku**，改为向宿主（已授权）请求**白名单能力**。

---

## 关键事实（可复用）

### 权限模型

```java
// 官方推荐流程（Shizuku-API README §Request permission）
if (Shizuku.isPreV11()) return false;                       // v11 以前不支持
if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) return true;
else if (Shizuku.shouldShowRequestPermissionRationale()) return false;  // 用户选了"拒绝且不再问"
else { Shizuku.requestPermission(code); return false; }     // 弹窗
```
- 结果通过 `Shizuku.addRequestPermissionResultListener` 回调
- **`Shizuku.getUid()`** → **root = 0**；**ADB = 2000**

### ADB 的权限边界（★ 重要，容易误判）

> 「What ADB can do is **significantly different from ROOT**」
> - Android 侧：`Shell` app 在 [它的 AndroidManifest](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/Shell/AndroidManifest.xml)
>   里声明的权限 —— **随 Android 版本变化**
> - Linux 侧：uid / capabilities / SELinux context
> - ★ 例：**shell 无法访问其他应用的 `/data/user/0/<package>`**

### UserService 机制

| 项 | 值 |
|---|---|
| 身份 | **root(0)** 或 **shell(2000)** —— 与我们用 adb 启动时是 2000 |
| 非 SDK API | ✅ **无限制** |
| ⚠️ **不是合法的 Android 应用进程** | `Context#registerReceiver`、`Context#getContentResolver` **不可用** |
| 启动 | `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)` |
| 停止 | `unbindUserService` —— ⚠️ **进程不会自动被杀**，要自己实现 `destroy` |
| `destroy` 事务码 | **`16777115`**（aidl 里写 **`16777114`**） |
| 类要求 | 必须 implement `IBinder`（通常 extends `IYouAidlInterface.Stub`） |
| 构造器 | v13 起可用带 `Context` 的构造器（**优先尝试**）；更早版本只能默认构造器 |
| `tag` / `version` | 用 `tag` 判断"是不是同一个 UserService"（**不设则用类名，R8 后不稳定**）；<br/>`version` 不匹配会启新实例并 destroy 旧的 |

### 非 SDK 接口

- **Remote binder call** 在 app 进程内执行 ⇒ 需要 [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)
- 官方也有 [HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin)

---

## 本机实测（2026-09-12，坚果 Pro 3 / A10 / Shizuku 13.6.0.r1086）

| 查了什么 | 结果 |
|---|---|
| `/data/local/tmp` 下的权限文件 | ❌ 没有（只有我们自己的日志与截图） |
| `settings list global/secure/system \| grep -i shizuku` | ❌ 没有 |
| `ls /data/data/moe.shizuku.privileged.api/` | ❌ **`Permission denied`** ← 与官方文档第 2 条一致 |
| `/proc/<shizuku_server pid>/cwd` | `-> /`；fd 全是框架 jar（无状态文件） |

⇒ **实测与官方文档完全吻合**：授权库在 Shizuku 应用私有 data，**shell 身份读写皆不可**

---

## 来源

| URL | 主题 | 抓取日期 | 关键信息 |
|---|---|---|---|
| `github.com/RikkaApps/Shizuku/blob/master/README.md` | Shizuku 总览 / 开发者指南入口 | 2026-09-12 | 工作原理（middle man + binder 转发）；**开发指南指向 Shizuku-API 仓库**；ADB 权限有限 |
| `raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md` | ★ **真正的开发指南** | 2026-09-12 | ★ **权限模型**（只为自己请求）／**ADB vs ROOT 权限差异**／**UserService 全部细节**／非 SDK 接口绕法 |
| `cs.android.com/…/packages/Shell/AndroidManifest.xml` | Shell(ADB) 实际持有的权限清单 | — | （官方指向，**未抓取**；需要时再取） |
| `github.com/LSPosed/AndroidHiddenApiBypass` ／<br/>`github.com/RikkaApps/HiddenApiRefinePlugin` | 非 SDK 接口 | — | （官方指向，**未抓取**） |
| `shizuku.rikka.app/guide/dev/` | ❌ **404**（路径不对） | 2026-09-12 | 文档站只有 `/`；开发指南在 GitHub 上 |

## ⚠️ 顺带更正一条过时记录

工作区此前记着「`raw.githubusercontent.com` **不可达**」——
**2026-09-12 实测 HTTP 200 可达**（`curl` 直连，无代理）。该记录已过时。
