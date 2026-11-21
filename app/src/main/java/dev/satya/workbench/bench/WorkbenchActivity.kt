package dev.satya.workbench.bench

import android.graphics.Color
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class WorkbenchActivity : ComponentActivity() {
    private var bench: Bench? = null
    private var lastBlur = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Taps on our window must never pull it above the floating app (non-focusable windows don't reorder tasks).
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (savedInstanceState != null || Bench.active != null) {
            finish()
            return
        }
        val b = Bench(this)
        bench = b
        val task = intent.getIntExtra(Trigger.EXTRA_TASK, -1).takeIf { it > 0 }
        val snap = Trigger.handoff
        Trigger.handoff = null
        b.start(task, snap)
        setContent { dev.satya.workbench.ui.ProvideCoui { BenchUi(b) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        bench?.toggleFromTrigger()
    }

    override fun onDestroy() {
        bench?.dispose()
        super.onDestroy()
    }

    fun setFocusable(focusable: Boolean) {
        if (focusable) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    fun hideIme() {
        window.decorView.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    fun setBlur(px: Int) {
        if (px == lastBlur) return
        lastBlur = px
        // Everything below our (occluding) window is the wallpaper, so a blur-behind blurs exactly the wallpaper.
        window.attributes = window.attributes.apply {
            if (px > 0) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            else flags = flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            blurBehindRadius = px
        }
    }
}
