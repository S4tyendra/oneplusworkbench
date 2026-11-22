package dev.satya.workbench.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import dev.satya.workbench.bench.Bench
import dev.satya.workbench.bench.Trigger
import dev.satya.workbench.data.Edge
import dev.satya.workbench.data.Prefs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Draws a thin handle on the screen edge. Swipe it inward to open Workbench. */
class EdgeService : AccessibilityService() {
    companion object {
        @Volatile var running = false
        /** The connected service, for overlays only it may add (the main window's handle). */
        @Volatile var instance: EdgeService? = null
    }

    /** The main window's handle overlay; created on first use. */
    val mainPill: MainPill by lazy { MainPill(this, wm) }
    private lateinit var wm: WindowManager
    private var handle: HandleView? = null
    private val scope = MainScope()
    private var imeVisible = false
    private var locked = false
    private var attached = false
    private var clipboard: android.content.ClipboardManager? = null
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { takeClip() }

    private fun takeClip() {
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        kotlin.concurrent.thread(name = "wb-clip") { dev.satya.workbench.data.Shelf.ingestClip(this, clip) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            update()
        }
    }

    override fun onServiceConnected() {
        running = true
        wm = getSystemService(WindowManager::class.java)
        instance = this
        handle = HandleView(this) { Trigger.enter(this) }
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        // Clipboard history for the Shelf. Background reads are allowed by the system hook.
        clipboard = getSystemService(android.content.ClipboardManager::class.java).also { it.addPrimaryClipChangedListener(clipListener) }
        takeClip()
        scope.launch {
            combine(listOf<kotlinx.coroutines.flow.Flow<Any>>(Prefs.handleEnabled, Prefs.handleSide, Prefs.handleY, Bench.activeFlow, Prefs.handleTransparency, Prefs.handleAutoHide)) { }.collect { update() }
        }
    }

    private fun params(): WindowManager.LayoutParams {
        val d = resources.displayMetrics.density
        val h = (150 * d).toInt()
        val screenH = wm.currentWindowMetrics.bounds.height()
        return WindowManager.LayoutParams(
            (22 * d).toInt(), h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (Prefs.handleSide.value == Edge.LEFT) Gravity.LEFT else Gravity.RIGHT)
            y = ((screenH - h) * Prefs.handleY.value).toInt()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "WorkbenchHandle"
        }
    }

    private fun update() {
        val v = handle ?: return
        if (!Bench.activeFlow.value) runCatching { mainPill.show(null) }
        val show = Prefs.handleEnabled.value && !Bench.activeFlow.value && !imeVisible && !locked
        v.side = Prefs.handleSide.value
        v.opacity = 1f - Prefs.handleTransparency.value
        v.autoHide = Prefs.handleAutoHide.value
        if (show) {
            // Coming back (Workbench closed, keyboard down, unlocked): show the pill, then let it slide away again.
            if (!attached || v.visibility != View.VISIBLE) v.wake()
            val p = params()
            if (!attached) {
                runCatching { wm.addView(v, p) }.onSuccess { attached = true }
            } else {
                runCatching { wm.updateViewLayout(v, p) }
            }
            v.visibility = View.VISIBLE
        } else if (attached) {
            v.visibility = View.GONE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val t = event?.eventType ?: return
        if (t != AccessibilityEvent.TYPE_WINDOWS_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val ime = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        if (ime != imeVisible) {
            imeVisible = ime
            update()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        instance = null
        runCatching { mainPill.remove() }
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        clipboard?.removePrimaryClipChangedListener(clipListener)
        if (attached) runCatching { wm.removeView(handle) }
        super.onDestroy()
    }
}

@SuppressLint("ViewConstructor")
private class HandleView(ctx: Context, val onTrigger: () -> Unit) : View(ctx) {
    var side: Edge = Edge.RIGHT
        set(v) { field = v; invalidate() }
    /** User setting; while being swiped the pill fades in to full strength regardless. */
    var opacity: Float = 1f
        set(v) { field = v; invalidate() }
    /** Slide the pill off the edge after [HIDE_AFTER] ms without a touch. The view (and its swipe area) stays. */
    var autoHide: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v) scheduleHide() else { removeCallbacks(hideRunnable); animateShown(1f, 200) }
        }
    /** 1 = pill in place, 0 = slid off the edge. */
    private var shown = 1f
    private var shownAnim: ValueAnimator? = null
    private val hideRunnable = Runnable { animateShown(0f, 420) }

    private fun animateShown(to: Float, ms: Long) {
        shownAnim?.cancel()
        if (shown == to) return
        shownAnim = ValueAnimator.ofFloat(shown, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        if (autoHide) postDelayed(hideRunnable, HIDE_AFTER)
    }

    /** Show the pill now; with auto-hide on, it slides away again after a while. */
    fun wake() {
        removeCallbacks(hideRunnable)
        animateShown(1f, 200)
        scheduleHide()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        shownAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val HIDE_AFTER = 3000L
    }
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private var progress = 0f
    private var x0 = 0f
    private var fired = false
    private var anim: ValueAnimator? = null
    private val threshold = 36 * d
    private val rect = RectF()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    override fun onDraw(c: Canvas) {
        val w = (4 + 6 * progress) * d
        val h = (64 - 14 * progress) * d
        // Hidden: the pill slides out past the screen edge. A swipe pulls it back in as it goes.
        val vis = maxOf(shown, progress)
        if (vis <= 0f) return
        val inset = (3 + 10 * progress) * d - (1f - vis) * (w + 4 * d)
        val cy = height / 2f
        val left = if (side == Edge.LEFT) inset else width - inset - w
        rect.set(left, cy - h / 2, left + w, cy + h / 2)
        val sh = RectF(rect).apply { inset(-1 * d, -1 * d) }
        val k = (opacity + (1f - opacity) * progress) * vis
        shadow.alpha = (0x40 * k).toInt()
        c.drawRoundRect(sh, w, w, shadow)
        paint.alpha = ((150 + 105 * progress) * k).toInt()
        c.drawRoundRect(rect, w / 2, w / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                x0 = e.rawX
                fired = false
                removeCallbacks(hideRunnable)
                if (autoHide) animateShown(1f, 160)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - x0) * (if (side == Edge.LEFT) 1 else -1)
                progress = (dx / threshold).coerceIn(0f, 1f)
                invalidate()
                if (!fired && dx > threshold) {
                    fired = true
                    performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                    onTrigger()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scheduleHide()
                anim = ValueAnimator.ofFloat(progress, 0f).apply {
                    duration = 220
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }
}
