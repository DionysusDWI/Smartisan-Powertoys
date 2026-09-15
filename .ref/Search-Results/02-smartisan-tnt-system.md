# 02 · Smartisan TNT 系统结构(revone)

> 归档日期:2026-09-10
> **本主题的一手资料主要来自真机实测与 smali 语料,而非网络搜索。**
> 完整分析见 [`docs/TNT复刻准备_主报告.md`](../../docs/TNT复刻准备_主报告.md) 与
> [`handover/TNT_Handover/01_总报告/TNT桌面运行机制_总报告.md`](../../handover/TNT_Handover/01_总报告/TNT桌面运行机制_总报告.md)。
> 本文件只归档**网络来源**与**结论索引**,避免重复检索。

---

## 1. 网络来源

| URL | 主题 | 抓取日期 | 关键信息 |
|---|---|---|---|
| `github.com/SmartisanTech/android` | 锤子官方开源 | 2026-09-10 | BigBang / OneStep / Smartisan SDK |
| `cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@android-10.0.0_r1/core/java/android/view/Display.java` | AOSP Display flags | 2026-09-10 | 确认 AOSP **无** `FLAG_OWN_CONTENT_ONLY`(仅 SUPPORTS_PROTECTED_BUFFERS / SECURE / PRIVATE / PRESENTATION / ROUND / CAN_SHOW_WITH_INSECURE_KEYGUARD / SHOULD_SHOW_SYSTEM_DECORATIONS / SCALING_DISABLED) |
| `zh.wikipedia.org/zh-mo/坚果手机TNT工作站` | TNT 历史 | 2026-09-10 | TNT 1.0(2018 R1 工作站)始末 |
| `tech.chinadaily.com.cn/a/201911/01/WS5dbbf188a31099ab995e95ed.html` | TNT 2.0 发布 | 2026-09-10 | 坚果 Pro 3 随 Smartisan OS 7.0 发布 TNT 2.0 |

---

## 2. 结论索引(来源为实测,详见主报告)

| 主题 | 结论 | 详见 |
|---|---|---|
| 定性 | TNT = 同一 `system_server` 上的「第二显示面 + 桌面式窗口管理」,代号 `revone`;136 个类全在 system_server | 主报告 §2.1 |
| 入口 | `com.android.server.TntFeatureFactoryImpl.getTntManagerService(Context, AMS, WMS, IMS, PMS, DMS) → ITntManager` | 主报告 §2.1 |
| 显示面 | TNT 桌面固定 `displayId=100000`(`smt.tnt.virtual.display`,`FLAG_MIRROR_PC`);**物理屏 id 不固定**(实测 1→3→15) | 主报告 §2.2 |
| 伪装机制 | `mOverrideDisplayInfo` 把 100000 的 DisplayInfo 替换为物理 HDMI 屏的信息 | 主报告 §2.2 |
| 桌面驻留 | 桌面 Activity 常驻 100000,ProcessRecord 带 `d100000 pc:true` | 主报告 §2.3 |
| 窗口模型 | freeform;**每任务独立 bounds**;**无 docked 分屏**(`DockedStackDivider` 恒 INVISIBLE) | 主报告 §2.8–2.9 |
| 策略表 | `/system/etc/revone_window_config.xml`,743 App | 主报告 §2.7 |
| 放置算法 | `getDefaultBounds → adjustBoundsForAvoidOverlap → keepBoundsInScreen` | 主报告 §2.8 |
| 吸附 | 半屏 1207px / 边距 4px / 四角 1/4 | 主报告 §2.9 |
| 权限 | `com.android.permission.PC_MANAGER_API`(signature)等 | 主报告 §2.5 |
| PC 模式开关 | `pc_mode_enable`(secure)、`global_pc_mode_settings`(global)、`revone_screen_off_timeout` | 主报告 §2.4 |
| 黑屏根因 | 线材(PD=0 + SOURCE_DEFAULT) | 主报告 §1.3 |

---

## 3. ⚠️ 已知勘误(重要,避免重复踩坑)

| 早前判断 | 实测修正 |
|---|---|
| `Display.FLAG_OWN_CONTENT_ONLY` 在 AOSP 中不存在 → 判为代码 bug | **真机上该 flag 确实存在**,是 **Smartisan 在自家 framework 新增的**。AOSP 公开 SDK 里没有,所以用公开 SDK 编译会失败。tntgo 工程改用 `FLAG_PRIVATE` 是权宜之计,**真机语义不同,需复核** |
| TNT 桌面 UI 可从 XML 布局复刻 | **TNT 桌面本体是 OpenGL 自绘**(`SMGLSurfaceView`),`large_screen.xml` 仅 7 行,**无布局可抄**;但 dock/最近任务/通知面板有 457 个 XML 布局可精确参考 |
| 物理屏 displayId 为 1 或 3 | 本次实测为 **15** → 再次印证**禁止硬编码** |

---

## 4. 待补充(研究中)

- 宿主 `services.jar` / `framework.jar` 相对 AOSP 的完整 diff
- `Tnt*Impl` 各方法的注入细节
- 窗口几何持久化的存储位置(`SmtPCWindowSettingsWriter` 的具体键)
- 社区有无 TNT 相关的公开分析文章
