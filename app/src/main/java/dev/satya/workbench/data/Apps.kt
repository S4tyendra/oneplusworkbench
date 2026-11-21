package dev.satya.workbench.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(val component: ComponentName, val label: String, val user: UserHandle) {
    val key: String = component.flattenToString()
    val pkg: String get() = component.packageName
}

object Apps {
    val all = MutableStateFlow<List<AppEntry>>(emptyList())
    private val icons = LruCache<String, ImageBitmap>(400)
    private val labels = ConcurrentHashMap<String, String>()

    fun refresh(ctx: Context) {
        runCatching {
            val la = ctx.getSystemService(LauncherApps::class.java)
            val list = la.profiles.flatMap { u ->
                la.getActivityList(null, u).map { AppEntry(it.componentName, it.label.toString(), u) }
            }.filter { it.pkg != ctx.packageName }.sortedBy { it.label.lowercase() }
            all.value = list
            list.forEach { labels.putIfAbsent(it.pkg, it.label) }
        }
    }

    /** Most used launchable apps over the last two weeks (needs usage access; empty without it). */
    fun topUsed(ctx: Context, n: Int): List<AppEntry> = runCatching {
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(now - 14L * 24 * 3600 * 1000, now)
        if (stats.isEmpty()) {
            // Usage access missing: grant it to ourselves with root (Workbench is root-only anyway) and retry once.
            dev.satya.workbench.sys.Root.run("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        }
        val s2 = if (stats.isEmpty()) usm.queryAndAggregateUsageStats(now - 14L * 24 * 3600 * 1000, now) else stats
        val byPkg = all.value.filter { it.user == android.os.Process.myUserHandle() }.groupBy { it.pkg }
        s2.values.asSequence()
            .filter { it.totalTimeInForeground > 0 && byPkg.containsKey(it.packageName) }
            .sortedByDescending { it.totalTimeInForeground }
            .mapNotNull { byPkg[it.packageName]?.first() }
            .take(n).toList()
    }.onFailure { android.util.Log.w("Workbench", "topUsed", it) }.getOrDefault(emptyList())

    fun find(cn: ComponentName): AppEntry? =
        all.value.firstOrNull { it.component == cn } ?: all.value.firstOrNull { it.pkg == cn.packageName }

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg } ?: prettyPkg(pkg)
    }

    /** Last resort for packages that hide from PackageManager: com.foo.bar -> Foo. */
    private fun prettyPkg(pkg: String): String {
        val parts = pkg.split('.').filter { it.isNotBlank() }
        val generic = setOf("com", "org", "net", "io", "dev", "app", "android", "google", "apps", "ultra", "pro", "lite", "free", "mobile")
        val pick = parts.drop(1).firstOrNull { it.lowercase() !in generic } ?: parts.lastOrNull() ?: pkg
        return pick.replaceFirstChar { it.uppercase() }
    }

    fun cachedIcon(pkg: String): ImageBitmap? = icons.get(pkg)

    fun icon(ctx: Context, pkg: String): ImageBitmap? {
        icons.get(pkg)?.let { return it }
        val px = (56 * ctx.resources.displayMetrics.density).toInt()
        val bmp = runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            d.toBitmap(px, px).asImageBitmap()
        }.getOrNull() ?: return null
        icons.put(pkg, bmp)
        return bmp
    }

    val myUser: UserHandle get() = Process.myUserHandle()
}

@Composable
fun appIcon(pkg: String?): ImageBitmap? {
    val ctx = LocalContext.current
    val v by produceState(pkg?.let { Apps.cachedIcon(it) }, pkg) {
        if (value == null && pkg != null) value = withContext(Dispatchers.IO) { Apps.icon(ctx, pkg) }
    }
    return v
}
