package dev.satya.workbench.service

import android.annotation.SuppressLint
import android.content.Context
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
import kotlin.math.abs

/**
 * The main window's handle, drawn inside the app's own status-bar band (like the dots on ColorOS floating
 * windows). The app's window sits above Workbench, so this lives in an accessibility overlay above both.
 * Down: minimize, up: full screen, sideways: previous / next app, tap: the handle menu.
 */
@SuppressLint("ViewConstructor")
class MainPill(ctx: Context, private val wm: WindowManager) : View(ctx) {
    interface Actions {
        fun tap()
        fun up()
        fun down()
        fun side(dir: Int)
    }

    var actions: Actions? = null
    private var attached = false
    private var shownAt: Rect? = null
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var dx = 0f
    private var dy = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** Show the handle over [band] (screen px: the top band of the main window), or hide it with null. */
    fun show(band: Rect?) {
        if (band == null || band.isEmpty) {
            if (attached && visibility != GONE) visibility = GONE
            shownAt = null
            return
        }
        if (band == shownAt && visibility == VISIBLE) return
        shownAt = Rect(band)
        val p = WindowManager.LayoutParams(
            band.width(), band.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = band.left
            y = band.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "WorkbenchMainPill"
        }
        if (!attached) runCatching { wm.addView(this, p) }.onSuccess { attached = true }
        else runCatching { wm.updateViewLayout(this, p) }
        visibility = VISIBLE
    }

    fun remove() {
        if (attached) runCatching { wm.removeView(this) }
        attached = false
        shownAt = null
    }

    override fun onDraw(c: Canvas) {
        val w = 40 * d
        val h = 4.5f * d
        val cx = width / 2f + (dx * 0.35f).coerceIn(-60f, 60f)
        val cy = height / 2f + (dy * 0.25f).coerceIn(-24f, 24f)
        pill.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        // Readable on light and dark app bars alike.
        paint.color = 0xB0A0A0A0.toInt()
        c.drawRoundRect(pill, h / 2, h / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lim = 36 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dx = 0f; dy = 0f; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                dx = e.rawX - downX; dy = e.rawY - downY
                if (!dragging && (abs(dx) > 8 * d || abs(dy) > 8 * d)) dragging = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val a = actions
                when {
                    !dragging -> a?.tap()
                    abs(dx) > abs(dy) && abs(dx) > lim -> { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); a?.side(if (dx < 0) 1 else -1) }
                    dy > lim -> a?.down()
                    dy < -lim -> a?.up()
                }
                dx = 0f; dy = 0f; dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dx = 0f; dy = 0f; dragging = false; invalidate() }
        }
        return true
    }
}
