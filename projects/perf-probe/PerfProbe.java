package com.shware.perf;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * ★★★★ 性能探针：以 **shell 身份**（`app_process`）调用 **QTI 的 `android.util.BoostFramework`**。
 *
 * ## 为什么需要"常驻进程"
 *
 * 任务 AK 第一步用 `service call vendor.perfservice` 试过 —— 返回值 0 但**boost 不生效**
 * （`perfLockRelease` 回 -1，空闲驻留分布毫无变化）。
 *
 * ⚠️ **但那个实验有个漏洞**：`service call` 是**短命进程**，执行完立刻退出。
 * QTI 的 perf HAL **可能注册了 binder 死亡通知**，调用方一死就把 boost 撤掉。
 *
 * ⇒ ★★ **本探针的关键就是【施加 boost 之后不退出，一直活着】**，
 *    这样 HAL 就看不到"客户端死亡"。
 *
 * ## 跑法
 *
 * ```bash
 * adb push out/dex/classes.dex /data/local/tmp/perf.dex
 * # 后台跑，施加 boost 后保持 12 秒
 * adb shell "setsid nohup env CLASSPATH=/data/local/tmp/perf.dex \
 *     PROBE_MODE=hint PROBE_HINT=0x1081 PROBE_HOLD_MS=12000 \
 *     app_process /system/bin com.shware.perf.PerfProbe > /data/local/tmp/perf.log 2>&1 &"
 * ```
 *
 * ## 判据（★ 必须看**下游**，返回值不算数）
 *
 * 空闲状态下（无负载）：
 * - `policy4`(大核) 平时停在 **710400**、`policy7`(超大核) 停在 **825600**
 * - ★ **若 boost 生效（频率下限被抬起）⇒ 它们会被钉在 2419200 / 2956800**
 *
 * ## 参数（都用环境变量 —— `app_process` 会吃掉 argv，`-D` 也不透，两个都实测踩过）
 *
 * | 变量 | 含义 |
 * |---|---|
 * | `PROBE_MODE` | `prop` / `hint` / `lock` / `dump` |
 * | `PROBE_HINT` | hint id（默认 `0x1081`） |
 * | `PROBE_PKG`  | 包名参数（默认 `com.qualcomm.qti.performancemode`） |
 * | `PROBE_DUR`  | 时长 ms（默认 `0x7fffffff`） |
 * | `PROBE_HOLD_MS` | **施加后保持存活多久**（默认 12000） |
 * | `PROBE_RES`  | `lock` 模式的资源表 `opcode:value,...` |
 */
public class PerfProbe {

