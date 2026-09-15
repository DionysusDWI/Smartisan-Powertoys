# 09 · 性能调度侦察：不 root 能对坚果 Pro 3 做什么优化

> **抓取/实测日期**：2026-09-13 ｜ **任务**：[计划书 AK](../../.paper/plans/AK-性能调度侦察.md)
> **数据性质**：**全部来自本机实测**（`adb shell` / `dumpsys` / 设备文件），非网络资料
> **原始 dump**：[`.ref/perf_probe/`](../perf_probe/)
> **★ 项目更名**：**Smartisan MOD 启动器 → Smartisan Powertoys**（一体化应用）

---

## 0 · 一句话

**`/sys` 那套经典调频旋钮(governor / freq / GPU / stune / cpuset / 温控)在本机【全部是 root-only】，
无 root 碰不到。真正还开着的两条路是:① Android 框架层旋钮 ② QTI perf HAL —— 而 ② 的"能否真生效"尚未定论。**

---

## 1 · 平台身份

| 项 | 值 |
|---|---|
| 机型 | 坚果 Pro 3（`DT1901A`） |
| 平台 | **`msmnile`** = **SM8150**（骁龙 855 系） |
| 内核 | **`4.14.117-perf+`**（编译于 2022-06-16） |
| Android | 10（SDK 29）／Smartisan OS 8.0.4 |
| CPU | **8 核 3 簇，全部在线（0-7）** |

---

## 2 · ★★ CPU 拓扑与频率表（**一切优化的坐标**）

| policy | CPUs | 频率档位范围 | `scaling_min` | 当前 governor | 可选 governor |
|---|---|---|---|---|---|
| **`policy0`** | 0-3（小核 Silver） | 300 MHz – **1.7856 GHz**（18 档） | ★ **576 MHz**（≠ 硬件最低 300） | `schedutil` | conservative / ondemand / userspace / powersave / performance / **schedutil** |
| **`policy4`** | 4-6（大核 Gold） | 710 MHz – **2.4192 GHz**（17 档） | 710 MHz（= 硬件最低） | `schedutil` | 同上 |
| **`policy7`** | 7（超大核 Gold+） | 825 MHz – **2.9568 GHz**（21 档） | 825 MHz（= 硬件最低） | `schedutil` | 同上 |

**★ 三簇 `scaling_max_freq` 都等于 `cpuinfo_max_freq` ⇒ 当前没有任何频率上限压制。**

**`schedutil` 可调参数（三簇都有）**：
```
policy0: down_rate_limit_us=0  hispeed_freq=1209600  hispeed_load=90  pl=1  up_rate_limit_us=0
```

---

## 3 · ★★★★ 可达性扫描（**本任务最关键的一张表**）

> 测试身份：**`adb shell` = `uid=2000(shell)`，`gid=2000(shell)`，
> groups 里**没有 `system`(1000)**，context `u:r:shell:s0`**
> （★ Shizuku 给的就是这个身份 ⇒ **下表对 Shizuku 同样成立**）

### 3.1 ❌ **写不了（root-only）—— 经典调优旋钮全灭**

| 目标 | 权限 | 结果 |
|---|---|---|
| `.../policy0/scaling_governor` | `-rw-r--r-- root root` | ❌ |
| `.../policy0/scaling_max_freq` | **`-rw-rw-r-- system system`** | ❌ **shell 不在 `system` 组** |
| `.../policy0/scaling_min_freq` | `-rw-rw-r-- system system` | ❌ 同上 |
| `.../policy0/schedutil/up_rate_limit_us` | `-rw-r--r-- root root` | ❌ |
| `/sys/devices/system/cpu/cpu4/online`（**CPU 热插拔**） | `-rw-r--r-- root root` | ❌ |
| `/dev/stune/*/schedtune.boost`（**EAS boost**） | `-rw-r--r-- root root` | ❌ |
| `/dev/cpuset/*/cpus`（**任务分组**） | `-rw-r--r-- root root` | ❌ |
| `/sys/class/thermal/cooling_device*/cur_state` | `-rw-r--r-- root root` | ❌ |
| `/sys/class/kgsl/kgsl-3d0/*`（**GPU**） | — | ❌ **SELinux `LS-DEN`，整块目录列不出来** |
| `/sys/class/devfreq` | — | ❌ **SELinux `LS-DEN`** |
| `/sys/block/sda/queue/scheduler`（**IO 调度器**） | — | ❌ **SELinux `CAT-DEN`** |
| `/sys/module/msm_thermal/parameters`、`/sys/module/lowmemorykiller/parameters`、`/sys/kernel/msm_performance` | — | ❌ **`ABSENT`（SELinux 让 stat 都失败）** |

> ★★★ **⇒ 「`echo performance > scaling_governor`」这条最经典的免 root 调频路，在本机【是关的】。**
> ⚠️ 网上大量"免 root 调频"教程默认你有 `system` 组或某个可写节点 —— **本机没有**。

### 3.2 ✅ **能读（只读侦察可用）**

`/sys/devices/system/cpu/**`（在线、频率、档位、驻留）｜
`/sys/class/thermal/**`（50 个 zone + 26 个 cooling device 全可读）｜
`/proc/**`（★ **shell 能读 `/proc`**，app 不能）｜
`/vendor/etc/perf/*.xml`（★ **perf HAL 的配置，可读！**）

