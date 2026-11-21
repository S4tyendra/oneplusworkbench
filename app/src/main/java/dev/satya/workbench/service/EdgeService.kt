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
    }
    private lateinit var wm: WindowManager
    private var handle: HandleView? = null
    private val scope = MainScope()
    private var imeVisible = false
    private var locked = false
    private var attached = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            update()
        }
    }

    override fun onServiceConnected() {
        running = true
        wm = getSystemService(WindowManager::class.java)
        handle = HandleView(this) { Trigger.enter(this) }
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        scope.launch {
            combine(Prefs.handleEnabled, Prefs.handleSide, Prefs.handleY, Bench.activeFlow, Prefs.handleTransparency) { _, _, _, _, _ -> }.collect { update() }
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
        val show = Prefs.handleEnabled.value && !Bench.activeFlow.value && !imeVisible && !locked
        v.side = Prefs.handleSide.value
        v.opacity = 1f - Prefs.handleTransparency.value
        if (show) {
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
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
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
        val inset = (3 + 10 * progress) * d
        val cy = height / 2f
        val left = if (side == Edge.LEFT) inset else width - inset - w
        rect.set(left, cy - h / 2, left + w, cy + h / 2)
        val sh = RectF(rect).apply { inset(-1 * d, -1 * d) }
        val k = opacity + (1f - opacity) * progress
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
