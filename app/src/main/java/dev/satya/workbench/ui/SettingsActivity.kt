package dev.satya.workbench.ui

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.satya.workbench.bench.Geo
import dev.satya.workbench.bench.Trigger
import dev.satya.workbench.data.Edge
import dev.satya.workbench.data.Notifs
import dev.satya.workbench.data.Prefs
import dev.satya.workbench.data.Shelf
import dev.satya.workbench.data.Usage
import dev.satya.workbench.service.EdgeService
import dev.satya.workbench.sys.Root
import dev.satya.workbench.sys.Sys
import `in`.devh.coui.Coui
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Settings as a native ColorOS page: COUI toolbar with large title, card groups, switches, sliders and dialogs. */
class SettingsActivity : Activity() {
    private lateinit var coui: Coui
    /** Rebuilt on every resume: the status card, and the rows whose numbers change outside this screen. */
    private lateinit var statusHost: FrameLayout
    private lateinit var dataHost: FrameLayout
    private val launcherCn by lazy { ComponentName(this, "dev.satya.workbench.Launcher") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coui = Coui(this)
        // Tagged like the kit's own cards, so the page column doesn't add a second margin around them.
        val card = coui.group().tag
        statusHost = FrameLayout(this).apply { tag = card }
        dataHost = FrameLayout(this).apply { tag = card }
        val page = coui.column(
            coui.button("Open Workbench") { Trigger.enter(this) },
            header("Status"), statusHost,
            header("Strip"), strip(),
            header("Background"), background(),
            header("Edge handle"), handle(),
            header("Workbench"), features(),
            header("Shelf, windows and learning"), dataHost,
            header("App drawer"), drawer(), drawerNote(),
            about(),
        )
        coui.show(this, coui.screen("Workbench", page, largeTitle = true))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshData()
    }

    // ------------------------------------------------------------------ sections

    private data class Status(val hook: Boolean, val freeform: Boolean, val a11y: Boolean, val notifs: Boolean)

    private fun refreshStatus() {
        thread(name = "wb-settings-status") {
            val cr = contentResolver
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val s = Status(
                Sys.privileged(this),
                Settings.Global.getInt(cr, "enable_freeform_support", 0) == 1,
                EdgeService.running,
                nm.isNotificationListenerAccessGranted(Notifs.component(this)),
            )
            runOnUiThread { if (!isDestroyed) showStatus(s) }
        }
    }

    private fun showStatus(s: Status) {
        fun state(ok: Boolean, fix: String) = if (ok) "Working" else fix
        val rows = mutableListOf(
            coui.row("System hook", state(s.hook, "Enable the Workbench module for System Framework in Vector, then reboot")),
            coui.row("Freeform windows", state(s.freeform, "Off: Workbench can't float apps")),
            coui.row("Edge handle service", state(s.a11y, "Accessibility service is off")),
            coui.row("Notification access", state(s.notifs, "Needed for badges and the notifications panel")),
        )
        if (!(s.freeform && s.a11y && s.notifs)) rows += coui.row("Fix with root", "Turns on freeform windows, the edge handle and notification access") { fixWithRoot() }
        statusHost.removeAllViews()
        statusHost.addView(coui.group(*rows.toTypedArray()))
    }

    private fun fixWithRoot() {
        thread(name = "wb-settings-fix") {
            val svc = ComponentName(this, EdgeService::class.java).flattenToString()
            val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = (cur.split(':').filter { it.isNotBlank() && it != svc } + svc).joinToString(":")
            val r = Root.run(
                "settings put global enable_freeform_support 1",
                "settings put global force_resizable_activities 1",
                "settings put secure enabled_accessibility_services '" + list + "'",
                "settings put secure accessibility_enabled 1",
                "cmd notification allow_listener " + Notifs.component(this).flattenToString(),
            )
            runOnUiThread {
                if (r.code != 0) coui.dialog("Root command failed", r.out.take(300)).show()
                refreshStatus()
            }
        }
    }

