package dev.satya.workbench.bench

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.animation.core.tween
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
private fun AppCell(app: AppEntry, pinned: Boolean, modifier: Modifier = Modifier, badge: String? = null, onClick: (Rect) -> Unit, onLong: (() -> Unit)?, bench: Bench? = null) {
    val icon = appIcon(app.pkg)
    var rect by remember { mutableStateOf(Rect()) }
    val haptic = LocalHapticFeedback.current
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            .couiPress(press)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClick = { onClick(rect) },
                // With a bench to drop on, the long press belongs to the drag gesture below (still pins if you don't move).
                onLongClick = if (bench != null) null else onLong?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } }
            )
            .then(if (bench == null) Modifier else Modifier.pointerInput(app.key) {
                var moved = false
                var acc = Offset.Zero
                val slop = 14.dp.toPx()
                detectDragGesturesAfterLongPress(
                    onDragStart = { moved = false; acc = Offset.Zero; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDrag = { c, d ->
                        c.consume()
                        acc += d
                        val p = Offset(rect.exactCenterX(), rect.exactCenterY()) + acc
                        if (!moved && acc.getDistance() > slop) { moved = true; bench.beginAppDrag(app, p) }
                        if (moved) bench.appDragAt(p)
                    },
                    onDragEnd = { if (moved) bench.endAppDrag() else onLong?.invoke() },
                    onDragCancel = { if (moved) bench.endAppDrag(cancel = true) }
                )
            })
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
    var editing by remember { mutableStateOf(false) }
    // Entrance cascade only right after the panel opens; cells scrolled into view later just appear.
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val fresh = { android.os.SystemClock.uptimeMillis() - openedAt < 500 }
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.popIn(0, rise = 28.dp, from = 0.94f).padding(top = 10.dp, bottom = 10.dp)) {
            dev.satya.workbench.ui.CouiSearchBar("Search apps", { q = it }, {
                results.firstOrNull()?.let { b.pickApp(it, null) }
            }, Modifier.weight(1f).height(56.dp), onEditing = { editing = it })
            // While typing, the bar's own Cancel takes this spot; the close button folds away.
            androidx.compose.animation.AnimatedVisibility(
                !editing,
                enter = androidx.compose.animation.fadeIn(tween(200)) + androidx.compose.animation.expandHorizontally(tween(260)) + androidx.compose.animation.scaleIn(tween(260), 0.6f),
                exit = androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.shrinkHorizontally(tween(260)) + androidx.compose.animation.scaleOut(tween(200), 0.6f)
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    dev.satya.workbench.ui.CouiRoundButton(Icons.Rounded.Close, "Close", 44.dp) { b.closeModal() }
                }
            }
        }
        if (b.splitPick) Text("Pick an app for the lower half", color = Color(0xFF9BD39B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.popIn(0, fresh()).padding(start = 4.dp, bottom = 8.dp))
        else if (q.isBlank()) Text("Long-press an app to pin it to the strip, or drag it onto the bench", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.popIn(0, fresh()).padding(start = 4.dp, bottom = 8.dp))
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (q.isBlank() && top.isNotEmpty()) {
                item(key = "h:top", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeader("Most used", Modifier.popIn(0, fresh())) }
                    itemsIndexed(top, key = { _, it -> "top:" + it.key + "#" + it.user.hashCode() }) { i, app ->
                    AppCell(app, pins.contains(app.key), Modifier.animateItem().popIn(1 + i / 5 + i % 5, fresh()),
                        onClick = { r -> b.pickApp(app, r) }, onLong = { Prefs.togglePin(app.key) }, bench = b)
                }
                item(key = "h:div", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.popIn(4, fresh(), from = 0.6f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.14f)))
                }
                item(key = "h:all", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeader("All apps", Modifier.popIn(5, fresh())) }
            }
            val base = if (q.isBlank() && top.isNotEmpty()) 6 else 1
            itemsIndexed(results, key = { _, it -> it.key + "#" + it.user.hashCode() }) { i, app ->
                AppCell(app, pins.contains(app.key),
                    // filtering: survivors glide to their new cells, newcomers spring in, the rest shrink away
                    Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.spring(stiffness = 500f),
                        placementSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f),
                        fadeOutSpec = androidx.compose.animation.core.tween(120)
                    ).popIn(base + i / 5 + i % 5, fresh()),
                    onClick = { r -> b.pickApp(app, r) }, onLong = { Prefs.togglePin(app.key) }, bench = b)
            }
        }
    }
}

/** COUI press feedback: the pressed item sinks with a soft spring and bounces back on release (no ripple). */
fun Modifier.couiPress(source: androidx.compose.foundation.interaction.InteractionSource, scale: Float = 0.88f): Modifier = this.then(Modifier.composed {
    val pressed by source.collectIsPressedAsState()
    val s by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) scale else 1f,
        androidx.compose.animation.core.spring(dampingRatio = if (pressed) 1f else 0.55f, stiffness = if (pressed) 900f else 420f),
        label = "press"
    )
    Modifier.graphicsLayer { scaleX = s; scaleY = s }
})