    private static final String TAG = "[perf-probe] ";

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            line("✗ 顶层异常: " + t);
            t.printStackTrace(System.out);
        }
        // ★ 别用 System.exit —— 让它自然结束，日志刷干净
    }

    private static void run() throws Exception {
        line("=========== QTI perf 探针（常驻进程）===========");
        line("uid=" + uid() + "  mode=" + env("PROBE_MODE", "dump"));

        Class<?> bfClass;
        try {
            bfClass = Class.forName("android.util.BoostFramework");
            line("✓ 找到 android.util.BoostFramework");
        } catch (Throwable t) {
            line("✗ 找不到 BoostFramework: " + t);
            return;
        }

        // 列出公开方法（确认 API 面）
        line("--- BoostFramework 的方法 ---");
        for (Method m : bfClass.getMethods()) {
            if (m.getDeclaringClass() == bfClass) {
                StringBuilder sb = new StringBuilder("  " + m.getReturnType().getSimpleName()
                        + " " + m.getName() + "(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                line(sb.append(")").toString());
            }
        }

        Object bf;
        try {
            bf = bfClass.getDeclaredConstructor().newInstance();
            line("✓ BoostFramework 实例化成功（说明 native 库能加载）");
        } catch (Throwable t) {
            line("✗ 实例化失败: " + t);
            return;
        }

        String mode = env("PROBE_MODE", "dump");

        if ("prop".equals(mode)) {
            callGetProp(bfClass, bf);
            return;
        }
        if ("dump".equals(mode)) {
            callGetProp(bfClass, bf);
            line("（未施加 boost —— 加 PROBE_MODE=hint 才试）");
            return;
        }

        if ("hint".equals(mode)) {
            int hint = parseInt(env("PROBE_HINT", "0x1081"), 0x1081);
            String pkg = env("PROBE_PKG", "com.qualcomm.qti.performancemode");
            int dur = parseInt(env("PROBE_DUR", "" + Integer.MAX_VALUE), Integer.MAX_VALUE);
            int hold = parseInt(env("PROBE_HOLD_MS", "12000"), 12000);

            Method m = bfClass.getMethod("perfHint", int.class, String.class, int.class, int.class);
            Object r = m.invoke(bf, hint, pkg, dur, -1);
            line("★★★ perfHint(hint=0x" + Integer.toHexString(hint) + ", pkg=" + pkg
                    + ", dur=" + dur + ") → " + r);

            line("@@HOLD_START@@ 保持存活 " + hold + "ms —— ★ 现在去测频率");
            System.out.flush();
            Thread.sleep(hold);

            // 释放（有 handle 就用 handler 版）
            int handle = (r instanceof Integer) ? (Integer) r : -1;
            for (String name : new String[]{"perfLockReleaseHandler", "perfLockRelease"}) {
                try {
                    if ("perfLockReleaseHandler".equals(name)) {
                        Method rm = bfClass.getMethod(name, int.class);
                        line("释放 " + name + "(" + handle + ") → " + rm.invoke(bf, handle));
                    } else {
                        Method rm = bfClass.getMethod(name);
                        line("释放 " + name + "() → " + rm.invoke(bf));
                    }
                    break;
                } catch (Throwable t) {
                    line("  释放 " + name + " 失败: " + t);
                }
            }
            line("@@HOLD_END@@");
            return;
        }

        if ("lock".equals(mode)) {
            // PROBE_RES 形如 "0x40800000:1785,0x40800100:2419,0x40800200:2956"
            String spec = env("PROBE_RES", "0x40800000:1785,0x40800100:2419,0x40800200:2956");
            String[] pairs = spec.split(",");
            int[] list = new int[pairs.length * 2];
            for (int i = 0; i < pairs.length; i++) {
                String[] kv = pairs[i].split(":");
                list[i * 2] = parseInt(kv[0], 0);
                list[i * 2 + 1] = parseInt(kv[1], 0);
            }
            int dur = parseInt(env("PROBE_DUR", "12000"), 12000);
            Method m = bfClass.getMethod("perfLockAcquire", int.class, int[].class);
            Object r = m.invoke(bf, dur, list);
            line("★★★ perfLockAcquire(dur=" + dur + ", list=" + spec + ") → " + r);
            line("@@HOLD_START@@");
            System.out.flush();
            Thread.sleep(parseInt(env("PROBE_HOLD_MS", "12000"), 12000));
            try {
                Method rm = bfClass.getMethod("perfLockRelease");
                line("释放 perfLockRelease() → " + rm.invoke(bf));
            } catch (Throwable t) {
                line("  释放失败: " + t);
            }
            line("@@HOLD_END@@");
            return;
        }

        line("未知 mode: " + mode);
    }

    /** `perfGetProp` —— ★ 用来验证 perf HAL 的属性覆盖表是否生效 */
    private static void callGetProp(Class<?> bfClass, Object bf) {
        String[] keys = {
                "vendor.perf.performancemode.support",
                "vendor.perf.iop_v3.enable",
                "vendor.perf.gestureflingboost.enable",
                "ro.vendor.qti.sys.fw.bg_apps_limit",
        };
        for (String k : keys) {
            try {
                Method m = bfClass.getMethod("perfGetProp", String.class, String.class);
                line("  perfGetProp(" + k + ") = " + m.invoke(bf, k, "(默认)"));
            } catch (Throwable t) {
                line("  perfGetProp(" + k + ") 失败: " + t);
            }
        }
        // 对照：系统属性本身
        for (String k : keys) {
            line("  System.getProperty(" + k + ") = " + System.getProperty(k)
                    + "   / getenv = " + System.getenv(k));
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String uid() {
        try {
            Class<?> p = Class.forName("android.os.Process");
            Method m = p.getMethod("myUid");
            return String.valueOf(m.invoke(null));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(String s, int def) {
        try {
            s = s.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) Long.parseLong(s.substring(2), 16);
            }
            return (int) Long.parseLong(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void line(String s) {
        System.out.println(TAG + s);
    }
}
