package dev.satya.workbench.bench

import android.graphics.Insets
import android.graphics.Rect
import dev.satya.workbench.data.Edge
import kotlin.math.roundToInt

/**
 * All layout in window pixels. The Compose strip is laid out with the same numbers so slots line up exactly.
 *
 * The main window is the screen scaled down (same aspect ratio). The floating task gets a density scaled by
 * the same factor, so apps lay out exactly as they do full screen and every open / close is a pure scale.
 * Cards are the screen scaled further down, turned about their vertical axis (left edge stays, right edge recedes).
 */
class Geo(val w: Int, val h: Int, val edge: Edge, insets: Insets, private val d: Float) {
    fun dp(v: Int): Int = (v * d).roundToInt()

    /** Perspective tilt of strip cards (rotation about the vertical axis), degrees. */
    val tilt = 38f

    val labelH = 0
    val listPad = dp(6)
    val crossPad = dp(8)
    /**
     * The fixed end of the strip (settings and search circles). Matches the Compose layout in StripTools.
     */
    val toolsLen = if (edge.vertical) dp(118) else dp(58)

    val full = Rect(0, 0, w, h)
    /** Main window size relative to the screen. */
    val scale: Float
    val strip: Rect
    val main: Rect
    val handle: Rect
    val cardW: Int
    val cardH: Int
    val thick: Int
    val itemMain: Int
    val gap: Int

    init {
        val top = insets.top
        // Gesture navigation reports no bottom inset, but a touch that starts in the bottom ~16dp is a home swipe.
        val bottom = h - maxOf(insets.bottom, dp(18))
        val b = bottom - dp(6)
        if (edge.vertical) {
            // Cards down the left; the main window takes everything to their right, a few px clear of them.
            val rowH = dp(56)
            cardW = (w * 0.18f).roundToInt()
            cardH = (cardW.toFloat() * h / w).roundToInt()
            gap = dp(10)
            val cardLeft = insets.left + dp(10)
            val right = w - insets.right - dp(10)
            val left = cardLeft + (cardW * FORESHORTEN).roundToInt() + CLEAR_PX
            val availTop = top + rowH
            var mw = right - left
            var mh = (mw.toFloat() * h / w).roundToInt()
            if (mh > b - availTop) {
                mh = b - availTop
                mw = (mh.toFloat() * w / h).roundToInt()
            }
            scale = mh.toFloat() / h
            val t = availTop + ((b - availTop) - mh) / 2
            main = Rect(right - mw, t, right, t + mh)
            // The strip runs the full height on the left; the row above the main window only holds its handle.
            strip = Rect(insets.left, top, cardLeft + cardW + dp(10), bottom)
            handle = Rect(main.left, main.top - rowH, main.right, main.top)
            thick = strip.width()
            itemMain = cardH
        } else {
            // A short row of small cards under the status bar; the main window fills the rest below it.
            val handleH = dp(30)
            // Side edges are back / sidebar swipe zones; keep the strip's ends (search button, grip) out of them.
            val side = maxOf(dp(30), insets.left, insets.right)
            cardH = (h * 0.125f).roundToInt()
            cardW = (cardH.toFloat() * w / h).roundToInt()
            // Turned cards are narrower on screen: pull them together so they read as a stack.
            gap = dp(8) - (cardW * (1f - FORESHORTEN)).roundToInt()
            strip = Rect(side, top, w - side, top + cardH + 2 * crossPad)
            val availTop = strip.bottom + handleH
            val mh = b - availTop
            val mw = (mh.toFloat() * w / h).roundToInt()
            scale = mh.toFloat() / h
            val l = (w - mw) / 2
            main = Rect(l, availTop, l + mw, b)
            thick = strip.height()
            itemMain = cardW
            handle = Rect(main.left, main.top - handleH, main.right, main.top)
        }
    }

    /**
     * How much of a card (in its own, untilted px, from its left edge) is not covered by the main window.
     * The tilt foreshortens the card to about [FORESHORTEN] of its width on screen.
     */
    val cardVisible: Int get() = if (!edge.vertical) cardW else
        ((main.left - dp(2) - (strip.left + (thick - cardW) / 2)) / FORESHORTEN).roundToInt().coerceIn(dp(48), cardW)
    /** Width of the strip left uncovered by the main window. */
    val stripVisible: Int get() = if (!edge.vertical) strip.width() else (main.left - strip.left).coerceIn(dp(40), strip.width())

    /** Where card number [i] sits when the strip list is scrolled to the start. */
    fun slot(i: Int): Rect {
        val r = if (edge.vertical) {
            val x = strip.left + (thick - cardW) / 2
            val y = strip.top + listPad + i * (itemMain + gap)
            Rect(x, y, x + cardW, y + cardH)
        } else {
            val x = strip.left + listPad + i * (itemMain + gap)
            val y = strip.top + crossPad
            Rect(x, y, x + cardW, y + cardH)
        }
        // Past the visible end of the strip: park it at the end.
        if (edge.vertical && r.bottom > strip.bottom - toolsLen) r.offsetTo(r.left, strip.bottom - toolsLen - cardH)
        if (!edge.vertical && r.right > strip.right - toolsLen) r.offsetTo(strip.right - toolsLen - cardW, r.top)
        return r
    }

    /** Is [r] (a flyer end point) a strip card? Those are drawn tilted. */
    fun isCard(r: Rect): Boolean = kotlin.math.abs(r.width() - cardW) <= 3 && kotlin.math.abs(r.height() - cardH) <= 3

    companion object {
        /** The edges a strip can live on. */
        val EDGES = listOf(Edge.LEFT, Edge.TOP)
        /** On-screen width of a tilted card relative to its real width (measured on device). */
        const val FORESHORTEN = 0.70f
        /** Clearance between the tilted cards and the main window, px. */
        const val CLEAR_PX = 5
    }
}
