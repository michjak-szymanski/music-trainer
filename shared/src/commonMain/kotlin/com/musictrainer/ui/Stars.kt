package com.musictrainer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val StarGold = Color(0xFFF2B705)

/** Ease-out with overshoot: goes a bit past 1 and settles back. */
private val EaseOutBack = Easing { t ->
    val c1 = 2.2f
    val c3 = c1 + 1f
    val u = t - 1f
    1f + c3 * u * u * u + c1 * u * u
}

/** 1–5 star rating; with [animate], the filled stars pop in one after another. */
@Composable
fun StarRow(stars: Int, size: Dp, animate: Boolean = false) {
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Row(
        Modifier.semantics { contentDescription = "$stars of 5 stars" },
        horizontalArrangement = Arrangement.spacedBy(size * 0.15f),
    ) {
        for (i in 0 until 5) {
            val filled = i < stars
            val pop = remember(stars) { Animatable(if (animate && filled) 0f else 1f) }
            if (animate && filled) {
                LaunchedEffect(stars) {
                    // Staggered after the card has popped in; the easing overshoots for a little bounce.
                    pop.animateTo(1f, tween(durationMillis = 450, delayMillis = 250 + i * 160, easing = EaseOutBack))
                }
            }
            Canvas(Modifier.size(size)) {
                val path = starPath(this.size.width / 2f, Offset(this.size.width / 2f, this.size.height / 2f))
                drawPath(path, empty, style = Stroke(width = this.size.width * 0.06f))
                if (filled) scale(pop.value) { drawPath(path, StarGold) }
            }
        }
    }
}

private fun starPath(radius: Float, center: Offset): Path = Path().apply {
    val inner = radius * 0.45f
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) radius * 0.95f else inner
        val angle = -PI / 2 + k * PI / 5
        val p = Offset(center.x + (cos(angle) * r).toFloat(), center.y + (sin(angle) * r).toFloat())
        if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