---

## 4 · 温控图景（**50 个 zone + 26 个 cooling device**）

### 4.1 thermal_zone 分类（`thermal_zone0..49`）

| 类别 | 例子 | 说明 |
|---|---|---|
| `*-usr` | `cpu-0-0-usr` … `cpu-1-7-usr`、`gpuss-0/1-usr`、`ddr-usr`、`cwlan-usr`、`video-usr`、`npu-usr`、`camera-usr` | **真实温度**（0.001 °C） |
| `*-lowf` | `cpu-1-7-lowf`、`gpuss-0-lowf`、`camera-lowf` | 低频场景阈值 |
| `*-step` / `*-max-step` | `cpu-0-0-step` … `apc-0-max-step`、`pop-mem-step`、`gpuss-max-step`、`npu-step` | ★ **温控降档档位** |
| `lmh-dcvs-00/01` | 恒 **75000** | ⚠️ **LMh 硬件限值，不是传感器**（[T §6.2](../../.paper/plans/T-手机侧性能窗口mod.md) 已记） |

**当前温度**：全机 **35–40 °C**（空闲、凉爽）

### 4.2 cooling_device（`thermal-cpufreq-N` 每核一个）

```
cooling_device2..5   thermal-cpufreq-0..3   cur=0  max=18     ← 小核
cooling_device6,8..10 thermal-cpufreq-4..6  cur=0  max=17     ← 大核
cooling_device11     thermal-cpufreq-7      cur=0  max=21     ← 超大核
cooling_device0      thermal-devfreq-0      cur=0  max=5      ← GPU
cooling_device15     panel0-backlight       cur=0  max=255    ← ★ 温控能调屏幕亮度
cooling_device14     battery / modem* / cdsp / hvx / ...
```

**★ `cur=0` 全部 ⇒ 当前没有任何温控降档在生效。**

### 4.3 Android 侧温控

```
cmd thermalservice override-status STATUS   ← ★ 存在！可"锁定"给 app 看的温控状态
cmd thermalservice reset
dumpsys thermalservice → Thermal Status: 0 ｜ ThermalHAL 2.0 connected: yes
                        ★ 但 "Current temperatures from HAL:" 是【空的】
```

> ★★ **Android 的 ThermalService 拿不到温度数据**（HAL 没报）⇒ **它没法基于温度做降档**。
> ⇒ 真正的温控在 **`thermal-engine`（root，pid 1071）** 与内核里 —— **我们碰不到**。

---

## 5 · ★★★★★ QTI perf HAL —— 唯一像"官方性能通道"的东西

### 5.1 现场

| 项 | 值 |
|---|---|
| binder 服务 | ★ **`vendor.perfservice: [com.qualcomm.qti.IPerfManager]`** |
| 进程 | `vendor.qti.hardware.perf@2.0-service`（root, pid 833）＋ `perfservice`（**system**, pid 1098） |
| 库 | `/vendor/lib64/libqti-perfd-client.so`、`/vendor/lib64/libqti-perfd.so` |
| 配置 | `/vendor/etc/perf/{perfboostsconfig,perfconfigstore,targetconfig,commonresourceconfigs,targetresourceconfigs}.xml` ★ **都可读** |
| 框架类 | ★ **`android.util.BoostFramework` 在 `framework.jar` 里存在**（含 `perfHint` / `perfLockAcquire`） |

### 5.2 ★★★★ 从 ROM 自带的「性能模式」APK 里挖出的**完整机制**

`/system/product/app/PerformanceMode/PerformanceMode.apk`（**仅 25 KB**）反编译：
[`.ref/perf_probe/smali_pm/.../PerformanceModeManager.smali`](../perf_probe/smali_pm/com/qualcomm/qti/performancemode/api/PerformanceModeManager.smali)

```java
PROP_PERFORMANCE_MODE_SUPPORT = "vendor.perf.performancemode.support"   // 默认 "0"

int turnOnPerformanceMode() {                       // 开启
    return mBoostFramework.perfHint(0x1091, "com.qualcomm.qti.performancemode",
                                    0x7fffffff, -1);
}
int turnOffPerformanceMode(int handle) {            // 关闭
    return mBoostFramework.perfLockReleaseHandler(handle);
}
boolean isPerformanceModeSupport() {                // 门控
    String s = mBoostFramework.perfGetProp("vendor.perf.performancemode.support", "0");
    return "1".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
}
```

**★★ 门控状态：`getprop vendor.perf.performancemode.support` 是【空的】**
⇒ `perfGetProp` 返回默认 `"0"` ⇒ ★ **本 ROM 的「性能模式」是【关闭的】**；
APK 是空壳（`PerformanceModeActivity` 启动报 `does not exist`）。

### 5.3 ★★★★ boost 配置解出来了（`perfboostsconfig.xml`）

**资源 opcode 含义**（从配置自证）：

