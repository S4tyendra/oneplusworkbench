package dev.satya.workbench.bench

import android.content.ClipDescription
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.satya.workbench.data.Shelf
import dev.satya.workbench.data.ShelfItem

private val Accent = Color(0xFF9BD39B)

/** The Shelf laid out along the strip: compact tiles, newest first (copied text and images land here on their own). */
@Composable
fun ShelfList(b: Bench, vertical: Boolean, tile: Dp, tileLong: Dp) {
    val items by Shelf.items.collectAsState()
    val pad = PaddingValues(8.dp)
    val content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        itemsIndexed(items, key = { _, s -> s.id }) { i, s -> ShelfTile(b, s, tile, tileLong, Modifier.animateItem().popIn(minOf(i, 6))) }
        if (items.isEmpty()) item(key = "hint") {
            Box(if (vertical) Modifier.width(tile).height(tileLong * 2.2f).padding(2.dp) else Modifier.fillParentMaxWidth(0.7f).height(tileLong).padding(6.dp), contentAlignment = Alignment.Center) {
                Text("Copy something, or drag it here from any app", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
            }
        }
    }
    if (vertical) LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    else LazyRow(Modifier.fillMaxSize(), contentPadding = pad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** What a tile says it is: where text came from, or the kind of file. */
private fun ShelfItem.tag(): String = when {
    kind == "text" -> if (src == "clip") "CLIPBOARD" else "TEXT"
    isImage -> "IMG"
    else -> name.substringAfterLast('.', "FILE").uppercase().take(5)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfTile(b: Bench, s: ShelfItem, w: Dp, h: Dp, modifier: Modifier) {
    val ctx = LocalContext.current
    val thumb = remember(s.id) { if (s.isImage) Shelf.thumb(s)?.asImageBitmap() else null }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(w, h)
            .clip(shape)
            .background(Color(0xFF1B1D23))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .clickable { b.shelfActionsFor = if (b.shelfActionsFor == s.id) null else s.id }
            // Long-press and drag: the item goes into whatever app it is dropped on. Inside clickable, which
            // would otherwise consume the down before the drag source sees it.
            .dragAndDropSource { _ ->
                b.buzz(android.view.HapticFeedbackConstants.DRAG_START)
                b.shelfActionsFor = null
                DragAndDropTransferData(
                    Shelf.clip(ctx, s),
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
            }
    ) {
        when {
            thumb != null -> Image(thumb, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            s.kind == "text" -> Text(s.text.trim(), color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 20.dp))
            else -> Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(s.name, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 11.sp)
            }
        }
        // Kind label along the bottom, on a scrim so it reads over photos too.
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 7.dp, bottom = 5.dp, top = 8.dp)
        ) { Text(s.tag(), color = if (s.src == "clip" || s.isImage) Accent else Color.White.copy(alpha = 0.7f), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, lineHeight = 9.sp, maxLines = 1) }
        AnimatedVisibility(
            visible = b.shelfActionsFor == s.id,
            enter = fadeIn(tween(120)), exit = fadeOut(tween(110)) + scaleOut(tween(140), targetScale = 0.9f)
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xD8000000)), contentAlignment = Alignment.Center) {
                val pop = { i: Int -> Modifier.popIn(i, step = 40, rise = 8.dp, from = 0.3f, damping = 0.52f, stiffness = 520f) }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MiniBtn(Icons.Rounded.ContentCopy, pop(0)) { Shelf.copy(ctx, s); b.buzz(android.view.HapticFeedbackConstants.CONFIRM); b.shelfActionsFor = null }
                        MiniBtn(Icons.Rounded.MoveToInbox, pop(1)) { b.sendToFileDock(listOf(s)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MiniBtn(Icons.Rounded.Share, pop(2)) { b.shelfActionsFor = null; Shelf.share(ctx, s) }
                        MiniBtn(Icons.Rounded.DeleteOutline, pop(3), Color(0xCCE5484D)) { b.buzz(android.view.HapticFeedbackConstants.REJECT); b.shelfActionsFor = null; Shelf.remove(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBtn(icon: ImageVector, modifier: Modifier, bg: Color = Color(0x40FFFFFF), onClick: () -> Unit) {
    Box(modifier.size(26.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/**
 * Always-composed drop zones over the strip (a target created mid-drag would never hear about that drag).
 * Invisible and touch-transparent until something is dragged anywhere on screen.
 */
@Composable
fun DropZones(b: Bench, vertical: Boolean, modifier: Modifier) {
    val vis by animateFloatAsState(if (b.dropActive) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "drop")
    val arr = Arrangement.spacedBy(10.dp)
    // One zone: ColorOS already shows its own FileDock target at the screen edge during any drag.
    val zones: @Composable (Modifier) -> Unit = { m -> Zone(b, Icons.Rounded.Inbox, "Drop to Shelf", false, m) }
    Box(modifier.graphicsLayer { alpha = vis; val s = 0.94f + 0.06f * vis; scaleX = s; scaleY = s }) {
        if (vertical) Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = arr) { zones(Modifier.weight(1f).fillMaxWidth()) }
        else Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = arr) { zones(Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun Zone(b: Bench, icon: ImageVector, label: String, fileDock: Boolean, modifier: Modifier) {
    var hover by remember { mutableStateOf(false) }
    val h by animateFloatAsState(if (hover) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "hover")
    val target = remember(b) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { b.dropActive = true }
            override fun onEnded(event: DragAndDropEvent) { b.dropActive = false; hover = false }
            override fun onEntered(event: DragAndDropEvent) { hover = true; b.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK) }
            override fun onExited(event: DragAndDropEvent) { hover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover = false
                return b.acceptDrop(event.toAndroidDragEvent(), fileDock)
            }
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .graphicsLayer { val s = 1f + 0.05f * h; scaleX = s; scaleY = s }
            .clip(shape)
            .background(Color(0xFF101216).copy(alpha = 0.92f))
            .background(Accent.copy(alpha = 0.10f + 0.25f * h))
            .border((1.5f + 1f * h).dp, Accent.copy(alpha = 0.5f + 0.5f * h), shape)
            .dragAndDropTarget(shouldStartDragAndDrop = { e -> !e.toAndroidDragEvent().clipDescription.let { it != null && it.label == "Shelf" } }, target = target),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}
