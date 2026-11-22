package dev.satya.workbench.sys

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.IActivityTaskManager
import android.app.TaskInfo
import android.app.TaskStackListener
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.window.IWindowOrganizerController
import android.window.TaskSnapshotManager
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** Thin wrapper over the hidden task / window-organizer APIs. All calls are binder calls: keep them off the UI thread when possible. */
object Sys {
    const val TAG = "Workbench"
    const val WM_UNDEFINED = 0
    const val WM_FULLSCREEN = 1
    const val WM_FREEFORM = 5
    const val ACT_STANDARD = 1
    const val ACT_HOME = 2
    const val ACT_RECENTS = 3
    private const val RECENT_IGNORE_UNAVAILABLE = 0x0002
    private const val RES_HIGH = 1
    private const val RES_LOW = 2
    private const val RES_ANY = 3

    val atm: IActivityTaskManager by lazy { ActivityTaskManager.getService() }
    private val woc: IWindowOrganizerController by lazy { atm.windowOrganizerController }
    val userId: Int get() = android.os.Process.myUid() / 100000

    private val mWindowingMode: Method? by lazy { runCatching { TaskInfo::class.java.getMethod("getWindowingMode") }.getOrNull() }
    private val mActivityType: Method? by lazy { runCatching { TaskInfo::class.java.getMethod("getActivityType") }.getOrNull() }
    private val mToken: Method? by lazy { runCatching { TaskInfo::class.java.getMethod("getToken") }.getOrNull() }
    private val fVisible: Field? by lazy { runCatching { TaskInfo::class.java.getDeclaredField("isVisible").apply { isAccessible = true } }.getOrNull() }
    private val fLastActive: Field? by lazy { runCatching { TaskInfo::class.java.getDeclaredField("lastActiveTime").apply { isAccessible = true } }.getOrNull() }
    private val mSetWindowingMode: Method? by lazy {
        runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType) }.getOrNull()
    }

    fun windowingMode(t: TaskInfo): Int = runCatching { mWindowingMode?.invoke(t) as Int }.getOrDefault(WM_UNDEFINED)
    fun activityType(t: TaskInfo): Int = runCatching { mActivityType?.invoke(t) as Int }.getOrDefault(0)
    fun token(t: TaskInfo): WindowContainerToken? = runCatching { mToken?.invoke(t) as WindowContainerToken? }.getOrNull()
    fun isVisible(t: TaskInfo): Boolean = runCatching { fVisible!!.getBoolean(t) }.getOrDefault(false)
    fun lastActive(t: TaskInfo): Long = runCatching { fLastActive!!.getLong(t) }.getOrDefault(0L)
    fun packageOf(t: TaskInfo): String? =
        t.baseActivity?.packageName ?: t.baseIntent?.component?.packageName ?: t.topActivity?.packageName

    fun recentTasks(max: Int = 48): List<ActivityManager.RecentTaskInfo> =
        runCatching { atm.getRecentTasks(max, RECENT_IGNORE_UNAVAILABLE, userId).list }
            .onFailure { Log.w(TAG, "getRecentTasks", it) }.getOrNull() ?: emptyList()

    fun runningTasks(): List<ActivityManager.RunningTaskInfo> =
        runCatching { atm.getTasks(64, false, false, -1) }
            .onFailure { Log.w(TAG, "getTasks", it) }.getOrNull() ?: emptyList()

    /** True when the system hook is active (we can see other apps' tasks). */
    fun privileged(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.MANAGE_ACTIVITY_TASKS") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission("android.permission.READ_FRAME_BUFFER") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The app the user is looking at right now (fullscreen, standard), excluding [ownPkg]. */
    fun foregroundTask(ownPkg: String): ActivityManager.RunningTaskInfo? =
        runningTasks().firstOrNull {
            isVisible(it) && activityType(it) == ACT_STANDARD && packageOf(it) != ownPkg &&
                windowingMode(it) != WM_FREEFORM
        }

    /** Snapshots taken of floating windows (no status-bar band at the top, unlike full-screen ones). */
    val windowSnaps: MutableSet<Bitmap> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    fun isWindowSnap(b: Bitmap): Boolean = synchronized(windowSnaps) { b in windowSnaps }

    fun snapshot(taskId: Int, fresh: Boolean = false, low: Boolean = false): Bitmap? {
        return try {
            val m = TaskSnapshotManager.getInstance()
            var s = if (fresh) runCatching { m.takeTaskSnapshot(taskId, false) }.getOrNull() else null
            if (s == null) s = m.getTaskSnapshot(taskId, if (low) RES_LOW else RES_ANY)
            val hb = s?.hardwareBuffer ?: return null
            val bmp = Bitmap.wrapHardwareBuffer(hb, s.colorSpace)
            val mode = runCatching { s.javaClass.getMethod("getWindowingMode").invoke(s) as Int }.getOrDefault(WM_UNDEFINED)
            if (bmp != null && mode == WM_FREEFORM) synchronized(windowSnaps) { windowSnaps.add(bmp) }
            bmp
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot $taskId: $t")
            null
        }
    }

    fun tokenOf(taskId: Int): WindowContainerToken? =
        runningTasks().firstOrNull { it.taskId == taskId }?.let { token(it) }

    fun transact(build: (WindowContainerTransaction) -> Unit): Boolean = try {
        val wct = WindowContainerTransaction()
        build(wct)
        woc.applyTransaction(wct)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "applyTransaction", t)
        false
    }

    fun freeformOptions(ctx: Context?, bounds: Rect?, noAnim: Boolean): ActivityOptions {
        val o = if (noAnim && ctx != null) ActivityOptions.makeCustomAnimation(ctx, 0, 0) else ActivityOptions.makeBasic()
        if (bounds != null) {
            o.setLaunchBounds(virtualBounds(bounds))
            runCatching { mSetWindowingMode?.invoke(o, WM_FREEFORM) }
        }
        return o
    }

    fun launchBehindOptions(bounds: Rect): ActivityOptions {
        val o = runCatching { ActivityOptions::class.java.getMethod("makeTaskLaunchBehind").invoke(null) as ActivityOptions }
            .getOrElse { ActivityOptions.makeBasic() }
        o.setLaunchBounds(virtualBounds(bounds))
        runCatching { mSetWindowingMode?.invoke(o, WM_FREEFORM) }
        return o
    }

    // ------------------------------------------------------------------ scaled windows
    //
    // A floating app keeps the configuration it has full screen: its task stays screen-sized (one px short, so
    // the system doesn't treat it as covering Workbench) at the normal density, and the system hook scales the
    // task's surface down into the on-screen frame, like ColorOS's own zoom windows. Nothing about the app's
    // configuration changes on the way in or out, so apps are never restarted.

    /** Must match SystemHook.FRAME_MAGIC: a resizeTask call with this mode only tells the hook a task's frame. */
    private const val FRAME_MAGIC = 0x57420001
    private const val FRAME_DEFAULT = -1
    private const val FRAME_CLEAR_ALL = -2

    @Volatile var screenW = 0
    @Volatile var screenH = 0

    /** The screen-sized task bounds whose scaled picture fills [frame]. Same width as the screen, height to match. */
    fun virtualBounds(frame: Rect): Rect {
        if (screenW <= 0 || frame.isEmpty) return Rect(frame)
        val s = frame.width() / screenW.toFloat()
        var vh = (frame.height() / s).roundToInt()
        if (kotlin.math.abs(vh - screenH) <= 3 || vh >= screenH) vh = screenH - 1
        return Rect(0, 0, screenW, vh)
    }

    private fun sendFrame(taskId: Int, frame: Rect?) {
        runCatching { atm.resizeTask(taskId, frame?.let { Rect(it) } ?: Rect(), FRAME_MAGIC) }
            .onFailure { Log.w(TAG, "frame $taskId", it) }
    }

    /** Where floating tasks without a frame of their own (fresh launches) are drawn; null clears it. */
    fun setDefaultFrame(frame: Rect?) = sendFrame(FRAME_DEFAULT, frame)

    /** Switch an existing task to a floating window drawn at [frame], without changing z-order. [densityDpi] 0 = the app's normal density. */
    fun prepareFreeform(taskId: Int, frame: Rect, densityDpi: Int): Boolean {
        val tok = tokenOf(taskId) ?: return false
        sendFrame(taskId, frame)
        return transact {
            it.setWindowingMode(tok, WM_FREEFORM)
            it.setBounds(tok, virtualBounds(frame))
            it.setDensityDpi(tok, densityDpi)
        }
    }

    /** Bring a task to the top with no transition animation. Optionally as a floating window at [bounds]. */
    fun showTask(ctx: Context, taskId: Int, bounds: Rect?): Boolean {
        val ok = try {
            atm.startActivityFromRecents(taskId, freeformOptions(ctx, bounds, true).toBundle()) >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityFromRecents $taskId", t)
            false
        }
        if (ok) return true
        // Fallback: plain reorder.
        val tok = tokenOf(taskId) ?: return false
        return transact { it.reorder(tok, true) }
    }

    fun focus(taskId: Int) {
        runCatching { atm.setFocusedTask(taskId) }
    }

    fun remove(taskId: Int): Boolean = runCatching { atm.removeTask(taskId) }.getOrDefault(false)

    /** Put tasks back to normal fullscreen (inherit display), clear bounds, density overrides and every frame. */
    fun restore(ids: Collection<Int>, top: Int? = null) {
        if (ids.isEmpty() && top == null) { sendFrame(FRAME_CLEAR_ALL, null); return }
        val running = runningTasks().associateBy { it.taskId }
        transact { wct ->
            for (id in ids + listOfNotNull(top)) {
                val t = running[id] ?: continue
                val tok = token(t) ?: continue
                if (windowingMode(t) == WM_FREEFORM || id == top) {
                    wct.setWindowingMode(tok, WM_UNDEFINED)
                    wct.setBounds(tok, Rect())
                    wct.setDensityDpi(tok, 0)
                }
            }
        }
        // After the switch: a task that left freeform is no longer scaled by the hook anyway.
        sendFrame(FRAME_CLEAR_ALL, null)
    }

    fun register(l: TaskStackListener) {
        runCatching { atm.registerTaskStackListener(l) }.onFailure { Log.w(TAG, "register listener", it) }
    }

    fun unregister(l: TaskStackListener) {
        runCatching { atm.unregisterTaskStackListener(l) }
    }
}