| opcode | 含义 |
|---|---|
| `0x40800000 / 0x40800100 / 0x40800200` | **CPUBOOST_MAX_FREQ** 小 / 大 / 超大核（值 = **MHz**） |
| `0x40804000 / 0x40804100 / 0x40804200` | **CPUBOOST_MIN_FREQ** 小 / 大 / 超大核 |
| `0x43000000` | **SCHEDBOOST** |
| `0x41800000` | **CPUBW_MIN_FREQ**（内存带宽） |
| `0x43400000` | **LLCCBW**（末级缓存带宽） |
| `0x40C00000` | **POWER COLLAPSE** |
| `0x4281C000` / `0x40400000` / `0x42C10000` / `0x41448000` | 其它资源（未逐个确证） |

**★ 关键条目**：
```
Id=0x1081 Type=1 Timeout=2000:
  0x40800000=0xFFF  0x40800100=0xFFF  0x40800200=0xFFF     ← 三簇 MAX 全给 4095(最大哨兵)
  0x43000000=0xFF   SCHEDBOOST 满
Id=0x1080 Type=1:  SCHEDBOOST=0xFF, LITTLE=1209, BIG=1171, PRIME=1171
Id=0x1081 Type=3 Timeout=15000 / Type=8 / Type=104 ...     ← 分场景
```
**配置里定义的 Id**：`0x1040, 0x1041, 0x1080, 0x1081, 0x1083, 0x1086, 0x1087, 0x1088, 0x1089, 0x1090`
⚠️ **`0x1091`（性能模式用的那个）不在配置里** —— 它是 perfd **内置**的 hint。

### 5.4 ⚠️ `perfconfigstore.xml` 是**属性覆盖表**，不是白名单

它让 `perfGetProp` 返回一批**性能相关属性的值**（不是调用者白名单）：
```
vendor.iop.enable_uxe=1        vendor.perf.iop_v3.enable=true
vendor.debug.enable.lm=true    vendor.enable.prefetch=false
vendor.perf.gestureflingboost.enable=true
ro.vendor.qti.sys.fw.bg_apps_limit=60      ← ★ 后台应用上限
ro.lmk.enable_userspace_lmk=false
```

## 5.5 ⚠️ ~~实测：`service call` 能进 HAL，但 boost 不生效~~ ⇒ ★★★★ **此结论已作废（事务码猜错了）**

> ## ⚠️⚠️ **勘误（同日修正，原判定保留以示警戒）**
>
> 下面这一节当时的结论是"`service call` 不生效"。**错在哪**：我把 AIDL 的**事务码猜错了**
> —— 我按"声明顺序从 1 开始"猜 `1=perfHint / 2=perfLockAcquire / 3=perfLockRelease`，
> **但真实值是从 `IPerfManager$Stub` 里读出来的**（见 §5.7）：
>
> | 我猜的 | **真实** |
> |---|---|
> | code 1 = perfHint | **code 1 = `perfLockRelease`** |
> | code 2 = perfLockAcquire | **code 2 = `perfLockReleaseHandler`** |
> | code 3 = perfLockRelease | **code 3 = `perfHint`** |
>
> ⇒ ★ **那三轮 `service call` 全部打偏**（调的是"释放"和"用垃圾参数调 hint"），
> **"不生效"是必然的** ⇒ **结论作废**。
>
> ★★ **教训**：**AIDL 事务码不能猜，必须从 `Stub` 里读。**
> 而且当时的"返回值恒 0"其实是**打偏后返回的垃圾**，不是真正的 hint 响应。

### （原记录，保留供追溯）

```bash
service call vendor.perfservice 99            → Error "Not a data message"   ← 服务可达
service call vendor.perfservice 1 i32 4225 …  → Parcel(00000000)
service call vendor.perfservice 3             → Parcel(00000000 ffffffff)
```
受控 A/B：空闲 10s 的 `time_in_state` 驻留分布在"加锁/不加锁"下完全一致。

---

## 5.6 ★★★★★ 真正的答案：**手搓 binder 事务 —— app 身份下【完全可行】**

> **2026-09-13 同日攻克。** 这是任务 AK 最重要的一条结论。

### 5.6.1 走到这一步的**完整排除链**（每一步都是实测）

| # | 试法 | 结果 |
|---|---|---|
| 1 | `echo performance > scaling_governor`（shell） | ❌ 权限就不允许（`root` / `system` 所有，shell 不在 `system` 组） |
| 2 | `service call vendor.perfservice …` | ❌ **事务码猜错**（见 §5.5 勘误） |
| 3 | `app_process`（**shell 身份**）反射 `android.util.BoostFramework` | ⚠️ 类**能实例化**、`perfHint` 返回 **-1**（无包名 ⇒ perfd 拒） |
| 4 | **app**（`targetSdk=27`）反射 `BoostFramework` | ❌ ★ **`declaredConstructors` = 0 个、`declaredMethods` = 0 个** ⇒ **整个类被隐藏 API 清空** |
| 5 | `VMRuntime.setHiddenApiExemptions(["L"])` | ❌ 也被拦（Android 10 已加固） |
| 6 | ★ **`System.loadLibrary("qti-perfd-client")`** | ✅ **成功**（该库在 `/vendor/etc/public.libraries.txt` 里） |
| 7 | ★★★★★ **手搓 binder 事务** | ✅✅✅ **成功，且实测有效** |

### 5.6.2 ★★★★ 协议（**从 ROM 里读出来的，不是猜的**）

