package dev.satya.workbench.bench

import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.roundToInt
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.AndroidUiDispatcher
import dev.satya.workbench.data.AppGroup
import dev.satya.workbench.data.Apps
import dev.satya.workbench.data.Edge
import dev.satya.workbench.data.Prefs
import dev.satya.workbench.sys.Sys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class Flyer(
    val image: ImageBitmap?,
    val icon: ImageBitmap?,
    val from: Rect,
    val to: Rect,
    val r0: Float,
    val r1: Float,
    val alphaTo: Float = 1f,
) {
    val p = Animatable(0f)
}

data class TaskItem(val taskId: Int, val pkg: String, val component: ComponentName, val label: String)

data class StripEntry(
    val key: String,
    val task: TaskItem?,
    val component: ComponentName,
    val label: String,
    val pinned: Boolean,
    /** The app in the main window (or flying to / from it): drawn as a plain card with its icon. */
    val active: Boolean = false,
) {
    val pkg: String get() = component.packageName
}

enum class Modal { SEARCH, GROUPS, NEW_GROUP, NOTIFS }

/**
 * Workbench controller.
 *
 * Z-order model: our activity is an opaque fullscreen window that shows the wallpaper and draws the strip.
 * The "main" app is a real freeform task placed on top of it at [Geo.main]. To animate anything over the
 * main area we "cover": snapshot the main task, draw that snapshot exactly where the live window is, then
 * move our task to the front (no visual change). Animations run on snapshots, and at the end the target
 * task is brought back on top at the main bounds ("uncover").
 */
class Bench(private val act: WorkbenchActivity) {
    companion object {
        @Volatile var active: Bench? = null
        val activeFlow = MutableStateFlow(false)
        private const val TAG = "Workbench"
        private val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        const val MAIN_RADIUS_DP = 26
        /** Space between the two panes of a split main window (holds the divider). */
        const val SPLIT_GAP_DP = 18
        /** Smallest pane share while dragging the divider; past it (and a little more) the pane closes. */
        const val SPLIT_MIN = 0.22f
    }

    // AndroidUiDispatcher.Main carries a Choreographer-backed MonotonicFrameClock, which Animatable needs.
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val ui = Handler(Looper.getMainLooper())
    val density = act.resources.displayMetrics.density

    var edge by mutableStateOf(Prefs.edge.value); private set
    var geo by mutableStateOf(computeGeo(Prefs.edge.value)); private set
    var mainTask by mutableStateOf<Int?>(null); private set
    var mainImage by mutableStateOf<ImageBitmap?>(null); private set
    var mainPkg by mutableStateOf<String?>(null); private set
    var tasks by mutableStateOf<List<TaskItem>>(emptyList()); private set
    val snaps = mutableStateMapOf<Int, ImageBitmap>()
    val flyers = mutableStateListOf<Flyer>()
    val hidden = mutableStateListOf<Int>()
    /** Tasks that are in the strip model but invisible while their flyer is still travelling to the slot. */
    val landing = mutableStateListOf<Int>()
    val reveal = Animatable(0f)
    var modal by mutableStateOf<Modal?>(null); private set
    var actionsFor by mutableStateOf<String?>(null)
    var handleMenu by mutableStateOf(false)
    var relocPointer by mutableStateOf<Offset?>(null)
    /** The strip shows the Shelf instead of cards. */
    var shelfOpen by mutableStateOf(false)
    /** Something is being dragged somewhere on screen (from any app): the strip turns into drop targets. */
    var dropActive by mutableStateOf(false)
    var shelfActionsFor by mutableStateOf<String?>(null)
    /** An app icon being dragged out of search: where the finger is. */
    var appDrag by mutableStateOf<dev.satya.workbench.data.AppEntry?>(null); private set
    var appDragPos by mutableStateOf(Offset.Zero); private set

    // ------------------------------------------------------------------ split main window
    /** Lower pane of a split main window ([mainTask] is the upper one). */
    var splitTask by mutableStateOf<Int?>(null); private set
    var splitPkg by mutableStateOf<String?>(null); private set
    var splitImage by mutableStateOf<ImageBitmap?>(null); private set
    /** App launched into the lower pane that has not shown up yet. */
    var pendingSplitPkg by mutableStateOf<String?>(null); private set
    private var pendingSplitSince = 0L
    /** Upper pane's share of the main area. */
    var splitRatio by mutableStateOf(0.5f); private set
    /** Search was opened to pick the app for the lower pane. */
    var splitPick by mutableStateOf(false); private set
    val isSplit: Boolean get() = splitTask != null || pendingSplitPkg != null

    /** Upper and lower pane at [ratio]. Same width as the main window, so the apps keep the main window's density. */
    fun splitRects(ratio: Float = splitRatio): Pair<Rect, Rect> {
        val m = geo.main
        val gap = geo.dp(SPLIT_GAP_DP)
        val cut = m.top + ((m.height() - gap) * ratio).roundToInt()
        return Rect(m.left, m.top, m.right, cut) to Rect(m.left, cut + gap, m.right, m.bottom)
    }

    /** Where the main app is on screen right now (the upper pane when split). */
    val mainShown: Rect get() = if (isSplit) splitRects().first else geo.main
    /** [mainShown] as it was when the bench last covered the apps: where outgoing flyers start. */
    private var lastShown = Rect()

    enum class DropTarget { MAIN, SPLIT, STRIP }
    /** The part of an occupied main window that splits it when an app is dropped there. */
    fun splitZone(): Rect = Rect(geo.main).apply { top = geo.main.top + (geo.main.height() * 0.58f).roundToInt() }
    fun dropTargetAt(p: Offset): DropTarget? = when {
        mainTask != null && splitZone().contains(p.x.toInt(), p.y.toInt()) -> DropTarget.SPLIT
        geo.main.contains(p.x.toInt(), p.y.toInt()) -> DropTarget.MAIN
        Rect(geo.strip).apply { inset(0, -geo.dp(12)) }.contains(p.x.toInt(), p.y.toInt()) -> DropTarget.STRIP
        else -> null
    }
    val listState = LazyListState()
    val cardRects = HashMap<String, Rect>()

    private var covered = true
    private var busy = false
    private var exiting = false
    private val session = LinkedHashSet<Int>()
    private val behindPkgs = HashSet<String>()
    private var pendingPkg by mutableStateOf<String?>(null)
    /** Strip order of unpinned tasks. Fixed for the session: switching apps never reorders cards; new tasks go last. */
    private val order = ArrayList<Int>()
    private var pendingSince = 0L
    private var suppressAdoptUntil = 0L
    private var reloadJob: Job? = null
    private var relocResult: CompletableDeferred<Edge?>? = null

    val screenRadius: Float = act.display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
    /** Corner radius of the floating main window; the system hook rounds the task surface with the same value. */
    val mainRadius: Float = MAIN_RADIUS_DP * density
    val cardRadius: Float get() = 12 * density

    /** Perspective tilt for a flyer end point: strip cards are tilted, everything else is flat. */
    fun tiltOf(r: Rect): Float = if (geo.isCard(r)) geo.tilt else 0f

