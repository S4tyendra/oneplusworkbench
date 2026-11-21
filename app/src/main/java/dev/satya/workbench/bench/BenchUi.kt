package dev.satya.workbench.bench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.TransformOrigin
import android.graphics.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.satya.workbench.data.Edge
import dev.satya.workbench.data.Prefs
import dev.satya.workbench.data.appIcon
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

val Surface = Color(0xFF1B1D24)

/** Place a node at an absolute window rect (px). */
fun Modifier.at(r: Rect): Modifier = this
    .offset { IntOffset(r.left, r.top) }
    .layout { measurable, _ ->
        val p = measurable.measure(Constraints.fixed(r.width().coerceAtLeast(0), r.height().coerceAtLeast(0)))
        layout(p.width, p.height) { p.place(0, 0) }
    }

@Composable
fun pxDp(px: Int): Dp = with(LocalDensity.current) { px.toDp() }

@Composable
fun BenchUi(b: Bench) {
    val d = LocalDensity.current
    val transparency by Prefs.transparency.collectAsState()
    // Opacity 0.5 (the default) is the original look: full blur, 35% tint. Clearer cuts the blur, then the tint.
    val opacity = 1f - transparency
    LaunchedEffect(Unit) {
        val maxBlur = with(d) { 36.dp.toPx() }
        snapshotFlow {
            // Search / groups panels always get the full blur behind them, whatever the transparency setting.
            val k = if (b.modal != null) 1f else ((1f - Prefs.transparency.value) * 2f).coerceAtMost(1f)
            (b.reveal.value * maxBlur * k / 6f).roundToInt() * 6
        }
            .collect { b.setBlur(it) }
    }
    // Blur the whole bench (strip, main window, buttons) under the search / groups panels.
    val panelBlur by androidx.compose.animation.core.animateDpAsState(if (b.modal != null) 28.dp else 0.dp, tween(220), label = "panelBlur")
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().then(if (panelBlur > 0.dp) Modifier.blur(panelBlur) else Modifier)) {
        // Dim + tap-to-exit backdrop
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = b.reveal.value }
                .background(Color.Black.copy(alpha = 0.7f * opacity))
                .cleanTap { b.onBackdropTap() }
        )
        MainArea(b)
        if (b.mainTask != null) MainHandle(b)
        Strip(b)
        if (b.edge.vertical) TopButtons(b)
        if (b.relocPointer != null) RelocateTargets(b)
        FlyerLayer(b)
      }
        Panels(b)
    }
}


@Composable
private fun MainArea(b: Bench) {
    val g = b.geo
    val img = b.mainImage
    val inFlight = b.flyers.isNotEmpty()
    Box(
        Modifier
            .at(g.main)
            .graphicsLayer {
                alpha = b.reveal.value
                shape = RoundedCornerShape(b.mainRadius)
                clip = true
            }
    ) {
        when {
            img != null -> SnapImage(b, img, g.main, Modifier.fillMaxSize())
            b.mainPkg != null -> if (!inFlight) Splash(b.mainPkg)
            !inFlight -> EmptyMain(b)
        }
    }
}

@Composable
private fun Splash(pkg: String?) {
    val icon = appIcon(pkg)
    Box(Modifier.fillMaxSize().background(Surface), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.size(64.dp))
    }
}

@Composable
private fun EmptyMain(b: Bench) {
    val groups by Prefs.groups.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x24FFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(with(LocalDensity.current) { b.mainRadius.toDp() })),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Workbench", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.popIn(0, step = 60))
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap a card to bring an app here,\nor search for any app.",
                color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.popIn(1, step = 60)
            )
            Spacer(Modifier.height(20.dp))
            dev.satya.workbench.ui.CouiButton("Search apps", Modifier.popIn(2, step = 60), icon = Icons.Rounded.Search) { b.openModal(Modal.SEARCH) }
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                dev.satya.workbench.ui.CouiButton("Groups", primary = false, icon = Icons.Rounded.Layers) { b.openModal(Modal.GROUPS) }
            }
        }
    }
}