来源：`/system/framework/QPerformance.jar` → 反编译 `IPerfManager$Stub$Proxy`
（[`.ref/perf_probe/smali_qperf/`](../perf_probe/smali_qperf/)）

```
服务名        : vendor.perfservice
接口描述符    : com.qualcomm.qti.IPerfManager

TRANSACTION_perfLockRelease        = 1   ← token
TRANSACTION_perfLockReleaseHandler = 2   ← token, int(handle)
TRANSACTION_perfHint               = 3   ← token, int(hint), String(userStr), int(d1), int(d2), int(tid)
TRANSACTION_perfLockAcquire        = 4   ← token, int(duration), intArray(list)
TRANSACTION_perfUXEngine_events    = 5
TRANSACTION_setClientBinder        = 6
TRANSACTION_perfGetProp            = 7
TRANSACTION_perfPerformanceMode    = 8   ← token, int(0/1)   ★ 返回 void ⇒ oneway
```

> ★★ **`perfHint` 的第 5 个参数是 `tid`** —— `Performance.smali` 里传的是 **`Process.myTid()`**。

### 5.6.3 ★★★★★ 为什么"手搓"能绕过隐藏 API

| 用到的 | 是否公开 API |
|---|---|
| `Parcel.obtain()` / `writeInterfaceToken` / `writeInt` / `writeString` / `writeIntArray` | ✅ **公开** |
| `IBinder.transact(int, Parcel, Parcel, int)` / `IBinder.FLAG_ONEWAY` | ✅ **公开** |
| `Process.myTid()` | ✅ **公开** |
| ⚠️ `android.os.ServiceManager.getService(String)` | ⚠️ **隐藏类 ⇒ 全程只有这一处要反射**（实测**放行**） |

⇒ ★ **不需要 root、不需要 Shizuku、不需要 NDK、不需要隐藏 API 豁免。**

### 5.6.4 ★★★★★ 实测结果（**完整闭环**）

探针：[`projects/mode-launcher/src/perf-probe/`](../../projects/mode-launcher/src/perf-probe/)（`targetSdk=27`，包名 `com.shware.perfprobe`）

```
① ServiceManager.getService("vendor.perfservice") ✓
✓ 拿到 binder: com.qualcomm.qti.IPerfManager

② perfHint(0x1091, "com.qualcomm.qti.performancemode", 30000) → -1    ← ✗ 被拒
③ perfHint(0x1081, …)                                        → -1    ← ✗ 被拒
④ ★★ perfLockAcquire(30000, 三簇钉最高频)                     → 17222 ← ★★★ 成功！
⑤ perfPerformanceMode(true) 已发出（oneway）
```

**★ 关键：`perfLockAcquire` 不查包名白名单，`perfHint` 查。**（这也解释了为什么 `perfHint` 一路 -1）

#### 效果 ①：**频率下限被真的抬起来**（空闲、无负载）

> ⚠️ 下表的"修正前"数据是**用错映射**测的（大核只被请求 1785）；
> 修正后见每行的 ★ 列。

| 簇 | 空闲基线 | ~~修正前~~（错映射） | ★ **修正后** |
|---|---|---|---|
| `policy0` 小核 | 1785600（本来就顶格） | 1785600 | 1785600 |
| ★ `policy4` 大核 | **710400** | ~~1804800~~ | ★★ **2419200**（= `cpuinfo_max`） |
| ★ `policy7` 超大核 | **825600** | 2956800 | 2956800 |

连续 20 次采样**完全稳定、零回落**。

> ⚠️ **曾经的误解**：「请求 2419 只生效 1804800 ⇒ perfd 内部有档位映射」
> —— **错**。真相是 **opcode 顺序读反了**，大核只被请求了 1785。见 §5.6.5 的勘误。

#### 效果 ②：★★★ **量化收益（突发负载基准）**

> 为什么用"突发"而不是"持续满载"：**持续满载时 `schedutil` 本来就把频率顶满**
> ⇒ 实测"加不加锁的最高频完全一样"，**那种测法分辨不出差别**。
> 而手机上的卡顿大多是**一簇一簇的短突发**（点一下、滑一下、开界面）——
> 没有下限时每个突发都要从最低频爬上去。

```
60 轮 × (300 万次整数运算 + 停 40ms)，只统计纯计算耗时：
   ~~修正前（错映射）~~ 无锁 576ms → 有锁 465ms   ⇒ +19.3%
   ★★ 修正后          无锁 577ms → 有锁 299ms   ⇒ ★★★ +48.2%
```

#### 效果 ③：★ **完全可逆**

```
锁 30s 自然过期后：  p4 1804800 → 710400 ／ p7 2956800 → 825600   ✅ 完全回落
显式释放：          perfLockReleaseHandler(17238) → 0            ✅ 释放成功
```

### 5.6.5 ★★ 资源 opcode 表（`perfLockAcquire` 的 `list` 参数）