    private val listener = object : TaskStackListener() {
        override fun onTaskStackChanged() { ui.post { scheduleReload() } }
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) { ui.post { onCreated(taskId, componentName) } }
        override fun onTaskRemoved(taskId: Int) { ui.post { onRemoved(taskId) } }
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) { ui.post { onFront(taskInfo) } }
        override fun onTaskMovedToBack(taskInfo: ActivityManager.RunningTaskInfo) {
            ui.post {
                if (taskInfo.taskId == mainTask && !busy) onMainGone()
                else if (taskInfo.taskId == splitTask && !busy && !covered) onSplitGone()
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun computeGeo(e: Edge): Geo {
        val m = act.windowManager.currentWindowMetrics
        val ins = m.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Geo(m.bounds.width(), m.bounds.height(), e, ins, density)
    }

    fun geoFor(e: Edge): Geo = computeGeo(e)

    fun setBlur(px: Int) = act.setBlur(px)

    /** Haptic feedback through the window (the OnePlus linear motor plays the system effects). */
    fun buzz(kind: Int) {
        if (!Prefs.haptics.value) return
        act.window?.decorView?.performHapticFeedback(kind)
    }

    /** Same layout as full screen: shrink the density by exactly the window's scale. */
    private fun densityFor(bounds: Rect): Int {
        val dpi = act.resources.displayMetrics.densityDpi
        return (dpi * bounds.width() / geo.w.toFloat()).roundToInt().coerceAtLeast(96)
    }

    /** [densityFor] with the app's own zoom applied: a higher dpi draws the same layout bigger. */
    private fun dpiOf(id: Int, bounds: Rect): Int {
        val pkg = when (id) { mainTask -> mainPkg; splitTask -> splitPkg; else -> pkgOfTask(id) }
        return (densityFor(bounds) * Prefs.zoom(pkg)).roundToInt().coerceAtLeast(96)
    }

    private fun icon(pkg: String?): ImageBitmap? = pkg?.let { Apps.cachedIcon(it) ?: Apps.icon(act, it) }

    private fun pkgOfTask(id: Int): String? = tasks.firstOrNull { it.taskId == id }?.pkg

    private fun pinIndex(pkg: String?): Int {
        if (pkg == null) return -1
        return Prefs.pinned.value.indexOfFirst { ComponentName.unflattenFromString(it)?.packageName == pkg }
    }

    /** Slot a card for [pkg] will occupy once it lands in the strip. */
    private fun slotFor(pkg: String?): Rect {
        val list = entries(Prefs.pinned.value)
        val pi = pinIndex(pkg)
        val idx = if (pi >= 0) {
            list.count { it.pinned && pinIndex(it.pkg) in 0 until pi }
        } else list.count { it.pinned }
        return geo.slot(idx)
    }

    private fun keyOfTask(id: Int): String =
        entries(Prefs.pinned.value).firstOrNull { it.task?.taskId == id }?.key ?: "t$id"

    /** On-screen rect of a strip card, scrolling the strip first if the card is out of view. */
    private suspend fun cardRectFor(key: String): Rect {
        val list = entries(Prefs.pinned.value)
        val idx = list.indexOfFirst { it.key == key }
        if (idx < 0) return geo.slot(list.size)
        val li = listState.layoutInfo
        val info = li.visibleItemsInfo.firstOrNull { it.index == idx }
        if (info == null || info.offset < li.viewportStartOffset || info.offset + info.size > li.viewportEndOffset) {
            runCatching { listState.animateScrollToItem(idx) }
            frames(2)
        }
        return cardRects[key]?.let { Rect(it) } ?: geo.slot(idx)
    }

    private fun shrink(r: Rect, f: Float): Rect {
        val dx = (r.width() * (1 - f) / 2).toInt()
        val dy = (r.height() * (1 - f) / 2).toInt()
        return Rect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
    }

    // ------------------------------------------------------------------ strip model

    fun entries(pins: List<String>): List<StripEntry> {
        val hid = hidden.toHashSet()
        val main = mainTask
        val pend = pendingPkg
        val live = landing.toHashSet()
        val lower = splitTask
        val pendLower = pendingSplitPkg
        fun isActive(id: Int) = id == main || id == lower || id in live
        val visible = tasks.filter { it.taskId !in hid }
        val used = HashSet<Int>()
        val out = ArrayList<StripEntry>()
        for (key in pins) {
            val cn = ComponentName.unflattenFromString(key) ?: continue
            if (tasks.any { it.pkg == cn.packageName && it.taskId in hid }) continue
            val t = visible.firstOrNull { it.pkg == cn.packageName && it.taskId !in used }
            if (t != null) used += t.taskId
            val active = if (t != null) isActive(t.taskId) else pend == cn.packageName || pendLower == cn.packageName
            out += StripEntry(if (t != null) "t" + t.taskId else "p" + key, t, cn, t?.label ?: Apps.label(act, cn.packageName), true, active)
        }
        for (t in visible) if (t.taskId !in used) out += StripEntry("t" + t.taskId, t, t.component, t.label, false, isActive(t.taskId))
        return out
    }

    fun scheduleReload(delayMs: Long = 120) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(delayMs)
            reloadNow()
        }
    }

    suspend fun reloadNow() {
        val own = act.packageName
        val list = withContext(Dispatchers.IO) {
            Sys.recentTasks().mapNotNull { r ->
                val cn = r.baseActivity ?: r.baseIntent?.component ?: return@mapNotNull null
                if (cn.packageName == own) return@mapNotNull null
                val type = Sys.activityType(r)
                if (type == Sys.ACT_HOME || type == Sys.ACT_RECENTS) return@mapNotNull null
                if (r.baseIntent?.hasCategory(Intent.CATEGORY_HOME) == true) return@mapNotNull null
                val label = r.taskDescription?.label?.toString()?.takeIf { it.isNotBlank() } ?: Apps.label(act, cn.packageName)
                TaskItem(r.taskId, cn.packageName, cn, label)
            }
        }
        val ids = list.mapTo(HashSet()) { it.taskId }
        order.retainAll(ids)
        for (t in list) if (t.taskId !in order) order += t.taskId
        val pos = order.withIndex().associate { it.value to it.index }
        tasks = list.sortedBy { pos[it.taskId] ?: Int.MAX_VALUE }
        snaps.keys.toList().forEach { if (it !in ids && it != mainTask) snaps.remove(it) }
        val m = mainTask
        if (m != null && m !in ids && !busy && pendingPkg == null) {
            // The main task disappeared without us noticing (e.g. finished).
            val running = withContext(Dispatchers.IO) { Sys.runningTasks().any { it.taskId == m } }
            if (!running) onMainGone()
        }
        if (pendingSplitPkg != null && SystemClock.uptimeMillis() - pendingSplitSince > 6000) {
            pendingSplitPkg = null
            if (splitTask == null) mainTask?.let { m ->
                val r = Rect(geo.main)
                withContext(Dispatchers.IO) { Sys.prepareFreeform(m, r, dpiOf(m, r)) }
            }
        }
        if (pendingPkg != null && SystemClock.uptimeMillis() - pendingSince > 5000) {
            pendingPkg = null
            if (mainTask == null) mainPkg = null
        }
    }

    suspend fun ensureSnapshot(id: Int) {
        if (snaps.containsKey(id)) return
        val b = withContext(Dispatchers.IO) { Sys.snapshot(id, low = true) } ?: return
        snaps[id] = b.asImageBitmap()
    }

    // ------------------------------------------------------------------ choreography primitives

    private fun op(block: suspend CoroutineScope.() -> Unit) {
        if (busy || exiting) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "operation failed", t)
            } finally {
                busy = false
            }
        }
    }

    /**
     * A card is about to be inserted at the head of the strip. LazyList anchors its scroll position to the
     * first visible key, which would push the new card above the viewport; pin the list to the top instead
     * so the other cards slide down and open the slot the flyer lands in.
     */
    private fun stickToTop() {
        runCatching { listState.requestScrollToItem(0) }
    }

    private suspend fun frames(n: Int) = repeat(n) { withFrameNanos { } }

    private fun persistSession() {
        val s = session.toSet()
        thread { Prefs.session = s }
    }

    /** Make our window the top one, with a fresh snapshot of the main app drawn where it was. */
    private suspend fun cover(collapse: Boolean = true) {
        lastShown = Rect(mainShown)
        if (covered) {
            if (collapse) collapseSplit()
            return
        }
        val id = mainTask
        if (id != null) {
            val img = withContext(Dispatchers.IO) { Sys.snapshot(id, fresh = true) }?.asImageBitmap()
            if (img != null) {
                mainImage = img
                snaps[id] = img
            }
        }
        splitTask?.let { s ->
            val img = withContext(Dispatchers.IO) { Sys.snapshot(s, fresh = true) }?.asImageBitmap()
            if (img != null) {
                splitImage = img
                snaps[s] = img
            }
        }
        frames(2)
        withContext(Dispatchers.IO) { Sys.showTask(act, act.taskId, null) }
        covered = true
        frames(1)
        if (collapse) collapseSplit()
    }

    /** Anything that changes the main window ends the split: the lower app flies back to its card. */
    private fun collapseSplit() {
        pendingSplitPkg = null
        val s = splitTask ?: return
        val from = splitRects().second
        val img = splitImage
        val pkg = splitPkg
        splitTask = null
        splitPkg = null
        splitImage = null
        splitRatio = 0.5f
        landing += s
        scope.launch {
            val f = Flyer(img, icon(pkg), from, cardRectFor(keyOfTask(s)), mainRadius, cardRadius)
            flyers += f
            f.p.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f))
            landing.remove(s)
            frames(2)
            flyers.remove(f)
        }
    }

    /** Put task [id] on top of us as a floating window at the main bounds. */
    private suspend fun uncover(id: Int): Boolean {
        val isMain = id == mainTask
        val bounds = if (isMain && isSplit) splitRects().first else Rect(geo.main)
        val lower = splitTask.takeIf { isMain }
        val lowerRect = splitRects().second
        val ok = withContext(Dispatchers.IO) {
            Sys.prepareFreeform(id, bounds, dpiOf(id, bounds))
            val shown = Sys.showTask(act, id, bounds)
            if (lower != null) {
                Sys.prepareFreeform(lower, lowerRect, dpiOf(lower, lowerRect))
                Sys.showTask(act, lower, lowerRect)
            }
            if (shown) Sys.focus(id)
            shown
        }
        if (ok) {
            covered = false
            session += id
            persistSession()
        }
        return ok
    }

    private suspend fun animate(vararg fl: Flyer, withReveal: Float? = null) = coroutineScope {
        if (withReveal != null) launch { reveal.animateTo(withReveal, tween(320, easing = EaseOut)) }
        for (f in fl) launch { f.p.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
    }

    // ------------------------------------------------------------------ lifecycle

    fun start(task: Int?, snap: ImageBitmap?) {
        active = this
        activeFlow.value = true
        Sys.register(listener)
        scope.launch(Dispatchers.IO) { Apps.refresh(act) }
        // Clipboard history fallback, in case a copy happened while the listener couldn't read it.
        scope.launch(Dispatchers.IO) {
            val cm = act.getSystemService(android.content.ClipboardManager::class.java)
            dev.satya.workbench.data.Shelf.ingestClip(act, runCatching { cm.primaryClip }.getOrNull())
        }
        scope.launch { reloadNow() }
        scope.launch {
            androidx.compose.runtime.snapshotFlow { mainTask to mainPkg }.collect { (t, p) ->
                if (t != null && p != null) withContext(Dispatchers.IO) { dev.satya.workbench.data.Usage.used(p) }
            }
        }
        scope.launch {
            var prev = dev.satya.workbench.data.Notifs.counts.value
            dev.satya.workbench.data.Notifs.counts.collect { now ->
                val grew = now.filter { (p, c) -> c > (prev[p] ?: 0) }.keys
                prev = now
                if (grew.isEmpty()) return@collect
                // The app redraws a moment after posting; then its card shows the new content.
                delay(700)
                for (t in tasks) if (t.pkg in grew && t.taskId != mainTask) {
                    val bmp = withContext(Dispatchers.IO) { Sys.snapshot(t.taskId, low = true) } ?: continue
                    snaps[t.taskId] = bmp.asImageBitmap()
                }
            }
        }
        if (task == null) {
            op { reveal.animateTo(1f, tween(360, easing = EaseOut)) }
            return
        }
        // First frame must show the app exactly as it was, so the flyer exists before setContent draws.
        val f = Flyer(snap, null, Rect(geo.full), Rect(geo.main), screenRadius, mainRadius)
        flyers += f
        mainTask = task
        session += task
        persistSession()
        op {
            val pkg = withContext(Dispatchers.IO) { Sys.runningTasks().firstOrNull { it.taskId == task }?.let { Sys.packageOf(it) } }
            mainPkg = pkg
            val img = snap ?: withContext(Dispatchers.IO) { Sys.snapshot(task) }?.asImageBitmap()
            if (snap == null && img != null) {
                flyers.remove(f)
                val f2 = Flyer(img, null, Rect(geo.full), Rect(geo.main), screenRadius, mainRadius)
                flyers += f2
                launch(Dispatchers.IO) { Sys.prepareFreeform(task, Rect(geo.main), dpiOf(task, Rect(geo.main))) }
                animate(f2, withReveal = 1f)
                mainImage = img
                uncover(task)
                frames(3)
                flyers.remove(f2)
                return@op
            }
            launch(Dispatchers.IO) { Sys.prepareFreeform(task, Rect(geo.main), dpiOf(task, Rect(geo.main))) }
            animate(f, withReveal = 1f)
            mainImage = img
            uncover(task)
            frames(3)
            flyers.remove(f)
        }
    }

    fun dispose() {
        thread { dev.satya.workbench.data.Usage.endSession() }
        scope.cancel()
        Sys.unregister(listener)
        if (active === this) active = null
        activeFlow.value = false
        if (!exiting && session.isNotEmpty()) {
            val s = session.toList()
            thread { Sys.restore(s); Prefs.session = emptySet() }
        }
    }

    private fun finishQuietly() {
        exiting = true
        active = null
        activeFlow.value = false
        act.finish()
        act.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun exitSilently() {
        if (exiting) return
        exiting = true
        val s = session.toList()
        session.clear()
        thread { Sys.restore(s); Prefs.session = emptySet() }
        finishQuietly()
    }

    fun toggleFromTrigger() {
        if (mainTask != null) expand() else exitHome()
    }

    // ------------------------------------------------------------------ task events

    private fun onCreated(taskId: Int, cn: ComponentName?) {
        val pkg = cn?.packageName ?: return
        if (behindPkgs.remove(pkg)) {
            session += taskId
            persistSession()
            // The system cascades a launch that would sit on another window's bounds: snap it back to the main bounds.
            val b = Rect(geo.main)
            scope.launch(Dispatchers.IO) { Sys.prepareFreeform(taskId, b, dpiOf(taskId, b)) }
        }
        scheduleReload()
    }

    private fun onRemoved(taskId: Int) {
        session.remove(taskId)
        snaps.remove(taskId)
        if (taskId == mainTask) onMainGone()
        else if (taskId == splitTask) onSplitGone()
        scheduleReload(0)
    }

    /** The lower pane's app went away: the upper one takes the whole main window again. */
    private fun onSplitGone() {
        splitTask = null
        splitPkg = null
        splitImage = null
        splitRatio = 0.5f
        val m = mainTask ?: return
        val b = Rect(geo.main)
        val dpi = densityFor(b)
        scope.launch(Dispatchers.IO) { Sys.prepareFreeform(m, b, dpiOf(m, b)) }
        scheduleReload(0)
    }

    private fun onMainGone() {
        // Split: the lower app moves up and fills the main window.
        val s = splitTask
        if (s != null && !exiting) {
            mainTask = s
            mainPkg = splitPkg
            mainImage = splitImage
            splitTask = null
            splitPkg = null
            splitImage = null
            splitRatio = 0.5f
            val b = Rect(geo.main)
            scope.launch(Dispatchers.IO) { Sys.prepareFreeform(s, b, dpiOf(s, b)) }
            scheduleReload(0)
            return
        }
        pendingSplitPkg = null
        mainTask = null
        mainImage = null
        mainPkg = null
        covered = true
        handleMenu = false
        scheduleReload(0)
        promoteNextMain()
    }

    /** The main app went away: the top-most session window still floating at the main bounds takes its place. */
    private fun promoteNextMain() {
        if (exiting) return
        val live = session.toSet()
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                Sys.runningTasks().firstOrNull {
                    it.taskId in live && Sys.isVisible(it) && Sys.windowingMode(it) == Sys.WM_FREEFORM
                }
            } ?: return@launch
            if (mainTask != null || exiting) return@launch
            mainTask = next.taskId
            mainPkg = Sys.packageOf(next)
            mainImage = snaps[next.taskId]
            covered = false
            scheduleReload(0)
        }
    }

    private fun onFront(info: ActivityManager.RunningTaskInfo) {
        if (exiting) return
        val id = info.taskId
        if (id == act.taskId) return
        val type = Sys.activityType(info)
        if (type == Sys.ACT_HOME || type == Sys.ACT_RECENTS) {
            exitSilently()
            return
        }
        val pkg = Sys.packageOf(info)
        if (id == splitTask) {
            covered = false
            return
        }
        val pendingSplit = pendingSplitPkg
        if (pendingSplit != null && pkg == pendingSplit && mainTask != null) {
            pendingSplitPkg = null
            splitTask = id
            splitPkg = pkg
            covered = false
            session += id
            persistSession()
            val r = splitRects().second
            val dpi = densityFor(r)
            scope.launch(Dispatchers.IO) { Sys.prepareFreeform(id, r, dpiOf(id, r)) }
            scheduleReload()
            return
        }
        val pending = pendingPkg
        if (pending != null && pkg == pending) {
            pendingPkg = null
            mainTask = id
            mainPkg = pkg
            covered = false
            session += id
            persistSession()
            // Always pin the bounds: freeform launches that land on another window's exact bounds get cascaded.
            val b = Rect(geo.main)
            scope.launch(Dispatchers.IO) { Sys.prepareFreeform(id, b, dpiOf(id, b)) }
            scheduleReload()
            return
        }
        if (id == mainTask) {
            covered = false
            return
        }
        if (busy || modal != null || SystemClock.uptimeMillis() < suppressAdoptUntil) return
        if (type != Sys.ACT_STANDARD) return
        // Another app came to the front (opened from the main app, a notification, ...): it becomes the main window.
        mainTask = id
        mainPkg = pkg
        mainImage = snaps[id]
        covered = false
        session += id
        persistSession()
        val b = Rect(geo.main)
        scope.launch(Dispatchers.IO) { Sys.prepareFreeform(id, b, dpiOf(id, b)) }
        scheduleReload()
    }

    // ------------------------------------------------------------------ user actions

    fun onBackdropTap() {
        if (actionsFor != null || handleMenu) {
            actionsFor = null
            handleMenu = false
            return
        }
        exitHome()
    }

    fun onCardTap(e: StripEntry) {
        if (actionsFor != null) {
            actionsFor = null
            return
        }
        val t = e.task
        if (t != null) switchTo(t.taskId, e.key) else openApp(e.component, null, cardRects[e.key])
    }

    fun switchTo(id: Int, fromKey: String?) = op { switchImpl(id, fromKey?.let { cardRects[it] }) }

    /** Handle swipe: the next (dir = 1) or previous (-1) running app in strip order slides into the main window. */
    fun quickSwitch(dir: Int) {
        val running = entries(Prefs.pinned.value).mapNotNull { it.task?.taskId }
        if (running.isEmpty()) return
        val cur = running.indexOf(mainTask)
        val next = if (cur < 0) running.first() else running.getOrNull(cur + dir) ?: run {
            buzz(android.view.HapticFeedbackConstants.REJECT)
            return
        }
        op { switchImpl(next, null, slide = dir) }
    }

    private suspend fun switchImpl(id: Int, from: Rect?, slide: Int = 0) = coroutineScope {
        actionsFor = null
        handleMenu = false
        if (id != mainTask) buzz(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        if (id == mainTask) {
            if (covered) uncover(id)
            return@coroutineScope
        }
        if (id == splitTask) {
            swapImpl()
            return@coroutineScope
        }
        val old = mainTask
        val oldPkg = mainPkg
        cover()
        val item = tasks.firstOrNull { it.taskId == id }
        val newImg = snaps[id] ?: withContext(Dispatchers.IO) { Sys.snapshot(id) }?.asImageBitmap()
        // Morph in place: the new app sits in the main area right away and the old window fades out over it.
        // Nothing travels; the cards just swap states (old one shows its preview again, new one its icon).
        val main = Rect(geo.main)
        val fl = ArrayList<Flyer>()
        // Nothing in the main area yet: the card's preview grows out of the strip into it instead.
        val grow = old == null
        val sliding = slide != 0 && old != null
        val shift = (main.width() * 0.55f).roundToInt() * slide
        val start = when {
            grow -> cardRects["t$id"]?.let { Rect(it) } ?: from ?: cardRectFor("t$id")
            sliding -> Rect(main).apply { offset(shift, 0) }
            else -> main
        }
        // Quick switch: the old window slides out the opposite way, a little smaller, as the new one slides in.
        val oldTo = if (sliding) shrink(main, 0.9f).apply { offset(-shift, 0) } else Rect(main)
        if (sliding) fl += Flyer(mainImage, icon(oldPkg), Rect(lastShown), oldTo, mainRadius, mainRadius, alphaTo = 0f)
        fl += Flyer(newImg, icon(item?.pkg), start, Rect(main), if (grow) cardRadius else mainRadius, mainRadius)
        val fade = if (old != null && !sliding) Flyer(mainImage, icon(oldPkg), Rect(lastShown), Rect(main), mainRadius, mainRadius, alphaTo = 0f) else null
        if (fade != null) fl += fade
        mainTask = id
        mainPkg = item?.pkg
        mainImage = null
        flyers.addAll(fl)
        val bounds = Rect(geo.main)
        session += id
        persistSession()
        launch(Dispatchers.IO) { Sys.prepareFreeform(id, bounds, dpiOf(id, bounds)) }
        if (grow || sliding) animate(*fl.toTypedArray()) else {
            fl[0].p.snapTo(1f)
            fade?.p?.animateTo(1f, tween(300, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        }
        mainImage = newImg
        uncover(id)
        frames(3)
        flyers.removeAll(fl)
        scheduleReload(0)
    }

    fun openApp(cn: ComponentName, user: UserHandle?, from: Rect?) = op { openImpl(cn, user, from) }

    private suspend fun openImpl(cn: ComponentName, user: UserHandle?, from: Rect?) {
        if (modal != null) closeModalNow(restore = false)
        actionsFor = null
        handleMenu = false
        val existing = tasks.firstOrNull { it.pkg == cn.packageName }
        if (existing != null) {
            switchImpl(existing.taskId, from)
            return
        }
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        val launchEntry = Apps.all.value.firstOrNull { it.component == cn }
            ?: Apps.all.value.firstOrNull { it.pkg == cn.packageName }
        val target = launchEntry?.component ?: cn
        val targetUser = user ?: launchEntry?.user
        val old = mainTask
        val oldPkg = mainPkg
        cover()
        val fl = ArrayList<Flyer>()
        if (old != null) {
            val dest = cardRectFor(keyOfTask(old))
            landing += old
            fl += Flyer(mainImage, icon(oldPkg), Rect(lastShown), dest, mainRadius, cardRadius)
        }
        val ic = withContext(Dispatchers.IO) { Apps.icon(act, target.packageName) }
        val startRect = from ?: shrink(geo.main, 0.3f)
        fl += Flyer(null, ic, startRect, Rect(geo.main), cardRadius, mainRadius)
        mainTask = null
        mainPkg = target.packageName
        mainImage = null
        pendingPkg = target.packageName
        pendingSince = SystemClock.uptimeMillis()
        flyers.addAll(fl)
        animate(*fl.toTypedArray())
        if (old != null) landing.remove(old)
        val opts = Sys.freeformOptions(act, Rect(geo.main), noAnim = true).toBundle()
        val launched = runCatching {
            if (targetUser == null || targetUser == Apps.myUser) {
                act.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    opts
                )
            } else {
                act.getSystemService(LauncherApps::class.java).startMainActivity(target, targetUser, null, opts)
            }
        }.onFailure { Log.w(TAG, "launch $target", it) }.isSuccess
        if (!launched) {
            pendingPkg = null
            mainPkg = null
        }
        frames(3)
        flyers.removeAll(fl)
        scheduleReload()
    }

    /** A notification opens in the main window: its app takes the main spot, then the notification's own intent runs there. */
    fun openNotif(n: dev.satya.workbench.data.Notif, from: Rect?) = op {
        val pi = n.intent
        val existing = tasks.firstOrNull { it.pkg == n.pkg }
        if (pi == null || existing != null) {
            // App already on the bench (or nothing to send): bring it into the main window first.
            openImpl(existing?.component ?: Apps.all.value.firstOrNull { it.pkg == n.pkg }?.component ?: return@op, null, from)
            if (pi == null) return@op
            delay(250)
        } else {
            if (modal != null) closeModalNow(restore = false)
            buzz(android.view.HapticFeedbackConstants.CONFIRM)
            val old = mainTask
            val oldPkg = mainPkg
            cover()
            val fl = ArrayList<Flyer>()
            if (old != null) {
                landing += old
                fl += Flyer(mainImage, icon(oldPkg), Rect(lastShown), cardRectFor(keyOfTask(old)), mainRadius, cardRadius)
            }
            fl += Flyer(null, icon(n.pkg), from ?: shrink(geo.main, 0.3f), Rect(geo.main), cardRadius, mainRadius)
            mainTask = null
            mainPkg = n.pkg
            mainImage = null
            pendingPkg = n.pkg
            pendingSince = SystemClock.uptimeMillis()
            flyers.addAll(fl)
            animate(*fl.toTypedArray())
            if (old != null) landing.remove(old)
            frames(3)
            flyers.removeAll(fl)
        }
        val opts = Sys.freeformOptions(act, Rect(geo.main), noAnim = true)
        runCatching { opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED) }
        val ok = runCatching { pi.send(act, 0, null, null, null, null, opts.toBundle()) }
            .onFailure { Log.w(TAG, "notification intent", it) }.isSuccess
        if (!ok && existing == null) { pendingPkg = null; mainPkg = null }
        if (ok && n.autoCancel) dev.satya.workbench.data.Notifs.dismiss?.invoke(n.key)
        scheduleReload()
    }

    fun minimize() = op {
        val old = mainTask ?: return@op
        handleMenu = false
        buzz(android.view.HapticFeedbackConstants.GESTURE_END)
        cover()
        val dest = cardRectFor(keyOfTask(old))
        landing += old
        val f = Flyer(mainImage, icon(mainPkg), Rect(lastShown), dest, mainRadius, cardRadius)
        mainTask = null
        mainImage = null
        mainPkg = null
        flyers += f
        animate(f)
        landing.remove(old)
        frames(2)
        flyers.remove(f)
        scheduleReload(0)
    }

    fun closeMain() = op {
        val id = mainTask ?: return@op
        handleMenu = false
        buzz(android.view.HapticFeedbackConstants.REJECT)
        cover()
        val f = Flyer(mainImage, icon(mainPkg), Rect(lastShown), shrink(geo.main, 0.86f), mainRadius, cardRadius, alphaTo = 0f)
        mainTask = null
        mainImage = null
        mainPkg = null
        flyers += f
        launch(Dispatchers.IO) { Sys.remove(id) }
        f.p.animateTo(1f, tween(240, easing = EaseOut))
        flyers.remove(f)
        session.remove(id)
        persistSession()
        snaps.remove(id)
        scheduleReload(0)
    }

    fun closeEntry(e: StripEntry) {
        actionsFor = null
        val t = e.task
        if (t == null) {
            if (e.pinned) Prefs.togglePin(e.component.flattenToString())
            return
        }
        buzz(android.view.HapticFeedbackConstants.REJECT)
        hidden += t.taskId
        scope.launch {
            withContext(Dispatchers.IO) { Sys.remove(t.taskId) }
            session.remove(t.taskId)
            persistSession()
            snaps.remove(t.taskId)
            reloadNow()
            hidden.remove(t.taskId)
        }
    }

    fun pinKeyFor(pkg: String, cn: ComponentName?): String =
        Apps.all.value.firstOrNull { it.pkg == pkg }?.key ?: (cn ?: ComponentName(pkg, pkg)).flattenToString()

    fun togglePin(e: StripEntry) {
        val existing = Prefs.pinned.value.firstOrNull { ComponentName.unflattenFromString(it)?.packageName == e.pkg }
        buzz(if (existing == null) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.TOGGLE_OFF)
        Prefs.togglePin(existing ?: pinKeyFor(e.pkg, e.component))
    }

    fun togglePinMain() {
        val pkg = mainPkg ?: return
        val existing = Prefs.pinned.value.firstOrNull { ComponentName.unflattenFromString(it)?.packageName == pkg }
        buzz(if (existing == null) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.TOGGLE_OFF)
        Prefs.togglePin(existing ?: pinKeyFor(pkg, null))
        handleMenu = false
    }

    fun isMainPinned(): Boolean = pinIndex(mainPkg) >= 0

    /** Main app (or a strip card) goes fullscreen and Workbench closes. */
    fun expand(taskId: Int? = null, fromKey: String? = null) = op {
        val target = taskId ?: mainTask ?: return@op
        handleMenu = false
        actionsFor = null
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        cover()
        val isMain = target == mainTask
        val from = if (isMain) Rect(lastShown) else (fromKey?.let { cardRects[it] } ?: geo.slot(0))
        val img = if (isMain) mainImage else snaps[target] ?: withContext(Dispatchers.IO) { Sys.snapshot(target) }?.asImageBitmap()
        val f = Flyer(img, icon(pkgOfTask(target) ?: mainPkg), from, Rect(geo.full), if (isMain) mainRadius else cardRadius, screenRadius)
        if (isMain) mainImage = null else hidden += target
        flyers += f
        animate(f, withReveal = 0f)
        exiting = true
        val s = session.toList()
        session.clear()
        withContext(Dispatchers.IO) {
            Sys.restore(s, top = target)
            Sys.showTask(act, target, null)
        }
        thread { Prefs.session = emptySet() }
        finishQuietly()
    }

    fun openFull(e: StripEntry) {
        val t = e.task
        if (t != null) {
            expand(t.taskId, e.key)
            return
        }
        op {
            exiting = true
            val s = session.toList()
            session.clear()
            withContext(Dispatchers.IO) { Sys.restore(s) }
            thread { Prefs.session = emptySet() }
            val entry = Apps.all.value.firstOrNull { it.pkg == e.pkg }
            runCatching {
                act.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(entry?.component ?: e.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            }
            finishQuietly()
        }
    }

    /** Tap on empty space: everything goes back to normal, home screen. */
    fun exitHome() = op {
        handleMenu = false
        actionsFor = null
        cover()
        val f = mainTask?.let {
            Flyer(mainImage, icon(mainPkg), Rect(lastShown), shrink(geo.main, 0.9f), mainRadius, cardRadius, alphaTo = 0f)
        }
        mainImage = null
        mainPkg = null
        if (f != null) flyers += f
        if (f != null) animate(f, withReveal = 0f) else reveal.animateTo(0f, tween(260, easing = EaseOut))
        exiting = true
        val s = session.toList()
        session.clear()
        withContext(Dispatchers.IO) { Sys.restore(s) }
        thread { Prefs.session = emptySet() }
        runCatching {
            act.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeCustomAnimation(act, 0, android.R.anim.fade_out).toBundle()
            )
        }
        finishQuietly()
    }

    /** Settings icon: Workbench closes and the settings screen opens full screen. */
    fun openSettings() = op {
        actionsFor = null
        handleMenu = false
        exiting = true
        val s = session.toList()
        session.clear()
        withContext(Dispatchers.IO) { Sys.restore(s) }
        thread { Prefs.session = emptySet() }
        runCatching {
            act.startActivity(
                Intent(act, dev.satya.workbench.ui.SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeCustomAnimation(act, android.R.anim.fade_in, 0).toBundle()
            )
        }
        finishQuietly()
    }

    // ------------------------------------------------------------------ split

    /** Handle menu "Split": search opens and the app picked there goes into the lower pane. */
    fun pickForSplit() {
        if (mainTask == null) return
        splitPick = true
        openModal(Modal.SEARCH)
    }

    /** An app tapped in search: opened normally, or into the lower pane when picking for a split. */
    fun pickApp(app: dev.satya.workbench.data.AppEntry, from: Rect?) {
        if (splitPick) op { splitImpl(app, from) } else openApp(app.component, app.user, from)
    }

    private suspend fun splitImpl(app: dev.satya.workbench.data.AppEntry, from: Rect?) {
        if (modal != null) closeModalNow(restore = false)
        splitPick = false
        actionsFor = null
        handleMenu = false
        val m = mainTask
        if (m == null || app.pkg == mainPkg) {
            openImpl(app.component, app.user, from)
            return
        }
        if (app.pkg == splitPkg) {
            uncover(m)
            return
        }
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        val wasSplit = isSplit
        cover(collapse = false)
        val oldTop = Rect(lastShown)
        // A different app already in the lower pane goes back to its card.
        val oldLower = splitTask
        val fl = ArrayList<Flyer>()
        if (oldLower != null) {
            landing += oldLower
            fl += Flyer(splitImage, icon(splitPkg), splitRects().second, cardRectFor(keyOfTask(oldLower)), mainRadius, cardRadius)
        }
        splitTask = null
        splitImage = null
        splitPkg = null
        if (!wasSplit) splitRatio = Prefs.splitRatio(mainPkg, app.pkg) ?: 0.5f
        val (top, bottom) = splitRects()
        val existing = tasks.firstOrNull { it.pkg == app.pkg }
        val img = existing?.let { snaps[it.taskId] ?: withContext(Dispatchers.IO) { Sys.snapshot(it.taskId) }?.asImageBitmap() }
        val start = from ?: existing?.let { cardRects[keyOfTask(it.taskId)]?.let(::Rect) } ?: shrink(bottom, 0.3f)
        val topImg = mainImage
        // The main app shrinks into the upper pane while the new one grows into the lower.
        fl += Flyer(topImg, icon(mainPkg), oldTop, Rect(top), mainRadius, mainRadius)
        fl += Flyer(img, icon(app.pkg), start, Rect(bottom), cardRadius, mainRadius)
        mainImage = null
        if (existing != null) {
            splitTask = existing.taskId
            splitPkg = existing.pkg
        } else {
            pendingSplitPkg = app.pkg
            pendingSplitSince = SystemClock.uptimeMillis()
        }
        flyers.addAll(fl)
        withContext(Dispatchers.IO) {
            Sys.prepareFreeform(m, top, dpiOf(m, top))
            existing?.let { Sys.prepareFreeform(it.taskId, bottom, dpiOf(it.taskId, bottom)) }
        }
        animate(*fl.toTypedArray())
        if (oldLower != null) landing.remove(oldLower)
        mainImage = topImg
        splitImage = img
        if (existing != null) {
            session += existing.taskId
            persistSession()
        }
        uncover(m)
        if (existing == null) {
            behindPkgs += app.pkg
            runCatching {
                val opts = Sys.freeformOptions(act, Rect(bottom), noAnim = true).toBundle()
                if (app.user == Apps.myUser) act.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    opts
                ) else act.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, null, opts)
            }.onFailure {
                Log.w(TAG, "split launch ${app.pkg}", it)
                pendingSplitPkg = null
            }
        }
        frames(3)
        flyers.removeAll(fl)
        scheduleReload(0)
    }

    /** Handle menu: step the main app's zoom; remembered for that app from now on. */
    var zoomLabel by mutableStateOf<String?>(null); private set
    private var zoomJob: Job? = null

    fun cycleZoom() {
        val m = mainTask ?: return
        val pkg = mainPkg ?: return
        val steps = listOf(1f, 1.12f, 1.25f, 0.88f)
        val cur = Prefs.zoom(pkg)
        val next = steps[(steps.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f } + 1).mod(steps.size)]
        Prefs.setZoom(pkg, next)
        buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        val r = Rect(mainShown)
        scope.launch(Dispatchers.IO) { Sys.prepareFreeform(m, r, dpiOf(m, r)) }
        zoomJob?.cancel()
        zoomLabel = "${(next * 100).roundToInt()}%"
        zoomJob = scope.launch { delay(1400); zoomLabel = null }
    }

    /** Tap on the divider: the two panes trade places. */
    fun swapSplit() = op { swapImpl() }

    private suspend fun swapImpl() {
        val m = mainTask ?: return
        val s = splitTask ?: return
        buzz(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        cover(collapse = false)
        val (top, bottom) = splitRects()
        val mi = mainImage
        val si = splitImage
        val a = Flyer(mi, icon(mainPkg), Rect(top), Rect(bottom), mainRadius, mainRadius)
        val c = Flyer(si, icon(splitPkg), Rect(bottom), Rect(top), mainRadius, mainRadius)
        val mp = mainPkg
        mainTask = s
        mainPkg = splitPkg
        splitTask = m
        splitPkg = mp
        mainImage = null
        splitImage = null
        flyers += a
        flyers += c
        animate(a, c)
        mainImage = si
        splitImage = mi
        uncover(s)
        frames(3)
        flyers.remove(a)
        flyers.remove(c)
    }

    /** End the split keeping [keepTop]'s pane: the other app goes back to its card, the kept one fills the window. */
    fun unsplit(keepTop: Boolean = true) = op {
        val m = mainTask ?: return@op
        val s = splitTask
        if (s == null) {
            pendingSplitPkg = null
            if (!covered) uncover(m)
            return@op
        }
        handleMenu = false
        buzz(android.view.HapticFeedbackConstants.GESTURE_END)
        cover(collapse = false)
        val (top, bottom) = splitRects()
        val goneId = if (keepTop) s else m
        val keepId = if (keepTop) m else s
        val goneImg = if (keepTop) splitImage else mainImage
        val keepImg = if (keepTop) mainImage else splitImage
        val gonePkg = if (keepTop) splitPkg else mainPkg
        val keepPkg = if (keepTop) mainPkg else splitPkg
        landing += goneId
        val f1 = Flyer(goneImg, icon(gonePkg), if (keepTop) bottom else top, cardRectFor(keyOfTask(goneId)), mainRadius, cardRadius)
        val f2 = Flyer(keepImg, icon(keepPkg), if (keepTop) top else bottom, Rect(geo.main), mainRadius, mainRadius)
        splitTask = null
        splitPkg = null
        splitImage = null
        splitRatio = 0.5f
        mainTask = keepId
        mainPkg = keepPkg
        mainImage = null
        flyers += f1
        flyers += f2
        val b = Rect(geo.main)
        launch(Dispatchers.IO) { Sys.prepareFreeform(keepId, b, dpiOf(keepId, b)) }
        animate(f1, f2)
        landing.remove(goneId)
        mainImage = keepImg
        uncover(keepId)
        frames(3)
        flyers.remove(f1)
        flyers.remove(f2)
        scheduleReload(0)
    }

    private var resizeJob: Job? = null

    /** Divider being dragged: both windows follow (at most one resize in flight; the last position always lands). */
    fun dragDivider(ratio: Float) {
        if (busy || splitTask == null) return
        splitRatio = ratio.coerceIn(SPLIT_MIN, 1f - SPLIT_MIN)
        if (resizeJob?.isActive == true) return
        resizeJob = scope.launch { applySplitBounds() }
    }

    /** Divider released: dragged to an end it closes that pane, otherwise the windows settle at the new size. */
    fun endDivider(ratio: Float) {
        Log.d(TAG, "divider released at $ratio")
        when {
            ratio < SPLIT_MIN - 0.04f -> unsplit(keepTop = false)
            ratio > 1f - SPLIT_MIN + 0.04f -> unsplit(keepTop = true)
            else -> scope.launch {
                resizeJob?.join()
                applySplitBounds()
                val t = mainPkg
                val l = splitPkg
                if (t != null && l != null) Prefs.setSplitRatio(t, l, splitRatio)
                buzz(android.view.HapticFeedbackConstants.GESTURE_END)
            }
        }
    }

    private suspend fun applySplitBounds() {
        while (true) {
            val r = splitRatio
            val m = mainTask ?: return
            val s = splitTask ?: return
            val (top, bottom) = splitRects(r)
            withContext(Dispatchers.IO) {
                Sys.prepareFreeform(m, top, dpiOf(m, top))
                Sys.prepareFreeform(s, bottom, dpiOf(s, bottom))
            }
            delay(32)
            if (splitRatio == r) return
        }
    }

    // ------------------------------------------------------------------ drag an app out of search

    fun beginAppDrag(app: dev.satya.workbench.data.AppEntry, p: Offset) {
        appDrag = app
        appDragPos = p
        act.hideIme()
    }

    fun appDragAt(p: Offset) {
        val before = dropTargetAt(appDragPos)
        appDragPos = p
        val now = dropTargetAt(p)
        if (now != before && now != null) buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK)
    }

    fun endAppDrag(cancel: Boolean = false) {
        val app = appDrag ?: return
        val p = appDragPos
        appDrag = null
        if (cancel) return
        val s = geo.dp(52)
        val from = Rect(p.x.toInt() - s / 2, p.y.toInt() - s / 2, p.x.toInt() + s / 2, p.y.toInt() + s / 2)
        when (dropTargetAt(p)) {
            DropTarget.MAIN -> openApp(app.component, app.user, from)
            DropTarget.SPLIT -> op { splitImpl(app, from) }
            DropTarget.STRIP -> dockApp(app)
            null -> buzz(android.view.HapticFeedbackConstants.GESTURE_END)
        }
    }

    /** Dropped on the strip: pinned there and started in the background, so its card is ready with a preview. */
    fun dockApp(app: dev.satya.workbench.data.AppEntry) = op {
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        if (!Prefs.isPinned(app.key) && pinIndex(app.pkg) < 0) Prefs.togglePin(app.key)
        if (modal != null) closeModalNow(restore = true)
        if (tasks.none { it.pkg == app.pkg }) {
            behindPkgs += app.pkg
            suppressAdoptUntil = SystemClock.uptimeMillis() + 2500
            withContext(Dispatchers.IO) {
                runCatching {
                    act.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        Sys.launchBehindOptions(Rect(geo.main)).toBundle()
                    )
                }
            }
            val until = SystemClock.uptimeMillis() + 1500
            while (app.pkg in behindPkgs && SystemClock.uptimeMillis() < until) delay(50)
            delay(150)
            // Launch-behind lands in front when nothing covers it: bench back on top, then its shown app(s) above it.
            withContext(Dispatchers.IO) { Sys.showTask(act, act.taskId, null) }
            val m = mainTask
            if (m != null && !covered) uncover(m)
        }
        scheduleReload(0)
    }

    // ------------------------------------------------------------------ shelf

    fun toggleShelf() {
        shelfOpen = !shelfOpen
        shelfActionsFor = null
        buzz(if (shelfOpen) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.TOGGLE_OFF)
    }

    /** A drop from another app: copy what it carries into the Shelf (or straight on to FileDock). */
    fun acceptDrop(ev: android.view.DragEvent, toFileDock: Boolean): Boolean {
        val clip = ev.clipData ?: return false
        val perms = runCatching { act.requestDragAndDropPermissions(ev) }.getOrNull()
        val desc = ev.clipDescription
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        shelfOpen = true
        scope.launch {
            val before = dev.satya.workbench.data.Shelf.items.value.map { it.id }.toSet()
            withContext(Dispatchers.IO) {
                for (i in 0 until clip.itemCount) {
                    val it = clip.getItemAt(i)
                    val mime = if (desc != null && desc.mimeTypeCount > 0) desc.getMimeType(minOf(i, desc.mimeTypeCount - 1)) else null
                    when {
                        it.uri != null -> dev.satya.workbench.data.Shelf.addUri(act, it.uri, mime)
                        it.text != null -> dev.satya.workbench.data.Shelf.addText(it.text.toString())
                        it.htmlText != null -> dev.satya.workbench.data.Shelf.addText(it.coerceToText(act).toString())
                        it.intent != null -> dev.satya.workbench.data.Shelf.addText(it.intent.toUri(0))
                    }
                }
            }
            perms?.release()
            if (toFileDock) {
                val fresh = dev.satya.workbench.data.Shelf.items.value.filter { it.id !in before }
                sendToFileDock(fresh)
            }
        }
        return true
    }

    fun sendToFileDock(list: List<dev.satya.workbench.data.ShelfItem>) {
        shelfActionsFor = null
        if (list.isEmpty()) return
        val ok = dev.satya.workbench.data.Shelf.toFileDock(act, list)
        buzz(if (ok) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.REJECT)
        android.widget.Toast.makeText(act, if (ok) "Sent to FileDock" else "FileDock isn't available", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Clipboard into the Shelf. Reading the clipboard needs window focus, so take it for a moment. */
    fun pasteClipboard() = scope.launch {
        act.setFocusable(true)
        delay(120)
        val cm = act.getSystemService(android.content.ClipboardManager::class.java)
        val c = runCatching { cm.primaryClip }.getOrNull()
        act.setFocusable(modal != null)
        if (c == null || c.itemCount == 0) {
            buzz(android.view.HapticFeedbackConstants.REJECT)
            android.widget.Toast.makeText(act, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
            return@launch
        }
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        withContext(Dispatchers.IO) {
            for (i in 0 until c.itemCount) {
                val it = c.getItemAt(i)
                if (it.uri != null) dev.satya.workbench.data.Shelf.addUri(act, it.uri, c.description.getMimeType(0))
                else dev.satya.workbench.data.Shelf.addText(it.coerceToText(act).toString())
            }
        }
    }

    // ------------------------------------------------------------------ strip position

    fun beginRelocate(p: Offset) {
        if (busy || exiting) return
        buzz(android.view.HapticFeedbackConstants.DRAG_START)
        relocPointer = p
        val d = CompletableDeferred<Edge?>()
        relocResult = d
        op {
            actionsFor = null
            handleMenu = false
            cover()
            val e = d.await()
            relocResult = null
            relocPointer = null
            relocateImpl(e)
        }
    }

    fun endRelocate(cancel: Boolean = false) {
        val p = relocPointer
        val d = relocResult
        if (d == null) {
            relocPointer = null
            return
        }
        val e = if (cancel || p == null) null else nearestEdge(p)
        buzz(if (e != null && e != edge) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.GESTURE_END)
        d.complete(e)
    }

    fun nearestEdge(p: Offset): Edge =
        if (p.x / geo.w <= p.y / geo.h) Edge.LEFT else Edge.TOP

    fun relocateTo(e: Edge) = op {
        if (modal != null) closeModalNow(restore = false)
        cover()
        relocateImpl(e)
    }

    private suspend fun relocateImpl(e: Edge?) {
        if (e == null || e == edge) {
            mainTask?.let { uncover(it) }
            return
        }
        val oldMain = Rect(geo.main)
        val img = mainImage
        reveal.animateTo(0f, tween(150))
        Prefs.setEdge(e)
        edge = e
        geo = computeGeo(e)
        cardRects.clear()
        val m = mainTask
        if (m != null) {
            val f = Flyer(img, icon(mainPkg), oldMain, Rect(geo.main), mainRadius, mainRadius)
            mainImage = null
            flyers += f
            val bounds = Rect(geo.main)
            val dpi = densityFor(bounds)
            // Windows parked behind the main one move too, or they would peek out around the new main window.
            val parked = session.filter { it != m }
            coroutineScope {
                launch(Dispatchers.IO) {
                    Sys.prepareFreeform(m, bounds, dpiOf(m, bounds))
                    for (id in parked) Sys.prepareFreeform(id, bounds, dpiOf(id, bounds))
                }
                animate(f, withReveal = 1f)
            }
            mainImage = img
            uncover(m)
            frames(3)
            flyers.remove(f)
        } else {
            val bounds = Rect(geo.main)
            val parked = session.toList()
            scope.launch(Dispatchers.IO) { for (id in parked) Sys.prepareFreeform(id, bounds, dpiOf(id, bounds)) }
            reveal.animateTo(1f, tween(320, easing = EaseOut))
        }
    }

    // ------------------------------------------------------------------ panels

    fun openModal(m: Modal) = op {
        buzz(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        actionsFor = null
        handleMenu = false
        cover(collapse = false)
        act.setFocusable(true)
        modal = m
    }

    fun switchModal(m: Modal) {
        modal = m
    }

    fun closeModal() = op { closeModalNow(restore = true) }

    private suspend fun closeModalNow(restore: Boolean) {
        modal = null
        splitPick = false
        act.hideIme()
        act.setFocusable(false)
        if (restore) mainTask?.let { uncover(it) }
    }

    fun canSaveCurrent(): Boolean = mainPkg != null || session.isNotEmpty()

    fun saveCurrentAsGroup() {
        val pkgs = LinkedHashSet<String>()
        mainPkg?.let { pkgs += it }
        val recentSession = tasks.filter { it.taskId in session }.map { it.pkg }
        pkgs.addAll(recentSession)
        val keys = pkgs.take(6).map { pinKeyFor(it, null) }
        if (keys.isEmpty()) return
        val n = Prefs.groups.value.size + 1
        val name = keys.take(2).joinToString(" + ") { Apps.label(act, ComponentName.unflattenFromString(it)!!.packageName) }
        Prefs.saveGroup(AppGroup("g" + System.currentTimeMillis(), if (keys.size > 2) "$name +" + (keys.size - 2) else name.ifBlank { "Group $n" }, keys))
    }

    /** Save a suggested set of apps as a group. */
    fun saveSuggestion(pkgs: List<String>) {
        val keys = pkgs.map { pinKeyFor(it, null) }
        val name = pkgs.take(2).joinToString(" + ") { Apps.label(act, it) } + if (pkgs.size > 2) " +" + (pkgs.size - 2) else ""
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        Prefs.saveGroup(AppGroup("g" + System.currentTimeMillis(), name, keys))
    }

    fun launchApps(pkgs: List<String>) = launchGroup(AppGroup("tmp", "", pkgs.map { pinKeyFor(it, null) }))

    fun launchGroup(g: AppGroup) = op {
        if (modal != null) closeModalNow(restore = false)
        val comps = g.apps.mapNotNull { ComponentName.unflattenFromString(it) }
        if (comps.isEmpty()) return@op
        buzz(android.view.HapticFeedbackConstants.CONFIRM)
        cover()
        suppressAdoptUntil = SystemClock.uptimeMillis() + 3000
        val bounds = Rect(geo.main)
        withContext(Dispatchers.IO) {
            for (cn in comps.drop(1).reversed()) {
                val t = tasks.firstOrNull { it.pkg == cn.packageName }
                if (t != null) {
                    if (t.taskId != mainTask) {
                        Sys.prepareFreeform(t.taskId, bounds, dpiOf(t.taskId, bounds))
                        session += t.taskId
                    }
                } else {
                    val entry = Apps.all.value.firstOrNull { it.pkg == cn.packageName } ?: continue
                    behindPkgs += entry.pkg
                    runCatching {
                        act.startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(entry.component)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            Sys.launchBehindOptions(bounds).toBundle()
                        )
                    }
                }
            }
        }
        persistSession()
        // Wait for the background launches to exist, then put the bench back over them: only the first app shows.
        val until = SystemClock.uptimeMillis() + 1500
        while (behindPkgs.isNotEmpty() && SystemClock.uptimeMillis() < until) delay(50)
        delay(150)
        withContext(Dispatchers.IO) { Sys.showTask(act, act.taskId, null) }
        openImpl(comps[0], null, null)
        delay(600)
        scheduleReload(0)
    }
}
