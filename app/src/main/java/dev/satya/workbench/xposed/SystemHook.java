package dev.satya.workbench.xposed;

import android.os.Binder;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.SurfaceControl;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs inside system_server. Grants a small, fixed set of task-management permissions to the
 * Workbench app only, by answering ActivityManager.checkComponentPermission for its uid.
 * Every AMS/ATMS permission check (getRecentTasks, task snapshots, WindowContainerTransaction,
 * startActivityFromRecents, removeTask) funnels through that static method.
 */
public class SystemHook implements IXposedHookLoadPackage {
    private static final String PKG = "dev.satya.workbench";
    private static final Set<String> GRANTS = new HashSet<>(Arrays.asList(
            "android.permission.REAL_GET_TASKS",
            "android.permission.MANAGE_ACTIVITY_TASKS",
            "android.permission.MANAGE_ACTIVITY_STACKS",
            "android.permission.READ_FRAME_BUFFER",
            "android.permission.START_TASKS_FROM_RECENTS",
            "android.permission.REMOVE_TASKS",
            "android.permission.REORDER_TASKS",
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    ));
    private static volatile int sAppId = -1;
    private static volatile boolean sInstalled = false;
    private static final ThreadLocal<Boolean> sResolving = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if ("com.android.systemui".equals(lpp.packageName)) {
            hookShell(lpp.classLoader);
            return;
        }
        if (!"android".equals(lpp.packageName) && !"system".equals(lpp.packageName)) return;
        if (sInstalled) return;
        sInstalled = true;
        try {
            Class<?> am = Class.forName("android.app.ActivityManager", false, lpp.classLoader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object[] a = p.args;
                    if (a == null || a.length < 4) return;
                    if (!(a[0] instanceof String) || !(a[1] instanceof Integer)) return;
                    int uid = (Integer) a[1];
                    if (uid % 100000 < 10000) return;
                    if (!GRANTS.contains((String) a[0])) return;
                    int id = appId();
                    if (id > 0 && uid % 100000 == id) p.setResult(0);
                }
            };
            XposedBridge.hookAllMethods(am, "checkComponentPermission", hook);
            XposedBridge.log("Workbench: permission bridge installed");
            hookFinishCorners(lpp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }


    /**
     * Transition.buildFinishTransaction resets the corner radius of every participating task to 0, which would
     * square off the floating window after each transition. Keep the radius on freeform tasks.
     */
    private static final ThreadLocal<Object> sFinishing = new ThreadLocal<>();
    private static final java.util.regex.Pattern TASK_NAME = java.util.regex.Pattern.compile("Task=(\\d+)#");

