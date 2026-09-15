/*
 * ★★★★★ Smartisan Powertoys 对外 API（任务 AP / AP7）。
 *
 * 给**别的工程**调用 —— 不需要共享 AAR、不需要知道 mod 契约，
 * 绑定这个 Service 就能列出功能、读写开关、查图层。
 *
 * ## ★★★ 为什么全部用 String（JSON）而不是 Parcelable
 *
 * | 方案 | 加一个字段会怎样 |
 * | --- | --- |
 * | Parcelable | ★ **两端必须同时重新编译**，否则旧调用方**直接崩**（读串位） |
 * | **JSON 字符串** | ★ **加字段对旧调用方无害** —— 它不认识就忽略 |
 *
 * 对一个"**要给后续工程长期用**"的 API，这是**唯一合理的取舍**：
 * 我们不可能控制调用方什么时候升级。
 *
 * 代价是丢失编译期类型检查 —— 用**版本号 + 字段只增不改不删**来补：
 * 见 `getApiVersion()` 与 `PowerToysApi.API_VERSION` 的说明。
 *
 * ## 调用示例
 *
 * ```kotlin
 * val svc = IPowerToysApi.Stub.asInterface(
 *     Intent("com.shware.mode.action.API")
 *         .setPackage("com.shware.mode")
 *         .let { bindService(it, conn, BIND_AUTO_CREATE) }
 * )
 * val json = JSONArray(svc.listFeatures())
 * ```
 *
 * ⚠️ A11+ 上调用方 manifest 里**必须**加包可见性，否则 `bindService` 静默失败：
 * ```xml
 * <queries><package android:name="com.shware.mode" /></queries>
 * ```
 */

// ⚠️⚠️ 这一行**不能省**。漏了它 aidl 会把接口生成到【默认包】里：
//   · 生成的是根目录下的 `IPowerToysApi.java`（无 package 行）
//   · 于是 binder 的接口描述符变成裸的 `IPowerToysApi`，而不是
//     `com.shware.mode.api.IPowerToysApi`
//   · 更坑的是**同模块里还能编译通过**（Kotlin 恰好能看见根包类），
//     直到另一个工程想 `import com.shware.mode.api.IPowerToysApi` 时才暴露
//   ⇒ 2026-09-14 实测踩到，症状是客户端报「Unresolved reference 'mode'」。
package com.shware.mode.api;

interface IPowerToysApi {

    // ---------------------------------------------------------------- 元信息

    /** ★ 契约版本。**只增不改** —— 调用方靠它判断某个方法在不在。 */
    int getApiVersion();

    /**
     * 一句人类可读的状态：Shizuku 连没连、TNT 屏在不在。
     *
     * 用来在调用方的界面上回答"为什么什么都查不到"。
     */
    String getStatus();

    // ---------------------------------------------------------------- 功能

    /**
     * ★ 全部功能，**JSON 数组**。
     *
     * 每个元素（字段只增不改）：
     * ```json
     * { "id": "tntgo.battery", "name": "TNT GO 电量", "desc": "…",
     *   "category": "display", "categoryLabel": "显示",
     *   "target": "tnt", "touch": "none",
     *   "enabled": true, "state": "running",
     *   "source": "builtin", "order": 20, "versionName": "0.2.0-powertoys",
     *   "icon": "feat_battery", "iconKey": "feat_battery",
     *   "api": 1, "apiCompatible": true,
     *   "hasSettings": true, "settingsComponent": "com.shware.mode/…",
     *   "processName": "com.shware.mode:mod_tntgobat" }
     * ```
     *
     * ⚠️ 会走一次 `dumpsys`（慢，百毫秒级）⇒ **不要在滚动/高频路径里调**。
     * 只要静态信息的话用 {@link #listFeaturesFast()}。
     */
    String listFeatures();

    /** 同上，但**不查实时运行状态**（快；`state` 恒为 `unknown`）。适合启动时先铺界面。 */
    String listFeaturesFast();

    /** 单个功能的详情（JSON 对象）；找不到返回 `null`。 */
    @nullable String describeFeature(String id);

    // ---------------------------------------------------------------- 开关与启停

    /** 用户开关（持久）。 */
    boolean isEnabled(String id);

    /**
     * 设用户开关并**立即生效**：开 ⇒ 拉起；关 ⇒ 停止。
     *
     * ★ 与界面上的开关是**同一件事**（同一个 `ModStore`），不会各记一套。
     *
     * @return 成功与否。失败原因用 {@link #getLastError()} 取。
     */
    boolean setEnabled(String id, boolean on);

    /** 立即拉起（不改开关；看门狗仍可能把它拉回来）。 */
    boolean start(String id);

    /** 立即停止（不改开关；★ 若开关仍是开的，看门狗 30s 内会把它拉回来）。 */
    boolean stop(String id);

    /**
     * 运行状态：`running` / `stopped` / `unknown`。
     *
     * ⚠️ `unknown` 表示**查不了**（通常是 Shizuku 没连），**不是**"没在跑"。
     */
    String getState(String id);

    // ---------------------------------------------------------------- 图层

    /**
     * ★★ 全部悬浮图层，**JSON 数组**（实测自 `dumpsys window`，不是 mod 的申报值）。
     *
     * ```json
     * { "featureId": "hello.card", "featureName": "示例·状态卡片",
     *   "displayId": 0, "displayLabel": "手机屏 (0)",
     *   "x": 538, "y": 148, "w": 502, "h": 219,
     *   "touch": "none", "visible": true,
     *   "packageName": "com.shware.mode", "processName": "com.shware.mode:mod_hello",
     *   "pid": 5891, "windowTitle": "com.shware.mode", "windowType": "APPLICATION_OVERLAY",
     *   "own": true }
     * ```
     *
     * `featureId` 为 `null` ⇒ 认不出归属（通常是别的应用的悬浮窗）。
     */
    String listLayers();

    /** 两个"自吃"图层是否重叠（会互相抢点击）。JSON 数组，元素为冲突对。 */
    String listLayerConflicts();

    /**
     * ★ 请某个组件隐藏/显示自己的图层 —— **发广播，是请求不是命令**。
     *
     * ⚠️ **组件没实现监听的话，什么都不会发生**。
     * 调用方**不要**把它当成一定能生效的操作写进 UI 文案。
     *
     * @param featureId 空串 = 广播给所有组件
     */
    void setLayerVisible(String featureId, boolean visible);

    // ---------------------------------------------------------------- 错误

    /**
     * 最近一次失败的说明（成功时为 `null`）。
     *
     * ★ 为什么用"读一次"而不是让方法抛异常：
     * AIDL 跨进程抛异常会把**对方的堆栈**带过来，且不同 Android 版本行为不一致。
     * 返回 boolean + 单独取错误，是跨进程接口里更稳的形态。
     *
     * ⚠️ 必须标 `@nullable` —— AIDL 的返回值**默认是非空**的，
     * 不标的话 Kotlin 侧生成的签名是 `String` 而不是 `String?`，
     * 「成功时返回 null」就编译不过。
     */
    @nullable String getLastError();
}