@Composable
private fun GridHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp))
}

@Composable
fun GroupsPanel(b: Bench) {
    val groups by Prefs.groups.collectAsState()
    BackHandler { b.closeModal() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Text("Groups", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            dev.satya.workbench.ui.CouiRoundButton(Icons.Rounded.Close, "Close", 44.dp) { b.closeModal() }
        }
        Text("Open several apps at once. The first app opens in the main window, the rest wait in the strip.", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            dev.satya.workbench.ui.CouiButton("New group", icon = Icons.Rounded.Add) { b.switchModal(Modal.NEW_GROUP) }
            if (b.canSaveCurrent()) dev.satya.workbench.ui.CouiButton("Save open apps", primary = false, icon = Icons.Rounded.Bookmarks) { b.saveCurrentAsGroup() }
        }
        Spacer(Modifier.height(16.dp))
        if (Prefs.suggestions.collectAsState().value) Suggestions(b, groups)
        if (groups.isEmpty()) {
            Text("No groups yet.", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(groups, key = { _, it -> it.id }) { i, g ->
                Box(Modifier.animateItem().popIn(3 + i)) { GroupRow(g, onOpen = { b.launchGroup(g) }, onDelete = { Prefs.deleteGroup(g.id) }, onTrigger = {
                    b.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK)
                    Prefs.saveGroupInPlace(g.copy(trigger = dev.satya.workbench.data.Usage.nextTrigger(g.trigger)))
                }) }
            }
        }
        Text("Strip position", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        dev.satya.workbench.ui.CouiSegments(
            Geo.EDGES.map { e -> e.name.lowercase().replaceFirstChar { it.uppercase() } },
            Geo.EDGES.indexOf(b.edge).coerceAtLeast(0),
            Modifier.padding(bottom = 16.dp).width(220.dp).height(44.dp)
        ) { i -> Geo.EDGES.getOrNull(i)?.let { b.relocateTo(it) } }
    }
}

@Composable
private fun GroupRow(g: AppGroup, onOpen: () -> Unit, onDelete: () -> Unit, onTrigger: () -> Unit = {}) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("" + g.apps.size + " apps  ·  ", color = Dim, fontSize = 12.sp)
                // Tap to cycle: none → headphones → morning → afternoon → evening → night.
                Text(
                    dev.satya.workbench.data.Usage.triggerLabel(g.trigger),
                    color = if (g.trigger.isEmpty()) Dim else Color(0xFF9BD39B), fontSize = 12.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onTrigger).background(Color(0x14FFFFFF)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
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


/** Learned sets: apps you keep opening together, and what you usually open at this hour. */
@Composable
private fun Suggestions(b: Bench, groups: List<AppGroup>) {
    val ver by dev.satya.workbench.data.Usage.version.collectAsState()
    val saved = remember(groups) { groups.map { g -> g.apps.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet() } }
    val installed by Apps.all.collectAsState()
    val have = remember(installed) { installed.map { it.pkg }.toSet() }
    val sugg = remember(ver, saved, have) {
        val out = ArrayList<Pair<List<String>, String>>()
        for ((p, n) in dev.satya.workbench.data.Usage.topPairs()) {
            if (p.all { it in have } && saved.none { it.containsAll(p) }) out += p to "Used together " + n + " times"
        }
        val now = dev.satya.workbench.data.Usage.forNow(3).filter { it in have }
        if (now.size >= 2 && saved.none { it.containsAll(now) } && out.none { it.first.toSet() == now.toSet() })
            out += now to "Usually open in the " + dev.satya.workbench.data.Usage.slotNow()
        out.take(3)
    }
    if (sugg.isEmpty()) return
    Text("Suggested", color = Dim, fontSize = 13.sp, modifier = Modifier.popIn(2).padding(bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
        sugg.forEachIndexed { i, (pkgs, why) ->
            Row(
                Modifier.popIn(3 + i).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0x339BD39B), RoundedCornerShape(20.dp))
                    .background(Color(0x0F9BD39B))
                    .clickable { b.launchApps(pkgs) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width((40 + 22 * (pkgs.size.coerceAtMost(4) - 1)).dp).height(40.dp)) {
                    pkgs.take(4).forEachIndexed { j, p ->
                        val ic = appIcon(p)
                        Box(
                            Modifier.offset((22 * j).dp, 0.dp).size(40.dp).clip(CircleShape).background(Color(0xFF22252D))
                                .border(2.dp, Color(0xFF15171C), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { if (ic != null) Image(ic, null, Modifier.size(32.dp)) }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Text(pkgs.joinToString(" + ") { Apps.label(ctx, it) }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(why, color = Color(0xFF9BD39B), fontSize = 12.sp)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { b.saveSuggestion(pkgs) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Bookmarks, "Save as group", tint = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}
