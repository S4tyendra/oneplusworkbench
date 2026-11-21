package dev.satya.workbench.bench

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ComponentName
import dev.satya.workbench.data.AppEntry
import dev.satya.workbench.data.AppGroup
import dev.satya.workbench.data.Apps
import dev.satya.workbench.data.Edge
import dev.satya.workbench.data.Prefs
import dev.satya.workbench.data.appIcon
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Dim = Color.White.copy(alpha = 0.6f)

fun matches(label: String, q: String): Int {
    val l = label.lowercase()
    val s = q.trim().lowercase()
    if (s.isEmpty()) return 1
    if (l.startsWith(s)) return 3
    val words = l.split(' ', '-', '_', '.').filter { it.isNotEmpty() }
    if (words.any { it.startsWith(s) }) return 2
    if (words.joinToString("") { it.take(1) }.startsWith(s)) return 2
    if (l.contains(s)) return 1
    return 0
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, fr: FocusRequester? = null, onGo: () -> Unit = {}) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color(0x26FFFFFF))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = Dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().let { if (fr != null) it.focusRequester(fr) else it }
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Rounded.Close, null, tint = Dim, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(app: AppEntry, pinned: Boolean, badge: String? = null, onClick: (Rect) -> Unit, onLong: (() -> Unit)?) {
    val icon = appIcon(app.pkg)
    var rect by remember { mutableStateOf(Rect()) }
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { onClick(rect) },
                onLongClick = onLong?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } }
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Box(
                Modifier
                    .size(52.dp)
                    .onGloballyPositioned { c ->
                        val r = c.boundsInWindow()
                        rect = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                    }
            ) { if (icon != null) Image(icon, null, Modifier.fillMaxSize()) }
            if (pinned) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(18.dp).clip(CircleShape).background(Color(0xFF3A3F4B)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.PushPin, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
            }
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).size(22.dp).clip(CircleShape).background(Color(0xFF5B8CFF)),
                    contentAlignment = Alignment.Center
                ) { Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun SearchPanel(b: Bench) {
    val apps by Apps.all.collectAsState()
    val pins by Prefs.pinned.collectAsState()
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val results = remember(q, apps) {
        if (q.isBlank()) apps
        else apps.map { it to matches(it.label, q) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }
    // Results change under the grid; keep it at the top so the best match is always visible.
    LaunchedEffect(q) { grid.requestScrollToItem(0) }
    BackHandler { b.closeModal() }
    // No auto-focus: the keyboard opens only when the search field is tapped.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val top by androidx.compose.runtime.produceState(emptyList<AppEntry>(), apps) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Apps.topUsed(ctx, 10) }
    }
    // The most-used rows arrive after the grid is shown; the grid anchors to its first visible app, so jump back up.
    LaunchedEffect(top) { if (q.isBlank()) grid.requestScrollToItem(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp)) {
            SearchField(q, { q = it }, "Search apps", Modifier.weight(1f), fr) {
                results.firstOrNull()?.let { b.openApp(it.component, it.user, null) }
            }
            Spacer(Modifier.width(6.dp))
            Text("Cancel", color = Color.White, fontSize = 15.sp, modifier = Modifier.clip(CircleShape).clickable { b.closeModal() }.padding(10.dp))
        }
        if (q.isBlank()) Text("Long-press an app to pin it to the strip", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (q.isBlank() && top.isNotEmpty()) {
                item(key = "h:top", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeader("Most used") }
                items(top, key = { "top:" + it.key + "#" + it.user.hashCode() }) { app ->
                    AppCell(app, pins.contains(app.key), onClick = { r -> b.openApp(app.component, app.user, r) }, onLong = { Prefs.togglePin(app.key) })
                }
                item(key = "h:div", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.14f)))
                }
                item(key = "h:all", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeader("All apps") }
            }
            items(results, key = { it.key + "#" + it.user.hashCode() }) { app ->
                AppCell(app, pins.contains(app.key), onClick = { r -> b.openApp(app.component, app.user, r) }, onLong = { Prefs.togglePin(app.key) })
            }
        }
    }
}

