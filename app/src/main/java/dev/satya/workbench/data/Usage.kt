package dev.satya.workbench.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * What the bench learns from use: which hours each app is opened at, and which apps share a bench session.
 * Kept small (counts only) and local.
 */
object Usage {
    private lateinit var sp: SharedPreferences
    private val hours = HashMap<String, IntArray>()
    private val pairs = HashMap<String, Int>()
    private val sessionPkgs = LinkedHashSet<String>()
    /** Bumped on every change so suggestion UIs recompose. */
    val version = MutableStateFlow(0)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        runCatching {
            val h = JSONObject(sp.getString("hours", "{}")!!)
            for (k in h.keys()) { val a = h.getJSONArray(k); hours[k] = IntArray(24) { a.optInt(it) } }
            val p = JSONObject(sp.getString("pairs", "{}")!!)
            for (k in p.keys()) pairs[k] = p.getInt(k)
        }
    }

    @Synchronized fun used(pkg: String) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hours.getOrPut(pkg) { IntArray(24) }[h]++
        sessionPkgs += pkg
        save()
    }

    /** The bench closed: every pair of apps used in it counts as "used together" once. */
    @Synchronized fun endSession() {
        val l = sessionPkgs.toList().sorted()
        sessionPkgs.clear()
        if (l.size < 2) return
        for (i in l.indices) for (j in i + 1 until l.size) {
            val k = l[i] + "|" + l[j]
            pairs[k] = (pairs[k] ?: 0) + 1
        }
        save()
    }

    private fun save() {
        val h = JSONObject(); for ((k, v) in hours) h.put(k, org.json.JSONArray(v.toList()))
        val p = JSONObject(); for ((k, v) in pairs) p.put(k, v)
        sp.edit().putString("hours", h.toString()).putString("pairs", p.toString()).apply()
        version.value++
    }

    /** Pairs of apps opened together at least [min] times, strongest first. */
    @Synchronized fun topPairs(min: Int = 2, n: Int = 3): List<Pair<List<String>, Int>> =
        pairs.entries.filter { it.value >= min }.sortedByDescending { it.value }.take(n).map { it.key.split('|') to it.value }

    /** Apps usually opened around this hour (±1), most used first. */
    @Synchronized fun forNow(n: Int = 4): List<String> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hours.entries.map { (k, a) -> k to (a[h] * 2 + a[(h + 23) % 24] + a[(h + 1) % 24]) }
            .filter { it.second >= 3 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    // ---------------------------------------------------------------- group triggers

    const val T_NONE = ""
    const val T_HEADPHONES = "headphones"
    val SLOTS = listOf("morning", "afternoon", "evening", "night")

    fun slotNow(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night"
    }

    fun nextTrigger(t: String): String = when (t) {
        T_NONE -> T_HEADPHONES
        T_HEADPHONES -> SLOTS[0]
        else -> SLOTS.getOrNull(SLOTS.indexOf(t) + 1) ?: T_NONE
    }

    fun triggerLabel(t: String): String = when (t) {
        T_NONE -> "No trigger"
        T_HEADPHONES -> "When headphones connect"
        else -> "In the " + t
    }

    fun headphonesOn(ctx: Context): Boolean {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return false
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
    }

    /** The saved group whose trigger matches right now (headphones win over time of day). */
    fun triggeredGroup(ctx: Context, groups: List<AppGroup>): AppGroup? {
        if (groups.isEmpty()) return null
        if (headphonesOn(ctx)) groups.firstOrNull { it.trigger == T_HEADPHONES }?.let { return it }
        val s = slotNow()
        return groups.firstOrNull { it.trigger == s }
    }
}
