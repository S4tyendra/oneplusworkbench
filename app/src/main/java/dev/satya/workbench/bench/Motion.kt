package dev.satya.workbench.bench

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Staggered entrance: the element rises [rise], grows from [from] and fades in on a soft spring, [step] ms
 * after the one before it. With [enabled] false it just shows (items scrolled into view later shouldn't replay).
 */
fun Modifier.popIn(
    index: Int,
    enabled: Boolean = true,
    step: Int = 22,
    rise: Dp = 20.dp,
    from: Float = 0.88f,
    damping: Float = 0.74f,
    stiffness: Float = 340f,
): Modifier = composed {
    val p = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) {
            delay(index.coerceIn(0, 24) * step.toLong())
            p.animateTo(1f, spring(dampingRatio = damping, stiffness = stiffness))
        }
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * risePx
        val s = from + (1f - from) * v
        scaleX = s; scaleY = s
    }
}