    /** Is the task behind this surface (named "Task=<id>#...") a floating window? Called inside buildFinishTransaction. */
    private static boolean finishingFreeform(Object transition, SurfaceControl sc) {
        try {
            java.util.regex.Matcher m = TASK_NAME.matcher(String.valueOf(sc));
            if (!m.find()) return false;
            int id = Integer.parseInt(m.group(1));
            Object atm = field(field(transition, "mController"), "mAtm");
            Object root = field(atm, "mRootWindowContainer");
            Object task = null;
            for (Class<?> c = root.getClass(); c != null && task == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method mm : c.getDeclaredMethods()) {
                    if (!mm.getName().equals("anyTaskForId")) continue;
                    Class<?>[] pt = mm.getParameterTypes();
                    Object[] a = new Object[pt.length];
                    a[0] = id;
                    for (int i = 1; i < pt.length; i++) {
                        // MATCH_ATTACHED_TASK_ONLY = 0; no options / root task
                        a[i] = pt[i] == int.class ? 0 : pt[i] == boolean.class ? false : null;
                    }
                    if (pt.length == 0 || pt[0] != int.class) continue;
                    mm.setAccessible(true);
                    task = mm.invoke(root, a);
                    if (task != null) break;
                }
            }
            int mode = task == null ? -1 : (Integer) task.getClass().getMethod("getWindowingMode").invoke(task);
            return mode == 5;
        } catch (Throwable t) {
            if (sDbgFf++ < 3) XposedBridge.log("Workbench: finishingFreeform " + android.util.Log.getStackTraceString(t));
            return false;
        }
    }

    /**
     * Transition.buildFinishTransaction resets the corner radius of every participating task to 0, which would
     * square off the floating window after each transition. Keep the radius on freeform tasks.
     */
    private static void hookFinishCorners(ClassLoader cl) {
        try {
            Class<?> tr = Class.forName("com.android.server.wm.Transition", false, cl);
            XposedBridge.hookAllMethods(tr, "buildFinishTransaction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    sFinishing.set(p.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sFinishing.remove();
                }
            });
            XC_MethodHook keep = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object t = sFinishing.get();
                    if (t == null || p.args.length < 2 || !(p.args[0] instanceof SurfaceControl)) return;
                    SurfaceControl sc = (SurfaceControl) p.args[0];
                    boolean ff = finishingFreeform(t, sc);
                    if (!ff) return;
                    if (p.method.getName().equals("setCornerRadiusExt")) {
                        p.args[1] = CORNER_DP * Resources.getSystem().getDisplayMetrics().density;
                    } else {
                        forceRadius(p.args);
                        smoothCorners(p.thisObject, sc);
                    }
                }
            };
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setCornerRadius", keep);
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setCornerRadiusExt", keep);
            XposedBridge.log("Workbench: finish-transaction corner hook installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Every float argument of a setCornerRadius overload (uniform or per-corner) becomes our radius. */
    private static void forceRadius(Object[] args) {
        float r = CORNER_DP * Resources.getSystem().getDisplayMetrics().density;
        for (int i = 1; i < args.length; i++) if (args[i] instanceof Float) args[i] = r;
    }

    private static Method sExt;
    private static int sDbgExt = 0;
    private static int sDbgExtReset = 0;
    private static int sDbgTrace = 0;
    private static int sDbgFf = 0;

    /** Keep resets through the smooth-corner call off our windows too. */
    private static void hookExt(java.util.function.Predicate<SurfaceControl> ours) {
        XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setCornerRadiusExt", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !(p.args[0] instanceof SurfaceControl) || !(p.args[1] instanceof Float)) return;
                SurfaceControl sc = (SurfaceControl) p.args[0];
                if (!ours.test(sc)) return;
                float r = CORNER_DP * Resources.getSystem().getDisplayMetrics().density;
                p.args[1] = r;
            }
        });
    }

    /** ColorOS SurfaceFlinger rounds with its smooth-corner call (as PiP does); the plain radius alone is not drawn. */
    static void smoothCorners(Object t, SurfaceControl sc) {
        smoothCorners(t, sc, CORNER_DP * Resources.getSystem().getDisplayMetrics().density);
    }

    static void smoothCorners(Object t, SurfaceControl sc, float r) {
        try {
            if (sExt == null) sExt = SurfaceControl.Transaction.class.getMethod("setCornerRadiusExt", SurfaceControl.class, float.class, float.class, int.class);
            sExt.invoke(t, sc, r, 3f, 1);
        } catch (Throwable e) {
            if (sDbgExt++ < 1) XposedBridge.log("Workbench: smooth corners unavailable " + e);
        }
    }


    private static volatile boolean sShellHooked = false;

    /**
     * WM Shell (inside SystemUI) puts a caption bar with back/minimize/maximize/close and
     * drag-resize handles on every freeform task. Workbench manages its own window chrome,
     * so freeform tasks get no shell decoration.
     */
    private static void hookShell(ClassLoader cl) {
        if (sShellHooked) return;
        sShellHooked = true;
        try {
            Class<?> vm = Class.forName("com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel", false, cl);
            XposedBridge.hookAllMethods(vm, "shouldShowWindowDecor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object ti = p.args.length > 0 ? p.args[0] : null;
                    if (ti == null) return;
                    try {
                        int mode = (Integer) ti.getClass().getMethod("getWindowingMode").invoke(ti);
                        if (mode == 5) p.setResult(false);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log("Workbench: shell caption hook installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        hookCorners(cl);
        hookInstantTransitions(cl);
    }

    private static final String BENCH_PKG = "dev.satya.workbench";
    private static volatile int sBenchTask = -1;
    private static volatile boolean sBenchVisible = false;
    private static int sDbgInstant = 0;

    private static String pkgOf(Object ti) {
        try {
            Object cn = ti.getClass().getField("baseActivity").get(ti);
            if (cn == null) cn = ti.getClass().getField("topActivity").get(ti);
            return cn == null ? null : ((android.content.ComponentName) cn).getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Remember whether the Workbench task is on screen (fed from ShellTaskOrganizer callbacks). */
    private static void trackBench(Object ti, boolean vanished) {
        int id = taskId(ti);
        if (id < 0) return;
        if (vanished) {
            if (id == sBenchTask) { sBenchTask = -1; sBenchVisible = false; }
            return;
        }
        if (!BENCH_PKG.equals(pkgOf(ti))) return;
        sBenchTask = id;
        try { sBenchVisible = ti.getClass().getField("isVisible").getBoolean(ti); } catch (Throwable ignored) { sBenchVisible = true; }
    }

    /**
     * While Workbench is up it animates everything itself (snapshots in its own window). The shell's freeform
     * animations on top of that (old window sliding away, new one zooming in) fight it, so any transition that
     * only touches floating windows and Workbench itself is completed instantly: final state, no animation.
     */
    private static boolean benchOnly(Object info) {
        if (!sBenchVisible || info == null) return false;
        try {
            java.util.List<?> changes = (java.util.List<?>) info.getClass().getMethod("getChanges").invoke(info);
            boolean freeform = false;
            for (Object c : changes) {
                Object ti = c.getClass().getMethod("getTaskInfo").invoke(c);
                if (ti == null) continue;
                if (BENCH_PKG.equals(pkgOf(ti))) continue;
                int mode = (Integer) ti.getClass().getMethod("getWindowingMode").invoke(ti);
                if (mode != 5) {
                    // A task leaving freeform (Workbench restoring it to full screen) is ours too once it was rounded.
                    if (!sRounded.contains(taskId(ti))) return false;
                }
                freeform = true;
            }
            return freeform;
        } catch (Throwable t) {
            if (sDbgInstant++ < 2) XposedBridge.log("Workbench: benchOnly " + t);
            return false;
        }
    }

    private static void hookInstantTransitions(ClassLoader cl) {
        try {
            Class<?> tr = Class.forName("com.android.wm.shell.transition.Transitions", false, cl);
            XposedBridge.hookAllMethods(tr, "playTransition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 1 || p.args[0] == null) return;
                    Object active = p.args[0];
                    try {
                        Object info = XposedHelpers.getObjectField(active, "mInfo");
                        if (!benchOnly(info)) return;
                        if (XposedHelpers.getObjectField(active, "mHandler") == null) {
                            java.util.List<?> hs = (java.util.List<?>) XposedHelpers.getObjectField(p.thisObject, "mHandlers");
                            if (hs == null || hs.isEmpty()) return;
                            XposedHelpers.setObjectField(active, "mHandler", hs.get(0));
                        }
                        SurfaceControl.Transaction st = (SurfaceControl.Transaction) XposedHelpers.getObjectField(active, "mStartT");
                        st.apply();
                        XposedHelpers.callMethod(p.thisObject, "onFinish", XposedHelpers.getObjectField(active, "mToken"), null);
                        p.setResult(null);
                    } catch (Throwable t) {
                        if (sDbgInstant++ < 3) XposedBridge.log("Workbench: instant transition " + t);
                    }
                }
            });
            XposedBridge.log("Workbench: instant transition hook installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Must match Bench.MAIN_RADIUS_DP. */
    private static final int CORNER_DP = 26;
    private static final Map<Integer, SurfaceControl> sLeashes = new ConcurrentHashMap<>();

    /**
     * Without the shell decoration nothing rounds a freeform task, so it would be a hard rectangle.
     * Round the task leash (crop to the task bounds + corner radius) whenever the task appears or changes.
     */
    private static int sDbg = 0;
    private static int sDbg2 = 0;
    private static Method sSame;

    private static boolean same(SurfaceControl a, SurfaceControl b) {
        if (a == b) return true;
        try {
            if (sSame == null) sSame = SurfaceControl.class.getMethod("isSameSurface", SurfaceControl.class);
            return (Boolean) sSame.invoke(a, b);
        } catch (Throwable t) {
            return false;
        }
    }
    private static final ThreadLocal<Boolean> sInRound = new ThreadLocal<>();
    private static final Set<Integer> sRounded = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static void hookCorners(ClassLoader cl) {
        try {
            Class<?> org = Class.forName("com.android.wm.shell.ShellTaskOrganizer", false, cl);
            java.util.Set<?> hs = XposedBridge.hookAllMethods(org, "onTaskAppeared", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object ti = null;
                    SurfaceControl leash = null;
                    for (Object a : p.args) {
                        if (a instanceof SurfaceControl) leash = (SurfaceControl) a;
                        else if (a != null && a.getClass().getName().endsWith("TaskAppearedInfo")) {
                            try {
                                ti = a.getClass().getMethod("getTaskInfo").invoke(a);
                                leash = (SurfaceControl) a.getClass().getMethod("getLeash").invoke(a);
                            } catch (Throwable ignored) {
                            }
                        } else if (a != null && a.getClass().getName().endsWith("RunningTaskInfo")) ti = a;
                    }
                    if (ti == null || leash == null) return;
                    trackBench(ti, false);
                    int id = taskId(ti);
                    if (id < 0) return;
                    sLeashes.put(id, leash);
                    round(id, ti, leash);
                }
            });
            XposedBridge.hookAllMethods(org, "onTaskInfoChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1) return;
                    trackBench(p.args[0], false);
                    int id = taskId(p.args[0]);
                    SurfaceControl leash = sLeashes.get(id);
                    if (leash != null) round(id, p.args[0], leash);
                }
            });
            XposedBridge.hookAllMethods(org, "onTaskVanished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1) return;
                    trackBench(p.args[0], true);
                    int id = taskId(p.args[0]);
                    sLeashes.remove(id);
                    sRounded.remove(id);
                }
            });
            // Transitions reset the radius on the task leash when they finish; keep ours on rounded freeform tasks.
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setCornerRadius", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sRounded.isEmpty() || p.args.length < 2 || !(p.args[0] instanceof SurfaceControl)) return;
                    if (sInRound.get() != null) return;
                    SurfaceControl sc = (SurfaceControl) p.args[0];
                    for (Integer id : sRounded) {
                        SurfaceControl l = sLeashes.get(id);
                        if (l != null && l.isValid() && sc.isValid() && same(l, sc)) {
                            forceRadius(p.args);
                            smoothCorners(p.thisObject, sc);
                            return;
                        }
                    }
                }
            });
            hookExt(sc -> {
                if (sInRound.get() != null || !sc.isValid()) return false;
                for (Integer id : sRounded) {
                    SurfaceControl l = sLeashes.get(id);
                    if (l != null && l.isValid() && same(l, sc)) return true;
                }
                return false;
            });
            XposedBridge.log("Workbench: freeform corner hook installed (" + hs.size() + " appeared overloads)");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static int taskId(Object ti) {
        try {
            return ti.getClass().getField("taskId").getInt(ti);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void round(int id, Object ti, SurfaceControl leash) {
        try {
            if (!leash.isValid()) return;
            int mode = (Integer) ti.getClass().getMethod("getWindowingMode").invoke(ti);
            Method corner = SurfaceControl.Transaction.class.getMethod("setCornerRadius", SurfaceControl.class, float.class);
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            if (mode != 5) {
                // Left freeform: take our crop and rounding off again.
                if (!sRounded.remove(id)) return;
                t.setCrop(leash, null);
                sInRound.set(Boolean.TRUE);
                try { corner.invoke(t, leash, 0f); smoothCorners(t, leash, 0f); } finally { sInRound.remove(); }
                t.apply();
                return;
            }
            Object cfg = ti.getClass().getField("configuration").get(ti);
            Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
            Rect b = (Rect) wc.getClass().getMethod("getBounds").invoke(wc);
            if (b.isEmpty()) return;
            float r = CORNER_DP * Resources.getSystem().getDisplayMetrics().density;
            t.setCrop(leash, new Rect(0, 0, b.width(), b.height()));
            corner.invoke(t, leash, r);
            smoothCorners(t, leash, r);
            t.apply();
            if (sRounded.add(id)) XposedBridge.log("Workbench: rounded task " + id + " " + b);
        } catch (Throwable t) {
            XposedBridge.log("Workbench: round " + t);
        }
    }

    private static int appId() {
        int id = sAppId;
        if (id > 0) return id;
        if (sResolving.get() != null) return -1;
        sResolving.set(Boolean.TRUE);
        long token = Binder.clearCallingIdentity();
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            Method m = pm.getClass().getMethod("getPackageUid", String.class, long.class, int.class);
            int uid = (Integer) m.invoke(pm, PKG, 0L, 0);
            if (uid > 0) {
                id = uid % 100000;
                sAppId = id;
            }
        } catch (Throwable ignored) {
        } finally {
            Binder.restoreCallingIdentity(token);
            sResolving.remove();
        }
        return id;
    }
}
