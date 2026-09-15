// IKeyCallback.aidl
//
// ★ 按键流的回调端。
//
// 为什么要有它（而不是复用 exec）：`getevent` 是**无限流**，exec 是**一次性**的
// （要等进程退出才返回）。若改用「抓 N 个就退出」的批模式，两次调用之间的缝隙会丢键
// ⇒ 不能当基础设施。所以单开一对 start/stop，由 UserService 自己管线程。
//
// 为什么是 oneway：App 侧一旦卡顿，同步回调会把 shell 侧读线程堵死 ⇒ 内核缓冲区溢出丢键。
// oneway 让 shell 侧**永不阻塞**，代价是 App 侧收到回调时已在 binder 线程（须自己切主线程）。
package com.shware.mode.shell;

oneway interface IKeyCallback {
    /**
     * 一行 getevent 输出。
     * UserService 侧**已过滤**：只留 `EV_KEY` 与 `MSC_SCAN`——
     * 鼠标 125Hz 的 `EV_REL` / `EV_SYN` / `EV_REP` 不上跨进程总线。
     */
    void onKeyLine(String line);

    /** 流结束（进程退出 / 被 stop / 出错）。reason 给人看，也进日志。 */
    void onCaptureClosed(String reason);
}