@Composable
private fun GridHeader(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp))
}

@Composable
fun GroupsPanel(b: Bench) {
    val groups by Prefs.groups.collectAsState()
    BackHandler { b.closeModal() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Text("Groups", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x26FFFFFF)).clickable { b.closeModal() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = Color.White)
            }
        }
        Text("Open several apps at once. The first app opens in the main window, the rest wait in the strip.", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(Icons.Rounded.Add, "New group") { b.switchModal(Modal.NEW_GROUP) }
            if (b.canSaveCurrent()) PillButton(Icons.Rounded.Bookmarks, "Save open apps") { b.saveCurrentAsGroup() }
        }
        Spacer(Modifier.height(16.dp))
        if (groups.isEmpty()) {
            Text("No groups yet.", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(groups, key = { it.id }) { g -> GroupRow(g, onOpen = { b.launchGroup(g) }, onDelete = { Prefs.deleteGroup(g.id) }) }
        }
        Text("Strip position", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            for (e in Geo.EDGES) {
                val sel = e == b.edge
                Text(
                    e.name.lowercase().replaceFirstChar { it.uppercase() },
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (sel) Color.White else Color(0x26FFFFFF))
                        .clickable { b.relocateTo(e) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupRow(g: AppGroup, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x1AFFFFFF))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width((40 + 22 * (g.apps.size.coerceAtMost(4) - 1)).dp).height(40.dp)) {
            g.apps.take(4).forEachIndexed { i, k ->
                val pkg = ComponentName.unflattenFromString(k)?.packageName
                val ic = appIcon(pkg)
                Box(
                    Modifier.offset((22 * i).dp, 0.dp).size(40.dp).clip(CircleShape).background(Color(0xFF22252D))
                        .border(2.dp, Color(0xFF15171C), CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (ic != null) Image(ic, null, Modifier.size(32.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(g.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("" + g.apps.size + " apps", color = Dim, fontSize = 12.sp)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Dim)
        }
    }
}

@Composable
fun NewGroupPanel(b: Bench) {
    val apps by Apps.all.collectAsState()
    var name by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }
    val results = remember(q, apps) {
        if (q.isBlank()) apps else apps.map { it to matches(it.label, q) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }
    BackHandler { b.switchModal(Modal.GROUPS) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { b.switchModal(Modal.GROUPS) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
            }
            Text("New group", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
            val ok = sel.size >= 2
            Text(
                "Save",
                color = if (ok) Color.Black else Color.White.copy(alpha = 0.35f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (ok) Color.White else Color(0x1AFFFFFF))
                    .clickable(enabled = ok) {
                        val label = name.ifBlank {
                            sel.take(2).joinToString(" + ") { k -> ComponentName.unflattenFromString(k)?.let { cn -> Apps.find(cn)?.label } ?: "App" } +
                                (if (sel.size > 2) " +" + (sel.size - 2) else "")
                        }
                        Prefs.saveGroup(AppGroup("g" + System.currentTimeMillis(), label, sel.toList()))
                        b.switchModal(Modal.GROUPS)
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(CircleShape).background(Color(0x26FFFFFF)).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) Text("Group name (optional)", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SearchField(q, { q = it }, "Find apps", Modifier.fillMaxWidth())
        Text(
            if (sel.isEmpty()) "Pick 2 or more apps. Number 1 opens in the main window." else "" + sel.size + " selected",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp)
        )
        LazyVerticalGrid(columns = GridCells.Fixed(4), contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.weight(1f)) {
            items(results, key = { it.key + "#" + it.user.hashCode() }) { app ->
                val idx = sel.indexOf(app.key)
                AppCell(app, false, badge = if (idx >= 0) "" + (idx + 1) else null, onClick = { if (!sel.remove(app.key)) sel.add(app.key) }, onLong = null)
            }
        }
    }
}