@Composable
fun PillButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MainHandle(b: Bench) {
    val g = b.geo
    val d = LocalDensity.current
    Box(
        Modifier
            .at(g.handle)
            .graphicsLayer { alpha = b.reveal.value },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            b.handleMenu,
            transitionSpec = {
                (fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.66f, stiffness = 520f), initialScale = 0.35f)) togetherWith
                    (fadeOut(tween(90)) + androidx.compose.animation.scaleOut(tween(120), targetScale = 0.35f)) using
                    androidx.compose.animation.SizeTransform(clip = false)
            },
            label = "handle"
        ) { menu ->
            if (!menu) {
                var dy by remember { mutableStateOf(0f) }
                var dx by remember { mutableStateOf(0f) }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.7f)
                        .pointerInput(Unit) { detectTapGestures(onTap = { b.handleMenu = true }) }
                        .pointerInput(Unit) {
                            val lim = with(d) { 36.dp.toPx() }
                            // Down: minimize, up: full screen, sideways: previous / next app.
                            detectDragGestures(
                                onDragStart = { dy = 0f; dx = 0f },
                                onDragEnd = {
                                    when {
                                        kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > lim -> b.quickSwitch(if (dx < 0) 1 else -1)
                                        dy > lim -> b.minimize()
                                        dy < -lim -> b.expand()
                                    }
                                    dy = 0f; dx = 0f
                                },
                                onDragCancel = { dy = 0f; dx = 0f }
                            ) { c, delta -> c.consume(); dy += delta.y; dx += delta.x }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                translationY = (dy * 0.25f).coerceIn(-24f, 24f)
                                translationX = (dx * 0.35f).coerceIn(-60f, 60f)
                            }
                            .size(44.dp, 5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f))
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val hp = { i: Int -> Modifier.popIn(i, step = 35, rise = 6.dp, from = 0.4f, damping = 0.55f, stiffness = 600f) }
                    HandleBtn(Icons.Rounded.Remove, hp(0)) { b.minimize() }
                    HandleBtn(Icons.Rounded.OpenInFull, hp(1)) { b.expand() }
                    HandleBtn(if (b.isMainPinned()) Icons.Rounded.PushPin else Icons.Outlined.PushPin, hp(2)) { b.togglePinMain() }
                    HandleBtn(Icons.Rounded.Close, hp(3)) { b.closeMain() }
                }
            }
        }
    }
}

@Composable
private fun HandleBtn(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0x40FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
}

@Composable
private fun Strip(b: Bench) {
    val g = b.geo
    val e = b.edge
    val pins by Prefs.pinned.collectAsState()
    val entries by remember(pins) { derivedStateOf { b.entries(pins) } }
    val slide = (if (e.vertical) g.strip.width() else g.strip.height()).toFloat()
    val sign = if (e == Edge.LEFT || e == Edge.TOP) -1f else 1f
    Box(
        Modifier
            .at(g.strip)
            .graphicsLayer {
                val t = 1f - b.reveal.value
                if (e.vertical) translationX = sign * slide * t else translationY = sign * slide * t
                alpha = b.reveal.value
            }
    ) {
        if (e.vertical) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                LazyColumn(
                    state = b.listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().fadeEdges(b, true),
                    contentPadding = PaddingValues(vertical = pxDp(g.listPad)),
                    verticalArrangement = Arrangement.spacedBy(pxDp(g.gap)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(entries, key = { it.key }) { StripItem(b, it, Modifier.animateItem()) }
                }
                Grip(b, Modifier.align(Alignment.Start).width(pxDp(g.stripVisible)).height(pxDp(g.gripLen)))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().width(pxDp(g.headerLen)), contentAlignment = Alignment.Center) { StripHeader(b) }
                LazyRow(
                    state = b.listState,
                    modifier = Modifier.weight(1f).fillMaxHeight().fadeEdges(b, false),
                    contentPadding = PaddingValues(horizontal = pxDp(g.listPad)),
                    horizontalArrangement = Arrangement.spacedBy(pxDp(g.gap)),
                    verticalAlignment = Alignment.Top
                ) {
                    items(entries, key = { it.key }) { StripItem(b, it, Modifier.padding(top = pxDp(g.crossPad)).animateItem()) }
                }
                Column(
                    Modifier.fillMaxHeight().width(pxDp(g.gripLen)),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.requiredSize(46.dp), contentAlignment = Alignment.Center) { BellBtn(b, 42.dp) }
                    Grip(b, Modifier.requiredSize(46.dp))
                }
            }
        }
    }
}

