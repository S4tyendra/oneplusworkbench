package dev.satya.workbench.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import `in`.devh.coui.Coui
import java.lang.reflect.Proxy
import kotlin.concurrent.thread

/** Native ColorOS widgets (COUI Kit) inside the Compose UI. */
object Kit {
    const val TAG = "Workbench"
    private const val SEARCH_BAR = "com.coui.appcompat.searchview.COUISearchBar"
    const val STATE_NORMAL = 0
    const val STATE_EDIT = 1

    /** Extracts and loads the packs off the main thread, so the first bench doesn't pay for it. */
    fun warm(app: Context) {
        thread(name = "wb-coui") {
            val t = System.nanoTime()
            runCatching { Coui(app).galleryCtx }.onFailure { Log.w(TAG, "coui warm-up failed", it) }
            Log.i(TAG, "coui warm-up ${(System.nanoTime() - t) / 1_000_000} ms")
        }
    }

    /** COUISearchBar from the Gallery pack: ColorOS 16 search pill that morphs into its edit state on tap. */
    fun searchBar(coui: Coui): ViewGroup =
        coui.galleryCtx.classLoader.loadClass(SEARCH_BAR).getConstructor(Context::class.java)
            .newInstance(coui.galleryCtx) as ViewGroup

    fun onStateChange(coui: Coui, bar: View, f: (old: Int, new: Int) -> Unit) {
        val l = coui.galleryCtx.classLoader
        val iface = l.loadClass(SEARCH_BAR + "\$" + "OnStateChangeListener")
        val p = Proxy.newProxyInstance(l, arrayOf(iface)) { self, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === a!![0]
                "toString" -> "wb-search-state"
                else -> {
                    Log.i(TAG, "search bar ${m.name} ${a?.toList()}")
                    if (a != null && a.size >= 2 && a[0] is Int && a[1] is Int) f(a[0] as Int, a[1] as Int)
                    null
                }
            }
        }
        coui.call(bar, "addOnStateChangeListener", p)
    }
}

val LocalCoui = staticCompositionLocalOf<Coui> { error("COUI is not provided") }

@Composable
fun ProvideCoui(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val coui = remember { Coui(ctx) }
    CompositionLocalProvider(LocalCoui provides coui, content = content)
}

/** A Compose vector as a plain Drawable, for COUI views. */
@Composable
fun rememberIconDrawable(icon: ImageVector, size: Dp = 24.dp, tint: Color = Color.White): Drawable {
    val painter = rememberVectorPainter(icon)
    val density = LocalDensity.current
    val res = LocalContext.current.resources
    return remember(icon, size, tint) {
        val px = with(density) { size.roundToPx() }
        val bmp = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bmp), Size(px.toFloat(), px.toFloat())) {
            with(painter) { draw(this@draw.size, colorFilter = ColorFilter.tint(tint)) }
        }
        BitmapDrawable(res, bmp.asAndroidBitmap())
    }
}

/** COUI floating round button (Gallery's accessory button): blurred glass, glow, press spring, haptics. */
@Composable
fun CouiRoundButton(icon: ImageVector, desc: String?, size: Dp = 48.dp, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    val coui = LocalCoui.current
    val d = rememberIconDrawable(icon, 24.dp)
    val cb by rememberUpdatedState(onClick)
    val density = LocalDensity.current
    var native by remember { mutableStateOf(0) }
    AndroidView(
        factory = {
            coui.roundButton(d) { cb() }.apply {
                contentDescription = desc
                d.setTint(tint.toArgb()) // the kit tints with the day label color; the bench is always dark
                measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                native = measuredWidth
            }
        },
        modifier = modifier.size(size).graphicsLayer {
            val s = if (native > 0) with(density) { size.toPx() } / native else 1f
            scaleX = s; scaleY = s
        }
    )
}

/** COUIButton with its caustic glow and press motion; [primary] takes the system accent. */
@Composable
fun CouiButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val coui = LocalCoui.current
    val d = icon?.let { rememberIconDrawable(it, 18.dp) }
    val cb by rememberUpdatedState(onClick)
    AndroidView(
        factory = {
            coui.button(text, primary = primary, large = false) { cb() }.apply {
                if (d != null) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
                    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                }
                if (!primary) setTextColor(android.graphics.Color.WHITE)
            }
        },
        modifier = modifier
    )
}

/** COUI segmented control (Gallery's Years / Months / Days): glass indicator that slides and can be dragged. */
@Composable
fun CouiSegments(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val coui = LocalCoui.current
    val cb by rememberUpdatedState(onSelect)
    // Built once with the current choice; the control animates its own selection from then on.
    AndroidView(factory = { coui.segments(labels, selected, 44f) { cb(it) } }, modifier = modifier)
}

/**
 * COUISearchBar. It starts collapsed (no keyboard); a tap morphs it into the edit state, which opens the
 * keyboard and shows Cancel. Cancel morphs it back and clears the query.
 */
@Composable
fun CouiSearchBar(hint: String, onQuery: (String) -> Unit, onGo: () -> Unit, modifier: Modifier = Modifier, onEditing: (Boolean) -> Unit = {}, autoOpen: Boolean = false) {
    val coui = LocalCoui.current
    val q by rememberUpdatedState(onQuery)
    val go by rememberUpdatedState(onGo)
    val editing by rememberUpdatedState(onEditing)
    AndroidView(
        factory = {
            val bar = Kit.searchBar(coui)
            val et = coui.call(bar, "getSearchEditText") as EditText
            et.hint = hint
            et.setTextColor(android.graphics.Color.WHITE)
            et.setHintTextColor(0x80FFFFFF.toInt())
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            et.imeOptions = EditorInfo.IME_ACTION_GO
            et.isSingleLine = true
            et.setOnEditorActionListener { _, _, _ -> go(); true }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { q(s?.toString().orEmpty()) }
            })
            runCatching { coui.call(bar, "setFunctionalButtonText", "Cancel") }
            Kit.onStateChange(coui, bar) { _, new -> if (new == Kit.STATE_NORMAL) et.setText("") }
            // Standalone, the bar leaves both morphs to its host: a tap on the collapsed pill opens it for
            // typing, and Cancel clears the query, drops the keyboard and folds it back.
            val open = {
                if (coui.call(bar, "getSearchState") == Kit.STATE_NORMAL) {
                    coui.call(bar, "changeStateWithAnimation", Kit.STATE_EDIT)
                    runCatching { coui.call(bar, "openSoftInput", true) }
                    editing(true)
                }
            }
            bar.setOnClickListener { open() }
            (coui.call(bar, "getFunctionalButton") as? TextView)?.apply {
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    et.setText("")
                    et.clearFocus()
                    et.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(et.windowToken, 0)
                    coui.call(bar, "changeStateWithAnimation", Kit.STATE_NORMAL)
                    editing(false)
                }
            }
            // Opened for typing right away: same path as a tap, once the panel has popped in.
            if (autoOpen) bar.postDelayed({ if (bar.isAttachedToWindow) runCatching { open() }.onFailure { Log.w(Kit.TAG, "search auto-open", it) } }, 180)
            bar
        },
        modifier = modifier
    )
}
