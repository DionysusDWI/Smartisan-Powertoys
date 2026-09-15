package com.shware.probe;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ★★★ 以 **shell 身份**（`app_process`）探测"到底能调用哪些系统能力"。
 *
 * 跑法（见 .paper/plans/AC-shell能力探针.md）：
 * ```
 * adb shell CLASSPATH=/data/local/tmp/probe.dex app_process /system/bin com.shware.probe.ShellProbe
 * ```
 *
 * ★ **全部用反射**：javac 对着公开的 android.jar 编译时，隐藏 API（`IActivityTaskManager` 等）
 * 编译不过；反射绕开这个，而且**不需要拉 framework.jar**。
 *
 * ⚠️ 本探针**只读**（列方法、查任务、查包），不做任何写操作。
 */
public class ShellProbe {

    public static void main(String[] args) {
        line("=========== shell 能力探针 ===========");

        identity();

        Object atm = service("activity_task", "android.app.IActivityTaskManager");
        Object pm = service("package", "android.content.pm.IPackageManager");
        Object wm = service("window", "android.view.IWindowManager");
        // ★★ 音频路由（任务 AH）：确认 shell 能不能控制输出设备
        Object audio = service("audio", "android.media.IAudioService");

        /*
         * ⚠️ **参数用环境变量传，既不用 argv 也不用 -D** —— 两个都实测踩过：
         *   · `app_process` 会把命令行参数吃掉 ⇒ `main(String[])` 收到空数组
         *   · `-D` 系统属性也没透到 VM 里（本 ROM）
         * ⇒ 环境变量最稳（`adb shell "PROBE_MODE=... CLASSPATH=... app_process ..."`）。
         */
        String mode = env("PROBE_MODE");

        // ★ 写操作要显式点名才做（默认只读）
        if (mode.equals("move-stack")) {
            moveStackToDisplay(atm, wm,
                    Integer.parseInt(env("PROBE_STACK")),
                    Integer.parseInt(env("PROBE_DISPLAY")));
            return;
        }
        if (mode.equals("stacks")) {
            callGetAllStackInfos(atm);
            return;
        }
        if (mode.equals("focus-task")) {
            callOne(atm, "setFocusedTask", new Class<?>[]{int.class},
                    new Object[]{Integer.parseInt(env("PROBE_TASK"))});
            return;
        }
        // ★★ 音频路由（任务 AH）
        if (mode.equals("audio-read")) {
            callGet(audio, "isSpeakerphoneOn");
            callGet(audio, "getMode");
            callGet(audio, "isAudioServerRunning");
            callGet(audio, "getAudioProductStrategies");
            callGet(audio, "isBluetoothA2dpOn");
            return;
        }
        if (mode.equals("audio-set")) {
            // setWiredDeviceConnectionState(int type, int state, String address, String name, String caller)
            callOne(audio, "setWiredDeviceConnectionState",
                    new Class<?>[]{int.class, int.class, String.class, String.class, String.class},
                    new Object[]{Integer.parseInt(env("PROBE_DEV")), Integer.parseInt(env("PROBE_STATE")),
                            env("PROBE_ADDR"), env("PROBE_NAME"), "mode-probe"});
            return;
        }

        dumpMethods("IActivityTaskManager", atm);
        dumpMethods("IPackageManager", pm);
        dumpMethods("IWindowManager", wm);
        dumpMethods("IAudioService", audio);

        callGetTasks(atm);
        callGetAllStackInfos(atm);
        callGetInstalledPackages(pm);

        line("=========== 探针结束 ===========");
    }

    // ---------------------------------------------------------------- ★ 写操作