/** Side layout: search and groups side by side at the very top, above the main window. */
@Composable
private fun TopButtons(b: Bench) {
    val g = b.geo
    Row(
        Modifier
            .at(g.header)
            .graphicsLayer {
                alpha = b.reveal.value
                translationY = -g.header.height() * (1f - b.reveal.value) * 0.5f
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundBtn(Icons.Rounded.Search) { b.openModal(Modal.SEARCH) }
        RoundBtn(Icons.Rounded.Layers) { b.openModal(Modal.GROUPS) }
        BellBtn(b)
    }
}

/** Notifications button with the total unread count on it. */
@Composable
private fun BellBtn(b: Bench, size: androidx.compose.ui.unit.Dp = 46.dp) {
    val counts by dev.satya.workbench.data.Notifs.counts.collectAsState()
    val total = counts.values.sum()
    Box {
        dev.satya.workbench.ui.CouiRoundButton(Icons.Rounded.Notifications, "Notifications", size) { b.openModal(Modal.NOTIFS) }
        TotalBadge(total, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun TotalBadge(n: Int, modifier: Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = n > 0, modifier = modifier,
        enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f)) + fadeIn(),
        exit = androidx.compose.animation.scaleOut(tween(150)) + fadeOut(tween(150))
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape).background(Color(0xFFE5484D))
                .border(1.5.dp, Color(0xFF0B0C10), CircleShape).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp) }
    }
}

@Composable
private fun StripHeader(b: Bench) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RoundBtn(Icons.Rounded.Search) { b.openModal(Modal.SEARCH) }
        RoundBtn(Icons.Rounded.Layers) { b.openModal(Modal.GROUPS) }
    }
}

/** Unread notifications of [pkg]: a red count bubble that pops in (and bumps when the count grows). */
@Composable
private fun NotifBadge(pkg: String, modifier: Modifier) {
    val counts by dev.satya.workbench.data.Notifs.counts.collectAsState()
    val n = counts[pkg] ?: 0
    val bump = remember { Animatable(1f) }
    var last by remember { mutableStateOf(n) }
    LaunchedEffect(n) {
        if (n > last) { bump.snapTo(1.35f); bump.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f)) }
        last = n
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = n > 0,
        modifier = modifier,
        enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f)) + fadeIn(),
        exit = androidx.compose.animation.scaleOut(tween(150)) + fadeOut(tween(150))
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .clip(CircleShape)
                .background(Color(0xFFE5484D))
                .border(1.5.dp, Color(0xFF0B0C10), CircleShape)
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, onClick: () -> Unit) {
    dev.satya.workbench.ui.CouiRoundButton(icon, null, 46.dp, onClick = onClick)
}