> ## ⚠️⚠️⚠️ **重大勘误（2026-09-13 同日修正）—— 本表曾经【顺序读反】**
>
> 原表写的是「`0x40800000/0100/0200` = **小 / 大 / 超大**核」—— **错**。
> `perfboostsconfig.xml` 的注释顺序本来就是 **BIG / LITTLE / PRIME**，
> 但最初按"小/大/超大"去套 ⇒ ★★ **大核一直在被请求 1785 而不是 2419**。
>
> **症状**：「请求大核 2419，实际只生效 **1804800**」——
> 当时被误判为"perfd 内部有档位映射"。**真相是请求值 1785 映射到了大核的 1804800 档。**
>
> **修正后收益变化**：突发负载提速从 **+19.3% → +48.2%**（见 §5.6.4）。

#### ★ 已确证的部分（多次独立实测）

| opcode | ★ 实测确认含义 | 证据 |
|---|---|---|
| **`0x40800000`** | ★★★ **大核 (Gold, `policy4` cpu4-6) 的 CPUBOOST_MAX_FREQ** | ① 单发 1785 → `p4=1804800`<br/>② 单发 1500 → `p4=1612800`<br/>③ 单发 1000 → `p4=1056000`<br/>④ ★ **反向**：单发 **900** → `p4` 被**压到 940800`（封顶测试） |
| **`0x40800200`** | ★★ **超大核 (Prime, `policy7` cpu7) 的 CPUBOOST_MAX_FREQ** | 扫描 825→825600 … 2956→2956800，**完美单调** |

#### ★★ 值语义：**向上取整到该簇最近的可用档位**

```
大核  请求 1000 → 1056000     请求 1500 → 1612800
      请求 1785 → 1804800     请求 2419 → 2419200（= cpuinfo_max）
      请求  900 →  940800（封顶方向同理）
