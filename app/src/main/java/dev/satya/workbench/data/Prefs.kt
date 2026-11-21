package dev.satya.workbench.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class Edge {
    LEFT, TOP, RIGHT, BOTTOM;
    val vertical: Boolean get() = this == LEFT || this == RIGHT
}

data class AppGroup(val id: String, val name: String, val apps: List<String>, val trigger: String = "")

object Prefs {
    private lateinit var sp: SharedPreferences

    val edge = MutableStateFlow(Edge.LEFT)
    val handleSide = MutableStateFlow(Edge.RIGHT)
    val handleY = MutableStateFlow(0.32f)
    val handleEnabled = MutableStateFlow(true)
    val compact = MutableStateFlow(true)
    /** Workbench background: 0 = solid dark, 1 = wallpaper fully visible (no blur, no tint). */
    val transparency = MutableStateFlow(0.5f)
    /** Edge handle pill: 0 = fully visible, 1 = invisible (still swipeable). */
    val handleTransparency = MutableStateFlow(0f)
    val pinned = MutableStateFlow<List<String>>(emptyList())
    val groups = MutableStateFlow<List<AppGroup>>(emptyList())
    val haptics = MutableStateFlow(true)
    /** Unread counts on strip cards and the bell. */
    val badges = MutableStateFlow(true)
    /** Learned app pairs and time / headphone group suggestions on the empty bench and in Groups. */
    val suggestions = MutableStateFlow(true)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("workbench", Context.MODE_PRIVATE)
        edge.value = when (sp.getString("edge", "LEFT")) { "TOP", "BOTTOM" -> Edge.TOP; else -> Edge.LEFT }
        handleSide.value = if (sp.getString("handleSide", "RIGHT") == "LEFT") Edge.LEFT else Edge.RIGHT
        handleY.value = sp.getFloat("handleY", 0.32f)
        handleEnabled.value = sp.getBoolean("handleEnabled", true)
        compact.value = sp.getBoolean("compact", true)
        transparency.value = sp.getFloat("transparency", 0.5f)
        handleTransparency.value = sp.getFloat("handleTransparency", 0f)
        pinned.value = sp.getString("pinned", "")!!.split('|').filter { it.isNotBlank() }
        groups.value = parseGroups(sp.getString("groups", "[]")!!)
        haptics.value = sp.getBoolean("haptics", true)
        badges.value = sp.getBoolean("badges", true)
        suggestions.value = sp.getBoolean("suggestions", true)
    }

    /** Per-app content zoom in the main window (1 = laid out exactly as full screen). */
    fun zoom(pkg: String?): Float = if (pkg == null) 1f else sp.getFloat("zoom:" + pkg, 1f)
    fun setZoom(pkg: String, z: Float) { sp.edit().apply { if (z == 1f) remove("zoom:" + pkg) else putFloat("zoom:" + pkg, z) }.apply() }
    /** Divider position remembered for an upper/lower pair of apps. */
    fun splitRatio(top: String?, bottom: String?): Float? =
        if (top == null || bottom == null) null else sp.getFloat("split:$top|$bottom", -1f).takeIf { it > 0f }
    fun setSplitRatio(top: String, bottom: String, r: Float) { sp.edit().putFloat("split:$top|$bottom", r).apply() }

    fun setHaptics(on: Boolean) { haptics.value = on; sp.edit().putBoolean("haptics", on).apply() }
    fun setBadges(on: Boolean) { badges.value = on; sp.edit().putBoolean("badges", on).apply() }
    fun setSuggestions(on: Boolean) { suggestions.value = on; sp.edit().putBoolean("suggestions", on).apply() }

    /** Apps with their own zoom, and app pairs with a remembered divider. */
    fun windowMemory(): Pair<Int, Int> = sp.all.keys.let { k -> k.count { it.startsWith("zoom:") } to k.count { it.startsWith("split:") } }
    fun resetWindowMemory() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("zoom:") || k.startsWith("split:")) e.remove(k)
        e.apply()
    }

    fun setEdge(e: Edge) { if (e != Edge.LEFT && e != Edge.TOP) return; edge.value = e; sp.edit().putString("edge", e.name).apply() }
    fun setHandleSide(e: Edge) { handleSide.value = e; sp.edit().putString("handleSide", e.name).apply() }
    fun setHandleY(y: Float) { handleY.value = y; sp.edit().putFloat("handleY", y).apply() }
    fun setHandleEnabled(b: Boolean) { handleEnabled.value = b; sp.edit().putBoolean("handleEnabled", b).apply() }
    fun setTransparency(v: Float) { transparency.value = v.coerceIn(0f, 1f); sp.edit().putFloat("transparency", transparency.value).apply() }
    fun setHandleTransparency(v: Float) { handleTransparency.value = v.coerceIn(0f, 1f); sp.edit().putFloat("handleTransparency", handleTransparency.value).apply() }
    fun setCompact(b: Boolean) { compact.value = b; sp.edit().putBoolean("compact", b).apply() }

    fun isPinned(key: String) = pinned.value.contains(key)
    fun togglePin(key: String) {
        val l = pinned.value.toMutableList()
        if (!l.remove(key)) l.add(key)
        pinned.value = l
        sp.edit().putString("pinned", l.joinToString("|")).apply()
    }

    fun saveGroup(g: AppGroup) {
        val l = groups.value.filter { it.id != g.id } + g
        setGroups(l)
    }
    /** Update a group without moving it to the end of the list. */
    fun saveGroupInPlace(g: AppGroup) = setGroups(groups.value.map { if (it.id == g.id) g else it })
    fun deleteGroup(id: String) = setGroups(groups.value.filter { it.id != id })
    private fun setGroups(l: List<AppGroup>) {
        groups.value = l
        val arr = JSONArray()
        for (g in l) arr.put(JSONObject().put("id", g.id).put("name", g.name).put("apps", JSONArray(g.apps)).put("trigger", g.trigger))
        sp.edit().putString("groups", arr.toString()).apply()
    }
    private fun parseGroups(s: String): List<AppGroup> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.getJSONArray("apps")
            AppGroup(o.getString("id"), o.getString("name"), (0 until a.length()).map { a.getString(it) }, o.optString("trigger", ""))
        }
    }.getOrDefault(emptyList())

    /** Task ids currently turned into floating windows by Workbench (for crash recovery). */
    var session: Set<Int>
        get() = sp.getString("session", "")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet()
        set(v) {
            val ok = sp.edit().putString("session", v.joinToString(",")).commit()
            if (!ok) android.util.Log.w("Workbench", "session not saved")
        }
}