@Composable
private fun StripItem(b: Bench, e: StripEntry, modifier: Modifier) {
    val g = b.geo
    val vertical = b.edge.vertical
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val off = remember { Animatable(0f) }
    val tid = e.task?.taskId
    val snap = tid?.let { b.snaps[it] }
    LaunchedEffect(tid) { if (tid != null) b.ensureSnapshot(tid) }
    val icon = appIcon(e.pkg)
    val shape = RoundedCornerShape(with(LocalDensity.current) { b.cardRadius.toDp() })
    val toEdge = if (b.edge == Edge.LEFT || b.edge == Edge.TOP) -1f else 1f
    // Swap animation: the card crossfades between its preview and the plain icon card, with a small dip in scale.
    val activeT by animateFloatAsState(if (e.active) 1f else 0f, tween(300, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)), label = "active")
    val pulse = remember { Animatable(1f) }
    var lastActive by remember { mutableStateOf(e.active) }
    LaunchedEffect(e.active) {
        if (e.active != lastActive) {
            lastActive = e.active
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(300))
        }
    }
    Column(
        modifier.width(pxDp(g.cardW)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(pxDp(g.cardW), pxDp(g.cardH))
                .onGloballyPositioned { c ->
                    val r = c.boundsInWindow()
                    b.cardRects[e.key] = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                }
                .graphicsLayer {
                    if (vertical) translationX = off.value else translationY = off.value
                    val s = 1f - 0.07f * kotlin.math.sin(Math.PI.toFloat() * pulse.value)
                    scaleX = s
                    scaleY = s
                    alpha = 1f - (abs(off.value) / (g.cardW * 1.6f)).coerceIn(0f, 0.95f)
                    // Perspective tilt: the edge nearest the screen border stays put, the far edge recedes.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = g.tilt
                    cameraDistance = CAMERA_DP * density
                }
                .shadow(8.dp, shape)
                .clip(shape)
                .background(Surface)
                .pointerInput(e.key) {
                    detectTapGestures(
                        onTap = { b.onCardTap(e) },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            b.actionsFor = e.key
                        }
                    )
                }
                .draggable(
                    orientation = if (vertical) Orientation.Horizontal else Orientation.Vertical,
                    state = rememberDraggableState { delta -> scope.launch { off.snapTo(off.value + delta) } },
                    onDragStopped = { v ->
                        val lim = (if (vertical) g.cardW else g.cardH) * 0.45f
                        if (abs(off.value) > lim || abs(v) > 1800f) {
                            val s = if (off.value != 0f) sign(off.value) else (if (v != 0f) sign(v) else toEdge)
                            off.animateTo(s * g.cardW * 2.2f, tween(150))
                            b.closeEntry(e)
                        } else off.animateTo(0f, spring())
                    }
                )
        ) {
            if (activeT < 1f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - activeT }) {
            if (snap != null) {
                SnapImage(b, snap, g.slot(0), Modifier.fillMaxSize())
                if (icon != null) Image(
                    icon, null,
                    Modifier.align(Alignment.TopEnd)
                        .padding(top = 7.dp, end = pxDp(g.cardW - g.cardVisible) + 7.dp)
                        .size(26.dp)
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (icon != null) Image(icon, null, Modifier.size(40.dp))
                }
            }
            }
            if (activeT > 0f) {
                // The app in the main window keeps its place in the row as a plain card with its icon.
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = activeT }.background(Color(0xFF0B0C10))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
                    contentAlignment = Alignment.Center
                ) { if (icon != null) Image(icon, null, Modifier.size(40.dp)) }
            }
            NotifBadge(e.pkg, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = pxDp(g.cardW - g.cardVisible) + 4.dp))
            if (e.pinned) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(5.dp).size(17.dp).clip(CircleShape).background(Color(0xB3000000)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.PushPin, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = b.actionsFor == e.key,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(110)) + androidx.compose.animation.scaleOut(tween(140), targetScale = 0.9f)
            ) { CardActions(b, e) }
        }
    }
}

@Composable
private fun CardActions(b: Bench, e: StripEntry) {
    Box(Modifier.fillMaxSize().background(Color(0xC0000000)), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val pop = Modifier
            ActionBtn(Icons.Rounded.OpenInFull, Color(0x40FFFFFF), pop.popIn(0, step = 45, rise = 10.dp, from = 0.3f, damping = 0.52f, stiffness = 520f)) { b.actionsFor = null; b.openFull(e) }
            ActionBtn(if (e.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, Color(0x40FFFFFF), pop.popIn(1, step = 45, rise = 10.dp, from = 0.3f, damping = 0.52f, stiffness = 520f)) {
                b.togglePin(e); b.actionsFor = null
            }
            if (e.task != null) ActionBtn(Icons.Rounded.Close, Color(0xCCE5484D), pop.popIn(2, step = 45, rise = 10.dp, from = 0.3f, damping = 0.52f, stiffness = 520f)) { b.closeEntry(e) }
        }
    }
}

