package dev.satya.workbench.ui

import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.satya.workbench.bench.Trigger
import dev.satya.workbench.data.Edge
import dev.satya.workbench.data.Prefs
import dev.satya.workbench.service.EdgeService
import dev.satya.workbench.sys.Root
import dev.satya.workbench.sys.Sys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

private val Card = Color(0xFF181A20)
private val Accent = Color(0xFF7FA6FF)

private data class Status(val hook: Boolean, val freeform: Boolean, val a11y: Boolean)

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val edge by Prefs.edge.collectAsState()
    val hSide by Prefs.handleSide.collectAsState()
    val hY by Prefs.handleY.collectAsState()
    val transp by Prefs.transparency.collectAsState()
    val hTransp by Prefs.handleTransparency.collectAsState()
    val launcherCn = remember { ComponentName(ctx, "dev.satya.workbench.Launcher") }
    var inDrawer by remember {
        mutableStateOf(ctx.packageManager.getComponentEnabledSetting(launcherCn) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }
    val hOn by Prefs.handleEnabled.collectAsState()
    var status by remember { mutableStateOf<Status?>(null) }
    var fixLog by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    suspend fun refresh() {
        status = withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            val ff = Settings.Global.getInt(cr, "enable_freeform_support", 0) == 1
            val a11y = EdgeService.running
            Status(Sys.privileged(ctx), ff, a11y)
        }
    }
    LaunchedEffect(tick) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F13))
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Workbench", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Text("Floating app stack for any screen edge.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Accent)
                .clickable { Trigger.enter(ctx) }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) { Text("Open Workbench", color = Color(0xFF0E1630), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        Section("Status") {
            val s = status
            StatusRow("System hook (Vector)", s?.hook, "Enable the Workbench module for System Framework, then reboot")
            StatusRow("Freeform windows", s?.freeform, "Needs enable_freeform_support")
            StatusRow("Edge handle service", s?.a11y, "Accessibility service")
            if (s != null && !(s.freeform && s.a11y)) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.clip(CircleShape).background(Color(0x26FFFFFF)).clickable {
                        scope.launch {
                            fixLog = "Working..."
                            val svc = ComponentName(ctx, EdgeService::class.java).flattenToString()
                            val r = withContext(Dispatchers.IO) {
                                val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                                val list = (cur.split(':').filter { it.isNotBlank() && it != svc } + svc).joinToString(":")
                                Root.run(
                                    "settings put global enable_freeform_support 1",
                                    "settings put global force_resizable_activities 1",
                                    "settings put secure enabled_accessibility_services '" + list + "'",
                                    "settings put secure accessibility_enabled 1"
                                )
                            }
                            fixLog = if (r.code == 0) "Done." else "Root command failed: " + r.out.take(200)
                            tick++
                        }
                    }.padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text("Fix with root", color = Color.White, fontSize = 14.sp) }
                if (fixLog.isNotEmpty()) Text(fixLog, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Section("Strip position") {
            val edges = dev.satya.workbench.bench.Geo.EDGES
            Segmented(edges.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, edges.indexOf(edge).coerceAtLeast(0)) {
                Prefs.setEdge(edges[it])
            }
            Text("You can also drag the settings button at the end of the strip to the other edge.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Section("Background") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transparency", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${(transp * 100).toInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            Slider(
                value = transp, onValueChange = { Prefs.setTransparency(it) },
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
            )
            Text("How much of the wallpaper shows through behind the strip and the main window. 0% is a dark, blurred background; 100% shows the wallpaper as it is.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }

        Section("App drawer") {
            ToggleRow("Show in app drawer", inDrawer) { on ->
                ctx.packageManager.setComponentEnabledSetting(
                    launcherCn,
                    if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                inDrawer = on
            }
            Text("When hidden, open these settings from the gear in Workbench, by long-pressing the Workbench Quick Settings tile, or from the module's settings in Vector.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Section("Edge handle") {
            ToggleRow("Show handle", hOn) { Prefs.setHandleEnabled(it) }
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("Left edge", "Right edge"), if (hSide == Edge.LEFT) 0 else 1) {
                Prefs.setHandleSide(if (it == 0) Edge.LEFT else Edge.RIGHT)
            }
            Spacer(Modifier.height(10.dp))
            Text("Vertical position", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Slider(
                value = hY, onValueChange = { Prefs.setHandleY(it) },
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Handle transparency", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${(hTransp * 100).toInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            Slider(
                value = hTransp, onValueChange = { Prefs.setHandleTransparency(it) },
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
            )
            Text("Swipe inward from the handle to open Workbench. The Quick Settings tile works too.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(22.dp))
    Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(16.dp)) { content() }
}

@Composable
private fun StatusRow(label: String, ok: Boolean?, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Icon(
            if (ok == true) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
            tint = when (ok) { true -> Color(0xFF5FD08A); false -> Color(0xFFFF8A65); null -> Color.Gray },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = Color.White, fontSize = 15.sp)
            if (ok == false) Text(hint, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!value) }) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Composable
private fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Color(0x14FFFFFF)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { i, s ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (sel) Color.White else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(s, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
    }
}