    private fun strip(): View {
        val edges = Geo.EDGES
        val seg = coui.segments(edges.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, edges.indexOf(Prefs.edge.value).coerceAtLeast(0), 44f) {
            Prefs.setEdge(edges[it])
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seg, LinearLayout.LayoutParams(-1, dp(44)))
            addView(note("You can also drag the settings button at the end of the strip to the other edge."))
        }
    }

    private fun background(): View = coui.group(
        sliderRow("Transparency", Prefs.transparency.value, { "$it% · how much wallpaper shows through" }) { Prefs.setTransparency(it / 100f) },
    )

    private fun handle(): View {
        val side = arrayOf("Left edge", "Right edge")
        lateinit var sideRow: View
        sideRow = coui.row("Side", side[if (Prefs.handleSide.value == Edge.LEFT) 0 else 1]) {
            coui.choiceDialog("Handle side", side.toList(), if (Prefs.handleSide.value == Edge.LEFT) 0 else 1) { i ->
                Prefs.setHandleSide(if (i == 0) Edge.LEFT else Edge.RIGHT)
                sideRow.summary(side[i])
            }.show()
        }
        return coui.group(
            coui.row("Show handle", "Swipe inward from it to open Workbench. The Quick Settings tile works too.", coui.switch(Prefs.handleEnabled.value) { Prefs.setHandleEnabled(it) }),
            sideRow,
            sliderRow("Height on screen", Prefs.handleY.value, null) { Prefs.setHandleY(it / 100f) },
            sliderRow("Handle transparency", Prefs.handleTransparency.value, { "$it%" }) { Prefs.setHandleTransparency(it / 100f) },
        )
    }

    private fun features(): View = coui.group(
        coui.row("Haptics", "Taps, docking, switching and drop targets", coui.switch(Prefs.haptics.value) { Prefs.setHaptics(it) }),
        coui.row("Notification badges", "Unread counts on cards and the bell", coui.switch(Prefs.badges.value) { Prefs.setBadges(it) }),
        coui.row("Smart suggestions", "Apps you use together, and groups that open by time of day or with headphones", coui.switch(Prefs.suggestions.value) { Prefs.setSuggestions(it) }),
    )

    private fun refreshData() {
        val shelf = Shelf.items.value
        val (zooms, splits) = Prefs.windowMemory()
        val learned = Usage.learnedCount()
        val rows = listOf(
            coui.row("Shelf", if (shelf.isEmpty()) "Empty" else plural(shelf.size, "item") + " kept") {
                if (shelf.isEmpty()) return@row
                coui.listDialog("Shelf", listOf("Send all to FileDock", "Clear the shelf")) { i ->
                    if (i == 0) Shelf.toFileDock(this, Shelf.items.value)
                    else confirm("Clear the shelf?", "Removes " + plural(Shelf.items.value.size, "item") + ". Copies already sent to FileDock or other apps stay there.") { Shelf.clear() }
                }.show()
            },
            coui.row(
                "Window sizes",
                if (zooms + splits == 0) "Every app at its normal size"
                else listOfNotNull(zooms.takeIf { it > 0 }?.let { plural(it, "app") + " zoomed" }, splits.takeIf { it > 0 }?.let { plural(it, "split pair") + " remembered" }).joinToString(" · ")
            ) {
                if (zooms + splits > 0) confirm("Reset window sizes?", "Apps go back to their normal zoom and split windows to half and half.") { Prefs.resetWindowMemory() }
            },
            coui.row("What Workbench learned", if (learned == 0) "Nothing yet" else "Usage of " + plural(learned, "app") + ", kept on this phone") {
                if (learned > 0) confirm("Forget what Workbench learned?", "Suggestions start from scratch. Your saved groups stay.") { Usage.reset() }
            },
        )
        dataHost.removeAllViews()
        dataHost.addView(coui.group(*rows.toTypedArray()))
    }

    private fun drawer(): View {
        val on = packageManager.getComponentEnabledSetting(launcherCn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        return coui.group(coui.row("Show in app drawer", null, coui.switch(on) { v ->
            packageManager.setComponentEnabledSetting(
                launcherCn,
                if (v) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }))
    }

    private fun drawerNote(): View =
        note("When hidden, open these settings from the gear in Workbench, by long-pressing the Workbench Quick Settings tile, or from the module's settings in Vector.")

    private fun about(): View {
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        return coui.text("Workbench $v", secondary = true).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 12f
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        coui.dialog(title, message, "OK", "Cancel", onPositive = {
            onYes()
            refreshData()
        }).show()
    }

    private fun header(t: String): View = coui.text(t, secondary = true).apply {
        textSize = 14f
        setPadding(dp(8), dp(10), 0, 0)
    }

    private fun note(t: String): View = coui.text(t, secondary = true).apply {
        textSize = 12f
        setPadding(dp(8), dp(8), dp(8), 0)
    }

    /**
     * A card row holding a title, an optional value line and a COUI slider. It is a kit row with its
     * contents swapped, so it keeps COUI's card background, corners and press state for its place in the group.
     * [summary] (when given) shows the value and follows the thumb.
     */
    private fun sliderRow(title: String, value: Float, summary: ((Int) -> String)?, onChange: (Int) -> Unit): View {
        val start = (value * 100).roundToInt()
        val row = coui.row(title) as ViewGroup
        val titleView = row.findViewById<TextView>(android.R.id.title)
        val sub = coui.text(summary?.invoke(start) ?: "", secondary = true).apply {
            textSize = 13f
            visibility = if (summary == null) View.GONE else View.VISIBLE
        }
        val label = coui.text(title).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleView.textSize)
            typeface = titleView.typeface
        }
        val slider = coui.slider(start, 100, 0, onChange = { v ->
            summary?.let { sub.text = it(v) }
            onChange(v)
        })
        row.removeAllViews()
        row.isClickable = false
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(10))
            addView(label, LinearLayout.LayoutParams(-1, -2))
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun View.summary(s: CharSequence?) {
        findViewById<TextView>(android.R.id.summary)?.apply { text = s; visibility = if (s == null) View.GONE else View.VISIBLE }
    }

    private fun plural(n: Int, w: String) = "$n " + if (n == 1) w else w + "s"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