@Composable
private fun ActionBtn(icon: ImageVector, bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(32.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp)) }
}

@Composable
private fun Grip(b: Bench, modifier: Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Drags are taken in the Initial pass, before the COUI button sees them (its press then cancels);
            // a plain tap goes through to the button, which opens settings.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragging = false
                    var moved = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        if (c == null) { if (dragging) b.endRelocate(cancel = true); break }
                        if (!c.pressed) { if (dragging) { c.consume(); b.endRelocate() }; break }
                        if (!dragging) {
                            moved += c.positionChange()
                            if (moved.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                b.beginRelocate(origin + c.position)
                            }
                        }
                        if (dragging) { c.consume(); b.relocPointer = origin + c.position }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        dev.satya.workbench.ui.CouiRoundButton(Icons.Rounded.Settings, "Settings (drag to move strip)", 42.dp) { b.openSettings() }
    }
}

@Composable
private fun RelocateTargets(b: Bench) {
    val p = b.relocPointer ?: return
    val target = b.nearestEdge(p)
    var lastTarget by remember { mutableStateOf(target) }
    if (target != lastTarget) { lastTarget = target; b.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK) }
    val rects = remember(b.geo) { Geo.EDGES.associateWith { b.geoFor(it).strip } }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0x66000000))
        for ((e, r) in rects) {
            val sel = e == target
            val inset = 6.dp.toPx()
            val tl = Offset(r.left + inset, r.top + inset)
            val sz = Size(r.width() - 2 * inset, r.height() - 2 * inset)
            drawRoundRect(Color.White.copy(alpha = if (sel) 0.30f else 0.08f), tl, sz, CornerRadius(20.dp.toPx()))
            if (sel) drawRoundRect(Color.White.copy(alpha = 0.9f), tl, sz, CornerRadius(20.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        drawCircle(Color.White, 10.dp.toPx(), p)
    }
}

private const val CAMERA_DP = 6f

@Composable
private fun FlyerLayer(b: Bench) {
    val d = LocalDensity.current
    val iconSize = with(d) { 64.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        for (f in b.flyers) key(f) { FlyerView(b, f, iconSize) }
    }
}

/** One flyer on its own full-window layer, so it can tilt in perspective around the same pivot a strip card uses. */
@Composable
private fun FlyerView(b: Bench, f: Flyer, iconSize: Float) {
    val vertical = b.edge.vertical
    val tilt0 = remember(f) { b.tiltOf(f.from) }
    val tilt1 = remember(f) { b.tiltOf(f.to) }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (tilt0 == 0f && tilt1 == 0f) return@graphicsLayer
                val t = f.p.value
                val l = lerp(f.from.left.toFloat(), f.to.left.toFloat(), t)
                val tp = lerp(f.from.top.toFloat(), f.to.top.toFloat(), t)
                val r = lerp(f.from.right.toFloat(), f.to.right.toFloat(), t)
                val bt = lerp(f.from.bottom.toFloat(), f.to.bottom.toFloat(), t)
                // Cards turn about their left edge, on both strip edges.
                transformOrigin = TransformOrigin(l / size.width, (tp + bt) / 2f / size.height)
                rotationY = lerp(tilt0, tilt1, t)
                cameraDistance = CAMERA_DP * density
            }
    ) { drawFlyer(f, iconSize) }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun DrawScope.drawFlyer(f: Flyer, iconSize: Float) {
    val t = f.p.value
    val l = lerp(f.from.left.toFloat(), f.to.left.toFloat(), t)
    val tp = lerp(f.from.top.toFloat(), f.to.top.toFloat(), t)
    val r = lerp(f.from.right.toFloat(), f.to.right.toFloat(), t)
    val bt = lerp(f.from.bottom.toFloat(), f.to.bottom.toFloat(), t)
    val w = r - l
    val h = bt - tp
    if (w < 1f || h < 1f) return
    val rad = lerp(f.r0, f.r1, t)
    val alpha = lerp(1f, f.alphaTo, t).coerceIn(0f, 1f)
    val path = Path().apply { addRoundRect(RoundRect(l, tp, r, bt, CornerRadius(rad))) }
    clipPath(path) {
        val img = f.image
        if (img != null) {
            // Fit width and anchor to the top. Only the bottom is cropped while the shape morphs,
            // which reads like a window resizing rather than a zoom.
            // Same aspect ratio everywhere: fit the width, anchor to the top.
            val s = w / img.width
            val srcH = (h / s).roundToInt().coerceIn(1, img.height)
            drawImage(
                img,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(img.width, srcH),
                dstOffset = IntOffset(l.roundToInt(), tp.roundToInt()),
                dstSize = IntSize(w.roundToInt(), (srcH * s).roundToInt()),
                alpha = alpha,
                filterQuality = FilterQuality.Low
            )
        } else {
            drawRect(Surface, Offset(l, tp), Size(w, h), alpha = alpha)
            val ic = f.icon
            if (ic != null) {
                val sz = minOf(iconSize, w * 0.6f, h * 0.6f)
                drawImage(
                    ic,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(ic.width, ic.height),
                    dstOffset = IntOffset((l + (w - sz) / 2).roundToInt(), (tp + (h - sz) / 2).roundToInt()),
                    dstSize = IntSize(sz.roundToInt(), sz.roundToInt()),
                    alpha = alpha
                )
            }
        }
    }
}