    /**
     * ★★★ 把一个 **stack 搬到另一个显示** —— 本任务要验的核心能力。
     *
     * ⚠️ **这是会改系统的操作**：调用前务必确认该 stack 里**只有你的靶子**，
     * 否则会把别人的窗口一起搬走。
     *
     * ⚠️ 这个方法在 `IActivityTaskManager` 和 `IWindowManager` 上**都可能存在**
     * （不同 ROM 不一样）⇒ **两个都试**，把实际生效的那个报出来。
     */
    private static void moveStackToDisplay(Object atm, Object wm, int stackId, int displayId) {
        line("-- 写操作：moveStackToDisplay(stackId=" + stackId + ", displayId=" + displayId + ") --");
        Object[] targets = {atm, wm};
        String[] names = {"IActivityTaskManager", "IWindowManager"};
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] == null) continue;
            try {
                Method m = targets[i].getClass().getMethod("moveStackToDisplay", int.class, int.class);
                m.invoke(targets[i], stackId, displayId);
                line("    ✓ 通过 " + names[i] + " 调用成功");
                return;
            } catch (NoSuchMethodException e) {
                line("    - " + names[i] + " 上没有这个方法");
            } catch (Throwable t) {
                line("    ✗ " + names[i] + " 调用失败：" + t);
                if (t.getCause() != null) line("      cause: " + t.getCause());
            }
        }
        line("    ⇒ 两个接口都没成功");
    }

    // ---------------------------------------------------------------- 0. 身份自证

    private static void identity() {
        line("-- 身份 --");
        line("  uid        = " + android.os.Process.myUid());
        line("  pid        = " + android.os.Process.myPid());
        try {
            Class<?> sel = Class.forName("android.os.SELinux");
            line("  SELinux    = " + sel.getMethod("getContext").invoke(null));
        } catch (Throwable t) {
            line("  SELinux    = <取不到> " + t);
        }
        line("  java.class.path = " + System.getProperty("java.class.path"));
    }

    // ---------------------------------------------------------------- 服务获取

    /** 拿 Binder 并转成接口。★ ServiceManager / Stub.asInterface 都是隐藏 API ⇒ 反射 */
    private static Object service(String name, String ifaceClass) {
        line("-- 服务 " + name + " --");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, name);
            if (binder == null) {
                line("  ✗ getService 返回 null（服务不存在）");
                return null;
            }
            line("  ✓ binder = " + binder);
            Class<?> stub = Class.forName(ifaceClass + "$Stub");
            Object iface = stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
                    .invoke(null, binder);
            line("  ✓ asInterface = " + iface);
            return iface;
        } catch (Throwable t) {
            line("  ✗ 失败：" + t);
            return null;
        }
    }

    // ---------------------------------------------------------------- 列方法

    /** ★ 先把所有方法签名打出来 —— 比我瞎猜哪些 API 能用准得多 */
    private static void dumpMethods(String label, Object iface) {
        if (iface == null) {
            line("-- " + label + " 方法表：<服务没拿到> --");
            return;
        }
        line("-- " + label + " 方法表 --");
        try {
            Method[] ms = iface.getClass().getMethods();
            java.util.Arrays.sort(ms, (a, b) -> a.getName().compareTo(b.getName()));
            for (Method m : ms) {
                if (m.getDeclaringClass() == Object.class) continue;
                StringBuilder sb = new StringBuilder("    ").append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(simple(ps[i]));
                }
                sb.append(") -> ").append(simple(m.getReturnType()));
                line(sb.toString());
            }
        } catch (Throwable t) {
            line("    ✗ 列方法失败：" + t);
        }
    }

    private static String simple(Class<?> c) {
        if (c.isArray()) return simple(c.getComponentType()) + "[]";
        String n = c.getName();
        return n.startsWith("java.lang.") ? n.substring(10) : n;
    }

    // ---------------------------------------------------------------- 试调只读能力

    private static void callGetTasks(Object atm) {
        line("-- 试调 getTasks（只读）--");
        if (atm == null) { line("    <服务没拿到>"); return; }
        try {
            Method m = atm.getClass().getMethod("getTasks", int.class);
            Object r = m.invoke(atm, 30);
            line("    ✓ getTasks(30) 返回 " + (r == null ? "null" : r.getClass().getName()));
            if (r instanceof List) {
                List<?> l = (List<?>) r;
                line("      任务数 = " + l.size());
                for (int i = 0; i < Math.min(l.size(), 40); i++) {
                    line("      [" + i + "] " + describeTask(l.get(i)));
                }
            }
        } catch (Throwable t) {
            line("    ✗ " + t);
            if (t.getCause() != null) line("      cause: " + t.getCause());
        }
    }

    /**
     * 描述一个任务。
     *
     * ⚠️ 这里**必须每个字段单独 try** —— 上一版一次性取全部字段，
     * 一个字段名不对（`label`）就整条描述失败，**等于白跑**。
     * 反射取隐藏类的字段本来就脆，**要抗得住**。
     */
    private static String describeTask(Object t) {
        StringBuilder sb = new StringBuilder();
        Class<?> c = t.getClass();
        sb.append(simple(c)).append(" { ");
        for (String f : new String[]{"taskId", "id", "displayId", "userId", "topActivity",
                "baseActivity", "baseIntent", "isRunning", "numActivities"}) {
            sb.append(f).append('=').append(field(c, t, f)).append(' ');
        }
        Object desc = field(c, t, "taskDescription");
        if (desc != null) {
            Class<?> dc = desc.getClass();
            sb.append("| desc.label=").append(field(dc, desc, "label"));
            sb.append(" desc.iconName=").append(field(dc, desc, "iconFilename"));
        }
        sb.append('}');
        return sb.toString();
    }

    /** 取字段，取不到就返回 "<->"（**不抛异常**） */
    private static Object field(Class<?> c, Object o, String name) {
        try {
            java.lang.reflect.Field f = c.getField(name);
            Object v = f.get(o);
            if (v instanceof android.content.Intent) {
                android.content.Intent i = (android.content.Intent) v;
                return i.getComponent() != null ? i.getComponent().flattenToShortString()
                        : String.valueOf(i.getAction());
            }
            return v;
        } catch (Throwable e) {
            return "<->";
        }
    }

    /**
     * ★★★ 列出所有 **stack** —— 这是"display ↔ stack"的映射表。
     *
     * Android 10 里**任务挂在 stack 上，stack 挂在 display 上**，
     * 而搬运窗口的 API 是 [`moveStackToDisplay`] / [`moveTaskToStack`]（都按 stack 走）
     * ⇒ **不先拿到这张表，不知道往哪儿搬。**
     *
     * 只读。
     */
    private static void callGetAllStackInfos(Object atm) {
        line("-- 试调 getAllStackInfos（只读）--");
        if (atm == null) { line("    <服务没拿到>"); return; }
        try {
            Object r = atm.getClass().getMethod("getAllStackInfos").invoke(atm);
            if (!(r instanceof List)) { line("    ✓ 返回 " + r); return; }
            List<?> l = (List<?>) r;
            line("    ✓ stack 数 = " + l.size());
            for (Object s : l) line("      " + describeStack(s));
        } catch (Throwable t) {
            line("    ✗ " + t);
            if (t.getCause() != null) line("      cause: " + t.getCause());
        }
    }

    private static String describeStack(Object s) {
        Class<?> c = s.getClass();
        StringBuilder sb = new StringBuilder(simple(c)).append(" { ");
        for (String f : new String[]{"stackId", "displayId", "userId", "topActivity",
                "taskIds", "bounds", "windowingMode", "activityType"}) {
            sb.append(f).append('=').append(field(c, s, f)).append(' ');
        }
        return sb.append('}').toString();
    }

    private static void callGetInstalledPackages(Object pm) {
        line("-- 试调 getInstalledPackages（只读）--");
        if (pm == null) { line("    <服务没拿到>"); return; }
        try {
            Method m = pm.getClass().getMethod("getInstalledPackages", int.class, int.class);
            Object slice = m.invoke(pm, 0, 0);
            line("    ✓ 返回 " + (slice == null ? "null" : slice.getClass().getName()));
            if (slice != null) {
                Object n = slice.getClass().getMethod("getList").invoke(slice);
                line("      包数 = " + (n instanceof List ? ((List<?>) n).size() : "?"));
            }
        } catch (Throwable t) {
            line("    ✗ " + t);
            if (t.getCause() != null) line("      cause: " + t.getCause());
        }
    }

    /**
     * 只读：调一个无参方法，把返回值或**真实异常**打出来。
     *
     * ⚠️ 反射调用的异常外壳是 `InvocationTargetException`，**真原因在 cause 里**
     * （比如 `SecurityException`）—— 不把 cause 打出来，就分不清"权限不够"和"方法不存在"。
     */
    private static void callGet(Object target, String name) {
        if (target == null) {
            line("    " + name + " → <服务没拿到>");
            return;
        }
        try {
            Object r = target.getClass().getMethod(name).invoke(target);
            line("    ✓ " + name + "() → " + r);
        } catch (Throwable t) {
            String c = t.getCause() != null ? t.getCause().toString() : "";
            line("    ✗ " + name + "() → " + t + (c.isEmpty() ? "" : "  ／ " + c));
        }
    }

    /** 通用：按名字反射调一个方法并报结果（够用即可，不做参数类型推断） */
    private static void callOne(Object target, String name, Class<?>[] types, Object[] args) {
        line("-- 写操作：" + name + " --");
        if (target == null) { line("    <服务没拿到>"); return; }
        try {
            Method m = target.getClass().getMethod(name, types);
            Object r = m.invoke(target, args);
            line("    ✓ 调用成功" + (r == null ? "" : "  返回 " + r));
        } catch (Throwable t) {
            line("    ✗ " + t);
            if (t.getCause() != null) line("      cause: " + t.getCause());
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v.trim();
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