```

#### ⚠️ **仍未判明**（**不写成结论**）

| opcode | 观测 | 状态 |
|---|---|---|
| `0x40800100` | 单发 **2419 / 900** 都**三簇全无变化** | ❓ **本机无观测效果**（可能是小核 MAX，但小核在该 ROM 上不响应此资源） |
| `0x40804000` | 单发 1400 → `p0=1382400` | ❓ **判不出来** —— 小核 `scaling_cur_freq` **自然波动就有这么大**（实测见过 1036800/1209600/1305600/1708800/1785600） |
| `0x40804100` / `0x40804200` | 单发 1400 **无变化** | ❓ 未判明 |
| `0x43000000` / `0x41800000` / `0x43400000` | ★ **已测：无效**（见下） | ✅ 见 §5.6.6 |

#### ★★ 判别方法（**下次别猜，用这个**）

> ★ **不要靠"注释顺序"或"常识顺序"推断 opcode 归属。**
> 用两个正交实验：

| 实验 | 做法 | 能判出什么 |
|---|---|---|
| **① 抬升测试** | 单发一个 opcode ＋ 一个**中间值**，看**哪一簇动了** | 该 opcode 管哪一簇 |
| **② ★ 封顶测试** | 单发 `MAX_FREQ` ＋ 一个**低于该簇当前频率的值**，看哪一簇**被压下去** | ★ **比抬升测试更硬** —— 不受"该簇本来就在高频"干扰 |

★ 本任务的教训：抬升测试对**小核**无效（它本来就常在高档），
**必须用封顶测试才能把它认出来**。

---

### 5.6.6 ★★★★★ 其它 opcode 探索（任务 AK6）

> **方法**：**逐个叠加** opcode，每种组合跑**同一个突发基准**
> （60 轮 × 300 万次整数运算 ＋ 停 40 ms，只统计纯计算耗时）
> **工具**：[`OpcodeMatrixActivity.kt`](../../projects/mode-launcher/src/perf-probe/src/main/java/com/shware/perfprobe/OpcodeMatrixActivity.kt)

#### 矩阵结果

| # | 组合 | pass2 | 相对无锁 |
|---|---|---|---|
| ① | 无锁（基线） | 1081 ms | — |
| ② | 三簇 MAX_FREQ | 800 ms | +26.0% |
| ③ | ＋`SCHEDBOOST 0xFF` | 778 ms | +28.0% |
| ④ | ＋`CPUBW_MIN_FREQ 0xFF` | 779 ms | +27.9% |
| ⑤ | ＋`LLCCBW 0xFFFF` | 788 ms | +27.1% |
| **⑥** | **＋`POWER COLLAPSE = 1`** | ★★ **262 ms** | ★★★ **+75.8%** |

#### ★★★★★ 复现验证（交替 3 轮）

```
② 三簇 MAX_FREQ      均值 789 ms   各轮 784 / 803 / 781
⑥ ＋POWER COLLAPSE 1  均值 269 ms   各轮 275 / 271 / 263   ← ★★ 极稳
⑦ ＋POWER COLLAPSE 0  均值 777 ms   各轮 777 / 779 / 777   ← ★ 对照组
```

**三点确认：**
1. ★★★ **`0x40C00000 = 1` 完全可复现**（三轮 ~270 ms）
2. ★★ **值语义确认**：`= 0`（777）与"不加该项"（789）**无差别** ⇒ 值确实有含义
3. **`SCHEDBOOST` / `CPUBW` / `LLCCBW` 实测【无效】**（全在噪声内）

#### ★★ `0x40C00000`（POWER COLLAPSE）是什么

**禁止 CPU 电源关断** ⇒ 核在空闲时也保持上电 ⇒
**突发任务不用等"唤醒"** ⇒ 这解释了为什么它比单纯提频有效得多（+26% → +75%）。

> ### ⚠️ 代价：**空闲时必然更费电**（放弃了电源关断）。**已量化 ⇒ 见 §5.6.7**

#### ★★ 顺带：**app 身份能不能读 sysfs**（shell 被挡的那些）

| 路径 | app 身份结果 |
|---|---|
| `/sys/class/devfreq`（**目录列举**） | ❌ `LS-DEN` |
| `/sys/class/kgsl/kgsl-3d0`（**目录列举**） | ❌ `LS-DEN` |
| ★ `/sys/class/kgsl/kgsl-3d0/gpuclk`（**单文件**） | ✅ **`CAT-OK = 257000000`**（GPU 空闲 257 MHz） |
| `/sys/class/thermal/thermal_zone0/temp` | ✅ `CAT-OK` |

> ★★ **SELinux 挡的是 `readdir`，不是 `read`** ——
> **只要知道确切路径，单文件就能读**。

---

### 5.6.7 ★★★★★ 档位功耗对照 —— 最后的未知数（任务 AK7）

> **方法**：**交替 2 轮**（无锁 / 均衡 / 强力 × 2），各 60 s，**空闲无负载**，屏幕常亮固定
> **前提**：★ 手机必须在**放电**（`AC powered: false`）

#### 结果

| 档 | 第 1 轮 | 第 2 轮 | **均值** | 相对无锁 | ★ 谷值 |
|---|---|---|---|---|---|
| ① 无锁 | 179.9 mA | 172.6 mA | **176.2 mA** | — | 119 / 113 |
| ② 均衡 | 163.3 mA | 182.6 mA | **173.0 mA** | ★ **−1.8%** | 121 / 125 |
| ③ **强力** | 241.9 mA | 230.5 mA | ★★ **236.2 mA** | ⚠️ **+60.0 mA（+34.0%）≈ +231 mW** | ★ **200 / 192** |

**三点解读：**
1. ★ **均衡档 = 代价 ≈ 0**（组内差 20 mA，组间差仅 3.2 mA）
2. ★★ **强力档代价高度可复现**（两轮差 11 mA，组间差 **60 mA**）
3. ★★ **谷值佐证**：强力档谷值 **192–200 mA** vs 其余 113–125 mA
   —— **CPU 再也降不到低功耗**，这就是那 60 mA 的物理来源

#### ★★ 算账

```
+60 mA × 15 分钟 = 15 mAh ÷ 4000 mAh ≈ 0.4% 电量
```
⇒ ★ **短时开强力档非常划算**；⚠️ **长时间挂着不划算**（1 h ≈ 1.5%，8 h ≈ 12%）

#### ★★★★★ 两档最终账（全部实测）

| 档 | 突发负载收益 | 空闲功耗代价 | 15 分钟的账 |
|---|---|---|---|
| **均衡**（★ 默认） | +26%（1081 → 789 ms） | ★ **≈ 0** | 约 0 |
| **强力** | ★★ **+75%（1081 → 269 ms）** | ⚠️ **+60 mA / +34%** | ★ 约 0.4% 电量 |

---


---

## 5.7 ★★★★★ 性能模式的【功耗/发热代价】（任务 AK4，实测）

> **工具**：[`PowerBenchActivity.kt`](../../projects/mode-launcher/src/perf-probe/src/main/java/com/shware/perfprobe/PowerBenchActivity.kt)
> **前提**：★ **手机必须在放电**（插着 TNT GO 时它在给手机充电，且电量高时**充电控制器在调节** ⇒ 测不出消耗差异）

### 5.7.1 ★★★ 数据源排查（**这是本节最值钱的部分**）

| # | 数据源 | 结果 |
|---|---|---|
| 1 | `/sys/class/power_supply/{battery,bms,usb,pc_port,main,dc}/…` | ❌ **SELinux 对 shell 全禁止** |
| 2 | `dumpsys battery` 的 `Charge counter`（µAh） | ❌ 充电时会动，**放电时【完全冻住】**（150 s 恒定） |
| 3 | `dumpsys batterystats` 的 `Discharge:`（mAh） | ⚠️ 能动，但**跳变粒度 5–20 mAh** ⇒ 短窗口测到的是量化噪声<br/>（曾据此得出"加锁比不加锁省电 60%"的**荒谬结论**） |
| 4 | ★★ **`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`** | ✅✅ **实测给真值**：`-1001464 / -500488 / -344726` µA（负 = 放电）<br/>★ **公开 API，零权限**（而 sysfs 连 shell 都被挡） |
| 5 | `BATTERY_PROPERTY_CURRENT_AVERAGE` / `ENERGY_COUNTER` | ❌ 本 ROM **不支持** |

### 5.7.2 ★★★★★ 结果

**① 空闲 A/B（交替四相位，各 60 s）**
```
off:  221 / 184 mA  ⇒ 平均 202 mA
on :  219 / 172 mA  ⇒ 平均 195 mA
★★ 空闲代价 = −7 mA（−3.4%） ≈ −27 mW
   组内差：off 37 mA ／ on 47 mA   ← ★★ 比组间差大 5～7 倍