@Composable
private fun Panels(b: Bench) {
    var last by remember { mutableStateOf<Modal?>(null) }
    if (b.modal != null) last = b.modal
    AnimatedVisibility(
        visible = b.modal != null,
        enter = fadeIn(tween(160)) +
            androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it / 16 } +
            scaleIn(spring(dampingRatio = 0.82f, stiffness = 420f), initialScale = 0.93f),
        exit = fadeOut(tween(150)) +
            androidx.compose.animation.slideOutVertically(tween(180)) { it / 28 } +
            androidx.compose.animation.scaleOut(tween(180), targetScale = 0.96f)
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xB30B0C10)).pointerInput(Unit) { detectTapGestures { } }) {
            when (last) {
                Modal.SEARCH -> SearchPanel(b)
                Modal.GROUPS -> GroupsPanel(b)
                Modal.NEW_GROUP -> NewGroupPanel(b)
                Modal.NOTIFS -> NotifPanel(b)
                null -> {}
            }
        }
    }
}


/** Soft fade where the list runs under its ends; the start only fades once the list is scrolled, so slot 0 stays crisp. */
private fun Modifier.fadeEdges(b: Bench, vertical: Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val len = 28.dp.toPx()
        val total = if (vertical) size.height else size.width
        if (total <= 2 * len) return@drawWithContent
        val f = len / total
        val head = if (b.listState.canScrollBackward) Color.Transparent else Color.Black
        val tail = if (b.listState.canScrollForward) Color.Transparent else Color.Black
        val stops = arrayOf(0f to head, f to Color.Black, (1f - f) to Color.Black, 1f to tail)
        val brush = if (vertical) Brush.verticalGradient(*stops) else Brush.horizontalGradient(*stops)
        drawRect(brush, blendMode = BlendMode.DstIn)
    }


@Composable
private fun SnapImage(b: Bench, img: androidx.compose.ui.graphics.ImageBitmap, asRect: android.graphics.Rect, modifier: Modifier) {
    Image(img, null, modifier, contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter)
}
