# 10 · 存储 TRIM 与 OP（预留空间）检查 —— 坚果 Pro 3

> **日期**：2026-09-13 ｜ **任务**：[AN](.paper 计划书) 的延伸
> **起因**：AN 实测发现「**应用冷启动是 IO 受限**」（抬 CPU 频率无用）⇒ 顺着查存储
> **数据性质**：**全部本机实测**

---

## 0 · 一句话

**TRIM 已启用、OP 非常充足、速度健康 —— 存储【不是】瓶颈，不需要动。**

---

## 1 · TRIM：**已启用（在线 discard 模式）**

```bash
$ mount | grep " /data "
/dev/block/dm-2 on /data type ext4 (rw,seclabel,nosuid,nodev,noatime,discard,noauto_da_alloc,...)
                                                            ^^^^^^^
```

★ **`discard` 挂载选项 = 【在线 TRIM】** —— 每次删除块时立即向 UFS 下发 discard 命令。

| 项 | 值 |
|---|---|
| 启动设备 | `1d84000.ufshc`（高通 UFS 控制器，来自 `ro.boot.bootdevice`） |
| `/data` 文件系统 | ext4（**文件级加密已开**：`File-based Encryption: true`） |
| `fstrim` 二进制 | ❌ `/system/bin` 与 `/vendor/bin` 都没有 |
| `sm idle-maint` | ❌ 本 ROM 的 `sm` **没有**这个子命令 |

> ### ⚠️ 一个值得知道的细节：**在线 discard ≠ 最优**
>
> 现代 Android 主流做法是**定期 fstrim**（由 `vold` 在空闲维护窗口跑），
> 而**不是**挂载 `discard` —— 因为**在线 discard 会给每次删除都加一点延迟**
> （尤其对随机小写入不友好）。
>
> ★ **但本机实测没有因此出问题**（见 §3：`Latency: 0ms`）——
> 且该选项由 ROM 的 `fstab` 决定，**无 root 改不了** ⇒ **不需要动。**

---

## 2 · OP（预留空间）：**非常充足**

### 2.1 手机 UFS 的"OP" = 空闲空间

> ★ **手机不像 PC SSD 那样有厂商工具去单独配置 OP** ——
> 控制器的 OP 是**出厂固定**的，用户侧**唯一的杠杆就是保持足够的空闲空间**
>（控制器用空闲块做 GC 与磨损均衡）。

| 指标 | 值 | 评价 |
|---|---|---|
| `Data-Free` | **64,491,772 K / 112,270,972 K = 57%** | ★ **非常充足** |
| `Cache-Free` | 同上 57% | ✓ |
| `System-Free` | 16,924 K / 3,245,732 K = 0% | ⚠️ 正常（system 是只读满分区） |

**占用明细**（`dumpsys diskstats`）：
```
App Size        8.27 GB
App Data       13.42 GB
App Cache       3.56 GB      ← ★ 3.5 GB 是应用缓存（可清，但没必要，空间充足）
Photos          1.03 GB
Videos          0.30 GB
Audio           5.65 GB
System         13.03 GB
Other          11.95 GB
```

> ★ **57% 空闲 ⇒ OP 完全不是问题。** 一般建议保持 ≥10–15% 空闲即可。

---

## 3 · 速度与延迟：**健康**

| 测试 | 结果 | 备注 |
|---|---|---|
| **顺序写 1 GB**（`dd bs=1M`） | **248 MB/s** | ✓ 健康的 UFS 3.x 水平 |
| 顺序写 512 MB（第二次） | **387 MB/s** | ✓ |
| `dumpsys diskstats` 延迟 | ★ **`Latency: 0ms [512B Data Write]`** | ✓ 极好 |
| `Recent Disk Write Speed` | 7,599 kB/s | 近期平均，非峰值 |

### ⚠️ 两个**不能采信**的读数（诚实标注）

| 读数 | 为什么不算数 |
|---|---|
| 读回 **3.2 GB/s** | ★ **是页缓存**，不是设备读取 —— `drop_caches` 需要 root（被拒） |
| 4K 写 **1.0 GB/s** | 同上，缓存效应 |

> ★ 想拿真机读数要用 `O_DIRECT` 绕过页缓存，
> **但本机 toybox `dd` 不支持** `iflag=direct` / `oflag=direct`（实测报 `bad arg`）。

---

## 4 · ★★★★ 结论：**为什么存储不是冷启动慢的原因**

把 AN 的证据串起来：

| 观测 | 含义 |
|---|---|
| 冷启动 350–660 ms | 不快 |
| ★ **冷启动期间 CPU 只用 40–65%**（`800%cpu … 275–435%idle`） | **CPU 没打满** |
| ★ **同期 `iow` 出现 10–75%**（平时是 0） | **有 IO 等待** |
| ★ **但 `iow` 也没到 100%**，且设备延迟 0ms、顺序写 248 MB/s | **IO 也没饱和** |

> ### ⇒ **两边都没到极限 ⇒ 是"加载 + 初始化的【串行延迟】"**
> 即：进程创建 → zygote fork → 类加载 → 资源加载 → Activity 生命周期 → 首帧渲染，
> 这条链上每一步都要等上一步 —— **典型的"延迟受限"而非"吞吐受限"**。
>
> ★ 所以：**抬 CPU 频率没用**（AN 已实测）、**存储也没什么可调的**（本文）。

---

## 5 · ★ 如果将来还要动存储，能做什么

| # | 手段 | 可行性 |
|---|---|---|
| **1** | 触发一次 fstrim | ❌ 无 `fstrim` 二进制、无 `sm idle-maint`；`vold` 的入口未开放给 shell |
| **2** | 改掉在线 `discard`、改用定期 fstrim | ❌ 需要改 `fstab` ⇒ **root** |
| **3** | UFS 低功耗调优（`hibern8_on_idle_*`、`clkgate_*`、`rpm_lvl`、`spm_lvl`） | ❌ **全部 `-rw-r--r-- root root` + SELinux 拒绝**（和 CPU 那些节点同款） |
| **4** | 读 UFS 寿命/健康（`life_time_estimation_*`、`health_descr`） | ❌ 该内核（4.14）**没有这些节点**；`latency_hist` 也被拒 |
| **5** | ★ **保持空闲空间**（唯一用户侧杠杆） | ✅ **已经 57% 空闲，做得很好** |

---

## 6 · 附：一条对**以后**有用的方法论

> ★★ **别用带缓存的 `dd` 读数下结论。**
> 本机第一次测出的"读 3.2 GB/s、4K 写 1.0 GB/s"**全是页缓存的功劳**，
> 差点被当成"存储飞快"的证据。
>
> ★ 三个替代办法（本机都没走通，记下来免得重试）：
> 1. `echo 3 > /proc/sys/vm/drop_caches` —— **需要 root**
> 2. `dd iflag=direct` —— **toybox dd 不支持**
> 3. 读文件 **远大于 RAM** —— 7.3 GB 内存，不现实
>
> ⇒ 结论：**本机拿不到干净的真机读带宽**；
> 但 `dumpsys diskstats` 的 `Latency` 与顺序写速度**已足够判断"存储不慢"**。
