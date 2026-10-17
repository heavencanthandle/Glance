package com.wanderwildwood.hitome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.wanderwildwood.hitome.Moon
import com.wanderwildwood.hitome.terminatorK
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moon for the utilities row. Black ink is the sunlit part and the paper is the
 * shadow, so the dark side of the moon is the light part of the icon. A waxing moon
 * is inked on the right.
 *
 * Filling the shadow instead reads backwards: the side that is dark in the sky comes
 * out as the dark mark on the page.
 */
@Composable
fun MoonGlyph(phase: Moon.Phase, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.dp.toPx().coerceAtLeast(1f)
        val r = (size.minDimension - stroke) / 2f
        if (r <= 0f) return@Canvas
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = phase.illumination
        if (t >= FULL) {
            drawCircle(Color.Black, r, c)
            return@Canvas
        }
        if (t > NEW) {
            drawPath(limbPath(c, r, terminatorK(t), phase.waxing), Color.Black)
        }
        drawCircle(Color.Black, r, c, style = Stroke(stroke))
    }
}

/** The right limb, top to bottom, then the terminator back. [waxing] false mirrors it. */
private fun limbPath(c: Offset, r: Float, k: Float, waxing: Boolean): Path {
    val side = if (waxing) 1f else -1f
    val path = Path()
    for (i in 0..STEPS) {
        val phi = Math.PI * i / STEPS
        val x = c.x + side * sin(phi).toFloat() * r
        val y = c.y - cos(phi).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (i in STEPS downTo 0) {
        val phi = Math.PI * i / STEPS
        val x = c.x + side * k * sin(phi).toFloat() * r
        val y = c.y - cos(phi).toFloat() * r
        path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Below this the lit sliver is a hair, so the moon is an empty ring. */
private const val NEW = 0.02f

/** Above this the shadow is a hair, so the moon is a black disk. */
private const val FULL = 0.98f

private const val STEPS = 64
