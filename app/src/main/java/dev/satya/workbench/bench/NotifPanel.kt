package dev.satya.workbench.bench

import android.graphics.Rect
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.satya.workbench.data.Apps
import dev.satya.workbench.data.Notif
import dev.satya.workbench.data.Notifs
import dev.satya.workbench.data.appIcon
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val NDim = Color.White.copy(alpha = 0.6f)

private fun iconBitmap(ctx: android.content.Context, ic: android.graphics.drawable.Icon?): androidx.compose.ui.graphics.ImageBitmap? {
    val d = ic?.let { runCatching { it.loadDrawable(ctx) }.getOrNull() } ?: return null
    val bm = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, 96, 96)
    d.draw(android.graphics.Canvas(bm))
    return bm.asImageBitmap()
}

@Composable
fun NotifPanel(b: Bench) {
    val items by Notifs.items.collectAsState()
    val connected by Notifs.connected.collectAsState()
    BackHandler { b.closeModal() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.popIn(0, rise = 28.dp, from = 0.94f).padding(top = 16.dp, bottom = 4.dp)) {
            Text("Notifications", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            dev.satya.workbench.ui.CouiRoundButton(Icons.Rounded.Close, "Close", 44.dp) { b.closeModal() }
        }
        Text(
            if (connected) "Tap to open it here in the main window. Swipe sideways to dismiss." else "Waiting for notification access…",
            color = NDim, fontSize = 13.sp, modifier = Modifier.popIn(1)
        )
        Spacer(Modifier.height(14.dp))
        if (items.isNotEmpty()) {
            dev.satya.workbench.ui.CouiButton("Clear all", Modifier.popIn(2), primary = false, icon = Icons.Rounded.ClearAll) {
                b.buzz(android.view.HapticFeedbackConstants.REJECT)
                items.forEach { n -> Notifs.dismiss?.invoke(n.key) }
            }
            Spacer(Modifier.height(14.dp))
        } else {
            Text("You're all caught up.", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp, modifier = Modifier.popIn(2).padding(vertical = 12.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(items, key = { _, n -> n.key }) { i, n ->
                Box(Modifier.animateItem().popIn(3 + i)) { NotifRow(b, n) }
            }
        }
    }
}

@Composable
private fun NotifRow(b: Bench, n: Notif) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var w by remember { mutableStateOf(1f) }
    val appIc = appIcon(n.pkg)
    val large: androidx.compose.ui.graphics.ImageBitmap? = remember(n.key) { iconBitmap(ctx, n.largeIcon) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { c ->
                w = c.size.width.toFloat()
                val r = c.boundsInWindow()
                bounds = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
            }
            .graphicsLayer {
                translationX = off.value
                alpha = 1f - (abs(off.value) / w).coerceIn(0f, 0.9f)
            }
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x1AFFFFFF))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> scope.launch { off.snapTo(off.value + d) } },
                onDragStopped = { v ->
                    if (abs(off.value) > w * 0.35f || abs(v) > 1800f) {
                        b.buzz(android.view.HapticFeedbackConstants.GESTURE_END)
                        off.animateTo(if (off.value + v * 0.05f > 0) w * 1.1f else -w * 1.1f, tween(160))
                        Notifs.dismiss?.invoke(n.key)
                    } else off.animateTo(0f, spring(dampingRatio = 0.6f))
                }
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { b.openNotif(n, bounds) }
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(40.dp)) {
            if (large != null) Image(large, null, Modifier.size(40.dp).clip(CircleShape))
            else if (appIc != null) Image(appIc, null, Modifier.size(40.dp))
            if (large != null && appIc != null) Image(appIc, null, Modifier.align(Alignment.BottomEnd).size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Apps.label(ctx, n.pkg), color = NDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  ·  " + DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE), color = NDim, fontSize = 12.sp, maxLines = 1)
            }
            if (n.title.isNotBlank()) Text(n.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.text.isNotBlank()) Text(n.text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
