package com.shware.mode.shell

import android.content.Context

/**
 * ★ 进程内**唯一**的 [ShellGateway]。
 *
 * ## 为什么需要它
 *
 * Shizuku 的 `UserService` 每绑定一次就**起一个独立进程**
 * （名字形如 `com.shware.mode:shellsvc`）。
 * 实测（2026-09-12）：已堆积 **3 个** `shellsvc` 进程 ——
 * 就是"Activity 一个、别处再一个"各绑各的留下的。
 *
 * ⇒ 宿主现在有**两个常驻者**（`MainActivity` 与 [com.shware.mode.LauncherService]），
 * 若各建各的 gateway，进程数会随重建次数单调增长。
 * **共享一个**才是对的。
 *
 * ⚠️ 传进来的是 `applicationContext` —— 它的寿命就是进程寿命，
 * 与"进程内唯一"这个语义匹配。**不要**传 Activity（会泄漏）。
 */
object SharedShell {

    @Volatile
    private var instance: ShellGateway? = null

    fun get(context: Context): ShellGateway =
        instance ?: synchronized(this) {
            instance ?: ShellGateway(context.applicationContext).also { instance = it }
        }

    /** 已建过就返回它；没建过**不建**（用于只读地看一眼当前状态）。 */
    fun peek(): ShellGateway? = instance
}
