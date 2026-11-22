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
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            // Shelf clipboard history: read clips while in the background, without the "pasted from" toast.
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
            "android.permission.SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION"
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
            hookFrames(lpp.classLoader);
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

    /**
     * Corner radius for the floating task behind this surface (named "Task=<id>#..."), or -1 when it isn't one.
     * Called inside buildFinishTransaction. Scaled windows get it divided by their scale, so it looks the same.
     */
    private static float finishingFreeformRadius(Object transition, SurfaceControl sc) {
        try {
            java.util.regex.Matcher m = TASK_NAME.matcher(String.valueOf(sc));
            if (!m.find()) return -1f;
            int id = Integer.parseInt(m.group(1));
            Object atm = field(field(transition, "mController"), "mAtm");
            Object task = anyTask(atm, id);
            int mode = task == null ? -1 : (Integer) task.getClass().getMethod("getWindowingMode").invoke(task);
            if (mode != 5) return -1f;
            Rect f = frameOf(task, id);
            float s = f == null ? 1f : scaleOf(task, f);
            return CORNER_DP * Resources.getSystem().getDisplayMetrics().density / s;
        } catch (Throwable t) {
            if (sDbgFf++ < 3) XposedBridge.log("Workbench: finishingFreeform " + android.util.Log.getStackTraceString(t));
            return -1f;
        }
    }

    /** RootWindowContainer.anyTaskForId(id, ...) with neutral defaults for the extra parameters. */
    private static Object anyTask(Object atm, int id) throws Exception {
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
            return task;
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
                    float r = finishingFreeformRadius(t, sc);
                    if (r < 0) return;
                    if (p.method.getName().equals("setCornerRadiusExt")) {
                        p.args[1] = r;
                    } else {
                        forceRadius(p.args, r);
                        smoothCorners(p.thisObject, sc, r);
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

    /** Every float argument of a setCornerRadius overload (uniform or per-corner) becomes [r]. */
    private static void forceRadius(Object[] args, float r) {
        for (int i = 1; i < args.length; i++) if (args[i] instanceof Float) args[i] = r;
    }

    private static Method sExt;
    private static int sDbgExt = 0;
    private static int sDbgExtReset = 0;
    private static int sDbgTrace = 0;
    private static int sDbgFf = 0;

    /** Keep resets through the smooth-corner call off our windows too. [radius] gives our radius for a leash, or null. */
    private static void hookExt(java.util.function.Function<SurfaceControl, Float> radius) {
        XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setCornerRadiusExt", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !(p.args[0] instanceof SurfaceControl) || !(p.args[1] instanceof Float)) return;
                Float r = radius.apply((SurfaceControl) p.args[0]);
                if (r != null) p.args[1] = r;
            }
        });
    }

    /** ColorOS SurfaceFlinger rounds with its smooth-corner call (as PiP does); the plain radius alone is not drawn. */
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
            if (cn == null) {
                // A finishing task has no activities left: fall back to the intent it was started with.
                android.content.Intent in = (android.content.Intent) ti.getClass().getField("baseIntent").get(ti);
                if (in != null) cn = in.getComponent();
            }
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
        if (info == null) return false;
        if (!sBenchVisible) {
            if (sDbgInstant++ < 12) XposedBridge.log("Workbench: not instant: bench hidden");
            return false;
        }
        try {
            java.util.List<?> changes = (java.util.List<?>) info.getClass().getMethod("getChanges").invoke(info);
            boolean freeform = false;
            for (Object c : changes) {
                Object ti = c.getClass().getMethod("getTaskInfo").invoke(c);
                if (ti == null) continue;
                if (BENCH_PKG.equals(pkgOf(ti)) || taskId(ti) == sBenchTask) continue;
                int mode = (Integer) ti.getClass().getMethod("getWindowingMode").invoke(ti);
                if (mode != 5) {
                    // A task leaving freeform (Workbench restoring it to full screen) is ours too once it was rounded,
                    // also right after it left (its TaskInfo update usually lands before the transition plays).
                    int tid = taskId(ti);
                    Long left = sLeftFreeform.get(tid);
                    boolean recent = left != null && android.os.SystemClock.uptimeMillis() - left < 3000;
                    if (!sRounded.contains(tid) && !recent) {
                        if (sDbgInstant++ < 12) XposedBridge.log("Workbench: not instant: task " + tid + " mode " + mode + " left " + left + " rounded " + sRounded);
                        return false;
                    }
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
                        placeScaled(info, st);
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
                    sShellScale.remove(id);
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
                            float r = shellRadius(id);
                            forceRadius(p.args, r);
                            smoothCorners(p.thisObject, sc, r);
                            return;
                        }
                    }
                }
            });
            hookExt(sc -> {
                if (sInRound.get() != null || !sc.isValid()) return null;
                for (Integer id : sRounded) {
                    SurfaceControl l = sLeashes.get(id);
                    if (l != null && l.isValid() && same(l, sc)) return shellRadius(id);
                }
                return null;
            });
            XposedBridge.log("Workbench: freeform corner hook installed (" + hs.size() + " appeared overloads)");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------ scaled windows (system_server)

    /** Must match Sys.FRAME_MAGIC: resizeTask(taskId, frame, FRAME_MAGIC) from Workbench sets a task's on-screen frame. */
    static final int FRAME_MAGIC = 0x57420001;
    private static final int FRAME_DEFAULT = -1;
    private static final int FRAME_CLEAR_ALL = -2;
    /** TaskInfo.mOplusExtraBundle key that carries a task's frame to the shell (for its corner radius). */
    private static final String FRAME_KEY = "workbench_frame";
    private static final Map<Integer, Rect> sFrames = new ConcurrentHashMap<>();
    private static volatile Rect sDefaultFrame;
    /** Tasks whose surface currently carries our scale. */
    private static final Set<Integer> sScaled = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static int sDbgFrames = 0;
    private static int sDbgKeep = 0;

    private static void setScale(SurfaceControl.Transaction t, SurfaceControl sc, float s) {
        // Hidden overload setMatrix(sc, dsdx, dtdx, dtdy, dsdy).
        XposedHelpers.callMethod(t, "setMatrix", sc, s, 0f, 0f, s);
    }

    private static int taskIdOf(Object task) {
        return (Integer) XposedHelpers.getObjectField(task, "mTaskId");
    }

    private static boolean isTask(Object wc) {
        return wc != null && "com.android.server.wm.Task".equals(wc.getClass().getName());
    }

    /** The on-screen frame of a floating root task Workbench scales, or null. */
    static Rect frameOf(Object task, int id) {
        try {
            if ((Integer) XposedHelpers.callMethod(task, "getWindowingMode") != 5) return null;
            if (!(Boolean) XposedHelpers.callMethod(task, "isRootTask")) return null;
            Rect f = sFrames.get(id);
            if (f == null && sDefaultFrame != null && (Integer) XposedHelpers.callMethod(task, "getActivityType") == 1) f = sDefaultFrame;
            return f;
        } catch (Throwable t) {
            if (sDbgFrames++ < 3) XposedBridge.log("Workbench: frameOf " + t);
            return null;
        }
    }

    /** Scale from the task's (screen-sized) bounds to its frame. */
    static float scaleOf(Object task, Rect f) {
        Rect b = (Rect) XposedHelpers.callMethod(task, "getBounds");
        return b == null || b.width() <= 0 ? 1f : f.width() / (float) b.width();
    }

    /** Put the task's surface at its frame (or back to normal) right away, under the WM lock. */
    private static void applyFrame(Object atm, int id) {
        try {
            Object lock = field(atm, "mGlobalLock");
            synchronized (lock) {
                Object task = anyTask(atm, id);
                if (task == null) return;
                Object t = XposedHelpers.callMethod(task, "getPendingTransaction");
                XposedHelpers.callMethod(task, "updateSurfacePosition", t);
                XposedHelpers.callMethod(task, "scheduleAnimation");
            }
        } catch (Throwable t) {
            if (sDbgFrames++ < 6) XposedBridge.log("Workbench: applyFrame " + android.util.Log.getStackTraceString(t));
        }
    }

    private static void hookFrames(ClassLoader cl) {
        try {
            Class<?> atms = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, cl);
            XposedBridge.hookAllMethods(atms, "resizeTask", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object[] a = p.args;
                    if (a.length != 3 || !(a[2] instanceof Integer) || (Integer) a[2] != FRAME_MAGIC) return;
                    int id0 = appId();
                    if (id0 <= 0 || Binder.getCallingUid() % 100000 != id0) return;
                    p.setResult(null);
                    int id = (Integer) a[0];
                    Rect r = (Rect) a[1];
                    boolean clear = r == null || r.isEmpty();
                    if (id == FRAME_CLEAR_ALL) {
                        Set<Integer> ids = new HashSet<>(sFrames.keySet());
                        ids.addAll(sScaled);
                        sFrames.clear();
                        sDefaultFrame = null;
                        for (int i : ids) applyFrame(p.thisObject, i);
                    } else if (id == FRAME_DEFAULT) {
                        sDefaultFrame = clear ? null : new Rect(r);
                    } else {
                        if (clear) sFrames.remove(id); else sFrames.put(id, new Rect(r));
                        applyFrame(p.thisObject, id);
                    }
                }
            });
            // WM places a task's surface here; a scaled task goes to its frame instead, drawn at its scale.
            Class<?> wcc = Class.forName("com.android.server.wm.WindowContainer", false, cl);
            XposedBridge.hookAllMethods(wcc, "updateSurfacePosition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sFrames.isEmpty() && sDefaultFrame == null && sScaled.isEmpty()) return;
                    Object wc = p.thisObject;
                    if (p.args.length != 1 || !(p.args[0] instanceof SurfaceControl.Transaction) || !isTask(wc)) return;
                    try {
                        SurfaceControl sc = (SurfaceControl) field(wc, "mSurfaceControl");
                        if (sc == null) return;
                        if ((Boolean) XposedHelpers.callMethod(field(wc, "mSurfaceAnimator"), "hasLeash")) return;
                        int id = taskIdOf(wc);
                        SurfaceControl.Transaction t = (SurfaceControl.Transaction) p.args[0];
                        android.graphics.Point last = (android.graphics.Point) field(wc, "mLastSurfacePosition");
                        Rect f = frameOf(wc, id);
                        if (f != null) {
                            float s = scaleOf(wc, f);
                            t.setPosition(sc, f.left, f.top);
                            setScale(t, sc, s);
                            if (last != null) last.set(f.left, f.top);
                            if (sScaled.add(id)) XposedBridge.log("Workbench: scaled task " + id + " to " + f + " x" + s);
                            p.setResult(null);
                        } else if (sScaled.remove(id)) {
                            setScale(t, sc, 1f);
                            // Make the original write its position even though it thinks nothing moved.
                            if (last != null) last.set(Integer.MIN_VALUE, Integer.MIN_VALUE);
                        }
                    } catch (Throwable t) {
                        if (sDbgFrames++ < 6) XposedBridge.log("Workbench: updateSurfacePosition " + t);
                    }
                }
            });
            // Transitions put every participant back at its bounds, unscaled, when they finish.
            Class<?> tr = Class.forName("com.android.server.wm.Transition", false, cl);
            XposedBridge.hookAllMethods(tr, "resetSurfaceTransform", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length < 3 || !isTask(p.args[1]) || !(p.args[2] instanceof SurfaceControl)) return;
                    try {
                        Object task = p.args[1];
                        Rect f = frameOf(task, taskIdOf(task));
                        if (f == null) return;
                        float s = scaleOf(task, f);
                        SurfaceControl.Transaction t = (SurfaceControl.Transaction) p.args[0];
                        t.setPosition((SurfaceControl) p.args[2], f.left, f.top);
                        setScale(t, (SurfaceControl) p.args[2], s);
                    } catch (Throwable t) {
                        if (sDbgFrames++ < 6) XposedBridge.log("Workbench: resetSurfaceTransform " + t);
                    }
                }
            });
            // The shell learns each task's frame from its TaskInfo (for corners and transitions).
            Class<?> task = Class.forName("com.android.server.wm.Task", false, cl);
            XposedBridge.hookAllMethods(task, "fillTaskInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1 || !(p.args[0] instanceof android.app.TaskInfo)) return;
                    android.app.TaskInfo info = (android.app.TaskInfo) p.args[0];
                    try {
                        Rect f = (sFrames.isEmpty() && sDefaultFrame == null) ? null : frameOf(p.thisObject, info.taskId);
                        android.os.Bundle b = (android.os.Bundle) XposedHelpers.getObjectField(info, "mOplusExtraBundle");
                        if (f == null && (b == null || !b.containsKey(FRAME_KEY))) return;
                        android.os.Bundle nb = b == null ? new android.os.Bundle() : new android.os.Bundle(b);
                        if (f != null) nb.putParcelable(FRAME_KEY, new Rect(f)); else nb.remove(FRAME_KEY);
                        XposedHelpers.setObjectField(info, "mOplusExtraBundle", nb);
                    } catch (Throwable t) {
                        if (sDbgFrames++ < 6) XposedBridge.log("Workbench: fillTaskInfo " + t);
                    }
                }
            });
            // Launches put floating bounds inside the stable area (below the status bar) and shift any that match
            // another window's. Our bounds are the whole screen in miniature: keep them at (0, 0), or the app loses its
            // status-bar inset and lays out differently from full screen.
            XC_MethodHook keepVirtual = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sFrames.isEmpty() && sDefaultFrame == null) return;
                    Rect r = null;
                    for (Object a : p.args) if (a instanceof Rect) r = (Rect) a;
                    if (r == null || r.left != 0 || r.top != 0) return;
                    int sw = Resources.getSystem().getDisplayMetrics().widthPixels;
                    if (r.width() != sw) return;
                    if (sDbgKeep++ < 4) XposedBridge.log("Workbench: kept virtual bounds " + r + " in " + p.method.getName());
                    p.setResult(null);
                }
            };
            XposedBridge.hookAllMethods(Class.forName("com.android.server.wm.LaunchParamsUtil", false, cl), "adjustBoundsToFitInDisplayArea", keepVirtual);
            // Every config resolve of a floating task moves it below the status bar (Task.computeFreeformBounds).
            XposedBridge.hookAllMethods(Class.forName("com.android.server.wm.Task", false, cl), "computeFreeformBounds", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sFrames.isEmpty() && sDefaultFrame == null) return;
                    try {
                        Object task = p.thisObject;
                        if (frameOf(task, taskIdOf(task)) == null) return;
                        if (sDbgKeep++ < 4) XposedBridge.log("Workbench: kept bounds of task " + taskIdOf(task) + " at the top");
                        p.setResult(null);
                    } catch (Throwable t) {
                        if (sDbgFrames++ < 6) XposedBridge.log("Workbench: computeFreeformBounds " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(Class.forName("com.android.server.wm.TaskLaunchParamsModifier", false, cl), "adjustBoundsToAvoidConflictInDisplayArea", keepVirtual);
            // Floating windows lose every system bar inset (only caption and IME stay). A scaled window is the full
            // screen in miniature, so give it the full-screen insets back: the app keeps the exact same layout.
            Class<?> ip = Class.forName("com.android.server.wm.InsetsPolicy", false, cl);
            XposedBridge.hookAllMethods(ip, "enforceInsetsPolicyForTarget", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (sFrames.isEmpty() && sDefaultFrame == null) return;
                    if (p.args.length != 2 || p.args[0] == null || p.args[1] == null || p.getResult() == null) return;
                    try {
                        Object task = XposedHelpers.callMethod(p.args[0], "getTask");
                        if (task == null) return;
                        Object root = XposedHelpers.callMethod(task, "getRootTask");
                        Rect f = root == null ? null : frameOf(root, taskIdOf(root));
                        if (f == null) return;
                        Object in = p.args[1], out = p.getResult();
                        if (in == out) {
                            out = XposedHelpers.newInstance(in.getClass(), in, true);
                            p.setResult(out);
                        }
                        mapIme(out, f, scaleOf(root, f));
                        int bars = android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars()
                                | android.view.WindowInsets.Type.systemGestures() | android.view.WindowInsets.Type.mandatorySystemGestures()
                                | android.view.WindowInsets.Type.tappableElement();
                        int n = (Integer) XposedHelpers.callMethod(in, "sourceSize");
                        for (int i = 0; i < n; i++) {
                            Object src = XposedHelpers.callMethod(in, "sourceAt", i);
                            int type = (Integer) XposedHelpers.callMethod(src, "getType");
                            if ((type & bars) != 0) XposedHelpers.callMethod(out, "addSource", src);
                        }
                        XposedHelpers.callMethod(out, "setDisplayCutout", XposedHelpers.callMethod(in, "getDisplayCutout"));
                    } catch (Throwable t) {
                        if (sDbgFrames++ < 6) XposedBridge.log("Workbench: insets " + t);
                    }
                }
            });
            XposedBridge.log("Workbench: scaled window hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /**
     * The IME frame is in screen pixels, but a scaled app lays out at full-screen size. Move the IME into the app's
     * own coordinates (where the keyboard's top edge falls inside the scaled window), or the app reserves too little
     * room and its input field ends up under the keyboard.
     */
    private static void mapIme(Object state, Rect f, float s) {
        if (s <= 0f || s == 1f) return;
        int ime = android.view.WindowInsets.Type.ime();
        int n = (Integer) XposedHelpers.callMethod(state, "sourceSize");
        for (int i = 0; i < n; i++) {
            Object src = XposedHelpers.callMethod(state, "sourceAt", i);
            if ((Integer) XposedHelpers.callMethod(src, "getType") != ime) continue;
            Object copy = XposedHelpers.newInstance(src.getClass(), src);
            XposedHelpers.callMethod(copy, "setFrame", toVirtual((Rect) XposedHelpers.callMethod(src, "getFrame"), f, s));
            Rect vis = (Rect) XposedHelpers.callMethod(src, "getVisibleFrame");
            if (vis != null) XposedHelpers.callMethod(copy, "setVisibleFrame", toVirtual(vis, f, s));
            XposedHelpers.callMethod(state, "addSource", copy);
            return;
        }
    }

    private static Rect toVirtual(Rect r, Rect f, float s) {
        if (r == null || r.isEmpty()) return r;
        return new Rect(Math.round((r.left - f.left) / s), Math.round((r.top - f.top) / s),
                Math.round((r.right - f.left) / s), Math.round((r.bottom - f.top) / s));
    }

    // ------------------------------------------------------------------ scaled windows (shell)

    private static final Map<Integer, Float> sShellScale = new ConcurrentHashMap<>();
    /** When a rounded task last left freeform (uptime ms). */
    private static final Map<Integer, Long> sLeftFreeform = new ConcurrentHashMap<>();

    /** The frame system_server put into a TaskInfo, or null. */
    @SuppressWarnings("deprecation")
    static Rect frameIn(Object ti) {
        try {
            android.os.Bundle b = (android.os.Bundle) XposedHelpers.getObjectField(ti, "mOplusExtraBundle");
            if (b == null) return null;
            b.setClassLoader(Rect.class.getClassLoader());
            Object r = b.getParcelable(FRAME_KEY);
            return r instanceof Rect ? (Rect) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Our corner radius in a task leash's own space: divided by its scale so it looks the same on screen. */
    static float shellRadius(int id) {
        Float s = sShellScale.get(id);
        float r = CORNER_DP * Resources.getSystem().getDisplayMetrics().density;
        return s == null || s <= 0f ? r : r / s;
    }

    /**
     * The shell's transition setup puts each task leash at its bounds (top-left, unscaled). In the start
     * transaction of an instant transition, put scaled tasks at their frames, so no frame shows them full size.
     */
    private static void placeScaled(Object info, SurfaceControl.Transaction st) {
        try {
            java.util.List<?> changes = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
            for (Object c : changes) {
                Object ti = XposedHelpers.callMethod(c, "getTaskInfo");
                if (ti == null) continue;
                if ((Integer) XposedHelpers.callMethod(ti, "getWindowingMode") != 5) continue;
                Rect f = frameIn(ti);
                if (f == null) continue;
                SurfaceControl leash = (SurfaceControl) XposedHelpers.callMethod(c, "getLeash");
                Rect b = (Rect) XposedHelpers.callMethod(c, "getEndAbsBounds");
                if (leash == null || b == null || b.width() <= 0) continue;
                float s = f.width() / (float) b.width();
                int ox = 0, oy = 0;
                try {
                    Object root = XposedHelpers.callMethod(info, "getRoot", (Integer) XposedHelpers.callMethod(c, "getRootIndex"));
                    android.graphics.Point o = (android.graphics.Point) XposedHelpers.callMethod(root, "getOffset");
                    if (o != null) { ox = o.x; oy = o.y; }
                } catch (Throwable ignored) {
                }
                st.setPosition(leash, f.left - ox, f.top - oy);
                setScale(st, leash, s);
            }
        } catch (Throwable t) {
            if (sDbgInstant++ < 4) XposedBridge.log("Workbench: placeScaled " + t);
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
                sLeftFreeform.put(id, android.os.SystemClock.uptimeMillis());
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
            Rect f = frameIn(ti);
            sShellScale.put(id, f == null ? 1f : f.width() / (float) b.width());
            float r = shellRadius(id);
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