```
> ### ★★★ **空闲代价【测不出来】≈ 0** —— 噪声地板（±40 mA）远大于效应（7 mA）

**② 定量工作（每线程固定 60 亿次运算，8 线程）**

| | 耗时 | 耗电 |
|---|---|---|
| 无锁 #1 / #2 | 25.2 s ／ 17.4 s | 7.74 ／ 5.31 mAh |
| 有锁 #1 / #2 | 17.1 s ／ 17.8 s | 5.26 ／ 4.92 mAh |
| 含首轮（被 JIT 预热拉偏） | −18.2% | −22.1% |
| ★ **只比第二轮**（剔除预热） | **+2.1%** | **−7.4%** |

> ### ★★ **持续满载下代价也 ≈ 0**

### 5.7.3 ★★★★★ 综合结论

| 场景 | 结果 |
|---|---|
| **空闲** | ★ **代价 ≈ 0**（−7 mA，被 ±40 mA 噪声淹没） |
| **持续满载** | ★ **代价 ≈ 0**（耗时 +2.1%／耗电 −7.4%，噪声内） |
| **突发负载** | ★★ **收益 +19.3% 更快**（§5.6.4 实测） |

**★ 物理解释**：现代 SoC **空闲时核会被时钟门控 / 电源关断**，提高"设定频率"**不等于多耗电**；
满载时反正都在最高频。⇒ **真正受益的是突发/延迟敏感场景**，而那正是日常卡顿的来源。

**⇒ 产品含义**：**"更费电"这个顾虑基本不成立。**
默认 15 分钟／65 °C 可以放宽，**但温度闸门要保留**（满载实测电池 +1.0～2.2 °C）。

**⚠️ 不确定性（诚实标注）**
- 电流噪声地板 **±40 mA**；**小于此量级的差异测不出来**
- 只做了 **2 次重复**，系统本身有漂移（两次基线差 8 mA）
- 屏幕常亮是最大一路固定负载；★ **没测"熄屏"场景**（熄屏后 CPU 会 suspend，锁的行为可能不同）

---

## 6 · Smartisan 自己的性能相关设施

### 6.1 `settings list global` 里的相关键

```
★ game_mode_enable=1                       ← 游戏模式总开关
★ game_not_accelerate_packages=null        ← ★★「不加速的包」⇒ 说明存在一个【加速】机制
   game_mode_no_disturb_enable=1
   game_mode_shortcut_key_forbidden=1
   game_mode_lock_auto_brightness_enable=0
   game_mode_nav_bar_btn_forbidden=0
   game_sidebar_menu=1
   game_float_app=com.android.browser:0
   gamestore_show_recommend_apps=true
★ sys_uidcpupower=（空）                    ← ★★ 名字像"按 uid 的 CPU 功耗设置"
   low_power=0  low_power_sticky=0
★ animator_duration_scale=1.0  transition_animation_scale=1.0  window_animation_scale=1.0
   （★ 可调 ⇒ 降动画 = 立刻可感的"变快"）
```
⚠️ **`fixed_performance_mode`**：`settings get global` 返回 **`null`**（未设置；A10 上框架是否读它未验）。

### 6.2 相关包

| 包 | 说明 |
|---|---|
| ★ **`com.smartisanos.gamespeedup`** | **锤子的游戏加速** —— ★★★ **已解剖，见 §6.4** |
| `com.smartisanos.gamestore` | 游戏商店 |
| ★ `com.qualcomm.qti.performancemode` | QTI 性能模式（**本机被门控关闭**，见 §5.2） |
| `com.qualcomm.qti.workloadclassifier` | QTI 工作负载分类器（给 app 分类做调度） |
| `com.qualcomm.qti.qtisystemservice` | QTI 系统服务 |

### 6.3 其它服务

| 服务 | 说明 |
|---|---|
| **`performance.adj`** | ★ 有独立进程 **`performanceadjustor`（root, pid 850）**；`dumpsys` 报 `Unknown error -1` |
| `poweradvisor` / `power` / `thermalservice` | 标准 AOSP |

---

### 6.4 ★★★★★ `com.smartisanos.gamespeedup` 解剖结论：**它是【网络加速】，不是 CPU 加速**

> **方法**：`adb pull` APK（1.27 MB，`/system/app/GameSpeedUp/`）→ 抽 dex 字符串（10799 条）+ 组件/权限分析
> **原始**：[`.ref/gamespeedup/`](../gamespeedup/)

#### 结论

| # | 证据 | 说明 |
|---|---|---|
| **1** | 服务 **`com.subao.gamemaster.GameMasterVpnService`**（`BIND_VPN_SERVICE`） | ★ 第三方 **GameMaster（速宝）** 网络加速 SDK，**基于 VPN** |
| **2** | 字符串 **`GameNetworkAccelerationSettings`** | ★ **名字直接就是"游戏网络加速设置"** |
| **3** | `key_game_server_ip` / `key_redirect_game_ip` / **`configs/redirect_game_ip`** | ★★ **重定向游戏服务器 IP**（导到加速节点） |
| **4** | `/api/app/v2/qos/`、`/v3/report/client/qos`、`E/api/v2/%s/scripts?...` | QoS 测量 ＋ **云端下发脚本** |
| **5** | `JNI-ProxyLoop`、`"CONNECT refused by proxy"`、`"Perform Qos Message"` | 本地代理转发 |
| **6** | ★★ **申请的权限【全是网络相关】**：<br/>`INTERNET` / `ACCESS_NETWORK_STATE` / `READ_PHONE_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_NETWORK_STATE` / `com.smartisan.permission.ACCOUNT_CENTER` | ★ **没有任何 CPU / 性能 / 系统权限** |
| **7** | `BuyGameAccelerationActivity` | ★ **是付费服务**（账号中心集成） |

#### CPU 相关的字符串只有【只读探测】

```
/sys/devices/system/cpu/         ← 读
/proc/cpuinfo                    ← 读
%s/cpu%d/cpufreq/cpuinfo_max_freq ← 读（只读！没有写 scaling_*）
"The CPU '%s' matched" / "not matched"   ·  [enable=%b, cpu=%d, model=%d]
```
⇒ 这些只是**机型/能力判定**（决定要不要给这台机器开加速），**不是调频**。

#### ★★★ 由此得到的三条结论

| # | 结论 |
|---|---|
| **1** | ★ **`game_not_accelerate_packages` 是"不做【网络】加速的包"** —— 与 CPU 无关（此前只是猜测） |
| **2** | ★★★ **Smartisan 自己【没有】任何 CPU 性能模式** —— `mod-perfmode` **填补的是一个真实空白** |
| **3** | ❌ **没有可复用的 CPU 通道** —— 它自己都不碰 CPU |

> ⚠️ **顺带排除一个干扰**：查的时候看到系统里跑着一个 VPN
> （`HttpProxy 127.0.0.1:7892`、绕过表含 `deepseek.com`、`EstablishingAppUid: 10540`），
> **那不是 gamespeedup**（它 uid 是 10068）—— 是用户自己的代理工具。
> ★ **查 VPN 时要核对 `EstablishingAppUid`，别看到 tun0 就认领。**

---

## 7 · ✅ 目前**确认可用**的无 root 杠杆（Android 框架层）

| # | 杠杆 | 状态 |
|---|---|---|
| **1** | **`cmd thermalservice override-status <n>` / `reset`** | ✅ 存在（**能改 app 看到的温控状态**；不改变内核实际降档） |
| **2** | **动画缩放三键**（`animator_duration_scale` 等） | ✅ **最安全、立刻可感** |
| **3** | **`cmd power set-mode 0/1`**（省电开关） | ✅ 存在（**反向**：关掉省电） |
| **4** | **`am kill-all` / `appops` / `deviceidle`**（后台治理） | ✅ 标准 shell 能力 |
| **5** | **`settings put global game_*`**（Smartisan 游戏模式键） | ✅ 可写（**是否有真实效果待验**） |
| **6** | **QTI perf HAL（`BoostFramework`）** | ❓ **可达但效果未定论**（§5.5，需常驻进程复验） |
| **7** | **读 `/proc` + `/sys`（shell/Shizuku）** | ✅ 可做**完整的性能监控**（app 做不到） |

---

## 8 · ★ 下一步（按价值排序）

| # | 事项 | 判据 |
|---|---|---|
| **1** | ★★★★ **用【常驻进程】调 `BoostFramework`** —— 用 `app_process` 起一个 shell 身份的常驻 JVM，反射调 `perfHint(0x1081,…)` / `perfLockAcquire`，**保持存活**，同时看 `scaling_cur_freq` 是否被抬起 | ★ **能抬起 = 找到正路**；抬不起 = QTI 这条路对非白名单调用者死透 |
| **2** | ★★★ **解剖 `com.smartisanos.gamespeedup`** —— 它"加速"时到底做了什么？（写什么 settings / 调什么服务） | 能复用它的通道就最省事 |
| **3** | ★★ **验 `settings put global game_*` 的真实效果**（`game_mode_enable` 等） | ★ **记原值 → 写 → 验下游 → 回滚** |
| **4** | ★★ **`animator_duration_scale` 等三键**的收益量化 | 立刻可感，风险为零 |
| **5** | ★ **`performanceadjustor` 是什么**（Smartisan/QTI 的调度调节器） | 可能藏着一条通道 |
| **6** | 网络资料：QTI perf HAL 的调用者校验规则、`BoostFramework` 的用法 | 归档到本文件 |

---

## 9 · ★★ 三条方法论（本阶段踩出来的）

| # | 教训 |
|---|---|
| **1** | ★★★ **返回值 0 ≠ 成功** —— `perfHint` 对**非法 hint 和乱写包名**同样返回 `0`。<br/>⇒ ★ **必须有【下游观测】**（`time_in_state` / `cur_freq`），否则就是自欺 |
| **2** | ★★ **"最高频"不是好判据** —— 满负载时基线本来就能到最高频 ⇒ 加不加锁**看起来一样**。<br/>⇒ 要测 **floor** 就得看**空闲**时的驻留分布 |
| **3** | ★★★ **短命进程会污染结论** —— `service call` 执行完就退出，若 HAL 有 binder 死亡通知，锁会被立刻释放<br/>⇒ ★ **"调不动"的结论必须在【常驻进程】下才成立** |

---
